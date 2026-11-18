package jeju.datahub.lifezone.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 구역명 캐시 미리 채우기 대상 격자 매퍼. (TSK-4339 Step 5)
 *
 * <p>🔴 {@code @Mapper} 가 필수다 — {@code MyBatisConfig} 가 이 애노테이션이 붙은 인터페이스만
 * 매퍼 빈으로 스캔한다. 공간연산 + 집계라 MyBatis 담당이다.
 *
 * <p>대상 격자 식·중심 좌표 식은 XML 상단 주석 참고 — 중심 좌표는 {@code LifezoneSupplyMapper.xml} 과 같은 식이다.
 */
@Mapper
public interface ZoneNmPrefillMapper {

    /** 캐시가 없는 대상 격자 중 점(민원+주차장) 많은 순 {@code limit} 개 */
    List<ZoneNmPrefillTargetRow> selectTargets(@Param("gridSz") int gridSz, @Param("limit") int limit);

    /** 대상 격자 수 (캐시 유무 무관) */
    int countTargets(@Param("gridSz") int gridSz);

    /** 캐시가 아직 없는 대상 격자 수 */
    int countRemaining(@Param("gridSz") int gridSz);
}
