package jeju.datahub.batch.rptp;

import jeju.datahub.batch.ops.JobLogMdcListener;
import jeju.datahub.batch.ops.RunningJobRegistryListener;
import java.time.ZoneId;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ④{@code reportAutoCrtJob} 조립 — <b>{@code parking.tb_rptp_auto_crt_stng} 의 살아 있는 설정을
 * 읽어 배포일이 도래한 것만 백엔드 내부 API 로 보내 보고서를 만든다</b> (TSK-4027 Step 9).
 *
 * <pre>
 * Reader    RptpAutoCrtStngItemReader  actvtn_yn = TRUE AND del_dt IS NULL 인 설정을 stng_id 순 페이징
 * Processor ReportAutoCrtProcessor     기준일(baseYmd)에 배포일이 도래했는지 판정 → 대상기간 산출
 *                                      (도래하지 않으면 null = Spring Batch 가 조용히 걸러 낸다)
 * Writer    ReportAutoCrtWriter        POST /api/internal/reports/auto-crt → 201 생성 / 200 이미있음
 * </pre>
 *
 * <h2>🔴 중단 정책 — {@code skip()} 목록에 무엇을 넣느냐가 곧 정책이다 (D8)</h2>
 * <table>
 *   <tr><th>예외</th><th>처리</th><th>이유</th></tr>
 *   <tr><td>{@link ReportAutoCrtSkippableException}</td><td><b>스킵</b>(계속)</td>
 *       <td>그 설정 하나만의 문제다. <b>400</b>(0건 집계·설정 불일치)은 사유를 기록해 굳히고,
 *           <b>5xx·타임아웃</b>은 기록하지 않아 다음 실행이 다시 집어 온다(D8-①·②).
 *           한 설정 때문에 나머지 설정의 보고서가 통째로 안 만들어지면 안 된다</td></tr>
 *   <tr><td>{@code IllegalStateException}(401·403·503·예상 밖 상태코드)</td><td><b>Job FAILED</b></td>
 *       <td>내부 API 키 오류·백엔드 다운·경로 불일치는 <b>전 설정이 같은 이유로 실패</b>할 상황이다.
 *           삼키면 "전부 스킵했는데 Job 은 COMPLETED" 로 끝나 그 주기의 보고서가 조용히 빈다.
 *           백엔드가 멱등(200 이미있음)이라 고친 뒤 다시 돌리면 그만이다(D8-③)</td></tr>
 * </table>
 * 🔴 <b>{@code skip()} 목록에 다른 예외를 추가하지 않는다</b> — {@code skip(RuntimeException.class)}
 * 같은 광범위 스킵은 {@code rule_batch-project.md} 가 명시적으로 금지한다. 하나만 더 넣어도
 * 위 즉시 중단 정책이 통째로 무력화된다(선례 {@code RiskAlertJudgeJobConfig}·
 * {@code ComplaintFetchJobConfig} 와 같은 근거).
 *
 * <p>{@code skipLimit} 은 {@link Integer#MAX_VALUE} 다. 스킵은 예외 상황이 아니라 정상 경로라,
 * 건수가 늘었다고 Job 을 죽이면 나머지 정상 설정의 보고서까지 만들어지지 않는다. 급증 감지는
 * 종료 요약 로그와 {@code batch.tb_job_skip}({@code job_se='RPTP'}) 조회로 한다.
 *
 * <h2>도래하지 않은 설정은 스킵이 아니다</h2>
 * 배포일이 아닌 설정은 Processor 가 {@code null} 을 반환해 Spring Batch 가 걸러 낸다
 * ({@code filterCount}) — <b>대부분의 날은 전부 여기다.</b> {@code read > written} 은 정상이다.
 */
@Configuration
public class ReportAutoCrtJobConfig {

    /**
     * 배치 잡 이름. 수동 트리거({@link ReportAutoCrtController})·스케줄러
     * ({@link ReportAutoCrtScheduler})·{@code batch_job_instance} 조회가 쓰는 값이라 상수로 못박는다.
     */
    public static final String JOB_NAME = "reportAutoCrtJob";

    static final String STEP_NAME = "reportAutoCrtStep";

    /**
     * 기준일("오늘")을 정하는 시간대. 🔴 트리거와 스케줄러가 <b>같은 값</b>을 써야 한다 —
     * 한쪽만 시스템 기본 시간대를 쓰면 자정 근처에서 하루가 갈린다.
     */
    public static final String ZONE = "Asia/Seoul";

    /** {@link #ZONE} 의 {@link ZoneId} 형태. 진입점 두 곳이 {@code LocalDate.now(SEOUL)} 에 쓴다. */
    public static final ZoneId SEOUL = ZoneId.of(ZONE);

    /**
     * @param historyListener 시작 시 스킵 카운터 리셋 + 종료 시 요약 로그 + 실행 이력 1행
     *                        ({@code batch.tb_btch_excn_hstry})
     */
    @Bean
    public Job reportAutoCrtJob(
            JobRepository jobRepository,
            Step reportAutoCrtStep,
            ReportAutoCrtHistoryListener historyListener,
            RunningJobRegistryListener runningJobRegistry) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // TSK-4337 Step 5 — 실행별 로그 파일 MDC 는 체인 맨 앞(beforeJob 가장 먼저 — 다른 리스너 로그까지 파일에 담는다)
                .listener(JobLogMdcListener.INSTANCE)
                .listener(historyListener)
                // TSK-4337 — 실행 레지스트리는 리스너 체인 맨 뒤 — afterJob 이 역순이라 가장 먼저 불려 앞 리스너 예외에도 해제된다.
                .listener(runningJobRegistry)
                .start(reportAutoCrtStep)
                .build();
    }

    /**
     * @param reader    {@code @StepScope} 프록시. Step 시작 시점에 설정 테이블을 새로 읽는다
     * @param processor {@code @StepScope} 프록시. Job 파라미터 {@code baseYmd} 를 이 시점에 읽는다
     * @param writer    {@code @StepScope} 프록시. 같은 {@code baseYmd} 를 읽어 스킵 기록의
     *                  {@code trgt_de} 로 쓴다 — 진입점이 확정한 기준일 하나가 끝까지 흐른다
     * @param chunkSize 한 트랜잭션에 커밋하는 행 수 = 중간 실패 시 되돌아가는 범위.
     *                  🔴 <b>Reader 페이지 크기와 같은 키</b>({@code batch.rptp.chunk-size})를 읽어
     *                  한쪽만 바뀌는 일을 막는다({@link RptpAutoCrtStngItemReader} 생성자와 동일)
     */
    @Bean
    public Step reportAutoCrtStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RptpAutoCrtStngItemReader reader,
            ReportAutoCrtProcessor processor,
            ReportAutoCrtWriter writer,
            ReportAutoCrtSkipListener reportAutoCrtSkipListener,
            ReportAutoCrtHistoryListener historyListener,
            @Value("${batch.rptp.chunk-size:50}") int chunkSize) {

        return new StepBuilder(STEP_NAME, jobRepository)
                .<RptpStngRow, ReportAutoCrtCommand>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                // 🔴 D8 — 이 예외 하나만 스킵한다. 다른 예외를 넣지 않는다(위 표).
                .skip(ReportAutoCrtSkippableException.class)
                // 스킵은 정상 경로 — 건수로 Job 을 죽이지 않는다.
                .skipLimit(Integer.MAX_VALUE)
                .listener(reportAutoCrtSkipListener)
                // 🔴 Writer 는 @StepScope 라 afterJob 시점에는 이미 스코프가 닫혀 카운터를 읽을 수 없다.
                //    Step 이 살아 있는 동안(afterStep)에 생성/이미있음 건수를 JobExecution 으로 옮긴다
                //    — 캐스팅은 listener(Object) 오버로드로 새는 것을 막기 위한 명시다.
                .listener((StepExecutionListener) historyListener)
                .build();
    }
}
