package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.PrdMonths;
import jeju.datahub.analysis.service.ZoneRcmdtnBaselineVO;
import jeju.datahub.analysis.service.ZoneRcmdtnCrtrVls;
import jeju.datahub.analysis.service.ZoneRcmdtnExclLotVO;
import jeju.datahub.analysis.service.ZoneRcmdtnLotVO;
import jeju.datahub.analysis.service.ZoneRcmdtnResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnService;
import lombok.RequiredArgsConstructor;

/**
 * 구역추천(요금제 적용) 분석 서비스 구현체. eGovFrame {@link EgovAbstractServiceImpl} 상속(기존 패턴).
 *
 * <p>하는 일은 셋이다 — ① 요청값 검증·변환({@link ZoneRcmdtnQueryParam} 조립),
 * ② 계수 조달({@link ZoneRcmdtnCalculator#resolveCrtrVls}), ③ 매퍼 호출 + 순위 조립(🔁 TSK-4340 — 읍면동 판정은 제거됐다).
 * <b>산식은 {@link ZoneRcmdtnCalculator} 한 곳에만 둔다</b> — 여기에 다시 쓰면 두 곳이 갈린다.
 *
 * <h2>🔴 매퍼 파라미터는 전부 이 클래스가 만든다</h2>
 * <table border="1">
 *   <caption>요청 → 매퍼 파라미터</caption>
 *   <tr><th>매퍼 파라미터</th><th>출처</th></tr>
 *   <tr><td>{@code cvFrom}·{@code cvToExcl}</td>
 *       <td>{@code atStartOfDay()} / {@code plusDays(1).atStartOfDay()} <b>반열림</b>
 *           (🔴 {@code ocrn_dt} 는 {@code timestamp} 라 종료일 00:00 으로 두면 그 날이 빠진다.
 *           🔁 2026-10-12 — 종전 {@code dclr_ymd} 기준에서 옮겨왔다)</td></tr>
 *   <tr><td>{@code months}·{@code crdnMonths}</td>
 *       <td>{@code PrdMonths.of(시작, 종료)} — 달별 조회 비율 합(TSK-4284). 1일 조회도 0 보다 크다
 *           (SQL 이 이 값으로 나눈다)</td></tr>
 *   <tr><td>{@code crdnFrom}·{@code crdnTo}</td>
 *       <td>{@code atStartOfDay()} / {@code plusDays(1).atStartOfDay()} <b>반열림</b></td></tr>
 *   <tr><td>{@code trgtTypeSe}</td><td>화이트리스트 — 밖이면 <b>400</b></td></tr>
 *   <tr><td>{@code minCpcty}·{@code altmntRng}</td>
 *       <td>🔴 요청이 아니라 {@code RECOMMEND} <b>설정</b>이다</td></tr>
 *   <tr><td>{@code trendFrom}·{@code trendToExcl}·{@code bfrFrom}·{@code bfrToExcl}·
 *           {@code trendDayCnt}</td>
 *       <td>🔁 요청이 아니라 <b>선택한 민원 창에서 파생</b>한다 — <b>1차</b>: 최근 = 선택 기간
 *           전체({@code D} 일) · 이전 = <b>직전 같은 길이</b>({@code [bgng − D, bgng)})
 *           ({@link LocalDateTime} <b>반열림</b>. Step 15)</td></tr>
 *   <tr><td>{@code splitTrendFrom}·{@code splitTrendToExcl}·{@code splitBfrFrom}·
 *           {@code splitBfrToExcl}·{@code splitDayCnt}</td>
 *       <td>🔁 <b>폴백</b>(Step 13 의 반 분할) — 1차 '이전' 블록이 읍면동 전체에서 <b>0건</b>일 때만
 *           쓴다({@code warnings} 에 {@code TREND_BFR_FALLBACK}). 두 벌을 한 쿼리로 받아
 *           여기서 고른다</td></tr>
 * </table>
 *
 * <p>🔴 <b>블록이 7일({@link ZoneRcmdtnCalculator#MIN_TREND_DAYS}) 미만이면 비교가 성립하지
 * 않는다</b> — 그때는 증가율·월별 추이를 비우고 {@code warnings} 에 {@code TREND_WINDOW_SHORT} 를
 * 담는다(0 을 내리면 "최근에 줄었다" 로 잘못 읽힌다). 비우는 일은
 * {@link ZoneRcmdtnCalculator#applyTrend} 한 곳이 한다.
 * ⚠️ 🔁 Step 13 — 종전 "완전한 달 2개 미만"(Step 12)·"민원 창 6개월 미만"(Step 9) 판정은 모두
 * 폐지됐다. 달 경계를 보지 않으므로 {@code 최근1개월}(31일)도 증가율이 나온다.
 * 🔴 <b>판정 기준은 Step 15 에서도 {@code D/2}</b> 다({@code splitDayCnt}) — 1차를 쓰더라도
 * 폴백으로 내려앉을 수 있는 창이면 그 블록도 비교가 성립해야 한다.
 *
 * <p>🔴 <b>단속 창은 민원 창과 다르다 — 별도 파라미터이고 기본값이 있다.</b> 실측으로
 * {@code tb_crdn_dsctn} 보유 구간은 2025-02~2026-10 이고 이도이동은 2025-09~2026-07 뿐이다.
 * 민원 창(2025-01~08)으로 단속을 세면 이도이동 단속 0건이 된다(🔁 TSK-4340 — 종전엔 E0 판정이 이 창을
 * 썼고, 지금은 주차장별 배정 단속 {@code asgnCrdnCnt} 가 쓴다).
 *
 * <p>⚠️ 기간 상한 검증에 {@code DateRanges.validate} 를 쓰지 않는다 — 그 공용 가드는 1년(366일)
 * 상한이라 단속 기본 창(20개월)을 <b>400 으로 막는다</b>. 여기서는 "종료 ≥ 시작" 만 보고,
 * 상한(36개월)은 {@code ZoneRcmdtnController} 가 본다(Step 5 — 창마다 따로 센다).
 */
