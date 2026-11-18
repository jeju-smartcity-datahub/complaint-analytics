package jeju.datahub.complaint.service.impl;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.stereotype.Service;

import jeju.datahub.complaint.service.ComplaintKpiRow;
import jeju.datahub.complaint.service.ComplaintKpiVO;
import jeju.datahub.complaint.service.ComplaintQueryParam;
import jeju.datahub.complaint.service.ComplaintRequest;
import jeju.datahub.complaint.service.ComplaintService;
import jeju.datahub.complaint.service.ComplaintUnit;
import jeju.datahub.complaint.service.EmdCompareRow;
import jeju.datahub.complaint.service.EmdCompareVO;
import jeju.datahub.complaint.service.EmdTopTypeRow;
import jeju.datahub.complaint.service.NegEmdCompareRow;
import jeju.datahub.complaint.service.NegEmdCompareVO;
import jeju.datahub.complaint.service.SentimentDistVO;
import lombok.RequiredArgsConstructor;

/**
 * 민원현황(GIS) 핵심 API 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속 (eGovFrame 관례).
 *
 * <p>응답을 만든다. 리스트 필드는 추후 메서드/매퍼 추가로 확장.
 *
 * * 조회 구간은 자바에서 {@link LocalDate} 로 계산하고 {@code #{}} 로만 매퍼에 전달한다 —
 *   SQL 인젝션 방어. enum {@link ComplaintUnit} 은 SQL 에 닿지 않는다.
 * * 감성 분포 분모 = 분석 완료된 전체(null 제외). totalCount 와 다르다는
 *   박스의 주의사항은 §3 KPI 표가 안내한다.
 * * enforcementCount / shortageCount는 producer batch / Jenks 잡 미구현으로 0.
 * * 결정: "오늘" 은 {@code ComplaintDashboardServiceImpl}(대시보드)와
 *   <b>같은 기준</b> — {@code Asia/Seoul} 실제 날짜({@code LocalDate.now(SEOUL)})다.
 * * rank / deltaPct / deltaPctInferred / topTypeRatio 는 서비스가 계산한다 —
 *   rank 는 결정적 순위(count DESC, emd_cd ASC). prevCount=0 일 때 분모 1 로 치환해 deltaPct
 *   산출 + deltaPctInferred=true 동봉 (자체 규칙, §5 무관).
 */
@Service
@RequiredArgsConstructor
public class ComplaintServiceImpl extends EgovAbstractServiceImpl implements ComplaintService {

    /** 응답의 {@code bgngYmd}/{@code endYmd} 포맷 — {@code YYYYMMDD}.
     *  🔴 {@code LocalDate#toString()} 을 쓰지 않는다 — {@code 2026-09-01} 이 나와
     *  {@code TB_ANLS_EXCN.anls_bgng_ymd}({@code CHAR(8)})·집중구역 표기와 형식이 갈린다. */
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ComplaintMapper complaintMapper;

    @Override
    public ComplaintKpiVO getKpi(ComplaintRequest request) {
        LocalDate[] range = resolveRange(request);
        // range = [prevStart, curStart, curEnd]. prevStart 는 curStart 의 직전 동일 길이 시작일.
        ComplaintQueryParam param = new ComplaintQueryParam(
                range[1],
                range[2],
                range[0],
                request.getSggCd(),
                request.getEmdCd(),
                request.getCvlcptTypeCd()
        );

        ComplaintKpiRow row = complaintMapper.selectKpi(
                param.getCurStart(), param.getCurEnd(), param.getPrevStart(),
                param.getSggCd(), param.getEmdCd(), param.getCvlcptTypeCd());
        // §1 헤더 lastSyncDt — 가용 데이터의 WHERE 와 무관한 전 행 MAX(load_dt).
        //   selectKpi 의 WHERE ocrn_dt BETWEEN 에 묶이면 가용 데이터가 구간 밖일 때
        //   lastSyncDt 가 NULL 이 되어 헤더 부제가 비는 회귀가 있어서 별도 SELECT.
        //   시드 기준값 = 2026-03-19 00:00:00. ComplaintSyncScheduler 가 매일 02:00 갱신.
        java.time.LocalDateTime lastSyncDt = complaintMapper.selectLastSyncDt();
        long enforcementCount = complaintMapper.selectEnforcementCount(
                param.getCurStart(), param.getCurEnd(), param.getPrevStart(),
                param.getSggCd(), param.getEmdCd(), param.getCvlcptTypeCd());
        long shortageCount    = complaintMapper.selectShortageCount(
                param.getCurStart(), param.getCurEnd(),
                param.getSggCd(), param.getEmdCd(), param.getCvlcptTypeCd());

        // §7: 읍면동별 집계 + 최다 유형 매칭 후 rank/deltaPct/topTypeRatio 부여.
        List<EmdCompareVO> emdCompare = buildEmdCompare(param);
        // §8: 읍면동별 부정 민원 집계 + rank/deltaPct 부여 (ratio 는 SQL 에서 계산).
        List<NegEmdCompareVO> negEmdCompare = buildNegEmdCompare(param);

        // 실제 적용한 구간을 응답이 되비춘다(화면은 unit 만 알고 날짜를 모른다).
        return toVo(row, lastSyncDt, enforcementCount, shortageCount, emdCompare, negEmdCompare,
                range[1].format(YMD), range[2].format(YMD));
    }

