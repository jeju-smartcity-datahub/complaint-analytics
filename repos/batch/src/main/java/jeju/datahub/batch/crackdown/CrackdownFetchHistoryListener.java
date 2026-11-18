package jeju.datahub.batch.crackdown;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.admdst.AdmDistResolver;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeProcessor;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeSkipListener;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeSkippableException;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.history.BatchRunHistoryRecorder;
import jeju.datahub.batch.history.BatchRunMetrics;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * {@code crackdownFetchJob} 실행 1회를 {@code batch.tb_btch_excn_hstry} 에 남긴다 —
 * 다른 잡들과 <b>같은 테이블</b>이라 "언제 무엇이 얼마나 들어왔나"를 한 화면에서 본다.
 *
 * <h2>🔁 2026-09-15(TSK-4054 Step 7) — {@code BatchRunMetrics.none()} 을 걷어냈다</h2>
 * 종전에는 "수집 잡이라 Processor 지표가 없다" 는 이유로 지표를 전부 NULL 로 뒀다. 이제 이 잡이
 * 좌표 사전 전파 → 주소 단위 지오코딩({@code crackdownGeocodeStep})까지 하므로 지오코딩·행정동·스킵
 * 지표를 채운다({@code CvlcptJobSummaryListener} 와 같은 getter). 출처:
 * <ul>
 *   <li>{@code geocode_api_lookups}·{@code geocode_cache_hits}·{@code geocode_place_recoveries}·
 *       {@code geocode_stripped} — {@link CompositeGeocoder}(공유 싱글턴)</li>
 *   <li>{@code emd_contains}·{@code emd_buffer} — {@link AdmDistResolver}(공유 싱글턴)</li>
 *   <li>{@code skip_geocode_failed}·{@code skip_emd_unresolved} — {@link CrackdownGeocodeSkipListener}</li>
 *   <li>{@code filtered_transient} — {@link CrackdownGeocodeProcessor}</li>
 * </ul>
 * 분류({@code unclsf_cnt}·{@code unknown_types})와 이름 폴백({@code emd_name_fallback})은 이 잡이 하지
 * 않으므로 {@code null} 이다("했는데 0건" 이 아니라 "하지 않는다").
 * 사전 전파 행수는 이력 컬럼이 없어 INFO 로그로만 남는다({@link CrackdownCoordDictionaryTasklet}).
 *
 * <h2>🔴 {@code beforeJob} 에서 카운터를 비운다 · ⚠️ 민원 잡과 동시 실행 금지 (D10)</h2>
 * 지표를 들고 있는 빈은 전부 싱글턴이라 같은 JVM 에서 두 번 돌리면 2회차 이력에 1회차 수치가 더해진다.
 * 그런데 {@code CompositeGeocoder}·{@code AdmDistResolver} 는 {@code complaintFetchJob}(02:00 스케줄)과
 * <b>같은 인스턴스</b>라, 두 잡이 겹쳐 돌면 서로의 리셋·증가가 섞여 <b>두 이력이 모두 틀린다.</b>
 * 또 {@code read_cnt}·{@code write_cnt} 는 Step 합산이라 <b>수집 행수 + 지오코딩 주소 수</b>로 단위가 섞인다.
 */
@Component
public class CrackdownFetchHistoryListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(CrackdownFetchHistoryListener.class);

    private final BatchRunHistoryRecorder historyRecorder;
    private final CompositeGeocoder geocoder;
    private final AdmDistResolver admDistResolver;
    private final CrackdownGeocodeSkipListener skipListener;
    private final CrackdownGeocodeProcessor processor;

    public CrackdownFetchHistoryListener(
            BatchRunHistoryRecorder historyRecorder,
            CompositeGeocoder geocoder,
            AdmDistResolver admDistResolver,
            CrackdownGeocodeSkipListener skipListener,
            CrackdownGeocodeProcessor processor) {
        this.historyRecorder = historyRecorder;
        this.geocoder = geocoder;
        this.admDistResolver = admDistResolver;
        this.skipListener = skipListener;
        this.processor = processor;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        geocoder.resetStats();
        admDistResolver.resetStats();
        skipListener.resetStats();
        processor.resetStats();
    }

    /** 실패한 Job 에서도 호출된다 — FAILED 야말로 "왜 죽었는지"가 남아야 한다. */
    @Override
    public void afterJob(JobExecution jobExecution) {
        Map<CrackdownGeocodeSkippableException.Reason, Long> skips = skipListener.skipCounts();

        // ⚠️ try/catch 는 트랜잭션 경계 바깥인 여기 — 이력 기록 실패가 배치를 죽이면 안 된다.
        try {
            historyRecorder.record(jobExecution, new BatchRunMetrics(
                    skips.get(CrackdownGeocodeSkippableException.Reason.GEOCODE_FAILED),
                    skips.get(CrackdownGeocodeSkippableException.Reason.EMD_UNRESOLVED),
                    processor.transientFiltered(),
                    geocoder.apiLookups(), geocoder.cacheHits(),
                    geocoder.placeRecoveries(), geocoder.strippedRecoveries(),
                    admDistResolver.containsMatches(), admDistResolver.bufferMatches(),
                    // unclsf_cnt·unknown_types — 이 잡은 유형 분류를 하지 않는다.
                    null, null,
                    // emd_name_fallback — 이름 폴백은 pblpkltFetchJob 에만 있다.
                    null));
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — 기록(JDBC upsert · REQUIRES_NEW 트랜잭션 · 값 가공)에서 날 수 있는 예외만 잡는다.
            // 그 밖의 예외는 Spring Batch AbstractJob 이 afterJob 예외를 잡아 ERROR 로 남기므로 Job 결과는 바뀌지 않는다.
            log.warn("배치 실행 이력 기록 실패 — jobExecutionId={} (Job 결과에는 영향 없음)",
                    jobExecution.getId(), e);
        }
    }
}
