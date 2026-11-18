package jeju.datahub.commoncode.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.commoncode.service.CommonCodeService;
import jeju.datahub.commoncode.service.CommonCodeVO;
import lombok.RequiredArgsConstructor;

/**
 * 공통코드 조회 REST 컨트롤러. (parking.TB_COM_CD 실데이터)
 */
@Tag(name = "공통코드", description = "공통코드(TB_COM_CD) 조회 API")
@RestController
@RequestMapping("/api/common-codes")
@RequiredArgsConstructor
public class CommonCodeController {

    private final CommonCodeService commonCodeService;

    @Operation(summary = "공통코드 목록", description = "사용중인 공통코드 전체를 조회한다. groupCd 파라미터로 특정 그룹만 필터링 가능.")
    @GetMapping
    public List<CommonCodeVO> list(@RequestParam(name = "groupCd", required = false) String groupCd) {
        if (groupCd != null && !groupCd.isBlank()) {
            return commonCodeService.selectCommonCodesByGroup(groupCd);
        }
        return commonCodeService.selectCommonCodeList();
    }

    @Operation(summary = "그룹별 공통코드", description = "특정 그룹코드에 속한 공통코드를 조회한다.")
    @GetMapping("/{groupCd}")
    public List<CommonCodeVO> byGroup(@PathVariable("groupCd") String groupCd) {
        return commonCodeService.selectCommonCodesByGroup(groupCd);
    }
}
