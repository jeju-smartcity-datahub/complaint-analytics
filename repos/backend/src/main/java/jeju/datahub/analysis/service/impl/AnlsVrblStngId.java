package jeju.datahub.analysis.service.impl;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link AnlsVrblStng} 복합 PK 클래스. (anls_type_cd + vrbl_cd)
 *
 * <p>{@code @IdClass} 규약상 필드명·타입이 엔티티의 {@code @Id} 필드와 일치해야 하며,
 * {@link Serializable} 구현 + 기본 생성자 + {@code equals}/{@code hashCode} 가 필수다.
 */
public class AnlsVrblStngId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 분석유형코드 (AnlsVrblStng.anlsTypeCd 와 동일 이름·타입) */
    private String anlsTypeCd;

    /** 변수코드 (AnlsVrblStng.vrblCd 와 동일 이름·타입) */
    private String vrblCd;

    /** JPA 규약상 필수인 기본 생성자 */
    public AnlsVrblStngId() {
    }

    public AnlsVrblStngId(String anlsTypeCd, String vrblCd) {
        this.anlsTypeCd = anlsTypeCd;
        this.vrblCd = vrblCd;
    }

    public String getAnlsTypeCd() {
        return anlsTypeCd;
    }

    public String getVrblCd() {
        return vrblCd;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AnlsVrblStngId)) {
            return false;
        }
        AnlsVrblStngId other = (AnlsVrblStngId) obj;
        return Objects.equals(anlsTypeCd, other.anlsTypeCd) && Objects.equals(vrblCd, other.vrblCd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(anlsTypeCd, vrblCd);
    }

    @Override
    public String toString() {
        return "AnlsVrblStngId(anlsTypeCd=" + anlsTypeCd + ", vrblCd=" + vrblCd + ")";
    }
}
