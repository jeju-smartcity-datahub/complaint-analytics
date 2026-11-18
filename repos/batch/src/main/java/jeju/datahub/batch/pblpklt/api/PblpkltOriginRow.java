package jeju.datahub.batch.pblpklt.api;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;

/**
 * pblpklt 응답 1행을 담는 record — Reader 가 내고 Processor 가 받는다.
 *
 * <p>이 record 는 DB 에 <b>그대로 적재되지 않는다</b>. staging 테이블({@code tb_pblpklt_origin})은
 * 2026-08-24 폐지됐고, Processor 가 주소·행정동·유형을 가공해 {@code PblpkltRecord} 로 바꾼 뒤
 * 서비스 테이블 {@code parking.tb_pblpklt} 에 직접 적재한다.
 *
 * <p>응답 스키마(2026-09-11 실측, 데이터셋 {@code dataset.95c563b4-…} · 2,272행 · 34필드):
 * <pre>{@code
 * {
 *   "pklt_id": "1916",               "pklt_nm": "대정상모 공영노외1 주차장",
 *   "emd_cd": null, "lat": null, "lot": null,
 *   "pklt_cpcty": 37,                "pklt_type_cd": null,
 *   "extsh_dt": null,                "orgnl_id": null,
 *   "pklt_addr": "상모리 3964-1",    "orgnl_sn": 131,
 *   "emd_nm": "대정읍",              "pklt_type_nm": "노외공영",
 *   "pkltar": "1073.000000000",      "prk_crg_yn": "Y",
 *   "instl_ym": "202510",            "instl_ymd": null,
 *   "prk_crg_swtc_ym": "202602",     "rttrt": "1.46",
 *   "day_avg_vhcl_cnt": 54,          "incm_amt": 2118400,
 *   "expnd_amt": null,               "mng_nope": null,
 *   "mng_dept_nm": "교통행정과",     "pklt_mthd_nm": null,
 *   "ev_chrgr_cnt": null,            "prk_crg_cn": null,
 *   "ownshp_type_nm": null,          "prk_slot_grd": null,
 *   "obstcl_cn": null,               "obstcl_slot_cnt": null,
 *   "trfc_sfty_sign_instl_yn": null, "stpr_instl_yn": null,
 *   "rmrk_cn": null
 * }
 * }</pre>
 *
 * <h2>필드 구성 — 원천 34 − {@code extsh_dt} = 33</h2>
 * 앞 7필드는 종전 그대로(다른 클래스가 accessor 로 쓴다), 뒤 26필드가 2026-09-10 확장분이다.
 * {@code extsh_dt} 는 <b>읽지 않는다</b> — 소멸 판정은 {@code PblpkltSoftDeleteMarkingTasklet} 몫이라
 * 원천 소멸 시각을 받으면 판정이 두 갈래가 된다.
 *
 * <h2>숫자 파싱</h2>
 * <ul>
 *   <li>면적·회전율·금액({@code pkltar}·{@code rttrt}·{@code incm_amt}·{@code expnd_amt})은
 *       {@link BigDecimal} 로 받는다. {@code Double} 을 거치면 {@code NUMERIC} 에 반올림 오차가 실린다.
 *       좌표({@code lat}/{@code lot})만 종전대로 {@code Double}.</li>
 *   <li>원천은 같은 필드를 스냅샷마다 숫자로도 문자열로도 준다({@code pkltar}·{@code rttrt} 는 이미
 *       문자열이다). 그래서 숫자 헬퍼는 <b>JSON 숫자와 숫자형 문자열을 모두</b> 받는다.
 *       빈 문자열·공백은 {@code null}.</li>
 *   <li>🔴 <b>경로마다 표기가 다르다</b>(2026-09-17 전량 2,272행 실측) — 테이블 덤프는
 *       {@code pkltar "360.000000000"}·{@code rttrt "1.46"}(문자열), {@code analytics/query} 는 {@code 360}·{@code 1.46}
 *       (JSON 숫자)이다. 나머지 31필드는 타입·값이 전 행 동일. 두 표기는 {@link BigDecimal} 스케일만 다르고
 *       ({@code 360} vs {@code 360.000000000}) 목적지 {@code NUMERIC(19,9)}·{@code NUMERIC(5,2)} 가 스케일을
 *       고정하므로 <b>적재값이 같다</b>. 해시·비교키에 쓰이지 않아 단속 {@code ffnlg} 같은 정규화는 두지 않는다.
 *       ⚠️ JSON 숫자는 {@code double} 을 거친다 — 현 데이터 최대 유효자릿수 6(pkltar)·3(rttrt)이라 손실 없음.</li>
 *   <li>숫자로 읽을 수 없는 값(정수 필드에 소수 포함)은 <b>행을 죽이지 않고 그 값만 {@code null}</b>
 *       로 두고 WARN 을 남긴다 — 선택 컬럼 하나 때문에 주차장 1행을 잃지 않는다.</li>
 * </ul>
 *
 * <h2>🔒 로깅</h2>
 * 좌표·이름 같은 값을 로그에 그대로 남기지 않는다. 파싱 실패 WARN 도 <b>필드명과 JSON 노드 타입만</b>
 * 남긴다(값 원문 없음).
 *
 * <h2>생성 경로</h2>
 * 필드가 33개라 위치 인자 호출은 같은 타입이 연달아 있어 한 칸 밀려도 컴파일된다. 그래서 정식
 * 생성자 호출은 {@link Builder#build()} 한 곳에만 두고, 프로덕션은 {@link #fromApi(JsonNode)},
 * 테스트·픽스처는 {@link #builder()} 로 만든다.
 */
