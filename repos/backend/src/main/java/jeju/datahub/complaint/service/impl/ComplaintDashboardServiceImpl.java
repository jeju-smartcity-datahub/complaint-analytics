package jeju.datahub.complaint.service.impl;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.stereotype.Service;

import jeju.datahub.common.DateRanges;
import jeju.datahub.complaint.service.ComplaintDashboardService;
import jeju.datahub.complaint.service.DashboardKpiVO;
import jeju.datahub.complaint.service.DashboardRequest;
import jeju.datahub.complaint.service.DashboardTrendVO;
import jeju.datahub.complaint.service.DataStatusVO;
import jeju.datahub.complaint.service.RegionRankVO;
import jeju.datahub.complaint.service.RiskAlertKpiVO;
import jeju.datahub.complaint.service.RiskLevelCountVO;
import jeju.datahub.complaint.service.TrendBucket;
import jeju.datahub.complaint.service.TrendPointVO;
import jeju.datahub.complaint.service.TypeCountVO;
import lombok.RequiredArgsConstructor;

/**
 * 민원 대시보드 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>조회 기간(현재/직전 구간)은 이 계층에서 {@link LocalDate} 로 계산해 매퍼 파라미터로 넘긴다.
 * unit(enum) 은 SQL 에 닿지 않으며, 필터는 전부 {@code #{}} 바인딩이다.
 */
