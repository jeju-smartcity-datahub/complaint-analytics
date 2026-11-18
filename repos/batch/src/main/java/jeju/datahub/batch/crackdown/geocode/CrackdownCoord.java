package jeju.datahub.batch.crackdown.geocode;

import java.math.BigDecimal;

/**
 * 주소 1건의 지오코딩·행정동 매칭 결과 — {@code CrackdownCoordWriter} 가 같은 주소의 좌표 없는 행에 쓴다.
 *
 * @param crdnAddr 🔴 Reader 가 읽은 <b>원문 그대로</b>(trim 하지 않는다) — Writer 의
 *                 {@code WHERE crdn_addr = ?} 가 정확 일치라 가공하면 0행 갱신이 된다
 * @param lat      위도 — {@code Coordinate.lat()} 그대로(소수 10자리)
 * @param lot      경도 — {@code Coordinate.lot()} 그대로(소수 10자리)
 * @param emdCd    {@code AdmDistResolver} 가 매칭한 읍면동 코드(D6 — 매칭 실패면 이 레코드가 만들어지지 않는다)
 */
public record CrackdownCoord(String crdnAddr, BigDecimal lat, BigDecimal lot, String emdCd) {
}
