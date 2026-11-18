package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.service.CvlcptTypeCntVO;
import jeju.datahub.analysis.service.EnforcementTopMthdRow;
import jeju.datahub.analysis.service.HotspotAnalysisService;
import jeju.datahub.analysis.service.HotspotHeatmapPointVO;
import jeju.datahub.analysis.service.HotspotHeatmapResultVO;
import jeju.datahub.analysis.service.HotspotRankingRequest;
import jeju.datahub.analysis.service.HotspotRankingResultVO;
import jeju.datahub.analysis.service.HotspotRankingService;
import jeju.datahub.analysis.service.HotspotRankingZoneVO;
import jeju.datahub.analysis.service.HotspotView;
import jeju.datahub.complaint.service.ComplaintUnit;
import jeju.datahub.region.service.impl.AdmdstBndryGeoVO;
import jeju.datahub.region.service.impl.AdminDistrictMapper;
import lombok.RequiredArgsConstructor;

/**
 * 분석레이어 순위 조회 서비스 구현체. eGovFrame {@link EgovAbstractServiceImpl} 상속 (기존 패턴).
 *
 * <p>view enum → 매퍼 SELECT 분기. SQL 인젝션 방어: enum 만 받고 SQL 에 닿지 않음.
 * 기존 군집 분석 ({@link HotspotAnalysisService}) 과 독립 — 응답 envelope·필드·DB 가 모두 다르다.
 *
 * <p><b>2026-09-04 — view=shortage 복원 (Jenks DB 즉시 계산)</b>:
 * 사용자 결정("폐기" 가 아니라 "옛 정의를 적용하지 않는다" + Jenks 통계 기법 DB 즉시 계산)에 따라
 * {@code view=shortage} 분기 복원. Jenks k=3 → "심각" 등급 zone 을 ranking 응답으로 반환했다.
 *
 * <p>🔁 <b>2026-10-02 — 등급 필터를 걷었다.</b> 매퍼가
 * {@code fn_jenks_shortage(…, NULL::text)} 로 불러 <b>주차민원 &gt; 0 인 전 zone</b> 을 받는다 —
 * 🔴 {@code out_grade} 를 더 이상 읽지 않는다. 등급은 화면의 {@code pickRiskGrade} 백분위
 * <b>한 층</b>뿐이다(종전엔 Jenks 등급 안에서 다시 백분위를 매기는 2단이라 "안 보이는 이유" 가
 * 두 가지였다). 함수 자체는 PSDR·공급면수·민원수 계산 때문에 계속 쓴다.
 */
@Service
@RequiredArgsConstructor
public class HotspotRankingServiceImpl extends EgovAbstractServiceImpl implements HotspotRankingService {

    private static final Logger log = LoggerFactory.getLogger(HotspotRankingServiceImpl.class);

    private final HotspotRankingMapper hotspotRankingMapper;

    // 2026-09-08 추가 — shortage view 의 polygon 매핑용 admdst 매퍼.
    // most-complaint / enforcement 는 매퍼 SQL 에서 admdst LEFT JOIN 으로 동봉하므로
    // 이 매퍼는 shortage 분기에서만 호출. fn_jenks_shortage 함수 시그니처 보존 결정의 핵심.
    private final AdminDistrictMapper adminDistrictMapper;

    /** 히트맵 칸 크기(도) — 매퍼의 {@code ROUND(…, 3)} 과 짝이다(0.001° ≈ 위도 111m · 경도 93m). */
    private static final BigDecimal HEATMAP_CELL_DEG = new BigDecimal("0.001");

