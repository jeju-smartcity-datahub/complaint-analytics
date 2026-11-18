package jeju.datahub.region.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import jeju.datahub.region.service.RegionVO;

/**
 * 행정구역 MyBatis 매퍼. SQL 은 resources/mapper/region/RegionMapper.xml.
 *
 * <p>{@code MyBatisConfig} 가 {@code @Mapper} 애노테이션이 붙은 인터페이스만 스캔하므로 애노테이션은 필수다.
 */
@Mapper
public interface RegionMapper {

    /** 시군구 → 읍면동 트리 전체 (읍면동코드 오름차순) */
    List<RegionVO> selectRegionTree();
}
