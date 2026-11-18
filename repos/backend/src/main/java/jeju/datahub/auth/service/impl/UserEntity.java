package jeju.datahub.auth.service.impl;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code parking.TB_USER} 엔티티 (DDL).
 *
 * <p>쓰기(upsert)는 JPA 다.
 * 조회 응답({@code /me})은 복합 JOIN 이라 MyBatis {@code LoginUserVO} 가 따로 맡는다(겸용 금지).
 *
 * <p>🔴 {@code rgtr_id}·{@code reg_dt} 는 <b>NOT NULL 인데 DB DEFAULT 가 없다</b>
 * (기존 {@code 27-TB_AUTHRT.sql} 과 같은 규약). 서비스가 코드로 채운다 —
 * {@code @PrePersist} 나 DB DEFAULT 에 기대지 않는다.
 */
@Entity
@Table(name = "tb_user", schema = "parking")
public class UserEntity {

    /** PK. {@code preferred_username} 기반 20자 (3단 규칙) */
    @Id
    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    /** IdP 의 {@code sub}. UNIQUE — 로그인 매칭의 정본 */
    @Column(name = "cert_id", length = 100, nullable = false, unique = true)
    private String certId;

    @Column(name = "user_nm", length = 100, nullable = false)
    private String userNm;

    @Column(name = "user_eml_addr", length = 200)
    private String userEmlAddr;

    /** FK → {@code TB_AUTHRT.authrt_cd} */
    @Column(name = "user_authrt_cd", length = 30, nullable = false)
    private String userAuthrtCd;

    /**
     * 소속 부서. FK → {@code TB_DEPT.dept_cd}.
     *
     * <p>🔴 정본은 IdP {@code groups} claim 이다 — 로그인마다 덮어쓴다.
     * 🔴 FK 가 걸려 있으므로 값을 세팅하기 전에 {@code TB_DEPT} 등재가 먼저다.
     */
    @Column(name = "dept_cd", length = 30)
    private String deptCd;

    @Column(name = "last_lgn_dt")
    private LocalDateTime lastLgnDt;

    /** {@code N} 이면 로그인 차단 */
    @Column(name = "use_yn", length = 1, nullable = false)
    private String useYn;

    @Column(name = "rgtr_id", length = 20, nullable = false)
    private String rgtrId;

    @Column(name = "reg_dt", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "mdfr_id", length = 20)
    private String mdfrId;

    @Column(name = "mdfcn_dt")
    private LocalDateTime mdfcnDt;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getCertId() { return certId; }
    public void setCertId(String certId) { this.certId = certId; }

    public String getUserNm() { return userNm; }
    public void setUserNm(String userNm) { this.userNm = userNm; }

    public String getUserEmlAddr() { return userEmlAddr; }
    public void setUserEmlAddr(String userEmlAddr) { this.userEmlAddr = userEmlAddr; }

    public String getUserAuthrtCd() { return userAuthrtCd; }
    public void setUserAuthrtCd(String userAuthrtCd) { this.userAuthrtCd = userAuthrtCd; }

    public String getDeptCd() { return deptCd; }
    public void setDeptCd(String deptCd) { this.deptCd = deptCd; }

    public LocalDateTime getLastLgnDt() { return lastLgnDt; }
    public void setLastLgnDt(LocalDateTime lastLgnDt) { this.lastLgnDt = lastLgnDt; }

    public String getUseYn() { return useYn; }
    public void setUseYn(String useYn) { this.useYn = useYn; }

    public String getRgtrId() { return rgtrId; }
    public void setRgtrId(String rgtrId) { this.rgtrId = rgtrId; }

    public LocalDateTime getRegDt() { return regDt; }
    public void setRegDt(LocalDateTime regDt) { this.regDt = regDt; }

    public String getMdfrId() { return mdfrId; }
    public void setMdfrId(String mdfrId) { this.mdfrId = mdfrId; }

    public LocalDateTime getMdfcnDt() { return mdfcnDt; }
    public void setMdfcnDt(LocalDateTime mdfcnDt) { this.mdfcnDt = mdfcnDt; }
}
