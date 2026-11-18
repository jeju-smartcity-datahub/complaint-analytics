package jeju.datahub.analysis.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 분석변수 변경 이력 JPA 리포지터리. (append 전용)
 *
 * <p>조회는 MyBatis({@link AnalysisWeightMapper#selectHistories(int, String)})가 담당한다 — 최신순 페이징이라
 * 영속성 규칙상 MyBatis 쪽이다. 이 리포지터리는 {@code save()} 만 쓴다.
 *
 * <p>{@code MyBatisConfig} 는 {@code @Mapper} 가 붙은 인터페이스만 스캔하므로
 * 이 인터페이스가 MyBatis 매퍼로 잘못 등록되지 않는다.
 */
public interface AnlsVrblHstryRepository extends JpaRepository<AnlsVrblHstry, Long> {
}
