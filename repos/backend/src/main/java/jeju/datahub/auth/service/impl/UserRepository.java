package jeju.datahub.auth.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@code TB_USER} 리포지터리.
 *
 * <p>PK 가 {@code user_id}(VARCHAR) 라 ID 타입은 {@code String} 이다.
 *
 * <p>🔴 아래 두 파생 쿼리는 <b>반드시 선언한다</b> — 서비스 본문이 호출하므로 없으면 컴파일되지 않는다.
 * {@code existsByUserId} 는 20자 절단 충돌 회피(선점 검사)에 쓴다.
 */
public interface UserRepository extends JpaRepository<UserEntity, String> {

    Optional<UserEntity> findByCertId(String certId);

    boolean existsByUserId(String userId);
}
