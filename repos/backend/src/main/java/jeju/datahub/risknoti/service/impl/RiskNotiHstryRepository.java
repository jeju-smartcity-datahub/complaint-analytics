package jeju.datahub.risknoti.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 위험단계 알림 이력 JPA 리포지터리. (읽음 처리 전용 — PK 단건 조회 후 변경 감지, TSK-4086)
 *
 * <p>PK 는 {@code Long} 이다 — {@code noti_id} 가 BIGINT identity(마이그레이션 41)다.
 *
 * <p>🔴 조회 메서드를 여기 늘리지 않는다 — 알림 이력 조회는 필터·집계·페이징이라
 * MyBatis({@link RiskNotiMapper}) 담당이고, 이 리포지터리는 {@code findById} 로 찾은 행의
 * {@code prsl_yn}·{@code prsl_dt} 만 바꾸는 쓰기 경로에 쓴다.
 */
public interface RiskNotiHstryRepository extends JpaRepository<RiskNotiHstry, Long> {
}
