package jeju.datahub.lifezone.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.AnalysisWeightService;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.analysis.service.impl.AnlsVrblOvrdResolver;
import jeju.datahub.analysis.service.impl.ZoneNameResolver;
import jeju.datahub.analysis.service.impl.ZoneNameResolver.ZoneName;
import jeju.datahub.common.DateRanges;
import jeju.datahub.lifezone.service.LifezoneClsfMthd;
import jeju.datahub.lifezone.service.LifezoneCngstCellVO;
import jeju.datahub.lifezone.service.LifezoneCngstRequest;
import jeju.datahub.lifezone.service.LifezoneCngstResultVO;
import jeju.datahub.lifezone.service.LifezoneCngstService;
import jeju.datahub.lifezone.service.LifezoneCngstWorkbookCellVO;
import jeju.datahub.lifezone.service.LifezoneCngstWorkbookVO;
import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneGradeScale;
import jeju.datahub.lifezone.service.LifezoneRankVO;
import jeju.datahub.lifezone.service.LifezoneSupply;
import jeju.datahub.lifezone.service.LifezoneTarget;
import jeju.datahub.lifezone.service.LifezoneTargetVO;

/**
 * 생활권 혼잡도(주차 혼잡 지수 → 혼잡 점수) 분석 구현. (TSK-4090 Step 5)
 *
 * <h2>산식 (계획서 5-0)</h2>
 * <pre>
 *   Enf_g = (n_pda + n_manual + w_fixed × n_fixed) ÷ 개월수     -- 민원 기원 단속은 n_manual 에 포함(Step 1 확정)
 *   Cv_g  = cvlcpt_cnt ÷ 개월수
 *   개월수 = 요청 구간의 달력 월 수 (부분월은 일수 비례 — 2025-11-01~2026-06-30 = 8.0 · 01-15~01-31 = 17/31)
 *   초과주차 추정량 = (m × Enf_g + w_cv × Cv_g) ÷ 30 × g_geo     -- 🔴 민원엔 적발배수를 곱하지 않는다
 *   H_g   = occ + 초과주차 추정량 ÷ S_g
 *   혼잡 점수 = H_g × 100 (상한 없음)                              -- 2026-10-08 사용자 결정(변경 이력 ⑩ 환산 · ⑪ 상한 해제)
 *   anlsVl = round(H_g × 10000)                                   -- = 점수 × 100 (소수 둘째 자리까지 담는 내부 정밀도)
 * </pre>
 * 🔴 <b>anlsVl 을 정수 점수(×100)로 두지 않는다</b> — 실측 분포가 85~91점에 몰려 정수로는 동점이 쌓여
 * 분위 경계가 무너진다. 그래서 등급은 점수×100 정수에 매기고 표시만 점수 소수 첫째 자리로 한다.
 * 🔴 <b>정렬·순위는 원 H</b>(double)다 — 같은 anlsVl(반올림 동점)끼리도 실제 혼잡 순으로 선다.
 * 100점 초과 = 수요가 면수를 넘는 격자이며 {@code over1GridCnt} 로 센다({@code anlsVl > 10000} —
 * {@code EQUAL_INTERVAL_MIN} 의 심각 판정과 같은 기준). 점수 = 지수×100 이라 원 지수 필드는 따로 내리지 않는다.
 * 계수는 전부 {@link AnalysisWeightService#coefficient}({@code LIFEZONE}, …)로 읽는다 —
 * {@code PCSHR_CRTR_VL}(occ) · {@code DSCL_MLTP}(m) · {@code FIX_WGVL}(w_fixed) · {@code GEO_CVRG_CRCT_VL}(g_geo) ·
 * {@code CVLCPT_WGVL}(w_cv · 0 이면 명세 방식) · {@code MIN_CPCTY_VL}(분석 대상 면수 하한) ·
 * {@code CNGST_GRD_BNDRY1~4}({@code CNGST_ABSOLUTE} 경계 · 단위 점 → ×100). 이 서비스는 계수를 캐시하지 않는다(요청마다 읽는다).
 *
 * <h2>🔴 세 상태를 뭉개지 않는다 (DEC-027)</h2>
 * <ul>
 *   <li>공급 0 — 응답에 넣지 않는다(매퍼가 이미 거른다).</li>
 *   <li>{@code noEnfYn='Y'} — 불법주차 근거 없음(단속·민원 모두 0 → 추정량 0 → {@code H = occ}).
 *       <b>응답 {@code cells} 에서 뺀다</b>(지도·요약·등급 분모·순위 모두 제외 — 2026-10-08 사용자 결정).
 *       개수만 {@code noEnfGridCnt}(제외된 격자 수)로 내린다. 그래서 {@code totGridCnt} = 5등급 합(R10)이 성립한다.</li>
 *   <li>면수 하한 미만 — {@code S < MIN_CPCTY_VL}. 근거 없는 격자와 <b>같은 방식으로</b> 등급 계산 전에 뺀다
 *       (지도·요약·등급 분모·순위 모두 제외 — 2026-10-08 사용자 결정). 개수만 {@code refGridCnt} 로 내린다.
 *       면수가 작으면 추정량 몇 건에도 지수가 튀어 '심각' 칸을 차지하는데 순위표에선 빠져
 *       "심각 1개인데 순위엔 없음" 이 생겼다 — 그래서 아예 분석 대상에서 뺀다.</li>
 * </ul>
 * 🔴 <b>두 제외 사유가 겹치면 근거 없음({@code noEnfGridCnt})으로 센다</b> — {@code refGridCnt} 는 근거는 있으나
 * 면수 하한 미만인 격자 수다. 그래서 {@code noEnfGridCnt + refGridCnt + totGridCnt} = 공급>0 격자 수다.
 * 응답 셀의 {@code noEnfYn}·{@code refYn} 은 하위호환으로 남지만 항상 {@code 'N'} 이다.
 *
 * <p>🔴 민원건수·수급 파이프라인과 매퍼를 공유하지 않는다 — 기준 CTE 가 반대다({@link LifezoneCngstMapper}).
 */
