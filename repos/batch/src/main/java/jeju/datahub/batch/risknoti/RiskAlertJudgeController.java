package jeju.datahub.batch.risknoti;

import jeju.datahub.batch.history.ExcnSe;
import java.time.LocalDate;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jeju.datahub.batch.ops.JobParameterFactory;
import jeju.datahub.batch.web.JobExecutionSummary;

/**
 * ③{@code riskAlertJudgeJob} 수동 트리거 (TSK-4069 Step 7).
 *
 * <pre>
 * # 어제 하루치 판정 (targetDate 생략 — D1)
 * curl -X POST 'http://localhost:8280/api/batch/risk-alert-judge'
 *
 * # 특정 일자 판정
 * curl -X POST 'http://localhost:8280/api/batch/risk-alert-judge?targetDate=2025-12-31'
 * </pre>
 *
 * <h2>🔴 판정일을 여기서 확정해 <b>항상</b> Job 파라미터로 넣는다 (D1·D11)</h2>
 * 비워 두면 {@link RiskAlertTargetItemReader} 와 {@link RiskAlertJudgeProcessor} 가 <b>각자</b>
 * "어제" 를 계산한다 — 자정을 걸치면 둘이 하루가 갈려 <b>당일 건수와 전일 건수가 다른 날 기준</b>으로
 * 비교된다. 게다가 Spring Batch 메타·실행 이력에도 "무슨 날짜를 판정했는지" 가 남지 않아 사후 추적이
 * 불가능하다. 그래서 {@link RiskAlertProperties#resolveTargetDate(LocalDate)} 로 <b>진입점 한 곳에서</b>
 * 확정한 뒤 {@code addString("targetDate", ...)} 로 못박는다({@code ComplaintSyncScheduler} 체인도 동일).
 *
 * <h2>⚠️ HTTP 파라미터가 SQL 로 흘러가는 경로를 Job 시작 전에 끊는다</h2>
 * {@code targetDate} 는 {@code @DateTimeFormat(ISO.DATE)} 로 {@link LocalDate} 파싱을 통과한 뒤에만
 * Job 파라미터가 된다 — 형식이 틀리면 Job 이 시작되기도 전에 <b>400</b> 으로 끊긴다
 * ({@code rule_batch-project.md} "HTTP 파라미터가 SQL 로 흘러가는 경로").
 *
 * <h2>응답은 {@link JobExecutionSummary} — {@code JobExecution} 직접 반환 금지</h2>
 * {@link JobExecution} 을 그대로 반환하면 {@code JobInstance}·{@code ExecutionContext}·
 * {@code StepExecution} 순환 참조로 Jackson 이 {@code Document nesting depth (1001) exceeds the
 * maximum allowed (1000)} 로 죽는다(2026-08-24 {@code /api/batch/pblpklt-fetch} 실측 — Job 은 정상
 * 종료했는데 응답만 깨졌다). 🔴 <b>같은 record 를 이 클래스 안에 다시 정의하지 않는다</b> — 복사본
 * 중 하나가 빠져 위 사고가 났기 때문에 {@code jeju.datahub.batch.web} 한곳으로 모았다.
 *
 * <h2>⚠️ 로컬 개발용·무인증 엔드포인트 (cvlcpt·crackdown·pblpklt 와 동일)</h2>
 * 인증 없음. 운영 노출 시 인증/네트워크 차단 필수(dev 는 batch 포트 비노출로 방어).
 *
 * <h2>⚠️ 동기 실행</h2>
 * {@link JobLauncher} 기본이 {@code SyncTaskExecutor} 라 판정이 끝날 때까지 응답이 블로킹된다.
 * 다만 판정 대상은 읍면동 단위(수십 행)라 하루치는 즉시 끝난다.
 */
@RestController
@RequestMapping("/api/batch")
public class RiskAlertJudgeController {

    // 🔴 필드·생성자를 빠뜨리지 않는다 — 아래 run() 이 둘 다 쓴다(선례 CrackdownFetchController).
    //    Job 은 **빈 이름**으로 주입된다 — 파라미터명 riskAlertJudgeJob 이
    //    RiskAlertJudgeJobConfig 의 @Bean 메서드명(= JOB_NAME)과 같아야 한다.
    private final JobLauncher jobLauncher;
    private final Job riskAlertJudgeJob;
    /**
     * 🔴 백필 전용 Job 도 <b>빈 이름</b>으로 주입된다 — 파라미터명이
     * {@code RiskAlertJudgeJobConfig} 의 {@code @Bean} 메서드명
     * ({@code riskAlertReceiverBackfillJob} = {@code BACKFILL_JOB_NAME})과 같아야 한다.
     * 타입({@link Job})만 보면 이 레포에 Job 빈이 여럿이라
     * {@code NoUniqueBeanDefinitionException} 으로 기동이 죽는다.
     */
    private final Job riskAlertReceiverBackfillJob;

