package jeju.datahub.batch.geocode;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 제주 범위 정본.
 *
 * <p>설정 키 {@code geocode.jeju-bbox} <b>하나가 두 용도를 겸한다.</b>
 * <ol>
 *   <li>VWorld 장소검색의 {@code bbox} 파라미터 문자열 — {@link #bboxParam()}</li>
 *   <li>지오코딩 결과 좌표의 범위 가드 경계값 — {@link #contains(BigDecimal, BigDecimal)}</li>
 * </ol>
 * 두 곳에 각각 적으면 한쪽만 고쳐져 <b>"검색은 제주로 묶었는데 가드는 딴 범위를 본다"</b> 가 된다.
 * 그래서 값을 여기 한 곳에만 둔다.
 *
 * <h2>🔴 순서는 {@code minx,miny,maxx,maxy} = 경도,위도 다</h2>
 * VWorld 규약이며 {@code crs}(EPSG:4326) 기준으로 해석된다. 위도를 앞에 쓰면
 * <b>장소검색은 전건 {@code NOT_FOUND}</b> 가 되고 <b>가드는 전건을 거부</b>한다 — 둘 다
 * 조용히 실패하는 종류라 {@link #JejuBounds(String)} 가 기동 시점에 형식을 검증한다.
 *
 * <h2>🔴 위도 상한 34.05 는 추자면 때문이다</h2>
 * {@code parking.tb_admdst} 실측 범위는 {@code BOX(126.1441 33.1137, 126.9704 34.0153)} 이고
 * 최북단이 <b>추자면(34.0153 N)</b> 이다. 제주 <b>본섬은 33.57 까지</b>뿐이라, 본섬만 보고 상한을
 * 33.6 으로 줄이면 <b>추자도 전역이 통째로 빠진다.</b> 여유 0.04°(약 4.4km)는 행정동 경계
 * 폴리곤이 해안선을 정밀히 따르지 않아 좌표가 경계 밖 최대 148m 까지 찍히는 것까지 감안한 값이다
 * ({@code AdmDistResolver} 참조).
 *
 * <h2>비우면 꺼진다 — 되돌림 스위치</h2>
 * 설정이 빈 문자열이면 {@link #bboxParam()} 이 {@code ""} 를 반환해 장소검색이 파라미터를 생략하고,
 * {@link #contains} 는 <b>항상 {@code true}</b> 를 반환해 가드가 통과한다. 즉 종전 동작으로
 * 정확히 되돌아간다 — VWorld 가 {@code bbox} 의미를 바꾸거나 범위 판정이 오판할 때 코드 변경 없이 끈다.
 */
@Component
public class JejuBounds {

    private static final Logger log = LoggerFactory.getLogger(JejuBounds.class);

    /**
     * 대한민국 영역의 대략 경도·위도 대역. <b>축 순서 뒤집힘을 잡는 유일한 장치다.</b>
     *
     * <p>🔴 min&lt;max 검사만으로는 뒤집힘이 통과한다 — {@code 33.10,126.10,34.05,127.00} 은
     * 경도 자리(33.10→34.05)도 위도 자리(126.10→127.00)도 증가라 조건을 만족한다.
     * 값이 있어야 할 대역에 있는지를 봐야 걸린다.
     */
    private static final BigDecimal KR_LOT_MIN = new BigDecimal("124");
    private static final BigDecimal KR_LOT_MAX = new BigDecimal("132");
    private static final BigDecimal KR_LAT_MIN = new BigDecimal("33");
    private static final BigDecimal KR_LAT_MAX = new BigDecimal("39");

    /** 설정이 비어 가드·bbox 를 모두 끈 상태. */
    private final boolean enabled;

    private final BigDecimal minLot;
    private final BigDecimal minLat;
    private final BigDecimal maxLot;
    private final BigDecimal maxLat;

    /** VWorld 에 그대로 실릴 문자열. 꺼진 상태면 {@code ""}. */
    private final String bboxParam;

    public JejuBounds(@Value("${geocode.jeju-bbox:}") String bbox) {
        String trimmed = bbox == null ? "" : bbox.trim();
        if (trimmed.isEmpty()) {
            this.enabled = false;
            this.minLot = this.minLat = this.maxLot = this.maxLat = null;
            this.bboxParam = "";
            // 끄고 돌 수는 있지만 전국 POI 가 후보에 들어오고 범위 밖 좌표가 적재된다 —
            // 의도한 상태인지 드러나게 남긴다.
            log.warn("제주 범위(geocode.jeju-bbox)가 비어 있습니다 — 장소검색 bbox 와 좌표 범위 가드가"
                    + " 모두 꺼집니다(종전 동작).");
            return;
        }

        String[] parts = trimmed.split(",");
        if (parts.length != 4) {
            // 🔴 기동을 죽인다. 형식이 틀린 채로 뜨면 장소검색이 전건 NOT_FOUND 가 되거나
            //    가드가 전건을 거부해 "배치가 성공했는데 데이터가 없다" 가 된다.
            throw new IllegalArgumentException(
                    "geocode.jeju-bbox 는 minx,miny,maxx,maxy(경도,위도) 4개여야 합니다: " + trimmed);
        }
        try {
            this.minLot = new BigDecimal(parts[0].trim());
            this.minLat = new BigDecimal(parts[1].trim());
            this.maxLot = new BigDecimal(parts[2].trim());
            this.maxLat = new BigDecimal(parts[3].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("geocode.jeju-bbox 에 숫자가 아닌 값이 있습니다: " + trimmed, e);
        }
        // 🔴 순서 뒤집힘 검사를 먼저 한다 — 뒤집힌 값도 min<max 는 만족할 수 있어
        //    아래 검사만으로는 통과해 버린다(전건 NOT_FOUND / 전건 거부로 조용히 망한다).
        requireBand("경도(minx)", minLot, KR_LOT_MIN, KR_LOT_MAX, trimmed);
        requireBand("경도(maxx)", maxLot, KR_LOT_MIN, KR_LOT_MAX, trimmed);
        requireBand("위도(miny)", minLat, KR_LAT_MIN, KR_LAT_MAX, trimmed);
        requireBand("위도(maxy)", maxLat, KR_LAT_MIN, KR_LAT_MAX, trimmed);

        if (minLot.compareTo(maxLot) >= 0 || minLat.compareTo(maxLat) >= 0) {
            throw new IllegalArgumentException(
                    "geocode.jeju-bbox 의 min 이 max 보다 크거나 같습니다: " + trimmed);
        }

        this.enabled = true;
        this.bboxParam = trimmed;
        log.info("제주 범위(bbox)를 적용합니다: {} (경도,위도 순 — 장소검색 파라미터 + 좌표 가드 공용)",
                trimmed);
    }

    /**
     * VWorld 장소검색에 실을 {@code bbox} 값. 꺼진 상태면 {@code ""} 이며,
     * 호출자는 빈 문자열일 때 <b>파라미터 자체를 생략</b>해야 한다
     * ({@code bbox=} 로 비워 보내면 업스트림이 거절할 수 있다).
     */
    public String bboxParam() {
        return bboxParam;
    }

    /** 설정이 적용돼 가드가 켜져 있는가. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 좌표가 제주 범위 안인가. 경계값은 <b>포함</b>이다(추자면 최북단이 상한에 가까워 배타로 두면
     * 경계 근처가 탈락한다).
     *
     * <p>꺼진 상태({@code geocode.jeju-bbox} 빈 값)거나 좌표가 {@code null} 이면 {@code true} 다 —
     * 🔴 좌표가 없는 것은 <b>가드가 판정할 대상이 아니다</b>(일시 장애·미조회는 호출자가 이미 분기한다).
     * 여기서 {@code false} 를 주면 일시 장애가 범위 위반으로 오분류된다.
     */
    public boolean contains(BigDecimal lat, BigDecimal lot) {
        if (!enabled || lat == null || lot == null) {
            return true;
        }
        return lat.compareTo(minLat) >= 0 && lat.compareTo(maxLat) <= 0
                && lot.compareTo(minLot) >= 0 && lot.compareTo(maxLot) <= 0;
    }

    /**
     * 값이 있어야 할 대역 안인지 검사한다. 벗어나면 <b>순서 뒤집힘</b>을 1순위 원인으로 지목한다 —
     * 실제로 가장 흔한 실수이고, 메시지가 원인을 짚지 못하면 기동 실패 로그만 보고 헤맨다.
     */
    private static void requireBand(
            String what, BigDecimal value, BigDecimal min, BigDecimal max, String raw) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(
                    "geocode.jeju-bbox 의 " + what + " 값 " + value + " 이 대한민국 범위(" + min + "~" + max
                            + ") 밖입니다. 순서가 minx,miny,maxx,maxy(경도,위도)인지 확인하세요: " + raw);
        }
    }

    /** 스킵 {@code note} 에 남길 표기 — 어떤 좌표가 어떤 범위에 걸렸는지 한 줄로 읽히게 한다. */
    public String describe(BigDecimal lat, BigDecimal lot) {
        return "lat=" + lat + " lot=" + lot + " 범위=" + bboxParam;
    }
}
