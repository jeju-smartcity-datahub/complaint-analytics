package jeju.datahub.analysis.service;

/**
 * 분석레이어 순위 조회 서비스 인터페이스.
 *
 * < 가이드 §4 / §6: §6 GIS 지도의 분석레이어 polygon/marker 가 그리는 zone 목록.
 * < 기존 군집 분석 ({@link HotspotAnalysisService}) 과 다른 화면용이며, 동일 컨트롤러에
 * 합치지 않는다 (의미·응답·DB 가 다름).
 */
public interface HotspotRankingService {

    /**
     * view 별 zone 목록 조회.
     *
     * @param req view / unit / sggCd / emdCd / cvlcptTypeCd (현재 view 분기에만 사용)
     * @return view + zone 목록 (없으면 빈 배열)
     */
    HotspotRankingResultVO rank(HotspotRankingRequest req);

    /**
     * 히트맵 칸 목록 조회 (TSK-4295 — {@code GET /api/hotspots/heatmap}).
     *
     * <p>{@link #rank} 와 같은 요청·같은 기간 해석을 쓴다. {@code view=shortage} 는 히트맵 대상이 아니라
     * 400({@code HEATMAP_VIEW_UNSUPPORTED}), 화이트리스트 밖 view 는 {@link #rank} 와 같은 400.
     *
     * @param req view / unit(또는 startDt·endDt) / sggCd / emdCd / cvlcptTypeCd
     * @return view + unit + cellDeg + totalCnt + 칸 목록 (없으면 빈 배열)
     */
    HotspotHeatmapResultVO heatmap(HotspotRankingRequest req);
}
