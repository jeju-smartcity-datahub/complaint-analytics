package jeju.datahub.dashboard.service.impl;

import org.springframework.transaction.TransactionException;
import org.springframework.dao.DataAccessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jeju.datahub.analysis.service.HotspotAnalysisRequest;
import jeju.datahub.analysis.service.HotspotAnalysisResultVO;
import jeju.datahub.analysis.service.HotspotAnalysisService;
import jeju.datahub.analysis.service.HotspotZoneVO;
import jeju.datahub.complaint.service.ComplaintDashboardService;
import jeju.datahub.complaint.service.DataStatusVO;
import jeju.datahub.dashboard.service.AnalysisVO;
import jeju.datahub.dashboard.service.DashboardPanelService;
import jeju.datahub.dashboard.service.DataFreshnessItemVO;
import jeju.datahub.dashboard.service.DataFreshnessVO;
import jeju.datahub.dashboard.service.HotspotVO;
import jeju.datahub.dashboard.service.ReportVO;
import jeju.datahub.dashboard.service.SuggestionVO;
import lombok.RequiredArgsConstructor;

/**
 * 대시보드 하단/집중구역 카드 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>카드 4종 중 <b>집중구역만</b> 성격이 다르다 — 나머지 3종(분석·리포트·AI 추천)은 단순 조회
 * 위임이지만(로직은 SQL 에 있음), 집중구역은 {@link HotspotAnalysisService} 에 위임하는
 * <b>실시간 군집 분석</b>이다. 자세한 근거는 {@link #getHotspots(int, String)} 참고.
 */
@Service
@RequiredArgsConstructor
public class DashboardPanelServiceImpl extends EgovAbstractServiceImpl implements DashboardPanelService {

    // 🔴 이 클래스엔 로거가 없었다 — `EgovAbstractServiceImpl` 은 `log` 를 주지 않는다.
    //    레포 관례는 `@Slf4j` 가 아니라 LoggerFactory 다(SessionEndListener·AuthController 등 참조).
    private static final Logger log = LoggerFactory.getLogger(DashboardPanelServiceImpl.class);

    /**
     * 집중구역 카드의 분석 기간(일). 기준일을 포함한 최근 {@value} 일 = {@code [기준일-29, 기준일]} 이며,
     * 대시보드 조회단위(어제/주간/월간)와 <b>무관하게 고정</b>이다 — 이유는
     * {@link #getHotspots(int, String)}. 길이는 {@code DashboardUnit.MONTH} 와 같은 30일이지만 기준일이 달라
     * 구간은 다르다(월간은 어제까지다).
     *
     * <p>🔴 기준일은 오늘이 아니라 <b>최신 민원 발생일</b>이다 — {@link #resolveBaseDate()}.
     */
    private static final int HOTSPOT_PERIOD_DAYS = 30;

    /** 분석 서비스의 기간 파라미터 형식(YYYYMMDD). */
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuuMMdd");

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 화면에 세우는 수집 배치 4종. 🔴 순서가 곧 툴팁 표의 행 순서다.
     *
     * <p>⚠️ 주기 문구는 배치 레포 cron 의 <b>복제</b>다 — 레포가 갈려
     * 상수를 공유할 수 없다(보고서 주기 규칙이 양 레포에 중복된 것과 같은 상황).
     * 배치 cron 을 바꾸면 여기도 바꾼다.
     *
     * <p>🔴 단속은 스케줄러가 <b>없다</b>(수동 전용) —
     * {@code 일 1회} 같은 그럴듯한 문구를 지어내지 않는다. 화면이 "매일 갱신된다" 고 거짓을 말하게 된다.
     *
     * <p>🔴 {@code riskAlertJudgeJob}·{@code reportAutoCrtJob} 은 넣지 않는다 — 외부 원천을
     * 수집하는 잡이 아니라 우리 데이터를 가공하는 잡이다.
     */
    // 🔴 이름을 `DataSource` 로 짓지 않는다 — Spring 앱에서 `javax.sql.DataSource` 와 읽는 사람이 헷갈린다.
    private record FreshnessSource(String jobNm, String dataNm, String cyclNm) {}

    private static final List<FreshnessSource> SOURCES = List.of(
            new FreshnessSource("complaintFetchJob", "민원", "일 1회"),
            new FreshnessSource("crackdownFetchJob", "단속", "수동 실행"),
            new FreshnessSource("pblpkltFetchJob", "공영주차장 현황", "분기 1회"),
            new FreshnessSource("pkltUtztnFetchJob", "공영주차장 이용률", "월 1회"));

    private final DashboardPanelMapper dashboardPanelMapper;
    private final HotspotAnalysisService hotspotAnalysisService;

    /** 민원 최신 발생일 조회용. 🔴 {@code dashboard} → {@code complaint} 단방향 참조다(역참조를 만들지 않는다). */
    private final ComplaintDashboardService complaintDashboardService;

