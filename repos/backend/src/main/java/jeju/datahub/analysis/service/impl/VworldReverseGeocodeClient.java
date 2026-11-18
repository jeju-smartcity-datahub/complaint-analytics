package jeju.datahub.analysis.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.util.Locale;

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
 * VWorld <b>역지오코딩</b>으로 군집 중심좌표의 주소를 얻는다
 * (<a href="https://api.vworld.kr/req/address">api.vworld.kr/req/address</a>,
 * {@code request=getAddress}). 구역명 폴백 체인(TSK-4032 D6)의 <b>③단계</b>다.
 *
 * <pre>
 * GET /req/address?service=address&amp;request=getAddress&amp;version=2.0&amp;crs=epsg:4326
 *     &amp;point={경도},{위도}&amp;format=json&amp;type=both&amp;key={VWORLD_API_KEY}
 * </pre>
 *
 * <h2>응답 다듬기</h2>
 * {@code result[0].text} 는 {@code 제주특별자치도 제주시 아라일동 2304-4} 형태다.
 * <b>{@code 제주특별자치도 {시군구} } 접두를 잘라</b> {@code 아라일동 2304-4} 로 만든다 —
 * 구역명에 읍면동이 이미 들어가는 형태가 되어, 호출부가 읍면동을 다시 붙이지 않는다.
 *
 * <p>⚠️ {@code type=both} 인데도 <b>도로명이 없는 좌표가 있다</b>(실측). 그래서
 * {@code parcel}(지번) 을 우선으로 읽고, 없으면 첫 결과를 쓴다.
 *
 * <p>⚠️ {@link VworldPlaceClient} 와 응답 스키마가 다르다 — 여기는 {@code result} 가 배열이고
 * 저쪽은 {@code result.items[]} 다. 그래서 파싱을 공유하지 않는다(공유하는 것은
 * {@link VworldCallSupport} 의 호출 규율뿐).
 */
@Component
public class VworldReverseGeocodeClient {

    private static final Logger log = LoggerFactory.getLogger(VworldReverseGeocodeClient.class);

    /** 외부 JSON 파싱 전용 — 앱 직렬화 설정과 무관하게 동작하도록 자체 인스턴스를 쓴다(스레드 안전). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String apiKey;
    private final int maxAttempts;

    public VworldReverseGeocodeClient(
            @Value("${analysis.zone-name.vworld.base-url}") String baseUrl,
            @Value("${analysis.zone-name.vworld.api-key:}") String apiKey,
            @Value("${analysis.zone-name.vworld.timeout-ms:2000}") int timeoutMs,
            @Value("${analysis.zone-name.vworld.max-attempts:2}") int maxAttempts) {

        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.maxAttempts = maxAttempts;
        this.restClient = VworldCallSupport.restClient(baseUrl, timeoutMs);
    }

    /** 역지오코딩 결과 1건. */
    public record ReverseAddress(LookupStatus status, String address) {

        static ReverseAddress found(String address) {
            return new ReverseAddress(LookupStatus.FOUND, address);
        }

        static ReverseAddress notFound() {
            return new ReverseAddress(LookupStatus.NOT_FOUND, null);
        }

        static ReverseAddress transientFailure() {
            return new ReverseAddress(LookupStatus.TRANSIENT_FAILURE, null);
        }
    }

    /**
     * 중심좌표의 주소({@code 아라일동 2304-4} 형태 — 시도·시군구 접두 제거).
     *
     * @return 좌표에 주소가 없으면 {@code NOT_FOUND}, 외부 장애면 {@code TRANSIENT_FAILURE}
     */
    public ReverseAddress findAddress(BigDecimal lat, BigDecimal lot) {
        if (lat == null || lot == null) {
            return ReverseAddress.notFound();
        }
        if (apiKey.isEmpty()) {
            // 키가 없는 것은 "그 좌표에 주소가 없다"가 아니다 — 캐시에 굳히지 않도록 일시 실패로 본다.
            return ReverseAddress.transientFailure();
        }
        return VworldCallSupport.withRetry(
                "VWorld 역지오코딩", maxAttempts,
                () -> request(lat.doubleValue(), lot.doubleValue()),
                ReverseAddress::transientFailure);
    }

