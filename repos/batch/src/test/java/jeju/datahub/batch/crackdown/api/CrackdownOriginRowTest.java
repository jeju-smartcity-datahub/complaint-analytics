package jeju.datahub.batch.crackdown.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;

/**
 * API 응답 → {@code parking.tb_crdn_dsctn} 매핑 검증 (2026-09-14 스키마 — 원천·목적지 컬럼명 동일).
 *
 * <p>여기서 지키는 것은 넷이다 — <b>7컬럼이 이름 변환 없이 제자리에 꽂히는가</b>(결측은 NULL 통과),
 * <b>날짜 파싱 실패가 조용히 삼켜지지 않고 예외로 드러나는가</b>(수집 잡 원칙),
 * <b>원천 필드가 통째로 없는 행이 예외로 드러나는가</b>(원천 스키마 변경 가드),
 * <b>src_hash 가 결정적이면서 필드 경계 조작에 흔들리지 않는가</b>(멱등 upsert 의 근거).
 */
class CrackdownOriginRowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 운영 기본값(application.yml · DhubProperties)과 같은 값 — 6자리 소수부를 더했다. */
    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern(PATTERN);

    /** 확정 스키마(2026-09-14)의 예시 행 — 컬럼명은 목적지 테이블과 동일하고 ffnlg 는 원천처럼 숫자다. */
    private static final String SAMPLE = """
            { "crdn_dt": "2025-01-01 00:07:00.000000", "ffnlg": 40000.0,
              "crdn_mthd": "민원(공익제보)", "carmdl": "화물(4톤이하)",
              "orgnl_cn": "용담이동 641-29부근 횡단보도",
              "crdn_plc": "횡단보도", "crdn_addr": "용담이동 641-29" }""";

    @Test
    @DisplayName("7컬럼이 이름 변환 없이 1:1 매핑되고 crdn_dt 만 타입 변환된다")
    void mapsAllColumns() {
        CrackdownOriginRow row = CrackdownOriginRow.fromApi(parse(SAMPLE), FORMAT, PATTERN);

        assertThat(row.crdnDt()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 7));
        assertThat(row.ffnlg()).isEqualTo("40000.0");
        assertThat(row.crdnMthd()).isEqualTo("민원(공익제보)");
        assertThat(row.carmdl()).isEqualTo("화물(4톤이하)");
        assertThat(row.orgnlCn()).isEqualTo("용담이동 641-29부근 횡단보도");
        assertThat(row.crdnPlc()).isEqualTo("횡단보도");
        assertThat(row.crdnAddr()).isEqualTo("용담이동 641-29");
        assertThat(row.srcHash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("crdn_dt 는 밀리초가 있어도 없어도 파싱된다 — 패턴의 [.SSS] 는 선택 구간이다")
    void parsesWithAndWithoutMillis() {
        CrackdownOriginRow withMillis = CrackdownOriginRow.fromApi(
                withField("crdn_dt", "2025-01-13 14:30:05.123"), FORMAT, PATTERN);
        CrackdownOriginRow withoutMillis = CrackdownOriginRow.fromApi(
                withField("crdn_dt", "2025-01-13 14:30:05"), FORMAT, PATTERN);

        assertThat(withMillis.crdnDt()).isEqualTo(LocalDateTime.of(2025, 1, 13, 14, 30, 5, 123_000_000));
        assertThat(withoutMillis.crdnDt()).isEqualTo(LocalDateTime.of(2025, 1, 13, 14, 30, 5));
    }

    @Test
    @DisplayName("날짜 파싱 실패는 삼키지 않고 예외 — 메시지에 패턴과 원문이 담긴다")
    void failsLoudlyOnBadDate() {
        assertThatThrownBy(() -> CrackdownOriginRow.fromApi(
                withField("crdn_dt", "이건 날짜가 아님"), FORMAT, PATTERN))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("crdn_dt")
                .hasMessageContaining(PATTERN)
                .hasMessageContaining("이건 날짜가 아님");
    }

    @Test
    @DisplayName("결측은 NULL 통과 — 목적지 전 컬럼이 nullable 이다 (원본 보존)")
    void passesNullsThrough() {
        CrackdownOriginRow row = CrackdownOriginRow.fromApi(
                parse("{ \"crdn_mthd\": \"민원(공익제보)\" }"), FORMAT, PATTERN);

        assertThat(row.crdnDt()).isNull();
        assertThat(row.ffnlg()).isNull();
        assertThat(row.crdnMthd()).isEqualTo("민원(공익제보)");
        assertThat(row.crdnAddr()).isNull();
        assertThat(row.srcHash()).hasSize(64);
    }

    @Test
    @DisplayName("ffnlg 는 숫자 파싱 없이 원본 문자열 그대로 — 목적지도 varchar 다")
    void keepsFineAsRawString() {
        CrackdownOriginRow row = CrackdownOriginRow.fromApi(
                withField("ffnlg", "40,000원"), FORMAT, PATTERN);

        assertThat(row.ffnlg()).isEqualTo("40,000원");
    }

    @Test
    @DisplayName("src_hash — 같은 행이면 같은 값, 한 필드만 달라도 다른 값 (멱등 upsert 근거)")
    void hashIsDeterministicAndSensitive() {
        String h1 = CrackdownOriginRow.fromApi(parse(SAMPLE), FORMAT, PATTERN).srcHash();
        String h2 = CrackdownOriginRow.fromApi(parse(SAMPLE), FORMAT, PATTERN).srcHash();
        String h3 = CrackdownOriginRow.fromApi(withField("carmdl", "승용"), FORMAT, PATTERN).srcHash();

        assertThat(h1).isEqualTo(h2).isNotEqualTo(h3);
    }

    @Test
    @DisplayName("src_hash — 필드 경계가 구분자로 보호된다: (\"ab\",\"c\") ≠ (\"a\",\"bc\")")
    void hashIsBoundarySafe() {
        String h1 = CrackdownOriginRow.fromApi(withNeighborFields("ab", "c"), FORMAT, PATTERN).srcHash();
        String h2 = CrackdownOriginRow.fromApi(withNeighborFields("a", "bc"), FORMAT, PATTERN).srcHash();

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("src_hash — 숫자 ffnlg 는 Jackson 표기 \"40000.0\" 으로 해시된다 (정수 정규화 금지 — 기존 적재 행 호환)")
    void hashesNumericFineAsJacksonText() {
        String actual = CrackdownOriginRow.fromApi(parse(SAMPLE), FORMAT, PATTERN).srcHash();

        String expected = jeju.datahub.batch.collect.SourceFingerprint.hash(
                "2025-01-01 00:07:00.000000", "40000.0", "민원(공익제보)", "화물(4톤이하)",
                "용담이동 641-29부근 횡단보도", "횡단보도", "용담이동 641-29");
        String integerNormalized = jeju.datahub.batch.collect.SourceFingerprint.hash(
                "2025-01-01 00:07:00.000000", "40000", "민원(공익제보)", "화물(4톤이하)",
                "용담이동 641-29부근 횡단보도", "횡단보도", "용담이동 641-29");

        assertThat(actual).isEqualTo(expected).isNotEqualTo(integerNormalized);
    }

    @Test
    @DisplayName("ffnlg 정수 JSON(40000, analytics/query) 과 실수 JSON(40000.0, 종전 table 덤프)이 같은 값·같은 src_hash 가 된다")
    void integerAndDoubleFineNormalizeToSameValue() {
        CrackdownOriginRow asDouble = CrackdownOriginRow.fromApi(parse(SAMPLE), FORMAT, PATTERN);
        CrackdownOriginRow asInt = CrackdownOriginRow.fromApi(
                parse(SAMPLE.replace("40000.0", "40000")), FORMAT, PATTERN);

        assertThat(asInt.ffnlg()).isEqualTo("40000.0").isEqualTo(asDouble.ffnlg());
        assertThat(asInt.srcHash()).isEqualTo(asDouble.srcHash());
    }

    @Test
    @DisplayName("ffnlg 정수 0 → \"0.0\" (Double.toString 표기)")
    void zeroFineNormalizesToDoubleText() {
        CrackdownOriginRow row = CrackdownOriginRow.fromApi(
                parse(SAMPLE.replace("40000.0", "0")), FORMAT, PATTERN);

        assertThat(row.ffnlg()).isEqualTo("0.0");
    }

    /**
     * 🔴 고정값 — 시드 {@code 11-TB_CRACKDOWN.csv} 첫 데이터 행(crackdown_id=1)을 analytics/query 처럼
     * <b>정수 ffnlg</b> 로 받아도 시드에 박힌 src_hash 와 같아야 한다. 어긋나면 수집이 시드·기존
     * 적재분과 맞물리지 않아 전량 중복 적재된다. CSV 옛 컬럼명 → 현 원천 필드명:
     * date→crdn_dt, fine→ffnlg, kind→crdn_mthd, vehicle→carmdl, raw→orgnl_cn,
     * location→crdn_plc, address→crdn_addr.
     */
    @Test
    @DisplayName("고정값 — 시드 CSV 1행을 정수 ffnlg(40000) 로 받아도 src_hash = d21b58e939072d983fd6cf0a1d644a4d11b4d94dc2babc2697b232c5fec58de1")
    void integerFineMatchesSeedCsvHash() {
        JsonNode seedRow = parse("""
                { "crdn_dt": "2025-12-01 00:21:00.000", "ffnlg": 40000,
                  "crdn_mthd": "민원(공익제보)", "carmdl": "승용",
                  "orgnl_cn": "도로교통법 제32조~제34조",
                  "crdn_plc": "외도일동 751-5부근 횡단보도", "crdn_addr": "외도일동 751-5" }""");

        CrackdownOriginRow row = CrackdownOriginRow.fromApi(seedRow, FORMAT, PATTERN);

        assertThat(row.ffnlg()).isEqualTo("40000.0");
        assertThat(row.srcHash()).isEqualTo("d21b58e939072d983fd6cf0a1d644a4d11b4d94dc2babc2697b232c5fec58de1");
    }

    @Test
    @DisplayName("원천 7필드가 하나도 없는 행(옛 이름 crackdown_*)은 예외 — 메시지엔 필드명만, 값은 싣지 않는다")
    void failsWhenAllSourceFieldsMissing() {
        JsonNode oldSchema = parse("""
                { "crackdown_date": "1/1/25 0:07", "crackdown_fine": "40000",
                  "crackdown_kind": "민원(공익제보)", "crackdown_vehicle": "화물(4톤이하)",
                  "crackdown_raw": "용담이동 641-29부근 횡단보도",
                  "crackdown_location": "횡단보도", "crackdown_address": "용담이동 641-29" }""");

        assertThatThrownBy(() -> CrackdownOriginRow.fromApi(oldSchema, FORMAT, PATTERN))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("crdn_dt")
                .hasMessageContaining("crackdown_date")
                .hasMessageNotContaining("용담이동");
    }

    @Test
    @DisplayName("필드는 있고 값만 null 이면 예외 없이 NULL 통과 — 금액 결측 행 경로")
    void allowsNullValueWhenFieldPresent() {
        CrackdownOriginRow row = CrackdownOriginRow.fromApi(withField("ffnlg", null), FORMAT, PATTERN);

        assertThat(row.ffnlg()).isNull();
        assertThat(row.crdnDt()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 7));
        assertThat(row.srcHash()).hasSize(64);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {   // 4.3 — JSON 파싱·직렬화 실패만
            throw new IllegalStateException(e);
        }
    }

    /** SAMPLE 에서 한 필드만 바꾼(또는 null 로 지운) 행. */
    private static JsonNode withField(String field, String value) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) parse(SAMPLE);
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
        return node;
    }

    /** 경계 검증용 — 이웃 필드 쌍(crdn_plc / crdn_addr)만 바꾼 행. */
    private static JsonNode withNeighborFields(String location, String address) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) parse(SAMPLE);
        node.put("crdn_plc", location);
        node.put("crdn_addr", address);
        return node;
    }

    @Test
    @DisplayName("🆕 소수부 6자리(새 원천)·3자리·없음을 모두 읽고, 동일 시각의 표기 차이는 정규화해 같은 해시로 만든다")
    void parsesMicroAndMilliFractions() {
        CrackdownOriginRow micro = CrackdownOriginRow.fromApi(
                withField("crdn_dt", "2025-01-30 17:52:13.000000"), FORMAT, PATTERN);
        CrackdownOriginRow microNonZero = CrackdownOriginRow.fromApi(
                withField("crdn_dt", "2025-01-30 17:52:13.123456"), FORMAT, PATTERN);
        CrackdownOriginRow milli = CrackdownOriginRow.fromApi(
                withField("crdn_dt", "2025-01-30 17:52:13.000"), FORMAT, PATTERN);
        CrackdownOriginRow none = CrackdownOriginRow.fromApi(
                withField("crdn_dt", "2025-01-30 17:52:13"), FORMAT, PATTERN);

        assertThat(micro.crdnDt()).isEqualTo(java.time.LocalDateTime.of(2025, 1, 30, 17, 52, 13));
        assertThat(microNonZero.crdnDt()).isEqualTo(java.time.LocalDateTime.of(2025, 1, 30, 17, 52, 13, 123_456_000));
        assertThat(milli.crdnDt()).isEqualTo(micro.crdnDt());
        assertThat(none.crdnDt()).isEqualTo(micro.crdnDt());
        // 🔴 해시는 원문 — 패턴을 바꿔도 기존 적재분 해시는 그대로다(같은 원문이면 같은 해시).
        assertThat(micro.srcHash()).isEqualTo(milli.srcHash()).isEqualTo(none.srcHash());
        assertThat(microNonZero.srcHash()).isNotEqualTo(micro.srcHash());
    }

    @Test
    @DisplayName("🆕 운영 기본 패턴(설정 기본값)이 테스트 상수와 같다")
    void defaultPatternMatchesProperties() {
        assertThat(new jeju.datahub.batch.cvlcpt.api.DhubProperties().getCrackdownDatePattern()).isEqualTo(PATTERN);
    }
}
