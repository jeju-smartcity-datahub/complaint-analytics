package jeju.datahub.batch.rptp;

import jeju.datahub.batch.history.ExcnSe;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * 매일 02:30(Asia/Seoul) ④ 보고서 자동 생성 (TSK-4027 Step 9-3 · D11).
 *
 * <pre>
 * 02:00  ① complaintFetchJob → (성공 시) ③ riskAlertJudgeJob   (ComplaintSyncScheduler)
 * 02:30  ④ reportAutoCrtJob(baseYmd = 오늘) — 배포일이 도래한 설정만 보고서를 만든다
 * </pre>
 *
 * <h2>왜 02:00 체인에 붙이지 않고 02:30 인가</h2>
 * ④는 ①의 적재 결과를 <b>그날 바로</b> 쓰지 않는다 — 대상기간이 언제나 <b>직전 구간</b>
 * (전월·전분기·전반기·전년)이라 오늘 새벽에 들어온 민원이 집계에 끼지 않는다. 그래서 ①의 종료를
 * 기다리는 체인이 아니라 독립 스케줄이고, ①이 실패해도 ④는 자기 일을 한다.
 * 다만 같은 시각에 겹치면 DB·백엔드가 함께 붐비므로 30분을 띄웠다.
 *
 * <h2>🔁 언제 도나 — {@code batch.tb_btch_schdl} 이 정한다 (TSK-4337 Step 3)</h2>
 * 이 클래스에는 {@code @Scheduled}·{@code @EnableScheduling} 이 없다. {@code BatchScheduleRegistry} 가 표의
 * cron·사용여부로 {@link #syncDaily()} 를 걸고, 등록 여부·다음 실행은 기동 로그 {@code [schedule] registered job=reportAutoCrtJob}
 * 로 본다. {@link #CRON} 은 표 초기값(마이그레이션 104)과 같은 <b>참고용</b> 상수다.
 *
 * <h2>on/off</h2>
 * {@code rptp.sync.scheduled=false}({@code RPTP_SYNC_SCHEDULED})면 이 설정 클래스 자체가 빠진다 —
 * 레지스트리가 빈 부재를 <b>env 상위 차단</b>으로 보고 표 값과 무관하게 등록하지 않는다({@code [schedule] blocked}, D5).
 * 수동 트리거({@link ReportAutoCrtController})는 이 값과 무관하게 그대로 쓸 수 있다.
 * 🔴 플래그는 <b>최상위 {@code rptp.sync.scheduled}</b> 다 — 선례 3건
 * ({@code complaint.sync.scheduled}·{@code pblpklt.sync.scheduled}·{@code risknoti.sync.scheduled})이
 * 전부 최상위다. 반대로 청크 키는 {@code batch.rptp.chunk-size} 그대로다
 * ({@code batch.{도메인}.chunk-size} 관례).
 */
@Configuration
@ConditionalOnProperty(name = "rptp.sync.scheduled", havingValue = "true", matchIfMissing = true)
public class ReportAutoCrtScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportAutoCrtScheduler.class);

    /**
     * 초기값 참고용(정본은 {@code batch.tb_btch_schdl}) — 매일 02:30. ①·③(02:00)과 30분 띄운다.
     *
     * <p>🔁 종전 {@code rptp.sync.cron} 덮어쓰기는 폐기됐다(TSK-4337 Step 3) — 낮 시간 확인은 표의 cron 을 바꾼다.
     */
    static final String CRON = "0 30 2 * * *";

    /** 🔴 기준일 계산과 같은 시간대여야 한다 — {@link ReportAutoCrtJobConfig#ZONE} 한 곳이 정본이다. */
    static final String ZONE = ReportAutoCrtJobConfig.ZONE;

    private final JobLauncher jobLauncher;
    private final Job reportAutoCrtJob;

    public ReportAutoCrtScheduler(JobLauncher jobLauncher, Job reportAutoCrtJob) {
        this.jobLauncher = jobLauncher;
        this.reportAutoCrtJob = reportAutoCrtJob;
    }

    /**
     * ④ 보고서 자동 생성.
     *
     * <p>🔴 <b>컨트롤러와 같게 — 여기서 '오늘' 을 확정해 {@code baseYmd} 를 항상 넣는다.</b>
     * 비워 두면 Processor 와 Writer 가 각자 오늘을 계산해 자정 근처에서 하루가 갈리고,
     * {@link ReportDueDateResolver#parseBaseYmd(String)} 가 아예 예외로 끊는다.
     *
     * <p>예외를 밖으로 던지지 않는다 — {@code TaskScheduler} 는 예외가 나가면 로그만 남기므로
     * 여기서 잡아 원인을 분명히 남기는 편이 진단에 낫다. ⚠️ 다만 <b>실패한 날의 배포는 다음 실행이
     * 대신 해 주지 않는다</b> — 배포일이 지나가 버리면 그 주기는 도래 판정에서 빠진다. 그래서
     * 실패 로그는 ERROR 이고, 복구는 {@code POST /api/batch/report-auto-crt?baseYmd=그날} 수동
     * 재실행이다(백엔드가 멱등이라 이미 만들어진 보고서는 200 으로 돌아온다).
     */
    public void syncDaily() {
        String baseYmd = LocalDate.now(ReportAutoCrtJobConfig.SEOUL).toString();
        try {
            JobExecution execution = jobLauncher.run(reportAutoCrtJob, ExcnSe.tag(new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis()), ExcnSe.SCHDL)
                    .addString("baseYmd", baseYmd)
                    .toJobParameters());
            log.info("④ reportAutoCrtJob 종료 — baseYmd={} status={} 읽음={} 씀={}",
                    baseYmd, execution.getStatus(), readCount(execution), writeCount(execution));
        } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
                | JobParametersInvalidException | DataAccessException | TransactionException
                | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — JobLauncher.run 의 선언 예외 4종 + JobRepository(JDBC·트랜잭션) + 인자·상태 예외.
            // (동기 런처는 Job 내부 실패를 예외가 아니라 JobExecution 상태로 돌려준다 — 여기 오는 것은 실행 전·저장소 실패다.)
            log.error("④ reportAutoCrtJob 실행 실패 — baseYmd={}. 배포일이 지나면 다음 실행이 대신"
                    + " 만들지 않으므로 같은 baseYmd 로 수동 재실행이 필요합니다"
                    + " (POST /api/batch/report-auto-crt?baseYmd={}).", baseYmd, baseYmd, e);
        }
    }

    private static long readCount(JobExecution exec) {
        return exec.getStepExecutions().stream().mapToLong(s -> s.getReadCount()).sum();
    }

    private static long writeCount(JobExecution exec) {
        return exec.getStepExecutions().stream().mapToLong(s -> s.getWriteCount()).sum();
    }
}
