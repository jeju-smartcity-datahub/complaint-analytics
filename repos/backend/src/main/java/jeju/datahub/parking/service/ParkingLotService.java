package jeju.datahub.parking.service;

import java.util.List;

import jeju.datahub.parking.web.RefDataController;

/**
 * 공영주차장 참조 데이터 서비스 인터페이스.
 *
 * <p>{@code GET /api/parking-lots} 단순 목록 조회.
 * 좌표는 batch 에서 geocoding 완료되어 있어 추가 enrich 단계 불필요.
 * {@link RefDataController} 가 호출하며, 다른 화면과 공유하지 않는다(공영주차장 마커 전용).
 */
public interface ParkingLotService {

    /**
     * 공영주차장 목록. {@code sggCd} 가 있으면 해당 시군구만, 없으면 전체 반환.
     *
     * @param sggCd 시군구 코드(5자리, optional). null 이면 전체.
     * @return 좌표 있는 주차장 (lat·lot NOT NULL) 만.
     */
    List<ParkingLotVO> selectParkingLots(String sggCd);
}