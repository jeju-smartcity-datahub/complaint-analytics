package jeju.datahub.dept.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * IdP 그룹 슬러그를 {@code TB_DEPT} 에 자동 등재하는 전용 빈.
 *
 * <h2>🔴 왜 별도 빈인가 — {@code sync()} 안에서 직접 {@code save()} 하면 try/catch 가 무력하다</h2>
 * {@code AuthUserSyncServiceImpl.sync()} 는 {@code @Transactional} 이고 {@link DeptEntity} 는
 * <b>할당 PK(String)</b> 라 {@code save()} 가 {@code merge()} 로 간다 — 즉 <b>INSERT 가
 * flush/commit 시점에</b> 나가고, 그 지점은 이미 {@code try/catch} 범위 밖이다.
 * {@code saveAndFlush} 로 당겨 잡아도 {@code DataIntegrityViolationException} 이 트랜잭션을
 * rollback-only 로 표시해 커밋에서 {@code UnexpectedRollbackException} 이 터진다(= 로그인 500).
 * 삼키려면 <b>경계가 달라야</b> 하므로 {@link Propagation#REQUIRES_NEW} 로 새 트랜잭션에서 쓰고
 * 즉시 커밋한다.
 *
 * <h2>⚠️ 같은 클래스의 메서드에 어노테이션만 붙이면 안 된다</h2>
 * {@code @Transactional} 은 스프링 프록시가 거는 것이라 <b>자기호출은 프록시를 타지 않아</b>
 * 전파 속성이 통째로 무시된다. 그래서 반드시 별도 빈이며 호출부가 이 빈을 주입받는다.
 * 선례는 {@code ZoneNameCacheWriter}(analysis) · {@code LgnHstryServiceImpl} 이다 —
 * 둘 다 <b>별도 빈 + REQUIRES_NEW + 경계 바깥 WARN</b> 세트다.
 *
 * <h2>예외를 여기서 삼키지 않는 이유</h2>
 * 경계 <b>안쪽</b>에서 잡으면 삼킨 뒤 커밋을 시도하다 다시 터진다. 그래서 {@link #register(String)} 은
 * 예외를 <b>그대로 던지고</b>, 경계 바깥인 {@code AuthUserSyncServiceImpl} 이 WARN 으로 삼킨다.
 *
 * <h2>🔴 조회 창구({@link #exists(String)})도 이 빈이 갖는다</h2>
 * 등재 전체({@code existsById} + {@code save})가 여기로 들어오므로 호출부에는 catch 에서 쓸 조회
 * 수단이 없어진다. {@link DeptRepository} 를 따로 주입하지 않고 <b>이 빈 하나만</b> 주입해
 * {@code auth} → {@code dept} 접점을 1개로 유지한다.
 */
@Component
public class DeptRegistrar {

    /** 레포 관례 ({@code AuthUserSyncServiceImpl} 과 같은 값) */
    private static final String SYSTEM_USER_ID = "system";

    private final DeptRepository deptRepository;

    public DeptRegistrar(DeptRepository deptRepository) {
        this.deptRepository = deptRepository;
    }

    /**
     * 미등재 부서코드를 <b>독립 트랜잭션</b>에서 등재하고 즉시 커밋한다.
     *
     * <p>🔴 {@code dept_nm} 초기값은 슬러그와 같다. {@code sort_seq} 는 {@code null} 이다 —
     * 목록이 {@code NULLS LAST} 로 정렬하므로 수동 등록분 뒤에 붙는다.
     *
     * <p>🔴 이미 있으면 <b>아무것도 하지 않는다</b> — 사후 보정한 {@code dept_nm} 을 덮지 않기 위한
     * 가드다(검증).
     *
     * <p>🔴 예외를 삼키지 않는다 — 경계 바깥의 호출부가 WARN 으로 처리한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void register(String deptCd) {
        if (deptRepository.existsById(deptCd)) {
            return;
        }
        deptRepository.save(new DeptEntity(
                deptCd, deptCd, null, "Y", SYSTEM_USER_ID, LocalDateTime.now()));
    }

    /**
     * 부서코드 존재 여부. 🔴 {@code register()} 실패 후 <b>경합 상대가 먼저 넣었는지</b> 확인하는 용도다
     * ({@code existsById} + {@code save} 는 원자적이지 않아 같은 신규 그룹의 동시 최초 로그인이
     * 실제 도달 경로다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean exists(String deptCd) {
        return deptRepository.existsById(deptCd);
    }
}