@Service
public class LifezoneCngstServiceImpl extends EgovAbstractServiceImpl implements LifezoneCngstService {

    private static final Logger log = LoggerFactory.getLogger(LifezoneCngstServiceImpl.class);

    private static final DateTimeFormatter YMD =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /** 설정값 분석유형코드 — 기존 생활권 탭을 그대로 쓴다(Step 2) */
    static final String ANLS_TYPE_CD = "LIFEZONE";

    static final String VRBL_OCC = "PCSHR_CRTR_VL";
    static final String VRBL_DSCL_MLTP = "DSCL_MLTP";
    static final String VRBL_FIX_WGVL = "FIX_WGVL";
    static final String VRBL_GEO_CRCT = "GEO_CVRG_CRCT_VL";
    static final String VRBL_CVLCPT_WGVL = "CVLCPT_WGVL";
    static final String VRBL_MIN_CPCTY = "MIN_CPCTY_VL";
    static final String VRBL_GRD_BND_PREFIX = "CNGST_GRD_BNDRY";

    /**
     * 혼잡지수 → anlsVl 정수화 배율 (= 점수 × {@link #SCORE_TO_VL}). ×100(정수 점수)·×1000 은 분위 경계가
     * 동점에 뭉쳐 낮음이 사라진다(계획서 5-2 · 2026-10-08 점수 환산 후에도 유지).
     */
    static final int SCALE = LifezoneGradeScale.CNGST_FULL_VL;   // 지수 1.0 = 100점 = 10000 — 등급 규칙과 한 상수

    /** 점수 → anlsVl 배율 — 설정 경계(점)를 anlsVl 단위로 옮길 때 쓴다 */
    static final int SCORE_TO_VL = 100;

    /** 월 → 일 환산 (산식의 ÷30) */
    private static final double DAYS_PER_MONTH = 30.0;

    /** 초과주차 추정량 표시 소수 자릿수 */
    private static final int EST_SCALE = 1;

    /** 순위 개수 하한. 🔁 TSK-4227 D21 — 기본·상한은 없앴다(생략 = 심각·경고·주의 전부). */
    private static final int MIN_LIMIT = 1;

    /** {@code limit} 생략 = 상한 없음. 루프 조건 {@code ranking.size() >= limit} 이 닿지 않는 값이다. */
    private static final int UNLIMITED = Integer.MAX_VALUE;

    private static final LifezoneTarget TARGET = LifezoneTarget.CNGST;

    private final LifezoneCngstMapper lifezoneCngstMapper;
    private final LifezoneRegionResolver regionResolver;
    private final AnalysisWeightService analysisWeightService;
    private final ObjectMapper objectMapper;
    private final ZoneNameResolver zoneNameResolver;
    private final AnlsVrblOvrdResolver anlsVrblOvrdResolver;

    public LifezoneCngstServiceImpl(LifezoneCngstMapper lifezoneCngstMapper,
                                    LifezoneRegionResolver regionResolver,
                                    AnalysisWeightService analysisWeightService,
                                    ObjectMapper objectMapper,
                                    ZoneNameResolver zoneNameResolver,
                                    AnlsVrblOvrdResolver anlsVrblOvrdResolver) {
        this.lifezoneCngstMapper = lifezoneCngstMapper;
        this.regionResolver = regionResolver;
        this.analysisWeightService = analysisWeightService;
        this.objectMapper = objectMapper;
        this.zoneNameResolver = zoneNameResolver;
        this.anlsVrblOvrdResolver = anlsVrblOvrdResolver;
    }

    /**
     * 분석 1회의 결과와 엑셀 원자료의 재료 — {@link #analyze} 와 {@link #analyzeWithWorkbook} 공용 (TSK-4340 Step 2).
     *
     * @param rows   {@code selectCngstCells} 결과(공급&gt;0 전부 · SQL 순서 {@code gx, gy}) — 🔴 엑셀이 다시 조회하지 않고 이것을 쓴다
     * @param scored 전 격자의 산정 셀(제외 격자 포함) — 구역명 출처
     */
    private record Analyzed(LifezoneCngstResultVO result, AnlsVrblOvrd ovrd, Coefficients cfc, double months,
                            int gridSz, LocalDate bgngYmd, LocalDate endYmd,
                            List<LifezoneCngstRow> rows, List<Scored> scored) {
    }

