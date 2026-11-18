package jeju.datahub.batch.pblpklt;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.geocode.GeocodeSkipRecorder;
import jeju.datahub.batch.pblpklt.api.PblpkltOriginRow;

/**
 * 스킵 확정 건을 {@code batch.tb_geocode_skip} 에 기록한다.

 * <p>🔁 2026-08-24 — 민원·공영주차장 스킵 테이블이 하나로 합쳐졌다(46-UNIFY-GEOCODE-SKIP.sql).
 * 어느 파이프라인에서 실패했는지는 {@link #SRC_SE} 가 구분한다.
 *
 * <p>{@link PblpkltGeocodeProcessor} 가 던진 {@link PblpkltGeocodeSkippableException} 2종
 * ({@code GEOCODE_FAILED} / {@code EMD_UNRESOLVED}) 만 받아 기록한다 — 일시 장애
 * ({@code TRANSIENT})는 Processor 가 {@code null} 로 걸러 내며 <b>여기로 오지 않는다</b>.
 * Reader 의 {@code NOT EXISTS} 가 이 테이블을 영구 제외하므로(가이드 8), 다시 시도해 봐야 같은
 * 결과인 건만 여기로 흘러간다.
 *
 * <p>표 쓰기와 {@code REQUIRES_NEW} 는 {@link GeocodeSkipRecorder} 가 진다 — 리스너에 SQL·트랜잭션
 * 애노테이션을 다시 넣지 않는다. 여기 남는 것은 카운터·로그·사유 해석뿐이다.
 *
 * <h2>🔴 제네릭 실인자는 <b>Step 청크 타입</b>과 같아야 한다 (TSK-4142 §10-1 A)</h2>
 * 이 리스너는 한때 {@code SkipListener<PblpkltFetchRow, PblpkltRecord>} 였다. 그런데
 * {@code pblpkltFetchStep} 의 청크 타입은 {@code <}{@link PblpkltOriginRow}{@code ,
 * PblpkltRecord>} 다 — {@link PblpkltFetchRow} 는 {@code CompositeItemProcessor} <b>내부의
 * 중간 타입</b>일 뿐 Reader 가 내놓는 아이템이 아니다. 타입이 어긋나면
 * {@code FaultTolerantStepBuilder.listener(Object)} 오버로드가 잡히고, 그 경로는
 * {@code @OnSkipInProcess} <b>애노테이션만</b> 스캔하므로 애노테이션이 없는 이 클래스는
 * <b>콜백이 0회</b> 등록된다 — 컴파일 에러도 경고도 없이 스킵 기록이 통째로 사라진다
 * (spring-batch-core 5.2.1 실측 · 1회차 E2E 에서 102건 유실).
 *
 * <p>🔴 애노테이션을 덧붙이는 우회로 때우지 말고 <b>제네릭을 청크 타입에 맞춘다</b> — 그래야
 * {@code listener(SkipListener<? super I, ? super O>)} typed 오버로드가 선택된다.
 * 스킵 예외는 Processor 체인 안쪽({@code PblpkltGeocodeProcessor})에서 나지만, Spring Batch 가
 * 콜백에 넘기는 것은 그 <b>중간 타입이 아니라 Reader 가 읽은 원본 아이템</b>이다.
 */
@Component
public class PblpkltSkipListener implements SkipListener<PblpkltOriginRow, PblpkltRecord> {

    private static final Logger log = LoggerFactory.getLogger(PblpkltSkipListener.class);

    /** 통합 스킵 테이블에서 이 파이프라인을 가리키는 값 — {@code GEOCODE_SRC_SE} 공통코드. */
    static final String SRC_SE = "PBLPKLT";

    private final GeocodeSkipRecorder recorder;

    private final Map<PblpkltGeocodeSkippableException.Reason, AtomicLong> skipCounts =
            new EnumMap<>(PblpkltGeocodeSkippableException.Reason.class);

    public PblpkltSkipListener(GeocodeSkipRecorder recorder) {
        this.recorder = recorder;
        for (PblpkltGeocodeSkippableException.Reason reason : PblpkltGeocodeSkippableException.Reason.values()) {
            skipCounts.put(reason, new AtomicLong());
        }
    }

    @Override
    public void onSkipInProcess(PblpkltOriginRow item, Throwable t) {
        if (!(t instanceof PblpkltGeocodeSkippableException skip)) {
            log.error("스킵 사유를 해석할 수 없어 기록하지 않습니다. origin_pk={} type={}",
                    item == null ? null : item.pkltId(),
                    t == null ? null : t.getClass().getName(), t);
            return;
        }
        // 현재 동작 유지 — PBLPKLT 는 trgt_addr 를 기록해 온 적이 없다(기록 시작은 별도 결정).
        recorder.record(SRC_SE, skip.originPk(), skip.reason().name(), skip.note(), null);
        skipCounts.get(skip.reason()).incrementAndGet();
        log.warn("스킵 기록 [{}] origin_pk={} addr={} note={}",
                skip.reason(), skip.originPk(), skip.address(), skip.note());
    }

    public Map<PblpkltGeocodeSkippableException.Reason, Long> skipCounts() {
        return skipCounts.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().get(),
                (a, b) -> a, () -> new EnumMap<>(PblpkltGeocodeSkippableException.Reason.class)));
    }

    public long totalSkipped() {
        return skipCounts.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /**
     * 🔴 이 빈은 싱글턴이라 Job 을 같은 JVM 에서 두 번 돌리면 2회차 이력에 1회차 스킵 수가
     * 그대로 더해진다. {@link PblpkltFetchHistoryListener#beforeJob} 이 Job 시작마다 비운다
     * (TSK-4142 §7-3 — 이력에 실제 지표를 넣으면서 필요해졌다. 스킵 <b>테이블</b>의 행은
     * 건드리지 않는다 — 여기서 0 으로 되돌리는 것은 이번 실행분 카운터뿐이다).
     */
    public void resetStats() {
        skipCounts.values().forEach(counter -> counter.set(0));
    }
}