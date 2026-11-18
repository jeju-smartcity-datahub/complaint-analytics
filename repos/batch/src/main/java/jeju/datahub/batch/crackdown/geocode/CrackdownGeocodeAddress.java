package jeju.datahub.batch.crackdown.geocode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 단속 주소({@code parking.tb_crdn_dsctn.crdn_addr}) 원문 → <b>(유형, 지오코딩 입력 문자열)</b>
 *
 * <p>단속 주소는 민원과 모양이 다르다 — <b>시·도 접두가 없고</b>, 행의 67% 가 장소명
 * ({@code 공항입구교차로} 류)이다. 원문을 그대로 VWorld 에 넣으면 타 시·도의 같은 이름 지번·도로명이
 * 잡힌다. 그래서 유형을 먼저 가르고 유형별로 접두를 붙인다. 민원 입력(원문 그대로)은 바꾸지 않는다.
 *
 * <h2>입력 형식 — 스파이크(2026-09-15 실호출) 결정</h2>
 * <table>
 *   <tr><th>유형</th><th>입력</th><th>제주 안 성공률(채택 / 원문)</th><th>근거</th></tr>
 *   <tr><td>{@link AddressKind#JIBUN}</td><td>{@code 제주특별자치도 {sgg_nm} {원문}} (J-B)</td>
 *       <td>97.0% / 95.0%</td><td>원문(J-A)은 타 시·도 동명 지번 2건을 잡았다</td></tr>
 *   <tr><td>{@link AddressKind#ROAD}</td><td>{@code 제주특별자치도 {원문}} (R-B)</td>
 *       <td>92.0% / 70.0%</td><td>원문(R-A)은 좌표 46건 중 11건이 제주 밖</td></tr>
 *   <tr><td>{@link AddressKind#PLACE}</td><td>{@code 제주 {원문}}</td>
 *       <td>-</td><td>🔴 <b>지오코딩하지 않는다</b>(아래)</td></tr>
 * </table>
 *
 * <p>🔴 <b>장소명은 지오코딩하지 않는다.</b> 최선 조합(P-B·P-C)의 제주 안 행수 가중
 * 성공률이 13.4% 로 게이트(50%)에 못 미쳤다. {@code PLACE} 의 {@code query} 는 정해진 형식대로
 * 만들어 두지만 Processor가 곧바로 {@code NOT_FOUND} 로 처리한다 — 장소명 좌표는 같은
 * 주소의 기존 좌표 전파만이 출처다.
 *
 * <h2>🔴 동명 행정동은 접두를 붙이지 않는다</h2>
 * {@code 월평동} 은 제주시·서귀포시에 하나씩 있다. 한쪽 시를 골라 붙이면 <b>틀린 시의 좌표도 제주
 * 안</b>이라 행정동 매칭(D6)이 거르지 못한다. 그래서 {@code tb_admdst} 에 {@code emd_nm} 이
 * <b>정확히 1행</b>인 것만 표에 담고, 표에 없으면 원문 그대로 보낸다(해당 단속 주소 15개·89행,
 * 2026-09-15 로컬). {@code Collectors.toMap} 은 로드에서 죽고 {@code HashMap.put} 은 조용히 덮으므로
 * 중복을 직접 센다.
 *
 * <h2>⚠️ 캐시 키는 이 빌더가 만든 {@code query} 다</h2>
 * {@code batch.tb_geocode_cache} 는 입력 문자열이 키라, 접두가 붙은 단속 입력은 민원 캐시
 * ({@code 금능리 1428} 류 원문 키)와 섞이지 않는다. <b>형식을 바꾸면 기존 캐시가 전부 빗나간다</b>.
 *
 * <p>⚠️ 알려진 한계: 시설명 끝이 "동산+숫자" 인 장소명류 69주소·1,722행이 지번 정규식에 걸려
 * {@code JIBUN} 으로 분류된다 — 지오코딩이 {@code NOT_FOUND} 스킵으로 끝나 좌표가 잘못 들어가지는
 * 않는다(2026-09-15 스파이크).
 */
@Component
public class CrackdownGeocodeAddress {

    private static final Logger log = LoggerFactory.getLogger(CrackdownGeocodeAddress.class);

    /**
     * 지번형 — 분류 SQL({@code crdn_addr ~ '...'})과 <b>같은 규칙</b>. 지번이 도로명보다 먼저다.
     * 부분 일치({@link java.util.regex.Matcher#find()})라 SQL {@code ~} 와 판정이 같다.
     */
    static final Pattern JIBUN = Pattern.compile("(읍|면|동|리|가)\\s*(산\\s*)?[0-9]+(-[0-9]+)?");

    /** 도로명형 — §1-1 분류 SQL 과 같은 규칙. */
    static final Pattern ROAD = Pattern.compile("(로|길)\\s*[0-9]+");

    /** 읍면동 이름 → 시군구 이름 원천. {@code tb_admdst} 74행 전체를 한 번만 읽는다. */
    static final String LOAD_SQL = "SELECT emd_nm, sgg_nm FROM parking.tb_admdst";

    private static final String SIDO_PREFIX = "제주특별자치도 ";
    private static final String PLACE_PREFIX = "제주 ";

    /** 테스트용 생성자에서는 {@code null} — 표를 직접 받으므로 DB 를 읽지 않는다. */
    private final JdbcTemplate jdbc;

    /**
     * {@link #sggByEmdNm()} 의 지연 캐시. {@code volatile} — 이중 검사 잠금의 가시성 확보용
     * ({@code AdmDistResolver.knownEmdCds} 와 같은 방식).
     */
    private volatile Map<String, String> sggByEmdNm;

    /**
     * 운영 경로. 🔴 <b>생성 시점에 DB 를 읽지 않는다</b> — 첫 지번 주소를 만날 때 1회 읽는다
     * (기동을 죽이면 헬스체크·트리거까지 함께 죽는다).
     *
     * <p>🔴 {@code @Autowired} 를 빼지 않는다 — 아래 테스트용 생성자와 함께 생성자가 둘이라, 표시가
     * 없으면 Spring 이 기본 생성자를 찾다가 죽는다({@code RiskCriteriaCache} 와 같은 이유).
     */
    @Autowired
    public CrackdownGeocodeAddress(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 표를 직접 심는 경로 — 단위 테스트가 실 DB 의 행정동 표에 의존하지 않게 한다. */
    CrackdownGeocodeAddress(Map<String, String> sggByEmdNm) {
        this.jdbc = null;
        this.sggByEmdNm = Map.copyOf(sggByEmdNm);
    }

    /**
     * 단속 주소 원문을 분류하고 지오코딩 입력을 만든다.
     *
     * <p>분류는 앞뒤 공백을 걷어낸 원문에 지번 → 도로명 순으로 정규식을 대고, 둘 다 아니면 장소명이다.
     * 빈 문자열은 {@link AddressKind#PLACE} 로 떨어진다(Reader 가 거르므로 정상 경로에서는 오지 않는다).
     *
     * @param crdnAddr {@code crdn_addr} 원문. 🔴 {@code null} 불가 — Reader 가
     *                 {@code crdn_addr IS NOT NULL} 로 보장한다
     * @throws NullPointerException {@code crdnAddr} 가 {@code null} 일 때
     */
    public GeocodeInput build(String crdnAddr) {
        String addr = Objects.requireNonNull(crdnAddr, "crdnAddr").trim();
        String firstToken = addr.split("\\s+", 2)[0];
        if (JIBUN.matcher(addr).find()) {
            // "노형동" → "제주시". 🔴 동명이 2개 이상(월평동)이면 표에 없어 null → 접두 없음
            String sgg = sggByEmdNm().get(firstToken);
            return new GeocodeInput(AddressKind.JIBUN,
                    sgg == null ? addr : SIDO_PREFIX + sgg + " " + addr);
        }
        if (ROAD.matcher(addr).find()) {
            return new GeocodeInput(AddressKind.ROAD, SIDO_PREFIX + addr);
        }
        return new GeocodeInput(AddressKind.PLACE, PLACE_PREFIX + addr);
    }

    /**
     * 지연 초기화 — 지번 주소를 한 번도 안 보면 이 쿼리도 돌지 않는다. 행정동 경계는 배치가 도는 중에
     * 바뀌지 않으므로 실행 중 갱신을 고려하지 않는다(바뀌면 앱을 다시 띄운다).
     */
    private Map<String, String> sggByEmdNm() {
        Map<String, String> cached = sggByEmdNm;
        if (cached == null) {
            synchronized (this) {
                cached = sggByEmdNm;
                if (cached == null) {
                    cached = load();
                    sggByEmdNm = cached;
                }
            }
        }
        return cached;
    }

    /** {@code emd_nm} 이 정확히 1행인 것만 담는다 — 2행 이상은 이름만으로 시를 정할 수 없다. */
    private Map<String, String> load() {
        Map<String, String> unique = new HashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (Map<String, Object> row : jdbc.queryForList(LOAD_SQL)) {
            Object emdNm = row.get("emd_nm");
            Object sggNm = row.get("sgg_nm");
            if (emdNm == null || sggNm == null) {
                continue;   // 이름이 비면 접두를 만들 수 없다 — 원문으로 보내는 쪽이 안전하다
            }
            String name = emdNm.toString();
            if (duplicated.contains(name)) {
                continue;
            }
            if (unique.put(name, sggNm.toString()) != null) {
                unique.remove(name);
                duplicated.add(name);
            }
        }
        log.info("단속 지번 접두용 행정동 이름 {}종을 캐시했습니다(동명 제외 {}종: {}).",
                unique.size(), duplicated.size(), duplicated);
        return Map.copyOf(unique);
    }

    /** 주소 유형. 정규식 분류 결과이며 지번이 도로명보다 우선한다. */
    public enum AddressKind {
        /** 지번형 — {@code 노형동 1234-5}, {@code 강정동 산 12}. */
        JIBUN,
        /** 도로명형 — {@code 연북로 12}. */
        ROAD,
        /** 장소명·기타 — {@code 공항입구교차로}. 🔴 지오코딩하지 않는다. */
        PLACE
    }

    /**
     * 빌더 결과.
     *
     * @param kind  주소 유형
     * @param query 지오코딩 입력 문자열 — 그대로 지오코딩 캐시 키가 된다
     */
    public record GeocodeInput(AddressKind kind, String query) {
    }
}
