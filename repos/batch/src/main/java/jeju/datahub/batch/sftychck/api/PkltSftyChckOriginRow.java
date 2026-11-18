package jeju.datahub.batch.sftychck.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;

/**
 * 주차장 안전점검 원천 1행 — {@code POST /api/v1/analytics/query} 응답 행의 매핑 record.
 *
 * <p>원천 필드 목록(실측):
 * <pre>{@code
 * sfty_chck_id       int64   keyset 키 — 적재하지 않는다(D8). 스킵 기록 trgt_id
 * pklt_id            int64   🔴 없을 수 있다 — 잡 시작 시 컬럼 감지(D2 ① 키 경로 입력)
 * chck_ymd           int64   YYYYMMDD — 문자열로만 옮긴다. 8자리·날짜 검사는 Processor(YMD_INVALID)
 * chck_prd_cd        utf8    NULL 이면 Processor 가 점검월로 유도
 * sfty_chck_type_cd  utf8
 * sfty_chck_rslt_cd  utf8
 * rmrk_cn            utf8    nullable
 * pklt_nm, pklt_addr utf8    D2 ② 이름·주소 매칭 입력 — 적재하지 않는다
 * rgtr_id, reg_dt            🔴 SELECT 하지 않는다(D6·D8 — 우리가 BATCH·now() 로 쓴다)
 * }</pre>
 *
 * <h2>값 검증의 경계</h2>
 * 이 record 는 <b>모양</b>만 본다 — 필드가 응답에 없거나(원천 스키마 변경), 키·숫자 필드가 숫자로 읽히지
 * 않으면 {@link DhubApiException} 이다. 🔴 <b>스킵 대상이 아니다</b> — 전량에 걸리는 문제라 Step FAILED 가
 * 맞다. 값이 {@code null} 인 것은(키 제외) 그대로 통과시키고, 코드 허용 목록·일자 유효성 판정은 Processor 가
 * 사유와 함께 스킵한다.
 *
 * <h2>🔒 로깅</h2>
 * 예외 메시지에 값은 싣지 않는다 — 필드명까지만.
 */
