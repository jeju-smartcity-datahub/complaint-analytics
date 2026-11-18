package jeju.datahub.analysis.service;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;

/**
 * 분석 화면·탭별 일시 조정 허용 변수 목록 (TSK-4325 D3). 조정 가능 변수의 <b>정본</b>이다.
 *
 * <p>프론트만 막으면 API 로 고정값·타 화면 값을 바꿀 수 있어 서버가 이 목록으로 거른다. 목록 밖 코드는 400 이다.
 * 코드는 {@code parking.tb_anls_vrbl_stng} 의 {@code vrbl_cd} 이고, PK 가 {@code (anls_type_cd, vrbl_cd)} 라
 * {@code anlsTypeCd} + 코드로 행이 하나로 정해진다. 각 상수 위에 {@code anls_trgt_cd} 를 적어 둔다.
 *
 * <p>🔁 {@code POLICY_SIM} 은 정책 시뮬레이션 화면 전용 3스코프({@code POLICY_EXPAND}·{@code POLICY_REPAIR}·
 * {@code POLICY_FEE})에만 둔다(TSK-4332 D1 — TSK-4325 D19 개정). 조정 가능 = {@code min_vl < max_vl} 12행이고,
 * 🔴 고정값({@code min_vl = max_vl}) 9행({@code PCSHR_THRSH}·{@code DSCL_MLTP}·{@code FIX_WGVL}·
 * {@code VACLOT_GVBK_RT}·{@code CST_*} 5)은 어느 스코프에도 넣지 않는다(TSK-4332 D2 — 400 이다).
 * 🔴 구역추천이 읽는 {@code POLICY_SIM} 2행({@code PCSHR_CRTR_VL}·{@code PAID_DMND_RT})은 여전히 그 화면의 계수가 아니다.
 * 🔴 코드 문자열은 {@code PolicySimParams}·{@code RepairCoef}·{@code FeeCoef} 상수와 짝이다 — 분석 패키지가
 * 정책 패키지를 참조하지 않도록 문자열로 적고, 일치는 {@code PolicySimSpecTest} 가 단언한다.
 */
@Getter
public enum AnlsVrblScope {

    /**
     * 집중구역 — trgt NULL 의 등급 비율 3행.
     * 🔴 가중치({@code COMPLAINT}·{@code ENFORCE})는 기존 {@code cvlcptWgvl}·{@code crdnWgvl} 이 맡아 여기 없다(D2).
     */
    HOTSPOT("HOTSPOT",
            Codes.HOTSPOT_GRD_BNDRYS,
            List.of(Codes.HOTSPOT_GRD_BNDRYS)),

    /** 투자우선순위 — trgt NULL 의 가중치 4 + 계수 4. */
    INVEST("INVEST",
            List.of("SAFETY", "COMPLAINT", "USAGE", "ACCIDENT",
                    "ALTMNT_RNG_VL", "UP_GRD_CRTR_SCR", "MD_GRD_CRTR_SCR", "INDCT_PKLT_CNT"),
            List.of(Codes.INVEST_GRD_CRTRS)),

    /** 생활권 주차 수급 탭 — trgt {@code SPLY_DMND} 5행. */
    LIFEZONE_SPLY("LIFEZONE",
            Codes.SPLY_INT_VRBLS,
            List.of(Codes.SPLY_GRD_BNDRYS)),

    /** 생활권 혼잡도 탭 — trgt {@code CNGST} 10행. */
    LIFEZONE_CNGST("LIFEZONE",
            Codes.concat(List.of("PCSHR_CRTR_VL"), Codes.CNGST_GRD_BNDRYS,
                    List.of("DSCL_MLTP", "FIX_WGVL", "GEO_CVRG_CRCT_VL", "CVLCPT_WGVL", "MIN_CPCTY_VL")),
            List.of(Codes.CNGST_GRD_BNDRYS)),

