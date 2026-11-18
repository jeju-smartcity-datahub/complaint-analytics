package jeju.datahub.analysis.web;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.analysis.service.HotspotHeatmapResultVO;
import jeju.datahub.analysis.service.HotspotRankingRequest;
import jeju.datahub.analysis.service.HotspotRankingResultVO;
import jeju.datahub.analysis.service.HotspotRankingService;
import lombok.RequiredArgsConstructor;

/**
 * 분석레이어 순위 조회 REST 컨트롤러. ({@code GET /api/hotspots/ranking})
 * TSK-4295 — 같은 필터의 히트맵 칸 조회({@code GET /api/hotspots/heatmap})도 여기 둔다.
 *
 * < 가이드 §0 / §4 결정: 화면(UI-PAR-002) §6 분석레이어 polygon/marker 가 그리는 zone 순위.
 * {@code TB_HTSPT_ANLS_RSLT} 를 view 별로 ORDER BY 한 결과.
 *
 * < 기존 군집 분석 ({@link HotspotAnalysisController} 의 {@code /api/analysis/hotspot})
 * 과 <b>분리</b> — 분석 화면(TSK-4032) 의 가중치·등급·DBSCAN 결과와 의미가 다르며, 같은
 * 컨트롤러에 묶으면 두 화면의 응답이 섞일 위험이 있다. 사용자 follow-up (2026-08-31)
 * 으로 분리 의도 확정.
 *
 * * path prefix 없음 컨벤션 (가이드 §1 결정). 기존 HotspotAnalysisController 의
 *   {@code /api/analysis/hotspot} 와 다른 URL — 의도된 분리.
 */
@Tag(name = "분석레이어 순위",
        description = "민원현황(GIS) §6 분석레이어 polygon/marker 의 zone 순위 — TB_HTSPT_ANLS_RSLT 정렬. "
                + "기존 군집 분석(/api/analysis/hotspot) 과 다른 화면·다른 응답.")
@RestController
@RequestMapping("/api/hotspots")
@RequiredArgsConstructor
public class HotspotRankingController {

    private final HotspotRankingService hotspotRankingService;

    /**
     * 가이드 §4 결정: view=most-complaint|enforcement|shortage.
     * 기존 군집 분석과 다른 화면·다른 응답.
     *
     * <p>가이드 §2 결정 (2026-08-21): {@code unit/startDt·endDt/sggCd/emdCd/cvlcptTypeCd} 필터 받음.
     *
     * <p>2026-09-04 갱신: {@code view=least-complaint} 폐기 — 화면 라디오에서 제거, enum 에서도
     * 제거. 요청 시 400 (HotspotView.fromQueryValue 의 화이트리스트 미스 → IllegalArgumentException).
     * most-complaint 도 §2 필터를 SQL 에 반영 (parking.tb_cvlcpt 직접 집계 + COUNT(*) — 🔁 2026-10-02
     * TSK-4197 로 DISTINCT 기준 폐기. 근거는 mapper/complaint/ComplaintMapper.xml 상단 주석).
     * 2026-09-04 갱신 — 컬럼명 정정 (PR #24 Copilot review).
     *
     * <p>2026-09-04 갱신: {@code startDt/endDt} 둘 다 주어지면 {@code unit} 보다 우선 — 핵심 API
     * ComplaintRequest 와 동일 패턴. 프런트가 임의 기간(예: 08-01 ~ 09-04)을 보내도 YESTERDAY 가
     * 아닌 그 구간으로 집계된다.
     */
    @Operation(summary = "분석레이어 순위 조회",
            description = "view=most-complaint (default) / enforcement / shortage. "
                        + "unit(또는 startDt/endDt 직접 지정)/sggCd/emdCd/cvlcptTypeCd 필터 받음. "
                        + "응답은 {view, unit, zones[]} envelope — zones 는 view별 정렬된 zone 목록.")
    @GetMapping("/ranking")
    public HotspotRankingResultVO ranking(@ParameterObject HotspotRankingRequest request) {
        return hotspotRankingService.rank(request);
    }

    /**
     * TSK-4295 — 민원현황(GIS) 분석레이어 "히트맵" 표시 모드용 좌표 칸 목록.
     *
     * <p>{@code /ranking} 과 같은 요청({@link HotspotRankingRequest})·같은 기간 해석을 쓴다.
     * 좌표를 0.001° 칸으로 반올림해 칸별 건수를 내린다(원점 좌표는 내리지 않는다 — 응답 크기·개별 위치 노출).
     * 🔴 {@code totalCnt} 는 같은 조건 {@code /ranking} 의 건수 합계와 같다(매퍼 WHERE 가 같은 식).
     *
     * <p>{@code view=shortage} 는 400({@code HEATMAP_VIEW_UNSUPPORTED} — 읍면동 PSDR 이라 좌표 단위 값이 없다).
     * 화이트리스트 밖 view 도 {@code /ranking} 과 같은 400. 인가는 다른 조회 GET 과 같이 인증만.
     */
    @Operation(summary = "분석레이어 히트맵 조회",
            description = "view=most-complaint (default) / enforcement. view=shortage 는 400(HEATMAP_VIEW_UNSUPPORTED). "
                        + "필터는 /ranking 과 같다(unit 또는 startDt/endDt, sggCd, emdCd, cvlcptTypeCd). "
                        + "응답은 {view, unit, cellDeg, totalCnt, points[]} — points 는 0.001° 반올림 좌표별 건수. "
                        + "totalCnt 는 같은 조건 /ranking 의 건수 합계와 같다.")
    @GetMapping("/heatmap")
    public HotspotHeatmapResultVO heatmap(@ParameterObject HotspotRankingRequest request) {
        return hotspotRankingService.heatmap(request);
    }
}
