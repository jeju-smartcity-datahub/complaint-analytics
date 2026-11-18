package jeju.datahub.analysis.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 분석실행 JPA 리포지터리. (쓰기 전용 — 실행 이력 등록)
 *
 * <p>PK 는 {@code Long} 이다 — {@code excn_id} 가 BIGINT identity 이기 때문.
 *
 * <p>조회 메서드를 여기 늘리지 않는다. 대시보드 목록·보고서 스냅샷 조회는 MyBatis 담당이고,
 * 이 리포지터리는 {@code save} 만 쓴다 — {@code save()} 직후 채워진 PK 를
 * {@link AnlsRslt}·{@code TB_RPTP} 저장에 넘긴다.
 *
 * <p>{@code MyBatisConfig} 는 {@code @Mapper} 가 붙은 인터페이스만 스캔하므로
 * 이 인터페이스가 MyBatis 매퍼로 잘못 등록되지 않는다.
 */
public interface AnlsExcnRepository extends JpaRepository<AnlsExcn, Long> {
}
