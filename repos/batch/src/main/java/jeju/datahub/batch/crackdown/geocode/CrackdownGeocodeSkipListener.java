package jeju.datahub.batch.crackdown.geocode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import jeju.datahub.batch.geocode.GeocodeSkipRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.stereotype.Component;

/**
 * 단속 주소 스킵 확정 건을 공용 {@link GeocodeSkipRecorder} 로 기록하고 사유별로 센다
 * (TSK-4054 계획서 Step 6 · D7 · D13).
 *
 * <p>분담: <b>카운터는 이 리스너, 쓰기는 Recorder</b>. 이 클래스에는 SQL 도 {@code @Transactional} 도 없다 —
 * {@code REQUIRES_NEW} 는 별도 빈인 Recorder 가 프록시로 진다.
 *
 * <h2>{@code trgt_id} = {@code sha256(crdn_addr)}</h2>
 * 주소 단위라 행 ID 가 없고, {@code crdn_id} 는 환경마다 채번이 달라 쓸 수 없다(D7). 🔴 UTF-8 소문자
 * 16진이어야 Reader 의 SQL {@code encode(sha256(convert_to(crdn_addr,'UTF8')),'hex')} 와 같다 — 다르면
 * 스킵 주소를 매 실행 다시 읽는다. 🔁 2026-11-12 TSK-4336 — 시큐어코딩 2.4 로 md5 → SHA-256(64자,
 * {@code trgt_id VARCHAR(64)} — 마이그레이션 105).
 * 해시 입력은 Reader 가 읽은 원문 그대로다(trim 하지 않는다).
 *
 * <h2>🔴 제네릭은 Step 청크 타입 {@code <String, CrackdownCoord>} 와 같아야 한다</h2>
 * 어긋나면 {@code FaultTolerantStepBuilder.listener(Object)} 오버로드가 잡혀 애노테이션만 스캔하므로
 * 콜백이 0회 등록된다({@code PblpkltSkipListener} 주석 — TSK-4142 1회차 E2E 102건 유실).
 *
 * <h2>🔒 로그에 주소 원문을 남기지 않는다 — 민원 리스너와 다른 지점</h2>
 * 민원 {@code GeocodeSkipListener} 는 WARN 에 {@code addr} 를 찍지만, 단속 도메인은 장소 값을 로그에 남기지
 * 않는다({@code CrackdownOriginRow} "로깅"). 대신 {@code trgt_id}(SHA-256)를 찍어 스킵 테이블 행과 대조한다 —
 * 주소 원문은 {@code batch.tb_geocode_skip.trgt_addr} 에서 본다.
 */
@Component
public class CrackdownGeocodeSkipListener implements SkipListener<String, CrackdownCoord> {

    private static final Logger log = LoggerFactory.getLogger(CrackdownGeocodeSkipListener.class);

    /** 통합 스킵 테이블에서 이 파이프라인을 가리키는 값 — {@code GEOCODE_SRC_SE} 공통코드. */
    static final String SRC_SE = "CRDN";

    private final GeocodeSkipRecorder recorder;

    /** 사유별 스킵 건수 — {@code skip_geocode_failed}·{@code skip_emd_unresolved} 의 출처(Step 7). */
    private final Map<CrackdownGeocodeSkippableException.Reason, AtomicLong> skipCounts =
            new EnumMap<>(CrackdownGeocodeSkippableException.Reason.class);

    public CrackdownGeocodeSkipListener(GeocodeSkipRecorder recorder) {
        this.recorder = recorder;
        for (CrackdownGeocodeSkippableException.Reason reason : CrackdownGeocodeSkippableException.Reason.values()) {
            skipCounts.put(reason, new AtomicLong());
        }
    }

    /**
     * Processor 가 던진 스킵 예외 1건을 기록한다. 기록 실패는 삼키지 않는다 — Step 이 실패로 드러낸다.
     *
     * @param item 스킵된 주소. 예외가 주소를 들고 오므로 기록에는 쓰지 않는다
     */
    @Override
    public void onSkipInProcess(String item, Throwable t) {
        if (!(t instanceof CrackdownGeocodeSkippableException skip)) {
            // skip() 목록에 이 예외만 등록되므로 평시엔 오지 않는다. 왔다면 Step 조립이 바뀐 것이다.
            log.error("스킵 사유를 해석할 수 없어 기록하지 않습니다. type={}",
                    t == null ? null : t.getClass().getName(), t);
            return;
        }
        String trgtId = sha256Hex(skip.address());
        recorder.record(SRC_SE, trgtId, skip.reason().name(), skip.note(), skip.address());
        skipCounts.get(skip.reason()).incrementAndGet();
        log.warn("스킵 기록 [{}] trgt_id={} note={}", skip.reason(), trgtId, skip.note());
    }

    /** 사유별 누계(이번 실행). */
    public Map<CrackdownGeocodeSkippableException.Reason, Long> skipCounts() {
        return skipCounts.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().get(),
                (a, b) -> a, () -> new EnumMap<>(CrackdownGeocodeSkippableException.Reason.class)));
    }

    /** 이번 실행의 총 스킵 건수. */
    public long totalSkipped() {
        return skipCounts.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /** 카운터를 0 으로 되돌린다 — <b>Job 시작 시점에 호출한다</b>(싱글턴이라 실행 간 누적 방지). */
    public void resetStats() {
        skipCounts.values().forEach(counter -> counter.set(0));
    }

    /** UTF-8 소문자 16진 SHA-256 — PostgreSQL {@code encode(sha256(convert_to(x,'UTF8')),'hex')} 와 같은 값({@code CrackdownOriginRow.sha256} 와 같은 방식). */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JVM 필수 알고리즘 — 도달 불가. 도달하면 환경 문제이므로 즉시 드러낸다.
            throw new IllegalStateException("SHA-256 MessageDigest 를 사용할 수 없습니다.", e);
        }
    }
}
