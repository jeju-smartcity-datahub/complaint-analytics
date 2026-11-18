package jeju.datahub.batch.crackdown;

import jeju.datahub.batch.ops.JobLogMdcListener;
import jeju.datahub.batch.ops.RunningJobRegistryListener;
import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jeju.datahub.batch.crackdown.api.CrackdownOriginRow;
import jeju.datahub.batch.crackdown.api.DhubCrackdownApiClient;
import jeju.datahub.batch.crackdown.api.DhubCrackdownItemReader;
import jeju.datahub.batch.crackdown.geocode.CrackdownAddressReader;
import jeju.datahub.batch.crackdown.geocode.CrackdownCoord;
import jeju.datahub.batch.crackdown.geocode.CrackdownCoordWriter;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeProcessor;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeSkipListener;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeSkippableException;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;

/**
 * {@code crackdownFetchJob} 조립 — DHubManager 단속 데이터셋 → {@code parking.tb_crdn_dsctn} →
 * 좌표·행정동 보강 (TSK-4054 D1).
 *
 * <pre>
 * crackdownFetchStep      원천(load_dt 구간) → tb_crdn_dsctn (ON CONFLICT (src_hash) DO UPDATE, 가공 없음)
 *   → crackdownCoordDictStep  같은 crdn_addr 의 기존 좌표·emd_cd 를 좌표 없는 행에 set-based 전파 (API 0회)
 *   → crackdownGeocodeStep    남은 고유 주소 단위 지오코딩 → 행정동 매칭 → 같은 주소의 좌표 없는 행에 UPDATE
 * </pre>
 * 🆕 <b>수집 구간(TSK-4316)</b> — Job 파라미터 {@code from}(포함)·{@code to}(미포함)로 원천 {@code load_dt} 를 거른다.
 * 둘 다 없으면 <b>증분</b>(적재된 최대 원천 load_dt부터 오늘까지), 적재된 행이
 * 없으면 전량이다. 뒤 두 Step 은 종전대로 <b>테이블 전체</b>의 좌표 없는 행을 보므로 구간과 무관하다.
 * 행 단위 Processor(민원 방식)를 수집 Step 에 두지 않는 이유 — 적재가 {@code ON CONFLICT (src_hash) DO UPDATE} 이라
 * 수집 단계는 원문과 원천 load_dt만 쓰고, 좌표는 뒤의 주소 단위 보강 단계가 담당한다. 그래서 가공은 뒤 Step 이 주소 단위로 한다.
 * {@code start().next()} 기본 동작으로 <b>앞 Step 이 COMPLETED 가 아니면 뒤 Step 은 실행되지 않는다</b> —
 * 부분 수집분으로 가공하지 않는다.
 *
 * <ul>
 *   <li><b>{@code faultTolerant()} 는 수집 Step 에만 금지</b> — 행 하나라도 못 읽으면(파싱 실패 포함) 수집분이
 *       통째로 불완전해진다. 삼키고 COMPLETED 로 끝내는 대신 FAILED 로 드러낸다. upsert 라 다시 돌리면 그만이다.
 *       {@code crackdownGeocodeStep} 은 주소 단위 확정 실패({@link CrackdownGeocodeSkippableException})만
 *       스킵한다(D8). 🔴 {@code GeocodeQuotaExceededException} 은 {@code skip()} 목록에 넣지 않는다 —
 *       넣으면 즉시 중단(Job FAILED) 정책이 무력화된다(D9).</li>
 *   <li><b>{@code processorNonTransactional()}</b> — 처리 중 스킵 예외는 청크를 롤백하고 같은 청크의 앞선 항목을
 *       Processor 로 다시 돌린다(Spring Batch 5.2.1 {@code FaultTolerantChunkProcessor.transform}, 기본
 *       {@code processorTransactional=true}). 그러면 {@code geocode_cache_hits}(인메모리 히트)·{@code emd_contains}
 *       가 재처리분만큼 부푼다. 장소명 주소는 전부 {@code GEOCODE_FAILED} 라(Step 4 생략) 스킵이 잦다. 켜면 앞선
 *       항목의 non-null 결과를 재사용한다.
 *       <br>🔁 <b>2026-09-18 TSK-4162 Step 3</b> — 종전 주석의 "대가: 롤백된 청크의 DB 캐시 쓰기는 다시
 *       되지 않는다(단속은 좌표가 적재돼 다시 읽히지 않으므로 무해하다)"는 <b>더 이상 대가가 아니다</b>.
 *       {@link jeju.datahub.batch.geocode.GeocodeCacheRepository#save(String,
 *       jeju.datahub.batch.geocode.GeocodeOutcome)} 이 {@code REQUIRES_NEW} 로 떨어져 나가
 *       롤백된 청크의 캐시 쓰기도 남는다(공용 컴포넌트라 단속·민원에 자동 적용).</li>
 *   <li>🔴 <b>가공 Step 의 협력 객체(Tasklet·Reader·Processor·Writer·스킵 리스너)는 {@code @Bean} 메서드
 *       파라미터 타입으로 주입받는다</b>({@code @Qualifier} 없음, Step 메서드 안의 {@code new}·다른 {@code @Bean}
 *       메서드 직접 호출 없음). Job 통합 테스트({@code CrackdownFetchJobTest})가 테이블 전체를 대상으로 하는
 *       Tasklet·Reader 를 {@code @Primary} 픽스처 빈으로 교체하는 유일한 경로다 — 이 조건이 깨지면 테스트가 실 행
 *       약 9.4만 건을 전파·커밋한다.</li>
 *   <li>청크 크기는 설정 키 — 수집 {@code batch.crackdown.chunk-size}, 지오코딩
 *       {@code batch.crackdown.geocode.chunk-size}(Reader 페이지 크기와 같은 키). 커밋 간격 = 중간 실패 시
 *       되돌아가는 범위.</li>
 * </ul>
 */