    @Override
    public long getTotalCount(ComplaintRequest request) {
        LocalDate[] range = resolveRange(request);
        return complaintMapper.selectKpi(
                range[1], range[2], range[0],
                request.getSggCd(), request.getEmdCd(), request.getCvlcptTypeCd()).totalCount();
    }

    /**
     * 요청의 {@code unit} 또는 {@code startDt/endDt} 둘 다 주어진 경우 을 [prevStart, curStart, curEnd]
     * 로 해소. startDt/endDt 가 둘 다 있으면 unit 무시.
     */
    private LocalDate[] resolveRange(ComplaintRequest request) {
        LocalDate curStart;
        LocalDate curEnd;
        if (request.hasExplicitRange()) {
            curStart = request.getStartDt();
            curEnd = request.getEndDt();
        } else {
            ComplaintUnit unit = request.getUnit() != null ? request.getUnit() : ComplaintUnit.YESTERDAY;
            // 대시보드(ComplaintDashboardServiceImpl)와 같은 기준일을 본다.
            // 주간·월간은 두 화면 모두 어제까지다(ComplaintUnit · DashboardUnit).
            LocalDate[] cur = unit.resolveRange(LocalDate.now(SEOUL));
            curStart = cur[0];
            curEnd = cur[1];
        }
        // 직전 동일 길이 구간 시작일. 예: 오늘~어제(cur=어제) → prevStart=그저께
        //   오늘~어제 (2일) → prevStart=그저께 (이틀 전). 기간 길이 = curEnd - curStart + 1 일.
        // 식 자체는 ComplaintUnit.prevStart 로 옮겼다(분석레이어 순위도 같은 식을 쓴다).
        LocalDate prevStart = ComplaintUnit.prevStart(curStart, curEnd);
        return new LocalDate[] { prevStart, curStart, curEnd };
    }

    /**
     * 매퍼 결과 평면 row + placeholder 카운트 들을 응답 VO 로 합성.
     * negativityRatio 분모(totalSntmntCount)가 0 이면 0.0 반환 — 결정.
     *
     * @param lastSyncDt 헤더용 — {@link ComplaintMapper#selectLastSyncDt()} 결과. 조회 구간과 무관한 전 행 MAX.
     *                    {@code ComplaintKpiRow} 에는 더 이상 {@code lastSyncDt} 가 없으므로 이 값을 VO 헤더에 주입한다.
     *                    (가용 데이터가 구간 밖일 때 헤더가 비는 회귀를 차단).
     * @param bgngYmd 실제 적용된 조회 시작일 {@code YYYYMMDD} — {@code resolveRange} 결과의 curStart.
     * @param endYmd  실제 적용된 조회 종료일 {@code YYYYMMDD} — 같은 결과의 curEnd.
     */
    private static ComplaintKpiVO toVo(ComplaintKpiRow row, java.time.LocalDateTime lastSyncDt,
                                       long enforcementCount, long shortageCount,
                                       List<EmdCompareVO> emdCompare,
                                       List<NegEmdCompareVO> negEmdCompare,
                                       String bgngYmd, String endYmd) {
        double negativityRatio = row.totalSntmntCount() == 0
                ? 0.0
                : (double) row.negativeCount() / row.totalSntmntCount();
        SentimentDistVO dist = new SentimentDistVO(
                row.positiveCount(),
                row.neutralCount(),
                row.negativeCount()
        );
        return new ComplaintKpiVO(
                lastSyncDt,
                row.totalCount(),
                negativityRatio,
                dist,
                enforcementCount,
                shortageCount,
                emdCompare,
                negEmdCompare,
                bgngYmd,
                endYmd
        );
    }

