package jeju.datahub.batch.pblpklt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;

/**
 * pblpklt 응답 JSON → {@link PblpkltOriginRow} 매핑 검증 (TSK-4142 Step 5 · Step 9).
 *
 * <h2>🔴 왜 리플렉션 전수 검출인가</h2>
 * {@code fromApi()} 가 빌더를 쓰므로 <b>setter 하나를 빠뜨려도 컴파일이 통과</b>하고 그 필드만
 * 조용히 {@code null} 이 된다. 필드를 손으로 나열해 검증하면 "새로 추가한 필드를 테스트에도
 * 빠뜨리는" 같은 실수가 그대로 재현된다. 그래서 35키를 전부 non-null 로 채운 JSON 을 넣고
 * {@link Class#getRecordComponents()} 로 <b>모든 accessor</b> 를 돌며 non-null·기대값 일치를
 * 단언한다 — 필드를 늘려도 이 테스트가 자동으로 따라온다.
 *
 * <p>{@code extsh_dt} 는 원천 35필드 중 <b>유일하게 record 에 없는 필드</b>다(소멸 판정은
 * {@code PblpkltSoftDeleteMarkingTasklet} 몫). "빠뜨린 것" 과 "일부러 뺀 것" 을 구분하기 위해
 * 그 부재도 함께 단언한다.
 */
class PblpkltOriginRowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** record 에 매핑하지 않는 유일한 원천 필드. */
    private static final String NOT_MAPPED = "extsh_dt";

    /**
     * 원천 35키 — <b>전부 non-null</b>. 실측 응답(2026-09-11, 데이터셋 {@code dataset.95c563b4-…})의
     * 값 모양을 그대로 쓰되 null 자리는 그 컬럼의 실제 값 예시로 채웠다.
     *
     * <p>🔴 {@code pkltar}·{@code rttrt} 는 원천이 <b>문자열</b>로 준다 — {@code BigDecimal} 변환
     * 경로를 타는지 여기서 함께 확인한다. JSON 은 이 표에서 생성하므로 키가 어긋날 수 없다.
     */
    private static final Map<String, Object> SOURCE_35 = new LinkedHashMap<>();

    static {
        SOURCE_35.put("pklt_id", "1916");
        SOURCE_35.put("pklt_nm", "대정상모 공영노외1 주차장");
        SOURCE_35.put("emd_cd", 50130250L);
        SOURCE_35.put("lat", 33.2185123456);
        SOURCE_35.put("lot", 126.2521123456);
        SOURCE_35.put("pklt_cpcty", 37);
        SOURCE_35.put("pklt_type_cd", "OFSTR");
        SOURCE_35.put(NOT_MAPPED, "2026-09-11 00:00:00");   // 🔴 record 에 없다
        SOURCE_35.put("orgnl_id", "ORG-1916");
        SOURCE_35.put("orgnl_sn", 131);
        SOURCE_35.put("emd_nm", "대정읍");
        SOURCE_35.put("sgg_nm", "서귀포시");        // 🔁 2026-09-29 TSK-4192 신규(지오코딩 접두용)
        SOURCE_35.put("pklt_type_nm", "노외공영");
        SOURCE_35.put("pkltar", "1073.000000000");          // 🔴 문자열 → BigDecimal
        SOURCE_35.put("prk_crg_yn", "Y");
        SOURCE_35.put("prk_crg_cn", "30분 500원");
        SOURCE_35.put("prk_crg_swtc_ym", "202602");
        SOURCE_35.put("instl_ym", "202510");
        SOURCE_35.put("instl_ymd", "20251015");
        SOURCE_35.put("rttrt", "1.46");                     // 🔴 문자열 → BigDecimal
        SOURCE_35.put("day_avg_vhcl_cnt", 54);
        SOURCE_35.put("incm_amt", 2118400);
        SOURCE_35.put("expnd_amt", 31000);
        SOURCE_35.put("mng_nope", 2);
        SOURCE_35.put("mng_dept_nm", "교통행정과");
        SOURCE_35.put("pklt_mthd_nm", "직각주차");
        SOURCE_35.put("ev_chrgr_cnt", 4);
        SOURCE_35.put("ownshp_type_nm", "공유");
        SOURCE_35.put("prk_slot_grd", "A");
        SOURCE_35.put("obstcl_cn", "경계석");
        SOURCE_35.put("obstcl_slot_cnt", 1);
        SOURCE_35.put("trfc_sfty_sign_instl_yn", "Y");
        SOURCE_35.put("stpr_instl_yn", "N");
        SOURCE_35.put("rmrk_cn", "2026년 재조사 대상");
        SOURCE_35.put("pklt_addr", "상모리 3964-1");
    }

    // ── 1. 필드 누락 전수 검출 ────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 35키를 전부 채우면 record 의 34개 accessor 가 전부 non-null 이고 기대값과 같다")
    void mapsEveryRecordComponentWithoutOmission() {
        PblpkltOriginRow row = PblpkltOriginRow.fromApi(parse(toJson(SOURCE_35)));

        RecordComponent[] components = PblpkltOriginRow.class.getRecordComponents();
        // 원천 34 − extsh_dt = 33. 이 수가 바뀌면 record 를 손댄 것이므로 여기서 먼저 드러난다.
        assertThat(components).hasSize(SOURCE_35.size() - 1);

        for (RecordComponent component : components) {
            String jsonKey = toSnake(component.getName());
            assertThat(SOURCE_35)
                    .as("record 필드 %s 에 대응하는 원천 키 %s 가 픽스처에 없다 — "
                            + "필드를 늘렸다면 SOURCE_35 에도 넣는다", component.getName(), jsonKey)
                    .containsKey(jsonKey);

            Object actual = accessorValue(row, component);
            assertThat(actual)
                    .as("🔴 %s 가 null 이다 — fromApi() 의 빌더 setter 가 빠졌을 가능성이 크다",
                            component.getName())
                    .isNotNull();
            assertMatches(component, actual, SOURCE_35.get(jsonKey));
        }
    }

    @Test
    @DisplayName("🔴 extsh_dt 는 record 에 없다 — 소멸 판정은 Tasklet 몫이라 일부러 뺐다")
    void doesNotMapExtinctionTimestamp() {
        assertThat(PblpkltOriginRow.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("extshDt")
                .noneMatch(name -> toSnake(name).equals(NOT_MAPPED));
    }

    @Test
    @DisplayName("🔴 SOURCE_COLUMNS(수집 SELECT 목록)가 record 33필드와 정확히 같다 — 빠지면 그 컬럼이 조용히 전 행 null 적재")
    void sourceColumnsMatchRecordComponentsExactly() {
        // 🔁 2026-09-17 TSK-4161 Step 12 — analytics/query 는 SELECT 한 컬럼만 돌려준다. 매핑에 필드를 늘리고
        //    SOURCE_COLUMNS 에 빠뜨리면 fromApi() 는 결측=null 로 통과해 아무 오류 없이 NULL 이 적재된다.
        java.util.List<String> fromRecord = java.util.Arrays.stream(PblpkltOriginRow.class.getRecordComponents())
                .map(c -> toSnake(c.getName())).toList();

        assertThat(PblpkltOriginRow.SOURCE_COLUMNS)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(fromRecord)
                .doesNotContain(NOT_MAPPED);
    }

    @Test
    @DisplayName("🔴 SOURCE_COLUMNS 로만 채운 JSON 으로 34 accessor 가 전부 non-null — fromApi 가 목록 밖 필드를 읽지 않는다")
    void fromApiReadsNothingOutsideSourceColumns() {
        Map<String, Object> onlySelected = new LinkedHashMap<>();
        PblpkltOriginRow.SOURCE_COLUMNS.forEach(c -> onlySelected.put(c, SOURCE_35.get(c)));

        PblpkltOriginRow row = PblpkltOriginRow.fromApi(parse(toJson(onlySelected)));

        for (RecordComponent component : PblpkltOriginRow.class.getRecordComponents()) {
            assertThat(accessorValue(row, component))
                    .as("%s — SELECT 목록만으로는 채워지지 않는다(수집 경로에서 전 행 null)", component.getName())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("🔴 경로별 표기 차 — 덤프 문자열(\"360.000000000\"·\"1.46\")과 analytics/query 숫자(360·1.46)가 같은 값이다")
    void dumpStringAndQueryNumberMapToSameDecimal() {
        // 2026-09-17 전량 2,272행 대조: pkltar 1,272행·rttrt 30행이 덤프는 문자열, analytics/query 는 JSON 숫자로 왔다
        // (정수값은 IntNode 360, 소수는 DoubleNode 1.46). 나머지 31필드는 전 행 동일. 목적지 NUMERIC(19,9)·(5,2) 가
        // 스케일을 고정하므로 compareTo 가 같으면 적재값이 같다 — 해시·비교키가 아니라 정규화를 두지 않는다.
        PblpkltOriginRow dump = PblpkltOriginRow.fromApi(parse("""
                {"pklt_id":"1","pkltar":"360.000000000","rttrt":"1.46","incm_amt":2118400}"""));
        PblpkltOriginRow query = PblpkltOriginRow.fromApi(parse("""
                {"pklt_id":"1","pkltar":360,"rttrt":1.46,"incm_amt":2118400}"""));
        PblpkltOriginRow queryFraction = PblpkltOriginRow.fromApi(parse("""
                {"pklt_id":"1","pkltar":1073.5,"rttrt":0}"""));

        assertThat(query.pkltar()).isEqualByComparingTo(dump.pkltar());
        assertThat(query.rttrt()).isEqualByComparingTo(dump.rttrt());
        assertThat(query.incmAmt()).isEqualByComparingTo(dump.incmAmt());
        assertThat(queryFraction.pkltar().setScale(9)).isEqualTo(new BigDecimal("1073.500000000"));
        assertThat(queryFraction.rttrt().setScale(2)).isEqualTo(new BigDecimal("0.00"));
    }

    // ── 2. 숫자 파싱 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("pkltar · rttrt 는 문자열로 와도 BigDecimal 이다 — 원천이 문자열로 준다")
    void parsesDecimalsFromStrings() {
        PblpkltOriginRow row = PblpkltOriginRow.fromApi(parse(toJson(SOURCE_35)));

        assertThat(row.pkltar()).isEqualByComparingTo(new BigDecimal("1073.000000000"));
        assertThat(row.rttrt()).isEqualByComparingTo(new BigDecimal("1.46"));
    }

    @Test
    @DisplayName("숫자로 읽을 수 없는 값은 행을 죽이지 않고 그 필드만 null 이다")
    void keepsRowAliveWhenNumericFieldIsUnparsable() {
        PblpkltOriginRow row = PblpkltOriginRow.fromApi(parse("""
                {"pklt_id":"12","pkltar":"알 수 없음","pklt_cpcty":"","day_avg_vhcl_cnt":"  ",
                 "ev_chrgr_cnt":1.5,"pklt_nm":"살아남는다"}"""));

        assertThat(row.pkltar()).isNull();
        assertThat(row.pkltCpcty()).isNull();
        assertThat(row.dayAvgVhclCnt()).isNull();
        assertThat(row.evChrgrCnt()).isNull();   // 정수 필드에 소수 → 절삭하지 않고 null
        assertThat(row.pkltNm()).isEqualTo("살아남는다");
    }

    // ── 3. 결측 · 필수 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 pklt_id 만 있는 행은 나머지 32개 accessor 가 전부 null 이다 — 필드 밀림 검출")
    void mapsNullRowWithoutFieldShifting() {
        PblpkltOriginRow row = PblpkltOriginRow.fromApi(parse("{\"pklt_id\":\"12\"}"));

        assertThat(row.pkltId()).isEqualTo("12");
        for (RecordComponent component : PblpkltOriginRow.class.getRecordComponents()) {
            if (component.getName().equals("pkltId")) {
                continue;
            }
            assertThat(accessorValue(row, component))
                    .as("%s — 원천이 주지 않은 값이 채워져 있다(필드 밀림 의심)", component.getName())
                    .isNull();
        }
    }

    @Test
    @DisplayName("🔴 pklt_id 가 없거나 비면 DhubApiException — 원천 식별자 없이는 장부가 성립하지 않는다")
    void failsFastWithoutSourceIdentifier() {
        assertThatThrownBy(() -> PblpkltOriginRow.fromApi(parse("{\"pklt_nm\":\"이름만 있다\"}")))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("pklt_id");

        assertThatThrownBy(() -> PblpkltOriginRow.fromApi(parse("{\"pklt_id\":null}")))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("pklt_id");

        assertThatThrownBy(() -> PblpkltOriginRow.fromApi(parse("{\"pklt_id\":\"   \"}")))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("pklt_id");

        assertThatThrownBy(() -> PblpkltOriginRow.fromApi(null))
                .isInstanceOf(DhubApiException.class);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** accessor 타입에 맞춰 픽스처 원문을 변환해 비교한다(문자열로 온 숫자까지 한 규칙으로). */
    private static void assertMatches(RecordComponent component, Object actual, Object expectedRaw) {
        String raw = String.valueOf(expectedRaw);
        Class<?> type = component.getType();
        String name = component.getName();

        if (type == BigDecimal.class) {
            assertThat((BigDecimal) actual).as(name).isEqualByComparingTo(new BigDecimal(raw));
        } else if (type == Double.class) {
            assertThat((Double) actual).as(name).isEqualTo(Double.parseDouble(raw));
        } else if (type == Integer.class) {
            assertThat((Integer) actual).as(name).isEqualTo(Integer.parseInt(raw));
        } else if (type == Long.class) {
            assertThat((Long) actual).as(name).isEqualTo(Long.parseLong(raw));
        } else {
            assertThat(actual).as(name).isEqualTo(raw);
        }
    }

    private static Object accessorValue(PblpkltOriginRow row, RecordComponent component) {
        try {
            return component.getAccessor().invoke(row);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(component.getName() + " accessor 호출 실패", e);
        }
    }

    /** {@code pkltTypeNm} → {@code pklt_type_nm}. record 필드명과 원천 키의 대응 규칙은 이것 하나다. */
    private static String toSnake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String toJson(Map<String, Object> values) {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException e) {   // 4.3(TSK-4336) — JSON 파싱·직렬화 실패만
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {   // 4.3(TSK-4336) — JSON 파싱·직렬화 실패만
            throw new IllegalStateException(e);
        }
    }
}
