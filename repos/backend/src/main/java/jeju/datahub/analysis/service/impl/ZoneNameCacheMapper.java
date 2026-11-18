package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 구역명 캐시({@code parking.TB_ZONE_NM_CACHE}) MyBatis 매퍼.
 * SQL 은 {@code resources/mapper/analysis/ZoneNameCacheMapper.xml}.
 *
 * <p>{@code MyBatisConfig} 가 {@code @Mapper} 애노테이션이 붙은 인터페이스만 스캔하므로 애노테이션은 필수다.
 *
 * <h2>⚠️ 쓰기(upsert)까지 MyBatis 인 이유</h2>
 * 영속성 규칙은 "쓰기(CUD)는 JPA" 다. 이 표는 그 예외이며 근거는 셋이다.
 * <ol>
 *   <li><b>멱등성을 SQL 로 보장한다</b> — {@code INSERT ... ON CONFLICT DO UPDATE} 한 문장이다.
 *       JPA {@code save()} 로 같은 것을 하려면 SELECT 후 분기라 <b>왕복이 늘고 경합에 취약</b>하다
 *       (같은 좌표를 동시에 조회하는 요청이 둘이면 PK 충돌이 난다).</li>
 *   <li><b>엔티티 생명주기가 필요 없다</b> — 조회 결과를 수정해 되쓰는 흐름이 없다. 한 번 쓰고
 *       읽기만 하는 캐시라 영속성 컨텍스트·변경 감지가 얻는 게 없다.</li>
 *   <li><b>도메인 데이터가 아니다</b> — 외부 API 응답의 사본이며, 지워도 다음 조회가 다시 채운다.</li>
 * </ol>
 * 같은 사고방식이 배치의 대량 upsert에도 적혀 있다.
 */
@Mapper
public interface ZoneNameCacheMapper {

    /**
     * 좌표키로 캐시 1행. 없으면 {@code null}.
     *
     * <p>{@code zoneNmSrc = 'NOT_FOUND'} 행이 돌아오면 <b>확정 실패의 캐시 히트</b>다 —
     * 외부를 다시 부르지 않고 {@code {읍면동명} 일대} 로 내려간다.
     *
     * @param latKey 위도 소수 5자리 반올림값
     * @param lotKey 경도 소수 5자리 반올림값
     */
    ZoneNameCacheVO selectZoneName(@Param("latKey") BigDecimal latKey,
                                   @Param("lotKey") BigDecimal lotKey);

    /**
     * 캐시 저장(upsert). 같은 좌표키가 있으면 이름·출처·거리·조회일시를 갱신한다.
     *
     * <p>🔴 <b>일시 실패(타임아웃·5xx)는 이 메서드로 오면 안 된다.</b> 저장하는 순간 다음 조회가
     * 잘못된 확정 실패를 재사용한다 — 호출부({@link ZoneNameResolver})가 그 분기를 책임진다.
     */
    void upsertZoneName(ZoneNameCacheVO cache);
}
