package jeju.datahub.batch.cvlcpt;

import java.util.LinkedHashMap;
import java.util.Map;
import jeju.datahub.batch.cvlcpt.api.ComplaintReadProgress;
import jeju.datahub.batch.admdst.AdmDistResolver;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.history.BatchRunHistoryRecorder;
import jeju.datahub.batch.history.BatchRunMetrics;
import jeju.datahub.batch.ops.JobLiveMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Job 시작 시 카운터 리셋 + 종료 시 한 줄 요약 (계획서 Step 7-4).
 *
 * <pre>
 * [cvlcptGeocodeJob] status=COMPLETED read=156883 written=156750
 *   skipped: GEOCODE_FAILED=39 EMD_UNRESOLVED=9
 *   filtered(TRANSIENT)=0
 *   geocode: apiLookups=12419 cacheHits=67719 placeRecoveries=39 strippedRecoveries=12
 *   emd: contains=80129 buffer=27
 *   미지 유형 12종 76745건 → 99 적재
 * </pre>
 *
 * <p>Node 프로토타입의 {@code [4/4]} 요약을 승계한다. 흩어져 있던 수치(Processor·Geocoder·Resolver·
 * 스킵 리스너)를 한 블록으로 모으는 것이 목적이다 — <b>읽고 바로 판단할 수 있어야 한다.</b>
 *
 * <h2>🔴 {@code beforeJob} 의 리셋이 없으면 요약이 거짓말을 한다</h2>
 * 카운터를 들고 있는 빈들은 전부 {@code @Component} 싱글턴이다. 수동 트리거로 같은 JVM 에서 Job 을
 * 두 번 돌리면(멱등성 검증이 정확히 그 경우다) 2회차 요약에 1회차 수치가 그대로 더해져
 * "이번 실행에 무슨 일이 있었는지"를 알 수 없게 된다. 그래서 Job 시작 시점에 전부 0 으로 되돌린다.
 * (Step 의 read/write 는 {@link StepExecution} 이 실행마다 새로 만들어 주므로 리셋 대상이 아니다.)
 *
 * <p>{@code TRANSIENT} 는 스킵 테이블에 넣지 않으므로(D8) <b>{@code filtered(TRANSIENT)} 로만</b>
 * 관측된다. 이 값이 0 이 아니면 주소 단위 내역은 {@code batch.tb_geocode_cache} 의
 * {@code fail_rsn='TRANSIENT'} 행으로 확인한다.
 *
 * <h2>같은 수치를 {@code batch.tb_btch_excn_hstry} 에도 남긴다</h2>
 * 위 요약은 <b>로그라 사라진다</b>. "지난 실행 대비 캐시가 듣고 있나", "미분류가 튄 날이 언제인가"
 * 같은 질문은 SQL 로 물어야 하므로 {@link BatchRunHistoryRecorder} 가 같은 값을 한 행으로 적재한다
 * — <b>새로 계산하는 지표는 없다.</b>
 */
