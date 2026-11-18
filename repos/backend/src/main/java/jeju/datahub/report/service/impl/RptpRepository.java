package jeju.datahub.report.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 보고서 JPA 리포지터리. (쓰기 전용 — 직접 생성 보고서 등록)
 *
 * <p>PK 는 {@code Long} 이다 — {@code rptp_id} 가 BIGINT identity 이기 때문.
 *
 * <p>조회 <b>메서드를 여기 선언해 늘리지 않는다</b>. 목록·단건 조회는 다중 LEFT JOIN 이라
 * MyBatis({@link ReportMapper}) 담당이다.
 *
 * <p>다만 <b>상속분 {@code findById} 는 PK 단건 조회로 쓴다</b> —
 * 영속성 규칙의 "PK 단건 조회 = JPA" 규칙 그대로다.
 * 쓰는 곳은 {@code ReportAutoCrtServiceImpl}(AUTO 표식 덮기)과
 * {@code ReportOwnershipGuard}(소유 판정) 둘이다.
 *
 * <p>{@code MyBatisConfig} 는 {@code @Mapper} 가 붙은 인터페이스만 스캔하므로
 * 이 인터페이스가 MyBatis 매퍼로 잘못 등록되지 않는다.
 */
public interface RptpRepository extends JpaRepository<Rptp, Long> {
}
