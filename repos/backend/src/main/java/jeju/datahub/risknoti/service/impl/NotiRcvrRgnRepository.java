package jeju.datahub.risknoti.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 수신자 × 지역 매핑 JPA 리포지터리. (쓰기 전용)
 *
 * <p>조회 메서드를 여기 늘리지 않는다 — 지역 배열은 MyBatis({@link RiskNotiMapper})가 읽는다.
 *
 * <p>{@code MyBatisConfig} 는 {@code @Mapper} 가 붙은 인터페이스만 스캔하므로
 * 이 인터페이스가 MyBatis 매퍼로 잘못 등록되지 않는다.
 */
public interface NotiRcvrRgnRepository extends JpaRepository<NotiRcvrRgn, Long> {

    /**
     * 한 수신자의 지역 매핑을 <b>벌크 JPQL DELETE</b> 로 즉시 지운다 (수정 시 통째 교체).
     *
     * <p>🔴 <b>파생 {@code deleteByRcvrId}·{@code deleteAll(entities)} 를 쓰지 않는 이유</b> — 그것들은
     * {@code em.remove} 라 DELETE 가 flush 까지 미뤄지는데, 이 엔티티는 IDENTITY 라 {@code save()} 즉시 INSERT 가
     * 나간다. 유지되는 지역을 다시 넣으면 아직 지워지지 않은 옛 행과 부딪혀
     * {@code UNIQUE NULLS NOT DISTINCT (rcvr_id, sgg_nm, emd_cd)} 위반 500 이다.
     *
     * <p>벌크 DELETE 는 영속성 컨텍스트를 거치지 않으므로 {@code flushAutomatically}·{@code clearAutomatically}
     * 를 함께 건다. ⚠️ 호출 뒤에는 그 전에 읽은 엔티티가 모두 detached 다 — 이후 수정해도 반영되지 않는다.
     *
     * @return 지운 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from NotiRcvrRgn g where g.rcvrId = :rcvrId")
    int deleteAllByRcvrIdInBulk(@Param("rcvrId") Long rcvrId);
}
