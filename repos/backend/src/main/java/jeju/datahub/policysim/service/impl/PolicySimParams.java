package jeju.datahub.policysim.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.policysim.service.PolicySimParamMissingException;
import lombok.extern.slf4j.Slf4j;

/**
 * 정책 시뮬레이션 계수 13종(REPAIR·FEE 는 각 17종 — TSK-4038 Step 4-2 · TSK-4039 Step 5-2)의 <b>적용값</b> (TSK-4037 Step 1-1 표 · Step 2).
 *
 * <p>🔴 <b>행 개수가 아니라 필수 {@code vrbl_cd} 13개를 각각</b> 확인한다 — TSK-4038·4039 가 같은
 * {@code POLICY_SIM} 에 행을 더하므로 개수 비교는 틀린다. 하나라도 없으면
 * {@link PolicySimParamMissingException}(503) — 1.0 폴백을 하지 않는다.
 *
 * <p>값 해석은 {@code AnalysisWeightServiceImpl.coefficient()} 와 같은 규칙이다 — {@code use_yn='N'}
 * 이거나 {@code stng_vl} 이 NULL 이면 {@code bsc_stng_vl}(기본값 = 명세값)로 폴백한다.
 * ⚠️ 폴백할 {@code bsc_stng_vl} 마저 NULL 이면 쓸 값이 없으므로 <b>누락으로 친다</b>(1.0 으로 메우지 않는다).
 *
 * <p>{@link #toMap()} 이 {@code aplcn_param_json} 에 담을 <b>실제 적용값</b>(폴백 포함)이다.
 */
@Slf4j
public final class PolicySimParams {

    public static final String ANLS_TYPE_CD = "POLICY_SIM";

    public static final String PCSHR_CRTR_VL = "PCSHR_CRTR_VL";
    public static final String PCSHR_THRSH = "PCSHR_THRSH";
    public static final String ALPHA_VL = "ALPHA_VL";
    public static final String DSCL_MLTP = "DSCL_MLTP";
    public static final String FIX_WGVL = "FIX_WGVL";
    public static final String GEO_CVRG_CRCT_JEJU = "GEO_CVRG_CRCT_JEJU";
    public static final String GEO_CVRG_CRCT_SEOGWIPO = "GEO_CVRG_CRCT_SEOGWIPO";
    public static final String VACLOT_GVBK_RT = "VACLOT_GVBK_RT";
    public static final String CST_VACLOT = "CST_VACLOT";
    public static final String CST_PBLPKLT = "CST_PBLPKLT";
    public static final String CST_MULTFLR = "CST_MULTFLR";
    public static final String CST_LOW_BNDRY = "CST_LOW_BNDRY";
    public static final String CST_HIGH_BNDRY = "CST_HIGH_BNDRY";

    /** 필수 계수 13종 — Step 1-1 표의 sort_seq 순 */
    public static final List<String> REQUIRED_VRBL_CDS = List.of(
            PCSHR_CRTR_VL, PCSHR_THRSH, ALPHA_VL, DSCL_MLTP, FIX_WGVL,
            GEO_CVRG_CRCT_JEJU, GEO_CVRG_CRCT_SEOGWIPO,
            VACLOT_GVBK_RT, CST_VACLOT, CST_PBLPKLT, CST_MULTFLR, CST_LOW_BNDRY, CST_HIGH_BNDRY);

    /**
     * 이면도로 정비(REPAIR) 요청에만 필요한 계수 4종 (TSK-4038 Step 1 · Step 4-2) — 코드 상수는 {@link RepairCoef}.
     * 🔴 EXPAND 필수 목록({@link #REQUIRED_VRBL_CDS})에는 넣지 않는다 — 마이그레이션 82 가 없는 환경에서 확충까지 503 이 되지 않게.
     */
    public static final List<String> REPAIR_VRBL_CDS = List.of(
            RepairCoef.N100_RSDNC, RepairCoef.N100_CMRC, RepairCoef.ALPHA_B_VL, RepairCoef.BALLOON_RT);

    /** REPAIR 요청의 필수 계수 17종 = 공통·확충 13 + 이면도로 4 — 각각 확인한다(행 개수 비교 금지) */
    public static final List<String> REPAIR_REQUIRED_VRBL_CDS = concat(REQUIRED_VRBL_CDS, REPAIR_VRBL_CDS);

