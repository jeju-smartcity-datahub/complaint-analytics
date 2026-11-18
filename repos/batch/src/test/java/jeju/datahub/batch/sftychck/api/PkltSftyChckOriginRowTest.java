package jeju.datahub.batch.sftychck.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;

/** 원천 1행 매핑 — 필드 매핑 · {@code pklt_id} 유무 · 필드 누락·형식 불일치는 예외(스킵 아님). */
class PkltSftyChckOriginRowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("전 필드 매핑 — chck_ymd int64 는 정수 표기 문자열로, NULL rmrk_cn 은 null 로")
    void mapsAllFields() {
        PkltSftyChckOriginRow row = PkltSftyChckOriginRow.from(node(true), true);

        assertThat(row.sftyChckId()).isEqualTo(12L);
        assertThat(row.pkltId()).isEqualTo(345L);
        assertThat(row.chckYmd()).isEqualTo("20250301");
        assertThat(row.chckPrdCd()).isEqualTo("H1");
        assertThat(row.sftyChckTypeCd()).isEqualTo("REGULAR");
        assertThat(row.sftyChckRsltCd()).isEqualTo("B");
        assertThat(row.rmrkCn()).isNull();
        assertThat(row.pkltNm()).isEqualTo("테스트주차장");
        assertThat(row.pkltAddr()).isEqualTo("테스트로 1");
    }

    @Test
    @DisplayName("pklt_id 컬럼이 없다고 감지됐으면 응답에 그 필드가 없어도 통과 · pkltId=null")
    void withoutPkltIdColumn() {
        PkltSftyChckOriginRow row = PkltSftyChckOriginRow.from(node(false), false);

        assertThat(row.pkltId()).isNull();
    }

    @Test
    @DisplayName("pklt_id 값이 NULL 이면 null (컬럼은 있음)")
    void nullPkltIdValue() {
        ObjectNode n = node(true);
        n.putNull("pklt_id");

        assertThat(PkltSftyChckOriginRow.from(n, true).pkltId()).isNull();
    }

    @Test
    @DisplayName("pklt_id 컬럼이 있다고 감지됐는데 행에 필드가 없으면 예외")
    void missingPkltIdWhenDetected() {
        assertThatThrownBy(() -> PkltSftyChckOriginRow.from(node(false), true))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("pklt_id");
    }

    @Test
    @DisplayName("필수 필드가 응답에 없으면 예외 — 스킵이 아니라 FAILED")
    void missingRequiredField() {
        ObjectNode n = node(true);
        n.remove("chck_ymd");

        assertThatThrownBy(() -> PkltSftyChckOriginRow.from(n, true))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("누락 컬럼")
                .hasMessageContaining("chck_ymd");
    }

    @Test
    @DisplayName("키 NULL · 키가 숫자가 아니면 예외")
    void invalidKey() {
        ObjectNode nullKey = node(true);
        nullKey.putNull("sfty_chck_id");
        ObjectNode textKey = node(true);
        textKey.put("sfty_chck_id", "abc");

        assertThatThrownBy(() -> PkltSftyChckOriginRow.from(nullKey, true)).isInstanceOf(DhubApiException.class);
        assertThatThrownBy(() -> PkltSftyChckOriginRow.from(textKey, true))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("sfty_chck_id");
    }

    @Test
    @DisplayName("chck_ymd NULL 은 통과(Processor 가 YMD_INVALID), 소수 표기는 예외")
    void chckYmdShapes() {
        ObjectNode nullYmd = node(true);
        nullYmd.putNull("chck_ymd");
        ObjectNode decimalYmd = node(true);
        decimalYmd.put("chck_ymd", 20250301.5);

        assertThat(PkltSftyChckOriginRow.from(nullYmd, true).chckYmd()).isNull();
        assertThatThrownBy(() -> PkltSftyChckOriginRow.from(decimalYmd, true))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("chck_ymd");
    }

    @Test
    @DisplayName("행이 객체가 아니면 예외")
    void notObject() {
        assertThatThrownBy(() -> PkltSftyChckOriginRow.from(MAPPER.nullNode(), false))
                .isInstanceOf(DhubApiException.class);
    }

    /** 테스트용 가공값(실데이터 아님). */
    private static ObjectNode node(boolean withPkltId) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("sfty_chck_id", 12L);
        if (withPkltId) {
            n.put("pklt_id", 345L);
        }
        n.put("chck_ymd", 20250301L);
        n.put("chck_prd_cd", "H1");
        n.put("sfty_chck_type_cd", "REGULAR");
        n.put("sfty_chck_rslt_cd", "B");
        n.putNull("rmrk_cn");
        n.put("pklt_nm", "테스트주차장");
        n.put("pklt_addr", "테스트로 1");
        return n;
    }
}
