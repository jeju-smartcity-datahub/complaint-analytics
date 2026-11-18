package jeju.datahub.batch.geocode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicLong;
import jeju.datahub.batch.geocode.VworldCallSupport.TransientGeocodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * VWorld <b>장소(POI) 검색</b>으로 좌표를 얻는다
 * (<a href="https://api.vworld.kr/req/search">api.vworld.kr/req/search</a>, {@code type=place}).
 * 폴백 체인 <b>3단</b>이다 — D7 2026-08-05 재개정으로 신설.
 *
 * <h2>왜 필요한가</h2>
 * 주소DB({@code /req/address})에 없는 지번이 <b>POI 검색에는 있다.</b> 실측: 해녀박물관 일대
 * {@code 제주특별자치도 제주시 구좌읍 상도리 3204-1}(원천 24행)은 지번·도로명·부번제거가 전부
 * {@code NOT_FOUND} 인데 여기서는 {@code OK}(lat 33.5236 / lot 126.8634)가 나온다. 제거된 Kakao 폴백이
 * 39건을 살리던 것과 같은 성격의 보완이며, <b>같은 VWorld 안에서</b> 해결되므로 외부 의존이 늘지 않는다.
 *
 * <h2>🔴 이건 지오코딩이 아니라 <b>검색</b>이다 — 반드시 주소를 검증하고 채택한다</h2>
 * 질의와 무관한 POI 가 걸리면 좌표가 엉뚱한 곳에 찍힌다. {@link #matchesQuery} 를 통과한 결과만
 * 쓰고, 통과하지 못하면 <b>{@code NOT_FOUND} 로 취급해 4단(부번 제거)으로 넘긴다.</b>
 *
 * <h2>🔴 검색 영역을 제주로 묶는다 — {@code bbox} (2026-09-29)</h2>
 * 주소DB 조회({@code /req/address})는 주소 문자열 완전 일치라 타지역이 걸릴 일이 없지만, <b>여기는
 * 전국 POI 검색</b>이라 동명 지번이 육지에서 먼저 올라올 수 있다. 그래서 검색 자체를 제주 범위로
 * 제한한다 — {@code geocode.jeju-bbox} 가 비어 있지 않으면 {@code bbox} 로 실린다.
 *
 * <p>실측 확인(2026-09-29, 실호출):
 * <table>
 *   <tr><th>질의</th><th>bbox 없음</th><th>bbox 적용</th></tr>
 *   <tr><td>서울특별시 중구 태평로1가 31</td><td>{@code OK} (37.566 N)</td><td><b>{@code NOT_FOUND}</b></td></tr>
 *   <tr><td>제주시 구좌읍 상도리 3204-1</td><td>{@code OK}</td><td>{@code OK} (동일 좌표)</td></tr>
 * </table>
 *
 * <p>🔴 <b>범위 값의 정본은 {@link JejuBounds}</b> 다(설정 키 {@code geocode.jeju-bbox}).
 * 축 순서(경도,위도)·추자면 상한·되돌림 스위치의 근거는 전부 그 클래스에 있다 — 여기서 값을
 * 다시 적지 않는다. 🔁 <b>2026-09-29 TSK-4192 Step 1</b> 로 {@code geocode.vworld.search-bbox}
 * 에서 옮겨왔다(좌표 범위 가드가 같은 값을 읽어야 해서다).
 *
 * <p>bbox 는 <b>거친 1차 필터</b>일 뿐이다. 제주 범위 안에서 엉뚱한 POI 가 걸리는 것은 여전히
 * {@link #matchesQuery} 가 막고, 최종 판정은 행정동 공간매칭({@code AdmDistResolver})이 진다.
 *
 * <h2>⚠️ {@link VworldGeocodingClient} 와 응답 스키마가 다르다</h2>
 * <table>
 *   <tr><th></th><th>{@code /req/address}</th><th>{@code /req/search}</th></tr>
 *   <tr><td>좌표</td><td>{@code response.result.point}</td><td>{@code response.result.items[].point}</td></tr>
 *   <tr><td>주소</td><td>(질의가 곧 주소)</td><td>{@code items[].address.parcel} / {@code .road}</td></tr>
 * </table>
 * 그래서 파싱을 공유하지 않는다. 공유하는 것은 호출 규율({@link VworldCallSupport}) 뿐이다 —
 * 재시도(4회 선형 백오프) · 쿼터 초과 즉시 전파(D9) · {@code NOT_FOUND} 무재시도.
 */
@Component
public class VworldPlaceSearchClient implements GeocodingClient {

    private static final Logger log = LoggerFactory.getLogger(VworldPlaceSearchClient.class);

    /**
     * 가져올 결과 수. 채택은 첫 건만 보지만(D7), 응답을 눈으로 확인할 때 후보가 보여야 진단이 된다.
     */
    static final int SEARCH_SIZE = 5;

    /** 반환 주소가 질의와 다를 때 남기는 단계 내역 접두 — 스킵 {@code note} 로 이어진다. */
    static final String MISMATCH_DETAIL_PREFIX = "place=ADDR_MISMATCH";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String apiKey;
    private final int maxAttempts;
    private final RetryTemplate retryTemplate;

    /**
     * 검색 영역 정본. {@link JejuBounds#bboxParam()} 이 빈 문자열이면 <b>{@code bbox} 를 아예
     * 싣지 않는다</b> — 되돌림 스위치다.
     */
    private final JejuBounds jejuBounds;

    /** 실제로 나간 HTTP 요청 수 — 이 단계가 호출을 얼마나 쓰는지 확인하는 용도. */
    private final AtomicLong apiCallCount = new AtomicLong();

    public VworldPlaceSearchClient(
            @Value("${geocode.vworld.api-key}") String apiKey,
            @Value("${geocode.vworld.search-base-url}") String searchBaseUrl,
            @Value("${geocode.vworld.timeout-ms}") int timeoutMs,
            @Value("${geocode.vworld.max-attempts}") int maxAttempts,
            JejuBounds jejuBounds) {

        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.maxAttempts = maxAttempts;
        this.jejuBounds = jejuBounds;
        this.restClient = VworldCallSupport.restClient(searchBaseUrl, timeoutMs);
        this.retryTemplate = VworldCallSupport.retryTemplate(maxAttempts);
        // 적용 여부 로그는 JejuBounds 생성자가 한 번만 남긴다 — 여기서 또 남기면 두 줄이 된다.
    }

    /**
     * {@inheritDoc}
     *
     * <p>검색 결과가 있어도 <b>주소 검증({@link #matchesQuery})을 통과해야만</b> {@code FOUND} 다.
     * 통과하지 못하면 {@code NOT_FOUND} — "없는 것"으로 보고 다음 단계로 넘긴다.
     */
    @Override
    public GeocodeOutcome geocode(String address) {
        if (address == null || address.isBlank()) {
            return GeocodeOutcome.notFound("place=(빈 값)");
        }
        requireApiKey();
        return retryTemplate.execute(
                context -> request(address),
                context -> {
                    Throwable last = context.getLastThrowable();
                    // ⚠️ RetryTemplate 은 "재시도 대상이 아닌 예외"도 이 recovery 로 흘려보낸다.
                    //    그대로 두면 쿼터 초과가 조용히 "못 찾음"이 되어 D9(즉시 중단)가 깨진다.
                    if (last != null && !(last instanceof TransientGeocodeException)) {
                        throw last instanceof RuntimeException e ? e : new IllegalStateException(last);
                    }
                    log.warn("VWorld 장소 검색이 {}회 시도 후에도 실패했습니다(일시 장애로 처리). addr={}",
                            maxAttempts, address, last);
                    return GeocodeOutcome.transientFailure("place=TRANSIENT");
                });
    }

    /** 실제로 나간 HTTP 요청 수(누적). */
    public long apiCallCount() {
        return apiCallCount.get();
    }

    /**
     * 🔴 <b>반환 주소가 질의 주소와 같은 지번인지</b> 검증한다.
     *
     * <p>장소 검색은 지오코딩이 아니라 검색이라 질의와 무관한 POI 가 걸릴 수 있다. 실측상 반환
     * {@code address.parcel} 에는 <b>끝에 지목 1자가 붙는 경우가 있다</b>(붙지 않는 경우도 있다).
     *
     * <pre>
     * 질의  제주특별자치도 제주시 삼도이동 1264      →  반환  … 1264도    ('도' = 도로)
     * 질의  제주특별자치도 제주시 외도일동 560-2     →  반환  … 560-2대   ('대' = 대지)
     * 질의  제주특별자치도 제주시 구좌읍 상도리 3204-1 →  반환  … 3204-1   (지목 없음)
     * </pre>
     *
     * <p>그래서 <b>끝의 한글 1자를 지목으로 보고 떼어낸 뒤 정확히 일치</b>할 때만 채택한다.
     * <ul>
     *   <li>그냥 비교하면 지목이 붙은 응답이 전부 버려진다(대다수가 그렇다).</li>
     *   <li>🔴 <b>접두 일치({@code startsWith})는 쓰지 않는다</b> — {@code 상도리 3204} 질의가
     *       {@code 상도리 32041} 반환에 걸려 엉뚱한 좌표를 채택한다. 실제로 {@code 상도리 3204} 로
     *       검색하면 {@code 상도리 3204-1} 의 POI 가 나온다(부번이 다른 별개 필지다).</li>
     *   <li>지목을 뗄지 여부는 <b>마지막 글자가 한글일 때만</b> 판단한다. 지목이 없는 응답
     *       ({@code …3204-1})까지 잘라 버리면 정상 일치가 깨진다.</li>
     *   <li>공백은 앞뒤 제거 + 연속 공백 1칸으로만 정규화한다. {@code 상도리 3 204} 처럼 자리가
     *       다른 주소는 여전히 불일치다(공백을 지우는 게 아니라 겹친 것만 줄인다).</li>
     * </ul>
     */
    static boolean matchesQuery(String query, String returnedParcel) {
        if (returnedParcel == null || returnedParcel.isBlank()) {
            return false;
        }
        String normalizedQuery = normalize(query);
        String normalizedParcel = normalize(returnedParcel);
        return normalizedParcel.equals(normalizedQuery)
                || stripLandCategory(normalizedParcel).equals(normalizedQuery);
    }

    /** 끝 글자가 한글(지목)이면 뗀다. 아니면 그대로 — 지목이 없는 응답을 훼손하지 않는다. */
    private static String stripLandCategory(String parcel) {
        char last = parcel.charAt(parcel.length() - 1);
        return isHangulSyllable(last) ? parcel.substring(0, parcel.length() - 1) : parcel;
    }

    private static boolean isHangulSyllable(char c) {
        return c >= '가' && c <= '힣';
    }

    private static String normalize(String address) {
        return address == null ? "" : address.trim().replaceAll("\\s+", " ");
    }

    /**
     * 키가 없어도 기동은 되게 두고(헬스체크·수동 트리거를 함께 죽이지 않으려고), 실제 조회 시점에
     * 원인이 분명한 예외로 실패시킨다 — {@link VworldGeocodingClient} 와 같은 규율이다.
     */
    private void requireApiKey() {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "VWorld API 키가 없습니다. 환경변수 VWORLD_API_KEY 를 주입하세요 "
                            + "(루트 .env → local.sh 가 export). 프로퍼티: geocode.vworld.api-key");
        }
    }

    /**
     * 재시도 1회분(HTTP 1회 + 응답 해석).
     *
     * <p>package-private 인 이유는 테스트가 <b>감싸진 예외에 인증키가 섞이지 않는지</b>를 직접
     * 확인하기 위해서다 — {@link #geocode(String)} 는 재시도를 소진하면 예외를 삼킨다.
     */
    GeocodeOutcome request(String address) {
        apiCallCount.incrementAndGet();
        String body;
        try {
            body = restClient.get()
                    .uri(uri -> {
                        uri.queryParam("service", "search")
                                .queryParam("request", "search")
                                .queryParam("version", "2.0")
                                .queryParam("crs", "EPSG:4326");
                        String bbox = jejuBounds.bboxParam();
                        if (!bbox.isEmpty()) {
                            // 🔴 crs 바로 뒤에 둔다 — 값이 crs 기준으로 해석되므로 짝으로 읽혀야 한다.
                            uri.queryParam("bbox", bbox);
                        }
                        return uri.queryParam("type", "place")
                                .queryParam("format", "json")
                                .queryParam("size", SEARCH_SIZE)
                                .queryParam("page", 1)
                                .queryParam("query", address)
                                .queryParam("key", apiKey)
                                .build();
                    })
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // 원문 메시지는 인증키가 섞일 수 있는 URL 을 포함할 수 있어 예외 타입만 남긴다.
            throw new TransientGeocodeException(
                    "VWorld 장소 검색 호출 실패: " + e.getClass().getSimpleName(), e);
        }
        return parse(body, address);
    }

    private GeocodeOutcome parse(String body, String address) {
        JsonNode response;
        try {
            response = MAPPER.readTree(body == null ? "" : body).path("response");
        } catch (JsonProcessingException e) {
            throw new TransientGeocodeException("VWorld 장소 검색 응답 파싱 실패", e);
        }

        String status = response.path("status").asText("");
        if ("NOT_FOUND".equals(status)) {
            // 없는 지번은 fuzzy 매칭 없이 그대로 NOT_FOUND 로 답한다(실측 확인) — 재시도하지 않는다.
            return GeocodeOutcome.notFound("place=NOT_FOUND");
        }
        if (!"OK".equals(status)) {
            JsonNode error = response.path("error");
            String errorCode = error.path("code").asText("");
            if (VworldCallSupport.QUOTA_ERROR_CODE.equals(errorCode)) {
                // 재시도 대상이 아니다 — RetryTemplate 을 뚫고 Job 까지 그대로 올라간다(D9).
                throw new GeocodeQuotaExceededException(
                        "VWorld 호출 한도 초과(" + errorCode + "): " + error.path("text").asText(""));
            }
            throw new TransientGeocodeException(
                    "VWorld 장소 검색 status=" + status + " code=" + errorCode);
        }

        // 결과가 여러 건이어도 첫 건만 본다(D7) — 같은 지번의 POI 들이라 좌표가 사실상 동일하다.
        JsonNode item = response.path("result").path("items").path(0);
        if (item.isMissingNode() || item.isNull()) {
            log.warn("VWorld 장소 검색 status=OK 인데 items 가 비어 있습니다 — 미조회로 처리합니다.");
            return GeocodeOutcome.notFound("place=NOT_FOUND");
        }

        String parcel = item.path("address").path("parcel").asText(null);
        if (!matchesQuery(address, parcel)) {
            // 🔴 질의와 다른 지번의 POI 다. 좌표를 쓰면 엉뚱한 곳에 찍히므로 없는 것으로 본다.
            log.debug("장소 검색 결과의 주소가 질의와 달라 버립니다. query={} returned={}", address, parcel);
            return GeocodeOutcome.notFound(MISMATCH_DETAIL_PREFIX + "(" + parcel + ")");
        }

        JsonNode point = item.path("point");
        if (!point.hasNonNull("x") || !point.hasNonNull("y")) {
            log.warn("VWorld 장소 검색 결과에 point 가 없습니다 — 미조회로 처리합니다. query={}", address);
            return GeocodeOutcome.notFound("place=NOT_FOUND");
        }
        return GeocodeOutcome.found(
                Coordinate.of(point.get("x").asText(), point.get("y").asText()));
    }
}