    @Override
    public HotspotRankingResultVO rank(HotspotRankingRequest req) {
        HotspotView view = parseView(req);
        String sggCd = req.getSggCd();
        String emdCd = req.getEmdCd();
        String cvlcptTypeCd = req.getCvlcptTypeCd();
        LocalDate[] range = resolveRange(req);
        // 🔴 2026-10-02 직전 동일 길이 구간. 식을 여기서 다시 쓰지 않고
        //   ComplaintUnit 의 공용 헬퍼를 부른다(민원 KPI §7·§8 과 같은 정의여야 증감이 서로 설명된다).
        //   ⚠️ MOST_COMPLAINT 분기에는 넘기지 않는다 — 민원다발에는 증감 필드가 없다.
        LocalDate prevStart = ComplaintUnit.prevStart(range[0], range[1]);
        LocalDate prevEnd   = range[0].minusDays(1);

        List<HotspotRankingZoneVO> zones = switch (view) {
            case MOST_COMPLAINT  -> {
                // 2026-09-21 — 팝업용 민원유형별 건수. zone 1행에 유형 N행을 jsonb_agg 로
                // 뭉치지 않고 별도 매퍼로 한 번 조회해 emdCd 로 in-memory 매핑한다(shortage 의
                // bndryGeoJson 과 같은 패턴 — N+1 회피). 매핑 없는 zone 은 VO 기본값(빈 목록) 유지.
                List<HotspotRankingZoneVO> rows = hotspotRankingMapper.selectMostComplaint(
                        range[0], range[1], sggCd, emdCd, cvlcptTypeCd);
                if (rows == null || rows.isEmpty()) {
                    yield List.of();
                }
                // groupingBy + toList 는 원래 순서(emdCd, sort_seq)를 유지한다.
                Map<String, List<CvlcptTypeCntVO>> typeCntsByEmdCd = hotspotRankingMapper
                        .selectTypeBreakdown(range[0], range[1], sggCd, emdCd, cvlcptTypeCd)
                        .stream()
                        .collect(Collectors.groupingBy(CvlcptTypeCntVO::getEmdCd, Collectors.toList()));
                for (HotspotRankingZoneVO z : rows) {
                    List<CvlcptTypeCntVO> typeCnts = typeCntsByEmdCd.get(z.getEmdCd());
                    if (typeCnts != null) {
                        z.setTypeCnts(typeCnts);
                    }
                }
                yield rows;
            }
            case ENFORCEMENT     -> {
                // 🔴 2026-10-02 매퍼가 현재/직전 건수를 FILTER 로 갈라 주고,
                //   증감률은 여기서 낸다(민원 §7 emdCompare[].deltaPct 와 같은 규칙 —
                //   직전 0건이면 분모 1 치환 + deltaPctInferred=true).
                List<HotspotRankingZoneVO> rows = hotspotRankingMapper.selectEnforcement(
                        range[0], range[1], prevStart, prevEnd, sggCd, emdCd, cvlcptTypeCd);
                if (rows == null || rows.isEmpty()) {
                    yield List.of();
                }
                // 🔴 현재 구간만 넘긴다(직전 구간을 섞으면 비율이 100% 를 넘는다).
                Map<String, EnforcementTopMthdRow> topByEmd = hotspotRankingMapper
                        .selectEnforcementTopMthd(range[0], range[1], sggCd, emdCd, cvlcptTypeCd).stream()
                        .collect(Collectors.toMap(EnforcementTopMthdRow::emdCd, Function.identity()));
                for (HotspotRankingZoneVO z : rows) {
                    EnforcementTopMthdRow top = topByEmd.get(z.getEmdCd());
                    if (top != null && z.getCrdnCnt() > 0) {
                        z.setTopCrdnMthd(top.crdnMthd());
                        z.setTopCrdnMthdRatio((double) top.mthdCnt() / z.getCrdnCnt());
                    }
                    long prev = z.getPrevCrdnCnt() != null ? z.getPrevCrdnCnt() : 0L;
                    long diff = z.getCrdnCnt() - prev;
                    if (prev == 0) {
                        z.setDeltaPct((int) Math.round(diff / 1.0 * 100));
                        z.setDeltaPctInferred(true);
                    } else {
                        z.setDeltaPct((int) Math.round((double) diff / prev * 100));
                        z.setDeltaPctInferred(false);
                    }
                }
                yield rows;
            }
            case SHORTAGE        -> {
                // 2026-09-08 갱신 — shortage hotspot zone 의 bndryGeoJson 매핑.
                // fn_jenks_shortage 함수 시그니처 보존 결정(사용자 확정) 으로 매퍼 SQL 의
                // bndryGeoJson 컬럼은 placeholder NULL. admdst 매퍼 일괄 조회 후
                // in-memory 매핑 (N+1 회피 — WHERE emd_cd IN (:emdCds)).
                // 🔴 2026-10-02 shortageDeltaPp · deltaPctInferred 는 매퍼 SQL 이 직접 낸다
                //   (직전 분모를 내려보내지 않아 서비스에는 판정할 재료가 없다).
                List<HotspotRankingZoneVO> rows = hotspotRankingMapper.selectShortage(
                        range[0], range[1], prevStart, prevEnd, sggCd, emdCd, cvlcptTypeCd);
                if (rows == null || rows.isEmpty()) {
                    yield List.of();
                }
                List<String> emdCds = rows.stream()
                        .map(HotspotRankingZoneVO::getEmdCd)
                        .collect(Collectors.toList());
                Map<String, String> bndryByEmdCd = adminDistrictMapper.selectBndryGeoJsonByEmdCds(emdCds)
                        .stream()
                        .collect(Collectors.toMap(
                                AdmdstBndryGeoVO::getEmdCd,
                                v -> v.getBndryGeoJson() == null ? "" : v.getBndryGeoJson(),
                                (a, b) -> a));
                for (HotspotRankingZoneVO z : rows) {
                    String geo = bndryByEmdCd.get(z.getEmdCd());
                    // polygon 이 비어있거나 admdst 미등록이면 null 로 둠 — fallback 마커 대상.
                    if (geo != null && !geo.isEmpty()) {
                        z.setBndryGeoJson(geo);
                    }
                }
                yield rows;
            }
        };

        HotspotRankingResultVO vo = new HotspotRankingResultVO();
        vo.setView(view.queryValue());
        vo.setUnit(echoUnit(req));
        vo.setZones(zones);
        return vo;
    }

