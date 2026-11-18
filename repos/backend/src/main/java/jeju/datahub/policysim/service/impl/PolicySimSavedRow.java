package jeju.datahub.policysim.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 내 저장 결과 목록 한 행 — 매퍼 결과 전용.
 *
 * <p>{@code TB_PLCY_SMLT_RSLT} ⨝ {@code TB_ANLS_EXCN}. 두 JSON 칸은 문자열로 받고
 * 서비스가 응답 VO({@code PolicySimSavedResultVO})로 옮긴다 — {@code aplcn_param_json} 은
 * {@code PolicySimAplcnParam} 으로 역직렬화하고 {@code cstAmt} 는 그 단가로 다시 만든다(DB 칸이 없다).
 * 요청 조건은 SQL 이 {@code anls_param_json} 에서 꺼낸다.
 */
@Getter
@Setter
@ToString
public class PolicySimSavedRow {

    private Long rsltId;
    private LocalDateTime excnDt;

    // --- 요청 조건 (anls_param_json) ---
    private String plcyType;
    private String expandMethod;
    private Integer addPkltSlot;
    private Integer predcMm;
    private String sggNm;
    private String emdCd;
    /** 계수 조정 적용 내역 원문 ({@code anls_param_json.vrblAjmt}) — 구 저장분은 null */
    private String vrblAjmtJson;

    // --- 결과 (tb_plcy_smlt_rslt) ---
    private String anlsRngCn;
    private BigDecimal crtrCvlcptCnt;
    private BigDecimal crtrIlglCnt;
    private BigDecimal crtrCngstIdx;
    private Long exsPkltCpcty;
    private BigDecimal vldAddPkltSlot;
    private BigDecimal cvlcptRdctRt;
    private BigDecimal cngstImpvRt;
    private BigDecimal aftrCngstIdx;
    private BigDecimal aftrCvlcptCnt;
    private String calcSttsCd;
    private boolean excsYn;
    private String cstLvlCd;
    private String aplcnParamJson;

    // --- 이면도로 — 확충 행은 null ---
    private String roadMtncType;
    private Integer roadMtncLen;
    private BigDecimal adjcCvlcptCnt;

    // --- 요금제 — 확충·이면도로 행은 null. adjcCvlcptCnt 는 위 4038 칸을 함께 쓴다 ---
    private String feeAplcnMthd;
    private String feeAplcnZone;
    /** NUMERIC(5,2) — 응답 VO 는 Integer(10·20·30). CONVERT 는 null */
    private BigDecimal feeIncrsRt;
    private Integer aplcnTrgtSlot;
    private BigDecimal lvngVhclCnt;
    private BigDecimal absrpCnt;
    private BigDecimal trnsfCnt;
}