    public RiskAlertJudgeController(JobLauncher jobLauncher,
                                    Job riskAlertJudgeJob,
                                    Job riskAlertReceiverBackfillJob) {
        this.jobLauncher = jobLauncher;
        this.riskAlertJudgeJob = riskAlertJudgeJob;
        this.riskAlertReceiverBackfillJob = riskAlertReceiverBackfillJob;
    }

    /**
     * {@code POST /api/batch/risk-alert-judge?targetDate=2025-12-31}
     *
     * @param targetDate 판정 대상일 {@code yyyy-MM-dd}(선택). 없으면 <b>어제</b>(D1).
     *                   ⚠️ 로컬에서 생략하면 {@code written=0} 이 정상이다 — 실데이터가
     *                   {@code 2025-11-04~2025-12-31} 뿐이라 "어제" 에 민원이 없다(D11)
     * @throws Exception {@code JobExecutionException} 계열. 로컬 트리거라 그대로 500 으로 드러내는
     *                   편이 원인 파악에 낫다
     */
    @PostMapping("/risk-alert-judge")
    public JobExecutionSummary run(
            @RequestParam(required = false)
            @DateTimeFormat(iso = ISO.DATE) LocalDate targetDate) throws Exception {

        // 🔴 생략됐으면 '어제'로 확정해 **항상** Job 파라미터에 넣는다 (D1·D11 — 위 Javadoc).
        //    🔁 TSK-4337 — 확정·runAt 은 JobParameterFactory 한 곳(운영 API 와 공유). 실행 구분만 여기서 붙인다.
        JobParameters params = ExcnSe.tag(JobParameterFactory.riskAlertJudge(targetDate), ExcnSe.API)
                .toJobParameters();

        JobExecution execution = jobLauncher.run(riskAlertJudgeJob, params);
        return JobExecutionSummary.of(execution);
    }

    /**
     * 🔴 알림 × 수신자 매핑 <b>백필</b> 트리거 (TSK-4202 D7-1 ②).
     *
     * <pre>
     * # 전 기간 — 기간 파라미터를 아예 넣지 않는다
     * curl -X POST 'http://localhost:8280/api/batch/risk-alert-receiver-backfill'
     *
     * # 구간 지정(fromDt 포함 ~ toDt 미포함)
     * curl -X POST 'http://localhost:8280/api/batch/risk-alert-receiver-backfill?fromDt=2026-09-01&toDt=2026-10-01'
     * </pre>
     *
     * <h2>🔴 왜 1회성 마이그레이션이 아니라 상시 트리거인가</h2>
     * TSK-4202 부터 조회 화면({@code GET /api/risk-alerts})이 매핑 표로 수신 대상을 거른다.
     * 1회성 백필이면 <b>그 이후 새로 등록된 수신자는 과거 알림을 영원히 못 본다</b> —
     * "수단을 남기는 것" 이 D7-1 의 핵심이다.
     *
     * <h2>⚠️ 백필은 "지금 담당은 누구인가" 다</h2>
     * 수신자 표의 <b>현재 상태</b>로 과거 알림을 매핑한다("그때 누구에게 갔는가" 는 복원 불가 —
     * 발송 기록 자체가 없다). {@code ON CONFLICT DO NOTHING} 이라 몇 번을 호출해도 행이 늘지 않는다.
     *
     * @param fromDt 시작일 {@code yyyy-MM-dd}(<b>포함</b>, 선택). 없으면 하한 없음
     * @param toDt   끝일 {@code yyyy-MM-dd}(<b>미포함</b>, 선택). 없으면 상한 없음
     * @throws Exception {@code JobExecutionException} 계열. 로컬 트리거라 그대로 500 으로 드러낸다
     */
    @PostMapping("/risk-alert-receiver-backfill")
    public JobExecutionSummary runReceiverBackfill(
            @RequestParam(required = false)
            @DateTimeFormat(iso = ISO.DATE) LocalDate fromDt,
            @RequestParam(required = false)
            @DateTimeFormat(iso = ISO.DATE) LocalDate toDt) throws Exception {

        // 🔁 TSK-4337 — runAt·null 가드("기간 생략 = 파라미터를 아예 넣지 않음")는 JobParameterFactory 한 곳.
        JobParametersBuilder builder = ExcnSe.tag(
                JobParameterFactory.riskAlertReceiverBackfill(fromDt, toDt), ExcnSe.API);   // 비식별

        JobExecution execution = jobLauncher.run(riskAlertReceiverBackfillJob, builder.toJobParameters());
        return JobExecutionSummary.of(execution);
    }
}