    /**
     * 요금제 적용(FEE) 요청의 필수 계수 17종 = 공통·확충 13 + 요금제 4({@link FeeCoef#FEE_VRBL_CDS}) — 각각 확인한다 (TSK-4039 Step 5-2).
     * 🔴 EXPAND·REPAIR 목록에는 넣지 않는다 — 마이그레이션 83 이 없는 환경에서 다른 정책까지 503 이 되지 않게.
     */
    public static final List<String> FEE_REQUIRED_VRBL_CDS = concat(REQUIRED_VRBL_CDS, FeeCoef.FEE_VRBL_CDS);

    /** {@code tb_admdst.sgg_nm} — g_sgg 선택 키 */
    public static final String SGG_JEJU = "제주시";
    public static final String SGG_SEOGWIPO = "서귀포시";

    private static final String USE_Y = "Y";

    private final Map<String, BigDecimal> values;
    /** 해석한 코드의 min_vl·max_vl (TSK-4039 Step 4 민감도) — 둘 중 하나라도 NULL 인 행은 담지 않는다. {@link #toMap()} 에는 들어가지 않는다 */
    private final Map<String, VrblRange> ranges;

    private PolicySimParams(Map<String, BigDecimal> values) {
        this(values, Map.of());
    }

    private PolicySimParams(Map<String, BigDecimal> values, Map<String, VrblRange> ranges) {
        this.values = Collections.unmodifiableMap(values);
        this.ranges = Collections.unmodifiableMap(ranges);
    }

    /**
     * {@code selectWeightsByType("POLICY_SIM")} 결과에서 13계수를 해석한다.
     *
     * @throws PolicySimParamMissingException 필수 코드가 하나라도 없거나 쓸 값이 없을 때 (ERROR 1줄을 남긴다)
     */
    public static PolicySimParams resolve(List<AnalysisWeightVO> rows) {
        return resolve(rows, REQUIRED_VRBL_CDS);
    }

