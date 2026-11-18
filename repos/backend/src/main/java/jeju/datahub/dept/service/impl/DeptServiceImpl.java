package jeju.datahub.dept.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.stereotype.Service;

import jeju.datahub.dept.service.DeptService;
import jeju.datahub.dept.service.DeptVO;
import lombok.RequiredArgsConstructor;

/**
 * 부서 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속 (TSK-4183 Step 3).
 */
@Service
@RequiredArgsConstructor
public class DeptServiceImpl extends EgovAbstractServiceImpl implements DeptService {

    private final DeptMapper deptMapper;

    @Override
    public List<DeptVO> selectDepts() {
        return deptMapper.selectDepts();
    }

    @Override
    public List<String> activeDeptCds() {
        return deptMapper.selectDeptCds();
    }
}