public record PblpkltOriginRow(
        String pkltId,             // pklt_id — 원천 식별자("1"~"2272"). 필수
        String pkltNm,             // pklt_nm (65% null)
        Long emdCd,                // emd_cd (원천 100% null — 공간매칭 파생)
        Double lat,                // lat    (원천 100% null — 지오코딩 파생)
        Double lot,                // lot    (원천 100% null — 지오코딩 파생)
        Integer pkltCpcty,         // pklt_cpcty
        String pkltTypeCd,         // pklt_type_cd (원천 100% null — pkltTypeNm 파생)
        // ── 2026-09-10 확장 ──
        String orgnlId,            // orgnl_id
        Integer orgnlSn,           // orgnl_sn (유일하지 않다 — 키로 쓰지 않는다)
        String emdNm,              // emd_nm
        // ── 2026-09-29 확장 (신규 데이터셋 test01.tb_pblpklt 부터 제공) ──
        String sggNm,              // sgg_nm — 🔴 지오코딩 접두용. 원천 100% 채움(제주시/서귀포시)
        String pkltTypeNm,         // pklt_type_nm
        BigDecimal pkltar,         // pkltar — 문자열로 온다("331.000000000")
        String prkCrgYn,           // prk_crg_yn (Y/N)
        String prkCrgCn,           // prk_crg_cn
        String prkCrgSwtcYm,       // prk_crg_swtc_ym (YYYYMM)
        String instlYm,            // instl_ym (YYYYMM)
        String instlYmd,           // instl_ymd (YYYYMMDD)
        BigDecimal rttrt,          // rttrt — 문자열로 온다("1.59")
        Integer dayAvgVhclCnt,     // day_avg_vhcl_cnt
        BigDecimal incmAmt,        // incm_amt
        BigDecimal expndAmt,       // expnd_amt (원천 100% null — 타입 미상)
        Integer mngNope,           // mng_nope
        String mngDeptNm,          // mng_dept_nm
        String pkltMthdNm,         // pklt_mthd_nm (포장유형·주차배치 두 의미 혼재 — 원문 그대로)
        Integer evChrgrCnt,        // ev_chrgr_cnt
        String ownshpTypeNm,       // ownshp_type_nm
        String prkSlotGrd,         // prk_slot_grd (A~D)
        String obstclCn,           // obstcl_cn
        Integer obstclSlotCnt,     // obstcl_slot_cnt
        String trfcSftySignInstlYn, // trfc_sfty_sign_instl_yn
        String stprInstlYn,        // stpr_instl_yn
        String rmrkCn,             // rmrk_cn
        String pkltAddr) {         // pklt_addr — 원천이 준 주소

    private static final Logger log = LoggerFactory.getLogger(PblpkltOriginRow.class);

    /**
     * 원천 컬럼 목록 — {@link #fromApi(JsonNode)} 가 읽는 필드 이름의 <b>단일 출처</b>(34개, {@code extsh_dt} 제외).
     * 🔁 <b>2026-09-29 — {@code sgg_nm} 추가로 33 → 34</b>. 신규 데이터셋
     * ({@code test01.tb_pblpklt})부터 제공되며, 🔴 <b>옛 데이터셋에는 이 컬럼이 없어 SELECT 가 실패한다</b> —
     * {@code dhub.pblpklt-table-id} 와 {@code pblpklt-source-table} 을 반드시 짝으로 바꿔야 한다.
     * 수집 SQL 의 SELECT 목록이 이것으로 만들어진다({@code DhubPblpkltApiClient}, 🔁 2026-09-17).
     *
     * <p>🔴 {@code fromApi} 에 필드를 추가하면 <b>여기에도 함께</b> 넣는다 — 빠지면 SELECT 에서 빠져 그 컬럼이
     * 전 행 {@code null} 로 <b>조용히</b> 적재된다. 일치는 {@code PblpkltOriginRowTest} 가 레코드 컴포넌트 기준으로 고정한다.
     */
    public static final List<String> SOURCE_COLUMNS = List.of(
            "pklt_id", "pklt_nm", "emd_cd", "lat", "lot", "pklt_cpcty", "pklt_type_cd",
            "orgnl_id", "orgnl_sn", "emd_nm", "sgg_nm", "pklt_type_nm", "pkltar", "prk_crg_yn", "prk_crg_cn",
            "prk_crg_swtc_ym", "instl_ym", "instl_ymd", "rttrt", "day_avg_vhcl_cnt", "incm_amt", "expnd_amt",
            "mng_nope", "mng_dept_nm", "pklt_mthd_nm", "ev_chrgr_cnt", "ownshp_type_nm", "prk_slot_grd",
            "obstcl_cn", "obstcl_slot_cnt", "trfc_sfty_sign_instl_yn", "stpr_instl_yn", "rmrk_cn", "pklt_addr");

    /**
     * 응답 JSON 1행 → record 매핑. {@code pklt_id} 가 비면 {@link DhubApiException}.
     *
     * <p>{@code pklt_id} 만 필수다. 나머지는 전부 결측 허용 — 원천이 대부분 null 을 보내고,
     * NOT NULL 컬럼({@code pklt_nm}·{@code emd_cd}·{@code lat}/{@code lot})의 채움은 Processor 몫이다.
     * {@code extsh_dt} 는 매핑하지 않는다(클래스 주석).
     */
    public static PblpkltOriginRow fromApi(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new DhubApiException("pblpklt 응답 행이 비어 있습니다.");
        }
        return builder()
                .pkltId(requireText(node, "pklt_id"))
                .pkltNm(textOrNull(node, "pklt_nm"))
                .emdCd(longOrNull(node, "emd_cd"))
                .lat(doubleOrNull(node, "lat"))
                .lot(doubleOrNull(node, "lot"))
                .pkltCpcty(intOrNull(node, "pklt_cpcty"))
                .pkltTypeCd(textOrNull(node, "pklt_type_cd"))
                .orgnlId(textOrNull(node, "orgnl_id"))
                .orgnlSn(intOrNull(node, "orgnl_sn"))
                .emdNm(textOrNull(node, "emd_nm"))
                .sggNm(textOrNull(node, "sgg_nm"))
                .pkltTypeNm(textOrNull(node, "pklt_type_nm"))
                .pkltar(decimalOrNull(node, "pkltar"))
                .prkCrgYn(textOrNull(node, "prk_crg_yn"))
                .prkCrgCn(textOrNull(node, "prk_crg_cn"))
                .prkCrgSwtcYm(textOrNull(node, "prk_crg_swtc_ym"))
                .instlYm(textOrNull(node, "instl_ym"))
                .instlYmd(textOrNull(node, "instl_ymd"))
                .rttrt(decimalOrNull(node, "rttrt"))
                .dayAvgVhclCnt(intOrNull(node, "day_avg_vhcl_cnt"))
                .incmAmt(decimalOrNull(node, "incm_amt"))
                .expndAmt(decimalOrNull(node, "expnd_amt"))
                .mngNope(intOrNull(node, "mng_nope"))
                .mngDeptNm(textOrNull(node, "mng_dept_nm"))
                .pkltMthdNm(textOrNull(node, "pklt_mthd_nm"))
                .evChrgrCnt(intOrNull(node, "ev_chrgr_cnt"))
                .ownshpTypeNm(textOrNull(node, "ownshp_type_nm"))
                .prkSlotGrd(textOrNull(node, "prk_slot_grd"))
                .obstclCn(textOrNull(node, "obstcl_cn"))
                .obstclSlotCnt(intOrNull(node, "obstcl_slot_cnt"))
                .trfcSftySignInstlYn(textOrNull(node, "trfc_sfty_sign_instl_yn"))
                .stprInstlYn(textOrNull(node, "stpr_instl_yn"))
                .rmrkCn(textOrNull(node, "rmrk_cn"))
                .pkltAddr(textOrNull(node, "pklt_addr"))
                .build();
    }

    /**
     * 이름 붙은 setter 로 만드는 빌더. 테스트·픽스처는 이것을 쓴다(33개 위치 인자 호출 금지).
     * 설정하지 않은 필드는 {@code null}.
     */
    public static Builder builder() {
        return new Builder();
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            throw new DhubApiException("pblpklt 응답에 필수 필드가 없습니다: " + field);
        }
        return v.asText();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        return v.asText();
    }

    /**
     * 숫자 필드 공통 파서 — JSON 숫자와 숫자형 문자열을 모두 받는다.
     * 결측·{@code null}·빈 문자열·공백은 {@code null}. 숫자로 읽을 수 없으면 WARN 후 {@code null}.
     */
    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isTextual()) {
            String raw = v.asText().strip();
            if (raw.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(raw);
            } catch (NumberFormatException e) {
                warnUnparsable(field, v);
                return null;
            }
        }
        warnUnparsable(field, v);   // boolean·object·array
        return null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        BigDecimal d = decimalOrNull(node, field);
        if (d == null) {
            return null;
        }
        try {
            return d.intValueExact();   // 소수부가 있거나 int 범위를 넘으면 절삭하지 않고 null
        } catch (ArithmeticException e) {
            warnUnparsable(field, node.path(field));
            return null;
        }
    }

    private static Long longOrNull(JsonNode node, String field) {
        BigDecimal d = decimalOrNull(node, field);
        if (d == null) {
            return null;
        }
        try {
            return d.longValueExact();
        } catch (ArithmeticException e) {
            warnUnparsable(field, node.path(field));
            return null;
        }
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        BigDecimal d = decimalOrNull(node, field);
        return d == null ? null : d.doubleValue();
    }

    /** 🔒 값 원문은 남기지 않는다 — 필드명과 노드 타입만. */
    private static void warnUnparsable(String field, JsonNode v) {
        log.warn("pblpklt 응답 숫자 필드를 해석하지 못해 null 로 둔다: field={}, nodeType={}",
                field, v.getNodeType());
    }

    /** {@link #builder()} 참고. 정식 생성자 호출은 {@link #build()} 한 곳뿐이다. */
    public static final class Builder {
        private String pkltId;
        private String pkltNm;
        private Long emdCd;
        private Double lat;
        private Double lot;
        private Integer pkltCpcty;
        private String pkltTypeCd;
        private String orgnlId;
        private Integer orgnlSn;
        private String emdNm;
        private String sggNm;
        private String pkltTypeNm;
        private BigDecimal pkltar;
        private String prkCrgYn;
        private String prkCrgCn;
        private String prkCrgSwtcYm;
        private String instlYm;
        private String instlYmd;
        private BigDecimal rttrt;
        private Integer dayAvgVhclCnt;
        private BigDecimal incmAmt;
        private BigDecimal expndAmt;
        private Integer mngNope;
        private String mngDeptNm;
        private String pkltMthdNm;
        private Integer evChrgrCnt;
        private String ownshpTypeNm;
        private String prkSlotGrd;
        private String obstclCn;
        private Integer obstclSlotCnt;
        private String trfcSftySignInstlYn;
        private String stprInstlYn;
        private String rmrkCn;
        private String pkltAddr;

        private Builder() {
        }

        public Builder pkltId(String v) { this.pkltId = v; return this; }
        public Builder pkltNm(String v) { this.pkltNm = v; return this; }
        public Builder emdCd(Long v) { this.emdCd = v; return this; }
        public Builder lat(Double v) { this.lat = v; return this; }
        public Builder lot(Double v) { this.lot = v; return this; }
        public Builder pkltCpcty(Integer v) { this.pkltCpcty = v; return this; }
        public Builder pkltTypeCd(String v) { this.pkltTypeCd = v; return this; }
        public Builder orgnlId(String v) { this.orgnlId = v; return this; }
        public Builder orgnlSn(Integer v) { this.orgnlSn = v; return this; }
        public Builder emdNm(String v) { this.emdNm = v; return this; }
        public Builder sggNm(String v) { this.sggNm = v; return this; }
        public Builder pkltTypeNm(String v) { this.pkltTypeNm = v; return this; }
        public Builder pkltar(BigDecimal v) { this.pkltar = v; return this; }
        public Builder prkCrgYn(String v) { this.prkCrgYn = v; return this; }
        public Builder prkCrgCn(String v) { this.prkCrgCn = v; return this; }
        public Builder prkCrgSwtcYm(String v) { this.prkCrgSwtcYm = v; return this; }
        public Builder instlYm(String v) { this.instlYm = v; return this; }
        public Builder instlYmd(String v) { this.instlYmd = v; return this; }
        public Builder rttrt(BigDecimal v) { this.rttrt = v; return this; }
        public Builder dayAvgVhclCnt(Integer v) { this.dayAvgVhclCnt = v; return this; }
        public Builder incmAmt(BigDecimal v) { this.incmAmt = v; return this; }
        public Builder expndAmt(BigDecimal v) { this.expndAmt = v; return this; }
        public Builder mngNope(Integer v) { this.mngNope = v; return this; }
        public Builder mngDeptNm(String v) { this.mngDeptNm = v; return this; }
        public Builder pkltMthdNm(String v) { this.pkltMthdNm = v; return this; }
        public Builder evChrgrCnt(Integer v) { this.evChrgrCnt = v; return this; }
        public Builder ownshpTypeNm(String v) { this.ownshpTypeNm = v; return this; }
        public Builder prkSlotGrd(String v) { this.prkSlotGrd = v; return this; }
        public Builder obstclCn(String v) { this.obstclCn = v; return this; }
        public Builder obstclSlotCnt(Integer v) { this.obstclSlotCnt = v; return this; }
        public Builder trfcSftySignInstlYn(String v) { this.trfcSftySignInstlYn = v; return this; }
        public Builder stprInstlYn(String v) { this.stprInstlYn = v; return this; }
        public Builder rmrkCn(String v) { this.rmrkCn = v; return this; }
        public Builder pkltAddr(String v) { this.pkltAddr = v; return this; }

        public PblpkltOriginRow build() {
            return new PblpkltOriginRow(
                    pkltId, pkltNm, emdCd, lat, lot,
                    pkltCpcty, pkltTypeCd,
                    orgnlId, orgnlSn, emdNm, sggNm, pkltTypeNm,
                    pkltar, prkCrgYn, prkCrgCn, prkCrgSwtcYm,
                    instlYm, instlYmd, rttrt, dayAvgVhclCnt,
                    incmAmt, expndAmt, mngNope, mngDeptNm,
                    pkltMthdNm, evChrgrCnt, ownshpTypeNm, prkSlotGrd,
                    obstclCn, obstclSlotCnt, trfcSftySignInstlYn,
                    stprInstlYn, rmrkCn, pkltAddr);
        }
    }
}
