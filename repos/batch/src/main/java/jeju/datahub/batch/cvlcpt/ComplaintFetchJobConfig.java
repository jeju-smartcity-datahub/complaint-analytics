package jeju.datahub.batch.cvlcpt;

import jeju.datahub.batch.ops.JobLogMdcListener;
import jeju.datahub.batch.ops.RunningJobRegistryListener;
import jeju.datahub.batch.cvlcpt.api.ComplaintItemReader;
import jeju.datahub.batch.cvlcpt.api.ComplaintOriginRow;
import jeju.datahub.batch.geocode.GeocodeQuotaExceededException;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ①{@code complaintFetchJob} 조립 — <b>수집 API → 분류·지오코딩 → {@code parking.tb_cvlcpt}</b>.
 *
 * <h2>🔁 2026-08-24 — 2단 staging 을 1단으로 합쳤다</h2>
 * <pre>
 * [구] ① complaintFetchJob   API ─→ parking.tb_cvlcpt_origin
 *      ② cvlcptGeocodeJob    origin ─→ parking.tb_cvlcpt   (분류·지오코딩·공간매칭)
 *
 * [현] ① complaintFetchJob   API ─(분류·지오코딩·공간매칭)─→ parking.tb_cvlcpt
 * </pre>
 * {@code tb_cvlcpt_origin} 이 폐지되고(47-CVLCPT-DIRECT.sql) ②{@code cvlcptGeocodeJob} 이
 * 사라졌다. ②의 Processor·Writer·스킵 리스너는 <b>그대로 이 Step 안으로 옮겨 왔다</b>.
 *
 * <p><b>원본 보존은 컬럼으로 지킨다.</b> staging 테이블 대신 {@code tb_cvlcpt} 자신이 원문 17컬럼
 * ({@code dclr_type}·{@code prk_cvlcpt_type}·{@code src_path}·{@code prgrs_stts} …)을 함께 담는다.
 * 분류 규칙이 바뀌어도 이 값들이 있어 적재된 행을 다시 분류할 수 있다.
 *
 * <h2>🔴 중단 정책 — 스킵 목록에 무엇을 넣느냐가 곧 정책이다</h2>
 * <table>
 *   <tr><th>예외</th><th>처리</th><th>이유</th></tr>
 *   <tr><td>{@link GeocodeSkippableException}</td><td><b>스킵</b>(기록 후 계속)</td>
 *       <td>그 행만 실패 확정 — 배치를 멈출 이유가 없다. 주소DB에 없는 주소는 늘 일정 비율 있다</td></tr>
 *   <tr><td>{@link GeocodeQuotaExceededException}</td><td><b>Job FAILED</b></td>
 *       <td>오늘은 더 못 부른다. 계속 돌면 남은 전량이 헛도는 데다 실패로 캐싱될 위험</td></tr>
 *   <tr><td>그 외(API 오류 등)</td><td><b>Job FAILED</b></td>
 *       <td>페이지 하나를 못 받으면 그 구간이 통째로 빈다. 삼키고 COMPLETED 로 끝내면
 *           불완전한 수집분이 완전한 것처럼 보인다. upsert 라 다시 돌리면 그만이다</td></tr>
 * </table>
 * {@link GeocodeQuotaExceededException} 은 <b>{@code skip()} 목록에 넣지 않는 것</b>만으로 Job 이
 * 실패한다. 반대로 "스킵 사유를 늘리자"며 목록에 넣는 순간 행 단위로 삼켜져 즉시 중단 정책이
 * 통째로 무력화된다.
 *
 * <p>{@code skipLimit} 은 {@link Integer#MAX_VALUE} 다. 스킵은 예외 상황이 아니라 정상 경로라,
 * 건수가 늘었다고 Job 을 죽이면 나머지 정상 행까지 적재되지 않는다. 급증 감지는 종료 요약과
 * {@code batch.v_geocode_skip_diagnosis} 로 한다.
 *
 * <h2>미분류(99)는 스킵이 아니다</h2>
 * 주차민원이 아닌 행은 좌표 없이 {@code 99} 로 <b>정상 적재</b>된다(Processor 단계). 스킵 사유는
 * {@code GEOCODE_FAILED}/{@code EMD_UNRESOLVED} 2종뿐이다.
 */
@Configuration
public class ComplaintFetchJobConfig {

    /**
     * 배치 잡 이름. 수동 트리거({@link ComplaintBatchTriggerController})·스케줄러·
     * {@code batch_job_instance} 조회에 쓰는 값이라 상수로 못박는다.
     */
    public static final String JOB_NAME = "complaintFetchJob";

    static final String STEP_NAME = "complaintFetchStep";

    /**
     * @param summaryListener 시작 시 카운터 리셋 + 종료 시 요약 로그 + 실행 이력 1행
     *                        ({@code batch.tb_btch_excn_hstry})
     *
     * <p>🔁 2026-08-24 — 리스너가 하나로 줄었다. 종전 ①에는 {@code ComplaintFetchHistoryListener}
     * 가 붙어 지오코딩 지표를 {@code BatchRunMetrics.none()}(전부 NULL)로 남겼다 — ①에 Processor
     * 자체가 없었기 때문이다. 1단화로 이 Job 이 지오코딩까지 하므로 그 전제가 깨졌고,
     * 두 리스너를 같이 붙이면 <b>같은 job_execution_id 에 서로 다른 값을 쓰는 경쟁</b>이 된다.
     * 실제 지표를 아는 {@link CvlcptJobSummaryListener} 만 남긴다.
     */
    @Bean
    public Job complaintFetchJob(
            JobRepository jobRepository,
            Step complaintFetchStep,
            CvlcptJobSummaryListener summaryListener,
            RunningJobRegistryListener runningJobRegistry) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // TSK-4337 Step 5 — 실행별 로그 파일 MDC 는 체인 맨 앞(beforeJob 가장 먼저 — 다른 리스너 로그까지 파일에 담는다)
                .listener(JobLogMdcListener.INSTANCE)
                .listener(summaryListener)
                // TSK-4337 — 실행 레지스트리는 리스너 체인 맨 뒤 — afterJob 이 역순이라 가장 먼저 불려 앞 리스너 예외에도 해제된다.
                .listener(runningJobRegistry)
                .start(complaintFetchStep)
                .build();
    }

    /**
     * @param reader    {@code @StepScope} 프록시. Job 파라미터({@code mode}/{@code targetDate}/
     *                  {@code from}/{@code to})를 이 시점에 읽는다
     * @param chunkSize 한 트랜잭션에 커밋하는 행 수 = 중간 실패 시 되돌아가는 범위.
     *                  <b>1단화로 기본값을 500 → 100 으로 낮췄다</b> — 이제 청크마다 지오코딩
     *                  대기(행당 최대 100ms + API 왕복)가 끼어 500 이면 트랜잭션이 과도하게 길어진다.
     *                  100 ≈ 10~30초/청크 로 종전 ②{@code cvlcptGeocodeJob} 과 같은 값이다
     */
    @Bean
    public Step complaintFetchStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ComplaintItemReader reader,
            CvlcptGeocodeProcessor processor,
            CvlcptItemWriter writer,
            GeocodeSkipListener geocodeSkipListener,
            @Value("${batch.complaint.chunk-size:100}") int chunkSize) {

        return new StepBuilder(STEP_NAME, jobRepository)
                .<ComplaintOriginRow, CvlcptRecord>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                // GEOCODE_FAILED / EMD_UNRESOLVED 2 사유만 — 미분류(99)는 스킵이 아니라 정상 적재다.
                // 쿼터 초과·API 오류는 여기 없으므로 Job 이 FAILED 로 끊긴다(위 표).
                .skip(GeocodeSkippableException.class)
                // 스킵은 정상 경로 — 건수로 Job 을 죽이지 않는다.
                .skipLimit(Integer.MAX_VALUE)
                .listener(geocodeSkipListener)
                .build();
    }
}