    /** 재시도 1회분(HTTP 1회 + 응답 해석). 테스트가 직접 부를 수 있게 package-private 이다. */
    ReverseAddress request(double lat, double lot) {
        String point = String.format(Locale.ROOT, "%.6f,%.6f", lot, lat);   // ⚠️ 경도,위도 순
        String body;
        try {
            body = restClient.get()
                    .uri(uri -> uri.path("/req/address")
                            .queryParam("service", "address")
                            .queryParam("request", "getAddress")
                            .queryParam("version", "2.0")
                            .queryParam("crs", "epsg:4326")
                            .queryParam("point", point)
                            .queryParam("format", "json")
                            .queryParam("type", "both")
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // 원문 메시지에는 인증키가 섞인 URL 이 들어갈 수 있어 예외 타입만 남긴다.
            throw new TransientVworldException("VWorld 역지오코딩 호출 실패: " + e.getClass().getSimpleName(), e);
        }
        return parse(body);
    }

    /** 응답 → 다듬은 주소. 파싱 실패는 일시 실패로 본다. */
    ReverseAddress parse(String body) {
        JsonNode response;
        try {
            response = MAPPER.readTree(body == null ? "" : body).path("response");
        } catch (JsonProcessingException e) {
            throw new TransientVworldException("VWorld 역지오코딩 응답 파싱 실패", e);
        }

        String status = response.path("status").asText("");
        if ("NOT_FOUND".equals(status)) {
            return ReverseAddress.notFound();   // 확정 실패 — 재시도하지 않는다
        }
        if (!"OK".equals(status)) {
            throw new TransientVworldException("VWorld 역지오코딩 status=" + status
                    + " code=" + response.path("error").path("code").asText(""));
        }

        JsonNode results = response.path("result");
        String text = pickText(results);
        if (text == null || text.isBlank()) {
            log.debug("VWorld 역지오코딩 status=OK 인데 주소 문자열이 없습니다 — 미조회로 처리합니다.");
            return ReverseAddress.notFound();
        }
        return ReverseAddress.found(stripSidoSggPrefix(text));
    }

    /** {@code parcel}(지번) 우선. 없으면 첫 결과 — {@code type=both} 라도 도로명이 없는 좌표가 있다. */
    private static String pickText(JsonNode results) {
        String first = null;
        for (JsonNode r : results) {
            String text = r.path("text").asText(null);
            if (text == null || text.isBlank()) {
                continue;
            }
            if ("parcel".equals(r.path("type").asText(""))) {
                return text;
            }
            if (first == null) {
                first = text;
            }
        }
        return first;
    }

    /**
     * {@code 제주특별자치도 제주시 아라일동 2304-4} → {@code 아라일동 2304-4}.
     *
     * <p>시도(…도/…시) 토큰과 시군구(…시/…군/…구) 토큰을 앞에서 최대 1개씩만 걷어낸다.
     * 남는 토큰이 2개 미만이 되는 절단은 하지 않는다 — 읍면동까지 잘라 버리면 이름이 지번만 남는다.
     */
    static String stripSidoSggPrefix(String text) {
        String[] parts = text.trim().split("\\s+");
        int skip = 0;
        if (parts.length - skip >= 3 && (parts[skip].endsWith("도") || parts[skip].endsWith("시"))) {
            skip++;
        }
        if (parts.length - skip >= 3
                && (parts[skip].endsWith("시") || parts[skip].endsWith("군") || parts[skip].endsWith("구"))) {
            skip++;
        }
        return String.join(" ", java.util.Arrays.copyOfRange(parts, skip, parts.length));
    }
}
