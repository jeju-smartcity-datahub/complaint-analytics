package jeju.datahub.analysis.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 분석결과 JPA 리포지터리. (쓰기 전용 — 실행 1건 = 결과 1행 등록)
 *
 * <p>PK 는 {@code Long} 이다 — {@code rslt_id} 가 BIGINT identity 이기 때문.
 *
 * <p>조회 메서드를 여기 늘리지 않는다. 저장된 스냅샷 조회는 MyBatis 담당이고,
 * 이 리포지터리는 {@code save} 만 쓴다.
 *
 * <p>{@code MyBatisConfig} 는 {@code @Mapper} 가 붙은 인터페이스만 스캔하므로
 * 이 인터페이스가 MyBatis 매퍼로 잘못 등록되지 않는다.
 */
public interface AnlsRsltRepository extends JpaRepository<AnlsRslt, Long> {
}
