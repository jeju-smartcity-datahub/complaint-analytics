package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.ZoneRcmdtnTestData.Cand;
import jeju.datahub.analysis.ZoneRcmdtnTestData.Src;
import jeju.datahub.analysis.ZoneRcmdtnTestData.Win;
import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.ZoneRcmdtnBaselineVO;
import jeju.datahub.analysis.service.ZoneRcmdtnCrtrVls;
import jeju.datahub.analysis.service.ZoneRcmdtnLotVO;
import jeju.datahub.analysis.service.ZoneRcmdtnResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnService;
import jeju.datahub.analysis.service.ZoneRcmdtnTrendVO;
import jeju.datahub.analysis.service.impl.AnalysisWeightMapper;
import jeju.datahub.analysis.service.impl.ZoneRcmdtnCalculator;
import jeju.datahub.analysis.service.impl.ZoneRcmdtnTrendCountVO;

/**
 * 구역추천 순위 조립기 테스트 (TSK-4207 Step 4 검증).
 *
 * <p>🔁 TSK-4340(2026-11-11) — 읍면동 요금제 대상 판정(E0·필요 면수·충족률·FEE/SHARE·판정 불가 사유·
 * {@code reachNeed})이 서버에서 제거돼 그 단언을 지웠다. 순위·점수·추세·배정률 단언은 그대로다.
 *
 * <h2>무엇을 확인하는가</h2>
 * <ol>
 *   <li><b>{@code rnk} 채우기</b> — 🔴 매퍼 {@code ORDER BY} 는 순서만 정하므로 번호는 이 계산기가
 *       넣어야 한다. 빠뜨려도 <b>컴파일은 통과</b>하고 응답의 순위 열만 전부 null 이 된다</li>
 *   <li><b>계수 조달</b> — 🔴 정상 경로의 {@code warnings} 는 빈 배열이다. 폴백은 "행이 지워졌을
 *       때" 의 방어이며 <b>빈 Map 주입 단위테스트</b>로만 확인한다(§0-5)</li>
 *   <li><b>추세</b> — 증가율·분모 하한·월별 추이·창 판정(7일)·1차/폴백 선택</li>
 * </ol>
 *
 * <h2>🔴 날짜·읍면동·건수를 박지 않는다 — 로컬 데이터가 날짜째 옮겨진다</h2>
 * 종전에는 "2026-10-12 로컬 DB 실측"(이도이동 {@code E0≈14.90} · 일도이동 충족률 79.0% · 도달 6위 …)을
 * 기대값으로 박아 두었고, 단속 적재분이 옮겨진 날 <b>코드 결함 없이 4건이 깨졌다</b>. 지금은 테스트를
 * 둘로 가른다.
 * <ul>
 *   <li><b>합성 입력</b>(이름에 "합성") — 테스트가 직접 만든 기준선·주차장·추세 행으로 계산기를
 *       부른다. DB 를 읽지 않으므로 기대값 리터럴이 정당하다(산식 · 경계 · 폴백 분기는 전부 여기서
 *       본다 — 특정 데이터 모양에서만 타는 경로를 실데이터에 기대지 않는다).</li>
 *   <li><b>실경로</b> — 서비스·매퍼를 태우되 ① 조회 창은 DB 의 {@code min/max} 에서 읽고 ② 읍면동은
 *       조건으로 고르며 ③ 기대값은 {@link ZoneRcmdtnTestData} 의 독립 SQL·산식 재계산으로 낸다
 *       ({@link #assertDetail}). 폴백·판정 불가처럼 데이터 모양이 필요한 분기는 <b>창을 계산해서</b>
 *       만든다 — 자료 시작일 이전 구간은 반드시 0건이다.</li>
 * </ul>
 * ⚠️ 조건에 맞는 대상이 없으면 실패가 아니라 <b>사유와 함께 건너뛴다</b>. 민원·단속이 통째로 비었으면
 * {@code @BeforeEach} 가 그 사실을 메시지로 드러내며 실패한다.
 *
 * <p>전부 {@code SELECT} 이고 {@code @Transactional} 로 감싸 DB 를 바꾸지 않는다
 * ({@code WebEnvironment.MOCK} 이라 포트를 열지 않으므로 롤백이 실제로 걸린다).
 */
@SpringBootTest
@Transactional
@DisplayName("TSK-4207 Step 4 — 구역추천 순위 조립")
class ZoneRcmdtnCalculatorTest {

    // --- 합성 입력용 기준값 (계산기의 폴백 상수 = 마이그레이션 bsc_stng_vl 과 같은 숫자) ---
    private static final BigDecimal RNG_300 = new BigDecimal("300");
    private static final BigDecimal MIN_CPCTY_20 = new BigDecimal("20");
    /** 🔴 증가율 분모 하한 — 계산기의 코드 상수({@code MIN_TREND_BASE})다. 설정도 데이터도 아니다 */
    private static final int MIN_TREND_BASE = 10;

    /** 합성 입력의 소수 비교 허용 오차 — 표시 스케일(소수 1~2자리) 반올림을 흡수한다 */
    private static final BigDecimal EPS = new BigDecimal("0.05");
    /** 실경로 재계산(double) 대조 허용 오차 — 표시 반올림 폭의 절반 + 여유 */
    private static final double TOL_1 = 0.051;

    private static final String FALLBACK = ZoneRcmdtnTestData.WARN_TREND_BFR_FALLBACK;
    private static final String SHORT = ZoneRcmdtnTestData.WARN_TREND_WINDOW_SHORT;

    @Autowired
    private ZoneRcmdtnCalculator calculator;

    /** 요청값 변환(기간 파싱·단속 창 기본값·유형 화이트리스트)까지 포함한 실제 호출 경로 */
    @Autowired
    private ZoneRcmdtnService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ZoneRcmdtnTestData data;

    /** 민원 보유 구간(자르지 않은 원래 구간) */
    private Win cvRange;
    /** 단속 보유 구간 */
    private Win crdnRange;
    /** 민원 창 — 자료 시작일부터(상한 36개월). 🔴 1차 '이전' 블록이 반드시 0건이라 <b>폴백이 보장</b>된다 */
    private Win cv;
    /** 단속 창 — 단속 보유 구간(상한 36개월) */
    private Win crdn;
    /** 지금 적용되는 계수(설정 테이블) — 실경로 기대값의 입력이다 */
    private ZoneRcmdtnCrtrVls applied;
    /** 조건으로 고른 읍면동 — NARROW 후보 좌표가 충분한({@code RANK_LOCS} 곳 이상 · 없으면 최다) 동 중 창 안 민원이 가장 많은 동({@code busyEmd}). 없으면 {@code null} */
    private String emd;

    @BeforeEach
    void resolveWindowsAndTarget() {
        data = new ZoneRcmdtnTestData(jdbcTemplate);
        cvRange = data.cvRange();
        assertThat(cvRange)
                .as("주차 민원(parking.tb_cvlcpt · 유형 99 제외 · emd_cd 있음)이 비어 있다 — 적재분을 먼저 확인하라")
                .isNotNull();
        crdnRange = data.crdnRange();
        assertThat(crdnRange)
                .as("단속(parking.tb_crdn_dsctn · emd_cd 있음)이 비어 있다 — 적재분을 먼저 확인하라")
                .isNotNull();
        cv = ZoneRcmdtnTestData.head(cvRange);
        crdn = ZoneRcmdtnTestData.head(crdnRange);
        applied = calculator.resolveCrtrVls("NARROW", new ArrayList<>());
        emd = data.busyEmd(cv, applied.getSwtcTrgtMinCpcty(), false, 2);
    }

    /** 고른 읍면동이 있어야 도는 테스트의 전제 */
    private String targetEmd() {
        assumeTrue(emd != null, "NARROW 후보 좌표가 2곳 이상이면서 민원이 있는 읍면동이 없다");
        return emd;
    }

    /**
     * 🔴 테스트 8 전용 — 계수의 <b>적용값</b>을 DB 에서 읽는다.
     *
     * <p>{@code resolveCrtrVls} 는 {@code use_yn='Y'} 면 {@code stng_vl}, 아니면 {@code bsc_stng_vl} 을
     * 쓴다(운영자가 {@code M0603} 화면에서 조절하는 값이다). 종전에는 마이그레이션 84 의
     * {@code bsc_stng_vl} 리터럴 7개를 박아 뒀는데, 🔴 <b>운영자가 어느 계수든 조절하면 깨지는
     * 테스트</b>였다(2026-10-13 {@code CVLCPT_MM_CRTR_VL} 을 50 → 1 로 바꾸자 실제로 깨졌다).
     *
     * <p>⚠️ 아래 규칙은 {@code ZoneRcmdtnCalculator.toMap} 과 <b>같은 규칙을 다시 적은 것</b>이다
     * (패키지가 달라 그 메서드를 부를 수 없다). 그래서 이 단언은 "값이 맞다" 의 1차 근거가 아니다 —
     * 🔴 <b>1차 근거는 {@code warnings} 가 비어 있다는 것</b>이고(폴백이 한 건도 없었다는 뜻),
     * 여기서는 "DB 에 있는 그 값이 그대로 왔는가" 와 "HOTSPOT 의 동명 행이 섞이지 않았는가" 를 본다.
     *
     * <p>🔴 {@code bsc_stng_vl} 리터럴 검증은 <b>시드 검증</b>이라 {@code AnalysisWeightIntegrationTest}
     * 의 몫이다 — 여기서 겹쳐 들지 않는다.
     */
    @Autowired
    private AnalysisWeightMapper analysisWeightMapper;

