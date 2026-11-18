package jeju.datahub.analysis.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.impl.VworldCallSupport.LookupStatus;
import jeju.datahub.analysis.service.impl.VworldCallSupport.TransientVworldException;

/**
 * VWorld <b>장소(POI) 검색</b>으로 군집 중심좌표에 가장 가까운 <b>랜드마크</b> 이름을 얻는다
 * (<a href="https://api.vworld.kr/req/search">api.vworld.kr/req/search</a>, {@code type=place}).
 * 구역명 폴백 체인의 <b>②단계</b>다.
 *
 * <pre>
 * GET /req/search?service=search&amp;request=search&amp;version=2.0&amp;crs=EPSG:4326&amp;type=place
 *     &amp;format=json&amp;query={읍면동명}&amp;bbox={중심좌표 ±0.003도}&amp;size=100&amp;key={VWORLD_API_KEY}
 * </pre>
 *
 * <h2>🔴 거리순 정렬을 주지 않는다 — 받아서 직접 재정렬한다</h2>
 * 이건 지오코딩이 아니라 <b>키워드 검색</b>이다. 실측에서 {@code query=연동} 의 1위가
 * {@code 황제PC(연동)} 이었다. 그래서 {@code size=100} 으로 넉넉히 받아 <b>중심좌표 기준 거리로
 * 다시 정렬</b>한 뒤 고른다.
 *
 * <h2>🔴 {@code query} 는 읍면동명이다</h2>
 * {@code query} 또는 {@code category} 중 하나가 필수인데({@code PARAM_REQUIRED}),
 * {@code category} 는 코드값이라야 해서 {@code category=병원} 은 {@code NOT_FOUND} 였다.
 * 그래서 <b>{@code query = 읍면동명}</b> 으로 부르고 카테고리는 <b>응답 문자열로 거른다</b>.
 *
 * <h2>채택 규칙</h2>
 * <ul>
 *   <li>대분류 화이트리스트({@link #LANDMARK_CATEGORIES}) 에 들면 후보.</li>
 *   <li>대분류 블랙리스트({@link #EXCLUDED_CATEGORIES}) 는 부속·경계 시설이라 지명이 아니다 —
 *       실측에서 {@code 연동이화아파트입구}·{@code 연동대일아파트/가동} 이 여기 걸린다.</li>
 *   <li>⚠️ VWorld 분류가 거칠다 — {@code 신광초등학교} 가 {@code 중앙행정기관} 으로 들어 있다.
 *       대분류만으로는 놓치므로 <b>제목 키워드 보정</b>({@link #TITLE_KEYWORDS})을 함께 둔다:
 *       제목에 걸리면 화이트리스트에 없어도 후보로 올린다. 단 <b>블랙리스트가 이보다 우선</b>이다
 *       ({@code ○○사거리입구} 같은 진출입시설이 키워드로 우회하는 것을 막는다).</li>
 *   <li>🔴 <b>{@code poi-radius-m}(150m)를 넘으면 채택하지 않는다</b> — 군집과 무관한 지명이 붙는
 *       것을 막는다. 실측 노형동 군집의 최근접이 72m 짜리 {@code (주)그리메} 였는데 카테고리
 *       필터에서 탈락해 ③(역지오코딩)으로 내려간다.</li>
 * </ul>
 */
@Component
public class VworldPlaceClient {

    private static final Logger log = LoggerFactory.getLogger(VworldPlaceClient.class);

    /** 검색 bbox 반변(도). 위도 33° 기준 대략 ±330m 로, 채택 상한 150m 를 넉넉히 덮는다. */
    static final double BBOX_HALF_DEG = 0.003d;

    /** 채택 대상 대분류. 응답 {@code category} 의 {@code >} 앞부분과 대조한다. */
    static final Set<String> LANDMARK_CATEGORIES = Set.of(
            "병원", "의원", "중앙행정기관", "지방행정기관", "대학교", "시장",
            "공원", "관광지", "여객자동차터미널", "주차장", "문화시설");

    /** 제외 대분류. 부속·경계 시설이라 지명 구실을 못 한다. */
    static final Set<String> EXCLUDED_CATEGORIES = Set.of(
            "진출입시설", "시설구역경계", "건물", "그외기타교육기관");

    /**
     * 제목 키워드 보정. VWorld 대분류가 거칠어 랜드마크가 엉뚱한 대분류에 들어 있는 경우를 건진다
     * (실측: {@code 신광초등학교} → {@code 중앙행정기관}).
     */
    static final Pattern TITLE_KEYWORDS = Pattern.compile(
            "초등학교|중학교|고등학교|대학교|병원|시장|공원|터미널|역|주민센터|청사|사거리|교차로");

