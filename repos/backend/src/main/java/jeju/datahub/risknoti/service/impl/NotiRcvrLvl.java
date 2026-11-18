package jeju.datahub.risknoti.service.impl;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 수신자 × 위험단계 매핑 엔티티. (parking.TB_NOTI_RCVR_LVL — 쓰기 전용)
 *
 * <p>행이 존재하면 그 수신자가 해당 단계 알림의 <b>메일 수신 대상</b>이라는 의미이며,
 * 별도 상태 컬럼이 없다(테이블 컬럼이 복합 PK 2개뿐이다). 저장은 영속성 규칙상 JPA 담당이라
 * 이 엔티티를 쓴다 ({@code .claude/rules/rule_persistence-jpa-mybatis.md} — 쓰기=JPA).
 *
 * <p>🔁 <b>등록은 INSERT, 수정은 통째 교체</b>(TSK-4250 R10 — 종전 TSK-4028 D5 의 "수정 API 범위 밖" 폐기).
 * 교체의 삭제는 🔴 파생 {@code deleteByRcvrId} 가 아니라 벌크 JPQL
 * {@link NotiRcvrLvlRepository#deleteAllByRcvrIdInBulk} 로 한다 — 이 엔티티는 할당형 복합 PK 라
 * {@code save()}=merge 이고, {@code em.remove} 로 미룬 DELETE 는 flush 때 INSERT 뒤로 밀려 PK 위반이 난다.
 * 수신자 <b>삭제</b>는 여전히 소프트 삭제({@code TB_NOTI_RCVR.del_yn='Y'})라 매핑을 지우지 않는다.
 *
 * <p><b>조회는 이 엔티티로 하지 않는다.</b> 목록의 단계 배열은 MyBatis
 * {@code <collection>} 이 접어서 {@code riskLvlCds} 로 내린다.
 *
 * <p>컬럼명은 DDL {@code database/migrations/sql/41-alert-columns.sql} 기준으로 명시한다.
 */
@Entity
@Table(name = "tb_noti_rcvr_lvl", schema = "parking")
@IdClass(NotiRcvrLvlId.class)
@Getter
@Setter
@ToString
@NoArgsConstructor
public class NotiRcvrLvl {

    /** 수신자ID (rcvr_id, 복합 PK #1 — TB_NOTI_RCVR 참조. BIGINT identity 라 Long, D11) */
    @Id
    @Column(name = "rcvr_id", nullable = false)
    private Long rcvrId;

    /** 위험단계코드 (risk_lvl_cd, 복합 PK #2 — RISK_LVL_CD 공통코드) */
    @Id
    @Column(name = "risk_lvl_cd", nullable = false, length = 30)
    private String riskLvlCd;

    /**
     * 신규 매핑 행 생성용 생성자.
     *
     * <p>수신자 등록·수정 경로에서 만들어지며 신규 INSERT 다(수정은 벌크 삭제 뒤에 넣는다 — TSK-4250 R10).
     * 같은 요청 안에서 단계가 중복되면 복합 PK 위반이므로 서비스가
     * {@code LinkedHashSet} 으로 미리 중복을 제거한다.
     *
     * @param rcvrId    수신자ID (부모 저장 후 DB identity 가 매긴 값)
     * @param riskLvlCd 위험단계 코드
     */
    public NotiRcvrLvl(Long rcvrId, String riskLvlCd) {
        this.rcvrId = rcvrId;
        this.riskLvlCd = riskLvlCd;
    }
}
