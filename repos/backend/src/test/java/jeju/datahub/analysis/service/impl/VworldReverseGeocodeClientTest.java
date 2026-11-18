package jeju.datahub.analysis.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import jeju.datahub.analysis.service.impl.VworldCallSupport.LookupStatus;
import jeju.datahub.analysis.service.impl.VworldReverseGeocodeClient.ReverseAddress;

/**
 * {@link VworldReverseGeocodeClient} 단위 테스트.
 *
 * <p>검증 대상은 <b>parcel 우선 선택</b>과 <b>시도·시군구 접두 제거</b> 둘이다. 픽스처는 실측
 * 응답(아라일동 군집 중심 33.47749, 126.54188 → {@code 제주특별자치도 제주시 아라일동 2304-4})에서 따왔다.
 */
class VworldReverseGeocodeClientTest {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(VworldReverseGeocodeClientTest.class);

    private final VworldReverseGeocodeClient client =
            new VworldReverseGeocodeClient("https://api.vworld.kr", "dummy-key", 2000, 2);

    @Test
    @DisplayName("실측 응답: 시도·시군구 접두를 잘라 '아라일동 2304-4' 로 만든다")
    void parsesRealResponse() {
        String body = """
                {"response":{"status":"OK","result":[
                  {"type":"parcel","text":"제주특별자치도 제주시 아라일동 2304-4"}]}}""";

        ReverseAddress result = client.parse(body);

        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.address()).isEqualTo("아라일동 2304-4");
    }

    @Test
    @DisplayName("type=both 라도 지번(parcel)을 우선으로 읽는다 — 도로명이 앞에 와도 마찬가지")
    void prefersParcelOverRoad() {
        String body = """
                {"response":{"status":"OK","result":[
                  {"type":"road","text":"제주특별자치도 제주시 아라일동 아연로 123"},
                  {"type":"parcel","text":"제주특별자치도 제주시 아라일동 2304-4"}]}}""";

        assertThat(client.parse(body).address()).isEqualTo("아라일동 2304-4");
    }

    @Test
    @DisplayName("지번이 없으면 첫 결과를 쓴다 — 도로명이 없는 좌표가 실제로 있다")
    void fallsBackToFirstResult() {
        String body = """
                {"response":{"status":"OK","result":[
                  {"type":"road","text":"제주특별자치도 제주시 아라일동 아연로 123"}]}}""";

        assertThat(client.parse(body).address()).isEqualTo("아라일동 아연로 123");
    }

    @Test
    @DisplayName("NOT_FOUND 는 확정 실패다 — 재시도하지 않고 그대로 올라간다")
    void notFoundIsDeterministic() {
        assertThat(client.parse("""
                {"response":{"status":"NOT_FOUND"}}""").status()).isEqualTo(LookupStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("접두 제거는 읍면동까지 잘라 먹지 않는다")
    void stripKeepsEmdAndBelow() {
        assertThat(VworldReverseGeocodeClient.stripSidoSggPrefix("제주특별자치도 제주시 아라일동 2304-4"))
                .isEqualTo("아라일동 2304-4");
        assertThat(VworldReverseGeocodeClient.stripSidoSggPrefix("제주특별자치도 제주시 구좌읍 상도리 3204-1"))
                .isEqualTo("구좌읍 상도리 3204-1");
        assertThat(VworldReverseGeocodeClient.stripSidoSggPrefix("제주특별자치도 서귀포시 서귀동 285"))
                .isEqualTo("서귀동 285");
        // 이미 짧은 문자열은 건드리지 않는다 — 남는 토큰이 2개 미만이 되는 절단은 하지 않는다.
        assertThat(VworldReverseGeocodeClient.stripSidoSggPrefix("아라일동 2304-4"))
                .isEqualTo("아라일동 2304-4");
    }

    /**
     * 실 API 호출 검증. 키가 있을 때만 돈다 — 키 없는 CI 에서 빌드를 깨지 않으면서
     * 로컬에서는 실호출 검증이 자동으로 켜진다.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "VWORLD_API_KEY", matches = ".+")
    @DisplayName("[실호출] 아라일동 군집 중심 좌표의 지번을 받아 온다")
    void liveCallReturnsParcel() {
        VworldReverseGeocodeClient live = new VworldReverseGeocodeClient(
                "https://api.vworld.kr", System.getenv("VWORLD_API_KEY"), 5000, 2);

        ReverseAddress result = live.findAddress(new BigDecimal("33.47749"), new BigDecimal("126.54188"));

        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.address()).startsWith("아라일동");
        LOG.info("[실호출] 역지오코딩 = {}", result.address());   // 6.2 — System.out 대신 로거
    }
}