    /**
     * §7 응답 emdCompare[] 구성.
     * <ol>
     *   <li>{@code selectEmdCompare} 결과 (rank 정렬 후) 그대로 1-base rank 부여</li>
     *   <li>{@code selectTopTypeByEmd} 결과를 {@code emdCd} 키 Map 으로 그룹핑</li>
     *   <li>각 row 의 {@code topTypeCd/topTypeNm} 을 그룹핑에서 매칭, {@code topTypeRatio} 계산</li>
     *   <li>{@code deltaPct} 계산 — {@code prevCount=0} 이면 분모 1 로 치환 + {@code deltaPctInferred=true}</li>
     * </ol>
     */
    private List<EmdCompareVO> buildEmdCompare(ComplaintQueryParam param) {
        List<EmdCompareRow> rows = complaintMapper.selectEmdCompare(
                param.getCurStart(), param.getCurEnd(), param.getPrevStart(),
                param.getSggCd(), param.getEmdCd(), param.getCvlcptTypeCd());
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, EmdTopTypeRow> topByEmd = complaintMapper.selectTopTypeByEmd(
                param.getCurStart(), param.getCurEnd(), param.getPrevStart(),
                param.getSggCd(), param.getEmdCd(), param.getCvlcptTypeCd()).stream()
                .collect(Collectors.toMap(EmdTopTypeRow::emdCd, r -> r, (a, b) -> a));

        List<EmdCompareVO> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            EmdCompareRow r = rows.get(i);
            int rank = i + 1;
            EmdTopTypeRow top = topByEmd.get(r.getEmdCd());

            double topTypeRatio = (top != null && r.getCvlcptCnt() > 0)
                    ? (double) top.typeCount() / r.getCvlcptCnt()
                    : 0.0;

            int deltaPct;
            boolean inferred;
            if (r.getPrevCnt() == 0) {
                // prevCnt=0 이면 분모를 1로 치환. cvlcptCnt*100% 가 결과.
                deltaPct = (int) Math.round((r.getCvlcptCnt() - r.getPrevCnt()) / 1.0 * 100);
                inferred = true;
            } else {
                deltaPct = (int) Math.round((double) (r.getCvlcptCnt() - r.getPrevCnt()) / r.getPrevCnt() * 100);
                inferred = false;
            }

            out.add(new EmdCompareVO(
                    rank,
                    r.getEmdCd(),
                    r.getEmdNm(),
                    r.getCvlcptCnt(),
                    deltaPct,
                    top != null ? top.typeCd() : null,
                    top != null ? top.typeNm() : null,
                    topTypeRatio,
                    inferred,
                    r.getPrevCnt()
            ));
        }
        return out;
    }

    /**
     * §8 응답 negEmdCompare[] 구성. (§7 buildEmdCompare 와 같은 구조, 차이: 최다 유형 매칭 없음,
     * negRatio 는 SQL 에서 가져옴)
     * <ol>
     *   <li>{@code selectNegEmdCompare} 결과 (SQL 의 negativeRatio DESC 정렬, 2026-09-04 갱신)
     *       그대로 1-base rank 부여</li>
     *   <li>각 row 의 {@code negativeRatio} (SQL {@code NULLIF} 계산 결과, null 이면 0.0)</li>
     *   <li>{@code sentimentDist} 조립 — row 의 positive/neutral/negative 분포에서.</li>
     *   <li>{@code deltaPct} 계산 — {@code prevNegativeCount=0} 이면 분모 1 로 치환 +
     *       {@code deltaPctInferred=true}</li>
     * </ol>
     *
     * <p>2026-09-04 갱신 — envelope 동봉. 응답에 {@code totalCount} 와
     * {@code sentimentDist} 가 들어가야 §8 NegEmdCompareCard 가 시안의 sentiment bar
     * (긍정/보통/부정 3색 가로 막대) 를 그릴 수 있다.
     */
    private List<NegEmdCompareVO> buildNegEmdCompare(ComplaintQueryParam param) {
        List<NegEmdCompareRow> rows = complaintMapper.selectNegEmdCompare(
                param.getCurStart(), param.getCurEnd(), param.getPrevStart(),
                param.getSggCd(), param.getEmdCd(), param.getCvlcptTypeCd());
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<NegEmdCompareVO> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            NegEmdCompareRow r = rows.get(i);
            int rank = i + 1;

            int deltaPct;
            boolean inferred;
            if (r.getPrevNegativeCount() == 0) {
                // prevNegativeCount=0 이면 분모를 1로 치환 (설계 결정과 동일 정책). negativeCount*100% 가 결과.
                deltaPct = (int) Math.round((r.getNegativeCount() - r.getPrevNegativeCount()) / 1.0 * 100);
                inferred = true;
            } else {
                deltaPct = (int) Math.round((double) (r.getNegativeCount() - r.getPrevNegativeCount()) / r.getPrevNegativeCount() * 100);
                inferred = false;
            }

            // sentimentDist — envelope 의 sentimentDist.{positive,neutral,negative}.
            // 시안의 3색 bar (긍정/보통/부정) 가로 막대의 원천. row 의 분포 카운트에서 조립.
            // §3 KPI 의 공통 SentimentDistVO 를 재사용 (2026-09-04 갱신 — 중복 record 회피).
            // 부정 분포는 별도 row 필드 없이 negativeCount 를 그대로 재사용 (코파일럿
            // 2026-09-04 코멘트 정합 — 동일 DISTINCT/FILTER 의 중복 컬럼 회피).
            SentimentDistVO sentimentDist = new SentimentDistVO(
                    r.getPositiveCount(),
                    r.getNeutralCount(),
                    r.getNegativeCount()
            );

            out.add(new NegEmdCompareVO(
                    rank,
                    r.getEmdCd(),
                    r.getEmdNm(),
                    r.getNegativeCount(),
                    r.getTotalCount(),
                    sentimentDist,
                    r.getNegativeRatio() == null ? 0.0 : r.getNegativeRatio(),
                    deltaPct,
                    inferred
            ));
        }
        return out;
    }
}