package jeju.datahub.complaint.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.complaint.service.ComplaintDashboardService;
import jeju.datahub.complaint.service.DashboardKpiVO;
import jeju.datahub.complaint.service.DashboardRequest;
import jeju.datahub.complaint.service.DashboardTrendVO;
import jeju.datahub.complaint.service.DataStatusVO;
import jeju.datahub.complaint.service.RegionRankVO;
import lombok.RequiredArgsConstructor;

/**
 * 민원 대시보드 REST 컨트롤러. (parking.TB_CVLCPT 실데이터)
 *
 * <p>집계 API 3종(kpi·regions·trend)을 제공한다. 집중구역(hotspots)은 {@code DashboardPanelController} 담당.
 * 공통 요청 파라미터는 {@link DashboardRequest} 로 바인딩한다(unit 은 enum → 화이트리스트 밖 값이면 400).
 */
@Tag(name = "민원 대시보드", description = "민원(TB_CVLCPT) 대시보드 집계 API")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class ComplaintDashboardController {

    private final ComplaintDashboardService complaintDashboardService;

    /**
     * 스모크/임시 엔드포인트. 매퍼 빈 등록·DB 연결·계층 배선이 정상인지 확인하기 위한 용도다.
     */
    @Operation(summary = "스모크 핑", description = "[스모크/임시] 민원 총건수를 반환한다.")
    @GetMapping("/ping")
    public Map<String, Long> ping() {
        return Map.of("count", complaintDashboardService.countAllComplaints());
    }

    /**
     * KPI 카드 집계. 현재 기간 총 민원수·직전 동일 길이 기간 총건수·증감률(%)을 반환한다.
     * "불법주차 발생" 카드는 total 과 같은 모수라 별도 필드가 없다(프론트가 total/deltaPct 재사용).
     * "위험 단계 발생" 카드는 별도 테이블(TB_RISK_STP_NOTI_HSTRY)을 집계해 riskAlert 로 함께 내린다.
     *
     * <p>⚠️ 필터 적용 대상이 갈린다 — typeCd 는 민원 집계에만, riskLvlCd 는 알림 집계에만
     * 걸린다. 기간·sggCd 만 양쪽 공통이다.
     */
    @Operation(summary = "KPI 집계",
            description = "현재/직전 기간 총 민원수와 증감률 + 위험단계 알림 집계(riskAlert). "
                        + "unit(TODAY/YESTERDAY/WEEK/MONTH) 또는 startDt·endDt 로 기간 지정. "
                        + "typeCd 는 민원 집계에만, riskLvlCd 는 riskAlert 에만 적용된다.")
    @GetMapping("/kpi")
    public DashboardKpiVO kpi(DashboardRequest request) {
        return complaintDashboardService.getKpi(request);
    }

    /**
     * 읍면동 순위. 읍면동별 건수(내림차순)·전기 대비 증감률·유형별 분해를 반환한다.
     * 전체 읍면동을 반환하며 상위 N(시안 5카드)은 프론트가 slice 한다.
     */
    @Operation(summary = "읍면동 순위",
            description = "읍면동별 민원 건수 내림차순 + 유형별 분해 + 전기 대비 증감률. 파라미터는 kpi 와 동일.")
    @GetMapping("/regions")
    public List<RegionRankVO> regions(DashboardRequest request) {
        return complaintDashboardService.getRegionRanks(request);
    }

    /**
     * 추이 차트. 요청 구간을 버킷으로 접어 x축 칸별 유형 분해를 반환한다.
     * 빈 칸도 {@code total=0} 으로 포함하므로 프론트는 받은 순서대로 그리기만 하면 된다.
     */
    @Operation(summary = "민원 발생 추이",
            description = "구간을 시간대(24칸, 같은 시각 합산) 또는 일자로 접어 유형별 건수를 반환한다. "
                    + "bucket 생략 시 항상 HOUR(24칸). 날짜순 시계열은 bucket=DAY 를 명시. 나머지 파라미터는 kpi 와 동일.")
    @GetMapping("/trend")
    public DashboardTrendVO trend(DashboardRequest request) {
        return complaintDashboardService.getTrend(request);
    }

    /**
     * 데이터 현황. 화면 우상단 "최종 업데이트" 표기용이라 <b>파라미터를 받지 않는다</b>(전역 지표).
     * 최종 발생일시는 {@code MAX(tb_cvlcpt.ocrn_dt)} 다.
     */
    @Operation(summary = "데이터 현황",
            description = "민원(TB_CVLCPT) 최종 발생일시와 총건수. 대시보드 상단 '최종 업데이트' 표기용이며 필터를 받지 않는다.")
    @GetMapping("/data-status")
    public DataStatusVO dataStatus() {
        return complaintDashboardService.getDataStatus();
    }
}
