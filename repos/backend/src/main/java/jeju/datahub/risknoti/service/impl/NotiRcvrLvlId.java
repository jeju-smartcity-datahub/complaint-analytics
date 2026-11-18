package jeju.datahub.risknoti.service.impl;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link NotiRcvrLvl} 복합 PK 클래스. (rcvr_id + risk_lvl_cd)
 *
 * <p>{@code @IdClass} 규약상 필드명·타입이 엔티티의 {@code @Id} 필드와 일치해야 하며,
 * {@link Serializable} 구현 + 기본 생성자 + {@code equals}/{@code hashCode} 가 필수다.
 * 선례는 {@code AuthrtMenuId}.
 *
 * <p>🔴 {@code rcvrId} 는 {@code Long} 이다 — 부모 PK 가 BIGINT identity 라
 * 매핑 테이블도 {@code BIGINT} 로 정의돼 있다.
 */
public class NotiRcvrLvlId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 수신자ID (NotiRcvrLvl.rcvrId 와 동일 이름·타입) */
    private Long rcvrId;

    /** 위험단계코드 (NotiRcvrLvl.riskLvlCd 와 동일 이름·타입) */
    private String riskLvlCd;

    /** JPA 규약상 필수인 기본 생성자 */
    public NotiRcvrLvlId() {
    }

    public NotiRcvrLvlId(Long rcvrId, String riskLvlCd) {
        this.rcvrId = rcvrId;
        this.riskLvlCd = riskLvlCd;
    }

    public Long getRcvrId() {
        return rcvrId;
    }

    public String getRiskLvlCd() {
        return riskLvlCd;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof NotiRcvrLvlId)) {
            return false;
        }
        NotiRcvrLvlId other = (NotiRcvrLvlId) obj;
        return Objects.equals(rcvrId, other.rcvrId) && Objects.equals(riskLvlCd, other.riskLvlCd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rcvrId, riskLvlCd);
    }

    @Override
    public String toString() {
        return "NotiRcvrLvlId(rcvrId=" + rcvrId + ", riskLvlCd=" + riskLvlCd + ")";
    }
}
