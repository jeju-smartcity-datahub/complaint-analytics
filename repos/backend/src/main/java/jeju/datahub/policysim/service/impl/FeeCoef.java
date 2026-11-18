package jeju.datahub.policysim.service.impl;

import java.util.List;

import jeju.datahub.policysim.service.PolicySimParamMissingException;

/**
 * 요금제 변화식 계수 7종의 적용값. 공유 3종(occ·occ_th·α — 4037)과 요금제 전용 4종(INSERT).
 *
 * <p>🔴 <b>계산기에 계수를 인자로 넘기는 이유</b> — 민감도가 계수 하나만 {@code min_vl}·{@code max_vl} 로 바꿔
 * 다시 계산한다({@link #withEps}·{@link #withLambda}·{@link #withTau}). 값의 조달(행 존재 확인 · 폴백 · 누락 시 503)은
 * {@link PolicySimParams} 해석 규칙이 한다 — FEE 필수 목록 확장은 구현이다.
 *
 * @param occ      기준점유율 occ ({@code PCSHR_CRTR_VL}) — U0_aply = 면수 × occ, 흡수 on/off 판정
 * @param occTh    포화점유율 임계값 occ_th ({@code PCSHR_THRSH}) — occ ≥ occ_th 일 때만 흡수
 * @param alpha    흡수율 α ({@code ALPHA_VL})
 * @param eps      가격탄력성 ε ({@code PRC_ELST_VL}, 기본 −0.3) — 🔴 음수(0 포함)만 받는다
 * @param lambda   유료전환 수요감소율 λ ({@code PAID_DMND_RT}, 기본 0.3)
 * @param tau      전이율 τ ({@code TRNSF_RT}, 기본 0.2)
 * @param tauLocal 전이 중 같은 지역 비율 τ_local ({@code TRNSF_LOCAL_RT}, 기본 0.5) — 총량을 줄이지 않는다
 */
public record FeeCoef(double occ, double occTh, double alpha, double eps, double lambda, double tau, double tauLocal) {

    public static final String PRC_ELST_VL = "PRC_ELST_VL";
    public static final String PAID_DMND_RT = "PAID_DMND_RT";
    public static final String TRNSF_RT = "TRNSF_RT";
    public static final String TRNSF_LOCAL_RT = "TRNSF_LOCAL_RT";

    /** 요금제 전용 계수 4종 — INSERT sort_seq 18~21 순 */
    public static final List<String> FEE_VRBL_CDS = List.of(PRC_ELST_VL, PAID_DMND_RT, TRNSF_RT, TRNSF_LOCAL_RT);

    /** 부호·범위가 뒤집히면 산식이 뒤집히므로 받지 않는다. 설정 화면 범위(min_vl·max_vl) 검사는 저장 API 가 한다 */
    public FeeCoef {
        if (!(occ >= 0) || !(occTh > 0) || !(alpha >= 0) || !(eps <= 0)
                || !(lambda >= 0 && lambda <= 1) || !(tau >= 0 && tau <= 1) || !(tauLocal >= 0 && tauLocal <= 1)) {
            throw new IllegalArgumentException("요금제 계수가 유효하지 않습니다: occ=" + occ + ", occTh=" + occTh
                    + ", alpha=" + alpha + ", eps=" + eps + ", lambda=" + lambda + ", tau=" + tau + ", tauLocal=" + tauLocal);
        }
    }

    /**
     * 해석된 계수에서 만든다 — 공유 3종은 4037 접근자, 전용 4종은 {@link PolicySimParams#get}.
     *
     * @throws PolicySimParamMissingException 전용 4종 중 하나라도 해석되지 않았을 때(FEE 필수 목록으로 해석하지 않은 인스턴스)
     */
    public static FeeCoef from(PolicySimParams p) {
        List<String> missing = FEE_VRBL_CDS.stream().filter(cd -> p.get(cd) == null).toList();
        if (!missing.isEmpty()) {
            throw new PolicySimParamMissingException(missing);
        }
        return new FeeCoef(p.occ(), p.occTh(), p.alpha(),
                p.get(PRC_ELST_VL).doubleValue(),
                p.get(PAID_DMND_RT).doubleValue(),
                p.get(TRNSF_RT).doubleValue(),
                p.get(TRNSF_LOCAL_RT).doubleValue());
    }

    /** 민감도용 — ε 만 바꾼다 */
    public FeeCoef withEps(double v) {
        return new FeeCoef(occ, occTh, alpha, v, lambda, tau, tauLocal);
    }

    /** 민감도용 — λ 만 바꾼다 */
    public FeeCoef withLambda(double v) {
        return new FeeCoef(occ, occTh, alpha, eps, v, tau, tauLocal);
    }

    /** 민감도용 — τ 만 바꾼다 */
    public FeeCoef withTau(double v) {
        return new FeeCoef(occ, occTh, alpha, eps, lambda, v, tauLocal);
    }

    /** 흡수가 켜지는가 — occ ≥ occ_th (명세 6.3 ②). false 면 흡수 0 ({@code wrnngCn}) */
    public boolean absorbs() {
        return occ >= occTh;
    }
}