    /**
     * 지정한 필수 코드 목록으로 해석한다 (TSK-4038 Step 4-2 — REPAIR 는 {@link #REPAIR_REQUIRED_VRBL_CDS}).
     * 목록의 코드를 <b>각각</b> 확인하고, 적용값은 그 목록의 코드만 담는다.
     *
     * @throws PolicySimParamMissingException 필수 코드가 하나라도 없거나 쓸 값이 없을 때 (ERROR 1줄을 남긴다)
     */
    public static PolicySimParams resolve(List<AnalysisWeightVO> rows, List<String> requiredVrblCds) {
        Map<String, AnalysisWeightVO> byCd = new HashMap<>();
        if (rows != null) {
            for (AnalysisWeightVO r : rows) {
                if (r.getVrblCd() != null) {
                    byCd.putIfAbsent(r.getVrblCd(), r);
                }
            }
        }
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Map<String, VrblRange> ranges = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String cd : requiredVrblCds) {
            AnalysisWeightVO row = byCd.get(cd);
            BigDecimal v = applied(row);
            if (v == null) {
                missing.add(cd);
            } else {
                values.put(cd, v);
                if (row.getMinVl() != null && row.getMaxVl() != null) {
                    ranges.put(cd, new VrblRange(row.getMinVl().doubleValue(), row.getMaxVl().doubleValue()));
                }
            }
        }
        if (!missing.isEmpty()) {
            log.error("정책 시뮬레이션 필수 계수가 없어 계산하지 않습니다(마이그레이션 81·82·83 확인): anlsTypeCd={}, missing={}",
                    ANLS_TYPE_CD, missing);
            throw new PolicySimParamMissingException(missing);
        }
        return new PolicySimParams(values, ranges);
    }

    /** coefficient() 와 같은 해석 — 꺼졌거나 값이 비면 bsc_stng_vl. 행이 없거나 둘 다 비면 null */
    private static BigDecimal applied(AnalysisWeightVO row) {
        if (row == null) {
            return null;
        }
        if (!USE_Y.equals(row.getUseYn()) || row.getStngVl() == null) {
            if (row.getBscStngVl() != null) {
                log.warn("정책 시뮬레이션 계수를 쓸 수 없어 기본설정값 {} 로 진행합니다: vrblCd={}, useYn={}, stngVl={}",
                        row.getBscStngVl(), row.getVrblCd(), row.getUseYn(), row.getStngVl());
            }
            return row.getBscStngVl();
        }
        return row.getStngVl();
    }

    /** 단위 테스트용 — 13개가 모두 있어야 한다. 이면도로 4종·요금제 4종은 있으면 함께 담는다 */
    static PolicySimParams of(Map<String, BigDecimal> values) {
        List<String> missing = REQUIRED_VRBL_CDS.stream().filter(cd -> values.get(cd) == null).toList();
        if (!missing.isEmpty()) {
            throw new PolicySimParamMissingException(missing);
        }
        Map<String, BigDecimal> ordered = new LinkedHashMap<>();
        concat(REPAIR_REQUIRED_VRBL_CDS, FeeCoef.FEE_VRBL_CDS).stream().filter(cd -> values.get(cd) != null)
                .forEach(cd -> ordered.put(cd, values.get(cd)));
        return new PolicySimParams(ordered);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    /**
     * 이면도로 계수 4종 (TSK-4038 Step 4-2) — {@link #REPAIR_REQUIRED_VRBL_CDS} 로 해석한 경우에만 값이 있다.
     *
     * @throws PolicySimParamMissingException 4종 중 하나라도 해석되지 않았을 때 — 13종만 해석한 인스턴스로 이면도로를 계산하지 않는다
     */
    public RepairCoef repairCoef() {
        List<String> missing = REPAIR_VRBL_CDS.stream().filter(cd -> values.get(cd) == null).toList();
        if (!missing.isEmpty()) {
            throw new PolicySimParamMissingException(missing);
        }
        return new RepairCoef(
                values.get(RepairCoef.N100_RSDNC).doubleValue(),
                values.get(RepairCoef.N100_CMRC).doubleValue(),
                values.get(RepairCoef.ALPHA_B_VL).doubleValue(),
                values.get(RepairCoef.BALLOON_RT).doubleValue());
    }

    /**
     * 계수의 허용 범위 {@code min_vl}·{@code max_vl} (TSK-4039 Step 4 민감도). 해석 목록에 없었거나 행의 min/max 가 NULL 이면 null.
     * 🔴 민감도 범위의 정본은 이 값이다 — 코드에 하드코딩하지 않는다.
     */
    public VrblRange range(String vrblCd) {
        return ranges.get(vrblCd);
    }

    public BigDecimal get(String vrblCd) {
        return values.get(vrblCd);
    }

    /** occ — 기준점유율 (pcshr 결측 폴백, 수정 3) */
    public double occ() { return values.get(PCSHR_CRTR_VL).doubleValue(); }

    /** occ_th — 포화점유율 임계값 (α_e = α × min(1, occ ÷ occ_th)) */
    public double occTh() { return values.get(PCSHR_THRSH).doubleValue(); }

    /** α — 흡수율 */
    public double alpha() { return values.get(ALPHA_VL).doubleValue(); }

    /** r_ret — 공한지 연반환율 (/년) */
    public double vaclotGvbkRt() { return values.get(VACLOT_GVBK_RT).doubleValue(); }

    /** m — 적발배수 */
    public double dsclMltp() { return values.get(DSCL_MLTP).doubleValue(); }

    /** w_fixed — 고정형(CCTV) 단속 가중치 */
    public double fixWgvl() { return values.get(FIX_WGVL).doubleValue(); }

    /**
     * g_sgg — 좌표 결측 보정계수 (수정 4).
     *
     * @throws IllegalStateException 제주시·서귀포시 밖의 시군구명 — 1.0 으로 메우면 E0 가 조용히 틀린다
     */
    public double geoCvrgCrct(String sggNm) {
        if (SGG_JEJU.equals(sggNm)) {
            return values.get(GEO_CVRG_CRCT_JEJU).doubleValue();
        }
        if (SGG_SEOGWIPO.equals(sggNm)) {
            return values.get(GEO_CVRG_CRCT_SEOGWIPO).doubleValue();
        }
        throw new IllegalStateException("좌표 결측 보정계수가 정의되지 않은 시군구: " + sggNm);
    }

    /** aplcn_param_json 용 — 해석한 계수(13 · REPAIR 17 · FEE 17)의 실제 적용값(폴백 포함), sort_seq 순 */
    public Map<String, BigDecimal> toMap() {
        return values;
    }
}
