package jeju.datahub.batch.pblpklt;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.history.BatchRunHistoryRecorder;
import jeju.datahub.batch.history.BatchRunMetrics;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * {@code pblpkltFetchJob} 실행 1회를 {@code batch.tb_btch_excn_hstry} 에 남긴다 —
 * 다른 잡들과 <b>같은 테이블</b>이라 "언제 무엇이 얼마나 들어왔나"를 한 화면에서 본다.
 *
 * <h2>🔁 2026-09-12 — {@code BatchRunMetrics.none()} 을 걷어냈다</h2>
 * 종전에는 이 잡이 "공유 지오코딩 카운터를 참조하지 않는다"는 이유로 지표를 전부 NULL 로 뒀다.
 * 그 결과 <b>{@code emd_nm} 이름 폴백으로 채운 건수도, 미분류 유형도 이력에 한 줄도 남지
 * 않았다</b> — 안 남기면 "공간매칭이 잘 되고 있다" 는 착각이 굳는다. 이제
 * {@link PblpkltGeocodeProcessor}·{@link PblpkltSkipListener} 의 전용 카운터로 실제 지표를
 * 조립해 넘긴다({@code CvlcptJobSummaryListener} 와 같은 형태).
 *
 * <p>🔴 <b>채우지 않는 지표는 여전히 {@code null}</b> 이다 — 이 잡은 {@code CompositeGeocoder}·
 * {@code AdmDistResolver} 의 <b>공유</b> 카운터를 쓰는데(cvlcpt 와 같은 빈) 그 값을 여기서 읽으면
 * 다른 잡의 수치가 섞인다. 0 으로 채우면 "했는데 0건" 이라는 가짜 관측치가 추이 집계를 왜곡한다.
 *
 * <h2>🔴 {@code beforeJob} 에서 카운터를 비운다</h2>
 * 지표를 들고 있는 빈은 전부 싱글턴이다. 수동 트리거로 같은 JVM 에서 두 번 돌리면 2회차 이력에
 * 1회차 수치가 그대로 더해진다({@code CvlcptJobSummaryListener} 와 같은 근거). ID 배정기 리셋은
 * {@link PblpkltFetchJobConfig} 의 Job 조립 지점이 따로 맡는다 — 책임이 다르다.
 */
@Component
public class PblpkltFetchHistoryListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(PblpkltFetchHistoryListener.class);

    private final BatchRunHistoryRecorder historyRecorder;
    private final PblpkltGeocodeProcessor processor;
    private final PblpkltSkipListener skipListener;

    public PblpkltFetchHistoryListener(
            BatchRunHistoryRecorder historyRecorder,
            PblpkltGeocodeProcessor processor,
            PblpkltSkipListener skipListener) {
        this.historyRecorder = historyRecorder;
        this.processor = processor;
        this.skipListener = skipListener;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        processor.resetStats();
        skipListener.resetStats();
    }

    /** 실패한 Job 에서도 호출된다 — FAILED 야말로 "왜 죽었는지"가 남아야 한다. */
    @Override
    public void afterJob(JobExecution jobExecution) {
        Map<PblpkltGeocodeSkippableException.Reason, Long> skips = skipListener.skipCounts();
        Map<String, Long> unknownTypes = processor.unknownTypeCounts();

        // ⚠️ try/catch 는 트랜잭션 경계 바깥인 여기 — 이력 기록 실패가 수집 배치를 죽이면 안 된다.
        try {
            historyRecorder.record(jobExecution, new BatchRunMetrics(
                    skips.get(PblpkltGeocodeSkippableException.Reason.GEOCODE_FAILED),
                    skips.get(PblpkltGeocodeSkippableException.Reason.EMD_UNRESOLVED),
                    processor.transientFiltered(),
                    // geocode_* · emd_contains · emd_buffer 는 공유 카운터라 이 잡이 채우지 않는다.
                    null, null, null, null, null, null,
                    processor.unclassified(),
                    unknownTypes.isEmpty() ? null : unknownTypes,
                    processor.emdNameFallback()));
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3 — 기록(JDBC upsert · REQUIRES_NEW 트랜잭션 · 값 가공)에서 날 수 있는 예외만 잡는다.
            // 그 밖의 예외는 Spring Batch AbstractJob 이 afterJob 예외를 잡아 ERROR 로 남기므로 Job 결과는 바뀌지 않는다.
            log.warn("배치 실행 이력 기록 실패 — jobExecutionId={} (Job 결과에는 영향 없음)",
                    jobExecution.getId(), e);
        }

        processor.logStats();
    }
}
