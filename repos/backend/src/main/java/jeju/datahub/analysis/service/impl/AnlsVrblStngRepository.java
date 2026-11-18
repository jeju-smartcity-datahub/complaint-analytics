package jeju.datahub.analysis.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 분석변수 설정 JPA 리포지터리. (쓰기 전용 — 분석유형 1건 전체 교체 저장)
 *
 * <p>행은 마이그레이션 시드가 만들어 두므로 이 리포지터리는 {@code findById} → dirty checking UPDATE
 * 경로만 쓴다. 목록 조회는 MyBatis({@link AnalysisWeightMapper}) 담당이다.
 *
 * <p>{@code MyBatisConfig} 는 {@code @Mapper} 가 붙은 인터페이스만 스캔하므로
 * 이 인터페이스가 MyBatis 매퍼로 잘못 등록되지 않는다.
 */
public interface AnlsVrblStngRepository extends JpaRepository<AnlsVrblStng, AnlsVrblStngId> {
}
