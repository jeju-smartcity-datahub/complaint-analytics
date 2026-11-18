package jeju.datahub.risknoti.service;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 위험단계 알림 이력 목록 행 VO.
 * (parking.TB_RISK_STP_NOTI_HSTRY + TB_ADMDST — MyBatis 조회 전용)
 *
 * <p><b>1행 = 판정 사건 1건</b>이다. 조회는 수신자와 무관하므로
 * {@code TB_NOTI_RCVR}·{@code TB_NOTI_RCVR_LVL}·{@code TB_NOTI_HSTRY_RCVR} 는 이 경로에 등장하지 않고,
 * <b>수신자 필드({@code rcvrId}·{@code rcvrNm} 등)도 없다.</b>
 *
 * <p><b>코드명은 내리지 않는다</b> — 조회 SQL 이 {@code h}+{@code a} 2테이블 고정이라
 * {@code TB_COM_CD} 조인이 없다. 단계 라벨(심각/경고/주의)은 프론트가 코드값에서 만든다.
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은
 * {@code mybatis.configuration.map-underscore-to-camel-case=true} 로 자동 처리.
 */
@Schema(description = "위험단계 알림 이력 행 (판정 사건 1건)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RiskAlertVO {

    /** 알림아이디 (noti_id, BIGINT identity) */
    @Schema(description = "알림아이디", example = "4")
    private Long notiId;

    /** 위험단계 코드 (risk_lvl_cd — CRIT | WARN | CAUT). 단계명은 프론트가 만든다 */
    @Schema(description = "위험단계 코드", example = "WARN")
    private String riskLvlCd;

    /** 시군구명 — 이력에 컬럼이 없어 {@code TB_ADMDST} 조인으로 얻는다 */
    @Schema(description = "시군구명", example = "제주시")
    private String sggNm;

    /** 읍면동코드 (emd_cd, NOT NULL — 판정은 언제나 읍면동 단위다) */
    @Schema(description = "읍면동코드", example = "50110104")
    private String emdCd;

    /** 읍면동명 (TB_ADMDST.emd_nm) */
    @Schema(description = "읍면동명", example = "이도이동")
    private String emdNm;

    /**
     * 지역 표시명 — <b>항상 읍면동명</b>이다.
     * 보고서의 3분기 CASE 와 달리 여기엔 분기가 없다 — '지역 = 전체' 인 이력 행이 존재하지 않는다.
     */
    @Schema(description = "지역 표시명 (항상 읍면동명)", example = "이도이동")
    private String regionNm;

    /**
     * 알림 내용 (noti_ctt) — <b>판정 시점에 저장된 문구를 그대로 내린다</b>.
     * 조회에서 재조립하지 않는다 (형식을 바꿔도 과거 알림 문구가 소급 변경되지 않게).
     */
    @Schema(description = "알림 내용 (판정 시점 저장 문구)", example = "이도이동 민원 21% 증가")
    private String notiCtt;

    /**
     * 증가율 (incrs_rt, %) — 전일 대비 민원 증가율.
     * {@code (당일건수 − GREATEST(전일건수,1)) / GREATEST(전일건수,1) × 100}.
     */
    @Schema(description = "전일 대비 증가율 (%)", example = "21.0")
    private Double incrsRt;

    /**
     * 판정 당시 기준 증가율 (incrs_rt_thrsh, % — 스냅샷).
     * 기준({@code TB_RISK_STP_NOTI_CRTR})을 바꿔도 과거 판정 근거가 남는다.
     */
    @Schema(description = "판정 당시 기준 증가율 (%)", example = "20.0")
    private Double incrsRtThrsh;

    /** 민원 건수 (cvlcpt_cnt) — 판정 대상 읍면동의 <b>당일</b> 민원 건수(보정 전 실제 값) */
    @Schema(description = "민원 건수 (당일)", example = "22")
    private Integer cvlcptCnt;

    /** 발생일시 (ocrn_dt) */
    @Schema(description = "발생일시", example = "2026-05-28T11:47:00")
    private LocalDateTime ocrnDt;

    /**
     * 처리상태 코드 (prcs_stts_cd). <b>이번 범위에서는 {@code OPEN} 고정</b>이다 —
     * 실질 상태는 {@code prslYn}·{@code idntyYn} 이 담당한다.
     */
    @Schema(description = "처리상태 코드 (이번 범위 OPEN 고정)", example = "OPEN")
    private String prcsSttsCd;

    // --- 읽음 · 확인 -----------------------------------------------------------------
    // 알림 1건당 상태 1개다(조회가 수신자와 무관해 '수신자별 읽음' 개념이 없다).
    // 읽음은 팝오버 행 클릭으로 갱신된다 — PATCH /api/risk-alerts/{notiId}/read.
    // ⚠️ 확인(idntyYn)은 아직 갱신 API 가 없다 — 읽어서 보여주기만 한다.

    /** 읽음여부 (prsl_yn — Y | N) */
    @Schema(description = "읽음여부", example = "Y")
    private String prslYn;

    /** 읽은일시 (prsl_dt). 미읽음이면 null */
    @Schema(description = "읽은일시", example = "2026-05-28T13:47:00")
    private LocalDateTime prslDt;

    /** 확인여부 (idnty_yn — Y | N). 확인은 읽음을 전제한다(DB CHECK 제약) */
    @Schema(description = "확인여부", example = "Y")
    private String idntyYn;

    /** 확인일시 (idnty_dt). 미확인이면 null */
    @Schema(description = "확인일시", example = "2026-05-28T14:47:00")
    private LocalDateTime idntyDt;
}
