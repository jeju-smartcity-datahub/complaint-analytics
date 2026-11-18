package jeju.datahub.policysim.service.impl;

import java.math.BigDecimal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 정책시뮬레이션결과 엔티티 (parking.TB_PLCY_SMLT_RSLT — 쓰기 전용, TSK-4037 Step 5).
 *
 * <p>쓰기는 JPA 담당이다(rule_persistence-jpa-mybatis.md). 조회가 생기면 MyBatis VO 로 따로 받는다.
 * 컬럼은 {@code 08-TB_PLCY_SMLT_RSLT} + 마이그레이션 81 기준 — JPA 는 underscore→camel 설정과 무관하므로
 * {@code @Column(name)} 을 필드마다 명시한다.
 *
 * <p>NOT NULL: {@code excn_id}·{@code plcy_type_cd}·{@code predc_mm}·{@code cst_lvl_cd}·{@code rcmdtn_yn}·{@code excs_yn}.
 * {@code road_mtnc_type}·{@code road_mtnc_len}·{@code adjc_cvlcpt_cnt}(TSK-4038)·{@code fee_incrs_rt}(TSK-4039) 는 확충에서 NULL 이다.
 * 이면도로는 {@code add_pklt_slot} NULL · {@code vld_add_pklt_slot} = ΔS · {@code cst_lvl_cd} = {@code TBD}.
 * 요금제(TSK-4039)는 {@code add_pklt_slot}·{@code road_mtnc_*}·{@code vld_add_pklt_slot} NULL · {@code fee_*}·{@code aplcn_trgt_slot}·
 * {@code lvng_vhcl_cnt}·{@code absrp_cnt}·{@code trnsf_cnt}·{@code adjc_cvlcpt_cnt} 값 · {@code cst_lvl_cd} = {@code TBD}.
 */