    /** 외부 JSON 파싱 전용 — 앱 직렬화 설정과 무관하게 동작하도록 자체 인스턴스를 쓴다(스레드 안전). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String apiKey;
    private final int maxAttempts;
    private final int fetchSize;
    private final int radiusM;

    public VworldPlaceClient(
            @Value("${analysis.zone-name.vworld.base-url}") String baseUrl,
            @Value("${analysis.zone-name.vworld.api-key:}") String apiKey,
            @Value("${analysis.zone-name.vworld.timeout-ms:2000}") int timeoutMs,
            @Value("${analysis.zone-name.vworld.max-attempts:2}") int maxAttempts,
            @Value("${analysis.zone-name.poi-fetch-size:100}") int fetchSize,
            @Value("${analysis.zone-name.poi-radius-m:150}") int radiusM) {

        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.maxAttempts = maxAttempts;
        this.fetchSize = fetchSize;
        this.radiusM = radiusM;
        this.restClient = VworldCallSupport.restClient(baseUrl, timeoutMs);
        if (this.apiKey.isEmpty()) {
            // 기동은 막지 않는다 — 이름이 없어도 집중구역 순위는 DB 만으로 계산된다.
            log.warn("VWorld API 키가 비어 있습니다(analysis.zone-name.vworld.api-key)."
                    + " 구역명 POI 폴백은 건너뛰고 '{읍면동명} 일대' 로 내려갑니다."
                    + " 루트 .env 의 VWORLD_API_KEY 를 채우세요.");
        }
    }

    /** 채택 후보 1건 — 이름과 중심점까지의 거리(m). */
    public record NearestPlace(LookupStatus status, String title, int distM) {

        static NearestPlace found(String title, int distM) {
            return new NearestPlace(LookupStatus.FOUND, title, distM);
        }

        static NearestPlace notFound() {
            return new NearestPlace(LookupStatus.NOT_FOUND, null, 0);
        }

        static NearestPlace transientFailure() {
            return new NearestPlace(LookupStatus.TRANSIENT_FAILURE, null, 0);
        }
    }

    /**
     * 중심좌표에서 {@code poi-radius-m} 이내의 최근접 랜드마크.
     *
     * @param lat    군집 중심 위도(EPSG:4326)
     * @param lot    군집 중심 경도(EPSG:4326)
     * @param emdNm  읍면동명 — {@code query} 파라미터로 쓴다. 비면 호출하지 않고 {@code NOT_FOUND}
     * @return 채택 후보가 없으면 {@code NOT_FOUND}, 외부 장애면 {@code TRANSIENT_FAILURE}
     */
    public NearestPlace findNearestLandmark(BigDecimal lat, BigDecimal lot, String emdNm) {
        if (lat == null || lot == null) {
            return NearestPlace.notFound();
        }
        if (emdNm == null || emdNm.isBlank()) {
            // query 없이 부르면 PARAM_REQUIRED 다. 호출 자체를 아껴 ③(역지오코딩)으로 넘긴다.
            log.debug("읍면동명이 없어 POI 검색을 건너뜁니다. lat={} lot={}", lat, lot);
            return NearestPlace.notFound();
        }
        if (apiKey.isEmpty()) {
            // 키가 없는 것은 "그 좌표에 답이 없다"가 아니다 — 캐시에 굳히지 않도록 일시 실패로 본다.
            return NearestPlace.transientFailure();
        }
        return VworldCallSupport.withRetry(
                "VWorld 장소 검색", maxAttempts,
                () -> request(lat.doubleValue(), lot.doubleValue(), emdNm),
                NearestPlace::transientFailure);
    }

    /** 재시도 1회분(HTTP 1회 + 응답 해석). 테스트가 직접 부를 수 있게 package-private 이다. */
    NearestPlace request(double lat, double lot, String emdNm) {
        String bbox = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f",
                lot - BBOX_HALF_DEG, lat - BBOX_HALF_DEG, lot + BBOX_HALF_DEG, lat + BBOX_HALF_DEG);
        String body;
        try {
            body = restClient.get()
                    .uri(uri -> uri.path("/req/search")
                            .queryParam("service", "search")
                            .queryParam("request", "search")
                            .queryParam("version", "2.0")
                            .queryParam("crs", "EPSG:4326")
                            .queryParam("type", "place")
                            .queryParam("format", "json")
                            .queryParam("query", emdNm)
                            .queryParam("bbox", bbox)
                            .queryParam("size", fetchSize)
                            .queryParam("page", 1)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // 원문 메시지에는 인증키가 섞인 URL 이 들어갈 수 있어 예외 타입만 남긴다.
            throw new TransientVworldException("VWorld 장소 검색 호출 실패: " + e.getClass().getSimpleName(), e);
        }
        return parse(body, lat, lot);
    }