@Configuration
public class CrackdownFetchJobConfig {

    public static final String JOB_NAME = "crackdownFetchJob";
    static final String STEP_NAME = "crackdownFetchStep";
    static final String COORD_DICT_STEP_NAME = "crackdownCoordDictStep";
    static final String GEOCODE_STEP_NAME = "crackdownGeocodeStep";

    @Bean
    public Job crackdownFetchJob(
            JobRepository jobRepository,
            Step crackdownFetchStep,
            Step crackdownCoordDictStep,
            Step crackdownGeocodeStep,
            CrackdownFetchHistoryListener historyListener,
            RunningJobRegistryListener runningJobRegistry) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // TSK-4337 Step 5 — 실행별 로그 파일 MDC 는 체인 맨 앞(beforeJob 가장 먼저 — 다른 리스너 로그까지 파일에 담는다)
                .listener(JobLogMdcListener.INSTANCE)
                .listener(historyListener)
                // TSK-4337 — 실행 레지스트리는 리스너 체인 맨 뒤 — afterJob 이 역순이라 가장 먼저 불려 앞 리스너 예외에도 해제된다.
                .listener(runningJobRegistry)
                .start(crackdownFetchStep)
                .next(crackdownCoordDictStep)
                .next(crackdownGeocodeStep)
                .build();
    }

    /**
     * 🆕 {@code @StepScope} — Job 파라미터 {@code from}/{@code to} 를 받는다(TSK-4316). 구간 확정(워터마크 조회 포함)은
     * Reader 의 {@code open()} 에서 한다.
     */
    @Bean
    @StepScope
    public DhubCrackdownItemReader crackdownItemReader(
            DhubCrackdownApiClient apiClient, DhubProperties properties, jeju.datahub.batch.collect.SourceLoadWatermark watermark,
            @Value("#{jobParameters['from']}") String from,
            @Value("#{jobParameters['to']}") String to) {
        return new DhubCrackdownItemReader(apiClient, properties,
                () -> jeju.datahub.batch.collect.SourceLoadRange.resolve(from, to, watermark::crackdown,
                        properties.resolveToday(java.time.Clock.systemUTC())));
    }

    @Bean
    public Step crackdownFetchStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DhubCrackdownItemReader reader,
            CrackdownItemWriter writer,
            @Value("${batch.crackdown.chunk-size:500}") int chunkSize) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<CrackdownOriginRow, CrackdownOriginRow>chunk(chunkSize, transactionManager)
                .reader(reader)
                .writer(writer)
                .build();
    }

    /** 좌표 사전 전파(D3 ①). 🔴 Tasklet 은 파라미터 타입으로 주입받는다(클래스 주석 — 테스트 교체 경로). */
    @Bean
    public Step crackdownCoordDictStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CrackdownCoordDictionaryTasklet tasklet) {
        return new StepBuilder(COORD_DICT_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    /**
     * 좌표 없는 고유 주소 Reader. {@code @Bean} 으로 반환해야 컨테이너가 {@code afterPropertiesSet()} 을 부른다
     * ({@link CrackdownAddressReader} 주석).
     *
     * @param chunkSize    페이지 크기 — Step 청크 크기와 <b>같은 키</b>
     * @param maxAddresses 1회 실행 상한. 0 = 무제한(D9)
     */
    @Bean
    @StepScope
    public JdbcPagingItemReader<String> crackdownAddressReader(
            DataSource dataSource,
            @Value("${batch.crackdown.geocode.chunk-size:50}") int chunkSize,
            @Value("${batch.crackdown.geocode.max-addresses:0}") int maxAddresses) {
        return CrackdownAddressReader.create(dataSource, CrackdownAddressReader.WHERE_CLAUSE, chunkSize, maxAddresses);
    }

    /**
     * 주소 단위 지오코딩(D2·D8).
     *
     * @param reader {@code @StepScope} 프록시
     */
    @Bean
    public Step crackdownGeocodeStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<String> reader,
            CrackdownGeocodeProcessor processor,
            CrackdownCoordWriter writer,
            CrackdownGeocodeSkipListener skipListener,
            @Value("${batch.crackdown.geocode.chunk-size:50}") int chunkSize) {
        return new StepBuilder(GEOCODE_STEP_NAME, jobRepository)
                .<String, CrackdownCoord>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                // GEOCODE_FAILED / EMD_UNRESOLVED 2 사유만. 쿼터 초과는 여기 없으므로 Job 이 FAILED 로 끊긴다.
                .skip(CrackdownGeocodeSkippableException.class)
                // 스킵은 정상 경로 — 건수로 Job 을 죽이지 않는다.
                .skipLimit(Integer.MAX_VALUE)
                // 스킵 스캔 재처리로 지표가 부풀지 않게 한다(클래스 주석).
                .processorNonTransactional()
                .listener(skipListener)
                .build();
    }
}