    /**
     * {@inheritDoc}
     *
     * <p><b>저장된 분석 결과가 아니라 실시간 군집 분석이다</b>. 예전에는 0행짜리
     * 저장된 분석 결과 테이블을 읽어 카드가 늘 비어 있었다. 지금은 분석 화면과 같은
     * {@link HotspotAnalysisService#analyze(HotspotAnalysisRequest)} 를 그대로 호출하므로
     * <b>카드와 분석 화면이 같은 산식·같은 등급 임계값</b>을 본다(계산을 두 벌 두지 않는다).
     *
     * <h2>기간이 {@value #HOTSPOT_PERIOD_DAYS}일 고정인 이유</h2>
     * 대시보드 KPI·추이는 조회단위(어제/주간/월간)를 따르지만 이 카드는 따르지 <b>않는다</b> —
     * 하루치로는 DBSCAN 최소 점 개수를 못 채워 군집이 0개로 나오기 때문이다(실측). 조회단위를
     * 따르게 하면 "어제"에서 카드가 구조적으로 항상 비므로, 기준일로부터
     * {@code [기준일-29, 기준일]} 을 고정으로 쓴다.
     *
     * <p>🔴 기준일은 오늘이 아니라 <b>최신 민원 발생일</b>이다 — 근거는
     * {@link #resolveBaseDate()}.
     *
     * <p>지역은 {@code sggCd} 를 따른다. <b>미전송·빈 값·미등록 코드일 때만</b>
     * 전 지역이다 — {@link #resolveSggNm(String)} 이 {@code null} 을 돌려주고 분석 서비스가
     * 그것을 "전체"로 읽는다. {@code limit} 은 호출자가 준 값 그대로 넘긴다 —
     * 🔴 <b>카드는 반드시 값을 명시한다</b>(5). 분석 서비스는 {@code limit} 이 {@code null} 이면
     * <b>전량</b>을 돌려주므로(상한이 없어졌다), 비워 두면 카드가 수십 칸을 받는다.
     * 하한만 클램프된다({@code 0}·음수 → 1).
     */
    @Override
    public List<HotspotVO> getHotspots(int limit, String sggCd) {
        LocalDate baseDate = resolveBaseDate();

        HotspotAnalysisRequest request = new HotspotAnalysisRequest();
        request.setBgngYmd(baseDate.minusDays(HOTSPOT_PERIOD_DAYS - 1L).format(YMD));
        request.setEndYmd(baseDate.format(YMD));  // 기준일 포함 — 분석 서비스가 +1일 반열림으로 바꾼다
        request.setLimit(limit);
        // 🔴 분석 서비스는 코드가 아니라 **시군구명**을 받는다(`HotspotAnalysisRequest.sggNm`).
        //    emdCd 는 여전히 미지정(null)이다.
        request.setSggNm(resolveSggNm(sggCd));    // null = 전 지역(종전 동작)

        HotspotAnalysisResultVO result = hotspotAnalysisService.analyze(request);
        return toCards(result == null ? null : result.getZones());
    }

