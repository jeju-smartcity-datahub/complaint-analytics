package jeju.datahub.batch.cvlcpt;

import jeju.datahub.batch.history.ExcnSe;
import jeju.datahub.batch.risknoti.RiskAlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * 매월 1일 02:00(Asia/Seoul) ① 민원 수집 → 성공 시 ③ 위험단계 판정 (계획서 11-3,
 * {@code PR-BAT-002-01} · {@code IF-CVLCPT-001} · TSK-4069 Step 7).
 *
 * <pre>
 * 02:00  ① complaintFetchJob(daily, 전일 하루치) — 수집·분류·지오코딩·적재를 한 Job 이 끝낸다
 *          ├─COMPLETED──▶ ③ riskAlertJudgeJob(targetDate = 어제) — 읍면동별 증가율 → 위험단계 판정
 *          └─FAILED─────▶ 중단(③을 돌리지 않는다)
 * </pre>
 *
 * <p>🔁 2026-08-24 — 원본(staging) 폐지로 ②{@code cvlcptGeocodeJob} 은 사라졌다(수집·가공이 한 Job).
 * 2026-08-26 그 자리에 ③{@code riskAlertJudgeJob} 이 붙어 <b>다시 2단 체인</b>이 됐다.
 *
 * <h2>🔴 ①이 성공했을 때만 ③을 돌린다</h2>
 * ③은 {@code parking.tb_cvlcpt} 의 <b>당일·전일 민원 건수</b>를 읍면동으로 묶어 증가율을 계산한다.
 * ①이 실패하면 그 날짜 구간이 부분만 적재된 상태라, 그대로 ③을 돌리면 <b>덜 찬 건수로 증가율이
 * 계산돼</b> 알림이 통째로 어긋난다 — 게다가 그 결과가 {@code tb_risk_stp_noti_hstry} 에 정상
 * 판정으로 남는다. 나중에 ①을 다시 돌려도 ③을 재실행하지 않으면 잘못된 판정이 그대로 굳는다.
 *
 * <h2>🔴 고정 시각 지연(02:10 같은 것)을 쓰지 않는다</h2>
 * ①은 전량 실행이 <b>1~2시간</b>까지 걸린다. 10분 뒤에 판정하면 당일 민원이 덜 적재된 상태로
 * 증가율이 계산되므로, 별도 스케줄러가 아니라 <b>①의 종료를 기다리는 체인</b>으로 붙였다.
 *
 * <h2>🔁 언제 도나 — {@code batch.tb_btch_schdl} 이 정한다 (TSK-4337 Step 3)</h2>
 * 이 클래스에는 {@code @Scheduled}·{@code @EnableScheduling} 이 없다. {@code BatchScheduleRegistry} 가 표의
 * cron·사용여부로 {@link #syncMonthly()} 를 걸고, 등록 여부·다음 실행은 기동 로그 {@code [schedule] registered job=complaintFetchJob}
 * 로 본다. {@link #CRON} 은 표 초기값(마이그레이션 104)과 같은 <b>참고용</b> 상수다.
 *
 * <h2>on/off</h2>
 * {@code complaint.sync.scheduled=false} 면 이 설정 클래스 자체가 빠진다 — 레지스트리가 빈 부재를
 * <b>env 상위 차단</b>으로 보고 표 값과 무관하게 등록하지 않는다({@code [schedule] blocked}, D5).
 * 수동 트리거({@link ComplaintBatchTriggerController})는 이 값과 무관하게 그대로 쓸 수 있다.
 *
 * <p>③만 끄려면 {@code risknoti.sync.scheduled=false}({@code RISKNOTI_SYNC_SCHEDULED}) 다.
 * 🔴 이건 <b>클래스 조건이 아니라 메서드 안 분기</b>다 — {@code @ConditionalOnProperty} 를 하나 더
 * 붙이면 조건이 AND 로 묶여 <b>①까지 함께 꺼진다.</b> 그래서 ③ 플래그만 {@code @Value} 로 받는다.
 */
@Configuration
@ConditionalOnProperty(name = "complaint.sync.scheduled", havingValue = "true", matchIfMissing = true)
public class ComplaintSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ComplaintSyncScheduler.class);

    /** 초기값 참고용(정본은 {@code batch.tb_btch_schdl}) — 매월 1일 02:00. 하루가 완결된 뒤 <b>전일</b>을 읽는다(당일을 읽으면 22시간분이 영구 누락된다). */
    static final String CRON = "0 0 2 1 * *";

    static final String ZONE = "Asia/Seoul";

    private final JobLauncher jobLauncher;
    private final Job complaintFetchJob;
    private final Job riskAlertJudgeJob;

    /**
     * ③ on/off (TSK-4069 D11 · 기본 {@code true}).
     *
     * <p>🔴 <b>{@code @ConditionalOnProperty} 를 하나 더 붙일 수 없다</b> — 클래스에 이미
     * {@code complaint.sync.scheduled} 가 걸려 있어 조건을 추가하면 <b>①까지 함께 꺼진다.</b>
     * 그래서 ③ 플래그만 {@code @Value} 로 받아 {@link #syncMonthly()} 안에서 분기한다.
     */
    private final boolean riskAlertScheduled;

    public ComplaintSyncScheduler(
            JobLauncher jobLauncher, Job complaintFetchJob,
            Job riskAlertJudgeJob,                                   // 빈 이름으로 주입된다(①과 동일)
            @Value("${risknoti.sync.scheduled:true}") boolean riskAlertScheduled) {
        this.jobLauncher = jobLauncher;
        this.complaintFetchJob = complaintFetchJob;
        this.riskAlertJudgeJob = riskAlertJudgeJob;
        this.riskAlertScheduled = riskAlertScheduled;
    }

    /**
     * ① → (COMPLETED 일 때만) ③.
     *
     * <p>①의 Job 파라미터는 {@code runAt} 하나만 넣는다 — {@code mode}/{@code targetDate} 를 비우면
     * Reader가 원천 MAX(load_dt)부터 오늘까지의 구간을 스스로 정한다. 스케줄러가 날짜를
     * 계산해 넘기면 그 로직이 Reader 와 두 벌이 된다.
     *
     * <p>🔴 <b>③은 정반대다 — {@code targetDate} 를 항상 넣는다</b>(D1·D11). ③은 Reader 와
     * Processor <b>둘 다</b> 판정일을 필요로 해서, 비워 두면 각자 "어제" 를 계산해 자정 근처에서
     * 하루가 갈릴 수 있다. 실행 이력에 "무슨 날짜를 판정했는지" 를 남기는 효과도 있다.
     *
     * <p>예외를 밖으로 던지지 않는다 — {@code TaskScheduler} 는 예외가 나가면 로그만 남기므로
     * 여기서 잡아 <b>"③을 왜 안 돌렸는지"</b> 를 분명히 남기는 편이 진단에 낫다. ③의 실패도
     * 삼킨다 — upsert 라 다음 실행이 같은 날짜를 재판정하면 그만이다.
     */
    public void syncMonthly() {
        JobExecution fetch;
        try {
            fetch = jobLauncher.run(complaintFetchJob, ExcnSe.tag(new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis()), ExcnSe.SCHDL)
                    .toJobParameters());
        } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
                | JobParametersInvalidException | DataAccessException | TransactionException
                | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — JobLauncher.run 의 선언 예외 4종 + JobRepository(JDBC·트랜잭션) + 인자·상태 예외.
            // (동기 런처는 Job 내부 실패를 예외가 아니라 JobExecution 상태로 돌려준다 — 여기 오는 것은 실행 전·저장소 실패다.)
            // ⚠️ 예외 메시지에 응답 본문·자격증명이 실리지 않는 것은 클라이언트 계층의 계약이다
            //    (DhubQueryClient / ComplaintApiClient 주석). 여기서는 그대로 옮기기만 한다.
            log.error("complaintFetchJob 실행 실패 — 다음 실행이 같은 구간을 다시 받습니다(upsert).", e);
            return;
        }

        if (fetch.getStatus() != BatchStatus.COMPLETED
                || !ExitStatus.COMPLETED.getExitCode().equals(fetch.getExitStatus().getExitCode())) {
            log.error("complaintFetchJob 이 {}({}) 로 끝났습니다 — 적재분이 불완전할 수 있어 "
                            + "다음 실행 또는 수동 재실행이 필요합니다.",
                    fetch.getStatus(), fetch.getExitStatus().getExitCode());
            return;
        }

        log.info("complaintFetchJob COMPLETED — 읽음={} 씀={}", readCount(fetch), writeCount(fetch));

        // ▼ ③ 여기부터 신규(TSK-4069) — ①이 COMPLETED 일 때만 도달한다.
        //    (위 이중 검사를 통과하지 못하면 이미 return 됐으므로 fetch 를 다시 보지 않는다)
        if (!riskAlertScheduled) {
            log.info("③ riskAlertJudgeJob 은 risknoti.sync.scheduled=false 라 건너뜁니다.");
            return;
        }
        try {
            // 🔴 컨트롤러와 같게 — 여기서 '어제'를 확정해 targetDate 를 **항상** 넣는다(D1·D11).
            //    비워 두면 Reader 와 Processor 가 각자 어제를 계산해 자정 근처에서 하루가 갈릴 수 있다.
            String targetDate = RiskAlertProperties.resolveTargetDate(null).toString();
            // TSK-4337 — 연쇄 실행은 CHAIN + 앞 잡(①) 실행 ID 를 비식별로 싣는다.
            JobExecution judge = jobLauncher.run(riskAlertJudgeJob, ExcnSe.chain(new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis())
                    .addString("targetDate", targetDate), fetch.getId()).toJobParameters());
            log.info("③ riskAlertJudgeJob 종료 — targetDate={} status={} 씀={}",
                    targetDate, judge.getStatus(), writeCount(judge));
        } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
                | JobParametersInvalidException | DataAccessException | TransactionException
                | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — JobLauncher.run 의 선언 예외 4종 + JobRepository(JDBC·트랜잭션) + 인자·상태 예외.
            // (동기 런처는 Job 내부 실패를 예외가 아니라 JobExecution 상태로 돌려준다 — 여기 오는 것은 실행 전·저장소 실패다.)
            log.error("③ riskAlertJudgeJob 실행 실패 — 다음 실행이 같은 날짜를 재판정한다(upsert 라 안전).", e);
        }
    }

    private static long readCount(JobExecution execution) {
        return execution.getStepExecutions().stream().mapToLong(s -> s.getReadCount()).sum();
    }

    private static long writeCount(JobExecution execution) {
        return execution.getStepExecutions().stream().mapToLong(s -> s.getWriteCount()).sum();
    }
}
