package jeju.datahub.batch.pblpklt;

import jeju.datahub.batch.history.ExcnSe;

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
 * 분기 첫날 02:00(Asia/Seoul) ① 수집 → 성공 시 ② 가공 (가이드 12-2, 12-3).
 *
 * <pre>
 * 02:00  pblpkltFetchJob(분기) — 수집·ID배정·지오코딩·소멸마킹을 한 Job 이 끝낸다
 *
 * <p>🔁 2026-08-24 — 원본(staging) 폐지로 ④{@code pblpkltProcessJob} 이 사라졌다.
 *                              └─FAILED─────▶  중단(②를 돌리지 않는다)
 * </pre>
 *
 * <h2>분기 첫날 02:00 — 매 분기 (1/1, 4/1, 7/1, 10/1) 새벽</h2>
 * cvlcpt 의 매일 02:00 과 다르다. 공영주차장 조사는 분기 단위로 진행된다(2026-08-12 결정).
 * 정확한 날짜·시각은 제안값이다 — 가이드 12-2 의 ⚠️ 참고.
 *
 * <h2>🔴 ①이 성공했을 때만 ②를 돌린다</h2>
 * cvlcpt 와 동일 근거. ① 실패 시 부분 적재 상태로 ②를 돌리면 불완전한 수집분이 완전한 것처럼
 * 가공되고, ②의 {@code NOT EXISTS} 가 적재된 행을 영구 제외해 빠진 행이 메워지지 않는다.
 *
 * <h2>on/off</h2>
 * {@code pblpklt.sync.scheduled=false} 면 이 설정 클래스 자체가 빠진다(= env 상위 차단). 수동 트리거
 * ({@link PblpkltProcessTriggerController}, {@link PblpkltFetchController})는 그대로 쓸 수 있다.
 *
 * <p>🔁 언제 도나는 {@code batch.tb_btch_schdl} 이 정한다(TSK-4337 Step 3) — {@code @Scheduled}·{@code @EnableScheduling} 은
 * 걷혔고 {@code BatchScheduleRegistry} 가 {@link #syncQuarterly()} 를 건다(기동 로그 {@code [schedule] registered job=pblpkltFetchJob}).
 */
@Configuration
@ConditionalOnProperty(name = "pblpklt.sync.scheduled", havingValue = "true", matchIfMissing = true)
public class PblpkltSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PblpkltSyncScheduler.class);

    /**
     * 초기값 참고용(정본은 {@code batch.tb_btch_schdl}) — 분기 첫날 02:00. cron 식은 {@code 초 분 시 일 월 요일}.
     * 일=1, 월=1,4,7,10 → 1월·4월·7월·10월 1일. 분기 4개가 돌아가며 한 해를 채운다.
     */
    static final String CRON = "0 0 2 1 1,4,7,10 *";

    static final String ZONE = "Asia/Seoul";

    private final JobLauncher jobLauncher;
    private final Job pblpkltFetchJob;
    private final Job pkltUtztnFetchJob;
    private final boolean utztnChained;

    public PblpkltSyncScheduler(
            JobLauncher jobLauncher,
            Job pblpkltFetchJob,
            Job pkltUtztnFetchJob,
            // 🔴 클래스 @ConditionalOnProperty 에 이 키를 추가하지 않는다 — 이미 pblpklt.sync.scheduled 가
            //    걸려 있어 조건을 하나 더 붙이면 **주차장 수집까지 함께 꺼진다**. @Value 로 받아 메서드에서
            //    분기한다(risknoti.sync.scheduled 가 ComplaintSyncScheduler 에서 그렇게 처리된다).
            // 🔴 플래그를 필드 @Value 가 아니라 **생성자 파라미터**로 받는다 — 스프링 컨텍스트 없이
            //    new PblpkltSyncScheduler(...) 로 분기를 단언하는 테스트가 가능해야 한다
            //    (ComplaintSyncSchedulerTest 와 같은 형태).
            @Value("${utztn.sync.scheduled:true}") boolean utztnChained) {
        this.jobLauncher = jobLauncher;
        this.pblpkltFetchJob = pblpkltFetchJob;
        this.pkltUtztnFetchJob = pkltUtztnFetchJob;
        this.utztnChained = utztnChained;
    }

    public void syncQuarterly() {
        JobExecution fetch;
        try {
            fetch = jobLauncher.run(pblpkltFetchJob, ExcnSe.tag(new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis()), ExcnSe.SCHDL)
                    .toJobParameters());
        } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
                | JobParametersInvalidException | DataAccessException | TransactionException
                | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — JobLauncher.run 의 선언 예외 4종 + JobRepository(JDBC·트랜잭션) + 인자·상태 예외.
            // (동기 런처는 Job 내부 실패를 예외가 아니라 JobExecution 상태로 돌려준다 — 여기 오는 것은 실행 전·저장소 실패다.)
            log.error("pblpkltFetchJob 실행 실패 — 다음 실행이 전량을 다시 받습니다(upsert).", e);
            return;
        }

        if (fetch.getStatus() != BatchStatus.COMPLETED
                || !ExitStatus.COMPLETED.getExitCode().equals(fetch.getExitStatus().getExitCode())) {
            log.error("pblpkltFetchJob 이 {}({}) 로 끝났습니다 — 적재분이 불완전할 수 있어 "
                            + "다음 실행 또는 수동 재실행이 필요합니다. "
                            + "(소멸 마킹 Tasklet 은 이 경우 스스로 물러난다)",
                    fetch.getStatus(), fetch.getExitStatus().getExitCode());
            return;
        }

        log.info("pblpkltFetchJob COMPLETED — 읽음={} 씀={}", readCount(fetch), writeCount(fetch));

        chainPkltUtztn(fetch);
    }

    /**
     * 주차장 수집이 {@code COMPLETED} 로 끝난 뒤 {@code pkltUtztnFetchJob} 을 잇는다 (TSK-4189 Step 9).
     *
     * <h2>왜 잇나</h2>
     * 데이터셋 교체 시 {@code tb_pblpklt} 는 upsert 가 아니라 <b>전량 재적재</b>가 확정 절차인데
     * ({@code docs/checklist/pblpklt-geocode.md} 4-3 · TSK-4192 Step 9), 그러면 {@code pklt_id} 가
     * 재채번돼 이용패턴을 <b>다시 적재해야</b> 한다. 그 "다시 실행" 을 사람이 깜빡하는 것이 네 단계 복구
     * 절차에서 <b>유일하게 조용한 실패</b>라, 그 하나를 구조적으로 없앤다.
     *
     * <p>⚠️ 이 클래스는 {@code pblpklt} 패키지다 — 배치 규칙의 "잡을 추가해도 기존 잡 패키지를 건드리지
     * 않는다" 의 예외이며, 근거가 위 문단이다(민원 스케줄러가 위험단계 판정을 잇는 것과 같은 형태).
     *
     * <p>🔴 {@code COMPLETED} 가 아니면 잇지 않는다 — 이 메서드는 그 판정 <b>뒤에만</b> 불린다.
     * 부분 수집분으로 매칭하면 우리 표에 아직 없는 주차장이 대량 미해석으로 떨어진다.
     * 🔴 수동 트리거({@code POST /api/batch/pblpklt-fetch})에는 이 연쇄가 <b>없다</b> —
     * 그 경로로 재적재했다면 {@code POST /api/batch/pklt-utztn-fetch} 를 직접 눌러야 한다.
     *
     * @param fetch {@code COMPLETED} 로 끝난 주차장 수집 실행 — 그 ID 를 연쇄 잡의
     *              {@code prntExcnId} 로 싣는다(TSK-4337 D6)
     */
    private void chainPkltUtztn(JobExecution fetch) {
        if (!utztnChained) {
            log.info("pkltUtztnFetchJob 연쇄 생략 — utztn.sync.scheduled=false "
                    + "(이용패턴 자동 실행 전부가 꺼져 있다. 수동 트리거는 그대로 동작한다)");
            return;
        }
        try {
            JobExecution utztn = jobLauncher.run(pkltUtztnFetchJob, ExcnSe.chain(new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis()), fetch.getId())
                    .toJobParameters());
            log.info("pkltUtztnFetchJob {} — 읽음={} 씀={}",
                    utztn.getStatus(), readCount(utztn), writeCount(utztn));
        } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
                | JobParametersInvalidException | DataAccessException | TransactionException
                | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — JobLauncher.run 의 선언 예외 4종 + JobRepository(JDBC·트랜잭션) + 인자·상태 예외.
            // (동기 런처는 Job 내부 실패를 예외가 아니라 JobExecution 상태로 돌려준다 — 여기 오는 것은 실행 전·저장소 실패다.)
            // 🔴 삼키고 ERROR 1줄 — 던지면 이 주기가 통째로 사라지고 원인이 로그에 남지 않는다.
            log.error("pkltUtztnFetchJob 연쇄 실행 실패 — 수동 트리거로 다시 돌리세요"
                    + "(POST /api/batch/pklt-utztn-fetch).", e);
        }
    }

    private static long readCount(JobExecution exec) {
        return exec.getStepExecutions().stream().mapToLong(s -> s.getReadCount()).sum();
    }

    private static long writeCount(JobExecution exec) {
        return exec.getStepExecutions().stream().mapToLong(s -> s.getWriteCount()).sum();
    }
}