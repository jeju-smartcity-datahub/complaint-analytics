package jeju.datahub.risknoti.service.impl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 수신자 × 위험단계 매핑 JPA 리포지터리. (쓰기 전용 — 등록 INSERT · 수정 시 통째 교체)
 *
 * <p>PK 는 복합키 {@link NotiRcvrLvlId} 다 ({@code rcvr_id} + {@code risk_lvl_cd}).
 *
 * <p>🔁 TSK-4250 R10 — 수정 API({@code PUT /api/noti-receivers/{rcvrId}})가 생겨 '전체 교체'(지우고 다시 넣기)
 * 호출부가 생겼다. 종전 TSK-4028 D5 의 <i>"수정 API 범위 밖 · {@code deleteByRcvrId} 를 만들지 않는다"</i> 는
 * 폐기됐다. 단 🔴 <b>파생 {@code deleteByRcvrId} 는 여전히 만들지 않는다</b> — 아래 벌크 삭제를 쓴다
 * ({@link #deleteAllByRcvrIdInBulk} 참고). 수신자 <b>삭제</b>는 여전히 소프트 삭제라 매핑을 지우지 않는다.
 *
 * <p>{@code MyBatisConfig} 는 {@code @Mapper} 가 붙은 인터페이스만 스캔하므로
 * 이 인터페이스가 MyBatis 매퍼로 잘못 등록되지 않는다.
 */
public interface NotiRcvrLvlRepository extends JpaRepository<NotiRcvrLvl, NotiRcvrLvlId> {

    /**
     * 한 수신자의 단계 매핑을 <b>벌크 JPQL DELETE</b> 로 즉시 지운다 (TSK-4250 R10 — 수정 시 통째 교체).
     *
     * <p>🔴 <b>파생 {@code deleteByRcvrId}·{@code deleteAll(entities)} 를 쓰지 않는 이유</b> — 그것들은
     * {@code em.remove} 라 DELETE 가 flush 까지 미뤄지고, Hibernate 는 flush 때 <b>INSERT 를 DELETE 보다 먼저</b>
     * 낸다. 이 엔티티는 할당형 복합 PK({@code Persistable} 아님)라 {@code save()}=merge 이므로, 유지되는 단계를
     * 다시 넣으면 방금 remove 한 같은 키에서 삭제 인스턴스 merge 오류 또는 PK 위반이 난다.
     *
     * <p>벌크 DELETE 는 영속성 컨텍스트를 거치지 않으므로 {@code flushAutomatically}(앞선 변경을 먼저 반영)와
     * {@code clearAutomatically}(삭제된 행이 컨텍스트에 남지 않게)를 함께 건다.
     * ⚠️ 호출 뒤에는 그 전에 읽은 엔티티가 모두 detached 다 — 이후 수정해도 반영되지 않는다.
     *
     * @return 지운 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from NotiRcvrLvl l where l.rcvrId = :rcvrId")
    int deleteAllByRcvrIdInBulk(@Param("rcvrId") Long rcvrId);
}
