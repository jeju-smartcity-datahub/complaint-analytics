package jeju.datahub.batch.cvlcpt;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import jeju.datahub.batch.cvlcpt.api.ComplaintOriginRow;
import jeju.datahub.batch.geocode.GeocodeSkipRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.stereotype.Component;

/**
 * 스킵 확정 건을 {@code batch.tb_geocode_skip} 에 기록한다.

 * <p>🔁 2026-08-24 — 민원·공영주차장 스킵 테이블이 하나로 합쳐졌다(46-UNIFY-GEOCODE-SKIP.sql).
 * 어느 파이프라인에서 실패했는지는 {@link #SRC_SE} 가 구분하고, PK 가 복합키
 * {@code (src_se, trgt_id)} 라 두 체계의 키 값이 겹쳐도 서로를 덮지 않는다.
 *
 * <p>Reader 가 이 테이블을 {@code NOT EXISTS} 로 영구 제외하므로, 여기 남는 것은
 * <b>다시 시도해도 결과가 같은 건</b>뿐이다 — {@code GEOCODE_FAILED}(주소 없음) ·
 * {@code EMD_UNRESOLVED}(행정동 경계 밖) 2종. 일시 장애({@code TRANSIENT})는 Processor 가
 * {@code null} 로 걸러 내며 <b>여기 오지 않는다</b>(D8 개정 — 넣으면 장애가 영구 제외로 굳는다).
 *
 * <p>표 쓰기와 {@code REQUIRES_NEW} 는 {@link GeocodeSkipRecorder} 가 진다 — 리스너에 SQL·트랜잭션
 * 애노테이션을 다시 넣지 않는다. 여기 남는 것은 카운터·로그·사유 해석뿐이다.
 *
 * <h2>재실행하면 마지막 판정이 남는다</h2>
 * {@code ON CONFLICT (src_se, trgt_id) DO UPDATE} 로 {@code fail_rsn}·{@code note}·{@code checked_dt}
 * 을 갱신한다. 스킵 행을 지우고 다시 돌렸을 때 이전 판정이 남아 혼동되는 일이 없다.
 */
@Component
public class GeocodeSkipListener implements SkipListener<ComplaintOriginRow, CvlcptRecord> {

    private static final Logger log = LoggerFactory.getLogger(GeocodeSkipListener.class);

    /** 통합 스킵 테이블에서 이 파이프라인을 가리키는 값 — {@code GEOCODE_SRC_SE} 공통코드. */
    static final String SRC_SE = "CVLCPT";

    private final GeocodeSkipRecorder recorder;

    /** 사유별 스킵 건수 — Job 종료 요약({@code skipped: GEOCODE_FAILED=39 ...})의 원천. */
    private final Map<GeocodeSkippableException.Reason, AtomicLong> skipCounts =
            new EnumMap<>(GeocodeSkippableException.Reason.class);

    public GeocodeSkipListener(GeocodeSkipRecorder recorder) {
        this.recorder = recorder;
        for (GeocodeSkippableException.Reason reason : GeocodeSkippableException.Reason.values()) {
            skipCounts.put(reason, new AtomicLong());
        }
    }

    /**
     * Processor 가 던진 스킵 예외 1건을 기록한다.
     *
     * <p>스킵 사유·주소·단계 내역({@code note})은 예외가 그대로 들고 온다 — 원본 행을 다시 조회하지
     * 않는다({@link GeocodeSkippableException} 설계).
     *
     * @param item 스킵된 원본 행. 예외에 이미 식별자가 있어 기록에는 쓰지 않고 로그 대조용으로만 둔다
     */
    @Override
    public void onSkipInProcess(ComplaintOriginRow item, Throwable t) {
        if (!(t instanceof GeocodeSkippableException skip)) {
            // skip() 목록에 GeocodeSkippableException 만 등록돼 있어 평시엔 오지 않는다.
            // 왔다면 Step 조립이 바뀐 것이므로 조용히 넘기지 않고 드러낸다.
            log.error("스킵 사유를 해석할 수 없어 기록하지 않습니다. cvlcpt_id={} type={}",
                    item == null ? null : item.cvlcptId(), t == null ? null : t.getClass().getName(), t);
            return;
        }
        recorder.record(SRC_SE, skip.cvlcptId(), skip.reason().name(), skip.note(), skip.address());
        skipCounts.get(skip.reason()).incrementAndGet();
        log.warn("스킵 기록 [{}] cvlcpt_id={} addr={} note={}",
                skip.reason(), skip.cvlcptId(), skip.address(), skip.note());
    }

    /** 사유별 누계(이번 실행). Job 종료 요약 로그가 읽는다. */
    public Map<GeocodeSkippableException.Reason, Long> skipCounts() {
        return skipCounts.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().get(),
                (a, b) -> a, () -> new EnumMap<>(GeocodeSkippableException.Reason.class)));
    }

    /** 이번 실행의 총 스킵 건수. */
    public long totalSkipped() {
        return skipCounts.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /**
     * 카운터를 0 으로 되돌린다 — <b>Job 시작 시점에 호출한다</b>.
     *
     * <p>싱글턴 빈이라 리셋하지 않으면 두 번째 실행의 요약에 1회차 스킵 건수가 그대로 더해진다.
     * 테이블에 쌓인 누적 건수는 {@code batch.v_geocode_skip_diagnosis} 로 따로 볼 수 있으므로,
     * 이 카운터는 "이번 실행에서 새로 스킵된 수"를 뜻하게 둔다.
     */
    public void resetStats() {
        skipCounts.values().forEach(counter -> counter.set(0));
    }
}