    /**
     * 히트맵 칸 목록. 기간 해석·unit echo·view 파싱은 {@link #rank} 와 같은 헬퍼를 쓴다(복제 금지).
     *
     * <p>🔴 {@code prevStart} 를 계산하지 않는다 — 히트맵은 현재 구간만 센다(증감률 없음).
     * 매퍼 WHERE 가 순위 쿼리와 같은 식이라 {@code totalCnt} == 같은 조건 순위 응답의 건수 합계다.
     */
    @Override
    public HotspotHeatmapResultVO heatmap(HotspotRankingRequest req) {
        HotspotView view = parseView(req);
        LocalDate[] range = resolveRange(req);
        String sggCd = req.getSggCd();
        String emdCd = req.getEmdCd();
        String cvlcptTypeCd = req.getCvlcptTypeCd();

        List<HotspotHeatmapPointVO> points = switch (view) {
            case MOST_COMPLAINT -> hotspotRankingMapper.selectComplaintHeatmap(
                    range[0], range[1], sggCd, emdCd, cvlcptTypeCd);
            case ENFORCEMENT    -> hotspotRankingMapper.selectCrdnHeatmap(
                    range[0], range[1], sggCd, emdCd, cvlcptTypeCd);
            // 계획 Q4 — 수요 부족은 좌표 단위 값이 아니라(읍면동 PSDR) 히트맵 대상이 아니다.
            // 전역 예외 핸들러가 없어 reason 문자열은 응답 본문에 실리지 않는다 — 계약은 상태코드 400.
            case SHORTAGE       -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "HEATMAP_VIEW_UNSUPPORTED: view=shortage 는 히트맵 대상이 아니다");
        };
        if (points == null) {
            points = List.of();
        }

        HotspotHeatmapResultVO vo = new HotspotHeatmapResultVO();
        vo.setView(view.queryValue());
        vo.setUnit(echoUnit(req));
        vo.setCellDeg(HEATMAP_CELL_DEG);
        vo.setTotalCnt(points.stream().mapToLong(HotspotHeatmapPointVO::getCnt).sum());
        vo.setPoints(points);
        return vo;
    }

    /**
     * view 는 String 으로 받아 enum 변환한다 — Spring 의 기본 enum 바인딩은
     * MOST_COMPLAINT 같은 enum 상수명만 받아서 API 컨벤션의 kebab-case 와 어긋난다.
     * (직전 라운드의 unit 처리 패턴과 동일.) 화이트리스트 밖 값이면
     * ResponseStatusException(400) 으로 매핑 — 전역 핸들러 부재 컨벤션 (RiskAlertController 동일).
     * {@link #rank}·{@link #heatmap} 공용으로 추출.
     */
    private static HotspotView parseView(HotspotRankingRequest req) {
        try {
            return HotspotView.fromQueryValue(req.getView());
        } catch (IllegalArgumentException e) {
            // 🔴 응답 사유는 고정 문구 — 예외 메시지(요청값 포함)는 서버 로그에만(시큐어코딩 4.1)
            log.info("[hotspot] view 거절(400) — {}", e.getClass().getSimpleName());
            log.debug("[hotspot] view 거절(400) 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "view 값이 올바르지 않습니다.", e);
        }
    }

    /**
     * 조회 구간 {@code {startDt, endDt}}.
     * 2026-09-04 갱신: unit → startDt/endDt 변환은 모든 view 공통 (이전엔 enforcement 한정).
     * 매퍼 시그니처가 startDt/endDt 를 받도록 통일.
     * startDt/endDt 둘 다 주어지면 unit 보다 우선 (핵심 ComplaintServiceImpl 동일 패턴).
     * 둘 다 없으면 unit.resolveRange(now()) 로 폴백 — 기존 YESTERDAY/WEEK/MONTH 분기 호환 유지.
     * {@link #rank}·{@link #heatmap} 공용으로 추출.
     */
    private static LocalDate[] resolveRange(HotspotRankingRequest req) {
        if (req.hasExplicitRange()) {
            return new LocalDate[] { req.getStartDt(), req.getEndDt() };
        }
        ComplaintUnit unit = req.getUnit() != null ? req.getUnit() : ComplaintUnit.YESTERDAY;
        return unit.resolveRange(LocalDate.now());
    }

    /**
     * 응답 envelope 의 unit echo-back (String). 핵심 API 는 별도 필드로 가공하지 않으므로 enum 이름 그대로 사용.
     * 기간 직접 지정 모드면 unit 은 null — 어떤 enum 으로도 환원할 수 없으므로 응답 envelope 도 null.
     * {@link #rank}·{@link #heatmap} 공용으로 추출.
     */
    private static String echoUnit(HotspotRankingRequest req) {
        if (req.hasExplicitRange()) {
            return null;
        }
        return req.getUnit() != null ? req.getUnit().name() : ComplaintUnit.YESTERDAY.name();
    }
}