@Service
@RequiredArgsConstructor
public class ZoneRcmdtnServiceImpl extends EgovAbstractServiceImpl implements ZoneRcmdtnService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    // 🔴 단속 창 기본값(DEFAULT_CRDN_BGNG_YMD·DEFAULT_CRDN_END_YMD)은 ZoneRcmdtnService 인터페이스에
    //    있다 — 컨트롤러(Step 5)가 기간 상한을 판정할 때 같은 값을 봐야 한다. 여기 다시 두지 않는다.

    /** 분석유형코드 ({@code ANLS_TYPE_CD} 공통코드 — 마이그레이션 36·99 에 등재돼 있다) */
    private static final String ANLS_TYPE_RECOMMEND = "RECOMMEND";

    /** 분석실행상태코드 ({@code EXCN_STTS_CD}) — 동기 실행이라 저장이 곧 완료다 */
    private static final String EXCN_STTS_DONE = "DONE";

    /** 후보 주차장 유형 범위 화이트리스트 (§0-6). 기본은 {@code NARROW} 다 */
    private static final Set<String> TRGT_TYPE_SE = Set.of("NARROW", "WIDE");
    private static final String DEFAULT_TRGT_TYPE_SE = "NARROW";

    /** 🔁 TSK-4259 — 실행 범위(응답 {@code scope} · {@code anls_param_json.scope}). 읍면동 | 시군구 */
    static final String SCOPE_EMD = "EMD";
    static final String SCOPE_SGG = "SGG";

    /**
     * 🔁 TSK-4259 — {@code sggNm} 길이 상한. {@code tb_anls_excn.sgg_nm} 이 {@code VARCHAR(40)} 이라 넘는 값이
     * 적재까지 가면 400 이 아니라 500 이다({@code ZoneRcmdtnShareServiceImpl.SGG_NM_MAX_LEN} 과 같은 근거).
     */
    private static final int SGG_NM_MAX_LEN = 40;

    private final ZoneRcmdtnMapper zoneRcmdtnMapper;
    private final ZoneRcmdtnCalculator zoneRcmdtnCalculator;
    private final AnlsVrblOvrdResolver anlsVrblOvrdResolver;

    /**
     * 🆕 TSK-4282 D13 — 민원 자료 시작일({@code selectDataRange})의 출처. 🔴 공유주차제 매퍼를 그대로 쓴다 —
     * 같은 SQL(민원 {@code <> '99'} · {@code emd_cd IS NOT NULL} 의 {@code min(ocrn_dt)})을
     * {@code ZoneRcmdtnMapper} 에 한 벌 더 두면 두 탭의 가드 기준일이 갈릴 수 있어서다. 같은 패키지
     * ({@code analysis.service.impl}) 안의 매퍼라 의존 방향 문제는 없다.
     */
    private final ZoneRcmdtnShareMapper zoneRcmdtnShareMapper;

    // 🔴 적재는 JPA 다 (rule_persistence-jpa-mybatis.md — 쓰기=JPA). 조회는 위 매퍼가 맡는다.
    private final AnlsExcnRepository anlsExcnRepository;
    private final AnlsRsltRepository anlsRsltRepository;

    /** 🔴 {@code anls_param_json}·{@code rslt_json} 직렬화 경로를 한 곳으로 묶는다(Hotspot 선례) */
    private final ObjectMapper objectMapper;

    @Override
    public List<ZoneRcmdtnResultVO.Zone> selectZones(String bgngYmd, String endYmd,
                                                     String crdnBgngYmd, String crdnEndYmd,
                                                     String trgtTypeSe) {
        return selectZones(bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, trgtTypeSe, null);
    }

    @Override
    public List<ZoneRcmdtnResultVO.Zone> selectZones(String bgngYmd, String endYmd,
                                                     String crdnBgngYmd, String crdnEndYmd,
                                                     String trgtTypeSe, Map<String, BigDecimal> vrblVls) {
        // 🔴 상세와 같은 스코프로 검증한다 — 목록 밖 코드는 400 (TSK-4325). 🔁 TSK-4340 — 판정이 없어져
        //    계수는 결과에 영향이 없지만(위치 3필드뿐) 검증 계약은 유지한다.
        AnlsVrblOvrd ovrd = anlsVrblOvrdResolver.resolve(AnlsVrblScope.RECOMMEND_FEE, vrblVls);
        List<String> warnings = new ArrayList<>();
        ZoneRcmdtnCrtrVls crtrVls = zoneRcmdtnCalculator.resolveCrtrVls(
                normalizeTrgtTypeSe(trgtTypeSe), warnings, ovrd);
        // 🔴 /zones 는 읍면동 전량이라 emdCd 가 없다 — selectBaseline 만 부른다
        ZoneRcmdtnQueryParam param = param(null, null, bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, crtrVls);

        List<ZoneRcmdtnBaselineVO> rows = zoneRcmdtnMapper.selectBaseline(param);
        List<ZoneRcmdtnResultVO.Zone> zones = new ArrayList<>(rows.size());
        for (ZoneRcmdtnBaselineVO row : rows) {
            zones.add(zoneRcmdtnCalculator.zoneOf(row));
        }
        return zones;
    }

    @Override
    public ZoneRcmdtnResultVO selectZoneRcmdtn(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                               String crdnBgngYmd, String crdnEndYmd,
                                               String trgtTypeSe) {
        return selectZoneRcmdtn(emdCd, sggNm, bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, trgtTypeSe, null);
    }

    @Override
    public ZoneRcmdtnResultVO selectZoneRcmdtn(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                               String crdnBgngYmd, String crdnEndYmd,
                                               String trgtTypeSe, Map<String, BigDecimal> vrblVls) {
        return compute(emdCd, sggNm, bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, trgtTypeSe, vrblVls).result();
    }

    /**
     * {@inheritDoc}
     *
     * <p>🔴 {@link #compute} 가 이미 읽은 기준선 행·계수 설정 행·개월수로 {@code wbk} 를 만든다 — 새 조회가 없다.
     */
    @Override
    public ZoneRcmdtnResultVO selectZoneRcmdtnWithWorkbook(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                                           String crdnBgngYmd, String crdnEndYmd,
                                                           String trgtTypeSe, Map<String, BigDecimal> vrblVls) {
        Computed c = compute(emdCd, sggNm, bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, trgtTypeSe, vrblVls);
        c.result().setWbk(ZoneRcmdtnFeeWorkbooks.of(c.ovrd(), c.months(), c.applied()));
        return c.result();
    }

    /**
     * 조회 1회분 — 결과와, 엑셀 재계산 데이터({@code wbk})를 만들 때 필요한 같은 실행의 지역값 (TSK-4340 Step 1).
     *
     * @param zone   선택한 읍면동 기준선 행(원값). 🔴 시군구 모드는 {@code null}
     * @param months 민원 창 개월수({@code param.getMonths()})
     */
    private record Computed(ZoneRcmdtnResultVO result, ZoneRcmdtnBaselineVO zone, AnlsVrblOvrd ovrd,
                            BigDecimal months, ZoneRcmdtnCrtrVls applied) {
    }

    /**
     * {@link #selectZoneRcmdtn}·{@link #selectZoneRcmdtnWithWorkbook} 공용 본문(공유주차제 {@code compute}/{@code Computed} 선례).
     * 🔁 TSK-4340 Step 1 — 종전 8인자 {@code selectZoneRcmdtn} 본문을 그대로 옮겼다.
     */
    private Computed compute(String emdCd, String sggNm, String bgngYmd, String endYmd,
                             String crdnBgngYmd, String crdnEndYmd,
                             String trgtTypeSe, Map<String, BigDecimal> vrblVls) {
        // 🔁 TSK-4259 — requireEmdCd 를 대체한다. 🔴 이후 전부 '정규화된 값' 만 쓴다(공백 emdCd 가
        //    scopeWhere <when> 에 '' 로 들어가 0행 200 이 되지 않게).
        Scope scope = resolveScope(emdCd, sggNm);

        // 🔴 입력 오류는 400 이다. 설정 표에 쓰지 않는다 (TSK-4325 D8)
        AnlsVrblOvrd ovrd = anlsVrblOvrdResolver.resolve(AnlsVrblScope.RECOMMEND_FEE, vrblVls);
        List<String> warnings = new ArrayList<>();
        ZoneRcmdtnCrtrVls crtrVls = zoneRcmdtnCalculator.resolveCrtrVls(
                normalizeTrgtTypeSe(trgtTypeSe), warnings, ovrd);
        ZoneRcmdtnQueryParam param =
                param(scope.emdCd(), scope.sggNm(), bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, crtrVls);

        List<ZoneRcmdtnBaselineVO> baseline = zoneRcmdtnMapper.selectBaseline(param);
        ZoneRcmdtnBaselineVO zone = null;
        if (scope.isSgg()) {
            // 🔁 TSK-4259 — 시군구 모드는 기준선 1행 선택(404)을 타지 않는다. 기준선은 sggNm 존재 확인에만 쓴다.
            //    🔴 없는 sggNm(오타 등)은 400 이다(공유주차제 /share 와 같은 관례 · D6) — 통과시키면 빈 lots 로
            //    200 이 나가면서 실행 행이 적재돼, 오타 한 번이 "결과 없는 실행" 으로 이력에 남는다.
            if (baseline.stream().noneMatch(r -> scope.sggNm().equals(r.getSggNm()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "시군구를 찾을 수 없습니다: sggNm=" + scope.sggNm());
            }
        } else {
            // 기준선은 읍면동 전량이라 선택한 동 1행을 골라낸다 — 행정동 마스터 기준이므로 반드시 있다.
            zone = baseline.stream()
                    .filter(r -> scope.emdCd().equals(r.getEmdCd()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "읍면동을 찾을 수 없습니다: emdCd=" + scope.emdCd()));
        }

        List<ZoneRcmdtnLotVO> lots = zoneRcmdtnMapper.selectLotRanking(param);
        // 🔁 TSK-4276 — 후보 밖 주차장(지도 회색 핀). 🔴 trendScan 을 켜기 전, 후보와 '같은 param' 으로 부른다
        //    — 범위·후보 조건이 같아야 두 목록이 겹치지 않고 합이 범위 내 전체가 된다(trendScan 은 이 쿼리와
        //    무관하지만 상태가 바뀌기 전에 읽어 둔다). 두 모드 공통(D1).
        List<ZoneRcmdtnExclLotVO> exclLots = zoneRcmdtnMapper.selectExclLots(param);

        // 🔴 추세는 카디널리티가 달라 쿼리를 따로 부른다(주차장 1개소 = 여러 달).
        //    창이 짧으면 부르지 않는다 — 비교가 성립하지 않는 숫자를 조회할 이유가 없다.
        //    🔁 Step 15 — 이 쿼리만 민원 스캔을 '직전 동일 기간' 까지 넓힌다(trendScan). 🔴 위
        //       selectLotRanking 이 끝난 뒤에 켠다 — 먼저 켜면 정렬 기준 asgnCvlcptCnt 가 2배다.
        boolean trendOk = trendWindowOk(param);
        List<ZoneRcmdtnTrendCountVO> trendRows = List.of();
        boolean bfrFallback = false;
        if (trendOk) {
            param.setTrendScan(true);
            trendRows = zoneRcmdtnMapper.selectLotTrend(param);
            param.setTrendScan(false);
            // 🔴 읍면동 단위로 한 번 고른다 — 1차 '이전' 블록이 전 행 합 0 이면 반 분할로 내려앉는다
            //    ⚠️ 🔁 TSK-4259 — 판정은 '결과 전체' 기준이라 시군구 모드에선 시군구 전체에 한꺼번에 적용된다
            //    (TREND_BFR_FALLBACK). 그래서 같은 주차장의 cvlcptIncrsRt·trendMm·crtrVls.trendDayCnt 가 모드에
            //    따라 다를 수 있다 — 읍면동 모드에선 그 동의 '이전' 블록이 0이면 반 분할로 내려앉지만, 시군구
            //    모드에선 다른 동에 '이전' 민원이 있으면 1차 블록을 쓴다. 🔴 D2 의 "모드 간 일치" 는
            //    asgnCvlcptCnt·asgnCrdnCnt 까지만이다.
            // 🆕 TSK-4282 D13 — 1차 이전 블록이 자료 시작일 앞으로 넘어가도 반 분할로 내려앉는다(합 0 판정과 OR).
            //    자료 공백이 분모에 섞여 증가율이 부푸는 것을 막는다. 경고·블록 일수는 아래 기존 폴백 경로 그대로다.
            bfrFallback = ZoneRcmdtnCalculator.bfrFallback(trendRows, param.getBfrFrom(),
                    ZoneRcmdtnCalculator.dataBgngDt(zoneRcmdtnShareMapper.selectDataRange()));
        }
        // 🔴 블록 건수·월별 추이·증가율이 '같은 블록' 을 보게 하려면 x축도 고른 블록에서 만든다
        LocalDateTime rcntFrom = bfrFallback ? param.getSplitTrendFrom() : param.getTrendFrom();
        LocalDateTime rcntToExcl = bfrFallback ? param.getSplitTrendToExcl() : param.getTrendToExcl();
        zoneRcmdtnCalculator.applyTrend(lots, trendRows, trendYms(trendOk, rcntFrom, rcntToExcl),
                trendOk, bfrFallback, warnings);

        // 🔴 적용된 블록 일수를 응답 crtrVls 에 굳힌다 (🔁 Step 13 — 개월에서 일수로) — 조회
        //    기간에서 파생하므로 resolveCrtrVls 시점엔 알 수 없었다. 창이 짧으면 0 이 아니라
        //    null 이다("0일 블록" 이 아니라 "내지 않았다" 가 사실이다).
        //    🔁 Step 15 — 1차면 D, 폴백이면 D/2 다(실제로 쓴 블록 일수. 규칙 ③).
        ZoneRcmdtnCrtrVls applied = crtrVls.withTrendDayCnt(
                trendOk ? (bfrFallback ? param.getSplitDayCnt() : param.getTrendDayCnt()) : null);

        // 🆕 TSK-4282 — 검토지수(민원강도·면당압력 → 원점수 · 증가계수 · 우선도 · 우선도 단계 D14)를 채운다.
        //    🔴 applyTrend 다음이어야 한다(증가계수가 cvlcptIncrsRt 를 읽는다). 최종 정렬·순번은 이어지는
        //    assemble/assembleSgg 의 rank/rankOnly 가 한다(D9 — 매퍼 ORDER BY 는 결정적 입력 순서일 뿐이다).
        zoneRcmdtnCalculator.scoreLots(lots, param.getMonths(), applied, warnings);

        // 🔁 TSK-4259 — 시군구 모드는 판정·누적·배정률 없이 순위만(D4). scope·sggNm 은 계산기가 아니라 여기서
        //    정규화된 값으로 채운다(읍면동 모드의 sggNm 은 기준선이 읽어 온 zone.sggNm).
        ZoneRcmdtnResultVO result;
        if (scope.isSgg()) {
            result = zoneRcmdtnCalculator.assembleSgg(lots, applied, warnings);
            result.setScope(SCOPE_SGG);
            result.setSggNm(scope.sggNm());
        } else {
            result = zoneRcmdtnCalculator.assemble(zone, lots, applied, warnings);
            result.setScope(SCOPE_EMD);
            result.setSggNm(zone.getSggNm());
        }
        // 🔴 계산기가 새 result 를 만들므로 그 뒤에 담는다. buildRsltJson(4키)·보고서 스냅샷은 키를 골라
        //    담아 이 필드가 들어가지 않는다(D4 — 손대지 말 것).
        result.setExclLots(exclLots);
        // 🔴 buildRsltJson·보고서 스냅샷은 키를 골라 담아 이 칸이 rslt_json 에 들어가지 않는다 — 정본은 anls_param_json 이다(D10)
        result.setVrblAjmt(ovrd.toAjmtJson());
        return new Computed(result, zone, ovrd, param.getMonths(), applied);
    }

    /**
     * {@inheritDoc}
     *
     * <p>순서가 중요하다 — <b>조회 → 실행이력 → 결과</b>. {@code TB_ANLS_RSLT.excn_id} 가
     * NOT NULL FK 라 실행이력을 먼저 {@code flush()} 해 identity 채번을 확정해야 한다
     * ({@code HotspotReportServiceImpl} 선례).
     *
     * <p>⚠️ 조회가 400/404 로 끊기면 트랜잭션이 롤백돼 <b>두 표 모두 늘지 않는다</b> — 열어 볼 수
     * 없는 실행 이력을 남기지 않는다.
     */
    @Override
    @Transactional
    public ZoneRcmdtnResultVO analyzeAndRecord(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                               String crdnBgngYmd, String crdnEndYmd,
                                               String trgtTypeSe, String excutrId) {
        return analyzeAndRecord(emdCd, sggNm, bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, trgtTypeSe, excutrId, null);
    }

    @Override
    @Transactional
    public ZoneRcmdtnResultVO analyzeAndRecord(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                               String crdnBgngYmd, String crdnEndYmd,
                                               String trgtTypeSe, String excutrId,
                                               Map<String, BigDecimal> vrblVls) {
        // 🔴 sggNm 을 받는 창구로 부른다 — 6인자 default 를 타면 sggNm 이 버려진다(TSK-4259)
        ZoneRcmdtnResultVO result =
                selectZoneRcmdtn(emdCd, sggNm, bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, trgtTypeSe, vrblVls);
        // 🔁 TSK-4259 — 지역 칸은 응답의 scope·sggNm 과 (읍면동 모드면) zone 에서 고른다. 시군구 모드는
        //    zone == null 이라 종전처럼 result.getZone() 을 바로 역참조하면 NPE 다.
        boolean sgg = SCOPE_SGG.equals(result.getScope());
        String excnEmdCd = sgg ? null : result.getZone().getEmdCd();
        String excnSggNm = sgg ? result.getSggNm() : result.getZone().getSggNm();

        // 🔴 적재에 들어가는 기간은 요청 문자열이 아니라 '정규화된 값'이다 — 단속 창 생략값·대시 변환이
        //    여기서 되살아나야 anls_param_json 만 보고 같은 조회를 재현할 수 있다. param() 은 순수
        //    함수이고(검증은 이미 selectZoneRcmdtn 이 통과시켰다) 적용 계수는 응답의 crtrVls 를 쓴다 —
        //    설정을 다시 읽으면 그 사이 바뀐 값이 섞여 '응답에 적힌 기준' 과 갈린다.
        ZoneRcmdtnQueryParam p = param(excnEmdCd, sgg ? excnSggNm : null,
                bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, result.getCrtrVls());

        LocalDate bgng = p.getCvFrom().toLocalDate();
        // 🔴 cvToExcl·crdnTo 는 반열림 상한이라 -1일 해야 요청이 받은 '포함' 종료일로 돌아온다
        LocalDate end = p.getCvToExcl().toLocalDate().minusDays(1);
        LocalDate crdnBgng = p.getCrdnFrom().toLocalDate();
        LocalDate crdnEnd = p.getCrdnTo().toLocalDate().minusDays(1);

        // ── ① 실행이력 1행 (anls_type_cd='RECOMMEND' · emd_cd 채움 — 🔁 시군구 모드는 NULL) ──────
        AnlsExcn excn = new AnlsExcn();
        excn.setAnlsTypeCd(ANLS_TYPE_RECOMMEND);
        excn.setExcutrId(excutrId);   // 해석·401 은 컨트롤러 책임(PolicySimServiceImpl 과 같은 규칙) — TSK-4249
        // 🔴 sgg_nm 을 비우면 스냅샷 조회의 `sgg_nm || ' ' || emd_nm` 이 NULL 연결이 되어 지역명이
        //    통째로 사라진다 — 기준선이 TB_ADMDST 에서 읽어 온 값을 그대로 쓴다.
        //    🔁 TSK-4259 — 시군구 모드는 sgg_nm = 정규화된 sggNm · emd_cd = NULL 이다.
        excn.setSggNm(excnSggNm);
        excn.setEmdCd(excnEmdCd);
        excn.setAnlsBgngYmd(bgng.format(YMD));
        excn.setAnlsEndYmd(end.format(YMD));
        excn.setAnlsParamJson(buildParamJson(p, result, bgng, end, crdnBgng, crdnEnd));
        excn.setExcnSttsCd(EXCN_STTS_DONE);
        excn.setExcnDt(LocalDateTime.now());
        anlsExcnRepository.save(excn);
        // 결과 행이 excn_id 를 참조하므로 여기서 채번을 확정한다.
        anlsExcnRepository.flush();

        // ── ② 결과 1행 (실행 1건 = 결과 1행 — excn_id UNIQUE) ────────────────────
        AnlsRslt rslt = new AnlsRslt();
        rslt.setExcnId(excn.getExcnId());
        rslt.setRsltJson(buildRsltJson(result));
        anlsRsltRepository.save(rslt);

        result.setExcnId(excn.getExcnId());
        return result;
    }

    /**
     * 재현 메타 JSON ({@code anls_param_json}).
     *
     * <p>🔴 <b>{@code crtrVls} 는 키를 하나씩 명시</b>해 넣는다 — 응답 VO 를 그대로 직렬화하면 VO 변경이
     * 적재 계약을 조용히 바꾼다. 🔁 TSK-4340(2026-11-11) — 읍면동 판정 제거로 판정 계수·
     * {@code pcshrCrtrVl}·{@code paidDmndRt}·{@code dsclMltp} 는 더 굳히지 않는다.
     *
     * <p>🔴 <b>단속 창을 반드시 담는다</b> — 민원 창과 다르고 생략 가능해서, 이 네 칸이 없으면
     * 배정 단속({@code asgnCrdnCnt})을 어느 구간으로 셌는지 응답·이력 어디에도 남지 않는다.
     */
    private String buildParamJson(ZoneRcmdtnQueryParam p, ZoneRcmdtnResultVO result,
                                  LocalDate bgng, LocalDate end,
                                  LocalDate crdnBgng, LocalDate crdnEnd) {
        return writeJson(buildParamMap(p, result, bgng, end, crdnBgng, crdnEnd), "분석 파라미터");
    }

    /**
     * {@link #buildParamJson} 의 <b>Map 조립부</b> — 키·값·순서의 유일한 출처다 (TSK-4240 Step 2 에서 추출 ·
     * 동작 변화 0).
     *
     * <p>package-private static 인 이유 — 같은 패키지의 보고서 서비스({@code ZoneRcmdtnReportServiceImpl})가
     * 조회 실행과 <b>같은 키</b>를 적고 {@code rcmdtnTypeCd} 한 칸만 더해야 해서다. JSON 문자열을 돌려주면
     * 키를 더할 수 없고, 조립을 복제하면 두 실행의 재현 메타가 갈린다. 인스턴스 상태를 쓰지 않는다.
     *
     * @return 변경 가능한 {@link LinkedHashMap} — 호출부가 키를 더할 수 있다
     */
    static Map<String, Object> buildParamMap(ZoneRcmdtnQueryParam p, ZoneRcmdtnResultVO result,
                                             LocalDate bgng, LocalDate end,
                                             LocalDate crdnBgng, LocalDate crdnEnd) {
        ZoneRcmdtnCrtrVls c = result.getCrtrVls();

        Map<String, Object> param = new LinkedHashMap<>();
        // 🔁 TSK-4259 — 실행 범위·시군구명. 읍면동 모드도 scope:"EMD" 를 싣는다(보고서 경로도 이 Map 을 쓰므로
        //    같은 키를 갖는다). 🔴 scope 는 p 가 아니라 응답에서 읽는다 — 읍면동 모드의 p.sggNm 은 null 이다.
        param.put("scope", result.getScope());
        param.put("sggNm", result.getSggNm());
        param.put("emdCd", p.getEmdCd());
        param.put("bgngYmd", bgng.format(YMD));
        param.put("endYmd", end.format(YMD));
        param.put("months", p.getMonths());
        param.put("crdnBgngYmd", crdnBgng.format(YMD));
        param.put("crdnEndYmd", crdnEnd.format(YMD));
        param.put("crdnMonths", p.getCrdnMonths());
        param.put("trgtTypeSe", c.getTrgtTypeSe());
        // 🔴 추세 창(1차 4칸 + 폴백 4칸)도 굳힌다 — 민원 창에서 파생하지만 파생 규칙이 바뀌면 같은
        //    창이라도 다른 증가율이 나온다. 없으면 "어느 블록과 어느 블록을 비교했는지" 가 남지 않는다.
        //    🔁 Step 13 — 연월(YYYY-MM) 4칸이 '날짜 반열림' 4칸이 됐다(창이 짧으면 네 칸 모두 null).
        //    🔴 toString() 으로 굳힌다 — Jackson 설정(JavaTimeModule·타임스탬프 표기)이 바뀌어도
        //       적재된 표기가 흔들리지 않게 한다. 상한 두 칸은 '미포함' 이다.
        param.put("trendFrom", str(p.getTrendFrom()));
        param.put("trendToExcl", str(p.getTrendToExcl()));
        param.put("bfrFrom", str(p.getBfrFrom()));
        param.put("bfrToExcl", str(p.getBfrToExcl()));
        // 🔁 Step 15 — 폴백(반 분할) 4칸도 굳힌다. 🔴 어느 쪽을 실제로 썼는지는 warnings 의
        //    TREND_BFR_FALLBACK 과 crtrVls.trendDayCnt(D ↔ D/2)가 함께 말한다 — 두 벌을 남겨야
        //    "폴백이었다" 를 읽은 사람이 그 블록을 재현할 수 있다.
        param.put("splitTrendFrom", str(p.getSplitTrendFrom()));
        param.put("splitTrendToExcl", str(p.getSplitTrendToExcl()));
        param.put("splitBfrFrom", str(p.getSplitBfrFrom()));
        param.put("splitBfrToExcl", str(p.getSplitBfrToExcl()));

        Map<String, Object> cfc = new LinkedHashMap<>();
        cfc.put("altmntRngVl", c.getAltmntRngVl());
        cfc.put("swtcTrgtMinCpcty", c.getSwtcTrgtMinCpcty());
        // 🔁 TSK-4340(2026-11-11) — 읍면동 판정 제거로 stsfRtThrsh·cvlcptMmCrtrVl·ilglCrtrVl·pcshrCrtrVl·
        //    paidDmndRt·dsclMltp 는 더 굳히지 않는다(과거 실행 행에는 남아 있다).
        // 🔴 증가율 분모 하한도 코드 상수이지만 적재한다 — 바꾸면 같은 조회가 다른 증가율(또는
        //    null)을 내므로, 과거 실행이 어느 하한으로 났는지 알 수 없게 된다.
        cfc.put("minTrendBase", c.getMinTrendBase());
        // 🔁 Step 13 — 블록 일수도 적재한다. 파생 규칙을 바꾸면 같은 기간이 다른 길이로 갈리므로,
        //    이 값이 없으면 과거 실행의 증가율을 재현할 수 없다(위 날짜 4칸과 짝이다).
        cfc.put("trendDayCnt", c.getTrendDayCnt());
        // 🆕 TSK-4282 — 요금제 검토지수 설정 8종(실제 적용값). 🔴 키마다 명시해 담으므로 여기 없으면
        //    anls_param_json 에서 빠진다.
        cfc.put("feeCvlcptCntWgvl", c.getFeeCvlcptCntWgvl());
        cfc.put("feeCvlcptRtWgvl", c.getFeeCvlcptRtWgvl());
        cfc.put("feeCvlcptMmCrtrJeju", c.getFeeCvlcptMmCrtrJeju());
        cfc.put("feeCvlcptMmCrtrSeogwipo", c.getFeeCvlcptMmCrtrSeogwipo());
        cfc.put("feeCvlcptRtCrtrJeju", c.getFeeCvlcptRtCrtrJeju());
        cfc.put("feeCvlcptRtCrtrSeogwipo", c.getFeeCvlcptRtCrtrSeogwipo());
        cfc.put("feeCvlcptIncrsWgvl", c.getFeeCvlcptIncrsWgvl());
        cfc.put("feeCvlcptIncrsRtUplmtVl", c.getFeeCvlcptIncrsRtUplmtVl());
        param.put("crtrVls", cfc);
        // 🆕 TSK-4282 D11 — 분석 방법(RVW_SCR). 이 값이 없는 과거 실행은 구 방식(건수 순)이다.
        param.put("anlsMthd", result.getAnlsMthd());
        // TSK-4325 D10 — 분석 계수 적용 내역. 보고서 스냅샷이 여기서 되살린다
        param.put("vrblAjmt", result.getVrblAjmt());

        // 폴백으로 계산했는지도 남긴다 — 평시엔 빈 배열이다.
        param.put("warnings", result.getWarnings());

        return param;
    }

    /**
     * 결과 JSON ({@code rslt_json}).
     *
     * <p>🔴 <b>객체로 감싼다</b>(D15) — 배열을 그대로 두면 읍면동 블록·배정 커버리지를 함께 담을 자리가
     * 없다. 키는 응답 VO 필드명과 같게 둔다(되읽을 때 변환이 필요 없다).
     *
     * <p>🔴 {@code asgn} 블록을 빼지 않는다 — 배정률이 없으면 순위가 민원 전체를 설명하는 것처럼
     * 보인다.
     */
    private String buildRsltJson(ZoneRcmdtnResultVO result) {
        Map<String, Object> rslt = new LinkedHashMap<>();
        rslt.put("zone", result.getZone());
        rslt.put("asgn", result.getAsgn());
        rslt.put("lots", result.getLots());
        rslt.put("anlsMthd", result.getAnlsMthd());   // 🆕 TSK-4282 D11 — 보고서 스냅샷과 같은 키
        return writeJson(rslt, "분석 결과");
    }

    /**
     * {@code anls_param_json} 의 일시 칸 — {@code null} 을 보존하고 ISO 표기로 굳힌다.
     *
     * <p>🔴 {@link LocalDateTime} 객체를 그대로 넣지 않는 이유는 Jackson 설정(JavaTimeModule 등록
     * 여부·{@code WRITE_DATES_AS_TIMESTAMPS})이 바뀌면 적재된 표기가 함께 흔들리기 때문이다 —
     * 과거 실행을 되읽는 쪽이 파싱을 못 하게 된다.
     */
    private static String str(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    /** 🔴 직렬화 실패를 삼키지 않는다 — 빈 JSON 을 적재하면 재현 불가가 조용히 굳는다. */
    private String writeJson(Object value, String what) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    what + " 직렬화에 실패했습니다", e);
        }
    }

    /**
     * 매퍼 파라미터 조립. 🔴 적용 계수({@code minCpcty}·{@code altmntRng})는 요청이 아니라
     * {@link ZoneRcmdtnCrtrVls}(설정)에서 온다 — 응답의 {@code crtrVls} 와 <b>같은 값</b>이어야
     * 한다(다르면 "응답에 적힌 기준으로 낸 목록이 아니다").
     *
     * <p>package-private 인 이유 — 보고서 서비스({@code ZoneRcmdtnReportServiceImpl})가 적재에 넣을
     * '정규화된 기간' 을 조회 실행의 적재부와 <b>같은 호출</b>로 얻어야 해서다(TSK-4240 Step 2 · 가시성만 풀었다).
     * 순수 함수다 — DB 도 설정도 읽지 않는다.
     *
     * <p>🔁 TSK-4259 — {@code sggNm} 을 받는다(시군구 모드). {@code emdCd} 와 배타이고, 검증은
     * {@link #resolveScope} 가 이미 끝냈다(여기서는 그대로 싣는다). 🔴 {@code emdCd} 바로 뒤다.
     */
    static ZoneRcmdtnQueryParam param(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                      String crdnBgngYmd, String crdnEndYmd,
                                      ZoneRcmdtnCrtrVls crtrVls) {
        LocalDate bgng = parseYmd(bgngYmd, "bgngYmd");
        LocalDate end = parseYmd(endYmd, "endYmd");
        requireOrder(bgng, end, "bgngYmd", "endYmd");

        LocalDate crdnBgng = parseYmd(
                blankToNull(crdnBgngYmd) != null ? crdnBgngYmd : DEFAULT_CRDN_BGNG_YMD, "crdnBgngYmd");
        LocalDate crdnEnd = parseYmd(
                blankToNull(crdnEndYmd) != null ? crdnEndYmd : DEFAULT_CRDN_END_YMD, "crdnEndYmd");
        requireOrder(crdnBgng, crdnEnd, "crdnBgngYmd", "crdnEndYmd");

        ZoneRcmdtnQueryParam p = new ZoneRcmdtnQueryParam();
        p.setEmdCd(emdCd);
        p.setSggNm(sggNm);
        // 🔴 ocrn_dt 는 timestamp 라 LocalDateTime 이고 구간은 반열림이다 — 종료일은 +1일 00:00
        //    (crdn_dt 와 같은 방식). 종료일 00:00 으로 두면 그 날 하루가 통째로 빠진다.
        p.setCvFrom(bgng.atStartOfDay());
        p.setCvToExcl(end.plusDays(1).atStartOfDay());
        // 월평균 분모 — 달별 조회 비율 합(TSK-4284). 온전한 달만 고르면 종전 정수와 같다
        p.setMonths(PrdMonths.of(bgng, end));
        // 🔴 crdn_dt 는 timestamp 라 LocalDateTime 이고 구간은 반열림이다
        p.setCrdnFrom(crdnBgng.atStartOfDay());
        p.setCrdnTo(crdnEnd.plusDays(1).atStartOfDay());
        p.setCrdnMonths(PrdMonths.of(crdnBgng, crdnEnd));
        // 🔁 추세 창은 요청이 아니라 '선택한 민원 창' 을 일 단위로 반 갈라 파생한다
        //    (Step 13 — Step 12 의 월 단위 폐지). 네 칸 전부 LocalDateTime 반열림이다.
        applyTrendWindow(p, bgng, end);
        p.setMinCpcty(crtrVls.getSwtcTrgtMinCpcty());
        p.setAltmntRng(crtrVls.getAltmntRngVl());
        p.setTrgtTypeSe(crtrVls.getTrgtTypeSe());
        return p;
    }

    /**
     * 🔁 <b>추세 블록 파생</b> — 1차는 <b>선택 기간 전체 ↔ 직전 같은 기간</b>, 폴백은 Step 13 의
     * <b>반 분할</b>이다 (Step 15). 🔴 <b>두 벌을 모두 만든다</b> — 어느 쪽을 쓸지는 조회 결과
     * (1차 '이전' 블록의 읍면동 합)가 정하므로 여기서는 알 수 없다.
     *
     * <p>세 번 바뀐 자리다. <b>Step 9</b> 고정 3+3개월 → <b>Step 12</b> 완전한 달만 추려 반 분할 →
     * <b>Step 13</b> 일 단위 반 분할 → <b>Step 15</b> 직전 동일 기간(반 분할은 폴백).
     * Step 13 의 반 분할은 블록이 <b>절반</b>이라 ① 분모가 작아 증가율을 내는 행이 대량으로
     * 줄고(이도이동 {@code 최근1개월} 40행 중 <b>2행</b>만 값이 났다) ② 화면 보조줄의
     * {@code 기간 31일} 과 증가율이 보는 구간(15일)이 어긋났다.
     *
     * <ol>
     *   <li>{@code D} = 민원 창의 총 일수({@code bgng}~{@code end}, 양끝 포함).</li>
     *   <li><b>1차</b> — 최근 = {@code [bgng, end+1)}({@code D} 일) ·
     *       이전 = {@code [bgng − D, bgng)}({@code D} 일). 🔴 이전 블록이 <b>민원 창 밖</b>이라
     *       추세 쿼리만 스캔 범위를 넓힌다({@link ZoneRcmdtnQueryParam#getScanFrom()}).</li>
     *   <li><b>폴백</b> — {@code half = D / 2}(정수 나눗셈). 🔴 {@code D} 가 홀수면 가운데 1일을
     *       버린다 — 두 블록 일수가 다르면 증가율이 비교가 아니다(Step 13 ②).</li>
     *   <li>🔴 {@code half < }{@link ZoneRcmdtnCalculator#MIN_TREND_DAYS} 면 블록을 아예 만들지
     *       않는다(두 벌 모두 {@code null} · 일수 2칸 0) — 호출부가 {@code TREND_WINDOW_SHORT} 로
     *       드러낸다. 일주일도 안 되는 두 토막을 비교하면 요일 하나가 증가율을 만든다.
     *       🔴 판정 기준이 {@code D} 가 아니라 {@code half} 인 이유는, 폴백으로 내려앉을 수 있는
     *       창이면 그 블록도 비교가 성립해야 하기 때문이다(규칙 ④ "그대로").</li>
     *   <li>🔴 여덟 칸 모두 <b>반열림</b>({@code >= from AND < toExcl})이다 — {@code ocrn_dt} 는
     *       {@code timestamp} 라 상한을 포함으로 두면 그 날 00:00 이후가 통째로 빠진다.</li>
     * </ol>
     *
     * <p>🔴 <b>1차가 늘 되는 것은 아니다</b> — {@code ocrn_dt} 보유 구간이
     * <b>2026-01-01 ~ 2026-10-31(304일)</b> 이라 {@code D + D ≤ 304}, 즉 {@code D ≤ 152일}
     * (약 5개월)에서만 성립한다. 정본 창(243일)은 이전 기간이 2025년이라 <b>0건 → 폴백</b>이다.
     *
     * <p>🔴 <b>요일 효과는 감수한다.</b> 민원은 요일마다 다르다(실측 2026-01~08: 토 <b>15.9%</b> ↔
     * 월 <b>12.9%</b>). 폴백 블록이 7의 배수가 아니면 남는 날의 요일이 두 블록에서 달라 한쪽이
     * 유리해지지만, 그 편차는 합의 2% 안쪽이고 증가율은 ±30% 단위라 묻힌다. ⚠️ 1차는 두 블록이
     * 모두 {@code D} 일이라 이 편차가 1차에서는 애초에 작다.
     *
     * <p>⚠️ <b>민원 창({@code cvFrom}/{@code cvToExcl}) 자체는 건드리지 않는다</b> — 전 구간 건수
     * ({@code asgnCvlcptCnt})·월평균은 선택한 기간 그대로 센다. 바뀌는 것은 <b>추세 블록 파생</b>뿐이다.
     */
    private static void applyTrendWindow(ZoneRcmdtnQueryParam p, LocalDate bgng, LocalDate end) {
        // ① D — 양끝 포함이라 +1. 호출부가 이미 end >= bgng 를 보장한다(requireOrder)
        long days = ChronoUnit.DAYS.between(bgng, end) + 1;
        // ② half — 🔴 홀수면 가운데 1일이 자동으로 빠진다(폴백의 두 블록 일수를 같게 둔다)
        long half = days / 2;
        if (half < ZoneRcmdtnCalculator.MIN_TREND_DAYS) {
            // 🔴 여덟 칸을 null 로 남긴다 — XML 이 jdbcType=TIMESTAMP 를 달아 두었고
            //    ocrn_dt >= NULL 은 거짓이라 건수가 0 이고, 호출부는 추세 쿼리를 아예 부르지 않는다.
            p.setTrendDayCnt(0);
            p.setSplitDayCnt(0);
            return;
        }
        p.setTrendDayCnt((int) days);
        p.setSplitDayCnt((int) half);
        // ③ 1차 — 최근 = 선택 기간 전체 · 이전 = 직전 같은 길이. 🔴 둘 다 반열림이고 맞붙는다
        p.setTrendFrom(bgng.atStartOfDay());
        p.setTrendToExcl(end.plusDays(1).atStartOfDay());
        p.setBfrFrom(bgng.minusDays(days).atStartOfDay());
        p.setBfrToExcl(bgng.atStartOfDay());
        // ④ 폴백 — 이전 = 처음 half 일 · 최근 = 마지막 half 일 (Step 13 그대로)
        p.setSplitBfrFrom(bgng.atStartOfDay());
        p.setSplitBfrToExcl(bgng.plusDays(half).atStartOfDay());
        p.setSplitTrendFrom(end.minusDays(half - 1L).atStartOfDay());
        p.setSplitTrendToExcl(end.plusDays(1).atStartOfDay());
    }

    /**
     * 증가율·월별 추이를 낼 수 있는 창인가 — 🔁 <b>블록이 {@code MIN_TREND_DAYS}(7일) 이상</b>인가
     * (Step 13. 종전 "완전한 달 2개 이상"(Step 12)·{@code months >= 6}(Step 9) 판정은 폐지됐다).
     *
     * <p>판정은 {@link #applyTrendWindow} 가 이미 끝냈다 — {@code splitDayCnt >= 1} 이면 두 벌
     * (1차·폴백)이 만들어졌다는 뜻이다. 🔴 여기서 일수를 다시 세지 않는다 (두 곳이 각자 세면
     * "블록은 만들었는데 쓰지 않는다" 같은 어긋남이 생긴다).
     */
    private static boolean trendWindowOk(ZoneRcmdtnQueryParam p) {
        // 🔁 Step 15 — 기준은 '반 분할 블록'(D/2)이다. 1차를 쓰더라도 폴백으로 내려앉을 수 있는
        //    창이면 그 블록도 비교가 성립해야 한다(규칙 ④ "그대로"). 두 칸은 함께 0 이 된다.
        return p.getSplitDayCnt() >= 1;
    }

    /**
     * 차트 x축 — <b>최근 블록이 걸치는</b> 연월({@code YYYY-MM}) 오름차순. 🔴 <b>배정이 0건인 달도
     * 칸으로 존재해야</b> 하므로 데이터가 아니라 창에서 만든다({@code applyTrend} 가 0 으로 채운다).
     *
     * <p>🔁 Step 13 — 칸 수가 블록 <b>개월 수</b>가 아니라 <b>블록이 걸친 달 수</b>다. 블록이
     * 15일이면 1~2칸 · 121일이면 4~5칸이고 <b>양 끝 달은 부분 달</b>이다. 🔴 그래도
     * {@code trendMm} 합 = {@code asgnCvlcptCntRcnt} 불변식은 유지된다 — 같은 블록에서 달별
     * 값과 합을 함께 만들기 때문이다(달을 기준으로 채우면 그 등식이 깨진다).
     *
     * <p>🔁 <b>Step 15 — 블록을 인자로 받는다.</b> 1차인지 폴백인지가 조회 뒤에 정해지므로
     * 파라미터에서 꺼내 쓸 수 없다. 🔴 1차면 선택 기간 전체라 칸이 최대 <b>11개</b>까지 늘 수 있다.
     *
     * @param trendWindowOk 창이 성립하는가(아니면 빈 목록 — {@code applyTrend} 가 추이를 비운다)
     * @param rcntFrom      고른 최근 블록 시작(포함)
     * @param rcntToExcl    고른 최근 블록 종료(<b>미포함</b>)
     */
    private static List<String> trendYms(boolean trendWindowOk,
                                         LocalDateTime rcntFrom, LocalDateTime rcntToExcl) {
        List<String> yms = new ArrayList<>();
        if (!trendWindowOk || rcntFrom == null || rcntToExcl == null) {
            return yms;
        }
        // 🔴 상한은 '미포함' 이라 마지막 달은 toExcl 의 하루 전에서 읽는다 — toExcl 이 1일 00:00 이면
        //    그 달은 블록에 하루도 들어 있지 않다(그 달 칸을 만들면 항상 0 인 칸이 하나 붙는다).
        YearMonth last = YearMonth.from(rcntToExcl.minusDays(1));
        YearMonth ym = YearMonth.from(rcntFrom);
        while (!ym.isAfter(last)) {
            yms.add(ym.toString());
            ym = ym.plusMonths(1);
        }
        return yms;
    }

    /**
     * 유형 범위 정규화. 🔴 화이트리스트 밖은 <b>400</b> 이다 — 매퍼의 {@code <choose>} 는
     * {@code WIDE} 가 아니면 {@code NARROW} 로 떨어지므로, 검증 없이 흘리면 오타가
     * <b>조용히 {@code NARROW}</b> 로 동작한다.
     */
    private static String normalizeTrgtTypeSe(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return DEFAULT_TRGT_TYPE_SE;
        }
        String upper = v.trim().toUpperCase();
        if (!TRGT_TYPE_SE.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "trgtTypeSe 는 NARROW 또는 WIDE 여야 합니다: " + value);
        }
        return upper;
    }

    /**
     * 🔁 TSK-4259 — 실행 범위 해석({@code requireEmdCd} 대체). {@code emdCd}·{@code sggNm} 중 <b>정확히 하나</b>(D6).
     *
     * <p>🔴 두 값 모두 {@code blankToNull} + trim 으로 먼저 정규화한다 — 그래서 {@code emdCd=&sggNm=제주시} 는
     * 시군구 모드다. 둘 다 없음·둘 다 있음·{@code sggNm} 40자 초과는 DB 를 읽기 전에 400 이다.
     * 없는 {@code sggNm}(400)·없는 {@code emdCd}(404)는 기준선을 읽은 뒤 {@link #selectZoneRcmdtn} 이 본다.
     * 🔴 전부 실행 이력 기록 <b>전</b>이다 — 잘못된 입력은 이력을 남기지 않는다.
     */
    private static Scope resolveScope(String emdCd, String sggNm) {
        String e = blankToNull(emdCd) == null ? null : emdCd.trim();
        String s = blankToNull(sggNm) == null ? null : sggNm.trim();
        if (e == null && s == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "emdCd 또는 sggNm 중 하나는 필수입니다");
        }
        if (e != null && s != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "emdCd 와 sggNm 은 함께 보낼 수 없습니다");
        }
        // 🔴 DB 를 읽기 전에 끊는다 — 넘는 값은 어차피 실재하는 시군구명일 수 없다
        if (s != null && s.length() > SGG_NM_MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sggNm 은 " + SGG_NM_MAX_LEN + "자를 넘을 수 없습니다: " + s.length() + "자");
        }
        return new Scope(e, s);
    }

    /** 🔁 TSK-4259 — 정규화된 실행 범위. 둘 중 정확히 하나만 non-null 이다 */
    private record Scope(String emdCd, String sggNm) {
        boolean isSgg() {
            return sggNm != null;
        }
    }

    /** 🔴 형식 오류는 SQL 로 흘려보내지 않고 400 으로 끊는다({@code HotspotAnalysisServiceImpl} 선례). */
    private static LocalDate parseYmd(String value, String field) {
        if (blankToNull(value) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 는 필수입니다 (YYYYMMDD)");
        }
        try {
            return LocalDate.parse(value.trim(), YMD);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 는 YYYYMMDD 형식의 실재하는 날짜여야 합니다: " + value);
        }
    }

    private static void requireOrder(LocalDate bgng, LocalDate end, String bgngField, String endField) {
        if (end.isBefore(bgng)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    endField + " 가 " + bgngField + " 보다 빠릅니다: " + bgng + " ~ " + end);
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