public record PkltSftyChckOriginRow(
        long sftyChckId,        // sfty_chck_id       (keyset 키 · 스킵 trgt_id)
        Long pkltId,            // pklt_id            (nullable — 컬럼이 없거나 값이 없으면 null)
        String chckYmd,         // chck_ymd           (int64 → 문자열. 검증은 Processor)
        String chckPrdCd,       // chck_prd_cd        (nullable — Processor 가 점검월로 유도)
        String sftyChckTypeCd,  // sfty_chck_type_cd
        String sftyChckRsltCd,  // sfty_chck_rslt_cd
        String rmrkCn,          // rmrk_cn            (nullable)
        String pkltNm,          // pklt_nm            (매칭 입력)
        String pkltAddr, String srcJson) {
    public PkltSftyChckOriginRow(long id, Long parkingId, String date, String period, String type,
            String result, String remarks, String name, String address) {
        this(id, parkingId, date, period, type, result, remarks, name, address, null);
    }      // pklt_addr          (매칭 입력)

    /** keyset 키 컬럼. */
    public static final String KEY_COLUMN = "sfty_chck_id";

    /**
     * 🔴 반드시 있어야 하는 원천 컬럼 — 하나라도 없으면 원천 스키마 변경이라 Step FAILED 다.
     * 수집 SELECT 목록의 정본이기도 하다({@code rgtr_id}·{@code reg_dt} 는 일부러 뺐다 — D8).
     */
    public static final List<String> REQUIRED_COLUMNS = List.of(
            KEY_COLUMN, "chck_ymd", "chck_prd_cd", "sfty_chck_type_cd", "sfty_chck_rslt_cd",
            "rmrk_cn", "pklt_nm", "pklt_addr");

    /** 있을 수도 없을 수도 있는 원천 컬럼(D2). 있으면 SELECT 에 넣고 키 경로로 쓴다. */
    public static final String OPTIONAL_KEY_COLUMN = "pklt_id";

    /**
     * 컬럼 이름 집합에 {@link #REQUIRED_COLUMNS} 가 모두 있는지 본다 — 컬럼 감지(잡 시작)와 행 매핑이 함께 쓴다.
     *
     * @throws DhubApiException 하나라도 없을 때(원천 스키마 변경)
     */
    public static void requireSourceColumns(Collection<String> columns) {
        List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !columns.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new DhubApiException("주차장 안전점검 원천 스키마 불일치 — 누락 컬럼 " + missing
                    + ", 수신 컬럼 " + new TreeSet<>(columns)
                    + ". 원천 데이터셋이 바뀌었으면 PkltSftyChckOriginRow.REQUIRED_COLUMNS 를 맞추세요.");
        }
    }

    /**
     * 응답 JSON 1행 → record.
     *
     * @param hasPkltId 잡 시작 시 감지한 {@code pklt_id} 컬럼 유무. {@code true} 면 그 필드도 응답에 있어야 한다
     * @throws DhubApiException 행이 비었거나, 필드가 없거나, 키·{@code pklt_id}·{@code chck_ymd} 가 숫자로 읽히지 않을 때
     */
    public static PkltSftyChckOriginRow from(JsonNode node, boolean hasPkltId) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw new DhubApiException("주차장 안전점검 응답 행이 객체가 아닙니다.");
        }
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        requireSourceColumns(fields);
        if (hasPkltId && !fields.contains(OPTIONAL_KEY_COLUMN)) {
            throw new DhubApiException("주차장 안전점검 응답 행에 " + OPTIONAL_KEY_COLUMN
                    + " 가 없습니다 — 잡 시작 시 감지한 컬럼 구성과 다릅니다(수신 컬럼 " + new TreeSet<>(fields) + ").");
        }

        Long key = longOrNull(node, KEY_COLUMN);
        if (key == null) {
            throw new DhubApiException("주차장 안전점검 응답 행의 " + KEY_COLUMN + " 가 NULL 입니다.");
        }
        Long pkltId = hasPkltId ? longOrNull(node, OPTIONAL_KEY_COLUMN) : null;

        return new PkltSftyChckOriginRow(
                key,
                pkltId,
                integralTextOrNull(node, "chck_ymd"),
                textOrNull(node, "chck_prd_cd"),
                textOrNull(node, "sfty_chck_type_cd"),
                textOrNull(node, "sfty_chck_rslt_cd"),
                textOrNull(node, "rmrk_cn"),
                textOrNull(node, "pklt_nm"),
                textOrNull(node, "pklt_addr"), node.toString());
    }

    /** 정수 필드 — 숫자 노드(정수)·숫자 문자열을 받는다. NULL 이면 {@code null}. */
    private static Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return null;
        }
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            return v.asLong();
        }
        if (v.isTextual()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException e) {
                throw notNumeric(field, e);
            }
        }
        throw notNumeric(field, null);
    }

    /**
     * {@code chck_ymd} — int64 로 오므로 정수 표기 그대로 문자열로 옮긴다. 문자열이면 원문 그대로
     * (8자리·날짜 검사는 Processor 몫). 소수·객체처럼 정수 표기로 옮길 수 없는 노드는 예외다.
     */
    private static String integralTextOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return null;
        }
        if (v.isIntegralNumber() || v.isTextual()) {
            return v.asText();
        }
        throw notNumeric(field, null);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return null;
        }
        if (!v.isValueNode()) {
            throw new DhubApiException("주차장 안전점검 응답 행의 " + field + " 가 값이 아닙니다(nodeType="
                    + v.getNodeType() + ").");
        }
        return v.asText();
    }

    private static DhubApiException notNumeric(String field, Throwable cause) {
        // 값은 싣지 않는다 — 필드명까지만(로깅 규칙).
        String message = "주차장 안전점검 응답 행의 " + field + " 가 정수로 읽히지 않습니다 — 원천 스키마 변경 의심.";
        return cause == null ? new DhubApiException(message) : new DhubApiException(message, cause);
    }
}