    @Override
    @Transactional(readOnly = true)
    public LifezoneCngstResultVO analyze(LifezoneCngstRequest req) {
        return compute(req).result();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public LifezoneCngstWithWorkbook analyzeWithWorkbook(LifezoneCngstRequest req) {
        Analyzed a = compute(req);
        return new LifezoneCngstWithWorkbook(a.result(), buildWorkbook(a));
    }

    private Analyzed compute(LifezoneCngstRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 조건이 없습니다");
        }

        // ── ① 검증 ─────────────────────────────────────────────────────────────
        int gridSz = validateGridSz(req.getGridSz());
        LifezoneClsfMthd clsfMthd = LifezoneClsfMthdResolver.resolve(TARGET, req.getClsfMthd());
        LocalDate bgngYmd = parseYmd(req.getBgngYmd(), "bgngYmd");
        LocalDate endYmd = parseYmd(req.getEndYmd(), "endYmd");
        DateRanges.validate(bgngYmd, endYmd);
        String emdCd = LifezoneRegionResolver.normalizeKey(req.getEmdCd(), "emdCd");
        String sggNm = LifezoneRegionResolver.normalizeKey(req.getSggNm(), "sggNm");
        LifezoneRegionResolver.Region region = regionResolver.resolve(sggNm, emdCd);
        int limit = resolveLimit(req.getLimit());
        // 일시 입력 — 보고서 경로의 Map 이 먼저이고, 없으면 쿼리 JSON 이다. 설정 표에는 쓰지 않는다 (TSK-4325)
        //    🔴 집계(매퍼) 전에 검증한다 — 잘못된 입력으로 무거운 집계를 돌리지 않는다
        Map<String, BigDecimal> vrblVls = req.getVrblVlsMap() != null
                ? req.getVrblVlsMap()
                : AnlsVrblOvrd.parse(req.getVrblVls());
        AnlsVrblOvrd ovrd = anlsVrblOvrdResolver.resolve(AnlsVrblScope.LIFEZONE_CNGST, vrblVls);

        // ── ② 격자 집계 (공급>0 전량) ────────────────────────────────────────────
        LifezoneSupplyQueryParam param = new LifezoneSupplyQueryParam();
        param.setGridSz(gridSz);
        param.setBgngDt(bgngYmd.atStartOfDay());
        param.setEndDtExcl(endYmd.plusDays(1).atStartOfDay());   // 당일 포함 → 반열림 상한
        param.setEmdCd(emdCd);
        param.setSggCd(region.sggCd());
        List<LifezoneCngstRow> rows = lifezoneCngstMapper.selectCngstCells(param);
        if (rows == null) {
            rows = List.of();
        }

        // ── ③ 계수 · 혼잡지수 산정 · 세 상태 판정 ─────────────────────────────────
        // 🔴 occUsedVl·경계·제외 판정이 모두 이 값을 쓴다 — 입력이 있으면 적용값이다 (TSK-4325)
        Coefficients cfc = readCoefficients(ovrd);
        double months = monthsOf(bgngYmd, endYmd);
        List<Scored> scored = new ArrayList<>(rows.size());
        for (LifezoneCngstRow row : rows) {
            scored.add(score(row, cfc, months, gridSz));
        }
        // 혼잡지수(원 H) 내림차순 — anlsVl 반올림 동점끼리도 실제 혼잡 순(동점은 매퍼 순서 gx,gy — 안정 정렬)
        scored.sort(Comparator.comparingDouble(Scored::h).reversed());

        // ── ④ 분석 대상 밖 격자 제외 — 🔴 지도·요약·등급 분모·순위 모두에서 뺀다(2026-10-08) ──
        //    근거 없음(noEnf)을 먼저 본다: 둘 다 해당하면 noEnf 로 센다. ref = 근거는 있으나 면수 하한 미만
        int noEnfCnt = 0;
        int refCnt = 0;
        List<Scored> kept = new ArrayList<>(scored.size());
        for (Scored s : scored) {
            if (s.noEnf()) {
                noEnfCnt++;
            } else if (s.refr()) {
                refCnt++;
            } else {
                kept.add(s);
            }
        }

        // ── ⑤ 등급 경계 · 판정 (남은 격자만) ───────────────────────────────────────
        List<Integer> graded = new ArrayList<>(kept.size());
        for (Scored s : kept) {
            graded.add(s.cell().getAnlsVl());
        }
        LifezoneGradeScale scale = scaleOf(graded, clsfMthd, cfc);

        List<LifezoneCngstCellVO> cells = new ArrayList<>(kept.size());
        for (Scored s : kept) {
            LifezoneCngstCellVO cell = s.cell();
            LifezoneGrade grade = scale.gradeOf(cell.getAnlsVl());
            if (grade == null) {
                grade = LifezoneGrade.LOW;   // anlsVl ≥ occ×10000 ≥ 1 이라 도달하지 않는다 — 방어
            }
            cell.setGrade(grade.name());
            cells.add(cell);
        }

        // ── ⑥ 순위 — 심각·경고·주의 전부(limit 지정 시 그 값까지 · TSK-4227 D21 · 제외 격자는 이미 빠졌다) ──
        List<LifezoneRankVO> ranking = buildRanking(cells, limit);

        // ── ⑦ 응답 조립 ──────────────────────────────────────────────────────────
        LifezoneCngstResultVO result = buildResult(gridSz, clsfMthd, bgngYmd, endYmd, region, emdCd, cfc, scale,
                kept, noEnfCnt, refCnt, cells, ranking);
        result.setVrblAjmt(ovrd.toAjmtJson());
        return new Analyzed(result, ovrd, cfc, months, gridSz, bgngYmd, endYmd, rows, scored);
    }

