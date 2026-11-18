package jeju.datahub.commoncode.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.stereotype.Service;

import jeju.datahub.commoncode.service.CommonCodeService;
import jeju.datahub.commoncode.service.CommonCodeVO;
import lombok.RequiredArgsConstructor;

/**
 * 공통코드 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 */
@Service
@RequiredArgsConstructor
public class CommonCodeServiceImpl extends EgovAbstractServiceImpl implements CommonCodeService {

    private final CommonCodeMapper commonCodeMapper;

    @Override
    public List<CommonCodeVO> selectCommonCodeList() {
        return commonCodeMapper.selectCommonCodeList();
    }

    @Override
    public List<CommonCodeVO> selectCommonCodesByGroup(String groupCd) {
        return commonCodeMapper.selectCommonCodesByGroup(groupCd);
    }
}
