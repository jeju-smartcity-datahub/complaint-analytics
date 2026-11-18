package jeju.datahub.region.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.region.service.RegionService;
import jeju.datahub.region.service.RegionVO;
import lombok.RequiredArgsConstructor;

/**
 * 행정구역 REST 컨트롤러.
 *
 * <p>보고서 설정 폼(TSK-4026 D12)과 알림 화면(TSK-4028 D9)이 <b>같이 쓰는</b> 지역 select 소스다.
 * 어느 화면 도메인에도 속하지 않아 독립 패키지에 둔다.
 */
@Tag(name = "행정구역", description = "시군구 → 읍면동 트리(TB_ADMDST) API")
@RestController
@RequestMapping("/api/regions")
@RequiredArgsConstructor
public class RegionController {

    private final RegionService regionService;

    @Operation(summary = "시군구 → 읍면동 트리",
            description = "지역 select 를 채우는 행정구역 전체 목록. 시군구별로 하위 읍면동 배열을 함께 내린다.")
    @GetMapping
    public List<RegionVO> regions() {
        return regionService.selectRegions();
    }
}