    /**
     * 구역추천 요금제 탭 — trgt NULL 공통 2 + trgt {@code FEE} 8 = 10행(D17).
     * 🔁 TSK-4340(2026-11-11 사용자 요청) — 읍면동 판정 계수 3개({@code STSF_RT_THRSH}·{@code CVLCPT_MM_CRTR_VL}·
     * {@code ILGL_CRTR_VL})를 뺐다. 읍면동별 요금제 대상 판정은 서버에서도 제거됐다(같은 날 사용자 결정 — 계산기가
     * 더 읽지 않는다 · 설정 행은 남아 있다). 공유주차제의 동명 {@code CVLCPT_MM_CRTR_VL} 은 별개다.
     */
    RECOMMEND_FEE("RECOMMEND",
            List.of("ALTMNT_RNG_VL", "SWTC_TRGT_MIN_CPCTY",
                    "FEE_CVLCPT_CNT_WGVL", "FEE_CVLCPT_RT_WGVL",
                    "FEE_CVLCPT_MM_CRTR_JEJU", "FEE_CVLCPT_MM_CRTR_SEOGWIPO",
                    "FEE_CVLCPT_RT_CRTR_JEJU", "FEE_CVLCPT_RT_CRTR_SEOGWIPO",
                    "FEE_CVLCPT_INCRS_WGVL", "FEE_CVLCPT_INCRS_RT_UPLMT_VL"),
            List.of()),

    /** 구역추천 공유주차제 탭 — trgt NULL 1 + trgt {@code SHARE} 3 = 4행. */
    RECOMMEND_SHARE("RECOMMEND",
            List.of("CVLCPT_MM_CRTR_VL", "CVLCPT_RT_CRTR_VL", "CVLCPT_INCRS_WGVL", "CVLCPT_INCRS_RT_UPLMT_VL"),
            List.of()),

    /**
     * 정책 시뮬레이션 주차공간 확충 — trgt NULL 공통 4행(TSK-4332 D1).
     * 🔴 trgt {@code EXPAND} 6행은 모두 고정값(min=max)이라 넣지 않는다(D2).
     */
    POLICY_EXPAND("POLICY_SIM",
            Codes.POLICY_COMMON,
            List.of()),

    /** 정책 시뮬레이션 이면도로 정비 — trgt NULL 공통 4 + trgt {@code REPAIR} 4 = 8행(TSK-4332 D1). */
    POLICY_REPAIR("POLICY_SIM",
            Codes.concat(Codes.POLICY_COMMON,
                    List.of("N100_RSDNC", "N100_CMRC", "ALPHA_B_VL", "BALLOON_RT")),
            List.of()),

    /**
     * 정책 시뮬레이션 요금제 — trgt NULL 공통 4 + trgt {@code FEE} 4 = 8행(TSK-4332 D1).
     * {@code PRC_ELST_VL} 은 음수 범위라 범위 검사만 탄다(경계·합계 묶음 없음).
     */
    POLICY_FEE("POLICY_SIM",
            Codes.concat(Codes.POLICY_COMMON,
                    List.of("PRC_ELST_VL", "PAID_DMND_RT", "TRNSF_RT", "TRNSF_LOCAL_RT")),
            List.of());

    /** 분석유형코드 ({@code anls_type_cd}) — 설정 행을 읽는 키. */
    private final String anlsTypeCd;

    /** 허용 변수코드. */
    private final List<String> vrblCds;

    /** 엄격 증가해야 하는 변수 묶음 — 각 목록은 작은 값부터 나열한다. */
    private final List<List<String>> bndryChains;

    AnlsVrblScope(String anlsTypeCd, List<String> vrblCds, List<List<String>> bndryChains) {
        this.anlsTypeCd = anlsTypeCd;
        this.vrblCds = vrblCds;
        this.bndryChains = bndryChains;
    }

