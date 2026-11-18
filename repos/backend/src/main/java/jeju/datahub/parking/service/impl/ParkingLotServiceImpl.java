package jeju.datahub.parking.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.stereotype.Service;

import jeju.datahub.parking.service.ParkingLotService;
import jeju.datahub.parking.service.ParkingLotVO;
import lombok.RequiredArgsConstructor;

/**
 * 공영주차장 참조 데이터 서비스 구현체. eGovFrame {@link EgovAbstractServiceImpl} 상속 (기존 패턴).
 *
 * <p>단순 SELECT 위임 — 비즈니스 로직 없음. 좌표 NULL 행 제외는 매퍼 XML 의 {@code WHERE} 에서 처리.
 */
@Service
@RequiredArgsConstructor
public class ParkingLotServiceImpl extends EgovAbstractServiceImpl implements ParkingLotService {

    private final ParkingLotMapper parkingLotMapper;

    @Override
    public List<ParkingLotVO> selectParkingLots(String sggCd) {
        return parkingLotMapper.selectParkingLots(sggCd);
    }
}