package jeju.datahub.batch.cvlcpt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API 응답 → {@code tb_cvlcpt} 매핑 검증 (계획서 11-1, 2026-09-01 원천 이관 반영).
 *
 * <p>여기서 지키는 것은 다섯이다 — <b>26컬럼이 제자리에 꽂히는가</b>, <b>NOT NULL 컬럼이
 * {@code -} placeholder 로 채워지는가</b>, <b>구조 필드({@code cvlcpt_id}/{@code ocrn_dt})가 비면
 * 실패하는가</b>, <b>정규화 대상({@code ans_ymd}·{@code ocrn_dt})이 실패해도 예외가 아니라
 * {@code null} 인가</b>, 그리고 <b>원천이 채워 보낼 수 있는 가공 컬럼을 빠짐없이 받아 오는가</b>.
 *
 * <p>마지막 항목이 중요한 이유: 받지 않으면 원천이 좌표를 채워 보내기 시작해도 배치가 알아채지
 * 못한 채 지오코딩 쿼터를 계속 태운다. "지금은 전량 NULL 이니 안 받아도 된다"가 정확히
 * TSK-4066 이 {@code prk_cvlcpt_type} 에 내렸던 판단이고, 그것을 되돌리는 것이 이 작업이다.
 */
class ComplaintOriginRowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 새 원천({@code jeju_smart_city_test.tb_cvlcpt}) 실호출 응답 1건 그대로(2026-09-01). */
    private static final String SAMPLE = """
            { "cvlcpt_id": "SPP-2501-2102594", "dclr_ymd": "2025-01-21",
              "ocrn_dt": "2026-01-21 00:00:00.000",
              "aplcnt_se": "개인", "dclr_fld": "교통", "src_path": "모바일앱",
              "dclr_ocrn_rgn": "제주특별자치도 서귀포시 동홍동 657-6",
              "ttl": "(SPP-2501-2102594)기타 불법 주정차 신고입니다.\\n\\n* 안전신문고 신고",
              "cvlcpt_cn": "기타 불법 주정차 신고입니다. 인도에 대형트럭 주차.",
              "prgrs_stts": "완료", "ans_prcs_stts": "수용", "ans_ymd": "2025.1.24 ",
              "rwrd_yn": "X", "prcs_inst_cnt": 1,
              "prcs_inst_nm": "제주특별자치도 서귀포시 안전도시건설국 교통행정과",
              "prcs_inst_pic_nm": "현시헌",
              "dclr_grp_nm": null, "mbr_grp_nm": null,
              "dclr_type": "기타불법주정차", "prk_cvlcpt_type": "기타 불법주정차",
              "aprs_cd": "보통",
              "cvlcpt_type_cd": null, "emd_cd": null, "lat": null, "lot": null, "src_cd": null }""";

    @Test
    @DisplayName("원천 load_dt를 포함한 27컬럼이 매핑된다")
    void mapsAllColumns() {
        ComplaintOriginRow row = ComplaintOriginRow.fromApi(parse(SAMPLE));

        assertThat(row.cvlcptId()).isEqualTo("SPP-2501-2102594");
        assertThat(row.dclrYmd()).isEqualTo("2025-01-21");
        assertThat(row.aplcntSe()).isEqualTo("개인");
        assertThat(row.dclrFld()).isEqualTo("교통");
        assertThat(row.srcPath()).isEqualTo("모바일앱");
        assertThat(row.dclrOcrnRgn()).isEqualTo("제주특별자치도 서귀포시 동홍동 657-6");
        // ttl 에 개행이 있다 — CSV 가 아니라 JSON 을 쓰는 이유이자, 원본 그대로 보존해야 하는 값이다.
        assertThat(row.ttl()).contains("\n").startsWith("(SPP-2501-2102594)");
        assertThat(row.cvlcptCn()).isEqualTo("기타 불법 주정차 신고입니다. 인도에 대형트럭 주차.");
        assertThat(row.prgrsStts()).isEqualTo("완료");
        assertThat(row.ansPrcsStts()).isEqualTo("수용");
        assertThat(row.rwrdYn()).isEqualTo("X");
        assertThat(row.prcsInstCnt()).isEqualTo(1);
        assertThat(row.prcsInstNm()).isEqualTo("제주특별자치도 서귀포시 안전도시건설국 교통행정과");
        assertThat(row.prcsInstPicNm()).isEqualTo("현시헌");
        assertThat(row.dclrType()).isEqualTo("기타불법주정차");

        // 🔁 2026-09-01 — prk_cvlcpt_type 을 다시 받는다(TSK-4066 D8 의 "버린다" 를 뒤집었다).
        //    분류에서 dclr_type CASE 보다 우선하며, 적재해 둬야 재분류의 근거가 된다.
        assertThat(row.prkCvlcptType()).isEqualTo("기타 불법주정차");

        // 감정은 원문 그대로(코드 변환은 Processor), 발생일시는 TIMESTAMP 로 파싱.
        assertThat(row.aprsRaw()).isEqualTo("보통");
        assertThat(row.ocrnDt()).isEqualTo(LocalDateTime.parse("2026-01-21T00:00:00"));

        // 🔴 ans_ymd 는 원천이 '2025.1.24 '(후행 공백까지) 로 준다 — ISO 로 정규화한다.
        assertThat(row.ansYmd()).isEqualTo("2025-01-24");

        // JSON null 은 문자열 "null" 이 아니라 자바 null 이어야 한다.
        assertThat(row.dclrGrpNm()).isNull();
        assertThat(row.mbrGrpNm()).isNull();

        // 실원천은 가공 컬럼을 전량 NULL 로 준다(2026-09-01) — 그래도 자리는 받아 둔다.
        assertThat(row.cvlcptTypeCd()).isNull();
        assertThat(row.emdCd()).isNull();
        assertThat(row.lat()).isNull();
        assertThat(row.lot()).isNull();
        assertThat(row.srcCd()).isNull();

        // SELECT 목록에 없어야 하는 것들 — 응답이 커지지 않게, 그리고 구 원천 이름이 남지 않게.
        assertThat(ComplaintOriginRow.API_COLUMNS)
                .doesNotContain("geom", "risk_lvl_cd",
                        "dclr_no", "dclr_ttl", "dclr_cn", "dclr_cn_sntmnt", "dclr_at",
                        "dclr_ocrn_tm", "report_no");
        assertThat(ComplaintOriginRow.API_COLUMNS).hasSize(27);
    }

    @Test
    @DisplayName("🔴 원천이 가공값을 채워 보내면 빠짐없이 받는다 — 받지 않으면 배치가 알아채지 못한다")
    void mapsSourceProvidedDerivedColumns() {
        ComplaintOriginRow row = ComplaintOriginRow.fromApi(parse("""
                { "cvlcpt_id": "SPP-2501-0000001", "ocrn_dt": "2026-05-01 10:00:00",
                  "cvlcpt_type_cd": "02", "emd_cd": "50110107",
                  "lat": 33.4996, "lot": 126.5312, "src_cd": "APP" }"""));

        assertThat(row.cvlcptTypeCd()).isEqualTo("02");
        assertThat(row.emdCd()).isEqualTo("50110107");
        assertThat(row.lat()).isEqualByComparingTo(new BigDecimal("33.4996"));
        assertThat(row.lot()).isEqualByComparingTo(new BigDecimal("126.5312"));
        assertThat(row.srcCd()).isEqualTo("APP");
    }

    @Test
    @DisplayName("좌표는 숫자로 와도 문자열로 와도 같은 값이다 — ClickHouse Decimal 이 문자열로 흐를 수 있다")
    void parsesCoordinatesFromNumberOrString() {
        assertThat(mapped("\"lat\": 33.4996").lat()).isEqualByComparingTo(new BigDecimal("33.4996"));
        assertThat(mapped("\"lat\": \"33.4996\"").lat()).isEqualByComparingTo(new BigDecimal("33.4996"));

        // 읽을 수 없는 좌표는 예외가 아니라 null — Processor 가 지오코딩으로 되돌아간다.
        assertThat(mapped("\"lat\": \"알 수 없음\"").lat()).isNull();
        assertThat(mapped("\"lat\": null").lat()).isNull();
        assertThat(mapped("\"lat\": \"\"").lat()).isNull();
    }

    @Test
    @DisplayName("🔴 NOT NULL 컬럼이 비면 빈 문자열이 아니라 '-' placeholder 가 들어간다")
    void fillsNotNullColumnsWithPlaceholder() {
        // prcs_inst_pic_nm(개인정보 · NOT NULL)이 null 로 오는 상황.
        // 실원천도 prcs_inst_pic_nm 395건 · prcs_inst_nm 394건을 비워 보낸다(2026-09-01 실측).
        ComplaintOriginRow row = ComplaintOriginRow.fromApi(parse("""
                { "cvlcpt_id": "SPP-2501-0000001", "ocrn_dt": "2026-01-01 00:00:00",
                  "dclr_ocrn_rgn": null, "ttl": "", "cvlcpt_cn": "   ",
                  "prgrs_stts": null, "ans_prcs_stts": null,
                  "prcs_inst_cnt": null, "prcs_inst_nm": null,
                  "prcs_inst_pic_nm": null, "dclr_type": null }"""));

        assertThat(row.dclrOcrnRgn()).isEqualTo("-");
        assertThat(row.ttl()).isEqualTo("-");
        assertThat(row.cvlcptCn()).isEqualTo("-");
        assertThat(row.prgrsStts()).isEqualTo("-");
        assertThat(row.ansPrcsStts()).isEqualTo("-");
        assertThat(row.prcsInstNm()).isEqualTo("-");
        assertThat(row.prcsInstPicNm()).isEqualTo("-");
        assertThat(row.dclrType()).isEqualTo("-");
        // integer NOT NULL 은 0.
        assertThat(row.prcsInstCnt()).isZero();

        // NULL 허용 컬럼까지 placeholder 로 채우지는 않는다 — 원천의 "없음"을 보존한다.
        assertThat(row.aplcntSe()).isNull();
        assertThat(row.ansYmd()).isNull();
        assertThat(row.rwrdYn()).isNull();
        assertThat(row.dclrYmd()).isNull();
        // 🔴 감정·주차민원유형도 NULL 허용이다. '-' 는 APRS_CD 에도 분류명에도 유효하지 않다.
        assertThat(row.aprsRaw()).isNull();
        assertThat(row.prkCvlcptType()).isNull();
    }

    @Test
    @DisplayName("ans_ymd 는 점(.) 구분을 ISO 로 정규화하고, 못 읽으면 예외 대신 null 이다")
    void normalisesAnswerDate() {
        assertThat(mapped("\"ans_ymd\": \"2025.5.7\"").ansYmd()).isEqualTo("2025-05-07");
        assertThat(mapped("\"ans_ymd\": \"2025.11.15\"").ansYmd()).isEqualTo("2025-11-15");
        // 이미 ISO 면 그대로 통과한다(구 원천 형태 · 재수집 호환).
        assertThat(mapped("\"ans_ymd\": \"2025-05-07\"").ansYmd()).isEqualTo("2025-05-07");
        // 읽을 수 없는 값은 null — 수집을 통째로 실패시키지 않는다.
        assertThat(mapped("\"ans_ymd\": \"미답변\"").ansYmd()).isNull();
        assertThat(mapped("\"ans_ymd\": \"2025.13.99\"").ansYmd()).isNull();
    }

    @Test
    @DisplayName("ocrn_dt 는 밀리초 유무와 무관하게 읽고, 못 읽으면 예외 대신 null 이다")
    void parsesOccurrenceDateTime() {
        assertThat(mapped("\"x\": 1").ocrnDt())
                .isEqualTo(LocalDateTime.parse("2026-01-01T00:00:00"));

        assertThat(withOcrnDt("2026-04-29 20:17:00.000"))
                .isEqualTo(LocalDateTime.parse("2026-04-29T20:17:00"));
        assertThat(withOcrnDt("2026-04-29 20:17:00"))
                .isEqualTo(LocalDateTime.parse("2026-04-29T20:17:00"));
        assertThat(withOcrnDt("2026-04-29T20:17"))
                .isEqualTo(LocalDateTime.parse("2026-04-29T20:17:00"));

        // 🔴 파싱 실패는 null — 예외로 수집을 끊지 않는다. 값의 "존재" 는 구조로 강제하지만
        //    (아래 failsFastOnStructuralFields) 그 형식까지 강제하면 한 행이 배치를 죽인다.
        //    Processor 가 신고일 자정으로 대체한다.
        assertThat(withOcrnDt("알 수 없음")).isNull();
    }

    @Test
    @DisplayName("원천 PK가 누락되거나 NULL이어도 load_dt 수집을 계속한다")
    void nullableSourceIdsAreAllowed() {
        assertThat(ComplaintOriginRow.fromApi(parse("{}" )).cvlcptId()).isNull();
        assertThat(ComplaintOriginRow.fromApi(parse("{\"cvlcpt_id\":null,\"load_dt\":\"2026-11-14 03:04:05.123456\"}")).loadDt())
                .isEqualTo(LocalDateTime.parse("2026-11-14T03:04:05.123456"));
        assertThatThrownBy(() -> ComplaintOriginRow.fromApi(parse("{\"load_dt\":\"bad\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 구조 필드만 채운 최소 응답에 검증 대상 필드 하나를 얹어 매핑한다. */
    private static ComplaintOriginRow mapped(String extraField) {
        return ComplaintOriginRow.fromApi(parse(
                "{\"cvlcpt_id\":\"SPP-2501-0000001\",\"ocrn_dt\":\"2026-01-01 00:00:00\","
                        + extraField + "}"));
    }

    private static LocalDateTime withOcrnDt(String raw) {
        return ComplaintOriginRow.fromApi(parse(
                "{\"cvlcpt_id\":\"SPP-2501-0000001\",\"ocrn_dt\":\"" + raw + "\"}")).ocrnDt();
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {   // 4.3(TSK-4336) — JSON 파싱·직렬화 실패만
            throw new IllegalStateException(e);
        }
    }
}
