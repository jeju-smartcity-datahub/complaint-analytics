package jeju.datahub.commoncode.service;

import java.util.List;

/**
 * 공통코드 서비스 인터페이스.
 */
public interface CommonCodeService {

    /** 사용중인 공통코드 전체 조회 (그룹코드·정렬순서 순) */
    List<CommonCodeVO> selectCommonCodeList();

    /** 특정 그룹의 공통코드 조회 */
    List<CommonCodeVO> selectCommonCodesByGroup(String groupCd);
}