    /** 응답 → 최근접 랜드마크. 파싱 실패는 일시 실패로 본다(스키마가 아니라 응답이 깨진 경우가 대부분). */
    NearestPlace parse(String body, double lat, double lot) {
        JsonNode response;
        try {
            response = MAPPER.readTree(body == null ? "" : body).path("response");
        } catch (JsonProcessingException e) {
            throw new TransientVworldException("VWorld 장소 검색 응답 파싱 실패", e);
        }

        String status = response.path("status").asText("");
        if ("NOT_FOUND".equals(status)) {
            return NearestPlace.notFound();   // 확정 실패 — 재시도하지 않는다
        }
        if (!"OK".equals(status)) {
            throw new TransientVworldException("VWorld 장소 검색 status=" + status
                    + " code=" + response.path("error").path("code").asText(""));
        }

        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode item : response.path("result").path("items")) {
            String title = item.path("title").asText(null);
            String category = item.path("category").asText("");
            JsonNode point = item.path("point");
            if (title == null || title.isBlank() || !point.hasNonNull("x") || !point.hasNonNull("y")) {
                continue;
            }
            if (!isLandmark(title, category)) {
                continue;
            }
            double px;
            double py;
            try {
                px = Double.parseDouble(point.get("x").asText());
                py = Double.parseDouble(point.get("y").asText());
            } catch (NumberFormatException e) {
                // 좌표가 숫자가 아닌 후보는 건너뛴다(나머지 후보로 판정) (4.2)
                log.debug("VWorld 후보 좌표가 숫자가 아님 — 건너뜀");
                continue;
            }
            int distM = (int) Math.round(distanceM(lat, lot, py, px));
            if (distM > radiusM) {
                continue;                      // 🔴 상한을 넘으면 채택하지 않는다
            }
            candidates.add(new Candidate(distM, title));
        }
        if (candidates.isEmpty()) {
            return NearestPlace.notFound();
        }
        // 🔴 응답이 거리순이 아니므로 여기서 직접 재정렬한다. 동률이면 제목으로 결정적 순서를 준다.
        candidates.sort((a, b) -> a.distM() != b.distM()
                ? Integer.compare(a.distM(), b.distM())
                : a.title().compareTo(b.title()));
        Candidate nearest = candidates.get(0);
        return NearestPlace.found(nearest.title(), nearest.distM());
    }

    /**
     * 랜드마크 판정. 순서가 중요하다.
     *
     * <ol>
     *   <li>🔴 <b>블랙리스트가 가장 먼저다</b> — 부속·경계 시설은 "지명이 아니다"라는 명시적 제외
     *       의도라, 제목 키워드보다 우선한다. 이 순서가 아니면 {@code ○○사거리입구}
     *       ({@code 진출입시설})처럼 <b>키워드가 블랙리스트를 우회</b>해 후보로 올라온다.</li>
     *   <li>제목 키워드에 걸리면 화이트리스트에 없어도 후보다 — VWorld 대분류가 거칠어
     *       {@code 신광초등학교} 가 {@code 중앙행정기관} 에 들어 있는 식이라 대분류만으로는 놓친다.</li>
     *   <li>그 밖에는 대분류가 화이트리스트에 있어야 한다.</li>
     * </ol>
     */
    static boolean isLandmark(String title, String category) {
        String major = majorCategory(category);
        if (EXCLUDED_CATEGORIES.contains(major)) {
            return false;
        }
        if (title != null && TITLE_KEYWORDS.matcher(title).find()) {
            return true;
        }
        return LANDMARK_CATEGORIES.contains(major);
    }

    /** {@code "공원 > 어린이공원"} → {@code "공원"}. 구분자가 없으면 문자열 전체가 대분류다. */
    static String majorCategory(String category) {
        if (category == null) {
            return "";
        }
        int sep = category.indexOf('>');
        return (sep < 0 ? category : category.substring(0, sep)).trim();
    }

    /**
     * 두 좌표 사이 거리(m). 150m 판정이라 등거리 원통도법 근사로 충분하다
     * (PostGIS 왕복이나 삼각함수 정밀 공식을 쓸 이유가 없다).
     */
    static double distanceM(double lat1, double lot1, double lat2, double lot2) {
        double meterPerDegLat = 111_320.0d;
        double dy = (lat2 - lat1) * meterPerDegLat;
        double dx = (lot2 - lot1) * meterPerDegLat * Math.cos(Math.toRadians(lat1));
        return Math.hypot(dx, dy);
    }

    /** 재정렬 대상 후보 1건. */
    private record Candidate(int distM, String title) {
    }
}
