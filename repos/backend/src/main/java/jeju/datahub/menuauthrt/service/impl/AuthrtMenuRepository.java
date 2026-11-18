package jeju.datahub.menuauthrt.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 권한×메뉴 매핑 JPA 리포지터리. (쓰기 전용 — 전체 교체 저장)
 *
 * <p>{@code MyBatisConfig} 는 {@code @Mapper} 가 붙은 인터페이스만 스캔하므로
 * 이 인터페이스가 MyBatis 매퍼로 잘못 등록되지 않는다.
 */
public interface AuthrtMenuRepository extends JpaRepository<AuthrtMenu, AuthrtMenuId> {

    /**
     * 특정 메뉴의 매핑을 전부 삭제한다. (전체 교체 저장의 1단계)
     *
     * <p>호출부는 반드시 트랜잭션 안이어야 하며, 재삽입 전에 {@code flush()} 로
     * 삭제를 DB 에 먼저 반영해야 PK 충돌을 피한다.
     */
    void deleteByMenuId(String menuId);
}
