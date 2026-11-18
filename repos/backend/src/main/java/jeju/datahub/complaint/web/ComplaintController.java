package jeju.datahub.complaint.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.complaint.service.ComplaintKpiVO;
import jeju.datahub.complaint.service.ComplaintRequest;
import jeju.datahub.complaint.service.ComplaintService;
import lombok.RequiredArgsConstructor;

/**
 * 민원현황(GIS) REST 컨트롤러. (parking.TB_CVLCPT 실데이터)
 *
 * <p>가이드 §1 결정: 핵심 API 단일 = {@code GET /api/complaints/gis}. §1·§3(총)·§7·§8 이 공유한다.
 * 응답은 flat 구조 (래퍼 없음). 컴포넌트 1·3·7·8 이 전부 이 API 하나만 사용한다.
 *
 * * Dashboard 의 {@code /api/dashboard/kpi} 와 공유하지 않는다(별도 페이지 — 가이드 §1.1 주의).
 * * 가이드 §0 결정: API 도메인 = https://api.jeju-parking.go.kr. path prefix 없음 컨벤션.
 */
@Tag(name = "민원현황(GIS)", description = "민원현황 화면(UI-PAR-002) 핵심 집계 API")
@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaintService;

    /**
     * §1 핵심 API. §3 KPI 5필드 응답. §7 emdCompare[] / §8 negEmdCompare[] / §6 heatmapData[] 도
     * 같은 응답으로 동봉되지만, 본 라운드는 §3 KPI 5필드만 노출 — 후속 라운드에서 추가.
     *
     * <p>기간 필터:
     * <ul>
     *   <li>{@code unit} = {@code YESTERDAY/WEEK/MONTH} (default {@code YESTERDAY})</li>
     *   <li>{@code startDt} + {@code endDt} 둘 다 주어지면 {@code unit} 무시 (가이드 §1)</li>
     * </ul>
     * 행정 필터: {@code sggCd} (5자리, optional), {@code emdCd} (8자리, optional — sggCd보다 우선).
     * 민원유형: {@code cvlcptTypeCd} (01~05, optional — 본 화면 프런트는 호출 안 함, 가이드 §2.1 결정).
     */
    @Operation(summary = "민원현황 GIS 핵심 조회",
            description = "lastSyncDt / totalCount / negativityRatio / sentimentDist / enforcementCount / shortageCount flat 응답. "
                        + "필터 4종(unit, startDt/endDt, sggCd, emdCd, cvlcptTypeCd) 전부 AND 결합.")
    @GetMapping("/gis")
    public ComplaintKpiVO getGis(ComplaintRequest request) {
        return complaintService.getKpi(request);
    }
}