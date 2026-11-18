package jeju.datahub.policysim.service.impl;

/**
 * 산출상태코드 (공통코드 그룹 {@code CALC_STTS_CD} — 마이그레이션 81). 정상은 {@code null}.
 *
 * <p>🔴 두 값을 뭉개지 않는다. {@code E0=0} 이고 {@code S=0} 이면 {@link #NO_ENF} 가 우선한다.
 */
public enum PolicySimCalcStts {
    /** 단속 없음 — E0 = 0 이라 k 를 계산할 수 없다 */
    NO_ENF,
    /** 공급 없음 — S = 0 이라 H0 를 계산할 수 없다 (k·R 은 계산한다 — 명세 10장) */
    NO_SPLY
}