    /**
     * 집중구역 카드의 기준일 — <b>최신 민원 발생일</b>이다(오늘이 아니다).
     *
     * <p>수집이 지연되거나 원천이 과거 구간만 주면 "오늘 기준 30일"에는 민원이 0건이라 카드가
     * 구조적으로 빈다. 화면 부제가 {@code 최근 1개월 기준} 인데 최신 데이터가 그 창 밖에 있으면
     * 문구와 결과가 어긋난다.
     *
     * <p>🔴 <b>민원이 0건이면 오늘로 폴백한다</b> — 빈 DB 에서 NPE 로 500 을 내지 않는다.
     * 결과는 어차피 빈 배열이고, 프론트는 기존 {@code 데이터 없음} 표시를 그대로 쓴다.
     *
     * <p>⚠️ 조회 실패도 오늘로 폴백한다(로그 WARN 1줄). 카드 하나 때문에 대시보드를 죽이지 않는다.
     */
    private LocalDate resolveBaseDate() {
        try {
            DataStatusVO status = complaintDashboardService.getDataStatus();
            LocalDateTime last = status == null ? null : status.getLastOcrnDt();
            return last == null ? LocalDate.now(SEOUL) : last.toLocalDate();
        } catch (DataAccessException | TransactionException | IllegalStateException e) { // 4.3: DB 쿼리·트랜잭션(REQUIRES_NEW 커밋 포함) 실패 · 상태 오류(ISE)
            // 기준일을 못 구해도 카드는 그려야 한다 — 오늘로 떨어뜨리고 사실만 남긴다.
            log.warn("집중구역 기준일 조회 실패 — 오늘로 폴백한다: {}", e.getClass().getSimpleName());
            log.debug("집중구역 기준일 조회 실패 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만
            return LocalDate.now(SEOUL);
        }
    }

    /**
     * 시군구코드(5자리) → 시군구명. 분석 서비스가 코드가 아니라 이름을 받으므로 옮긴다.
     *
     * <p>🔴 <b>빈 문자열은 {@code null} 로 접는다</b>({@code anlsTypeCd} 와 같은 규약) — 그냥
     * 내려가면 쿼리가 0행을 돌려주고, 그게 다시 "전 지역"이라 조용히 맞는 것처럼 보인다.
     *
     * <p>⚠️ 미등록 코드면 조회 결과가 없어 {@code null} = 전 지역이다(예외가 아니다).
     * 화면 셀렉트가 2개뿐이라 실질 위험은 없지만, 넓힐 때는 검증을 붙인다.
     *
     * @return 시군구명. {@code sggCd} 미전송·빈 값·미등록 코드면 {@code null}(= 전 지역).
     */
    private String resolveSggNm(String sggCd) {
        if (sggCd == null || sggCd.isBlank()) {
            return null;
        }
        return dashboardPanelMapper.selectSggNm(sggCd);
    }

    /**
     * 분석 결과 구역 → 카드 VO 매핑. 분석 응답에서 카드가 쓰는 필드만 옮긴다
     * (구역명 출처{@code zoneNmSrc}·읍면동명은 카드에 표시하지 않는다).
     *
     * <p>결과 0건은 오류가 아니라 빈 목록이다 — 조회 기간에 데이터가 없거나 군집이
     * 형성되지 않은 정상 상태이며, 프론트는 기존 "데이터 없음" 표시를 그대로 쓴다.
     */
    private static List<HotspotVO> toCards(List<HotspotZoneVO> zones) {
        if (zones == null || zones.isEmpty()) {
            return List.of();
        }
        List<HotspotVO> cards = new ArrayList<>(zones.size());
        for (HotspotZoneVO zone : zones) {
            HotspotVO card = new HotspotVO();
            card.setRnk(zone.getRnk());
            card.setZoneNm(zone.getZoneNm());
            card.setCvlcptCnt(zone.getCvlcptCnt());
            card.setCrdnCnt(zone.getCrdnCnt());
            card.setSnthsScr(zone.getSnthsScr());
            card.setGrade(zone.getGrade());
            card.setGradeNm(zone.getGradeNm());
            card.setMrkLat(zone.getMrkLat());
            card.setMrkLot(zone.getMrkLot());
            cards.add(card);
        }
        return cards;
    }

    @Override
    public List<AnalysisVO> getRecentAnalyses(int limit, String anlsTypeCd, String userId) {
        // 빈 문자열이 그대로 내려가면 `= ''` 로 걸려 항상 0건이 된다.
        String typeCd = (anlsTypeCd == null || anlsTypeCd.isBlank()) ? null : anlsTypeCd;
        return dashboardPanelMapper.selectRecentAnalyses(limit, typeCd, userId);
    }

    @Override
    public List<ReportVO> getRecentReports(int limit, String crtType, String ownerId) {
        // 🔴 빈 문자열이 그대로 내려가면 `crt_type = ''` 로 걸려 항상 0건이 된다.
        //    위 anlsTypeCd 와 같은 수법으로 null 로 접어 "전체" 로 떨어뜨린다.
        String type = (crtType == null || crtType.isBlank()) ? null : crtType;
        // 🔴 매퍼가 ownerWhereRowwise 로 "남의 것" 만 뺀다(AUTO 는 배포 부서·ALL·보고서 관리 메뉴 보유자)
        return dashboardPanelMapper.selectRecentReports(limit, type, ownerId);
    }

    @Override
    public List<SuggestionVO> getAiSuggestions(int limit) {
        return dashboardPanelMapper.selectAiSuggestions(limit);
    }

    /**
     * {@inheritDoc}
     *
     * <p>SQL 은 돈 잡만 돌려주므로 여기서 {@link #SOURCES} 순서로 <b>4행을 고정</b>으로 세우고,
     * 없는 잡은 {@code lastExcnDt = null} 로 채운다. {@code latestDt} 는 null 이 아닌 값 중
     * 최댓값이며 전부 null 이면 null 이다.
     *
     * <p>⚠️ 조회 실패는 삼키지 않는다 — 전용 엔드포인트라 500 이 나도 프론트가 {@code —} 로
     * 떨어뜨려 화면을 지킨다(집중구역 기준일처럼 다른 카드를 끌고 죽지 않는다).
     */
    @Override
    public DataFreshnessVO getDataFreshness() {
        List<String> jobNms = SOURCES.stream().map(FreshnessSource::jobNm).toList();

        Map<String, LocalDateTime> lastByJob = new LinkedHashMap<>();
        for (DataFreshnessItemVO row : dashboardPanelMapper.selectDataFreshness(jobNms)) {
            lastByJob.put(row.getJobNm(), row.getLastExcnDt());
        }

        List<DataFreshnessItemVO> items = new ArrayList<>(SOURCES.size());
        LocalDateTime latest = null;
        for (FreshnessSource src : SOURCES) {
            LocalDateTime last = lastByJob.get(src.jobNm());   // 안 돈 잡이면 null — 행은 남긴다

            DataFreshnessItemVO item = new DataFreshnessItemVO();
            item.setJobNm(src.jobNm());
            item.setDataNm(src.dataNm());
            item.setLastExcnDt(last);
            item.setCyclNm(src.cyclNm());
            items.add(item);

            if (last != null && (latest == null || last.isAfter(latest))) {
                latest = last;
            }
        }

        DataFreshnessVO vo = new DataFreshnessVO();
        vo.setLatestDt(latest);
        vo.setItems(items);
        return vo;
    }
}
