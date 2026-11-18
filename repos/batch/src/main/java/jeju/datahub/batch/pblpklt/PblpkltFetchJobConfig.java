package jeju.datahub.batch.pblpklt;

import jeju.datahub.batch.ops.JobLogMdcListener;
import jeju.datahub.batch.ops.RunningJobRegistryListener;
import java.util.List;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.geocode.GeocodeQuotaExceededException;
import jeju.datahub.batch.pblpklt.api.DhubPblpkltItemReader;
import jeju.datahub.batch.pblpklt.api.DhubPblpkltApiClient;
import jeju.datahub.batch.pblpklt.api.PblpkltOriginRow;

/**
 * {@code pblpkltFetchJob} 조립 — DHubManager pblpklt 데이터셋 →
 * {@code parking.tb_pblpklt} + {@code batch.tb_pblpklt_addr_anomaly}.
 *
 * <h2>🔁 2026-08-24 — 2단 staging 을 1단으로 합쳤다</h2>
 * <pre>
 * [구] ③ pblpkltFetchJob    API ─→ parking.tb_pblpklt_origin
 *      ④ pblpkltProcessJob  origin ─→ parking.tb_pblpklt  (ID배정·지오코딩·소멸동기화)
 *
 * [현] ③ pblpkltFetchJob    API ─(ID배정·지오코딩·공간매칭)─→ parking.tb_pblpklt
 *                                 └ 마지막 Tasklet 이 소멸 마킹
 * </pre>
 * {@code tb_pblpklt_origin} 과 {@code batch.tb_pblpklt_id_map} 이 폐지되고
 * ({@code 48-PBLPKLT-DIRECT.sql}) ④{@code pblpkltProcessJob} 이 사라졌다. ④의 Processor·
 * Writer·스킵 리스너는 그대로 이 Job 안으로 옮겨 왔다.
 *
 * <p>기존 주차장은 주소로 찾는다. {@code origin_pk} 는 비유일인 마지막 관측 원천 ID 이며,
 * {@code pklt_id} 는 DB 가 IDENTITY 로 매긴다(🔁 2026-09-18 TSK-4162 Step 1 · C′ —
 * 인메모리 배정기가 폐지됐다). {@link PblpkltItemWriter} 주석 참고.
 *
 * <p>cvlcpt 의 {@code complaintFetchJob} 과 다른 점:
 * <ul>
 *   <li>수집 모드가 없다 (2026-08-11 E 답변). 일자 필터 없이 전량.</li>
 *   <li>Reader 의 {@code open()} 에서 {@code pklt_id} keyset 전 페이지를 받아 총건수를 대조한 뒤 버퍼에서 emit
 *       (🔁 2026-09-17 TSK-4161 Step 12 — 종전 단일 GET 덤프에서 전환).</li>
 *   <li>Processor 가 여럿이다. "주소 패턴 검증(관측용, 값 변형 없음)" 과 "원천 식별자 존재 검증"
 *       — {@link #pblpkltCompositeProcessor} 에서 직렬화.</li>
 *   <li>Writer 가 두 테이블을 본다. 같은 청크 트랜잭션 안에서 origin + anomaly 둘 다 INSERT
 *       (가이드 3-1 의 "값 보존 + 곁가지 기록" 정합성 보장).</li>
 * </ul>
 *
 * <h2>Processor 체인 순서</h2>
 * <pre>
 *   PblpkltOriginRow → PblpkltAddressAnomalyClassifier → PblpkltFetchRow
 *                   → PblpkltIdConsistencyProcessor    → PblpkltFetchRow
 *                   → PblpkltFetchItemWriter
 * </pre>
 * anomaly 분류가 먼저인 이유: 식별자가 빈 행도 anomaly 분류는 의미가 있어 일단 분류해 두고,
 * 식별자 검증에서 걸러진다.
 *
 * <h2>🔁 2026-09-18 TSK-4162 Step 3 — {@code processorNonTransactional()} (갈래 P)</h2>
 * 처리 중 스킵 예외는 청크를 롤백하고 같은 청크의 <b>앞선 항목을 Processor 로 다시 돌린다</b>
 * (Spring Batch 5.2.1 {@code FaultTolerantChunkProcessor.transform}, 기본
 * {@code processorTransactional=true}). 주차장은 스킵이 잦아 그 재처리로 지오코딩·공간매칭 지표가
 * 최대 3배까지 부푼다. 켜면 앞선 항목의 non-null 결과를 재사용한다 — {@code crackdownGeocodeStep}
 * 과 같은 설정이다.
 *
 * <p>🔴 <b>P 는 재처리만 없앤다 — 캐시 보존은 A 몫이다.</b> 롤백된 청크의 DB 캐시 쓰기가 사라지는 것은
 * {@code processorNonTransactional()} 이 만든 문제가 <b>아니다</b> — 켜기 전에도 청크 롤백이 그 청크에서
 * 처음 조회된 주소의 캐시 행을 지웠고, 인메모리 히트가 앞에서 반환해 재기록 기회까지 막았다.
 * 단속 쪽 주석의 "무해하다"(그 주소들은 좌표가 적재돼 다시 읽히지 않는다)는 <b>주차장엔 성립하지 않는다</b>
 * — 주차장 Reader 는 {@code NOT EXISTS} 를 쓰지 않아 매 실행 전량을 다시 지오코딩하므로 같은 유실이
 * 매번 되풀이된다. 그 유실을 실제로 막는 것은
 * {@link jeju.datahub.batch.geocode.GeocodeCacheRepository#save(String, jeju.datahub.batch.geocode.GeocodeOutcome)}
 * 의 {@code REQUIRES_NEW}(갈래 A)다.
 */
