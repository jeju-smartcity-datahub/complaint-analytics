package jeju.datahub.batch.utztn.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 주차장 이용패턴 원천 한 행 — {@code `jeju_mini`.`tb_pblpklt_use_cnt`} 의 6필드 그대로다 (TSK-4189 Step 1 실측).
 *
 * <pre>
 * pklt_nm     '947 남측'          주차장명   — 🔴 매칭 2단 수단(1단은 주소다)
 * pklt_addr   '노형동 947'        주차장주소 — 🔴 매칭 1단 키
 * msmt_ym     '2025-06'          집계연월   — 하이픈 포함 7자. tot_ym(CHAR(6)) 으로 넣기 전에 제거한다
 * use_cnt     3615               이용건수   → prk_cntom(주차대수)
 * pklt_cpcty  42                 주차면수   — 적재하지 않는다(tb_pblpklt 에 이미 있다). 경고용 대조만
 * use_rate    2.869047619047619  일평균회전 → day_avg_trnvr_rt
 * </pre>
 *
 * <p>🔴 <b>원천에 주차장 키가 없다</b> — `pklt_id`·`origin_pk` 둘 다 없어서 이름·주소 문자열로 우리 행을
 * 찾아야 한다. 그 해석은 {@code PkltUtztnPkltIdResolver} 가 진다(Step 7).
 *
 * <p>🔴 <b>null·형식 오류를 삼키지 않는다.</b> 수집 Step 은 {@code faultTolerant()} 를 쓰지만 파싱 예외를
 * {@code skip()} 목록에 넣지 않으므로, 여기서 던지면 Job 이 FAILED 로 끊긴다 — 원천 스키마가 바뀐 것이라
 * 전량에 걸리기 때문이다(upsert 라 고치고 다시 돌리면 된다).
 *
 * @param pkltNm    주차장명 (원문)
 * @param pkltAddr  주차장주소 (원문)
 * @param msmtYm    집계연월 원문({@code yyyy-MM})
 * @param useCnt    이용건수
 * @param pkltCpcty 주차면수 (원천 값 — 대조용)
 * @param useRate   일평균 회전수
 */
public record PkltUtztnOriginRow(
        String pkltNm,
        String pkltAddr,
        String msmtYm,
        int useCnt,
        int pkltCpcty,
        java.math.BigDecimal useRate, String srcJson) {

    public PkltUtztnOriginRow(String name, String address, String month, int count, int capacity, java.math.BigDecimal rate) {
        this(name, address, month, count, capacity, rate, null);
    }

    /** analytics/query 가 돌려주는 행 하나를 읽는다. 필드가 비면 {@link IllegalStateException} 이다. */
    public static PkltUtztnOriginRow from(JsonNode node) {
        return new PkltUtztnOriginRow(
                optionalText(node, "pklt_nm"),
                requireText(node, "pklt_addr"),
                optionalText(node, "msmt_ym"),
                requireInt(node, "use_cnt"),
                requireInt(node, "pklt_cpcty"),
                requireDecimal(node, "use_rate"), node.toString());
    }

    /**
     * {@code '2025-06'} → {@code '202506'} — {@code parking.tb_pklt_utztn_pttn.tot_ym} 은 {@code CHAR(6)} 이다.
     *
     * <p>🔴 원문(7자)을 그대로 넣으면 <b>잘리지 않고 22001 에러</b>다(짧으면 공백 패딩된다).
     * 그래서 형식 보증은 타입이 아니라 이 검증이 진다 — {@code ^[0-9]{6}$} 가 아니면 <b>FAILED</b> 로 끊는다
     * (행 단위 스킵이 아니다 — 원천 포맷이 바뀐 것이라 전량에 걸린다).
     */
    public String totYm() {
        // 🔴 압축 결과가 아니라 **원문 형식**을 먼저 본다 — '2025-6-1' 은 압축하면 '202561' 로
        //    6자리를 통과해 버린다(조용히 엉뚱한 연월이 된다).
        if (msmtYm == null || msmtYm.isBlank()) return null;
        if (!msmtYm.matches("^[0-9]{4}-[0-9]{2}$")) {
            throw new IllegalStateException(
                    "원천 msmt_ym 이 yyyy-MM 형식이 아닙니다: '" + msmtYm
                            + "' (tot_ym 은 CHAR(6) 이라 원문을 그대로 넣으면 잘리지 않고 22001 에러다 — "
                            + "원천 포맷이 바뀐 것이므로 전량에 걸린다)");
        }
        return msmtYm.replace("-", "");
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new IllegalStateException("원천 행에 " + field + " 가 없습니다: " + node);
        }
        return v.asText().trim();
    }

    private static String optionalText(JsonNode row, String field) {
        String value = row.path(field).isNull() ? null : row.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int requireInt(JsonNode node, String field) {
        try { return new java.math.BigDecimal(node.path(field).asText()).intValueExact(); }
        catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalStateException("원천 정수 컬럼을 읽을 수 없습니다: " + field, e);
        }
    }

    private static java.math.BigDecimal requireDecimal(JsonNode node, String field) {
        try { return new java.math.BigDecimal(node.path(field).asText()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("원천 숫자 컬럼을 읽을 수 없습니다: " + field, e);
        }
    }
}