    /* ── 엑셀 재계산 데이터 (TSK-4340 Step 2) ──────────────── */

    /**
     * 이미 받은 원자료 행 + 개월수 + 적용 계수 → {@code wbk}. 🔴 다시 조회하지 않는다(D6).
     *
     * <p>구역명(D9 — TSK-4339 이후): 순위 행에 같은 {@code gridCd} 가 있으면 그 이름(resolver 결과 — 화면 리스트와 같다),
     * 없으면 산정 셀 이름(캐시 → 생활권 폴백). 제외 격자도 계수에 따라 표시될 수 있어 전 행에 붙인다.
     * 🔴 resolver 를 다시 태우지 않는다(외부 호출 상한은 분석 세션에서 이미 썼다).
     */
    private static LifezoneCngstWorkbookVO buildWorkbook(Analyzed a) {
        Map<String, String> names = new HashMap<>();
        for (Scored s : a.scored()) {
            names.put(s.cell().getGridCd(), s.cell().getCntrPoiNm());
        }
        for (LifezoneRankVO rank : a.result().getRanking()) {
            if (rank.getCntrPoiNm() != null) {
                names.put(rank.getGridCd(), rank.getCntrPoiNm());
            }
        }
        List<LifezoneCngstWorkbookCellVO> cells = new ArrayList<>(a.rows().size());
        for (LifezoneCngstRow row : a.rows()) {
            LifezoneCngstWorkbookCellVO c = new LifezoneCngstWorkbookCellVO();
            c.setGridCd(LifezoneSupplyServiceImpl.gridIdOf(a.gridSz(), row.getGx(), row.getGy()));
            c.setCntrPoiNm(names.get(c.getGridCd()));
            c.setEmdNm(row.getEmdNm());
            c.setPkltCpctySum(row.getPkltCpctySum());
            c.setPkltCnt(row.getPkltCnt());
            c.setNFixed(row.getNFixed());
            c.setNPda(row.getNPda());
            c.setNManual(row.getNManual());
            c.setCvlcptCnt(row.getCvlcptCnt());
            cells.add(c);
        }

        Coefficients cfc = a.cfc();
        Map<String, BigDecimal> aplcn = new HashMap<>();
        aplcn.put(VRBL_OCC, cfc.occ());
        for (int i = 0; i < cfc.bnds().length; i++) {
            aplcn.put(VRBL_GRD_BND_PREFIX + (i + 1), cfc.bnds()[i]);   // 🔴 점 단위 원값 — ×100 반올림은 엑셀이 한다
        }
        // double 로 들고 있는 계수는 BigDecimal.valueOf(double)(최단 10진 표기)로 되돌린다 — 설정값이 소수 넷째 자리라 원값과 같다
        aplcn.put(VRBL_DSCL_MLTP, BigDecimal.valueOf(cfc.mltp()));
        aplcn.put(VRBL_FIX_WGVL, BigDecimal.valueOf(cfc.fixWgvl()));
        aplcn.put(VRBL_GEO_CRCT, BigDecimal.valueOf(cfc.geoCrct()));
        aplcn.put(VRBL_CVLCPT_WGVL, BigDecimal.valueOf(cfc.cvWght()));
        aplcn.put(VRBL_MIN_CPCTY, BigDecimal.valueOf(cfc.minCpcty()));

        LifezoneCngstWorkbookVO wbk = new LifezoneCngstWorkbookVO();
        wbk.setMnthCnt(a.months());   // 🔴 반올림 금지
        wbk.setBgngYmd(a.bgngYmd().format(YMD));
        wbk.setEndYmd(a.endYmd().format(YMD));
        wbk.setVrbls(LifezoneWorkbookVrbls.of(AnlsVrblScope.LIFEZONE_CNGST, a.ovrd(), aplcn, false));
        wbk.setCells(cells);
        log.debug("혼잡도 엑셀 원자료: {}건 개월수={}", cells.size(), a.months());
        return wbk;
    }

    /* ── 검증 ─────────────────────────────────────────── */

