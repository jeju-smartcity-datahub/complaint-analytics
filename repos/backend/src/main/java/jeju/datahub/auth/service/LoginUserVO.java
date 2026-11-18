package jeju.datahub.auth.service;

import java.util.List;

/**
 * {@code GET /api/auth/me} 응답 전용 조회 VO (MyBatis 결과 타입).
 *
 * <p>🔴 {@link AuthUserVO}와 <b>역할이 다르다 — 한 클래스로 겸용하지 않는다.</b>
 * {@code AuthUserVO} = 동기화 결과(4필드, JPA upsert 반환) ·
 * {@code LoginUserVO} = 조회 응답(7필드, MyBatis JOIN).
 * 영속성 규칙의 "엔티티(JPA) vs VO(MyBatis) 겸용 금지" 그대로다.
 *
 * <p>🔴 필드명이 곧 API JSON 키다({@code map-underscore-to-camel-case}) — 특히
 * {@code userAuthrtCd} 는 {@code authrtCd} 가 아니다.
 */
public class LoginUserVO {

    private String userId;
    private String userNm;
    private String userEmlAddr;
    private String userAuthrtCd;
    private String authrtNm;
    /** 부서명. nullable — 부서 미등재·무소속이면 null(LEFT JOIN tb_dept) */
    private String deptNm;
    /** 허용 메뉴 경로. {@code selectMenuPathNms} 가 별도 select 로 채운다(후속 작업용) */
    private List<String> menuPathNms;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserNm() { return userNm; }
    public void setUserNm(String userNm) { this.userNm = userNm; }

    public String getUserEmlAddr() { return userEmlAddr; }
    public void setUserEmlAddr(String userEmlAddr) { this.userEmlAddr = userEmlAddr; }

    public String getUserAuthrtCd() { return userAuthrtCd; }
    public void setUserAuthrtCd(String userAuthrtCd) { this.userAuthrtCd = userAuthrtCd; }

    public String getAuthrtNm() { return authrtNm; }
    public void setAuthrtNm(String authrtNm) { this.authrtNm = authrtNm; }

    public String getDeptNm() { return deptNm; }
    public void setDeptNm(String deptNm) { this.deptNm = deptNm; }

    public List<String> getMenuPathNms() { return menuPathNms; }
    public void setMenuPathNms(List<String> menuPathNms) { this.menuPathNms = menuPathNms; }
}
