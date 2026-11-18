package jeju.datahub.risknoti.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 알림 수신자 JPA 리포지터리. (쓰기 전용 — 등록·활성화 토글·소프트 삭제)
 *
 * <p>PK 는 {@code Long} 이다 — {@code rcvr_id} 가 BIGINT identity 이기 때문 (TSK-4028 D11).
 *
 * <p>조회 메서드를 여기 늘리지 않는다. 목록·단건 조회는 MyBatis({@link RiskNotiMapper}) 담당이고,
 * 이 리포지터리는 활성화·삭제 대상 행을 영속 상태로 잡아오는 {@code findById} 와
 * {@code save}/{@code flush} 만 쓴다.
 *
 * <p>{@code MyBatisConfig} 는 {@code @Mapper} 가 붙은 인터페이스만 스캔하므로
 * 이 인터페이스가 MyBatis 매퍼로 잘못 등록되지 않는다.
 */
public interface NotiRcvrRepository extends JpaRepository<NotiRcvr, Long> {
}
