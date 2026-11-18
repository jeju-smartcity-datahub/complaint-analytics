package jeju.datahub.parking.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.parking.service.ParkingLotVO;

/**
 * 공영주차장 매퍼 (MyBatis). XML 네임스페이스:
 * {@code jeju.datahub.parking.service.impl.ParkingLotMapper}.
 */
@Mapper
public interface ParkingLotMapper {

    /**
     * 공영주차장 목록. {@code sggCd} optional.
     * 좌표 있는 행만 반환 (지오코딩 미완료 행 제외 — 지도에 못 놓음).
     */
    List<ParkingLotVO> selectParkingLots(@Param("sggCd") String sggCd);
}