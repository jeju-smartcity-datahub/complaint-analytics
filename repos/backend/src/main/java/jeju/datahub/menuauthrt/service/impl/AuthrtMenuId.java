package jeju.datahub.menuauthrt.service.impl;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link AuthrtMenu} 복합 PK 클래스. (authrt_cd + menu_id)
 *
 * <p>{@code @IdClass} 규약상 필드명·타입이 엔티티의 {@code @Id} 필드와 일치해야 하며,
 * {@link Serializable} 구현 + 기본 생성자 + {@code equals}/{@code hashCode} 가 필수다.
 */
public class AuthrtMenuId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 권한코드 (AuthrtMenu.authrtCd 와 동일 이름·타입) */
    private String authrtCd;

    /** 메뉴아이디 (AuthrtMenu.menuId 와 동일 이름·타입) */
    private String menuId;

    /** JPA 규약상 필수인 기본 생성자 */
    public AuthrtMenuId() {
    }

    public AuthrtMenuId(String authrtCd, String menuId) {
        this.authrtCd = authrtCd;
        this.menuId = menuId;
    }

    public String getAuthrtCd() {
        return authrtCd;
    }

    public String getMenuId() {
        return menuId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AuthrtMenuId)) {
            return false;
        }
        AuthrtMenuId other = (AuthrtMenuId) obj;
        return Objects.equals(authrtCd, other.authrtCd) && Objects.equals(menuId, other.menuId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(authrtCd, menuId);
    }

    @Override
    public String toString() {
        return "AuthrtMenuId(authrtCd=" + authrtCd + ", menuId=" + menuId + ")";
    }
}
