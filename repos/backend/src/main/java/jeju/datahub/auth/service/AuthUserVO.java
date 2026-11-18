package jeju.datahub.auth.service;

/**
 * 사용자 동기화 결과.
 *
 * <p>🔴 <b>Spring Security 타입에 의존하지 않는다</b> — {@code DefaultOidcUser} 조립은 호출부의
 * 책임이다(이식성). 이 VO 는 "DB 에 어떤 행이 확정됐는가" 만 전달한다.
 *
 * <p>🔴 {@code LoginUserVO}({@code /me} 응답 6필드 MyBatis 조회 전용)와 <b>겸용하지 않는다</b> —
 * 영속성 규칙의 "엔티티(JPA) vs VO(MyBatis) 겸용 금지" 그대로다.
 */
public class AuthUserVO {

    private String userId;
    private String userNm;
    private String userEmlAddr;
    private String userAuthrtCd;

    public AuthUserVO() {
    }

    public AuthUserVO(String userId, String userNm, String userEmlAddr, String userAuthrtCd) {
        this.userId = userId;
        this.userNm = userNm;
        this.userEmlAddr = userEmlAddr;
        this.userAuthrtCd = userAuthrtCd;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserNm() { return userNm; }
    public void setUserNm(String userNm) { this.userNm = userNm; }

    public String getUserEmlAddr() { return userEmlAddr; }
    public void setUserEmlAddr(String userEmlAddr) { this.userEmlAddr = userEmlAddr; }

    public String getUserAuthrtCd() { return userAuthrtCd; }
    public void setUserAuthrtCd(String userAuthrtCd) { this.userAuthrtCd = userAuthrtCd; }
}
