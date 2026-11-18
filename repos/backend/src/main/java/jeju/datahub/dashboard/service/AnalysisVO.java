package jeju.datahub.dashboard.service;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 대시보드 "최근 분석 실행" 카드용 조회 전용 VO.
 * (parking.TB_ANLS_EXCN + parking.TB_COM_CD JOIN — MyBatis 전용)
 *
 * <p>코드→명 변환(anlsTypeNm·excnSttsNm)은 SQL JOIN(TB_COM_CD)에서 처리한다.
 * 컬럼(스네이크) → 필드(카멜) 매핑은 map-underscore-to-camel-case=true 로 자동 처리.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AnalysisVO {

    /** 분석 실행 ID (excn_id, BIGINT) */
    private Long excnId;

    /** 분석유형코드 (anls_type_cd) */
    private String anlsTypeCd;

    /** 분석유형명 (TB_COM_CD.cd_nm, group_cd=ANLS_TYPE_CD) */
    private String anlsTypeNm;

    /** 실행상태코드 (excn_stts_cd) */
    private String excnSttsCd;

    /** 실행상태명 (TB_COM_CD.cd_nm, group_cd=EXCN_STTS_CD — 대기/실행중/완료/실패) */
    private String excnSttsNm;

    /** 실행 일시 (excn_dt, TIMESTAMP) */
    private LocalDateTime excnDt;

    /** 읍면동코드 (emd_cd) */
    private String emdCd;

    // ── 정책 시뮬레이션(POLICY_SIM) 행 제목 재료 ──────────────────────
    // 다른 분석유형·재료 없음은 전부 null. 숫자 칸도 String 이다 — anls_param_json 을 `->>`(text)로
    // 꺼내 그대로 내린다(캐스팅 실패로 조회가 죽지 않게). 해석·조합은 프론트가 한다.

    /**
     * 결과아이디 (TB_PLCY_SMLT_RSLT.rslt_id) — 대시보드 카드 행을 누르면 정책 시뮬레이션 화면이
     * 이 값으로 시나리오 비교 목록의 그 행을 선택한다. POLICY_SIM 이 아니면 null.
     */
    private Long rsltId;

    /** 분석범위내용 (TB_PLCY_SMLT_RSLT.anls_rng_cn — "제주시 연동" · "제주시 전체" · "전체 지역") */
    private String anlsRngCn;

    /** 정책유형 (anls_param_json.plcyType — EXPAND / REPAIR / FEE) */
    private String plcyType;

    /** 확충 방식 (anls_param_json.expandMethod — VACLOT / MULTFLR / NEW) */
    private String expandMethod;

    /** 추가 주차면수 (anls_param_json.addPkltSlot, 문자열) */
    private String addPkltSlot;

    /** 도로정비유형 (anls_param_json.roadMtncType — RSDNC / CMRC) */
    private String roadMtncType;

    /** 도로정비연장 m (anls_param_json.roadMtncLen, 문자열) */
    private String roadMtncLen;

    /** 요금적용방법 (anls_param_json.feeAplcnMthd — RAISE / CONVERT) */
    private String feeAplcnMthd;

    /** 요금인상율 % (anls_param_json.feeIncrsRt, 문자열) */
    private String feeIncrsRt;
}