    private static int validateGridSz(int gridSz) {
        List<Integer> allowed = TARGET.getGridSzs();
        if (!allowed.contains(gridSz)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "격자크기는 " + String.join(" | ", allowed.stream().map(String::valueOf).toList())
                            + " (m) 중 하나여야 합니다: " + gridSz);
        }
        return gridSz;
    }

    private static LocalDate parseYmd(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 는 필수입니다 (YYYYMMDD)");
        }
        try {
            return LocalDate.parse(value.trim(), YMD);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 는 YYYYMMDD 형식의 실재하는 날짜여야 합니다: " + value);
        }
    }

    /**
     * 순위 개수. 🔁 TSK-4227 D21 — 생략({@code null})이면 상한 없음({@link #UNLIMITED}), 지정하면
     * {@code max(1, limit)} 까지다(민원건수 {@code LifezoneAnalysisServiceImpl.resolveLimit} 과 같은 규칙).
     */
    private static int resolveLimit(Integer requested) {
        if (requested == null) {
            return UNLIMITED;
        }
        return Math.max(MIN_LIMIT, requested);
    }

    /* ── 계수 ─────────────────────────────────────────── */

    /**
     * 산정에 쓰는 계수 묶음.
     *
     * @param occ      기준 점유율 ({@code PCSHR_CRTR_VL})
     * @param mltp     적발 배수 m ({@code DSCL_MLTP})
     * @param fixWgvl  고정형 단속 가중치 ({@code FIX_WGVL})
     * @param geoCrct  좌표 결측 보정 ({@code GEO_CVRG_CRCT_VL})
     * @param cvWght   민원 반영 가중치 ({@code CVLCPT_WGVL})
     * @param minCpcty 분석 대상 면수 하한 ({@code MIN_CPCTY_VL}) — 미만 격자는 제외
     * @param bnds     {@code CNGST_GRD_BNDRY1~4}
     */
    private record Coefficients(BigDecimal occ, double mltp, double fixWgvl, double geoCrct,
                                double cvWght, double minCpcty, BigDecimal[] bnds) {
    }

    private Coefficients readCoefficients(AnlsVrblOvrd ovrd) {
        BigDecimal[] bnds = new BigDecimal[4];
        for (int i = 0; i < bnds.length; i++) {
            bnds[i] = cfc(ovrd, VRBL_GRD_BND_PREFIX + (i + 1));
        }
        return new Coefficients(
                cfc(ovrd, VRBL_OCC),
                cfc(ovrd, VRBL_DSCL_MLTP).doubleValue(),
                cfc(ovrd, VRBL_FIX_WGVL).doubleValue(),
                cfc(ovrd, VRBL_GEO_CRCT).doubleValue(),
                cfc(ovrd, VRBL_CVLCPT_WGVL).doubleValue(),
                cfc(ovrd, VRBL_MIN_CPCTY).doubleValue(),
                bnds);
    }

    /**
     * 계수 1건 — 일시 입력이 있으면 그 값, 없으면 설정값(TSK-4325).
     * 🔴 {@code coefficient()} 의 폴백 계약은 그대로다 — 입력이 없는 변수만 그 경로를 탄다.
     */
    private BigDecimal cfc(AnlsVrblOvrd ovrd, String vrblCd) {
        return ovrd.value(vrblCd, () -> analysisWeightService.coefficient(ANLS_TYPE_CD, vrblCd));
    }

    /**
     * 요청 구간의 <b>달력 월 수</b> — 부분월은 그 달 일수 대비 비례 (계획서 5-0 · ⚠️ 라운드2 가정).
     *
     * <p>예) {@code 2025-11-01~2026-06-30} = 8.0 · {@code 2026-01-15~2026-01-31} = 17/31.
     * 양끝 포함이다. {@code bgng > end} 는 호출 전에 {@link DateRanges#validate} 가 400 으로 막는다.
     */
    static double monthsOf(LocalDate bgng, LocalDate end) {
        double sum = 0.0;
        LocalDate cur = bgng.withDayOfMonth(1);
        while (!cur.isAfter(end)) {
            LocalDate mEnd = cur.withDayOfMonth(cur.lengthOfMonth());
            LocalDate from = bgng.isAfter(cur) ? bgng : cur;
            LocalDate to = end.isBefore(mEnd) ? end : mEnd;
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            sum += (double) days / cur.lengthOfMonth();
            cur = cur.plusMonths(1);
        }
        return sum;
    }

    /* ── 산정 ─────────────────────────────────────────── */

    /** 셀과 정렬·판정용 원값의 짝 — 정수화 전 {@code H} 를 정렬·{@code over1} 판정에 쓴다 */
    private record Scored(LifezoneCngstCellVO cell, double h, boolean noEnf, boolean refr) {
    }

    private Scored score(LifezoneCngstRow row, Coefficients cfc, double months, int gridSz) {
        int s = row.getPkltCpctySum();   // 매퍼가 공급>0 만 내린다
        double enf = (row.getNPda() + row.getNManual() + cfc.fixWgvl() * row.getNFixed()) / months;
        double cv = row.getCvlcptCnt() / months;
        // 🔴 민원에는 적발배수 m 을 곱하지 않는다 — 민원은 신고 자체가 관측이다
        double est = (cfc.mltp() * enf + cfc.cvWght() * cv) / DAYS_PER_MONTH * cfc.geoCrct();
        boolean noEnf = !(est > 0.0);
        double h = noEnf ? cfc.occ().doubleValue() : cfc.occ().doubleValue() + est / s;
        boolean refr = s < cfc.minCpcty();

        LifezoneCngstCellVO cell = new LifezoneCngstCellVO();
        String gridCd = LifezoneSupplyServiceImpl.gridIdOf(gridSz, row.getGx(), row.getGy());
        cell.setGridCd(gridCd);
        // 🔴 점수 = H×100 (상한 없음 · ⑪) — anlsVl 은 그 ×100. 정렬은 Scored.h(원 H)로 한다
        cell.setAnlsVl((int) Math.round(h * SCALE));
        cell.setCtrLat(row.getCtrLat());
        cell.setCtrLot(row.getCtrLot());
        cell.setRing(parseRing(row.getRingJson(), gridCd));
        cell.setEmdNm(row.getEmdNm());
        // 전 셀 — 캐시 이름이 있으면 그것, 없으면 생활권 폴백(TSK-4339 · 외부 호출 0)
        cell.setCntrPoiNm(row.getCacheZoneNm() != null
                ? row.getCacheZoneNm() : LifezoneAnalysisServiceImpl.fallbackZoneNm(row.getEmdNm(), gridCd));
        cell.setPkltCpctySum(s);
        cell.setPkltCnt(row.getPkltCnt());
        cell.setCrdnCnt(row.getNFixed() + row.getNPda() + row.getNManual());
        cell.setCvlcptCnt(row.getCvlcptCnt());
        cell.setExcsPrkPrsmpVl(BigDecimal.valueOf(noEnf ? 0.0 : est).setScale(EST_SCALE, RoundingMode.HALF_UP));
        // 하위호환 필드 — 'Y' 인 셀은 ④에서 빠지므로 응답에는 항상 'N' 만 남는다
        cell.setNoEnfYn(noEnf ? "Y" : "N");
        cell.setRefYn(refr ? "Y" : "N");
        return new Scored(cell, h, noEnf, refr);
    }

    /**
     * 분류방법별 경계. 입력 {@code graded} 는 이미 <b>근거 없는 격자·면수 하한 미만 격자를 뺀</b> 등급대상 값이다.
     *
     * <p>{@code CNGST_ABSOLUTE} 는 설정 경계(점 × 100 반올림 = anlsVl 단위)를 {@link LifezoneGradeScale#ofFixed} 로 넘긴다 —
     * {@code cap} 은 등급대상 최대(없으면 0).
     */
    private static LifezoneGradeScale scaleOf(List<Integer> graded, LifezoneClsfMthd mthd, Coefficients cfc) {
        if (mthd == LifezoneClsfMthd.CNGST_ABSOLUTE) {
            int[] bounds = new int[cfc.bnds().length];
            for (int i = 0; i < bounds.length; i++) {
                bounds[i] = cfc.bnds()[i].multiply(BigDecimal.valueOf(SCORE_TO_VL))
                        .setScale(0, RoundingMode.HALF_UP).intValueExact();
            }
            int cap = 0;
            for (Integer v : graded) {
                cap = Math.max(cap, v);
            }
            return LifezoneGradeScale.ofFixed(bounds, cap, mthd);
        }
        return LifezoneGradeScale.of(graded, mthd);
    }

    /**
     * 격자 경계 GeoJSON → {@code [[위도, 경도], …]} 4점 (수급 서비스와 같은 규칙 — 계산 격자 Polygon).
     * 실패하면 빈 링 + WARN(셀 자체는 유효하다).
     */
    private List<List<BigDecimal>> parseRing(String ringJson, String gridCd) {
        List<List<BigDecimal>> ring = new ArrayList<>(4);
        if (ringJson == null || ringJson.isBlank()) {
            return ring;
        }
        try {
            JsonNode coords = objectMapper.readTree(ringJson).path("coordinates");
            while (coords.isArray() && !coords.isEmpty()
                    && coords.get(0).isArray() && coords.get(0).path(0).isArray()) {
                coords = coords.get(0);
            }
            if (!coords.isArray() || coords.isEmpty()) {
                return ring;
            }
            int end = coords.size();
            if (end > 1 && coords.get(0).equals(coords.get(end - 1))) {
                end--;
            }
            for (int i = 0; i < end; i++) {
                JsonNode pt = coords.get(i);
                if (!pt.isArray() || pt.size() < 2) {
                    continue;
                }
                ring.add(List.of(pt.get(1).decimalValue(), pt.get(0).decimalValue()));
            }
        } catch (JsonProcessingException e) {
            log.warn("격자 꼭짓점 링 파싱 실패 — 빈 링으로 둡니다. gridCd={} 사유={}", gridCd, e.getClass().getSimpleName());
            log.debug("격자 꼭짓점 링 파싱 실패 상세", e);   // 4.1 — 예외 원문(toString)은 DEBUG 로만 (TSK-4336)
            return new ArrayList<>();
        }
        return ring;
    }

    /**
     * anlsVl(점수×100) → 범례 표기 <b>점수 소수 첫째 자리</b> ({@code 8891 → "88.9"}).
     * 🔴 <b>{@code .0} 은 지운다</b> — {@code 10000 → "100"} · {@code 9200 → "92"} · 상한 없음({@code 15970 → "159.7"})(설정 경계가 정수 점수라
     * 범례가 {@code 92.0점} 으로 나가지 않게). 프론트 {@code formatCngstVl} 과 같은 규칙이다.
     * 🔴 {@link Locale#ROOT} — 기본 로캘이 쉼표 소수점이면 {@code 88,9} 가 나간다.
     */
    static String formatScore(int v) {
        String s = String.format(Locale.ROOT, "%.1f", v / (double) SCORE_TO_VL);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /* ── 순위 ─────────────────────────────────────────── */

    /**
     * <b>심각·경고·주의</b> 셀 전부(요청 {@code limit} 지정 시 그 값까지 — D21). 근거 없는 격자·면수 하한 미만 격자는 {@code cells} 에 이미 없으므로 거르지 않는다
     * (2026-10-08 — 종전의 {@code refYn} 필터는 대상이 사라져 걷었다).
     *
     * <p>🔁 <b>TSK-4227 — 심각·경고·주의만</b>(D21 — {@code limit} 생략 시 상한 없음). 보통·낮음 셀은 건너뛰고
     * ({@link LifezoneGrade#isListed()}) 실린 행 수가 {@code limit} 에 닿을 때까지 순회한다.
     * 순위({@code rnk})는 실린 순서다. 지도({@code cells})·요약 수치·{@code gradeCriteria} 는 그대로 5등급이다(D6).
     *
     * <p>{@code dtl} 은 빈 배열이다. 입력 {@code cells} 는 이미 혼잡지수 내림차순이다.
     *
     * <p>구역명({@code cntrPoiNm})은 민원건수({@code LifezoneAnalysisServiceImpl.buildRanking})와 같은 방식으로
     * {@link ZoneNameResolver} 를 거친다(2026-10-08 결과 패널 시안 정합). 좌표도 같다 —
     * 세 탭(민원건수·수급·혼잡도) 모두 <b>격자 중심점</b>({@code ctrLat/ctrLot})에서 가장 가까운 POI 를 찾는다
     * (격자 중심 고정 — D10 개정 TSK-4339, 종전: 민원건수·수급은 민원 무게중심. 조건이 바뀌어도 캐시 키가 같아 외부 조회 상한에 걸리지 않는다).
     * 🔴 <b>실린 순위 행에만</b> 부른다(건너뛴 보통·낮음 셀은 부르지 않는다) — 셀 전량을 태우면 요청당 외부 조회 상한이 즉시 소진된다.
     * 셀({@code cells[].cntrPoiNm})은 폴백 이름 그대로다.
     *
     * <p>⚠️ 순위 행 수가 세션 외부 조회 상한({@code analysis.zone-name.max-lookups-per-run}, 기본 50)을 넘으면
     * 캐시에 없는 뒤쪽 행은 resolver 가 예외 없이 {@code SRC_EMD} 폴백을 돌려주고(WARN 1줄/행)
     * {@link #resolveRankNm} 이 셀의 생활권 폴백 이름으로 되돌린다 — <b>순위는 끊기지 않고 이름만 폴백</b>이다.
     */
    private List<LifezoneRankVO> buildRanking(List<LifezoneCngstCellVO> cells, int limit) {
        // 🔴 limit 은 UNLIMITED(Integer.MAX_VALUE)일 수 있다 — 초기 용량에 그대로 쓰면 OOM 이다
        List<LifezoneRankVO> ranking = new ArrayList<>(Math.min(limit, cells.size()));
        if (cells.isEmpty()) {
            return ranking;
        }
        ZoneNameResolver.Session session = zoneNameResolver.newSession();
        for (LifezoneCngstCellVO cell : cells) {
            if (ranking.size() >= limit) {
                break;
            }
            if (!LifezoneGrade.valueOf(cell.getGrade()).isListed()) {
                continue;   // 보통·낮음 — 지도·요약에만 남는다(TSK-4227 D5·D6)
            }
            LifezoneRankVO rank = new LifezoneRankVO();
            rank.setRnk(ranking.size() + 1);
            rank.setGridCd(cell.getGridCd());
            rank.setCntrPoiNm(resolveRankNm(session, cell));
            rank.setAnlsVl(cell.getAnlsVl());
            rank.setGrade(cell.getGrade());
            rank.setCtrLat(cell.getCtrLat());
            rank.setCtrLot(cell.getCtrLot());
            ranking.add(rank);
        }
        return ranking;
    }

    /**
     * 격자 중심점 → 구역명. resolver 가 자기 폴백({@code SRC_EMD} — {@code … 일대}·{@code 위치 미상})으로
     * 내려갔거나 비어 오면 셀의 생활권 폴백 이름으로 되돌린다(민원건수 {@code resolveRankNm} 과 같은 규칙).
     */
    private static String resolveRankNm(ZoneNameResolver.Session session, LifezoneCngstCellVO cell) {
        ZoneName resolved = session.resolve(cell.getCtrLat(), cell.getCtrLot(), cell.getEmdNm());
        if (resolved == null || resolved.zoneNm() == null || resolved.zoneNm().isBlank()
                || ZoneNameResolver.SRC_EMD.equals(resolved.zoneNmSrc())) {
            return cell.getCntrPoiNm();
        }
        return resolved.zoneNm();
    }

    /* ── 응답 조립 ────────────────────────────────────── */

    private LifezoneCngstResultVO buildResult(int gridSz, LifezoneClsfMthd clsfMthd,
                                              LocalDate bgngYmd, LocalDate endYmd,
                                              LifezoneRegionResolver.Region region, String emdCd,
                                              Coefficients cfc, LifezoneGradeScale scale, List<Scored> kept,
                                              int noEnf, int refr, List<LifezoneCngstCellVO> cells,
                                              List<LifezoneRankVO> ranking) {
        LifezoneCngstResultVO result = new LifezoneCngstResultVO();
        result.setBgngYmd(bgngYmd.format(YMD));
        result.setEndYmd(endYmd.format(YMD));
        result.setSggNm(region.sggNm());
        result.setEmdCd(emdCd);
        result.setAnlsTrgtCd(TARGET.name());
        result.setAnlsTrgtNm(TARGET.getAnlsTrgtNm());
        result.setGridSz(gridSz);
        result.setGridSzNm(LifezoneSupply.gridSzNm(gridSz));
        result.setClsfMthd(clsfMthd.name());
        result.setClsfMthdNm(clsfMthd.getClsfMthdNm());
        result.setAvailableClsfMthds(TARGET.getClsfMthds().stream().map(Enum::name).toList());
        result.setUnit(TARGET.getUnit());
        result.setSectionTitle(TARGET.getSectionTitle());
        result.setSectionSub(TARGET.getSectionSub());
        result.setAvailableTargets(LifezoneTargetVO.allOf());
        result.setWeights(List.of());

        // 등급 기준 — 🔴 ÷100 포매터(점수 첫째 자리). 정수 그대로 찍으면 "8891점" 이 나간다
        result.setGradeScale(scale);
        List<LifezoneGradeCriterionVO> criteria = LifezoneGradeCriterionVO.allOf(scale, TARGET.getUnit(),
                LifezoneCngstServiceImpl::formatScore);
        if (scale.mthd() == LifezoneClsfMthd.EQUAL_INTERVAL_MIN) {
            // 🔴 심각 하한은 10001(100.01점)인데 첫째 자리 포매터로는 "100점 이상" 이 돼 규칙(100점 초과)과
            //    어긋나 읽힌다 — 이 분류방법만 "초과" 로 고쳐 쓴다(경고 행은 "~100점" 그대로 = 100점 포함)
            LifezoneGradeCriterionVO severe = criteria.get(0);
            severe.setLgndTxt("%s (%s%s 초과)".formatted(severe.getGradeNm(),
                    formatScore(LifezoneGradeScale.CNGST_FULL_VL), TARGET.getUnit()));
        }
        result.setGradeCriteria(criteria);

        // 요약 — 전부 응답 cells(근거 없음·면수 하한 미만 제외) 기준. tot = 5등급 합 = grdTrgt (R10 성립)
        // over1 도 남은 격자 기준이다 — 면수 하한 미만 격자의 H>1 은 세지 않는다
        // 🔴 100점 초과 = anlsVl > 10000 — EQUAL_INTERVAL_MIN 의 심각 판정과 같은 기준(같은 상수)이라
        //    그 분류방법에서는 over1GridCnt = severeCnt 다. 원 H 로 세면 1.0 < H < 1.00005 가 갈린다
        int over1 = 0;
        for (Scored s : kept) {
            if (s.cell().getAnlsVl() > LifezoneGradeScale.CNGST_FULL_VL) {
                over1++;
            }
            switch (LifezoneGrade.valueOf(s.cell().getGrade())) {
                case SEVERE -> result.setSevereCnt(result.getSevereCnt() + 1);
                case WARN -> result.setWarnCnt(result.getWarnCnt() + 1);
                case CAUTION -> result.setCautionCnt(result.getCautionCnt() + 1);
                case NORMAL -> result.setNormalCnt(result.getNormalCnt() + 1);
                case LOW -> result.setLowCnt(result.getLowCnt() + 1);
            }
        }
        result.setTotGridCnt(cells.size());
        result.setGrdTrgtGridCnt(cells.size());   // 하위호환 — tot 와 같은 값
        result.setNoEnfGridCnt(noEnf);            // 제외된 격자 수 (cells 에 없다)
        result.setRefGridCnt(refr);               // 면수 하한 미만으로 제외된 격자 수 (근거 없음과 겹치면 noEnf 로 센다)
        result.setOver1GridCnt(over1);
        result.setOccUsedVl(cfc.occ().stripTrailingZeros());

        DataRangeVO range = lifezoneCngstMapper.selectDataRange();
        if (range != null) {
            result.setDataBgngYmd(range.getDataBgngYmd());
            result.setDataEndYmd(range.getDataEndYmd());
        }
        result.setCells(cells);
        result.setRanking(ranking);

        if (cells.isEmpty()) {
            log.info("생활권 혼잡도 격자 0건 — 조건에 공급 격자가 없거나 전부 제외(근거 없음·면수 하한 미만)입니다. "
                            + "기간={}~{} 지역(sggCd={}, emdCd={}) 격자={}m 근거없음제외={} 면수하한제외={}",
                    result.getBgngYmd(), result.getEndYmd(), region.sggCd(), emdCd, gridSz, noEnf, refr);
        } else {
            log.debug("생활권 혼잡도 분석 완료: 격자={}m 분류={} 셀={} 근거없음제외={} 면수하한제외={} H>1={} occ={}",
                    gridSz, clsfMthd, cells.size(), noEnf, refr, over1, cfc.occ());
        }
        return result;
    }
}
