package jeju.datahub.batch.geocode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 지오코딩 결과 좌표 (WGS84 / EPSG:4326).
 *
 * <p>필드명은 DB 컬럼({@code parking.tb_cvlcpt.lat}/{@code lot}, {@code batch.tb_geocode_cache})과
 * 맞춘다 — {@code lot} 은 경도(longitude)다.
 *
 * <p><b>소수점 10자리로 반올림해 보관한다.</b> 두 테이블 모두 {@code NUMERIC(12,10)}/
 * {@code NUMERIC(13,10)} 이라 DB 에 들어가는 순간 10자리로 잘린다. VWorld 응답은 15자리까지 오므로
 * 반올림하지 않으면 <b>같은 주소인데 캐시 미스 때(15자리)와 캐시 히트 때(DB 에서 읽은 10자리)의 값이
 * 달라진다.</b> 파싱 시점에 한 번 맞춰 두면 실행 경로와 무관하게 같은 값이 나온다.
 */
public record Coordinate(BigDecimal lat, BigDecimal lot) {

    /** DB 컬럼 {@code NUMERIC(_,10)} 과 동일한 소수 자릿수. */
    static final int SCALE = 10;

    /**
     * VWorld 응답의 {@code result.point} 를 좌표로 만든다.
     *
     * @param x 경도(longitude) 문자열 — VWorld 는 {@code point.x} 가 경도다
     * @param y 위도(latitude) 문자열 — {@code point.y}
     */
    public static Coordinate of(String x, String y) {
        return new Coordinate(scaled(y), scaled(x));
    }

    private static BigDecimal scaled(String raw) {
        return new BigDecimal(raw).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
