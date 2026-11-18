package jeju.datahub.dept.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code TB_DEPT} 리포지터리 (TSK-4183 Step 2).
 *
 * <p>PK 가 {@code dept_cd}(VARCHAR) 라 ID 타입은 {@code String} 이다 —
 * <b>할당 PK</b> 이므로 {@code save()} 가 {@code merge()} 로 가고 INSERT 가 flush 시점에 나간다.
 * 그래서 등재 호출부는 {@link DeptRegistrar} 로 트랜잭션 경계를 분리한다.
 *
 * <p>🔴 <b>이 인터페이스는 {@code dept} 패키지 안에서만 쓴다</b> — {@code auth} 는
 * {@link DeptRegistrar} 하나만 주입해 패키지 접점을 1개로 유지한다.
 */
public interface DeptRepository extends JpaRepository<DeptEntity, String> {
}
