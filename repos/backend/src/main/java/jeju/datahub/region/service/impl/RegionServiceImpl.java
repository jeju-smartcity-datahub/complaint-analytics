package jeju.datahub.region.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.stereotype.Service;

import jeju.datahub.region.service.RegionService;
import jeju.datahub.region.service.RegionVO;
import lombok.RequiredArgsConstructor;

/**
 * 행정구역 조회 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 */
@Service
@RequiredArgsConstructor
public class RegionServiceImpl extends EgovAbstractServiceImpl implements RegionService {

    private final RegionMapper regionMapper;

    @Override
    public List<RegionVO> selectRegions() {
        return regionMapper.selectRegionTree();
    }
}
