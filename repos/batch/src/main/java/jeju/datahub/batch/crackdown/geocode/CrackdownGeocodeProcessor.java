package jeju.datahub.batch.crackdown.geocode;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import jeju.datahub.batch.admdst.AdmDistMatch;
import jeju.datahub.batch.admdst.AdmDistResolver;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeAddress.AddressKind;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeAddress.GeocodeInput;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.geocode.Coordinate;
import jeju.datahub.batch.geocode.GeocodeOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * 고유 단속 주소 1건 → 좌표·행정동.
 *
 * <pre>
 * 1. CrackdownGeocodeAddress.build(crdn_addr) → (kind, query)
 * 2. kind 별
 *      JIBUN·ROAD → CompositeGeocoder.geocode(query)
 *        ├ FOUND     → 3
 *        ├ NOT_FOUND → GEOCODE_FAILED 스킵 예외
 *        └ TRANSIENT → null(filter) + transientFiltered. 스킵 기록 없음
 *      PLACE      → 🔴 지오코더를 부르지 않고 곧바로 GEOCODE_FAILED 스킵 예외
 * 3. AdmDistResolver.resolve(lat, lot)
 *      ├ matched → CrackdownCoord
 *      └ 실패    → EMD_UNRESOLVED 스킵 예외 — 좌표도 쓰지 않는다(D6)
 * </pre>
 *
 * <h2>🔴 장소명은 지오코딩하지 않는다</h2>
 * 장소명 최선 조합의 제주 안 행수 가중 성공률이 13.4% 로 게이트(50%)에 못 미쳐 장소명 채택 규칙
 * ({@code CrackdownPlaceGeocoder})을 만들지 않았다. 부르면 쿼터만 쓰고 타 지역 동명 POI 위험만 남는다.
 * 장소명 좌표의 출처는 같은 주소의 기존 좌표 전파({@code CrackdownCoordDictionaryTasklet})뿐이다.
 * 스킵 기록으로 영구 제외되므로, 채택 규칙이 생기면 런북 §3-5 절차로 {@code CRDN} 장소명 스킵을 해제한다.
 *
 * <h2>실패 3갈래는 민원({@code CvlcptGeocodeProcessor})과 같다</h2>
 * {@code TRANSIENT} 를 {@code NOT_FOUND} 처럼 스킵 기록하면 Reader 의 {@code NOT EXISTS} 가 일시 장애를
 * 영구 제외로 굳힌다. 쿼터 초과({@code GeocodeQuotaExceededException})는 <b>잡지 않고</b> 그대로 전파해
 * Job 을 FAILED 로 끊는다(D9) — 커밋된 청크는 유효하고 재실행이 남은 주소부터 잇는다.
 *
 * <h2>🔒 note 에는 값을 싣지 않는다</h2>
 * 주소 원문은 스킵 테이블 {@code trgt_addr} 에 따로 들어간다. note 는 단계 판정만 담는다 — 지오코더 내역의
 * {@code stripped(주소)}·{@code place=ADDR_MISMATCH(지번)} 괄호 값은 {@link #withoutValues} 가 걷어낸다.
 * {@code EMD_UNRESOLVED} 의 좌표·최근접 거리는 "버퍼만 넓히면 살아나는지" 판단 근거라 남긴다.
 * 로그에도 주소 원문을 남기지 않는다({@code CrackdownOriginRow} "로깅").
 */
@Component
public class CrackdownGeocodeProcessor implements ItemProcessor<String, CrackdownCoord> {

    private static final Logger log = LoggerFactory.getLogger(CrackdownGeocodeProcessor.class);

    /** 장소명 — 지오코더를 부르지 않았다는 판정. 주소 원문을 싣지 않는다. */
    static final String PLACE_NOT_ATTEMPTED_NOTE = "kind=PLACE, place=NOT_ATTEMPTED(step4-skipped)";

    /** 지오코더가 단계 내역을 주지 못했을 때의 판정(평시엔 {@code CompositeGeocoder} 가 항상 채운다). */
    static final String NO_DETAIL = "detail=(없음)";

    /**
     * 단계 이름에 바로 붙은 괄호 값 — {@code stripped(제주특별자치도 …)} · {@code ADDR_MISMATCH(…)}.
     * 영문자 바로 뒤의 괄호만 걷어내 {@code stripped=(해당없음)}·{@code 캐시 히트(…)} 같은 설명은 남긴다.
     * 한 겹 중첩 괄호까지 한 덩어리로 본다.
     */
    private static final Pattern VALUE_IN_PARENS = Pattern.compile("(?<=[A-Za-z_])\\((?:[^()]|\\([^()]*\\))*\\)");

    private final CrackdownGeocodeAddress addressBuilder;
    private final CompositeGeocoder geocoder;
    private final AdmDistResolver admDistResolver;

    private final AtomicLong transientFiltered = new AtomicLong();

    public CrackdownGeocodeProcessor(
            CrackdownGeocodeAddress addressBuilder, CompositeGeocoder geocoder, AdmDistResolver admDistResolver) {
        this.addressBuilder = addressBuilder;
        this.geocoder = geocoder;
        this.admDistResolver = admDistResolver;
    }

    /**
     * @param crdnAddr Reader 가 읽은 고유 주소 원문(🔴 가공하지 않고 결과에 그대로 싣는다)
     * @return 좌표·행정동. <b>{@code null} 이면 이번 실행에서만 제외</b>(지오코딩 일시 장애)
     * @throws CrackdownGeocodeSkippableException 실패 확정 — {@code GEOCODE_FAILED} 또는 {@code EMD_UNRESOLVED}
     */
    @Override
    public CrackdownCoord process(String crdnAddr) {
        GeocodeInput input = addressBuilder.build(crdnAddr);
        AddressKind kind = input.kind();

        if (kind == AddressKind.PLACE) {
            throw new CrackdownGeocodeSkippableException(
                    CrackdownGeocodeSkippableException.Reason.GEOCODE_FAILED, crdnAddr, PLACE_NOT_ATTEMPTED_NOTE);
        }

        // 쿼터 예외는 잡지 않는다 — Job FAILED 가 정책이다(D9).
        GeocodeOutcome outcome = geocoder.geocode(input.query());
        switch (outcome.status()) {
            case TRANSIENT -> {
                transientFiltered.incrementAndGet();
                log.debug("단속 주소 지오코딩 일시 장애로 이번 실행에서 제외 kind={}", kind);
                return null;
            }
            case NOT_FOUND -> throw new CrackdownGeocodeSkippableException(
                    CrackdownGeocodeSkippableException.Reason.GEOCODE_FAILED, crdnAddr,
                    "kind=" + kind + ", " + withoutValues(outcome.detail()));
            case FOUND -> {
                // 아래로 진행
            }
        }

        Coordinate point = outcome.point();
        BigDecimal lat = point.lat();
        BigDecimal lot = point.lot();
        AdmDistMatch match = admDistResolver.resolve(lat, lot);
        if (!match.matched()) {
            throw new CrackdownGeocodeSkippableException(
                    CrackdownGeocodeSkippableException.Reason.EMD_UNRESOLVED, crdnAddr,
                    "kind=" + kind + ", lat=" + lat + " lot=" + lot + ", " + match.describeNearest());
        }
        return new CrackdownCoord(crdnAddr, lat, lot, match.emdCd());
    }

    /** 지오코더 단계 내역에서 괄호 값(주소·지번)을 걷어낸다. 내역이 없으면 {@link #NO_DETAIL}. */
    static String withoutValues(String detail) {
        if (detail == null || detail.isBlank()) {
            return NO_DETAIL;
        }
        return VALUE_IN_PARENS.matcher(detail).replaceAll("");
    }

    /** 일시 장애로 이번 실행에서만 제외한 주소 수 — {@code filtered_transient} 의 출처. */
    public long transientFiltered() {
        return transientFiltered.get();
    }

    /** 카운터를 0 으로 되돌린다 — <b>Job 시작 시점에 호출한다</b>(싱글턴이라 실행 간 누적 방지). */
    public void resetStats() {
        transientFiltered.set(0);
    }
}
