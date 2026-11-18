package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구역명 캐시 쓰기 전용 빈. 저장을 <b>부모 트랜잭션과 분리</b>하는 것이 유일한 존재 이유다.
 *
 * <h2>🔴 왜 별도 빈인가 — 읽기전용 트랜잭션 안의 INSERT 가 조회 전체를 죽인다</h2>
 * 집중구역 분석 서비스는 조회 전용이라 {@code @Transactional(readOnly = true)} 로 표명한다(D7).
 * 그 경계 안에서 캐시 upsert 를 그대로 실행하면 이렇게 번진다(실측).
 * <ol>
 *   <li>{@code PSQLException: cannot execute INSERT in a read-only transaction} (SQLState <b>25006</b>)</li>
 *   <li>그 실패가 <b>PostgreSQL 트랜잭션을 abort</b> 시켜, 뒤따르는 모든 문장이
 *       {@code 25P02: current transaction is aborted} 로 죽는다</li>
 *   <li>{@link ZoneNameResolver} 가 쓰기 예외를 삼켜도 <b>이미 늦다</b> — 같은 트랜잭션의 다음 조회
 *       (예: {@code selectDataRange()})가 터져 API 가 500 을 낸다</li>
 * </ol>
 * 그래서 {@link Propagation#REQUIRES_NEW} 로 <b>새 트랜잭션·새 커넥션</b>에서 쓰고 즉시 커밋한다.
 * 부모는 읽기전용인 채로 남고, 쓰기가 실패해도 부모가 오염되지 않는다.
 * 배치 규칙({@code rule_batch-project.md})의 "스킵 기록은 청크 롤백에 휩쓸리지 않도록
 * {@code REQUIRES_NEW} 로 즉시 커밋한다"와 같은 사고방식이다 — <b>기록의 생명주기를 본작업에서 떼어낸다.</b>
 *
 * <h2>⚠️ 같은 클래스 안의 메서드로 두면 안 된다</h2>
 * {@code @Transactional} 은 스프링 프록시가 거는 것이라 <b>자기호출(self-invocation)은 프록시를 타지
 * 않는다.</b> {@link ZoneNameResolver} 안에 어노테이션만 붙인 메서드를 두면 전파 속성이 통째로
 * 무시돼 위의 25006 → 25P02 경로가 그대로 재현된다. 그래서 반드시 별도 빈이며,
 * {@link ZoneNameResolver} 가 이 빈을 주입받아 호출한다.
 *
 * <h2>예외를 여기서 삼키지 않는 이유</h2>
 * 실패를 이 메서드 안에서 잡으면 <b>트랜잭션 경계 안쪽</b>이라, 삼킨 뒤 커밋을 시도하다 다시 터진다.
 * 그래서 이 메서드는 예외를 그대로 던지고, <b>경계 바깥</b>인 {@link ZoneNameResolver} 가 WARN 으로
 * 삼킨다(별개 빈이므로 호출 시점에 이미 프록시를 빠져나온 상태다).
 */
@Component
public class ZoneNameCacheWriter {

    private final ZoneNameCacheMapper cacheMapper;

    public ZoneNameCacheWriter(ZoneNameCacheMapper cacheMapper) {
        this.cacheMapper = cacheMapper;
    }

    /**
     * 캐시 1행 저장(upsert)을 <b>독립 트랜잭션</b>에서 수행하고 즉시 커밋한다.
     *
     * @param zoneNm {@code null} 이면 확정 실패({@code src = NOT_FOUND}) 기록이다
     * @param distM  POI 채택 시 중심점까지 거리(m). 그 외에는 {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(BigDecimal latKey, BigDecimal lotKey, String zoneNm, String src, Integer distM) {
        ZoneNameCacheVO row = new ZoneNameCacheVO();
        row.setLatKey(latKey);
        row.setLotKey(lotKey);
        row.setZoneNm(zoneNm);
        row.setZoneNmSrc(src);
        row.setPoiDistM(distM);
        cacheMapper.upsertZoneName(row);
    }
}