@Service
@RequiredArgsConstructor
public class ComplaintDashboardServiceImpl extends EgovAbstractServiceImpl implements ComplaintDashboardService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ComplaintDashboardMapper complaintDashboardMapper;

    @Override
    public long countAllComplaints() {
        return complaintDashboardMapper.countAll();
    }

    @Override
    public DashboardKpiVO getKpi(DashboardRequest request) {
        DashboardQueryParam param = resolveParam(request);
        DashboardKpiVO kpi = complaintDashboardMapper.selectKpi(param);
        kpi.setDeltaPct(deltaPct(kpi.getTotal(), kpi.getPrevTotal()));
        kpi.setCrdnCnt(complaintDashboardMapper.selectCrdnCount(param));
        // 공영주차장 현황(2026-10-01). 🔴 같은 param 을 넘기지만 SQL 이 기간을 쓰지 않는다 —
        // 시점 현황이라 sggCd 만 적용된다. crdnCnt 는 화면에서 빠졌어도 계속 내린다(되돌리기 쉽게).
        kpi.setPkltCnt(complaintDashboardMapper.selectPkltCount(param));
        kpi.setRiskAlert(riskAlertKpi(param));
        return kpi;
    }

    /**
     * 위험단계 알림 집계. 단계별 건수를 읽어 합계와 함께 담는다.
     * 알림이 한 건도 없으면 total=0 · breakdown=[] 로, null 이 아니라 <b>빈 집계</b>를 내린다
     * — 프론트가 0 을 실데이터로 표시해야 한다.
     */
    private RiskAlertKpiVO riskAlertKpi(DashboardQueryParam param) {
        List<RiskLevelCountVO> breakdown = complaintDashboardMapper.selectRiskAlertCounts(param);

        RiskAlertKpiVO vo = new RiskAlertKpiVO();
        vo.setBreakdown(breakdown);
        vo.setTotal(breakdown.stream().mapToLong(RiskLevelCountVO::getCount).sum());
        return vo;
    }

    @Override
    public List<RegionRankVO> getRegionRanks(DashboardRequest request) {
        DashboardQueryParam param = resolveParam(request);

        // 1) 읍면동별 현재/직전 건수(count 내림차순, SQL 에서 정렬)
        List<RegionRankVO> ranks = complaintDashboardMapper.selectRegionRanks(param);

        // 2) 읍면동 × 유형 분해(평면 행) → emdCd 기준으로 그룹핑
        Map<String, List<TypeCountVO>> breakdownByEmd = complaintDashboardMapper.selectTypeBreakdown(param).stream()
                .collect(Collectors.groupingBy(TypeCountVO::getEmdCd));

        // 3) 각 읍면동에 breakdown 주입 + 전기 대비 증감률 계산
        for (RegionRankVO rank : ranks) {
            rank.setBreakdown(breakdownByEmd.getOrDefault(rank.getEmdCd(), List.of()));
            rank.setDeltaPct(deltaPct(rank.getCount(), rank.getPrevCount()));
        }

        // 4) 전기 대비 순위 변동(rankDelta) 계산 — (시안 `.region__trend` 배지)
        assignRankDeltas(ranks);

        // LIMIT 는 SQL 에서 걸지 않는다: 전체 읍면동(74행 규모)을 count 내림차순으로 반환하고
        // 상위 N(시안 5카드)은 프론트가 slice 한다. 순위 API 를 다른 용도로도 재사용하기 위함.
        return ranks;
    }

    @Override
    public DashboardTrendVO getTrend(DashboardRequest request) {
        DashboardQueryParam param = resolveParam(request);
        TrendBucket bucket = resolveBucket(request);
        // ⚠️ 366칸 상한은 위 resolveParam() 안의 DateRanges.validate() 가 **매퍼 조회 앞**에서
        //    검사한다 — 뒤로 밀면 거부할 요청에도 구간 전체 GROUP BY 를 다 돌고 나서 400 을 내게 된다.
        List<String> keys = bucketKeys(bucket, param);

        List<TrendCountVO> rows = (bucket == TrendBucket.HOUR)
                ? complaintDashboardMapper.selectHourlyTrend(param)
                : complaintDashboardMapper.selectDailyTrend(param);

        // (bucketKey -> [{typeCd, count}]) 로 접는다. 조회 결과에 없는 키는 아래에서 0 으로 채운다.
        Map<String, List<TypeCountVO>> byBucket = new LinkedHashMap<>();
        for (TrendCountVO row : rows) {
            TypeCountVO tc = new TypeCountVO();
            tc.setTypeCd(row.getTypeCd());
            tc.setCount(row.getCount());
            byBucket.computeIfAbsent(row.getBucketKey(), k -> new ArrayList<>()).add(tc);
        }

        List<TrendPointVO> points = new ArrayList<>();
        for (String key : keys) {
            List<TypeCountVO> breakdown = byBucket.getOrDefault(key, List.of());
            TrendPointVO p = new TrendPointVO();
            p.setBucketKey(key);
            p.setBreakdown(breakdown);
            p.setTotal(breakdown.stream().mapToLong(TypeCountVO::getCount).sum());
            points.add(p);
        }

        DashboardTrendVO vo = new DashboardTrendVO();
        vo.setBucket(bucket);
        vo.setStartDt(param.getCurStart());
        vo.setEndDt(param.getCurEnd());
        vo.setPoints(points);
        return vo;
    }

    /**
     * 적용할 버킷. 요청이 명시하면 그대로, 아니면 기간 직접 지정=DAY / 조회단위=HOUR.
     */
    private TrendBucket resolveBucket(DashboardRequest request) {
        return request.getBucket() != null ? request.getBucket() : TrendBucket.HOUR;
    }

    /**
     * x축 칸 키 전체(빈 칸 포함). HOUR 은 항상 24칸, DAY 는 구간의 날짜 수만큼.
     */
    private List<String> bucketKeys(TrendBucket bucket, DashboardQueryParam param) {
        if (bucket == TrendBucket.HOUR) {
            return IntStream.range(0, 24)
                    .mapToObj(h -> String.format("%02d", h))
                    .collect(Collectors.toList());
        }
        long days = ChronoUnit.DAYS.between(param.getCurStart(), param.getCurEnd()) + 1;
        if (days < 1) {
            return List.of();
        }
        return param.getCurStart().datesUntil(param.getCurEnd().plusDays(1))
                .map(LocalDate::toString)
                .collect(Collectors.toList());
    }

    /**
     * 요청을 매퍼 파라미터로 변환한다. startDt·endDt 가 둘 다 있으면 그 구간을 현재 구간으로,
     * 아니면 unit(TODAY/YESTERDAY/WEEK/MONTH)으로 현재 구간을 정한다. 직전 구간은 현재 구간과 같은 길이로
     * 바로 앞에 붙인다.
     */
    private DashboardQueryParam resolveParam(DashboardRequest request) {
        LocalDate curStart;
        LocalDate curEnd;
        if (request.hasExplicitRange()) {
            curStart = request.getStartDt();
            curEnd = request.getEndDt();
        } else {
            LocalDate today = LocalDate.now(SEOUL);
            curEnd = today.minusDays(request.getUnit().getEndDaysBack());
            curStart = today.minusDays(request.getUnit().getStartDaysBack());
        }

        // 역전·366일 상한은 여기서 한 번만 검사한다 — 모든 대시보드 조회가
        // resolveParam() 을 거치므로 KPI·순위·추이가 같은 규칙·같은 문구로 400 을 낸다.
        DateRanges.validate(curStart, curEnd);

        // 구간 길이(일, 양끝 포함). 직전 구간: [curStart-len, curStart-1]
        long len = ChronoUnit.DAYS.between(curStart, curEnd) + 1;

        DashboardQueryParam param = new DashboardQueryParam();
        param.setCurStart(curStart);
        param.setCurEnd(curEnd);
        param.setPrevEnd(curStart.minusDays(1));
        param.setPrevStart(curStart.minusDays(len));
        param.setSggCd(blankToNull(request.getSggCd()));
        param.setTypeCd(blankToNull(request.getTypeCd()));
        param.setRiskLvlCd(blankToNull(request.getRiskLvlCd()));
        return param;
    }

    /**
     * 데이터 현황. 민원 최종 발생일시는 {@code MAX(ocrn_dt)} 다 —
     * {@code load_dt} 는 이름과 달리 원천 답변일 자정이라 쓰지 않는다.
     *
     * <p>필터를 받지 않으므로 매퍼에 파라미터를 넘기지 않는다. 집계 모수만 대시보드 조회 정책
     * (99 제외 · emd_cd NOT NULL)과 맞춘다 — KPI 카드 총건수와 다른 수를 세면 화면에서 어긋난다.
     */
    @Override
    public DataStatusVO getDataStatus() {
        return complaintDashboardMapper.selectDataStatus();
    }

    /**
     * 전기 대비 순위 변동({@code rankDelta})을 각 항목에 채운다.
     *
     * <p>규칙 3가지 — 사용자 확정 설계다.
     * <ol>
     *   <li><b>현재 순위</b>: {@code count} 내림차순. 매퍼가 이미
     *       {@code ORDER BY count DESC, emd_cd} 로 정렬해 주므로 순서대로 훑으며 매긴다.
     *       <b>동순위는 같은 순위, 다음은 건너뛴다</b>(SQL {@code rank()} 처럼 {@code 1,2,2,4}).</li>
     *   <li><b>직전 순위</b>: 같은 목록을 {@code prevCount} 내림차순으로 다시 정렬해 같은 방식으로
     *       매긴다. 🔴 {@code prevCount == 0} 인 항목은 <b>순위를 매기지 않는다</b>(제외) —
     *       0건을 공동 꼴찌로 보면 "70위 → 5위" 같은 과장이 된다.</li>
     *   <li>{@code rankDelta = 직전순위 − 현재순위} (<b>양수 = 상승</b>),
     *       {@code prevCount == 0} 이면 {@code null}(화면이 `신규` 로 표기).</li>
     * </ol>
     *
     * <p>🔴 <b>정렬 부작용 금지</b> — 반환 목록의 순서는 {@code count} 내림차순 그대로여야 한다
     * (프론트가 상위 5개를 slice 한다). 직전 순위는 <b>복사본</b>을 정렬해 구한다.
     */
    private void assignRankDeltas(List<RegionRankVO> ranks) {
        // 1) 현재 순위 (목록은 이미 count DESC — 앞 항목과 count 가 같으면 같은 순위)
        Map<String, Integer> curRank = rankBy(ranks, RegionRankVO::getCount);

        // 2) 직전 순위 — 복사본을 prevCount DESC 로 정렬, prevCount=0 은 제외
        List<RegionRankVO> prevOrder = new ArrayList<>(ranks);
        prevOrder.removeIf(r -> r.getPrevCount() == 0);
        prevOrder.sort(Comparator.comparingLong(RegionRankVO::getPrevCount).reversed()
                .thenComparing(RegionRankVO::getEmdCd));
        Map<String, Integer> prevRank = rankBy(prevOrder, RegionRankVO::getPrevCount);

        // 3) delta = 직전 − 현재 (양수 = 상승). 직전 0건이면 null.
        for (RegionRankVO r : ranks) {
            Integer prev = prevRank.get(r.getEmdCd()); // prevCount=0 이면 애초에 없다 → null
            Integer cur = curRank.get(r.getEmdCd());
            r.setRankDelta((prev == null || cur == null) ? null : prev - cur);
        }
    }

    /**
     * 정렬이 끝난 목록을 순서대로 훑어 {@code emdCd -> 순위} 를 만든다.
     * 앞 항목과 키 값이 같으면 같은 순위를 주고, 다음 순위는 그만큼 건너뛴다({@code 1,2,2,4}).
     *
     * @param sorted 키 값 내림차순으로 이미 정렬된 목록
     * @param keyFn  순위를 매길 기준값(count 또는 prevCount)
     */
    private Map<String, Integer> rankBy(List<RegionRankVO> sorted, ToLongFunction<RegionRankVO> keyFn) {
        Map<String, Integer> out = new LinkedHashMap<>();
        long prevKey = Long.MIN_VALUE;
        int rank = 0;
        int seen = 0;
        for (RegionRankVO r : sorted) {
            seen++;
            long key = keyFn.applyAsLong(r);
            if (seen == 1 || key != prevKey) {
                rank = seen; // 동순위 다음은 건너뛴다 (1,2,2,4)
            }
            out.put(r.getEmdCd(), rank);
            prevKey = key;
        }
        return out;
    }

    /** 증감률(%): round((cur-prev)/prev*100). prev=0 이면 0(0나눗셈 방지). */
    private int deltaPct(long cur, long prev) {
        if (prev == 0) {
            return 0;
        }
        return (int) Math.round((cur - prev) * 100.0 / prev);
    }

    private String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }
}