    /**
     * 설정 저장({@code AnalysisWeightServiceImpl})과 일시 조정이 함께 쓰는 변수코드 묶음.
     *
     * <p>🔴 enum 상수 인자에서 같은 enum 의 static 필드를 참조하면 illegal forward reference 라 중첩 클래스에 둔다.
     * 🔴 복사하지 않는다 — 저장과 조정의 규칙이 갈리면 설정에서 저장할 수 없는 값으로 보고서를 만들 수 있다(D4).
     */
    public static final class Codes {

        /**
         * 생활권 혼잡 등급 경계 변수코드 — 오름차순 (TSK-4090 Step 2). {@code CNGST_ABSOLUTE} 분류 전용.
         * 저장 시 이 순서로 엄격 증가해야 한다.
         */
        public static final List<String> CNGST_GRD_BNDRYS =
                List.of("CNGST_GRD_BNDRY1", "CNGST_GRD_BNDRY2", "CNGST_GRD_BNDRY3", "CNGST_GRD_BNDRY4");

        /** 집중구역 등급 순위 비율 (TSK-4321). 저장 시 정수이고 이 순서로 엄격 증가해야 한다. */
        public static final List<String> HOTSPOT_GRD_BNDRYS = List.of("GRD_BNDRY1", "GRD_BNDRY2", "GRD_BNDRY3");

        /**
         * 생활권 수급 등급 경계 변수코드 — 오름차순 = 보통·주의·경고·심각 하한 (TSK-4258 D20 · 마이그레이션 91).
         * 저장 시 이 순서로 엄격 증가해야 한다. 🔴 이름은 {@code LifezoneSupply.VRBL_GRD_BNDRY_PREFIX} 와 짝이다 —
         * 분석 패키지가 생활권 패키지를 참조하지 않도록 문자열로 다시 적었다.
         */
        public static final List<String> SPLY_GRD_BNDRYS =
                List.of("SPLY_GRD_BNDRY1", "SPLY_GRD_BNDRY2", "SPLY_GRD_BNDRY3", "SPLY_GRD_BNDRY4");

        /**
         * 정수만 받는 수급 설정 5행 (TSK-4258 D20) — 최소 월평균 민원({@code LifezoneSupply.VRBL_MIN_MNTH_AVG_CVLCPT}
         * 와 짝) + 경계 4개. 판정이 {@code 민원 × 100 ≥ 경계 × 수요지수} 정수 비교라 소수가 들어오면 담을 칸이 없다.
         */
        public static final List<String> SPLY_INT_VRBLS = List.of(
                "SPLY_MIN_MNTH_AVG_CVLCPT",
                "SPLY_GRD_BNDRY1", "SPLY_GRD_BNDRY2", "SPLY_GRD_BNDRY3", "SPLY_GRD_BNDRY4");

        /** 투자우선순위 등급 기준 — 중간 &lt; 상위. 🔴 일시 조정 전용 검증이다(설정 저장은 검사하지 않는다). */
        public static final List<String> INVEST_GRD_CRTRS = List.of("MD_GRD_CRTR_SCR", "UP_GRD_CRTR_SCR");

        /**
         * 정책 시뮬레이션 공통(trgt NULL) 중 조정 가능({@code min_vl < max_vl}) 4행 (TSK-4332 D1).
         * 🔴 같은 trgt NULL 의 고정값 {@code PCSHR_THRSH}·{@code DSCL_MLTP}·{@code FIX_WGVL} 은 넣지 않는다(D2).
         */
        public static final List<String> POLICY_COMMON =
                List.of("PCSHR_CRTR_VL", "ALPHA_VL", "GEO_CVRG_CRCT_JEJU", "GEO_CVRG_CRCT_SEOGWIPO");

        private Codes() {
        }

        @SafeVarargs
        private static List<String> concat(List<String>... lists) {
            List<String> all = new ArrayList<>();
            for (List<String> list : lists) {
                all.addAll(list);
            }
            return List.copyOf(all);
        }
    }
}
