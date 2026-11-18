package jeju.datahub.risknoti.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 위험단계 알림 기준 엔티티. (parking.TB_RISK_STP_NOTI_CRTR — 쓰기 전용)
 *
 * <p>단계당 정확히 1행이고 {@code thrsh_id} 가 {@code 'THRSH_' + risk_lvl_cd} 로 고정이라
 * ID 생성이 없다 — <b>할당형 PK</b> 다 (TSK-4028 D7·D11). 저장은 3건 일괄 UPSERT 이며
 * {@code save()} 가 {@code em.merge()} 로 가 기존 행이면 UPDATE, 없으면 INSERT 가 된다.
 *
 * <p>🔴 <b>{@code mdfr_id}·{@code mdfcn_dt} 를 반드시 매핑하고 UPSERT 마다 채운다.</b>
 * {@code 16-TB_RISK_STP_NOTI_CRTR.sql} 원문이 둘 다 {@code NOT NULL}(기본값 없음)이라
 * 매핑을 빠뜨리면 신규 INSERT 가 터지고, 매핑만 하고 값을 안 넣으면 merge 가 전 컬럼 UPDATE 를
 * 날려 기존 3행을 NULL 로 덮다가 500 이 난다. 로그인이 범위 밖이라 서비스가
 * {@code 'system'} + {@code now()} 를 채운다 (Step 4 시드와 같은 값).
 *
 * <p>⚠️ {@code thrsh_cnt} 는 매핑하지 않는다 — 화면 기준이 증가율(%)로 확정돼 보류된 컬럼이고
 * 41- 마이그레이션이 {@code DROP NOT NULL} 했으므로 INSERT/UPDATE 에서 빠져도 된다(범위 밖 #7).
 * 매핑하면 merge 가 시드의 NULL 을 그대로 덮어써 의미 없는 UPDATE 만 늘어난다.
 *
 * <p><b>조회는 이 엔티티로 하지 않는다.</b> 정렬이 {@code TB_COM_CD.sort_seq} 조인이라
 * MyBatis({@link RiskNotiMapper#selectCriteria()})가 담당하고 결과는 조회 전용
 * {@link jeju.datahub.risknoti.service.RiskCriteriaVO} 로 받는다.
 */
@Entity
@Table(name = "tb_risk_stp_noti_crtr", schema = "parking")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RiskCrtr {

    /**
     * 기준아이디 (thrsh_id, VARCHAR PK — <b>할당형</b>).
     *
     * <p>{@code 'THRSH_' + risk_lvl_cd} 고정이라 identity 로 바꾸지 않았다 (D11 예외).
     * 단계당 1행 고정 조회 키라 의미 있는 문자열이 낫다.
     */
    @Id
    @Column(name = "thrsh_id", length = 20)
    private String thrshId;

    /** 위험단계코드 (risk_lvl_cd, <b>NOT NULL</b> + UNIQUE — 단계당 1행을 구조가 강제한다, D18) */
    @Column(name = "risk_lvl_cd", nullable = false, length = 30)
    private String riskLvlCd;

    /** 증가율기준 (incrs_rt, NUMERIC(7,2)) — 전일 대비 민원 증가율(%) 기준. 0 초과 100 이하 (D18) */
    @Column(name = "incrs_rt")
    private BigDecimal incrsRt;

    /** 최소민원수 (min_cvlcpt_cnt, <b>NOT NULL</b> DEFAULT 0) — 판정 하한 (TSK-4092 D2·D4) */
    @Column(name = "min_cvlcpt_cnt", nullable = false)
    private Integer minCvlcptCnt;

    /**
     * 기본최소민원수 (bsc_min_cvlcpt_cnt, NUMERIC 아님 · <b>NOT NULL</b>) — 최초 등재값. <b>읽기만 한다.</b>
     *
     * <p>🔴 {@code updatable = false} 가 이 필드의 핵심이다 (TSK-4092 D11 ②) —
     * Hibernate 가 UPDATE 문 자체에서 이 컬럼을 빼므로 {@code merge()} 가 절대 덮지 못한다.
     * 선례: {@code AnlsVrblStng.bscStngVl}({@code AnlsVrblStng.java}).
     *
     * <p>⚠️ {@code updatable=false} 는 <b>UPDATE 만</b> 막는다 — INSERT 에는 들어간다.
     * 다만 이 엔티티는 3행이 시드로 이미 존재해 실제 경로는 항상 UPDATE 다.
     */
    @Column(name = "bsc_min_cvlcpt_cnt", nullable = false, updatable = false)
    private Integer bscMinCvlcptCnt;

    /** 수정자ID (mdfr_id, <b>NOT NULL</b>) — 인증 도입 전 상수 'system' */
    @Column(name = "mdfr_id", nullable = false, length = 20)
    private String mdfrId;

    /** 수정일시 (mdfcn_dt, <b>NOT NULL</b>) — UPSERT 시점 */
    @Column(name = "mdfcn_dt", nullable = false)
    private LocalDateTime mdfcnDt;
}
