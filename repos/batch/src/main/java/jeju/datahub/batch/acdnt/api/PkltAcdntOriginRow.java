package jeju.datahub.batch.acdnt.api;

import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jeju.datahub.batch.collect.SourceTimestamp;

/** D-Hub 파이프라인의 9컬럼. 내부 acdnt_id/pklt_id와 load_dt를 받지 않는다. */
public record PkltAcdntOriginRow(String originPk, String pkltNm, String pkltAddr,
        LocalDateTime acdntDt, String svrtCd, String acdntType, String prcsCn,
        String prcsSttsNm, String srcJson) {
    public static final List<String> COLUMNS = List.of("origin_pk", "pklt_nm", "pklt_addr", "acdnt_dt",
            "svrt_cd", "acdnt_type", "prcs_cn", "prcs_stts_nm", "src_json");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static PkltAcdntOriginRow from(JsonNode row) {
        for (String column : COLUMNS) {
            if (!row.has(column)) throw new IllegalArgumentException("사고이력 원천 컬럼 누락: " + column);
        }
        LocalDateTime date = SourceTimestamp.parse(value(row, "acdnt_dt", 100));
        if (date == null) throw new IllegalArgumentException("사고일시 acdnt_dt는 필수입니다.");
        String name = value(row, "pklt_nm", 300);
        String address = value(row, "pklt_addr", 200);
        if (name == null && address == null) throw new IllegalArgumentException("사고의 주차장명 또는 주소가 필요합니다.");
        String source = value(row, "src_json", Integer.MAX_VALUE);
        if (source != null) {
            try {
                if (!JSON.readTree(source).isObject()) throw new IllegalArgumentException("src_json은 JSON 객체여야 합니다.");
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("사고이력 src_json이 올바른 JSON이 아닙니다.", e);
            }
        }
        return new PkltAcdntOriginRow(value(row, "origin_pk", Integer.MAX_VALUE), name, address, date,
                value(row, "svrt_cd", 30), value(row, "acdnt_type", 100), value(row, "prcs_cn", 4000),
                value(row, "prcs_stts_nm", 300), source);
    }

    private static String value(JsonNode row, String column, int limit) {
        JsonNode node = row.get(column);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException("사고이력 문자열 컬럼의 타입 오류: " + column);
        String value = node.textValue();
        if (value.codePointCount(0, value.length()) > limit)
            throw new IllegalArgumentException("사고이력 컬럼 길이 초과: " + column);
        return value.isBlank() ? null : value;
    }
}