@Entity
@Table(name = "tb_plcy_smlt_rslt", schema = "parking")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PlcySmltRslt {

    /** 결과아이디 (BIGINT identity — DB 가 매긴다) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rslt_id")
    private Long rsltId;

    /** 실행아이디 (FK → TB_ANLS_EXCN) */
    @Column(name = "excn_id", nullable = false)
    private Long excnId;

    /** 정책유형코드 (PLCY_TYPE_CD) */
    @Column(name = "plcy_type_cd", nullable = false, length = 30)
    private String plcyTypeCd;

    /** 예측개월수 */
    @Column(name = "predc_mm", nullable = false)
    private Integer predcMm;

    /** 추가주차면수 N (요청값) */
    @Column(name = "add_pklt_slot")
    private Integer addPkltSlot;

    /** 도로정비유형 — TSK-4038. 요청 코드(RSDNC·CMRC) 그대로 */
    @Column(name = "road_mtnc_type", length = 40)
    private String roadMtncType;

    /** 정비길이(m) — TSK-4038 (마이그레이션 82). 확충은 NULL */
    @Column(name = "road_mtnc_len")
    private Integer roadMtncLen;

    /** 인접지역민원증가수 (풍선효과, 건/월) — TSK-4038 (마이그레이션 82). R·C1 에 이미 반영된 내역. 확충은 NULL */
    @Column(name = "adjc_cvlcpt_cnt", precision = 10, scale = 2)
    private BigDecimal adjcCvlcptCnt;

    /** 요금인상률(%) — TSK-4039. RAISE 만(10·20·30), CONVERT·다른 정책은 NULL */
    @Column(name = "fee_incrs_rt", precision = 5, scale = 2)
    private BigDecimal feeIncrsRt;

    /** 요금적용방법 (RAISE · CONVERT) — TSK-4039 (마이그레이션 83). 다른 정책은 NULL */
    @Column(name = "fee_aplcn_mthd", length = 30)
    private String feeAplcnMthd;

    /** 요금적용구역 (ALL · Z1 · Z2) — TSK-4039. 읍면동 지정 시 그 읍면동의 급지 */
    @Column(name = "fee_aplcn_zone", length = 30)
    private String feeAplcnZone;

    /** 적용대상면수 — TSK-4039 (대상 지역 ∩ 급지 ∩ 유무료, NO_ENF 읍면동 포함) */
    @Column(name = "aplcn_trgt_slot")
    private Integer aplcnTrgtSlot;

    /** 이탈차량수 V — TSK-4039 (NO_ENF 읍면동 제외) */
    @Column(name = "lvng_vhcl_cnt", precision = 10, scale = 2)
    private BigDecimal lvngVhclCnt;

    /** 흡수불법주차수 ΣΔE_abs — TSK-4039 */
    @Column(name = "absrp_cnt", precision = 10, scale = 2)
    private BigDecimal absrpCnt;

    /** 전이불법주차수 τ × V (대상 + 인접) — TSK-4039 */
    @Column(name = "trnsf_cnt", precision = 10, scale = 2)
    private BigDecimal trnsfCnt;

    /** 민원감소율 R (%) — NO_ENF 면 NULL */
    @Column(name = "cvlcpt_rdct_rt", precision = 7, scale = 2)
    private BigDecimal cvlcptRdctRt;

    /** 혼잡개선율 I (%) — NO_ENF·NO_SPLY 면 NULL */
    @Column(name = "cngst_impv_rt", precision = 5, scale = 2)
    private BigDecimal cngstImpvRt;

    /** 비용수준코드 (CST_LVL_CD) */
    @Column(name = "cst_lvl_cd", nullable = false, length = 30)
    private String cstLvlCd;

    /** 추천여부 — 단건 저장은 false (추천 판정은 시나리오 비교가 한다) */
    @Column(name = "rcmdtn_yn", nullable = false)
    private Boolean rcmdtnYn;

    /** 분석범위내용 (라벨) */
    @Column(name = "anls_rng_cn", length = 100)
    private String anlsRngCn;

    /** 기준민원건수 C0 */
    @Column(name = "crtr_cvlcpt_cnt", precision = 10, scale = 2)
    private BigDecimal crtrCvlcptCnt;

    /** 기준불법주차건수 E0 */
    @Column(name = "crtr_ilgl_cnt", precision = 10, scale = 2)
    private BigDecimal crtrIlglCnt;

    /** 기준혼잡지수 H0 — NO_SPLY 면 NULL */
    @Column(name = "crtr_cngst_idx", precision = 6, scale = 3)
    private BigDecimal crtrCngstIdx;

    /** 확충후혼잡지수 H1 — NO_ENF·NO_SPLY 면 NULL */
    @Column(name = "aftr_cngst_idx", precision = 6, scale = 3)
    private BigDecimal aftrCngstIdx;

    /** 기존주차면수 S */
    @Column(name = "exs_pklt_cpcty")
    private Integer exsPkltCpcty;

    /** 유효증설면수 — 확충 ΔS_eff · 이면도로 환산 확보 면수 ΔS */
    @Column(name = "vld_add_pklt_slot", precision = 8, scale = 2)
    private BigDecimal vldAddPkltSlot;

    /**
     * 적용설정값 (JSONB) — 정책별 필수 계수(13 · 이면도로·요금제 각 17) 실제 적용값 + 민원·단속 기준 구간.
     * ⚠️ {@code @JdbcTypeCode(SqlTypes.JSON)} 을 빼면 jsonb ← varchar 로 INSERT 가 깨진다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aplcn_param_json")
    private String aplcnParamJson;

    /** 확충후민원건수 C1 — NO_ENF 면 NULL */
    @Column(name = "aftr_cvlcpt_cnt", precision = 10, scale = 2)
    private BigDecimal aftrCvlcptCnt;

    /** 산출상태코드 (CALC_STTS_CD) — 정상 NULL · NO_ENF · NO_SPLY (조회 범위 단위) */
    @Column(name = "calc_stts_cd", length = 30)
    private String calcSttsCd;

    /** 초과(흡수 포화)여부 */
    @Column(name = "excs_yn", nullable = false)
    private Boolean excsYn;
}
