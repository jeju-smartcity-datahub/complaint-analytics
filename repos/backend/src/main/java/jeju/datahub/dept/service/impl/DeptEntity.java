package jeju.datahub.dept.service.impl;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code parking.TB_DEPT} 엔티티 (DDL).
 *
 * <p>🔴 <b>이 표는 마스터가 아니라 IdP {@code groups} claim 의 투영이다</b> —
 * 로그인이 미등록 그룹을 자동 등재하고, {@code dept_nm} 초기값은 슬러그와 같다
 * (표시명은 사후 {@code UPDATE} 로 보정하며, 자동 등재는 {@code existsById} 가드라 덮지 않는다).
 *
 * <p>쓰기(등재)는 JPA 다.
 * 목록 조회는 정렬·필터가 섞인 조회라 MyBatis 가 따로 맡는다(겸용 금지).
 *
 * <p>🔴 {@code rgtr_id}·{@code reg_dt}·{@code dept_nm}·{@code use_yn} 은 <b>NOT NULL 인데
 * DB DEFAULT 에 기대지 않는다</b>({@code UserEntity} 와 같은 규약) — 등재 코드가 채운다.
 */
@Entity
@Table(name = "tb_dept", schema = "parking")
public class DeptEntity {

    /** PK. IdP 그룹 슬러그(최대 30자) */
    @Id
    @Column(name = "dept_cd", length = 30, nullable = false)
    private String deptCd;

    /** 초기값은 슬러그와 같다. 표시명은 사후 보정 */
    @Column(name = "dept_nm", length = 100, nullable = false)
    private String deptNm;

    /** 화면 select 표시 순서. 🔴 자동 등재분은 {@code null} 이다(목록이 NULLS LAST) */
    @Column(name = "sort_seq")
    private Integer sortSeq;

    /** {@code N} 이면 부서 목록에서 제외 */
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

    /** JPA 기본 생성자 */
    public DeptEntity() {
    }

    /** 자동 등재용 — {@code dept_nm} 초기값이 슬러그와 같다 */
    public DeptEntity(String deptCd, String deptNm, Integer sortSeq, String useYn,
                      String rgtrId, LocalDateTime regDt) {
        this.deptCd = deptCd;
        this.deptNm = deptNm;
        this.sortSeq = sortSeq;
        this.useYn = useYn;
        this.rgtrId = rgtrId;
        this.regDt = regDt;
    }

    public String getDeptCd() { return deptCd; }
    public void setDeptCd(String deptCd) { this.deptCd = deptCd; }

    public String getDeptNm() { return deptNm; }
    public void setDeptNm(String deptNm) { this.deptNm = deptNm; }

    public Integer getSortSeq() { return sortSeq; }
    public void setSortSeq(Integer sortSeq) { this.sortSeq = sortSeq; }

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
