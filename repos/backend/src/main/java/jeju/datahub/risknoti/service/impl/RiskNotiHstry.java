package jeju.datahub.risknoti.service.impl;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 위험단계 알림 이력 엔티티. (parking.TB_RISK_STP_NOTI_HSTRY — <b>읽음 처리 전용</b>, TSK-4086)
 *
 * <p>{@code .claude/rules/rule_persistence-jpa-mybatis.md} 기준 <b>쓰기(U)는 JPA</b> 다.
 * PK 단건을 찾아 두 필드를 바꾸는 전형적인 변경 감지(dirty checking) 경로라
 * 같은 도메인의 {@link NotiRcvr}·{@link RiskCrtr} 와 같은 방식으로 둔다.
 *
 * <p>🔴 <b>나머지 컬럼은 매핑하지 않는다</b> — 이 엔티티의 책임은 '읽음 처리' 하나다.
 * 읽기 경로는 전부 MyBatis({@link jeju.datahub.risknoti.service.RiskAlertVO})라 엔티티에
 * 전 컬럼을 들일 이유가 없고, 들이면 배치({@code JdbcBatchItemWriter})가 쓰는 컬럼과 이중 관리가 된다.
 *
 * <p>⚠️ 단, 매핑하지 않은 NOT NULL 컬럼이 있어 이 엔티티로 INSERT 는 할 수 없다 —
 * {@code persist()} 를 호출하지 않는다(조회 후 수정 전용).
 */
@Entity
@Table(name = "tb_risk_stp_noti_hstry", schema = "parking")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RiskNotiHstry {

    /** 알림아이디 (noti_id) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 마이그레이션 41: BIGINT identity
    @Column(name = "noti_id")
    private Long notiId;

    /** 읽음여부 (Y | N). ⚠️ 컬럼명은 prsl_yn 이다 — read_yn 은 마이그레이션 49 로 개명된 옛 이름 */
    @Column(name = "prsl_yn", length = 1)
    private String prslYn;

    /** 읽은일시 */
    @Column(name = "prsl_dt")
    private LocalDateTime prslDt;
}
