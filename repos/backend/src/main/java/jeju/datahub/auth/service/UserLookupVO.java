package jeju.datahub.auth.service;

/**
 * 사용자 단건 조회 결과 — 다른 도메인에 공개하는 {@code TB_USER} 요약.
 *
 * <p>알림 수신자 등록이 {@code userId} 로 사용자를 읽어 이름·이메일·부서를 수신자 행에 복사할 때 쓴다.
 * 🔴 다른 도메인은 {@code auth.service.impl} 의 {@code UserEntity}·{@code UserRepository} 를 직접 참조하지 않는다
 * — 이 VO 가 그 경계다({@code CurrentUserResolver} 관례).
 *
 * <p>🔴 {@link AuthUserVO}(로그인 동기화 결과)·{@link LoginUserVO}({@code /me} MyBatis 조회)와 <b>겸용하지 않는다</b>.
 * 정지 사용자({@code useYn <> 'Y'})도 그대로 담아 돌려준다 — 판정은 호출부 책임이다.
 */
public class UserLookupVO {

    private String userId;
    private String userNm;
    private String userEmlAddr;
    private String deptCd;
    private String useYn;

    public UserLookupVO() {
    }

    public UserLookupVO(String userId, String userNm, String userEmlAddr, String deptCd, String useYn) {
        this.userId = userId;
        this.userNm = userNm;
        this.userEmlAddr = userEmlAddr;
        this.deptCd = deptCd;
        this.useYn = useYn;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserNm() { return userNm; }
    public void setUserNm(String userNm) { this.userNm = userNm; }

    public String getUserEmlAddr() { return userEmlAddr; }
    public void setUserEmlAddr(String userEmlAddr) { this.userEmlAddr = userEmlAddr; }

    public String getDeptCd() { return deptCd; }
    public void setDeptCd(String deptCd) { this.deptCd = deptCd; }

    public String getUseYn() { return useYn; }
    public void setUseYn(String useYn) { this.useYn = useYn; }
}
