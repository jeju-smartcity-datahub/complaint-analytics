package jeju.datahub.commoncode.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.commoncode.service.CommonCodeVO;

/**
 * 공통코드 MyBatis 매퍼. SQL 은 resources/mapper/commoncode/CommonCodeMapper.xml.
 */
@Mapper
public interface CommonCodeMapper {

    List<CommonCodeVO> selectCommonCodeList();

    List<CommonCodeVO> selectCommonCodesByGroup(@Param("groupCd") String groupCd);
}
