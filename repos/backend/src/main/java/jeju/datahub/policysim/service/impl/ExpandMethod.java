package jeju.datahub.policysim.service.impl;

/**
 * 주차장 확충 방식 (TSK-4037 Step 3 · Step 5 {@code expandMethod}).
 *
 * <p>순증 면수 {@code ΔS_eff} 와 단가 계수가 방식마다 갈린다(§0-2 수정 2 — 복층화를 신규와 분리).
 * 단가 계수 이름({@link #cstVrblCd})은 Step 4 비용 산출이 쓴다 — 이 Step 은 비용을 계산하지 않는다.
 */
public enum ExpandMethod {

    /** 공한지 조성 — 연 반환율 r_ret 만큼 예측기간 동안 면이 줄어든다 */
    VACLOT(PolicySimParams.CST_VACLOT),

    /** 복층화 — 반환 없음 */
    MULTFLR(PolicySimParams.CST_MULTFLR),

    /** 신규 조성 — 반환 없음 */
    NEW(PolicySimParams.CST_PBLPKLT);

    private final String cstVrblCd;

    ExpandMethod(String cstVrblCd) {
        this.cstVrblCd = cstVrblCd;
    }

    /** 1면당 조성비 계수의 {@code vrbl_cd} */
    public String cstVrblCd() {
        return cstVrblCd;
    }
}