    /** 분석유형의 {@code vrblCd → 적용값} — 위 주석의 규칙 그대로. */
    private Map<String, BigDecimal> appliedVls(String anlsTypeCd) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (AnalysisWeightVO row : analysisWeightMapper.selectWeightsByType(anlsTypeCd)) {
            if (row == null || row.getVrblCd() == null) {
                continue;
            }
            BigDecimal v = "Y".equals(row.getUseYn()) ? row.getStngVl() : null;
            if (v == null) {
                v = row.getBscStngVl();
            }
            if (v != null) {
                m.put(row.getVrblCd(), v);
            }
        }
        return m;
    }

    // =====================================================================================
    // 합성 입력 도구
    // =====================================================================================

    /** 합성 기준값 한 벌({@code NARROW}·300m·하한 20면) */
    private static ZoneRcmdtnCrtrVls crtrVls() {
        // 🆕 TSK-4282 FEE 8칸 — 마이그레이션 94 기본값(70/30 · 22/10 · 72/28 · 0.5 · 100)
        return new ZoneRcmdtnCrtrVls("NARROW", RNG_300, MIN_CPCTY_20, MIN_TREND_BASE, null,
                bd("70"), bd("30"), bd("22"), bd("10"), bd("72"), bd("28"), bd("0.5"), bd("100"));
    }

    /**
     * 🆕 TSK-4282 — FEE 8칸을 테스트가 직접 넣는 기준값. 🔴 폴백 상수에 기대지 않는다 — Step 0 재실측으로
     * 기본값이 바뀌어도 F1 기대값이 흔들리지 않게.
     */
    private static ZoneRcmdtnCrtrVls feeCrtr(String w1, String w2, String mmJeju, String mmSgp,
                                             String rtJeju, String rtSgp, String wi, String uplmt) {
        return new ZoneRcmdtnCrtrVls("WIDE", RNG_300, MIN_CPCTY_20, MIN_TREND_BASE, null,
                bd(w1), bd(w2), bd(mmJeju), bd(mmSgp), bd(rtJeju), bd(rtSgp), bd(wi), bd(uplmt));
    }

    private static BigDecimal bd(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    /** 합성 1번의 민원 원 건수 — 종전 되곱 {@code 388.75 × 8} 과 같은 값(배정률 총건수, TSK-4284 · D3) */
    private static final long CVLCPT_CNT_3110 = 3110L;

    /** 합성 읍면동 기준선 — 단속 월평균 · 민원 월평균 · 가능 면수 (민원 원 건수 0 — 배정률을 보지 않는 케이스용) */
    private static ZoneRcmdtnBaselineVO zone(String crdnMmAvg, String cvlcptMmAvg, String ableCpcty) {
        return zone(crdnMmAvg, cvlcptMmAvg, ableCpcty, 0L);
    }

    /**
     * 합성 읍면동 기준선 + 민원 원 건수. 🔴 배정률({@code asgn.cvlcptTot})을 단언하는 케이스는 이쪽을 쓴다 —
     * 총건수는 월평균 되곱이 아니라 원 건수다(TSK-4284 · D3).
     */
    private static ZoneRcmdtnBaselineVO zone(String crdnMmAvg, String cvlcptMmAvg, String ableCpcty,
                                             long cvlcptCnt) {
        ZoneRcmdtnBaselineVO z = new ZoneRcmdtnBaselineVO();
        z.setCvlcptCnt(cvlcptCnt);
        z.setEmdCd("00000000");
        z.setEmdNm("합성동");
        z.setSggNm("합성시");
        z.setCrdnMmAvg(new BigDecimal(crdnMmAvg));
        z.setCvlcptMmAvg(new BigDecimal(cvlcptMmAvg));
        z.setAbleCpcty(new BigDecimal(ableCpcty));
        z.setPaidCpcty(BigDecimal.ZERO);
        return z;
    }

    /** 합성 주차장 — 매퍼가 정렬까지 끝낸 한 행(순위·누적·추세는 비어 있다) */
    private static ZoneRcmdtnLotVO lot(long pkltId, int cpcty, long asgnCvlcptCnt) {
        ZoneRcmdtnLotVO l = new ZoneRcmdtnLotVO();
        l.setPkltId(pkltId);
        l.setPkltNm("합성 " + pkltId);
        l.setPkltCpcty(cpcty);
        l.setAsgnCvlcptCnt(asgnCvlcptCnt);
        l.setAsgnCrdnCnt(0L);
        return l;
    }

    private static List<ZoneRcmdtnLotVO> lots(ZoneRcmdtnLotVO... lots) {
        return new ArrayList<>(List.of(lots));
    }

    /** 합성 추세 행 — 1차(최근·이전) 2칸 + 폴백(최근·이전) 2칸 */
    private static ZoneRcmdtnTrendCountVO trend(long pkltId, String ym, long rcnt, long bfr,
                                                long rcntSplit, long bfrSplit) {
        ZoneRcmdtnTrendCountVO r = new ZoneRcmdtnTrendCountVO();
        r.setPkltId(pkltId);
        r.setYm(ym);
        r.setCntRcnt(rcnt);
        r.setCntBfr(bfr);
        r.setCntRcntSplit(rcntSplit);
        r.setCntBfrSplit(bfrSplit);
        return r;
    }

    private static ZoneRcmdtnLotVO byId(List<ZoneRcmdtnLotVO> lots, long pkltId) {
        return lots.stream().filter(l -> l.getPkltId() == pkltId).findFirst()
                .orElseThrow(() -> new AssertionError("주차장(" + pkltId + ") 행이 없다"));
    }

    // =====================================================================================
    // 실경로 도구 — 기대값을 데이터에서 다시 계산한다
    // =====================================================================================

    private ZoneRcmdtnResultVO detail(String emdCd, Win cvWin, Win crdnWin, String trgtTypeSe) {
        return service.selectZoneRcmdtn(emdCd, cvWin.bgngYmd(), cvWin.endYmd(),
                crdnWin.bgngYmd(), crdnWin.endYmd(), trgtTypeSe);
    }

    private static List<String> trendWarnings(ZoneRcmdtnResultVO r) {
        return r.getWarnings().stream().filter(w -> w.startsWith("TREND_")).toList();
    }

    /** 🆕 D14 — 우선도 단계 순서(위가 먼저). 응답 순서대로 이 목록의 위치가 비감소여야 한다 */
    private static final List<String> GRD_ORDER = List.of("TOP", "HIGH", "LOW");

    /** {@link #assertDetail} 이 알아낸 사실 — 테스트가 이어서 쓴다 */
    private record Expect(boolean fallback, Win rcnt, Win bfr, long bfrTot) {
    }

    /**
     * 🔴 상세 조회 결과 한 벌을 <b>데이터에서 다시 계산한 기대값</b>과 통째로 맞춘다.
     *
     * <ul>
     *   <li>읍면동 — {@code zone} 은 위치 3필드({@code emdCd}·{@code emdNm}·{@code sggNm})다(🔁 TSK-4340 판정 제거)</li>
     *   <li>목록 — 후보 개소 수 · {@code rnk} 1..n · 누적 면수 · 정렬 계약 ·
     *       주차장(좌표)별 배정 민원/단속 = 최근접 배정의 독립 재계산</li>
     *   <li>배정 커버리지 — 총건수 = 창 안 민원 수 · 배정 합 · 배정률</li>
     *   <li>추세 — 창이 7일 블록을 못 만들면 전부 비고 {@code TREND_WINDOW_SHORT}, 1차 '이전' 블록에
     *       배정이 0건이면 폴백({@code TREND_BFR_FALLBACK} · 블록 = 반 분할), 아니면 1차(블록 = 선택
     *       기간 ↔ 직전 같은 길이). 블록 건수 = 그 블록의 독립 재계산 · 증가율 = 블록 건수에서 재계산 ·
     *       {@code trendMm} = 최근 블록이 걸친 달 전부(합 = 최근 블록 건수)</li>
     * </ul>
     *
     * @param monthly {@code trendMm} 의 달별 칸까지 "그 달 ∩ 블록" 독립 재계산과 맞출지(쿼리가 달 수만큼 는다)
     */
    private Expect assertDetail(ZoneRcmdtnResultVO r, String e, Win cvWin, Win crdnWin,
                                boolean wide, boolean monthly) {
        ZoneRcmdtnCrtrVls c = r.getCrtrVls();
        BigDecimal minCpcty = c.getSwtcTrgtMinCpcty();
        BigDecimal rng = c.getAltmntRngVl();
        // 응답 기준값 = 설정 테이블의 적용값
        assertThat(minCpcty).usingComparator(BigDecimal::compareTo)
                .isEqualTo(data.appliedVl("RECOMMEND", "SWTC_TRGT_MIN_CPCTY"));
        assertThat(rng).usingComparator(BigDecimal::compareTo)
                .isEqualTo(data.appliedVl("RECOMMEND", "ALTMNT_RNG_VL"));
        assertThat(c.getTrgtTypeSe()).isEqualTo(wide ? "WIDE" : "NARROW");

        // ── 읍면동(위치 3필드 — 🔁 TSK-4340 판정 제거) ──
        long cvCnt = data.cvCountByEmd(cvWin).getOrDefault(e, 0L);
        Cand cand = data.cand(e, minCpcty, wide);
        ZoneRcmdtnResultVO.Zone z = r.getZone();
        assertThat(z.getEmdCd()).isEqualTo(e);
        Map<String, Object> admdst = data.admdst(e);
        assertThat(z.getEmdNm()).isEqualTo(admdst.get("emd_nm"));
        assertThat(z.getSggNm()).isEqualTo(admdst.get("sgg_nm"));

        // ── 목록 · 순위 ──
        List<ZoneRcmdtnLotVO> lots = r.getLots();
        assertThat(lots).as("🔴 전 후보가 그대로 내려간다(증가율 null 이어도)").hasSize((int) cand.cnt());
        BigDecimal cum = BigDecimal.ZERO;
        for (int i = 0; i < lots.size(); i++) {
            ZoneRcmdtnLotVO l = lots.get(i);
            assertThat(l.getRnk()).as("%s — rnk 는 1 부터 빠짐없이 이어진다", l.getPkltNm()).isEqualTo(i + 1);
            cum = cum.add(BigDecimal.valueOf(l.getPkltCpcty()));
            assertThat(l.getCumCpcty()).as("%s — 누적 면수", l.getPkltNm())
                    .usingComparator(BigDecimal::compareTo).isEqualTo(cum);
            if (i > 0) {
                // 🔁 TSK-4282 — 서비스가 scoreLots 를 부르므로 순서는 추세 반영 검토지수(↓)다.
                //    ⚠️ 응답 값은 소수 1자리 반올림이라 두 값이 같으면 2차 키(배정 민원 → 면수 → pklt_id)는
                //    단언하지 않는다(원값은 다를 수 있다 — Step 5 ZoneRcmdtnApiTest 와 같은 규칙).
                ZoneRcmdtnLotVO prev = lots.get(i - 1);
                assertThat(prev.getIncrsRfltRvwScr()).as("%s — 서비스 경로는 점수를 채운다", prev.getPkltNm())
                        .isNotNull();
                assertThat(l.getIncrsRfltRvwScr()).as("%s — 서비스 경로는 점수를 채운다", l.getPkltNm())
                        .isNotNull();
                assertThat(prev.getIncrsRfltRvwScr().compareTo(l.getIncrsRfltRvwScr()))
                        .as("🔴 정렬은 추세 반영 검토지수(↓)다 — TSK-4282: %s(%s) → %s(%s)",
                                prev.getPkltNm(), prev.getIncrsRfltRvwScr(),
                                l.getPkltNm(), l.getIncrsRfltRvwScr())
                        .isGreaterThanOrEqualTo(0);
                // 🆕 D14 — 단계는 순위값(우선도)으로 매기므로 응답 순서대로 비감소(TOP → HIGH → LOW)
                assertThat(GRD_ORDER.indexOf(prev.getRvwGrdCd()))
                        .as("🔴 단계는 응답 순서대로 비감소다(D14): %s(%s) → %s(%s)",
                                prev.getPkltNm(), prev.getRvwGrdCd(), l.getPkltNm(), l.getRvwGrdCd())
                        .isGreaterThanOrEqualTo(0)
                        .isLessThanOrEqualTo(GRD_ORDER.indexOf(l.getRvwGrdCd()));
            }
        }
        if (!lots.isEmpty()) {
            assertThat(lots.get(lots.size() - 1).getCumCpcty())
                    .as("🔴 누적 면수 최대값 = 후보 면수 합(독립 SQL)")
                    .usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.valueOf(cand.cpcty()));
        }
        Map<String, Long> cvAsgn = data.asgnByLoc(Src.CV, e, cvWin, minCpcty, wide, rng);
        assertThat(ZoneRcmdtnTestData.byLoc(lots, ZoneRcmdtnLotVO::getAsgnCvlcptCnt))
                .as("주차장(좌표)별 배정 민원 — 🔴 전 구간(민원 창) 집계다").isEqualTo(cvAsgn);
        assertThat(ZoneRcmdtnTestData.byLoc(lots, ZoneRcmdtnLotVO::getAsgnCrdnCnt))
                .as("주차장(좌표)별 배정 단속 — 🔴 단속 창으로 센다")
                .isEqualTo(data.asgnByLoc(Src.CRDN, e, crdnWin, minCpcty, wide, rng));

        // ── 배정 커버리지 ──
        long asgnTot = ZoneRcmdtnTestData.sum(cvAsgn);
        assertThat(r.getAsgn().getCvlcptTot()).as("조회 구간 민원 총건수").isEqualTo(cvCnt);
        assertThat(r.getAsgn().getCvlcptAsgn()).as("배정된 민원 합").isEqualTo(asgnTot)
                .isLessThanOrEqualTo(cvCnt);
        if (cvCnt == 0) {
            assertThat(r.getAsgn().getAsgnRt()).as("민원 0건이면 배정률은 0 이 아니라 null 이다").isNull();
        } else {
            assertThat(r.getAsgn().getAsgnRt().doubleValue()).as("배정률(%)")
                    .isCloseTo(asgnTot * 100.0 / cvCnt, within(TOL_1));
        }

        // ── 추세 ──
        if (!cvWin.trendOk()) {
            assertThat(trendWarnings(r)).containsExactly(SHORT);
            assertThat(c.getTrendDayCnt()).as("🔴 '0일 블록' 이 아니라 '내지 않았다' 가 사실이다").isNull();
            assertThat(lots).allSatisfy(l -> {
                assertThat(l.getAsgnCvlcptCnt()).as("전 구간 집계는 그대로 낸다").isNotNull();
                assertThat(l.getAsgnCvlcptCntRcnt()).as("🔴 0 이 아니라 null 이다").isNull();
                assertThat(l.getAsgnCvlcptCntBfr()).isNull();
                assertThat(l.getCvlcptIncrsRt()).isNull();
                assertThat(l.getTrendMm()).isEmpty();
            });
            return new Expect(false, null, null, 0);
        }
        // 🔴 폴백은 읍면동 단위로 한 번 — 1차 '이전' 블록의 배정 민원이 한 건도 없을 때
        //    🆕 또는 TSK-4282 D13 — 1차 '이전' 블록 시작이 자료 시작일 앞일 때(OR)
        long primaryBfr = data.asgnTotal(Src.CV, e, cvWin.bfr(), minCpcty, wide, rng);
        boolean bfrBeforeData = cvWin.bfr().bgng().isBefore(cvRange.bgng());
        boolean fallback = primaryBfr == 0 || bfrBeforeData;
        Win rcnt = fallback ? cvWin.splitRcnt() : cvWin;
        Win bfr = fallback ? cvWin.splitBfr() : cvWin.bfr();
        if (fallback) {
            assertThat(trendWarnings(r)).as("1차 이전 블록(%s ~ %s) 0건 또는 자료 시작(%s) 앞 → 폴백",
                    cvWin.bfr().bgng(), cvWin.bfr().end(), cvRange.bgng()).containsExactly(FALLBACK);
        } else {
            assertThat(trendWarnings(r)).as("🔴 1차가 성립하면 폴백 경고가 없다(1차 이전 %d건)", primaryBfr)
                    .isEmpty();
        }
        assertThat(c.getTrendDayCnt()).as("🔴 실제로 쓴 블록 일수 — 1차면 D, 폴백이면 D/2")
                .isEqualTo(fallback ? cvWin.half() : cvWin.days());

        Map<String, Long> bfrAsgn = data.asgnByLoc(Src.CV, e, bfr, minCpcty, wide, rng);
        assertThat(ZoneRcmdtnTestData.byLoc(lots, ZoneRcmdtnLotVO::getAsgnCvlcptCntRcnt))
                .as("최근 블록 %s ~ %s", rcnt.bgng(), rcnt.end())
                .isEqualTo(fallback ? data.asgnByLoc(Src.CV, e, rcnt, minCpcty, wide, rng) : cvAsgn);
        assertThat(ZoneRcmdtnTestData.byLoc(lots, ZoneRcmdtnLotVO::getAsgnCvlcptCntBfr))
                .as("이전 블록 %s ~ %s", bfr.bgng(), bfr.end()).isEqualTo(bfrAsgn);
        for (ZoneRcmdtnLotVO l : lots) {
            assertThat(l.getAsgnCvlcptCntRcnt()).as("%s — 창이 성립하면 0 이어도 null 이 아니다", l.getPkltNm())
                    .isNotNull();
            assertThat(l.getAsgnCvlcptCntBfr()).isNotNull();
            // 🔴 경계가 하나다 — 분모 하한 이상이면 값이 있고, 미만이면 null 이다
            BigDecimal expected = ZoneRcmdtnTestData.incrsRt(l.getAsgnCvlcptCntRcnt(),
                    l.getAsgnCvlcptCntBfr(), c.getMinTrendBase());
            if (expected == null) {
                assertThat(l.getCvlcptIncrsRt()).as("%s — 분모 %d < 하한 %d", l.getPkltNm(),
                        l.getAsgnCvlcptCntBfr(), c.getMinTrendBase()).isNull();
            } else {
                assertThat(l.getCvlcptIncrsRt()).as("%s — (최근 %d − 이전 %d) ÷ 이전 × 100", l.getPkltNm(),
                        l.getAsgnCvlcptCntRcnt(), l.getAsgnCvlcptCntBfr())
                        .isNotNull().isCloseTo(expected, within(new BigDecimal("0.1")));
            }
            assertThat(l.getTrendMm()).extracting(ZoneRcmdtnTrendVO::getYm)
                    .as("%s — 최근 블록이 걸친 달 전부(빈 달도 칸이 있다)", l.getPkltNm())
                    .containsExactlyElementsOf(rcnt.yms());
            assertThat(l.getTrendMm().stream().mapToLong(ZoneRcmdtnTrendVO::getCnt).sum())
                    .as("%s — 월별 합 = 최근 블록 건수", l.getPkltNm())
                    .isEqualTo(l.getAsgnCvlcptCntRcnt());
        }
        if (monthly) {
            List<String> yms = rcnt.yms();
            for (int i = 0; i < yms.size(); i++) {
                int idx = i;
                assertThat(ZoneRcmdtnTestData.byLoc(lots, l -> l.getTrendMm().get(idx).getCnt()))
                        .as("%s 칸 = 그 달 ∩ 최근 블록의 독립 재계산(양 끝은 부분 달이다)", yms.get(i))
                        .isEqualTo(data.asgnByLoc(Src.CV, e, rcnt.within(yms.get(i)), minCpcty, wide, rng));
            }
        }
        return new Expect(fallback, rcnt, bfr, ZoneRcmdtnTestData.sum(bfrAsgn));
    }

    private Expect assertDetail(ZoneRcmdtnResultVO r, String e, Win cvWin, Win crdnWin, boolean wide) {
        return assertDetail(r, e, cvWin, crdnWin, wide, false);
    }

    // =====================================================================================
    // 읍면동 블록 · 배정률 (합성 입력) — 🔁 TSK-4340 판정 제거
    // =====================================================================================

    @Test
    @DisplayName("1. 합성 — zone 은 위치 3필드 · 배정률 30.0%(933/3110) · 민원 0건이면 배정률 null")
    void zoneCarriesLocationAndAsgnRate() {
        List<ZoneRcmdtnLotVO> in = lots(lot(1, 200, 500), lot(2, 100, 300), lot(3, 97, 133));
        ZoneRcmdtnResultVO r = calculator.assemble(zone("148.95", "388.75", "397", CVLCPT_CNT_3110), in,
                crtrVls(), new ArrayList<>());
        ZoneRcmdtnResultVO.Zone z = r.getZone();

        assertThat(z.getEmdCd()).isEqualTo("00000000");
        assertThat(z.getEmdNm()).isEqualTo("합성동");
        assertThat(z.getSggNm()).isEqualTo("합성시");

        assertThat(r.getLots()).hasSize(3);
        // 🔴 배정률 30% = "민원 70% 는 이 주차장들로 설명되지 않는다". 총건수 = zone 의 원 건수 3110
        //    (🔁 TSK-4284 · D3 — 월평균 × 개월수 되곱이 아니다)
        assertThat(r.getAsgn().getCvlcptTot()).as("배정률 총건수 = zone 에 넣은 원 건수 그대로")
                .isEqualTo(CVLCPT_CNT_3110);
        assertThat(r.getAsgn().getCvlcptAsgn()).isEqualTo(933L);
        assertThat(r.getAsgn().getAsgnRt()).isCloseTo(new BigDecimal("30.0"), within(EPS));
        // 민원 0건이면 배정률은 0 이 아니라 null 이다(0% 와 "분모 없음" 은 다르다)
        assertThat(calculator.assemble(zone("148.95", "0", "397"), lots(lot(1, 200, 0)),
                crtrVls(), new ArrayList<>()).getAsgn().getAsgnRt()).isNull();
    }

    /**
     * 🔁 TSK-4340(2026-11-11 사용자 결정) — 읍면동 요금제 대상 판정을 서버에서도 지웠다. 판정 칸이 다시 생기면
     * 프론트·보고서가 지운 값을 다시 읽게 되므로 리플렉션으로 못박는다.
     */
    @Test
    @DisplayName("2. 🔴 판정 제거 — zone VO 는 emdCd·emdNm·sggNm 3필드뿐 · 주차장 VO 에 reachNeed 가 없다")
    void judgementFieldsAreGone() {
        List<String> zoneFields = java.util.Arrays.stream(ZoneRcmdtnResultVO.Zone.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(java.lang.reflect.Field::getName)
                .toList();
        assertThat(zoneFields).containsExactlyInAnyOrder("emdCd", "emdNm", "sggNm");
        assertThat(java.util.Arrays.stream(ZoneRcmdtnLotVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).doesNotContain("reachNeed");
    }

    // =====================================================================================
    // 🔴 rnk 채우기
    // =====================================================================================

    /**
     * 🔴 매퍼 {@code ORDER BY} 는 <b>순서만</b> 정하고 번호 칸은 비어 있다
     * ({@code ZoneRcmdtnMapperTest} 6번이 "매퍼는 rnk 를 채우지 않는다" 를 단언한다).
     * 그 번호를 Step 4 의 순위 루프가 넣는다 — <b>빠뜨려도 컴파일은 통과</b>하고 응답의 순위 열만
     * 전부 null 이 된다.
     */
    @Test
    @DisplayName("5. 🔴 합성 — rnk 1..4 · 누적 면수 50/90/120/140 · 자르지 않는다")
    void rankLoopFillsRnkAndCumCpcty() {
        List<ZoneRcmdtnLotVO> out = calculator.assemble(zone("100", "300", "140"),
                lots(lot(1, 50, 9), lot(2, 40, 7), lot(3, 30, 0), lot(4, 20, 0)),
                crtrVls(), new ArrayList<>()).getLots();

        assertThat(out).extracting(ZoneRcmdtnLotVO::getRnk)
                .as("🔴 null 이 섞여 있으면 setRnk 를 빠뜨린 것이다")
                .doesNotContainNull()
                .containsExactly(1, 2, 3, 4);
        assertThat(out).extracting(l -> l.getCumCpcty().intValueExact())
                .as("누적 면수 — 마지막 행이 후보 면수 합(= ableCpcty 140)과 같다")
                .containsExactly(50, 90, 120, 140);
    }

    /**
     * 실경로 — 고른 읍면동의 상세 조회 한 벌을 데이터에서 다시 계산한 기대값과 맞춘다
     * ({@link #assertDetail}: 읍면동 · {@code rnk} 1..n · 누적 면수 · 주차장별 배정 · 배정률).
     */
    @Test
    @DisplayName("5-1. 🔴 실경로 — 읍면동·rnk·누적 면수·주차장별 배정·배정률이 독립 재계산과 같다 (NARROW)")
    void detailMatchesIndependentRecomputation() {
        String e = targetEmd();
        ZoneRcmdtnResultVO r = detail(e, cv, crdn, "NARROW");

        assertDetail(r, e, cv, crdn, false);
        assertThat(r.getExcnId()).as("실행이력 적재는 Step 5 의 책임이다").isNull();
        assertThat(r.getLots()).as("고른 동은 후보가 2개소 이상이다").hasSizeGreaterThanOrEqualTo(2);
    }

    /**
     * 🔴 <b>주차장별 효과(감소 건수) 필드를 만들지 않았다</b>(§0-0 제약 1).
     * {@code E0} 가 읍면동 단위라 주차장 단위 효과 산식이 성립하지 않는다 — 순위 근거는
     * <b>배정 민원 수</b>까지다. 필드가 생기면 "이 주차장을 유료화하면 N건 줄어든다" 로 읽힌다.
     */
    @Test
    @DisplayName("6. 🔴 주차장 VO 에 감소 건수 필드가 없다 (리플렉션으로 못박는다)")
    void lotVoHasNoEffectField() {
        List<String> fields = java.util.Arrays.stream(ZoneRcmdtnLotVO.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(java.lang.reflect.Field::getName)
                .toList();

        assertThat(fields).as("매퍼 14필드 + 서비스 4필드 (🔁 TSK-4259 읍면동 2칸 · 🔁 TSK-4340 reachNeed 삭제) + 🆕 TSK-4282 검토지수 7필드(D14 — grdScr 삭제)")
                .containsExactlyInAnyOrder("pkltId", "pkltNm", "pkltTypeNm", "pkltCpcty", "pkltAddr",
                        "emdCd", "emdNm", "lat", "lot", "asgnCvlcptCnt", "asgnCvlcptCntRcnt", "asgnCvlcptCntBfr",
                        "asgnCrdnCnt", "cvlcptPerSlot",
                        "rnk", "cumCpcty", "cvlcptIncrsRt", "trendMm",
                        "sggNm", "cvlcptCntScr", "cvlcptRtScr", "rvwScr", "rvwGrdCd",
                        "incrsCfc", "incrsRfltRvwScr");
        assertThat(fields).noneSatisfy(name -> assertThat(name.toLowerCase())
                .as("감소/효과 필드가 생기면 이 분석이 말할 수 없는 것을 말하게 된다")
                .containsAnyOf("dcrs", "effct", "reduc", "effect"));
    }

    // =====================================================================================
    // 계수 조달
    // =====================================================================================

    /**
     * 🔴 <b>정상 호출의 {@code warnings} 는 빈 배열이다</b>(§0-5).
     * 🔁 TSK-4340 — 판정 계수(RECOMMEND 3종·POLICY_SIM 2종)는 더 읽지 않는다.
     */
    @Test
    @DisplayName("8. 🔴 RECOMMEND 계수가 실제로 읽힌다 — warnings 는 []")
    void realCoefficientsLeaveNoWarning() {
        List<String> warnings = new ArrayList<>();
        ZoneRcmdtnCrtrVls c = calculator.resolveCrtrVls("NARROW", warnings);

        assertThat(warnings).as("🔴 폴백이 일어났다면 분석유형·vrbl_cd 를 잘못 짚은 것이다").isEmpty();

        // 🔴 기대값을 리터럴로 박지 않는다 — DB 의 **적용값**과 비교한다(위 appliedVls 주석 참조).
        Map<String, BigDecimal> rc = appliedVls("RECOMMEND");
        assertThat(c.getAltmntRngVl()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(rc.get("ALTMNT_RNG_VL"));
        assertThat(c.getSwtcTrgtMinCpcty()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(rc.get("SWTC_TRGT_MIN_CPCTY"));
        assertThat(c.getFeeCvlcptMmCrtrJeju()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(rc.get("FEE_CVLCPT_MM_CRTR_JEJU"));
        assertThat(c.getTrgtTypeSe()).isEqualTo("NARROW");

        // 실 계수로 조립한 결과에도 경고가 없다 (조립은 경고를 더 담지 않는다)
        assertThat(calculator.assemble(zone("148.95", "388.75", "397"), new ArrayList<>(), c, warnings)
                .getWarnings()).isEmpty();
    }

    /**
     * 폴백 경로 — <b>빈 Map 주입</b>. 🔴 계수 10종(공통 2 + FEE 8)이 모두 폴백해도 {@code warnings} 는
     * {@code COEF_FALLBACK} <b>1건</b>이다({@code addOnce}). 중복을 막지 않으면 응답이
     * {@code ["COEF_FALLBACK","COEF_FALLBACK",…]} 이 된다.
     *
     * <p>⚠️ DB 를 건드리지 않는 순수 단위 검증이다 — {@code use_yn='N'} 으로 바꿔 보는 쪽은
     * 설정을 쓰므로 여기서 하지 않는다.
     */
    @Test
    @DisplayName("9. 🔴 폴백 — 빈 Map 주입 시 상수로 떨어지고 COEF_FALLBACK 은 1건뿐")
    void emptyMapsFallBackToConstantsOnce() {
        List<String> warnings = new ArrayList<>();
        ZoneRcmdtnCrtrVls c = calculator.resolveCrtrVls("WIDE", Map.of(), warnings);

        assertThat(warnings).as("🔴 10종이 폴백해도 1건이다").containsExactly("COEF_FALLBACK");

        // 🔴 상수는 마이그레이션 bsc_stng_vl 과 같은 숫자여야 한다 — 어긋나면 "행이 있을 때" 와
        //    "없을 때" 의 순위가 달라진다
        assertThat(c.getAltmntRngVl()).usingComparator(BigDecimal::compareTo).isEqualTo(RNG_300);
        assertThat(c.getSwtcTrgtMinCpcty()).usingComparator(BigDecimal::compareTo).isEqualTo(MIN_CPCTY_20);
        assertThat(c.getFeeCvlcptCntWgvl()).usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("70"));
        assertThat(c.getTrgtTypeSe()).as("유형 범위는 설정이 아니라 요청값이라 폴백 대상이 아니다")
                .isEqualTo("WIDE");
    }

    /**
     * 🔴 {@code SWTC_TRGT_MIN_CPCTY} 는 {@code min_vl=0} 이라 <b>0 이 적법한 설정값</b>이다.
     * {@code signum() <= 0} 가드를 태우면 사용자가 0 으로 내린 순간 조용히 기본값(20)으로 되돌아가고
     * 평시 호출의 {@code warnings} 가 비지 않는다.
     */
    @Test
    @DisplayName("10. 🔴 0 이 적법한 계수(면수 하한) — 0 을 넣어도 폴백하지 않고 경고도 없다")
    void zeroIsAValidSettingForMinCpcty() {
        List<String> warnings = new ArrayList<>();
        ZoneRcmdtnCrtrVls c = calculator.resolveCrtrVls("NARROW",
                // 🔁 TSK-4282 — FEE 8키도 넣는다. 없으면 COEF_FALLBACK 이 뜬다.
                Map.ofEntries(
                        Map.entry("ALTMNT_RNG_VL", RNG_300),
                        Map.entry("SWTC_TRGT_MIN_CPCTY", BigDecimal.ZERO),
                        Map.entry("FEE_CVLCPT_CNT_WGVL", new BigDecimal("70")),
                        Map.entry("FEE_CVLCPT_RT_WGVL", new BigDecimal("30")),
                        Map.entry("FEE_CVLCPT_MM_CRTR_JEJU", new BigDecimal("22")),
                        Map.entry("FEE_CVLCPT_MM_CRTR_SEOGWIPO", new BigDecimal("10")),
                        Map.entry("FEE_CVLCPT_RT_CRTR_JEJU", new BigDecimal("72")),
                        Map.entry("FEE_CVLCPT_RT_CRTR_SEOGWIPO", new BigDecimal("28")),
                        Map.entry("FEE_CVLCPT_INCRS_WGVL", new BigDecimal("0.5")),
                        Map.entry("FEE_CVLCPT_INCRS_RT_UPLMT_VL", new BigDecimal("100"))),
                warnings);

        assertThat(warnings).as("0 은 '값 없음' 이 아니다").isEmpty();
        assertThat(c.getSwtcTrgtMinCpcty()).usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
    }

    // =====================================================================================
    // 서비스 경유 — 요청값 변환(🔴 단속 창 기본값 · 개월수 · 유형 화이트리스트)
    // =====================================================================================

    /**
     * 🔴 <b>단속 창 기본값이 민원 창과 다르다.</b> 단속 창을 생략하면 인터페이스의 기본 창
     * ({@code DEFAULT_CRDN_BGNG_YMD}~{@code DEFAULT_CRDN_END_YMD})이 적용된다 — 민원 창을 돌려쓰지
     * 않는다. 그래서 생략한 호출의 결과가 <b>기본 창으로 다시 센</b> 기대값과 같아야 한다
     * (단속 건수 · 단속 개월수 분모 · 주차장별 배정 단속까지).
     *
     * <p>⚠️ 기본 창은 main 의 날짜 상수다 — 단속 적재분이 그 창 밖으로 옮겨지면 배정 단속이 0건이 된다.
     * 그때도 이 테스트는 성립한다(0건으로 다시 세어 같은 값을 기대한다).
     */
    @Test
    @DisplayName("11. 🔴 서비스 경유 — 단속 창을 생략하면 기본 단속 창으로 센다(민원 창을 돌려쓰지 않는다) · 유형 기본 NARROW")
    void serviceAppliesDefaultCrdnWindow() {
        String e = targetEmd();
        Win defaultCrdn = new Win(
                LocalDate.parse(ZoneRcmdtnService.DEFAULT_CRDN_BGNG_YMD, ZoneRcmdtnTestData.YMD),
                LocalDate.parse(ZoneRcmdtnService.DEFAULT_CRDN_END_YMD, ZoneRcmdtnTestData.YMD));

        ZoneRcmdtnResultVO r = service.selectZoneRcmdtn(e, cv.bgngYmd(), cv.endYmd(), null, null, null);

        assertThat(r.getExcnId()).as("실행이력 적재는 Step 5 의 책임이다").isNull();
        assertThat(r.getCrtrVls().getTrgtTypeSe()).as("생략하면 NARROW 가 기본이다").isEqualTo("NARROW");
        // 🔴 기본 단속 창으로 다시 센 기대값과 통째로 같다
        assertDetail(r, e, cv, defaultCrdn, false);

        // 기본 창을 명시한 호출과 같은 배정 단속이다
        ZoneRcmdtnResultVO explicit = detail(e, cv, defaultCrdn, null);
        assertThat(r.getLots()).extracting(ZoneRcmdtnLotVO::getAsgnCrdnCnt)
                .containsExactlyElementsOf(explicit.getLots().stream().map(ZoneRcmdtnLotVO::getAsgnCrdnCnt).toList());
    }

    /**
     * 🔴 화이트리스트 밖의 {@code trgtTypeSe} 는 <b>400</b> 이다 — 매퍼의 {@code <choose>} 는
     * {@code WIDE} 가 아니면 {@code NARROW} 로 떨어지므로, 검증 없이 흘리면 오타가
     * <b>조용히 {@code NARROW}</b> 로 동작한다.
     */
    @Test
    @DisplayName("12. 🔴 서비스 경유 — trgtTypeSe 오타는 400 · 날짜 형식 오류도 400")
    void serviceRejectsBadInput() {
        String anyEmd = data.emdCds().get(0);
        assertThatThrownBy(() ->
                service.selectZoneRcmdtn(anyEmd, cv.bgngYmd(), cv.endYmd(), null, null, "ALL"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        // 형식 오류 — 대시가 든 ISO 표기(YYYY-MM-DD)는 YYYYMMDD 가 아니다
        assertThatThrownBy(() ->
                service.selectZoneRcmdtn(anyEmd, cv.bgng().toString(), cv.endYmd(), null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    /**
     * {@code /zones} 경로 — 🔴 {@code TB_ADMDST} 기준이라 <b>행정동 전부</b>다(원천 0건인 동도 빠지지 않는다).
     * 🔁 TSK-4340 — 원소는 위치 3필드뿐이고 판정이 없다. 단속 자료 시작 전 창이어도 행 집합이 같다.
     */
    @Test
    @DisplayName("13. 서비스 경유 — /zones 는 tb_admdst 전 행 · 원소 = {emdCd, emdNm, sggNm} · 단속 0건 창이어도 행이 같다")
    void serviceZonesKeepsEveryEmd() {
        int admdstCnt = data.admdstCount();
        List<ZoneRcmdtnResultVO.Zone> zones =
                service.selectZones(cv.bgngYmd(), cv.endYmd(), crdn.bgngYmd(), crdn.endYmd(), "NARROW");

        assertThat(zones).hasSize(admdstCnt);
        assertThat(zones).extracting(ZoneRcmdtnResultVO.Zone::getEmdCd).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(data.emdCds());
        assertThat(zones).allSatisfy(z -> {
            assertThat(z.getSggNm()).isNotBlank();
            assertThat(z.getEmdNm()).isNotBlank();
        });
        String first = zones.get(0).getEmdCd();
        Map<String, Object> admdst = data.admdst(first);
        assertThat(zones.get(0).getEmdNm()).isEqualTo(admdst.get("emd_nm"));
        assertThat(zones.get(0).getSggNm()).isEqualTo(admdst.get("sgg_nm"));

        Win noCrdn = new Win(crdnRange.bgng().minusDays(60), crdnRange.bgng().minusDays(1));
        List<ZoneRcmdtnResultVO.Zone> noCrdnZones =
                service.selectZones(cv.bgngYmd(), cv.endYmd(), noCrdn.bgngYmd(), noCrdn.endYmd(), "NARROW");
        assertThat(noCrdnZones).extracting(ZoneRcmdtnResultVO.Zone::getEmdCd)
                .containsExactlyElementsOf(zones.stream().map(ZoneRcmdtnResultVO.Zone::getEmdCd).toList());
    }

    // =====================================================================================
    // Step 9 — 증가율 · 월별 추이
    // =====================================================================================

    /**
     * 🔁 TSK-4282 — {@code scoreLots} 전(점수 null)이면 2차 키(배정 민원) 순이고, 증가율 단독으로는 순위를 정하지
     * 않는다. 1위의 증가율이 <b>음수</b>(−4.5%)이고 증가율이 가장 큰 행(+130.8%)이 3위여도 순서는 그대로다
     * (증가율은 검토지수에 곱하는 증가계수로만 순번에 들어간다 — F 케이스).
     * 🔴 그리고 추세를 채워도 {@code asgnCvlcptCnt}(2차 키)는 <b>건드리지 않는다</b>.
     */
    @Test
    @DisplayName("14. 🔴 합성 — 증가율 −4.5%(106/111)·+34.2%(106/79)·+130.8%(30/13) · scoreLots 전이면 2차 키(배정 민원) 순 — 증가율 단독으로는 순위를 정하지 않는다")
    void incrsRtDoesNotReorderRanking() {
        List<ZoneRcmdtnLotVO> in = lots(lot(1, 28, 218), lot(2, 30, 185), lot(3, 25, 60));
        List<ZoneRcmdtnTrendCountVO> rows = List.of(
                trend(1, "1999-01", 106, 0, 0, 0), trend(1, "1998-12", 0, 111, 0, 0),
                trend(2, "1999-01", 106, 0, 0, 0), trend(2, "1998-12", 0, 79, 0, 0),
                trend(3, "1999-01", 30, 0, 0, 0), trend(3, "1998-12", 0, 13, 0, 0));
        List<String> warnings = new ArrayList<>();

        calculator.applyTrend(in, rows, List.of("1999-01"), true, false, warnings);
        List<ZoneRcmdtnLotVO> out = calculator.assemble(zone("100", "300", "83"), in,
                crtrVls(), warnings).getLots();

        assertThat(warnings).as("창이 성립하고 1차를 썼으면 경고가 없다").isEmpty();
        assertThat(out).extracting(ZoneRcmdtnLotVO::getPkltId)
                .as("🔴 scoreLots 전(점수 null)이면 2차 키(배정 민원) 순이다 — 증가율 단독으로는 순위를 정하지 않는다(증가율 최대 3번이 1위가 아니다)")
                .containsExactly(1L, 2L, 3L);
        assertThat(out).extracting(ZoneRcmdtnLotVO::getRnk).containsExactly(1, 2, 3);
        assertThat(out).extracting(ZoneRcmdtnLotVO::getAsgnCvlcptCnt)
                .as("🔴 전 구간 집계 불변 — 추세 블록만 채웠다").containsExactly(218L, 185L, 60L);

        assertThat(out.get(0).getAsgnCvlcptCntRcnt()).isEqualTo(106L);
        assertThat(out.get(0).getAsgnCvlcptCntBfr()).isEqualTo(111L);
        assertThat(out.get(0).getCvlcptIncrsRt()).as("(106-111)/111 = -4.5%")
                .isCloseTo(new BigDecimal("-4.5"), within(EPS));
        assertThat(out.get(1).getCvlcptIncrsRt()).as("(106-79)/79 = +34.2%")
                .isCloseTo(new BigDecimal("34.2"), within(EPS));
        assertThat(out.get(2).getCvlcptIncrsRt()).as("(30-13)/13 = +130.8%")
                .isCloseTo(new BigDecimal("130.8"), within(EPS));
    }

    /**
     * 🔴 <b>작은 분모는 계산하지 않는다</b>(§9-1). {@code MIN_TREND_BASE}(10) 미달이면
     * {@code null} 이고 화면은 {@code —} 로 표시한다. 하한이 없으면 이전 3건 → 최근 17건이
     * 산식대로 <b>+466.7%</b> 라 그 수치로 1위를 주게 된다.
     *
     * <p>🔁 Step 16 — 증가율을 못 낸 행도 <b>목록에 남는다</b>(Step 14 의 제외는 철회됐다). 증가율만
     * 비고 순위·누적 면수·추이는 그대로 채워진다.
     */
    @Test
    @DisplayName("15. 🔴 합성 — 분모 하한: 10(경계)은 +90.0% · 9·5·0 은 null 인 채 목록에 남는다")
    void smallDenominatorYieldsNullIncrsRt() {
        List<ZoneRcmdtnLotVO> in = lots(lot(1, 30, 40), lot(2, 30, 30), lot(3, 30, 20), lot(4, 30, 0));
        List<ZoneRcmdtnTrendCountVO> rows = List.of(
                trend(1, "1999-01", 19, 0, 0, 0), trend(1, "1998-12", 0, 10, 0, 0),
                trend(2, "1999-01", 17, 0, 0, 0), trend(2, "1998-12", 0, 9, 0, 0),
                trend(3, "1999-01", 12, 0, 0, 0), trend(3, "1998-12", 0, 5, 0, 0));

        calculator.applyTrend(in, rows, List.of("1999-01"), true, false, new ArrayList<>());
        List<ZoneRcmdtnLotVO> out = calculator.assemble(zone("100", "300", "120"), in,
                crtrVls(), new ArrayList<>()).getLots();

        // 🔴 경계는 '이상' 이다 — 분모가 정확히 10 이면 계산한다(미만일 때만 null)
        assertThat(byId(out, 1).getAsgnCvlcptCntBfr()).as("분모 10 = MIN_TREND_BASE 경계").isEqualTo(10L);
        assertThat(byId(out, 1).getCvlcptIncrsRt()).as("(19-10)/10 = +90.0%")
                .isCloseTo(new BigDecimal("90.0"), within(EPS));
        assertThat(byId(out, 2).getCvlcptIncrsRt()).as("분모 9").isNull();
        assertThat(byId(out, 3).getCvlcptIncrsRt()).as("분모 5").isNull();
        // 🔴 추세 행이 아예 없는 주차장 — 블록 건수는 null 이 아니라 0 이다("창은 있었다" 가 사실이다)
        assertThat(byId(out, 4).getAsgnCvlcptCntRcnt()).isZero();
        assertThat(byId(out, 4).getAsgnCvlcptCntBfr()).isZero();
        assertThat(byId(out, 4).getCvlcptIncrsRt()).as("분모 0 — 0 으로 나누지 않는다").isNull();

        // 🔁 Step 16 — 증가율이 빈 행도 목록·순위에 그대로 있다
        assertThat(out).as("🔴 전 후보가 그대로다 — 제외는 철회됐다").hasSize(4);
        assertThat(out).extracting(ZoneRcmdtnLotVO::getRnk).containsExactly(1, 2, 3, 4);

        // 정적 창구의 경계값
        assertThat(ZoneRcmdtnCalculator.incrsRt(17L, 0L)).isNull();
        assertThat(ZoneRcmdtnCalculator.incrsRt(17L, 3L)).isNull();
        assertThat(ZoneRcmdtnCalculator.incrsRt(17L, 9L)).isNull();
        assertThat(ZoneRcmdtnCalculator.incrsRt(84L, 66L))
                .isCloseTo(new BigDecimal("27.3"), within(EPS));
        assertThat(ZoneRcmdtnCalculator.incrsRt(11L, 15L))
                .isCloseTo(new BigDecimal("-26.7"), within(EPS));
    }

    /**
     * 🔴 {@code trendMm} 은 <b>블록이 걸친 달 수만큼</b> 칸이 있고 합이 {@code asgnCvlcptCntRcnt} 와
     * 같다. 매퍼는 배정이 있는 달만 돌려주므로 빈 달을 0 으로 채워야 한다 — 안 채우면 주차장마다
     * 차트 x축 칸 수가 달라진다.
     */
    @Test
    @DisplayName("16. 🔴 합성 — trendMm 은 x축 4칸 고정 · 행이 없던 달은 0 · 합 = asgnCvlcptCntRcnt")
    void trendMmHasOneCellPerSpannedMonth() {
        List<String> yms = List.of("1999-05", "1999-06", "1999-07", "1999-08");
        List<ZoneRcmdtnLotVO> in = lots(lot(1, 28, 218), lot(2, 20, 12), lot(3, 20, 0));
        List<ZoneRcmdtnTrendCountVO> rows = List.of(
                trend(1, "1999-05", 29, 0, 0, 0), trend(1, "1999-06", 23, 0, 0, 0),
                trend(1, "1999-07", 33, 0, 0, 0), trend(1, "1999-08", 21, 0, 0, 0),
                // 2번은 1999-08 에 배정이 없다 — 매퍼가 그 달 행을 내지 않는다
                trend(2, "1999-05", 3, 0, 0, 0), trend(2, "1999-06", 1, 0, 0, 0),
                trend(2, "1999-07", 1, 0, 0, 0),
                // 이전 블록의 달은 x축 밖이다 — 분모에만 들어간다
                trend(1, "1999-01", 0, 111, 0, 0), trend(2, "1999-01", 0, 5, 0, 0));

        calculator.applyTrend(in, rows, yms, true, false, new ArrayList<>());

        assertThat(in).allSatisfy(l -> {
            assertThat(l.getTrendMm()).extracting(ZoneRcmdtnTrendVO::getYm)
                    .as("%s — x축은 창에서 만든 4칸 그대로다", l.getPkltNm()).containsExactlyElementsOf(yms);
            assertThat(l.getTrendMm().stream().mapToLong(ZoneRcmdtnTrendVO::getCnt).sum())
                    .as("%s — 월별 합 = 최근 블록 건수", l.getPkltNm()).isEqualTo(l.getAsgnCvlcptCntRcnt());
        });
        assertThat(byId(in, 1).getTrendMm()).extracting(ZoneRcmdtnTrendVO::getCnt)
                .containsExactly(29L, 23L, 33L, 21L);
        assertThat(byId(in, 1).getAsgnCvlcptCntRcnt()).isEqualTo(106L);
        // 🔴 행이 없던 달이 0 으로 채워진다 — 그리고 증가율이 비어도(분모 5) 추이는 채운다
        assertThat(byId(in, 2).getTrendMm()).extracting(ZoneRcmdtnTrendVO::getCnt)
                .containsExactly(3L, 1L, 1L, 0L);
        assertThat(byId(in, 2).getCvlcptIncrsRt()).as("🔴 추이는 채우고 증가율만 비운다").isNull();
        // 배정이 한 건도 없는 주차장도 4칸이다
        assertThat(byId(in, 3).getTrendMm()).extracting(ZoneRcmdtnTrendVO::getCnt)
                .containsExactly(0L, 0L, 0L, 0L);
    }

    /**
     * 실경로 — 고른 읍면동의 {@code trendMm} 을 <b>달별 칸까지</b> 독립 재계산과 맞춘다. x축은 실제로 쓴
     * 최근 블록이 걸친 달 전부이고(양 끝은 부분 달), 각 칸은 "그 달 ∩ 블록" 을 따로 센 값이다.
     * 민원 창이 자료 시작일에서 출발하므로 이 창은 폴백(반 분할)이다.
     */
    @Test
    @DisplayName("16-1. 🔴 실경로 — trendMm 의 달별 칸 = 그 달 ∩ 최근 블록의 독립 재계산 · 블록 건수·증가율도 재계산과 같다")
    void trendMmMatchesIndependentMonthlyCounts() {
        String e = targetEmd();
        assumeTrue(cv.trendOk(), "민원 보유 구간이 14일 미만이라 추세 블록을 만들 수 없다");

        Expect x = assertDetail(detail(e, cv, crdn, "NARROW"), e, cv, crdn, false, true);

        assertThat(x.fallback()).as("🔴 자료 시작일에서 출발하는 창의 1차 '이전' 은 자료 시작 전이라 0건이다").isTrue();
    }

    /**
     * 🔴 적용된 하한을 결과와 함께 굳힌다 — 바꾸면 같은 조회가 다른 증가율(또는 {@code null})을
     * 내므로, 어느 하한으로 낸 값인지 남지 않으면 재현할 수 없다({@code altmntRngVl} 과 같은 근거).
     */
    @Test
    @DisplayName("17. crtrVls.minTrendBase = 코드 상수 10 · trendDayCnt 는 기간을 알기 전엔 null, 조회 뒤엔 실제 블록 일수")
    void crtrVlsCarriesMinTrendBase() {
        // 설정이 아니라 코드 상수라 폴백 경로에서도 같은 값이다
        assertThat(calculator.resolveCrtrVls("NARROW", Map.of(), new ArrayList<>())
                .getMinTrendBase()).isEqualTo(MIN_TREND_BASE);
        assertThat(applied.getMinTrendBase()).isEqualTo(MIN_TREND_BASE);

        // 🔁 Step 13 — 블록 '일수' 도 함께 굳힌다. 🔴 resolveCrtrVls 시점엔 기간을 모르므로 null 이고,
        //    조회 기간에서 파생한 뒤 서비스가 채운다(그 값이 없으면 증가율을 재현할 수 없다).
        assertThat(calculator.resolveCrtrVls("NARROW", Map.of(), new ArrayList<>())
                .getTrendDayCnt()).as("🔴 기간을 모르는 시점에 0 을 박지 않는다").isNull();

        String e = targetEmd();
        assumeTrue(cv.trendOk(), "민원 보유 구간이 14일 미만이라 추세 블록을 만들 수 없다");
        ZoneRcmdtnResultVO r = detail(e, cv, crdn, null);
        assertThat(r.getCrtrVls().getMinTrendBase()).isEqualTo(MIN_TREND_BASE);
        // 자료 시작일에서 출발하는 창은 폴백이라 D 가 아니라 D/2 다
        assertThat(r.getCrtrVls().getTrendDayCnt()).isEqualTo(cv.half());
    }

    /**
     * 🔴 <b>블록이 7일({@code MIN_TREND_DAYS}) 미만이면 비교가 성립하지 않는다</b> — 증가율·월별
     * 추이를 내지 않고 {@code TREND_WINDOW_SHORT} 로 드러낸다. 0 을 내리면 "최근에 줄었다" 로
     * 잘못 읽힌다. 🔴 그때는 폴백도 말하지 않는다(어느 방식도 쓰지 않았다 — 사유가 하나여야 한다).
     */
    @Test
    @DisplayName("18. 🔴 합성 — 창이 짧으면 블록 건수·증가율 null · trendMm 빈 배열 · TREND_WINDOW_SHORT 1건(폴백 경고 없음)")
    void shortWindowClearsEveryTrendField() {
        List<ZoneRcmdtnLotVO> in = lots(lot(1, 28, 5), lot(2, 20, 0));
        List<String> warnings = new ArrayList<>();

        // 🔴 폴백 플래그가 켜져 있어도 창이 짧으면 TREND_BFR_FALLBACK 을 담지 않는다
        calculator.applyTrend(in, List.of(), List.of(), false, true, warnings);
        calculator.applyTrend(in, List.of(), List.of(), false, true, warnings);

        assertThat(warnings).as("🔴 두 번 불러도 1건이다").containsExactly(SHORT);
        assertThat(in).allSatisfy(l -> {
            assertThat(l.getAsgnCvlcptCnt()).as("전 구간 집계는 그대로 둔다").isNotNull();
            assertThat(l.getAsgnCvlcptCntRcnt()).as("🔴 0 이 아니라 null 이다").isNull();
            assertThat(l.getAsgnCvlcptCntBfr()).isNull();
            assertThat(l.getCvlcptIncrsRt()).isNull();
            assertThat(l.getTrendMm()).isEmpty();
        });
        // 후보 0개소인 동이어도 경고는 남는다 — 창이 짧다는 사실은 목록과 무관하다
        List<String> noLots = new ArrayList<>();
        calculator.applyTrend(new ArrayList<>(), List.of(), List.of(), false, false, noLots);
        assertThat(noLots).containsExactly(SHORT);
    }

    /**
     * 실경로의 경계 — 판정은 <b>일수</b>다. {@code D=13}(half 6)은 불가, {@code D=14}(half 7)는 성립한다.
     * 🔴 일주일 미만을 막는 이유는 <b>요일 효과</b>다 — 6일 블록은 어느 요일이 들어왔는지가 증가율을 만든다.
     *
     * <p>두 창 모두 <b>자료 시작일에서 출발</b>시킨다 — 14일 창의 1차 '이전' 은 자료 시작 전이라 반드시
     * 0건이므로 폴백(7+7)으로 내려앉는다(데이터 모양에 기대지 않는다).
     */
    @Test
    @DisplayName("18-1. 🔴 실경로 — D=13(half 6)은 TREND_WINDOW_SHORT · D=14 는 7+7 블록이 성립한다(자료 시작일 창 = 폴백)")
    void thirteenDaysIsShortFourteenIsNot() {
        String e = targetEmd();
        Win d13 = new Win(cvRange.bgng(), cvRange.bgng().plusDays(12));
        Win d14 = new Win(cvRange.bgng(), cvRange.bgng().plusDays(13));

        ZoneRcmdtnResultVO r = detail(e, d13, crdn, null);
        assertDetail(r, e, d13, crdn, false);
        assertThat(trendWarnings(r)).containsExactly(SHORT);
        assertThat(r.getLots()).as("🔴 창이 짧다고 목록을 비우지 않는다").isNotEmpty();
        assertThat(r.getCrtrVls().getTrendDayCnt()).isNull();

        // 🔴 하루만 늘려도 경계를 넘는다
        ZoneRcmdtnResultVO ok = detail(e, d14, crdn, null);
        Expect x = assertDetail(ok, e, d14, crdn, false, true);
        assertThat(x.fallback()).as("🔴 14일 창의 1차 '이전' 은 자료 시작 전이다 → 폴백").isTrue();
        assertThat(trendWarnings(ok)).containsExactly(FALLBACK);
        assertThat(ok.getCrtrVls().getTrendDayCnt()).as("🔴 폴백이므로 D 가 아니라 D/2 다").isEqualTo(7);
        assertThat(ok.getLots()).allSatisfy(l -> {
            assertThat(l.getAsgnCvlcptCntRcnt()).as("%s — 블록 건수가 null 이 아니다", l.getPkltNm())
                    .isNotNull();
            assertThat(l.getAsgnCvlcptCntBfr()).isNotNull();
        });
    }

    /**
     * 🔁 <b>Step 15 의 존재 이유 — 짧은 최근 창이 1차(직전 동일 기간)로 성립한다.</b>
     *
     * <p>자료 종료일에서 거꾸로 31일({@code D=31})을 잡으면 1차 '이전' 은 그 직전 31일이고 보유 구간
     * 안이다. 그 블록에 배정 민원이 있으면 <b>반 분할로 내려앉지 않는다</b>.
     * 🔴 그래서 {@code trendDayCnt} 가 <b>15 가 아니라 31</b> 이고, 화면 보조줄의 {@code 기간 31일} 과
     * 증가율이 보는 구간이 <b>일치</b>한다(Step 13 에서는 31일 ↔ 15일로 어긋났다).
     *
     * <p>🔴 <b>1차의 불변식 — 최근 블록 = 선택 기간 전체</b>({@code asgnCvlcptCntRcnt = asgnCvlcptCnt}).
     */
    @Test
    @DisplayName("19. 🔁 Step 15 — 자료 끝 31일 창이 1차로 성립: trendDayCnt 31 · 최근 블록 = 전 구간 · 이전 = 직전 31일의 독립 재계산")
    void primaryWindowUsesWholeSelectedPeriod() {
        String e = targetEmd();
        assumeTrue(cvRange.days() >= 62, "민원 보유 구간이 62일 미만이라 '31일 + 직전 31일' 을 만들 수 없다");
        Win recent = ZoneRcmdtnTestData.tail(cvRange, 31);

        ZoneRcmdtnResultVO r = detail(e, recent, crdn, null);
        Expect x = assertDetail(r, e, recent, crdn, false, true);

        assumeTrue(!x.fallback(), "고른 읍면동의 직전 31일(" + recent.bfr().bgng() + " ~ "
                + recent.bfr().end() + ")에 반경 안 민원이 없다 — 1차가 성립하는 창이 아니다");
        assertThat(trendWarnings(r)).as("🔴 1차가 성립하면 폴백 경고가 없다").isEmpty();
        assertThat(r.getCrtrVls().getTrendDayCnt()).as("🔴 D 그대로다 — 15 가 아니다").isEqualTo(31);
        // 🔴 1차 불변식 — 최근 블록은 선택 기간 전체다(전 구간과 같은 수다)
        assertThat(r.getLots()).allSatisfy(l -> assertThat(l.getAsgnCvlcptCntRcnt())
                .as("%s — 최근 블록 = 전 구간", l.getPkltNm()).isEqualTo(l.getAsgnCvlcptCnt()));
        // 🔴 이전 블록은 민원 창 밖(직전 31일)에서 센 것이다 — 합이 독립 재계산과 같다
        assertThat(r.getLots().stream().mapToLong(ZoneRcmdtnLotVO::getAsgnCvlcptCntBfr).sum())
                .isEqualTo(x.bfrTot()).isPositive();
    }

    /**
     * 🔴 <b>"가운데 1일 버림" 은 폴백에만 있다</b>(Step 13 ②) — {@code D} 가 홀수면 두 블록 사이에 하루가
     * 떠 있고, 그날의 배정 민원은 어느 블록에도 들지 않는다. 그래서
     * {@code 전 구간 − (최근 + 이전) = 그 하루의 배정 민원} 이다(1차에서는 최근 = 전 구간이라 성립하지 않는다).
     */
    @Test
    @DisplayName("19-1. 🔴 실경로 — 폴백·홀수 D: 전 구간 − (최근 + 이전) = 버린 가운데 하루의 배정 민원(독립 재계산)")
    void fallbackDropsTheMiddleDayWhenDaysAreOdd() {
        String e = targetEmd();
        // 자료 시작일에서 출발(폴백 보장) · 홀수 일수
        Win odd = cv.days() % 2 == 1 ? cv : new Win(cv.bgng(), cv.end().minusDays(1));
        assumeTrue(odd.trendOk(), "민원 보유 구간이 15일 미만이라 홀수 일수 창을 만들 수 없다");

        ZoneRcmdtnResultVO r = detail(e, odd, crdn, null);
        Expect x = assertDetail(r, e, odd, crdn, false);

        assertThat(x.fallback()).isTrue();
        Win middle = new Win(odd.bgng().plusDays(odd.half()), odd.bgng().plusDays(odd.half()));
        assertThat(middle.end()).as("가운데 하루는 두 블록 사이다")
                .isAfter(x.bfr().end()).isBefore(x.rcnt().bgng());
        ZoneRcmdtnCrtrVls c = r.getCrtrVls();
        assertThat(ZoneRcmdtnTestData.byLoc(r.getLots(),
                l -> l.getAsgnCvlcptCnt() - l.getAsgnCvlcptCntRcnt() - l.getAsgnCvlcptCntBfr()))
                .as("🔴 %s 하루의 배정 민원이 어느 블록에도 들지 않는다", middle.bgng())
                .isEqualTo(data.asgnByLoc(Src.CV, e, middle, c.getSwtcTrgtMinCpcty(), false,
                        c.getAltmntRngVl()));
    }

    /**
     * 🔁 <b>TSK-4282 D13 — 1차 '이전' 이 자료 시작 앞으로 "일부만" 걸치는 창은 이제 폴백이다.</b> 종전(Step 15)
     * 에는 합이 0 이 아니면 1차를 써서 분모가 실제 기간보다 짧은 구간을 반영했다(⚠️ 알려진 제약이었다) —
     * Step 0 실측에서 그 공백이 증가율을 +1956% 까지 부풀려 순위를 왜곡해 규칙을 바꿨다. 이전 블록에 민원이
     * 있어도 {@code TREND_BFR_FALLBACK} · {@code trendDayCnt = D/2} 이다.
     *
     * <p>창은 계산해서 만든다 — 자료 종료일에서 거꾸로 <b>보유 일수의 60%</b>. 직전 같은 길이의 시작이
     * 자료 시작일보다 앞이라 '이전' 블록의 앞 20% 구간에는 자료가 없다. 종전 이 테스트가 지던 "1차의 trendMm =
     * 최근 블록이 걸친 달 전부" 는 자료 안쪽 창을 쓰는 19(월별 칸 독립 재계산)·24-1(걸친 달 목록)이
     * {@code assertDetail} 로 단언한다. ⚠️ 그 창의 양 끝이 부분 달인지는 DB 자료 구간에 따라 달라진다
     * (예: 자료 끝이 월말이면 19 의 31일 창은 온전한 한 달이다).
     */
    @Test
    @DisplayName("20. 🔁 D13 — 1차 '이전' 이 자료 시작 앞으로 일부만 걸쳐도(민원 있음) 폴백이다 — TREND_BFR_FALLBACK · trendDayCnt = D/2")
    void bfrStraddlingDataStartFallsBack() {
        String e = targetEmd();
        int days = (int) Math.min(cvRange.days() * 6L / 10, 1000);
        assumeTrue(days >= 2 * ZoneRcmdtnTestData.MIN_TREND_DAYS, "민원 보유 구간이 너무 짧다");
        Win win = ZoneRcmdtnTestData.tail(cvRange, days);
        assertThat(win.bfr().bgng()).as("이 창의 직전 기간은 자료 시작일보다 앞에서 시작한다")
                .isBefore(cvRange.bgng());

        ZoneRcmdtnResultVO r = detail(e, win, crdn, null);
        Expect x = assertDetail(r, e, win, crdn, false);

        assertThat(x.fallback()).as("🔴 D13 — 자료 시작 앞이면 이전 블록 합과 무관하게 폴백").isTrue();
        assertThat(trendWarnings(r)).containsExactly(FALLBACK);
        assertThat(r.getCrtrVls().getTrendDayCnt()).as("🔴 D/2 다 — D 가 아니다").isEqualTo(win.half());
    }

    /**
     * 🔴 <b>폴백은 읍면동 단위로 한 번 결정한다</b> (Step 15 ②) — 행마다 방식이 다르면 같은 표의
     * {@code %} 가 서로 다른 구간을 가리켜 비교가 깨진다. 판정은 <b>1차 '이전' 블록의 전 행 합</b>이고,
     * 🔴 <b>"0건" 만</b> 폴백 사유다(적지만 있는 것은 행 단위 {@code MIN_TREND_BASE} 가 거른다).
     */
    @Test
    @DisplayName("24. 🔴 합성 — bfrFallback 은 1차 이전 합이 0 일 때만 · 폴백이면 반 분할 2칸을 읽고 TREND_BFR_FALLBACK 1건")
    void fallbackIsDecidedOncePerEmd() {
        assertThat(ZoneRcmdtnCalculator.bfrFallback(null)).isTrue();
        assertThat(ZoneRcmdtnCalculator.bfrFallback(List.of())).as("행이 없으면 1차로 낼 숫자가 없다").isTrue();
        assertThat(ZoneRcmdtnCalculator.bfrFallback(List.of(
                trend(1, "1999-01", 9, 0, 4, 5), trend(2, "1999-01", 3, 0, 2, 1)))).isTrue();
        assertThat(ZoneRcmdtnCalculator.bfrFallback(List.of(
                trend(1, "1999-01", 9, 0, 4, 5), trend(2, "1998-12", 0, 1, 0, 0))))
                .as("🔴 한 주차장에 1건만 있어도 읍면동 전체가 1차다").isFalse();

        // 같은 행을 1차로 읽을 때와 폴백으로 읽을 때 — 고르는 칸이 다르다
        List<ZoneRcmdtnTrendCountVO> rows = List.of(
                trend(1, "1999-01", 50, 0, 0, 30), trend(1, "1999-02", 70, 0, 60, 0),
                trend(1, "1998-12", 0, 40, 0, 0));

        List<ZoneRcmdtnLotVO> primary = lots(lot(1, 28, 120));
        List<String> primaryWarn = new ArrayList<>();
        calculator.applyTrend(primary, rows, List.of("1999-01", "1999-02"), true, false, primaryWarn);
        assertThat(primaryWarn).isEmpty();
        assertThat(primary.get(0).getAsgnCvlcptCntRcnt()).as("1차 최근 = 선택 기간 전체").isEqualTo(120L);
        assertThat(primary.get(0).getAsgnCvlcptCntBfr()).isEqualTo(40L);
        assertThat(primary.get(0).getCvlcptIncrsRt()).as("(120-40)/40")
                .isCloseTo(new BigDecimal("200.0"), within(EPS));

        List<ZoneRcmdtnLotVO> split = lots(lot(1, 28, 120));
        List<String> splitWarn = new ArrayList<>();
        calculator.applyTrend(split, rows, List.of("1999-02"), true, true, splitWarn);
        calculator.applyTrend(split, rows, List.of("1999-02"), true, true, splitWarn);
        assertThat(splitWarn).as("🔴 두 번 불러도 1건이다").containsExactly(FALLBACK);
        assertThat(split.get(0).getAsgnCvlcptCntRcnt()).as("폴백 최근 = 반 분할 뒤쪽").isEqualTo(60L);
        assertThat(split.get(0).getAsgnCvlcptCntBfr()).as("폴백 이전 = 반 분할 앞쪽").isEqualTo(30L);
        assertThat(split.get(0).getCvlcptIncrsRt()).as("(60-30)/30")
                .isCloseTo(new BigDecimal("100.0"), within(EPS));
        assertThat(split.get(0).getAsgnCvlcptCnt()).as("🔴 전 구간 집계는 어느 쪽에서도 그대로다").isEqualTo(120L);
    }

    /**
     * 실경로에서 두 갈래를 모두 태운다 — <b>자료 시작일에서 출발하는 창</b>은 1차 '이전' 이 자료 시작 전이라
     * 반드시 폴백이고({@code trendDayCnt = D/2} · 블록 건수 = 반 분할의 독립 재계산), <b>자료 종료일에서
     * 거꾸로 잡은 창</b>은 직전 기간이 보유 구간 안이라 그 기간에 민원이 있으면 1차다
     * ({@code trendDayCnt = D} · 최근 블록 = 전 구간).
     *
     * <p>🔴 구조적 한계도 여기서 드러난다 — 1차는 {@code D + D ≤ 보유 일수} 인 창에서만 성립할 수 있다.
     */
    @Test
    @DisplayName("24-1. 🔴 실경로 — 자료 시작일 창은 폴백(TREND_BFR_FALLBACK · D/2) · 자료 끝 창은 1차(D)")
    void fallbackAndPrimaryOnRealPath() {
        String e = targetEmd();
        assumeTrue(cv.trendOk(), "민원 보유 구간이 14일 미만이라 추세 블록을 만들 수 없다");

        // 자료 시작일에서 출발 → 폴백 보장
        ZoneRcmdtnResultVO fallback = detail(e, cv, crdn, null);
        Expect f = assertDetail(fallback, e, cv, crdn, false);
        assertThat(f.fallback()).isTrue();
        assertThat(trendWarnings(fallback)).containsExactly(FALLBACK);
        assertThat(fallback.getCrtrVls().getTrendDayCnt()).isEqualTo(cv.half());

        // 자료 종료일에서 거꾸로 보유 일수의 1/3 → 직전 같은 길이가 통째로 보유 구간 안
        int days = cvRange.days() / 3;
        assumeTrue(days >= 2 * ZoneRcmdtnTestData.MIN_TREND_DAYS, "민원 보유 구간이 42일 미만이다");
        Win win = ZoneRcmdtnTestData.tail(cvRange, days);
        ZoneRcmdtnResultVO primary = detail(e, win, crdn, null);
        Expect p = assertDetail(primary, e, win, crdn, false);
        assumeTrue(!p.fallback(), "고른 읍면동의 직전 기간(" + win.bfr().bgng() + " ~ " + win.bfr().end()
                + ")에 반경 안 민원이 없다 — 1차가 성립하는 창이 아니다");
        assertThat(trendWarnings(primary)).isEmpty();
        assertThat(primary.getCrtrVls().getTrendDayCnt()).as("🔴 D 그대로다 — D/2 가 아니다").isEqualTo(days);
        assertThat(primary.getLots()).allSatisfy(l -> assertThat(l.getAsgnCvlcptCntRcnt())
                .as("%s — 1차면 최근 블록 = 전 구간", l.getPkltNm())
                .isEqualTo(l.getAsgnCvlcptCnt()));
    }

    /**
     * 🆕 <b>TSK-4282 D13 — 자료 시작일 가드</b>. 1차 이전 블록 시작이 자료 시작일 앞이면 이전 블록에 민원이
     * 있어도 폴백이다(자료 공백이 분모에 섞여 증가율이 부푼다 — Step 0 실측). 같은 날·뒤면 종전 판정(합 0)
     * 그대로이고, 자료 시작일이 {@code null} 이면 가드가 꺼진다.
     */
    @Test
    @DisplayName("24-2. 🆕 D13 합성 — 이전 블록 시작 < 자료 시작이면 폴백 · 같거나 뒤면 1차 · 자료 시작 null 이면 종전")
    void bfrFallbackGuardsDataStart() {
        List<ZoneRcmdtnTrendCountVO> withBfr = List.of(trend(1, "1999-01", 9, 5, 4, 5));
        java.time.LocalDateTime dataBgng = java.time.LocalDateTime.of(1999, 1, 10, 0, 0);

        // (a) 앞 → 폴백(이전 블록 합이 0 이 아니어도)
        assertThat(ZoneRcmdtnCalculator.bfrFallback(withBfr, dataBgng.minusDays(1), dataBgng)).isTrue();
        // (b) 같은 날 · 뒤 → 1차
        assertThat(ZoneRcmdtnCalculator.bfrFallback(withBfr, dataBgng, dataBgng)).as("같은 날은 앞이 아니다").isFalse();
        assertThat(ZoneRcmdtnCalculator.bfrFallback(withBfr, dataBgng.plusDays(3), dataBgng)).isFalse();
        // 자료 시작 null(자료 없음) → 종전 판정(합 0 여부)만
        assertThat(ZoneRcmdtnCalculator.bfrFallback(withBfr, dataBgng.minusDays(1), null)).isFalse();
        assertThat(ZoneRcmdtnCalculator.bfrFallback(List.of(trend(1, "1999-01", 9, 0, 4, 5)),
                dataBgng.plusDays(3), dataBgng)).as("뒤여도 합 0 이면 종전대로 폴백(OR)").isTrue();

        // 자료 구간 VO → 그날 00:00 · null/형식 오류는 null(가드 끔)
        jeju.datahub.analysis.service.DataRangeVO range = new jeju.datahub.analysis.service.DataRangeVO();
        range.setDataBgngYmd("19990110");
        assertThat(ZoneRcmdtnCalculator.dataBgngDt(range)).isEqualTo(dataBgng);
        assertThat(ZoneRcmdtnCalculator.dataBgngDt(null)).isNull();
        assertThat(ZoneRcmdtnCalculator.dataBgngDt(new jeju.datahub.analysis.service.DataRangeVO())).isNull();
    }

    // =====================================================================================
    // Step 16 — 🔁 증가율을 못 낸 주차장이 다시 보인다 (Step 14-2 철회)
    // =====================================================================================

    /**
     * 🔁 <b>되돌아왔음을 단언한다</b> (Step 16). Step 14 는 {@code cvlcptIncrsRt == null} 인 행을
     * 순위 계산 전에 걷어냈다. 🔴 <b>사용자 결정(2026-10-13)으로 철회</b>했으므로 목록은 <b>전 후보</b>이고
     * 그 안에 증가율 {@code null} 인 행이 섞여 있어도 된다.
     *
     * <p>🔴 <b>이 단계의 부수 효과 — 누적 면수 최대값이 후보 면수 합과 다시 일치한다.</b>
     * Step 14 동안에는 리스트 누적이 요약의 {@code 전환 가능 면수} 보다 작았다.
     *
     * <p>후보가 많은 {@code WIDE} 로 본다 — 행 수 = 독립 SQL 의 {@code WIDE} 후보 개소 수(증가율 유무와
     * 무관) · {@code rnk} 1..n · 누적 면수 = 전 후보 누적합.
     */
    @Test
    @DisplayName("21. 🔁 Step 16 — WIDE 전 후보가 그대로(증가율 null 포함) · rnk 1..n · 누적 최대 = 후보 면수 합")
    void keepsEveryCandidateIncludingUndecidableOnes() {
        String e = targetEmd();
        ZoneRcmdtnResultVO r = detail(e, cv, crdn, "WIDE");
        List<ZoneRcmdtnLotVO> lots = r.getLots();

        assertDetail(r, e, cv, crdn, true);

        Cand wide = data.cand(e, r.getCrtrVls().getSwtcTrgtMinCpcty(), true);
        assertThat(lots).as("🔴 증가율이 비는 행을 걷어내지 않는다 — 전 후보다").hasSize((int) wide.cnt());
        long nullIncrs = lots.stream().filter(l -> l.getCvlcptIncrsRt() == null).count();
        assertThat(nullIncrs).as("증가율 null 행 수 = 분모가 하한 미만인 행 수(그 행들이 목록에 있다)")
                .isEqualTo(lots.stream().filter(l -> l.getAsgnCvlcptCntBfr() == null
                        || l.getAsgnCvlcptCntBfr() < r.getCrtrVls().getMinTrendBase()).count());
        if (!lots.isEmpty()) {
            assertThat(lots.get(lots.size() - 1).getCumCpcty()).usingComparator(BigDecimal::compareTo)
                    .isEqualTo(BigDecimal.valueOf(wide.cpcty()));
        }
    }

    /**
     * 🔴 <b>배정률({@code asgn})은 전체 후보 기준이다</b> — "민원의 몇 %가 후보 반경 안인가" 이고
     * 표시 행 수와 무관하다. 유형 범위를 넓히면 분자(배정 합)만 늘고 <b>분모(민원 총건수)는 그대로</b>다.
     */
    @Test
    @DisplayName("22. 🔴 불변 — NARROW ↔ WIDE: 민원 총건수는 같고 배정 합·후보 수만 (줄지 않고) 달라진다")
    void assignmentCoverageDependsOnlyOnCandidateSet() {
        String e = targetEmd();
        ZoneRcmdtnResultVO narrow = detail(e, cv, crdn, "NARROW");
        ZoneRcmdtnResultVO wide = detail(e, cv, crdn, "WIDE");
        assertDetail(narrow, e, cv, crdn, false);
        assertDetail(wide, e, cv, crdn, true);

        assertThat(wide.getAsgn().getCvlcptTot()).as("분모는 유형 범위와 무관하다")
                .isEqualTo(narrow.getAsgn().getCvlcptTot());
        assertThat(wide.getAsgn().getCvlcptAsgn()).as("후보가 늘면 배정 합은 줄지 않는다")
                .isGreaterThanOrEqualTo(narrow.getAsgn().getCvlcptAsgn());
        assertThat(wide.getLots()).hasSizeGreaterThanOrEqualTo(narrow.getLots().size());
    }

    /**
     * 🔴 <b>전 행이 {@code null} 인 동도 목록이 그대로다.</b> Step 14 는 이 상황을 <b>폴백</b>(전부 비면
     * 제외하지 않는다)으로 다뤄야 했는데, 🔁 Step 16 에서는 <b>특례가 아니라 평시 동작</b>이다.
     */
    @Test
    @DisplayName("23. 🔁 Step 16 합성 — 전 행 증가율 null(분모 전부 하한 미만)이어도 목록·순위·추이는 그대로다")
    void keepsEveryLotWhenNoneIsDecidable() {
        List<ZoneRcmdtnLotVO> in = lots(lot(1, 30, 2), lot(2, 25, 0), lot(3, 20, 0));
        List<ZoneRcmdtnTrendCountVO> rows = List.of(
                trend(1, "1999-01", 1, 0, 0, 0), trend(1, "1998-12", 0, 1, 0, 0));
        List<String> warnings = new ArrayList<>();

        calculator.applyTrend(in, rows, List.of("1999-01"), true, false, warnings);
        List<ZoneRcmdtnLotVO> out = calculator.assemble(zone("0", "0.25", "75"), in,
                crtrVls(), warnings).getLots();

        assertThat(warnings).as("창은 충분하다 — 짧아서가 아니라 조용해서다").isEmpty();
        assertThat(out).as("🔴 비우지 않는다").hasSize(3);
        assertThat(out).allSatisfy(l -> {
            assertThat(l.getCvlcptIncrsRt()).as("%s", l.getPkltNm()).isNull();
            assertThat(l.getTrendMm()).hasSize(1);
        });
        // 순위·누적은 그대로 채운다(목록이 있는 것과 같다)
        assertThat(out).extracting(ZoneRcmdtnLotVO::getRnk).containsExactly(1, 2, 3);
    }

    /**
     * 실경로에서 같은 상황을 <b>창으로 만든다</b> — 민원 창을 자료 시작일 앞뒤로 같은 길이만큼 걸치면
     * (앞 20일은 자료 시작 전) 1차 '이전' 도, 폴백 '이전'(앞 절반)도 자료 시작 전이라 <b>분모가 전 행
     * 0</b> 이다. 그래서 어느 읍면동이든 증가율이 전 행 {@code null} 인데, 목록은 후보 개소 그대로다.
     */
    @Test
    @DisplayName("23-1. 🔁 실경로 — 분모 블록이 통째로 자료 시작 전인 창: 전 행 증가율 null 인데 후보는 전부 나온다")
    void keepsEveryLotWhenDenominatorBlockIsBeforeData() {
        String e = targetEmd();
        Win straddle = new Win(cvRange.bgng().minusDays(20), cvRange.bgng().plusDays(19));

        ZoneRcmdtnResultVO r = detail(e, straddle, crdn, null);
        Expect x = assertDetail(r, e, straddle, crdn, false);

        assertThat(x.fallback()).isTrue();
        assertThat(x.bfrTot()).as("폴백 이전 블록(%s ~ %s)은 자료 시작 전이다", x.bfr().bgng(), x.bfr().end())
                .isZero();
        assertThat(trendWarnings(r)).as("창은 충분하다(20일 블록) — 짧아서가 아니라 분모가 없어서다")
                .containsExactly(FALLBACK);
        assertThat(r.getLots()).as("🔴 비우지 않는다").isNotEmpty()
                .allSatisfy(l -> {
                    assertThat(l.getAsgnCvlcptCntBfr()).isZero();
                    assertThat(l.getCvlcptIncrsRt()).as("%s", l.getPkltNm()).isNull();
                });
    }

    // =====================================================================================
    // 🔁 TSK-4259 — 시군구 모드 조립
    // =====================================================================================

    /**
     * 🔁 TSK-4259 (D4) — 시군구 모드는 누적·배정률을 내지 않는다. {@code zone}·{@code asgn} 은 null,
     * {@code rnk} 만 1..N 이고 {@code cumCpcty} 는 null 이다. 🔁 TSK-4282 — 순서는 계산기가
     * §0-2 순번 규칙으로 다시 정하며, 점수가 null({@code scoreLots} 전)이면 2차 키(배정 민원) 순이라 이 입력에서는 입력 순서와 같다.
     * 🔴 읍면동 단위 지표를 시군구로 합산해 숫자를 지어내지 않는다 — 여기서 값이 채워지면 그 숫자가 화면·보고서에 실린다.
     */
    @Test
    @DisplayName("TSK-4259 🔴 합성 — assembleSgg: zone·asgn null · rnk 1..N · cumCpcty null · 점수 null 이면 2차 키(배정 민원) 순")
    void assembleSggRanksWithoutJudgement() {
        ZoneRcmdtnLotVO a = lot(7, 50, 9);
        ZoneRcmdtnLotVO b = lot(3, 40, 7);
        ZoneRcmdtnLotVO c = lot(5, 30, 0);
        // 🔴 이미 채워진 칸이 있어도 지운다 — 다른 경로에서 재사용된 VO 가 들어와도 누적이 새지 않는다
        b.setCumCpcty(new BigDecimal("90"));
        List<String> warnings = new ArrayList<>(List.of("COEF_FALLBACK"));

        ZoneRcmdtnResultVO r = calculator.assembleSgg(lots(a, b, c), crtrVls(), warnings);

        assertThat(r.getZone()).as("🔴 시군구 모드는 zone 이 없다(D4)").isNull();
        assertThat(r.getAsgn()).as("🔴 배정률은 읍면동 단위 지표다(D4)").isNull();
        assertThat(r.getCrtrVls()).as("적용 기준값은 그대로 싣는다").isNotNull();
        assertThat(r.getWarnings()).as("경고 목록을 버리지 않는다").containsExactly("COEF_FALLBACK");
        assertThat(r.getLots()).extracting(ZoneRcmdtnLotVO::getPkltId)
                .as("🔁 TSK-4282 — 계산기가 다시 정렬하지만 점수가 null 이면 2차 키(배정 민원 9→7→0) 순이다").containsExactly(7L, 3L, 5L);
        assertThat(r.getLots()).extracting(ZoneRcmdtnLotVO::getRnk).doesNotContainNull().containsExactly(1, 2, 3);
        assertThat(r.getLots()).extracting(ZoneRcmdtnLotVO::getCumCpcty).as("🔴 시군구 모드는 누적이 없다(D4)")
                .containsOnlyNulls();
        assertThat(r.getScope()).as("scope·sggNm 은 서비스가 채운다 — 계산기는 비워 둔다").isNull();
        assertThat(r.getSggNm()).isNull();

        // 빈 입력 · null 입력도 빈 목록으로 끝난다(NPE 가 아니다)
        assertThat(calculator.assembleSgg(new ArrayList<>(), crtrVls(), null).getLots()).isEmpty();
        ZoneRcmdtnResultVO none = calculator.assembleSgg(null, crtrVls(), null);
        assertThat(none.getLots()).isEmpty();
        assertThat(none.getWarnings()).isEmpty();
    }

    // =====================================================================================
    // 🆕 TSK-4282 — 요금제 검토지수 · 증가계수 · 순번 · 우선도 단계 D14 (F1~F9 — 기존 번호 1~24-1 과 겹치지 않게)
    // =====================================================================================

    /** 합성 조회 개월 수(6개월) — {@code PrdMonths} 를 거치지 않고 직접 넣는다 */
    private static final BigDecimal MONTHS_6 = new BigDecimal("6");

    /** 합성 요금제 주차장 — 시군구·증가율(%)까지 채운 한 행. 점수는 비어 있다 */
    private static ZoneRcmdtnLotVO feeLot(long pkltId, int cpcty, long asgn, String sggNm, String incrsRt) {
        ZoneRcmdtnLotVO l = lot(pkltId, cpcty, asgn);
        l.setSggNm(sggNm);
        l.setCvlcptIncrsRt(bd(incrsRt));
        return l;
    }

    /** F1 기준값 — w 70/30 · wi 0.5 · U 100 · 제주 A=22 B=70 · 서귀포 A=10 B=27 (계획서 F1 (a)(b) 그대로) */
    private static ZoneRcmdtnCrtrVls f1Crtr() {
        return feeCrtr("70", "30", "22", "10", "70", "27", "0.5", "100");
    }

    /**
     * F1 (a) — 서귀포 기준(A=10 · B=27)이면 면당압력이 큰 B 가 1위다. 원값은 계획서 §Step 2 F1 숫자 그대로이고,
     * 반올림(소수 1자리)은 번호를 매긴 뒤 끝에서만 한다.
     */
    @Test
    @DisplayName("F1. (a) 🔴 합성 — 서귀포 기준 A=10·B=27: A 221.0 · B 226.4 → B 1위 · 단계 둘 다 TOP(우선도 100↑ · D14)")
    void feeRankFlipsWithSeogwipoCriteria() {
        ZoneRcmdtnLotVO a = feeLot(1, 200, 180, "서귀포시", "-5.0");
        ZoneRcmdtnLotVO b = feeLot(2, 30, 108, "서귀포시", "35.0");
        List<String> warnings = new ArrayList<>();

        calculator.scoreLots(lots(a, b), MONTHS_6, f1Crtr(), warnings);

        // 원값(반올림 전) — 계획서 숫자 그대로
        assertThat(a.getCvlcptCntScr()).isCloseTo(bd("300"), within(bd("0.01")));
        assertThat(a.getCvlcptRtScr()).isCloseTo(bd("55.56"), within(bd("0.01")));
        assertThat(a.getRvwScr()).isCloseTo(bd("226.67"), within(bd("0.01")));
        assertThat(a.getIncrsCfc()).isEqualByComparingTo("0.975");
        assertThat(b.getCvlcptCntScr()).isCloseTo(bd("180"), within(bd("0.01")));
        assertThat(b.getCvlcptRtScr()).isCloseTo(bd("222.22"), within(bd("0.01")));
        assertThat(b.getRvwScr()).isCloseTo(bd("192.67"), within(bd("0.01")));
        assertThat(b.getIncrsCfc()).isEqualByComparingTo("1.175");
        assertThat(warnings).as("시군구를 알고 설정이 다 있으면 경고가 없다").isEmpty();

        List<ZoneRcmdtnLotVO> out = calculator.assembleSgg(lots(a, b), f1Crtr(), warnings).getLots();

        assertThat(out).extracting(ZoneRcmdtnLotVO::getPkltId).as("🔴 원점수 + 추세로 B 가 1위").containsExactly(2L, 1L);
        assertThat(out).extracting(ZoneRcmdtnLotVO::getRnk).containsExactly(1, 2);
        assertThat(b.getIncrsRfltRvwScr()).isEqualByComparingTo("226.4");
        assertThat(a.getIncrsRfltRvwScr()).isEqualByComparingTo("221.0");
        assertThat(out).extracting(ZoneRcmdtnLotVO::getRvwGrdCd).as("🔴 우선도 221.0 · 226.4 — 둘 다 100↑(D14)")
                .containsOnly("TOP");
        assertThat(a.getRvwScr()).as("표시값은 소수 1자리").isEqualByComparingTo("226.7");
        assertThat(a.getIncrsCfc()).as("증가계수 표시는 3자리").isEqualByComparingTo("0.975");
    }

    /**
     * F1 (b) — 같은 두 행을 제주 기준(A=22 · B=70)으로 보면 A 가 1위다. 🔁 D14 — 단계도 우선도 원값
     * (A 99.33 · B 97.51)로 매겨 둘 다 HIGH 다(종전 D1·D2 의 "1위 경고 · 2위 심각" 역전은 폐기).
     */
    @Test
    @DisplayName("F1. (b) 🔴 합성 — 제주 기준 A=22·B=70: A 99.3 · B 97.5 → A 1위 · 단계 둘 다 HIGH(100 미만 · D14)")
    void feeRankFlipsWithJejuCriteria() {
        ZoneRcmdtnLotVO a = feeLot(1, 200, 180, "제주시", "-5.0");
        ZoneRcmdtnLotVO b = feeLot(2, 30, 108, "제주시", "35.0");
        List<String> warnings = new ArrayList<>();

        calculator.scoreLots(lots(a, b), MONTHS_6, f1Crtr(), warnings);

        assertThat(a.getCvlcptCntScr()).isCloseTo(bd("136.36"), within(bd("0.01")));
        assertThat(a.getCvlcptRtScr()).isCloseTo(bd("21.43"), within(bd("0.01")));
        assertThat(a.getRvwScr()).isCloseTo(bd("101.88"), within(bd("0.01")));
        assertThat(b.getCvlcptCntScr()).isCloseTo(bd("81.82"), within(bd("0.01")));
        assertThat(b.getCvlcptRtScr()).isCloseTo(bd("85.71"), within(bd("0.01")));
        assertThat(b.getRvwScr()).isCloseTo(bd("82.99"), within(bd("0.01")));

        List<ZoneRcmdtnLotVO> out = calculator.assembleSgg(lots(b, a), f1Crtr(), warnings).getLots();

        assertThat(out).extracting(ZoneRcmdtnLotVO::getPkltId).as("🔴 제주 기준이면 A 가 1위").containsExactly(1L, 2L);
        assertThat(a.getIncrsRfltRvwScr()).isEqualByComparingTo("99.3");
        assertThat(b.getIncrsRfltRvwScr()).isEqualByComparingTo("97.5");
        assertThat(a.getRvwGrdCd()).as("🔴 우선도 원값 99.33 — 100 미만이라 TOP 이 아니다(반올림 99.3 과 같은 판정)")
                .isEqualTo("HIGH");
        assertThat(b.getRvwGrdCd()).as("우선도 원값 97.51").isEqualTo("HIGH");
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("F2. 🔴 합성 — 원점수가 100 을 넘는 두 행: 단계는 둘 다 TOP · 순서는 원점수로 갈린다")
    void rawScoreAboveHundredStillOrders() {
        ZoneRcmdtnLotVO small = feeLot(1, 20, 300, "제주시", null);   // 월 50건 — s1 227 · s2 347
        ZoneRcmdtnLotVO big = feeLot(2, 20, 600, "제주시", null);     // 월 100건 — s1 455 · s2 694
        List<ZoneRcmdtnLotVO> in = lots(small, big);

        calculator.scoreLots(in, MONTHS_6, crtrVls(), new ArrayList<>());
        List<ZoneRcmdtnLotVO> out = calculator.assembleSgg(in, crtrVls(), null).getLots();

        assertThat(out).extracting(ZoneRcmdtnLotVO::getPkltId).as("🔴 100 에서 자르면 동점 — 원점수로 가른다(D1)")
                .containsExactly(2L, 1L);
        assertThat(out).allSatisfy(l -> {
            assertThat(l.getRvwScr()).isGreaterThan(bd("100"));
            assertThat(l.getRvwGrdCd()).isEqualTo("TOP");
        });
    }

    @Test
    @DisplayName("F3. 합성 — 증가율 null → 증가계수 1 · 추세 반영 지수 = 검토지수")
    void nullIncrsRtIsNeutral() {
        assertThat(ZoneRcmdtnCalculator.incrsCfc(null, bd("0.5"), bd("100"))).isEqualByComparingTo("1");

        ZoneRcmdtnLotVO l = feeLot(1, 30, 120, "제주시", null);
        calculator.scoreLots(lots(l), MONTHS_6, crtrVls(), new ArrayList<>());

        assertThat(l.getIncrsCfc()).isEqualByComparingTo("1");
        assertThat(l.getIncrsRfltRvwScr()).isEqualByComparingTo(l.getRvwScr());
    }

    @Test
    @DisplayName("F4. 합성 — 증가율 +250% · U 100 → 계수 1.5 에서 잘림 · −250% → 0.5")
    void incrsCfcClampsAtUpperLimit() {
        // 정본 static 은 비율을 받는다(+250% = 2.5) — 공유주차제가 정밀 비율을 넘기는 창구와 같다
        assertThat(ZoneRcmdtnCalculator.incrsCfc(bd("2.5"), bd("0.5"), bd("100"))).isEqualByComparingTo("1.5");
        assertThat(ZoneRcmdtnCalculator.incrsCfc(bd("-2.5"), bd("0.5"), bd("100"))).isEqualByComparingTo("0.5");
        assertThat(ZoneRcmdtnCalculator.incrsCfc(bd("0.35"), bd("0.5"), bd("100"))).as("상한 안은 그대로")
                .isEqualByComparingTo("1.175");

        // 요금제 경로 — cvlcptIncrsRt 는 % 라 scoreLots 가 비율로 바꿔 넘긴다
        ZoneRcmdtnLotVO up = feeLot(1, 30, 120, "제주시", "250.0");
        ZoneRcmdtnLotVO down = feeLot(2, 30, 120, "제주시", "-250.0");
        calculator.scoreLots(lots(up, down), MONTHS_6, crtrVls(), new ArrayList<>());
        assertThat(up.getIncrsCfc()).isEqualByComparingTo("1.5");
        assertThat(down.getIncrsCfc()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("F5. 합성 — 동점이면 배정 민원 ↓ → 면수 ↓ → pklt_id ↑ · 점수 null 은 맨 뒤")
    void tieBreaksByAsgnThenCpctyThenId() {
        ZoneRcmdtnLotVO top = scored(15, 30, 10, "90");
        ZoneRcmdtnLotVO moreAsgn = scored(13, 30, 60, "80");
        ZoneRcmdtnLotVO moreCpcty = scored(12, 40, 50, "80");
        ZoneRcmdtnLotVO lowId = scored(10, 30, 50, "80");
        ZoneRcmdtnLotVO highId = scored(11, 30, 50, "80");
        ZoneRcmdtnLotVO none = scored(14, 50, 999, null);

        List<ZoneRcmdtnLotVO> out = calculator.assembleSgg(
                lots(none, highId, lowId, moreCpcty, moreAsgn, top), crtrVls(), null).getLots();

        assertThat(out).extracting(ZoneRcmdtnLotVO::getPkltId)
                .as("추세 반영 지수 ↓ → 배정 민원 ↓ → 면수 ↓ → pklt_id ↑ · null 은 배정 민원이 커도 맨 뒤")
                .containsExactly(15L, 13L, 12L, 10L, 11L, 14L);
        assertThat(out).extracting(ZoneRcmdtnLotVO::getRnk).containsExactly(1, 2, 3, 4, 5, 6);
    }

    /** F5 용 — 추세 반영 지수만 직접 넣은 행 */
    private static ZoneRcmdtnLotVO scored(long pkltId, int cpcty, long asgn, String rflt) {
        ZoneRcmdtnLotVO l = lot(pkltId, cpcty, asgn);
        l.setIncrsRfltRvwScr(bd(rflt));
        return l;
    }

    @Test
    @DisplayName("F6. 합성 — 단계 경계(D14): 100 → TOP · 99.99 → HIGH · 60 → HIGH · 59.99 → LOW (이상 판정 · 3단계 — 30 경계 없음)")
    void gradeBoundaryIsInclusive() {
        // w 100/0 · 제주 A=100 · 100개월 · 증가율 null(계수 1) — 우선도 = 배정민원 ÷ 100
        ZoneRcmdtnCrtrVls c = feeCrtr("100", "0", "100", "10", "72", "28", "0.5", "100");
        ZoneRcmdtnLotVO at100 = feeLot(1, 20, 10000, "제주시", null);
        ZoneRcmdtnLotVO below100 = feeLot(2, 20, 9999, "제주시", null);
        ZoneRcmdtnLotVO at60 = feeLot(3, 20, 6000, "제주시", null);
        ZoneRcmdtnLotVO below60 = feeLot(4, 20, 5999, "제주시", null);
        ZoneRcmdtnLotVO at30 = feeLot(5, 20, 3000, "제주시", null);
        ZoneRcmdtnLotVO below30 = feeLot(6, 20, 2999, "제주시", null);

        calculator.scoreLots(lots(at100, below100, at60, below60, at30, below30), bd("100"), c, new ArrayList<>());

        assertThat(at100.getIncrsRfltRvwScr()).isEqualByComparingTo("100");
        assertThat(at100.getRvwGrdCd()).isEqualTo("TOP");
        assertThat(below100.getIncrsRfltRvwScr()).isEqualByComparingTo("99.99");
        assertThat(below100.getRvwGrdCd()).isEqualTo("HIGH");
        assertThat(at60.getRvwGrdCd()).isEqualTo("HIGH");
        assertThat(below60.getRvwGrdCd()).isEqualTo("LOW");
        assertThat(at30.getRvwGrdCd()).isEqualTo("LOW");
        assertThat(below30.getRvwGrdCd()).as("30 경계 삭제 — 29.99 도 후순위").isEqualTo("LOW");

        // 🔴 반올림(99.99 → 100.0)이 단계를 바꾸지 않는다 — 단계는 원값으로 매겼다
        calculator.assembleSgg(lots(below100), c, null);
        assertThat(below100.getIncrsRfltRvwScr()).isEqualByComparingTo("100.0");
        assertThat(below100.getRvwGrdCd()).isEqualTo("HIGH");

        // 점수가 없으면(scoreLots 전) 단계도 없다
        ZoneRcmdtnLotVO unscored = lot(7, 20, 10);
        calculator.assembleSgg(lots(unscored), c, null);
        assertThat(unscored.getRvwGrdCd()).isNull();
    }

    @Test
    @DisplayName("F7. 합성 — 서귀포 행은 서귀포 기준값 · 시군구 null 은 제주시 기준 + COEF_FALLBACK 1회")
    void seogwipoRowUsesSeogwipoCriteria() {
        // w 100/0 · 추세 미반영 — 민원강도 점수만 본다. 월 10건 · 제주 A=22 · 서귀포 A=10
        ZoneRcmdtnCrtrVls c = feeCrtr("100", "0", "22", "10", "72", "28", "0", "100");
        ZoneRcmdtnLotVO sgp = feeLot(1, 20, 60, "서귀포시", null);
        ZoneRcmdtnLotVO jeju = feeLot(2, 20, 60, "제주시", null);
        List<String> ok = new ArrayList<>();

        calculator.scoreLots(lots(sgp, jeju), MONTHS_6, c, ok);

        assertThat(sgp.getCvlcptCntScr()).as("10 ÷ 10 × 100").isEqualByComparingTo("100");
        assertThat(jeju.getCvlcptCntScr()).as("10 ÷ 22 × 100").isCloseTo(bd("45.45"), within(bd("0.01")));
        assertThat(ok).as("시군구를 알면 경고가 없다").isEmpty();

        ZoneRcmdtnLotVO unknown1 = feeLot(3, 20, 60, null, null);
        ZoneRcmdtnLotVO unknown2 = feeLot(4, 20, 60, "합성시", null);
        List<String> warnings = new ArrayList<>();
        calculator.scoreLots(lots(unknown1, unknown2), MONTHS_6, c, warnings);

        assertThat(unknown1.getCvlcptCntScr()).as("🔴 null 은 제주시 기준").isEqualByComparingTo(jeju.getCvlcptCntScr());
        assertThat(unknown2.getCvlcptCntScr()).as("예상 밖 이름도 제주시 기준").isEqualByComparingTo(jeju.getCvlcptCntScr());
        assertThat(warnings).as("🔴 경고는 한 번만").containsExactly("COEF_FALLBACK");
    }

    @Test
    @DisplayName("F8. 🔴 합성 — 읍면동 모드 cumCpcty 는 새 순번 순서로 누적된다(D10)")
    void cumCpctyFollowsNewOrder() {
        // F1 (a) 의 두 행 — 배정 민원 순이면 A(200면) → B(30면)지만 검토지수 순은 B → A
        ZoneRcmdtnLotVO a = feeLot(1, 200, 180, "서귀포시", "-5.0");
        ZoneRcmdtnLotVO b = feeLot(2, 30, 108, "서귀포시", "35.0");
        List<ZoneRcmdtnLotVO> in = lots(a, b);
        List<String> warnings = new ArrayList<>();

        calculator.scoreLots(in, MONTHS_6, f1Crtr(), warnings);
        List<ZoneRcmdtnLotVO> out = calculator.assemble(zone("100", "300", "83"), in, f1Crtr(), warnings).getLots();

        assertThat(out).extracting(ZoneRcmdtnLotVO::getPkltId).containsExactly(2L, 1L);
        assertThat(out).extracting(ZoneRcmdtnLotVO::getRnk).containsExactly(1, 2);
        assertThat(out).extracting(l -> l.getCumCpcty().intValue()).as("🔴 30 → 230 (배정 민원 순이면 200 → 230)")
                .containsExactly(30, 230);
    }

    @Test
    @DisplayName("F9. 합성 — 한 행 꺼짐(w 70/0): 검토지수 = 민원강도 점수 · 둘 다 0 이면 폴백 70/30")
    void zeroWeightRenormalizes() {
        ZoneRcmdtnCrtrVls c = feeCrtr("70", "0", "22", "10", "72", "28", "0.5", "100");
        ZoneRcmdtnLotVO hi = feeLot(1, 20, 180, "제주시", null);   // 월 30 — s1 136.4 (s2 208.3 는 꺼짐)
        ZoneRcmdtnLotVO lo = feeLot(2, 20, 60, "제주시", null);    // 월 10 — s1 45.5 (s2 69.4 는 꺼짐)
        List<String> warnings = new ArrayList<>();

        calculator.scoreLots(lots(hi, lo), MONTHS_6, c, warnings);

        assertThat(hi.getRvwScr()).as("켜진 지표 100%").isEqualByComparingTo(hi.getCvlcptCntScr());
        assertThat(hi.getRvwGrdCd()).as("우선도 136.4 — 100↑").isEqualTo("TOP");
        assertThat(lo.getRvwScr()).isEqualByComparingTo(lo.getCvlcptCntScr());
        assertThat(lo.getRvwGrdCd()).as("우선도 45.5 — 60 미만").isEqualTo("LOW");
        assertThat(warnings).as("0 은 적법한 설정값이다 — 경고 없음").isEmpty();

        // 🔴 0/0 방어 — 둘 다 0 이면 폴백 70/30 + COEF_FALLBACK
        ZoneRcmdtnLotVO l = feeLot(3, 20, 60, "제주시", null);
        List<String> w0 = new ArrayList<>();
        calculator.scoreLots(lots(l), MONTHS_6, feeCrtr("0", "0", "22", "10", "72", "28", "0.5", "100"), w0);
        BigDecimal expect = l.getCvlcptCntScr().multiply(bd("70")).add(l.getCvlcptRtScr().multiply(bd("30")))
                .divide(bd("100"));
        assertThat(l.getRvwScr()).isCloseTo(expect, within(bd("0.0001")));
        assertThat(w0).containsExactly("COEF_FALLBACK");
    }
}