@Component
public class CvlcptJobSummaryListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(CvlcptJobSummaryListener.class);

    private final CvlcptGeocodeProcessor processor;
    private final CompositeGeocoder geocoder;
    private final AdmDistResolver admDistResolver;
    private final GeocodeSkipListener skipListener;
    private final BatchRunHistoryRecorder historyRecorder;
    private final ComplaintReadProgress readProgress;
    private final JobLiveMetrics liveMetrics;

    public CvlcptJobSummaryListener(
            CvlcptGeocodeProcessor processor,
            CompositeGeocoder geocoder,
            AdmDistResolver admDistResolver,
            GeocodeSkipListener skipListener,
            BatchRunHistoryRecorder historyRecorder,
            ComplaintReadProgress readProgress,
            JobLiveMetrics liveMetrics) {
        this.processor = processor;
        this.geocoder = geocoder;
        this.admDistResolver = admDistResolver;
        this.skipListener = skipListener;
        this.historyRecorder = historyRecorder;
        this.readProgress = readProgress;
        this.liveMetrics = liveMetrics;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        processor.resetStats();
        geocoder.resetStats();
        admDistResolver.resetStats();
        skipListener.resetStats();
        readProgress.reset();
        // TSK-4337 추가 — 운영 화면이 실행 중에 "API 수집 → 지오코딩 → DB 적재" 를 그릴 수치. 값은 조회 때마다 읽는다.
        liveMetrics.register(jobExecution.getId(), this::liveSnapshot);
        log.info("[{}] 시작 — jobExecutionId={} params={}",
                jobExecution.getJobInstance().getJobName(), jobExecution.getId(),
                jobExecution.getJobParameters());
    }

    /** 실패한 Job 에서도 호출된다 — 어디까지 하고 죽었는지가 오히려 그때 더 필요하다. */
    @Override
    public void afterJob(JobExecution jobExecution) {
        liveMetrics.unregister(jobExecution.getId());
        long read = 0;
        long written = 0;
        for (StepExecution step : jobExecution.getStepExecutions()) {
            read += step.getReadCount();
            written += step.getWriteCount();
        }

        Map<GeocodeSkippableException.Reason, Long> skips = skipListener.skipCounts();
        Map<String, Long> unknownTypes = processor.unknownTypeCounts();

        log.info("""
                        [{}] status={} read={} written={}
                          skipped: GEOCODE_FAILED={} EMD_UNRESOLVED={}
                          filtered(TRANSIENT)={}
                          geocode: apiLookups={} cacheHits={} placeRecoveries={} strippedRecoveries={}
                          emd: contains={} buffer={}
                          미지 유형 {}종 {}건 → {} 적재""",
                jobExecution.getJobInstance().getJobName(), jobExecution.getStatus(), read, written,
                skips.get(GeocodeSkippableException.Reason.GEOCODE_FAILED),
                skips.get(GeocodeSkippableException.Reason.EMD_UNRESOLVED),
                processor.transientFiltered(),
                geocoder.apiLookups(), geocoder.cacheHits(),
                geocoder.placeRecoveries(), geocoder.strippedRecoveries(),
                admDistResolver.containsMatches(), admDistResolver.bufferMatches(),
                unknownTypes.size(), processor.unclassified(), CvlcptCodeMapping.TYPE_CD_ETC);

        // 로그로 뱉은 그 값을 그대로 한 행으로 남긴다(계획서 batch-run-history Step 2).
        // ⚠️ try/catch 는 여기(트랜잭션 경계 바깥)에 둔다 — record() 안에서 잡으면 커밋 시점의
        //    실패를 못 잡는다. 이력 기록 때문에 15만 행 배치를 실패로 만들 수는 없다.
        try {
            historyRecorder.record(jobExecution, new BatchRunMetrics(
                    skips.get(GeocodeSkippableException.Reason.GEOCODE_FAILED),
                    skips.get(GeocodeSkippableException.Reason.EMD_UNRESOLVED),
                    processor.transientFiltered(),
                    geocoder.apiLookups(), geocoder.cacheHits(),
                    geocoder.placeRecoveries(), geocoder.strippedRecoveries(),
                    admDistResolver.containsMatches(), admDistResolver.bufferMatches(),
                    processor.unclassified(), unknownTypes,
                    // emd_name_fallback — 이름 폴백은 pblpkltFetchJob 에만 있다(TSK-4142 §7-3).
                    // 🔴 0 이 아니라 null 이다: "했는데 0건" 이 아니라 "이 잡은 하지 않는다".
                    null));
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — 기록(JDBC upsert · REQUIRES_NEW 트랜잭션 · 값 가공)에서 날 수 있는 예외만 잡는다.
            // 그 밖의 예외는 Spring Batch AbstractJob 이 afterJob 예외를 잡아 ERROR 로 남기므로 Job 결과는 바뀌지 않는다.
            log.warn("배치 실행 이력 기록 실패 — jobExecutionId={} (Job 결과에는 영향 없음). "
                    + "종료 요약은 위 로그에 남아 있습니다.", jobExecution.getId(), e);
        }

        // 비율 경고(미지 유형 급등 / 2차 매칭 급증)는 각 컴포넌트가 자기 기준으로 판단한다 —
        // 위 요약은 "이번 실행에 무슨 일이 있었나", 아래 두 줄은 "그게 정상 범위인가"를 말한다.
        processor.logStats();
        admDistResolver.logStats();

        if (skipListener.totalSkipped() > 0) {
            log.info("스킵 원인은 batch.v_geocode_skip_diagnosis 로 조회한다 — "
                    + "예: SELECT fail_rsn, count(*) FROM batch.v_geocode_skip_diagnosis GROUP BY 1;");
        }
    }

    /**
     * 실행 중 단계별 수치 — 키 이름이 운영 API {@code GET /api/batch/ops/executions/{id}/live} 의 {@code metrics} 계약이다
     * (백엔드 {@code BatchOpsLiveVO} 와 같은 이름). DB 적재 수는 여기 없다 — {@code batch_step_execution.write_count} 가 정본이다.
     */
    Map<String, Object> liveSnapshot() {
        Map<GeocodeSkippableException.Reason, Long> skips = skipListener.skipCounts();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dayIdx", readProgress.dayIdx());
        m.put("dayCnt", readProgress.dayCnt());
        m.put("crntYmd", readProgress.currentDay() == null ? null : readProgress.currentDay().toString());
        m.put("fetchCnt", readProgress.fetched());
        m.put("prcsCnt", processor.processed());
        m.put("geocodedCnt", processor.geocoded());
        m.put("unclsfCnt", processor.unclassified());
        m.put("filteredTransient", processor.transientFiltered());
        m.put("geocodeCacheHits", geocoder.cacheHits());
        m.put("geocodeApiLookups", geocoder.apiLookups());
        m.put("skipGeocodeFailed", skips.get(GeocodeSkippableException.Reason.GEOCODE_FAILED));
        m.put("skipEmdUnresolved", skips.get(GeocodeSkippableException.Reason.EMD_UNRESOLVED));
        return m;
    }
}