@Configuration
public class PblpkltFetchJobConfig {

    public static final String JOB_NAME = "pblpkltFetchJob";
    static final String STEP_NAME = "pblpkltFetchStep";
    /** 소멸 마킹 Step — 전량 수집이 끝난 뒤에만 "이번에 못 봤다" 를 확정할 수 있다. */
    static final String SOFT_DELETE_STEP_NAME = "pblpkltSoftDeleteStep";

    /**
     * Job 조립 — chunk Step (수집/적재) → Tasklet Step (soft delete 마킹) 순서.
     *
     * <p>🔁 2026-08-12 추가 — 두 번째 Step.
     * 순서가 구조적으로 고정된 이유(가이드 3-2 본문):
     * <ul>
     *   <li>chunk Step 이 모두 끝난 뒤에만 Tasklet 이 돈다 — 부정 판정("이번에 못 봤다") 은
     *       전체 수집이 끝난 뒤에만 확정 가능.</li>
     *   <li>순서가 뒤집히면 Job 이 중간에 죽었을 때 정상 행이 잘못 소멸로 마킹될 수 있다.</li>
     * </ul>
     */
    @Bean
    public Job pblpkltFetchJob(
            JobRepository jobRepository,
            Step pblpkltFetchStep,
            Step pblpkltSoftDeleteStep,
            PblpkltFetchHistoryListener historyListener,
            RunningJobRegistryListener runningJobRegistry) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // TSK-4337 Step 5 — 실행별 로그 파일 MDC 는 체인 맨 앞(beforeJob 가장 먼저 — 다른 리스너 로그까지 파일에 담는다)
                .listener(JobLogMdcListener.INSTANCE)
                // 실행 1회 = batch.tb_btch_excn_hstry 한 행 — 다른 두 잡과 같은 테이블에 남긴다.
                .listener(historyListener)
                // 🔁 2026-09-18 TSK-4162(C′) — 여기 있던 "ID 배정기 순번 카운터 초기화" 리스너를
                //    걷어냈다. pklt_id 를 DB 가 IDENTITY 로 매기면서 비울 카운터 자체가 없다.
                // TSK-4337 — 실행 레지스트리는 리스너 체인 맨 뒤 — afterJob 이 역순이라 가장 먼저 불려 앞 리스너 예외에도 해제된다.
                .listener(runningJobRegistry)
                .start(pblpkltFetchStep)
                .next(pblpkltSoftDeleteStep)
                .build();
    }

    /**
     * 원천 식별자 존재 검증. stateless → {@code @Bean}.
     *
     * <p>🔴 2026-09-12(TSK-4142 §7-0) — 이 Processor 의 {@code '-'} 형식 검증은 폐기됐다.
     * 남은 일은 "{@code pklt_id} 가 비면 스킵" 하나이며, 지오코딩 쿼터를 쓰기 전에 걸러야 해
     * 체인에는 그대로 둔다.
     */
    @Bean
    public PblpkltIdConsistencyProcessor pblpkltIdConsistencyProcessor() {
        return new PblpkltIdConsistencyProcessor();
    }

    /** 주소 특이 패턴 분류. stateless → {@code @Bean}. */
    @Bean
    public PblpkltAddressAnomalyClassifier pblpkltAddressAnomalyClassifier() {
        return new PblpkltAddressAnomalyClassifier();
    }

    /**
     * 두 Processor 를 직렬화한 컴포지트. Reader 출력 {@link PblpkltOriginRow} 가 들어와서
     * anomaly 분류를 거치며 {@link PblpkltFetchRow} 로 감싸지고, 그 결과가 ID 검증으로 다시
     * {@link PblpkltFetchRow} 로 emit 된다 (검증 실패 시 null → skip).
     *
     * <p>{@code List.of(...)} 로 만들어 Spring 이 제네릭을 추론하게 둔다.
     */
    @Bean
    public List<ItemProcessor<?, ?>> pblpkltCompositeProcessor(
            PblpkltAddressAnomalyClassifier anomalyClassifier,
            PblpkltIdConsistencyProcessor idConsistencyProcessor,
            PblpkltGeocodeProcessor geocodeProcessor) {
        // 🔁 2026-08-24 — 종전 ④ 가공 Job 의 지오코딩 Processor 가 체인 끝에 합류했다.
        //    순서가 중요하다: anomaly 관측 → 식별자 검증 → 지오코딩. 앞 둘은 값을 바꾸지 않고
        //    걸러내기만 하므로, 걸러질 행에 지오코딩 쿼터를 쓰지 않는다.
        return List.of(anomalyClassifier, idConsistencyProcessor, geocodeProcessor);
    }

    /**
     * {@link org.springframework.batch.item.support.CompositeItemProcessor} 자체는
     * {@code ItemProcessor<I, O>} 타입이라 Step 빌더에 직접 넘기려면 빈으로 노출해야 한다.
     * List 형태의 {@link #pblpkltCompositeProcessor} 결과를 이 빈에서 컴포지트로 합친다.
     */
    @Bean
    public org.springframework.batch.item.support.CompositeItemProcessor<
            PblpkltOriginRow, PblpkltRecord> pblpkltCompositeItemProcessor(
            List<ItemProcessor<?, ?>> pblpkltCompositeProcessor) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        org.springframework.batch.item.support.CompositeItemProcessor raw =
                new org.springframework.batch.item.support.CompositeItemProcessor();
        raw.setDelegates((List) pblpkltCompositeProcessor);
        return raw;
    }

    /**
     * 🔁 2026-08-24 — Processor 체인 끝에 지오코딩이 붙고, Writer 가 서비스 테이블을 본다.
     *
     * <pre>
     *   PblpkltOriginRow(API)
     *     → PblpkltAddressAnomalyClassifier  주소 특이 패턴 관측 (값 변형 없음)
     *     → PblpkltIdConsistencyProcessor    원천 식별자 존재 검증(§7-0: '-' 형식 검증 폐기)
     *     → PblpkltGeocodeProcessor          ID배정 · 유형분류 · 지오코딩 · 공간매칭
     *     → PblpkltItemWriter                parking.tb_pblpklt upsert
     * </pre>
     *
     * <h2>🔴 중단 정책</h2>
     * {@link PblpkltGeocodeSkippableException} 만 스킵한다(그 행만 실패 확정).
     * {@link GeocodeQuotaExceededException} 은 <b>스킵 목록에 없는 것만으로</b> Job 을
     * FAILED 로 끊는다 — 쿼터가 끝났는데 계속 돌면 남은 전량이 헛도는 데다 실패로 캐싱된다.
     * 목록에 넣는 순간 행 단위로 삼켜져 이 정책이 통째로 무력화되므로 늘리지 말 것.
     *
     * <p>⚠️ 이 Step 이 정상 종료하지 않으면 뒤따르는 소멸 마킹 Tasklet 이 스스로 물러난다
     * ({@link PblpkltSoftDeleteMarkingTasklet}) — 부분 수집분으로 마킹하면 멀쩡한 주차장이
     * 소멸 처리되기 때문이다.
     */
    @Bean
    public Step pblpkltFetchStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DhubPblpkltItemReader reader,                                // ← 빈 주입 (soft delete Step 과 공유)
            PblpkltItemWriter writer,
            org.springframework.batch.item.support.CompositeItemProcessor<
                    PblpkltOriginRow, PblpkltRecord> compositeProcessor,
            PblpkltSkipListener skipListener,
            @Value("${batch.pblpklt.chunk-size:100}") int chunkSize) {

        return new StepBuilder(STEP_NAME, jobRepository)
                .<PblpkltOriginRow, PblpkltRecord>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(compositeProcessor)
                .writer(writer)
                .faultTolerant()
                .skip(PblpkltGeocodeSkippableException.class)
                // 스킵은 정상 경로 — 주소DB에 없는 주소는 늘 일정 비율 있다.
                .skipLimit(Integer.MAX_VALUE)
                // 스킵 스캔 때 앞선 항목의 Processor 결과를 재사용한다 — 재처리 3배를 없앤다(클래스 주석).
                // 캐시 면에서는 중립이다 — 롤백된 청크의 캐시 쓰기 유실은 P 없이도 일어난다
                //    (CompositeGeocoder 메모리 히트가 재기록을 막는다). 주차장은 매 실행 전량을 다시
                //    지오코딩해 그 유실이 반복되므로, 막는 것은 save() 의 REQUIRES_NEW 다.
                .processorNonTransactional()
                .listener(skipListener)
                .build();
    }

    /**
     * 🔁 2026-08-12 추가 — soft delete 마킹 Step (가이드 3-2).
     *
     * <p>Reader 의 seen set 을 Tasklet 에 넘기는 게 핵심. 빈 wiring 시점에 Reader 가
     * {@code open()} 되어 seen set 이 채워진 상태여야 한다 — Spring Batch 가 Step 간 의존성을
     * 표현하는 방법은 없으므로, 같은 Step 내에서 Reader 의 {@code @StepScope} 와
     * {@link org.springframework.batch.core.listener.StepExecutionListener#beforeStep} 등을
     * 쓰지 않는 한 Job 내 다른 Step 의 상태를 직접 보기 어렵다.
     *
     * <p>현 구현은 Reader 가 {@link PblpkltFetchJobConfig#pblpkltFetchStep} 안에서
     * {@code new DhubPblpkltItemReader(...)} 로 직접 생성되기 때문에 두 Step 이 같은 Reader
     * 인스턴스를 공유하지 않는다 — 이 경우 두 번째 Step 의 Tasklet 이 "잘못된 seen set" 을 본다.
     *
     * <p>이를 막기 위해 Reader 를 빈으로 승격해 두 Step 이 같은 인스턴스를 공유하게 한다.
     */
    @Bean
    public DhubPblpkltItemReader pblpkltItemReader(
            DhubPblpkltApiClient apiClient, DhubProperties properties) {
        return new DhubPblpkltItemReader(apiClient, properties);
    }

    /**
     * 🔁 2026-08-12 — soft delete Tasklet Step. 같은 {@link DhubPblpkltItemReader} 빈을
     * 공유하므로, 앞 Step 이 끝난 시점의 {@code seenPkltIds} 를 그대로 본다.
     */
    @Bean
    public Step pblpkltSoftDeleteStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DhubPblpkltItemReader reader,
            javax.sql.DataSource dataSource) {
        PblpkltSoftDeleteMarkingTasklet tasklet =
                new PblpkltSoftDeleteMarkingTasklet(dataSource, reader);
        return new StepBuilder(SOFT_DELETE_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
