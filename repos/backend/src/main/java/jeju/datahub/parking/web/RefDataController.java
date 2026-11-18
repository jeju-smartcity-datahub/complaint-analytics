package jeju.datahub.parking.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.parking.service.ParkingLotService;
import jeju.datahub.parking.service.ParkingLotVO;
import lombok.RequiredArgsConstructor;

/**
 * 참조 데이터 REST 컨트롤러.
 *
 * <p>{@code GET /api/parking-lots} 는 단순 마커 표시용 공영주차장 목록.
 * 좌표는 batch 에서 VWorld 지오코딩 완료되어 있어 추가 enrich 단계 불필요.
 *
 * * 클래스를 {@link RefDataController} 로 명명한 이유: 결정 그대로 —
 *   향후 다른 참조 데이터(예: 차량 종류 코드 등) 가 추가될 때 같은 컨트롤러로 확장 가능.
 */
@Tag(name = "참조 데이터", description = "공영주차장 목록 등 단순 참조 데이터 (parking-lots)")
@RestController
@RequestMapping("/api/parking-lots")
@RequiredArgsConstructor
public class RefDataController {

    private final ParkingLotService parkingLotService;

    /**
     * 응답 [{pkltId, pkltNm, lat, lot}].
     * 행정구역 필터는 {@code sggCd} 만 — 공영주차장은 민원·기간과 무관한 정적 자산이라
     * {@code unit} / {@code cvlcptTypeCd} 같은 시간·민원 필터를 받지 않는다.
     */
    @Operation(summary = "공영주차장 목록",
            description = "좌표 있는 주차장만 반환 (lat·lot NOT NULL). sggCd 가 있으면 해당 시군구만, "
                        + "없으면 전체. unit/cvlcptTypeCd 무관 — 정적 자산이라 시간·민원 필터를 받지 않는다.")
    @GetMapping
    public List<ParkingLotVO> parkingLots(@RequestParam(value = "sggCd", required = false) String sggCd) {
        return parkingLotService.selectParkingLots(sggCd);
    }
}