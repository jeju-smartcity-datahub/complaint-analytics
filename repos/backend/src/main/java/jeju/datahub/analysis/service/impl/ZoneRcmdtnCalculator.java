package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.analysis.service.ZoneRcmdtnBaselineVO;
import jeju.datahub.analysis.service.ZoneRcmdtnCrtrVls;
import jeju.datahub.analysis.service.ZoneRcmdtnLotVO;
import jeju.datahub.analysis.service.ZoneRcmdtnResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnTrendVO;
import lombok.RequiredArgsConstructor;

/**
 * 구역추천(요금제 적용) <b>순위 조립기</b> (TSK-4207 Step 4).
 *
 * <p>매퍼가 낸 "원천이 말하는 사실"({@link ZoneRcmdtnBaselineVO} · {@link ZoneRcmdtnLotVO})을 받아
 * 주차장 목록에 <b>검토지수·순위·누적 면수</b>를 채우고 배정 커버리지를 낸다.
 *
 * <h2>🔁 TSK-4340(2026-11-11 사용자 결정) — 읍면동 요금제 대상 판정을 제거했다</h2>
 * 종전 이 클래스는 E0(적발배수 × 단속 월평균 ÷ 30) → 필요 면수 → 충족률 → 추천유형({@code FEE}/{@code SHARE})·
 * 판정 불가 사유와 {@code lots[].reachNeed}(필요 면수 도달)를 계산했다. <b>서버에서도 완전히 지웠다</b> —
 * 판정 계수 {@code STSF_RT_THRSH}·{@code ILGL_CRTR_VL}·{@code CVLCPT_MM_CRTR_VL}(RECOMMEND)과
 * {@code PCSHR_CRTR_VL}·{@code PAID_DMND_RT}(POLICY_SIM)는 더 이상 읽지 않는다(설정 행은 그대로 남아 있다 —
 * 🔴 {@code RECOMMEND.CVLCPT_MM_CRTR_VL} 은 공유주차제가 계속 쓴다). 순위(검토지수·종합지수·단계·{@code rnk}·
 * {@code cumCpcty})는 판정과 무관해 그대로다.
 *
 * <h2>계수는 {@code RECOMMEND} 한 분석유형에서만 읽는다</h2>
 * 공통 2종({@code ALTMNT_RNG_VL} · {@code SWTC_TRGT_MIN_CPCTY}) + {@code FEE_} 8종이다.
 *
 * <h2>🔴 공용 조회 헬퍼는 없다 — {@link #toMap(List)} 가 그 역할이다</h2>
 * 기존 코드에 {@code vrbl.get(type, code, default)} 같은 창구가 없어
 * {@link AnalysisWeightMapper#selectWeightsByType(String)} 목록을 {@code vrblCd → 유효값} Map 으로
 * 접어 쓴다. 폴백은 3단이다 — {@code use_yn='Y'} ·
 * {@code stng_vl != null} 이면 {@code stng_vl}, 꺼진 행은 {@code bsc_stng_vl}, 그것도 없으면
 * 아래 상수.
 *
 * <p>🔴 <b>{@code AnalysisWeightService} 의 두 창구를 쓰지 않는다</b> — 실효 가중치 조회는
 * {@code WGVL} 재정규화 결과를 주고, 계수 단건 조회는 행이 없으면 {@code 1.0} 을 조용히 돌려준다.
 *
 * <p>⚠️ 평시에는 폴백을 타지 않는다 — 정상 호출의 {@code warnings} 는 <b>빈 배열</b>이다(§0-5).
 * 폴백은 "행이 지워졌거나 꺼졌을 때" 의 방어다.
 *
 * <p>🔴 {@code MathContext} 를 쓰지 않는다 — 이 백엔드 {@code src/main} 전체에 사용처가 0건이고,
 * 선례({@code HotspotWeightResolver.COEF_SCALE} + {@code divide(x, scale, HALF_UP)})가 있다.
 */
@Component
@RequiredArgsConstructor
public class ZoneRcmdtnCalculator {

    private static final Logger log = LoggerFactory.getLogger(ZoneRcmdtnCalculator.class);

    // =====================================================================================
    // 🔴 상수 — 선언이 없으면 아래 계산부가 컴파일되지 않는다
    // =====================================================================================

    /** 비율 → % 환산 배수. */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 나눗셈 스케일 — {@code HotspotWeightResolver.COEF_SCALE}(6) 과 같은 근거
     * (순위를 가를 오차가 남지 않는다).
     */
    private static final int CALC_SCALE = 6;

    /** {@code ALTMNT_RNG_VL} 폴백 — 마이그레이션 84 의 {@code bsc_stng_vl}(m). */
    private static final BigDecimal DEFAULT_ALTMNT_RNG = new BigDecimal("300");
    /** {@code SWTC_TRGT_MIN_CPCTY} 폴백 — 후보 주차장 면수 하한. */
    private static final BigDecimal DEFAULT_MIN_CPCTY = new BigDecimal("20");

    /**
     * 🔴 <b>증가율 분모 하한(건)</b> — 이전 블록이 이 값 미만이면 {@code cvlcptIncrsRt} 를
     * 계산하지 않는다({@code null}). §9-1. 🔁 Step 12·13 이 블록 길이를 동적으로(그리고 월 →
     * 일 단위로) 바꿨어도 <b>이 하한은 그대로다</b>(분모 폭발 방어는 블록 길이와 무관하다).
     *
     * <p>왜 필요한가 — 이도이동 {@code 1982-3} 은 이전 3건 → 최근 17건이라 산식대로면
     * <b>+466.7%</b> 로 1위가 된다. 실제로 늘어난 것은 <b>14건</b>이다. 작은 분모에서 폭발하는
     * 비율로 순위를 말하면 안 된다.
     *
     * <p>⚠️ {@code private} 이 아니라 <b>패키지 공개</b>다 — {@code ZoneRcmdtnServiceImpl} 이
     * {@code anls_param_json} 에, {@link ZoneRcmdtnCrtrVls} 가 응답에 이 값을 함께 굳힌다
     * (하한을 바꾸면 같은 조회가 다른 증가율을 낸다 → 재현에 필요하다).
     *
     * <p>🔁 TSK-4282 — 증가율은 이제 <b>증가계수({@link #incrsCfc})를 거쳐 순번에 반영</b>된다. 이 하한
     * 미달로 증가율이 {@code null} 이면 계수는 1(추세 중립)이다 — 하한은 여전히 정렬 키가 아니다.
     */
    static final int MIN_TREND_BASE = 10;

    /**
     * 🔴 <b>증가율 블록의 최소 일수</b> — 민원 창을 반 갈랐을 때 한 블록이 이 일수 미만이면
     * 증가율·월별 추이를 내지 않는다({@code warnings} 에 {@link #WARN_TREND_WINDOW_SHORT}).
     * 🔁 Step 13 — 종전 "완전한 달 2개 이상"(Step 12)·{@code months >= 6}(Step 9) 판정을 대신한다.
     *
     * <p>왜 7일인가 — 민원은 요일마다 다르다(실측 2026-01~08: 토 <b>15.9%</b> ↔ 월 <b>12.9%</b>).
     * 일주일보다 짧은 두 토막을 비교하면 <b>어느 요일이 들어왔는지</b>가 증가율을 만든다.
     * 즉 <b>조회 기간이 14일 미만이면 증가율이 비지는 것이 정상</b>이다(화면 문구도 "14일" 로 적는다).
     *
     * <p>⚠️ 하한을 거는 곳은 {@code ZoneRcmdtnServiceImpl.applyTrendWindow()} 한 곳이다 —
     * 이 클래스는 상수만 들고 있다({@link #MIN_TREND_BASE} 와 함께 "추세 하한 2종" 을 한자리에 둔다).
     */
    static final int MIN_TREND_DAYS = 7;

    /** 계수 폴백 경고 코드. 🔴 같은 코드는 한 번만 담는다({@link #addOnce}). */
    static final String WARN_COEF_FALLBACK = "COEF_FALLBACK";

    /**
     * 추세 창 부족 경고 — 🔁 <b>Step 13 기준: 선택 민원 창을 반 갈랐을 때 한 블록이
     * {@link #MIN_TREND_DAYS}(7일) 미만</b>(= 조회 기간 14일 미만)이라 비교가 성립하지 않는다
     * (Step 12 의 "완전한 달 2개 미만" · Step 9 의 "6개월 미만" 판정은 모두 폐지됐다).
     * 이때 증가율은 {@code null}, 월별 추이는 빈 배열이다.
     *
     * <p>🔴 0 을 넣지 않는다 — "비교 구간이 없다" 와 "0건이었다" 는 다르다.
     * ⚠️ 이 경고와 <b>분모 하한 미달</b>({@link #MIN_TREND_BASE})은 <b>다른 사유</b>다 —
     * 전자는 기간이 짧은 것, 후자는 민원이 적은 것이다. 화면 문구를 섞지 않는다.
     */
    static final String WARN_TREND_WINDOW_SHORT = "TREND_WINDOW_SHORT";

    /**
     * 🔁 <b>증가율의 "이전" 이 반 분할로 내려앉았다</b>는 경고 (Step 15 ②).
     *
     * <p>1차는 <b>직전 같은 길이의 기간</b>을 분모로 쓴다. 그 기간에 이 읍면동의 배정 민원이
     * <b>한 건도 없으면</b>({@link #bfrFallback}) Step 13 의 <b>반 분할</b>로 떨어진다 —
     * {@code ocrn_dt} 보유 구간이 <b>2026-01-01 ~ 2026-10-31(304일)</b> 뿐이라
     * {@code D + D ≤ 304}, 즉 {@code D ≤ 152일} 에서만 1차가 성립한다.
     *
     * <p>🔴 <b>어느 방식을 썼는지 화면이 말해야 한다</b> — 폴백이면 보조줄의 {@code 기간 243일} 과
     * 증가율이 보는 구간(121일씩)이 어긋나기 때문이다. {@code trendDayCnt} 가 {@code D} 인지
     * {@code D/2} 인지로 추론하게 두면 조용히 달라진다.
     *
     * <p>🔴 <b>판정은 읍면동 단위로 한 번</b>이다 — 행마다 방식이 다르면 같은 표의 {@code %} 가
     * 서로 다른 구간을 가리켜 비교가 깨진다.
     */
    static final String WARN_TREND_BFR_FALLBACK = "TREND_BFR_FALLBACK";

    // =====================================================================================
    // 🆕 TSK-4282 — 요금제 검토지수 (§0-2) · 폴백 상수 · 우선도 단계 경계(D14)
    // =====================================================================================

    /**
     * 🆕 TSK-4282 — 분석 방법 코드(D11). {@link #assemble}·{@link #assembleSgg} 가 새 결과에만 넣는다.
     * 🔴 VO 필드 초기값으로 두지 않는다 — 구 스냅샷 역직렬화 시 새 방식으로 오인된다.
     */
    public static final String ANLS_MTHD_RVW_SCR = "RVW_SCR";

    /** 🆕 TSK-4282 — 꺼지면({@code use_yn='N'}) 0 으로 읽는 FEE 가중치 2행(켜진 지표 100% — 합 정규화). */
    private static final List<String> FEE_WGVL_CODES = List.of("FEE_CVLCPT_CNT_WGVL", "FEE_CVLCPT_RT_WGVL");

    /**
     * {@code FEE_CVLCPT_CNT_WGVL} 폴백 — 민원강도 가중치(%).
     * 🔴 아래 8개는 마이그레이션 94 의 {@code bsc_stng_vl} 과 같은 숫자여야 한다(D5).
     */
    private static final BigDecimal DEFAULT_FEE_CVLCPT_CNT_WGVL = new BigDecimal("70");
    /** {@code FEE_CVLCPT_RT_WGVL} 폴백 — 면당민원 가중치(%). */
    private static final BigDecimal DEFAULT_FEE_CVLCPT_RT_WGVL = new BigDecimal("30");
    /** {@code FEE_CVLCPT_MM_CRTR_JEJU} 폴백 — 제주시 민원 월 기준값(건/월 · 2026-10 P90 22.33). */
    private static final BigDecimal DEFAULT_FEE_CVLCPT_MM_CRTR_JEJU = new BigDecimal("22");
    /** {@code FEE_CVLCPT_MM_CRTR_SEOGWIPO} 폴백 — 서귀포시 민원 월 기준값(건/월 · P90 9.73). */
    private static final BigDecimal DEFAULT_FEE_CVLCPT_MM_CRTR_SEOGWIPO = new BigDecimal("10");
    /** {@code FEE_CVLCPT_RT_CRTR_JEJU} 폴백 — 제주시 면당민원 기준값(건/100면·월 · P90 72.04). */
    private static final BigDecimal DEFAULT_FEE_CVLCPT_RT_CRTR_JEJU = new BigDecimal("72");
    /** {@code FEE_CVLCPT_RT_CRTR_SEOGWIPO} 폴백 — 서귀포시 면당민원 기준값(건/100면·월 · P90 27.54). */
    private static final BigDecimal DEFAULT_FEE_CVLCPT_RT_CRTR_SEOGWIPO = new BigDecimal("28");
    /** {@code FEE_CVLCPT_INCRS_WGVL} 폴백 — 민원증가 가중치(배). ⚠️ {@code vrbl_se_cd} 는 {@code CFC} 다 */
    private static final BigDecimal DEFAULT_FEE_CVLCPT_INCRS_WGVL = new BigDecimal("0.5");
    /** {@code FEE_CVLCPT_INCRS_RT_UPLMT_VL} 폴백 — 민원증가율 상한(%). */
    private static final BigDecimal DEFAULT_FEE_CVLCPT_INCRS_RT_UPLMT_VL = new BigDecimal("100");

    /**
     * 🔁 우선도 단계 경계(D14) — 단계는 순위값과 같은 {@code incrsRfltRvwScr}(화면 이름 "우선도", 반올림 전 원값)로
     * 매긴다. 이상(≥) 판정. 100 = 이 시군구 상위 10%(P90) 주차장 수준이라 "100 넘으면 최우선" 으로 읽힌다.
     * 🔴 이 클래스 자체 상수다 — 설정으로 빼지 않고, 집중구역 {@code HotspotGrade}(80/60/40)와는 값·뜻이 달라
     * 참조하지 않는다(종전 D6 폐기).
     */
    static final BigDecimal PRRT_TOP = new BigDecimal("100");
    /** 우선 하한(D14). 이 값 미만은 후순위다 — 🔁 2026-10-26 3단계로 개정, 30 경계(종전 "보통" 하한) 삭제 */
    static final BigDecimal PRRT_HIGH = new BigDecimal("60");

    /** 우선도 단계코드 — 최우선(100↑) */
    static final String GRD_CD_TOP = "TOP";
    /** 우선도 단계코드 — 우선(60↑) */
    static final String GRD_CD_HIGH = "HIGH";
    /**
     * 우선도 단계코드 — 후순위(60 미만).
     * 🔁 2026-10-26 사용자 요청 — 4단계(보통 {@code MID} 30↑ 포함) → 3단계. 종전 {@code MID}·{@code LOW} 가 이 하나로 합쳐졌다.
     */
    static final String GRD_CD_LOW = "LOW";

    /** 기준값을 고르는 시군구명 — 제주시({@code tb_admdst.sgg_nm} 원문). 그 밖 · {@code null} 도 이 기준이다 */
    private static final String SGG_JEJU = "제주시";
    /** 기준값을 고르는 시군구명 — 서귀포시 */
    private static final String SGG_SEOGWIPO = "서귀포시";

    /** 증가계수 표시 자릿수 — 공유주차제({@code ZoneRcmdtnShareCalculator.INCRS_CFC_SCALE})와 같다. */
    private static final int INCRS_CFC_SCALE = 3;

    /**
     * 🆕 TSK-4282 순번 규칙(§0-2) — 우선도(추세 반영 검토지수) ↓ → 배정 민원 ↓ → 면수 ↓ → {@code pklt_id} ↑.
     *
     * <p>🔴 <b>null 안전</b>이다 — {@link #scoreLots} 를 거치지 않은 행(점수 {@code null})은 뒤로 가고, 전 행이
     * {@code null} 이면 2차 키로 떨어져 매퍼 {@code ORDER BY}(D9)와 같은 순서가 된다. 비교는 <b>반올림 전</b>
     * 원값으로 한다(반올림은 번호를 매긴 뒤 {@link #roundScores}).
     */
    private static final Comparator<ZoneRcmdtnLotVO> RANK_ORDER = Comparator
            .comparing(ZoneRcmdtnLotVO::getIncrsRfltRvwScr, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(ZoneRcmdtnLotVO::getAsgnCvlcptCnt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(ZoneRcmdtnLotVO::getPkltCpcty, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(ZoneRcmdtnLotVO::getPkltId, Comparator.nullsLast(Comparator.naturalOrder()));

    /** 사용여부 컬럼({@code use_yn})의 '사용' 값. {@code CHAR(1)} 문자열이다(boolean 아님). */
    private static final String USE_Y = "Y";

    /** 계수를 읽는 분석유형 — 구역추천(공통 2 + FEE 8). */
    private static final String ANLS_TYPE_RECOMMEND = "RECOMMEND";

    /**
     * TSK-4020 산출물의 읽기 창구. 🔴 {@code AnalysisWeightService} 가 아니라 <b>매퍼</b>를 직접
     * 받는다 — 그 서비스의 계수 단건 조회는 행 부재 시 {@code 1.0} 을, 실효 가중치 조회는 재정규화
     * 결과를 돌려주기 때문이다(클래스 주석 참고).
     */
    private final AnalysisWeightMapper analysisWeightMapper;

    // =====================================================================================
    // 계수 조달
    // =====================================================================================

    /**
     * {@code RECOMMEND} 분석유형을 읽어 기준값 한 벌로 돌려준다.
     *
     * <p>🔁 TSK-4340(2026-11-11) — 읍면동 판정 제거로 {@code POLICY_SIM}(기준점유율·유료수요율) 조회를 뺐다.
     *
     * @param trgtTypeSe 후보 주차장 유형 범위(요청 파라미터 — 설정이 아니다)
     * @param warnings   폴백이 일어나면 {@link #WARN_COEF_FALLBACK} 을 <b>한 번만</b> 담는다
     */
    public ZoneRcmdtnCrtrVls resolveCrtrVls(String trgtTypeSe, List<String> warnings) {
        return resolveCrtrVls(trgtTypeSe, warnings, null);
    }

    /**
     * 위와 같고 분석 계수 일시 입력을 얹는다 (TSK-4325).
     *
     * <p>🔴 {@code RECOMMEND} 행에만 덮는다(D19). 산식·단계 경계는
     * 그대로이고 적용값만 달라진다.
     *
     * @param ovrd 검증을 마친 입력. {@code null} 이면 설정값 그대로다
     */
    public ZoneRcmdtnCrtrVls resolveCrtrVls(String trgtTypeSe, List<String> warnings, AnlsVrblOvrd ovrd) {
        List<AnalysisWeightVO> rcRows = analysisWeightMapper.selectWeightsByType(ANLS_TYPE_RECOMMEND);
        if (ovrd != null) {
            rcRows = ovrd.apply(rcRows);
        }
        Map<String, BigDecimal> rc = toMap(rcRows);
        // 🆕 TSK-4282 — FEE 가중치 2행은 꺼지면 bsc_stng_vl 이 아니라 0 이다(selectEffectiveWeights 가 꺼진
        //    지표를 빼는 의미와 같다). 🔴 toMap 의 공용 의미는 바꾸지 않고 여기서 덮는다.
        if (rcRows != null) {
            for (AnalysisWeightVO row : rcRows) {
                if (row != null && FEE_WGVL_CODES.contains(row.getVrblCd()) && !USE_Y.equals(row.getUseYn())) {
                    rc.put(row.getVrblCd(), BigDecimal.ZERO);
                }
            }
        }
        return resolveCrtrVls(trgtTypeSe, rc, warnings);
    }

    /**
     * 위와 같은 조달을 <b>이미 접힌 Map</b> 으로 수행한다 — 폴백 경로 단위테스트의 창구다
     * (빈 Map 을 넣으면 10종(공통 2 + FEE 8) 전부 상수로 떨어지고 {@code warnings} 에
     * {@link #WARN_COEF_FALLBACK} 이 <b>1건만</b> 담긴다).
     *
     * @param rc {@code RECOMMEND} 의 {@code vrblCd → 유효값}
     */
    public ZoneRcmdtnCrtrVls resolveCrtrVls(String trgtTypeSe,
                                            Map<String, BigDecimal> rc,
                                            List<String> warnings) {
        // 🔴 RECOMMEND 공통 2종 — 매퍼의 #{minCpcty}·#{altmntRng} 입력이다.
        //    🔁 TSK-4340 — 판정 계수 STSF_RT_THRSH·ILGL_CRTR_VL·CVLCPT_MM_CRTR_VL 은 더 읽지 않는다.
        BigDecimal altmntRng = resolveCfc(rc, "ALTMNT_RNG_VL", DEFAULT_ALTMNT_RNG, warnings);
        // 🔴 min_vl=0 이라 0 이 적법한 설정값이다 — signum() 가드를 태우지 않는다
        BigDecimal minCpcty = resolveCfcAllowZero(rc, "SWTC_TRGT_MIN_CPCTY", DEFAULT_MIN_CPCTY, warnings);

        // 🔴 minTrendBase 는 설정이 아니라 코드 상수다 — 그래도 결과와 함께 굳힌다(재현성)
        // 🔴 trendDayCnt 는 여기서 알 수 없다 — 조회 기간에서 파생되므로 서비스가 창을 만든 뒤
        //    withTrendDayCnt() 로 채워 넣는다(Step 13). 여기서 0 을 박으면 "0일 블록" 이 응답에 남는다.
        // 🆕 TSK-4282 — 요금제 검토지수 8종(같은 RECOMMEND 맵에 함께 들어온다).
        //    기준값 4종·상한은 0 이하 → 폴백(resolveCfc). 가중치 2종·증가 가중치는 min_vl=0 이라
        //    0 이 적법하다(resolveCfcAllowZero) — FEE WGVL 2행의 "꺼짐 = 0" 은 2인자 오버로드가 덮었다.
        BigDecimal feeCntWgvl = resolveCfcAllowZero(rc, "FEE_CVLCPT_CNT_WGVL", DEFAULT_FEE_CVLCPT_CNT_WGVL, warnings);
        BigDecimal feeRtWgvl = resolveCfcAllowZero(rc, "FEE_CVLCPT_RT_WGVL", DEFAULT_FEE_CVLCPT_RT_WGVL, warnings);
        if (feeCntWgvl.signum() <= 0 && feeRtWgvl.signum() <= 0) {
            // 🔴 둘 다 0(또는 음수)이면 식 ÷(w1+w2) 가 0/0 이다 — 폴백 70/30 을 crtrVls 에 담아
            //    스냅샷에 실제 적용값이 남게 한다(한 행만 0 이면 그 식이 곧 합 정규화라 그대로 둔다).
            addOnce(warnings, WARN_COEF_FALLBACK);
            feeCntWgvl = DEFAULT_FEE_CVLCPT_CNT_WGVL;
            feeRtWgvl = DEFAULT_FEE_CVLCPT_RT_WGVL;
        }
        BigDecimal feeMmJeju = resolveCfc(rc, "FEE_CVLCPT_MM_CRTR_JEJU", DEFAULT_FEE_CVLCPT_MM_CRTR_JEJU, warnings);
        BigDecimal feeMmSgp = resolveCfc(rc, "FEE_CVLCPT_MM_CRTR_SEOGWIPO", DEFAULT_FEE_CVLCPT_MM_CRTR_SEOGWIPO, warnings);
        BigDecimal feeRtJeju = resolveCfc(rc, "FEE_CVLCPT_RT_CRTR_JEJU", DEFAULT_FEE_CVLCPT_RT_CRTR_JEJU, warnings);
        BigDecimal feeRtSgp = resolveCfc(rc, "FEE_CVLCPT_RT_CRTR_SEOGWIPO", DEFAULT_FEE_CVLCPT_RT_CRTR_SEOGWIPO, warnings);
        BigDecimal feeIncrsWgvl = resolveCfcAllowZero(rc, "FEE_CVLCPT_INCRS_WGVL", DEFAULT_FEE_CVLCPT_INCRS_WGVL, warnings);
        BigDecimal feeUplmt = resolveCfc(rc, "FEE_CVLCPT_INCRS_RT_UPLMT_VL", DEFAULT_FEE_CVLCPT_INCRS_RT_UPLMT_VL, warnings);
        return new ZoneRcmdtnCrtrVls(trgtTypeSe, altmntRng, minCpcty, MIN_TREND_BASE, null,
                feeCntWgvl, feeRtWgvl, feeMmJeju, feeMmSgp, feeRtJeju, feeRtSgp,
                feeIncrsWgvl, feeUplmt);
    }

    /**
     * {@code selectWeightsByType} 결과를 {@code vrblCd → 유효값} 으로 접는다 —
     * {@code use_yn='Y'}·{@code stng_vl != null} 이면 {@code stngVl}, 꺼진 행은 {@code bscStngVl}.
     *
     * <p>둘 다 비면 <b>키를 담지 않는다</b> — 그러면 {@code resolveCfc} 가 상수 폴백 + 경고로 간다.
     * ({@code rows} 가 {@code null} 이어도 죽지 않는다 — 매퍼 계약이 바뀌어도 분석 화면이 500 이
     * 되면 안 된다.)
     */
    static Map<String, BigDecimal> toMap(List<AnalysisWeightVO> rows) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        if (rows == null) {
            return m;
        }
        for (AnalysisWeightVO row : rows) {
            if (row == null || row.getVrblCd() == null) {
                continue;
            }
            BigDecimal value = USE_Y.equals(row.getUseYn()) ? row.getStngVl() : null;
            if (value == null) {
                value = row.getBscStngVl();
            }
            if (value != null) {
                m.put(row.getVrblCd(), value);
            }
        }
        return m;
    }

    /** 🔴 경고는 한 번만 담는다 — 계수 10종이 같은 목록을 공유한다. */
    private static void addOnce(List<String> warnings, String w) {
        if (!warnings.contains(w)) {
            warnings.add(w);
        }
    }

    /**
     * 계수 1건을 고른다 — 없거나 {@code <= 0} 이면 상수 폴백 + 경고.
     *
     * <p>🔴 경고는 한 번만 담는다 — 계수 10종이 같은 목록을 공유하므로 2건 이상 폴백하면
     * 응답 {@code warnings} 가 {@code ["COEF_FALLBACK","COEF_FALLBACK",…]} 이 된다.
     */
    private static BigDecimal resolveCfc(Map<String, BigDecimal> m, String code,
                                         BigDecimal dflt, List<String> warnings) {
        BigDecimal v = m.get(code);
        if (v == null || v.signum() <= 0) {
            addOnce(warnings, WARN_COEF_FALLBACK);
            return dflt;
        }
        return v;
    }

    /**
     * {@code 0} 이 <b>적법한 설정값</b>인 계수를 고른다 — null 검사로 끝낸다.
     *
     * <p>🔴 {@code SWTC_TRGT_MIN_CPCTY}({@code min_vl=0})·FEE 가중치 3종이 그 대상이다. {@link #resolveCfc} 의 {@code signum() <= 0} 가드를
     * 태우면 사용자가 0 으로 내린 순간 <b>조용히 기본값으로 되돌아가고</b> 평시 호출의
     * {@code warnings} 가 빈 배열이 아니게 된다.
     */
    private static BigDecimal resolveCfcAllowZero(Map<String, BigDecimal> m, String code,
                                                  BigDecimal dflt, List<String> warnings) {
        BigDecimal v = m.get(code);
        if (v == null) {
            addOnce(warnings, WARN_COEF_FALLBACK);
            return dflt;
        }
        return v;
    }

    // =====================================================================================
    // 읍면동 · 순위
    // =====================================================================================

    /**
     * 읍면동 기준선 1행에서 <b>위치 3필드</b>({@code emdCd}·{@code emdNm}·{@code sggNm})만 옮긴다 —
     * {@code /zones}(읍면동 선택 목록)와 상세 {@code zone} 블록이 같은 모양을 쓴다.
     *
     * <p>🔁 TSK-4340(2026-11-11) — 종전 {@code judge}(E0·필요 면수·충족률·추천유형·판정 불가 사유)를 대신한다.
     * 판정은 서버에서도 하지 않는다.
     */
    public ZoneRcmdtnResultVO.Zone zoneOf(ZoneRcmdtnBaselineVO row) {
        ZoneRcmdtnResultVO.Zone z = new ZoneRcmdtnResultVO.Zone();
        z.setEmdCd(row.getEmdCd());
        z.setEmdNm(row.getEmdNm());
        z.setSggNm(row.getSggNm());
        return z;
    }

    /**
     * 선택 읍면동 + 주차장 순위를 한 벌로 조립한다 — 상세 조회용.
     *
     * <h2>🔁 증가율을 못 낸 주차장도 목록에 남는다 (Step 16 — Step 14-2 철회)</h2>
     * Step 14 는 {@code cvlcptIncrsRt == null} 인 행을 순위 계산 전에 걷어내고 그 개소를
     * 전용 응답 필드로 밝혔다. 🔴 <b>사용자 결정(2026-10-13)으로 철회했다</b> — 증가율은
     * 참고 지표 하나일 뿐인데 그것 하나로 후보가 화면·지도에서 사라지면 "유료화 후보" 목록 자체가
     * 불완전해지고, 리스트의 누적 면수 최대값이 요약의 가능 면수와 어긋났다
     * (이도이동 723면 ↔ 1,128면). <b>같은 제안을 다시 하지 않도록 적어 둔다.</b>
     * 증가율이 없는 행은 화면이 {@code —} 와 회색 점으로 그린다.
     *
     * <p>그래서 순위·누적 면수({@link #rank})와 배정 커버리지({@link #asgn})가
     * <b>모두 같은 전 후보 목록</b>을 본다 — 두 기준이 갈릴 자리가 없다.
     *
     * @param zone   읍면동 기준선(매퍼 산출 — 위치 3필드와 배정률 분모 {@code cvlcptCnt} 만 읽는다)
     * @param lots   후보 주차장(매퍼 정렬 = 결정적 입력 순서. 🔁 TSK-4282 최종 정렬은 {@link #rank} 가 한다)
     * @param c      적용 기준값
     * @param warnings 계수 폴백 경고 목록(이 메서드는 여기에 더 담지 않는다)
     */
    public ZoneRcmdtnResultVO assemble(ZoneRcmdtnBaselineVO zone,
                                       List<ZoneRcmdtnLotVO> lots,
                                       ZoneRcmdtnCrtrVls c,
                                       List<String> warnings) {
        // 🔁 Step 16 — 전 후보를 그대로 순위·누적·배정에 넣는다(Step 14 의 제외는 철회됐다).
        List<ZoneRcmdtnLotVO> all = lots != null ? lots : new ArrayList<>();

        List<ZoneRcmdtnLotVO> ranked = rank(all);
        // 🆕 TSK-4282 — 표시용 반올림은 정렬·번호·누적을 <b>마친 뒤</b> 끝에서 한 번(비교는 원값으로 했다)
        roundScores(ranked);

        ZoneRcmdtnResultVO result = new ZoneRcmdtnResultVO();
        result.setAnlsMthd(ANLS_MTHD_RVW_SCR);   // 🆕 TSK-4282 D11
        result.setCrtrVls(c);
        result.setWarnings(warnings != null ? warnings : new ArrayList<>());
        result.setZone(zoneOf(zone));
        result.setAsgn(asgn(zone.getCvlcptCnt(), all));
        result.setLots(ranked);
        return result;
    }

    /**
     * 🔁 TSK-4259 — <b>시군구 모드</b> 조립: 시군구 안 전 후보 주차장의 순위만 낸다.
     *
     * <p>🔴 {@link #assemble} 을 재사용하지 않는다 — 그쪽은 {@code zoneOf(zone)}·
     * {@code asgn(zone.getCvlcptCnt())} 를 무조건 불러 {@code zone == null} 이면 NPE 다. 배정률은
     * <b>읍면동 단위 지표</b>라(§8) 시군구로 합산하지 않는다(D4).
     *
     * <p>그래서 {@code zone = null} · {@code asgn = null} · {@code lots[].rnk} 만 1부터 · {@code cumCpcty = null} 이다. 🔁 TSK-4282 — 순서는 {@link #rankOnly} 가 §0-2 순번 규칙({@link #RANK_ORDER})으로
     * 다시 정한다(점수가 {@code null} 이면 매퍼 정렬과 같은 2차 키 순). 증가율·월별 추이는 호출부가
     * {@link #applyTrend} 로 이미 채웠다(행 단위라 모드 무관).
     *
     * <p>⚠️ {@code scope}·{@code sggNm} 은 여기서 채우지 않는다 — 서비스가 정규화된 값으로 채운다.
     *
     * @param lots     후보 주차장(매퍼 정렬 순서로 오지만 🔁 TSK-4282 부터 {@link #rankOnly} 가 검토지수 순으로 다시 정렬한다. 없으면 빈 목록)
     * @param c        적용 기준값
     * @param warnings 경고 목록(이 메서드는 더 담지 않는다)
     */
    public ZoneRcmdtnResultVO assembleSgg(List<ZoneRcmdtnLotVO> lots,
                                          ZoneRcmdtnCrtrVls c,
                                          List<String> warnings) {
        ZoneRcmdtnResultVO result = new ZoneRcmdtnResultVO();
        result.setAnlsMthd(ANLS_MTHD_RVW_SCR);   // 🆕 TSK-4282 D11
        result.setCrtrVls(c);
        result.setWarnings(warnings != null ? warnings : new ArrayList<>());
        result.setZone(null);
        result.setAsgn(null);
        List<ZoneRcmdtnLotVO> ranked = rankOnly(lots);
        roundScores(ranked);   // 🆕 TSK-4282 — 번호를 매긴 뒤 표시용 반올림
        result.setLots(ranked);
        return result;
    }

    // =====================================================================================
    // 추세 — 최근 블록 · 이전 블록 · 증가율
    // (Step 9 고정 3+3 → 🔁 Step 12 월 단위 반분할 → 🔁 Step 13 일 단위 반분할)
    // =====================================================================================

    /**
     * 🔁 <b>1차(직전 동일 기간) ↔ 폴백(반 분할) 판정</b> — {@code selectLotTrend} 결과의
     * <b>1차 이전 블록이 읍면동 전체에서 0건</b>이면 {@code true}(= 반 분할로 내려앉는다). Step 15 ②.
     *
     * <p>🔴 <b>읍면동 단위로 한 번 결정한다</b> — 행마다 방식이 다르면 같은 표의 {@code %} 가 서로
     * 다른 구간을 가리켜 비교가 깨진다. 그래서 판정은 <b>전 행의 합</b>으로 한다.
     *
     * <p>🔴 <b>"0건" 만 폴백 사유다</b> — 적지만 있는 것(분모 미달)은 {@link #MIN_TREND_BASE} 가
     * <b>행 단위로</b> 거른다. 둘을 섞으면 "민원이 적은 동" 이 통째로 다른 구간을 보게 된다.
     *
     * <p>⚠️ {@code rows} 가 비어도({@code null} 포함) {@code true} 다 — 그 동은 어차피 1차로 낼
     * 숫자가 없다.
     */
    public static boolean bfrFallback(List<ZoneRcmdtnTrendCountVO> rows) {
        if (rows == null) {
            return true;
        }
        long bfrTot = 0L;
        for (ZoneRcmdtnTrendCountVO row : rows) {
            if (row != null) {
                bfrTot += row.getCntBfr();
            }
        }
        return bfrTot == 0L;
    }

    /**
     * 🆕 <b>TSK-4282 D13 — 자료 시작일 가드를 더한 폴백 판정.</b> 1차 이전 블록 시작({@code bfrFrom})이
     * 민원 자료 시작일({@code dataBgngDt}, {@code min(ocrn_dt)} 그날 00:00)보다 <b>앞</b>이면 {@code true}
     * 다. 기존 판정({@link #bfrFallback(List)} — 이전 블록 전 행 합 0)과 <b>OR</b> 로 합친다.
     *
     * <p>🔴 근거(Step 0 실측) — 이전 블록이 자료 앞으로 넘어가면 자료가 있는 일부 기간만 분모가 되어 증가율이
     * 부푼다(6개월 조회: 이전 184일 중 120일만 자료 · +1956% · 서귀포 상위 15곳 중 12곳이 상한 1.5). 순위가
     * 추세가 아니라 자료 공백을 따라 움직인다. 공유주차제({@code ZoneRcmdtnShareCalculator.trendMode})도
     * 같은 규칙이다 — 한쪽만 고치지 말 것.
     *
     * <p>{@code dataBgngDt} 가 {@code null}(자료 없음)이거나 {@code bfrFrom} 이 {@code null} 이면
     * 종전 판정 그대로다. 같은 날(시작 = 자료 시작)은 앞이 아니다 — 1차다.
     */
    public static boolean bfrFallback(List<ZoneRcmdtnTrendCountVO> rows,
                                      LocalDateTime bfrFrom, LocalDateTime dataBgngDt) {
        return bfrBeforeData(bfrFrom, dataBgngDt) || bfrFallback(rows);
    }

    /**
     * 🆕 D13 공용 판정 — 이전 블록 시작이 자료 시작일보다 앞인가. 요금제·공유주차제가 같이 쓴다
     * (규칙을 두 벌 두지 않는다). 어느 쪽이든 {@code null} 이면 {@code false}(= 종전 동작).
     */
    public static boolean bfrBeforeData(LocalDateTime bfrFrom, LocalDateTime dataBgngDt) {
        return bfrFrom != null && dataBgngDt != null && bfrFrom.isBefore(dataBgngDt);
    }

    /**
     * 🆕 D13 — {@link DataRangeVO#getDataBgngYmd()}({@code YYYYMMDD})를 그날 00:00 으로 바꾼다.
     * 자료가 없거나({@code null}) 형식이 어긋나면 {@code null} — 가드를 끄고 종전 판정으로 둔다
     * (안내용 값 하나 때문에 조회 전체를 500 으로 끊지 않는다).
     */
    public static LocalDateTime dataBgngDt(DataRangeVO range) {
        if (range == null || range.getDataBgngYmd() == null) {
            return null;
        }
        try {
            return LocalDate.parse(range.getDataBgngYmd(), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
        } catch (DateTimeParseException e) {
            // 안내용 값이라 조회 전체를 끊지 않는다 — null(자료 시작일 없음)로 둔다 (4.2 — TSK-4336)
            log.warn("자료 시작일 형식 오류 — null 로 처리: dataBgngYmd={}", range.getDataBgngYmd());
            return null;
        }
    }

    /**
     * 주차장 목록에 <b>블록 건수·증가율·월별 추이</b>를 채운다 (§9-1).
     *
     * <p>🔴 {@code asgnCvlcptCnt}(전 구간)는 <b>건드리지 않는다</b> — 정렬 기준이다. 여기서 채우는
     * 것은 별개 칸({@code asgnCvlcptCntRcnt}·{@code asgnCvlcptCntBfr}·{@code cvlcptIncrsRt}·
     * {@code trendMm})이다.
     *
     * <p>🔁 <b>Step 15 — 블록 건수의 소유자가 여기로 옮겨 왔다.</b> 종전에는 {@code selectLotRanking}
     * 이 두 칸을 함께 냈지만, 1차 "이전" 블록이 민원 창 <b>밖</b>이라 그 쿼리의 스캔 범위로는 셀 수
     * 없고 범위를 넓히면 정렬 기준이 2배가 된다(15-2). 그래서 {@code selectLotTrend} 가 네 칸을
     * 내고 이 메서드가 {@code bfrFallbackApplied} 로 <b>두 칸을 골라</b> 채운다 — 블록 건수·월별
     * 추이·증가율이 <b>같은 블록</b>을 보는 것이 구조로 보장된다.
     *
     * <p>🔴 {@code trendWindowOk} 가 {@code false}(블록 7일 미만 = 조회 기간 14일 미만)면
     * <b>4칸을 모두 비운다</b> —
     * 최근·이전 블록 건수까지 {@code null} 로 되돌린다. 반으로 가를 수 없는 창에서 나온 숫자를
     * 그대로 내리면 "최근에 늘었다/줄었다" 를 잘못 읽게 된다.
     * 경고({@link #WARN_TREND_WINDOW_SHORT})는 {@link #addOnce} 로 한 번만 담는다.
     *
     * <p>🔴 {@code trendMm} 은 <b>배정 0건인 달도 0 으로 채워 항상 {@code trendYms.size()} 칸</b>이다
     * — 매퍼는 배정이 있는 달만 돌려주므로(이도이동 {@code 1963-7} 은 2행뿐) 그대로 내리면 주차장마다
     * 차트의 x축 칸 수가 달라진다. 그래서 {@code trendMm} 합은 항상 {@code asgnCvlcptCntRcnt} 와 같다.
     *
     * @param lots          매퍼가 정렬까지 끝낸 주차장 목록(이 메서드가 제자리에서 채운다)
     * @param trendRows     {@code selectLotTrend} 의 평면 행. 창이 짧으면 빈 목록이다
     * @param trendYms      차트 x축 — 🔁 <b>최근 블록이 걸친</b> 연월({@code YYYY-MM}) 오름차순.
     *                      🔴 칸 수는 블록 일수가 아니라 <b>걸친 달 수</b>다(양 끝은 부분 달이다)
     * @param trendWindowOk 반 갈린 블록이 {@link #MIN_TREND_DAYS} 일 이상인가
     * @param bfrFallbackApplied 🔁 {@link #bfrFallback} 의 판정 — {@code true} 면 <b>폴백 2칸</b>
     *                      ({@code cntRcntSplit}·{@code cntBfrSplit})을 읽고
     *                      {@link #WARN_TREND_BFR_FALLBACK} 을 담는다
     * @param warnings      창이 짧으면 {@link #WARN_TREND_WINDOW_SHORT},
     *                      폴백이면 {@link #WARN_TREND_BFR_FALLBACK} 을 각각 한 번만 담는다
     */
    public void applyTrend(List<ZoneRcmdtnLotVO> lots,
                           List<ZoneRcmdtnTrendCountVO> trendRows,
                           List<String> trendYms,
                           boolean trendWindowOk,
                           boolean bfrFallbackApplied,
                           List<String> warnings) {
        if (!trendWindowOk && warnings != null) {
            addOnce(warnings, WARN_TREND_WINDOW_SHORT);
        }
        // 🔴 창이 짧으면 폴백을 말하지 않는다 — 그때는 어느 방식도 쓰지 않았다(사유가 하나여야 한다)
        if (trendWindowOk && bfrFallbackApplied && warnings != null) {
            addOnce(warnings, WARN_TREND_BFR_FALLBACK);
        }
        if (lots == null || lots.isEmpty()) {
            // 후보 0개소인 동(도련일동)이어도 경고는 남는다 — 창이 짧다는 사실은 목록과 무관하다
            return;
        }
        if (!trendWindowOk) {
            for (ZoneRcmdtnLotVO l : lots) {
                // 🔴 0 이 아니라 null 이다 — 계산하지 않았다는 사실 자체가 정보다
                l.setAsgnCvlcptCntRcnt(null);
                l.setAsgnCvlcptCntBfr(null);
                l.setCvlcptIncrsRt(null);
                l.setTrendMm(new ArrayList<>());
            }
            return;
        }

        // 🔴 최근 블록은 '달별' 로도 필요하고(차트) '합' 으로도 필요하다(증가율 분자) — 같은 칸에서
        //    둘을 만들기 때문에 trendMm 합 = asgnCvlcptCntRcnt 불변식이 구조로 유지된다.
        Map<Long, Map<String, Long>> rcntByLot = new LinkedHashMap<>();
        Map<Long, Long> bfrByLot = new LinkedHashMap<>();
        if (trendRows != null) {
            for (ZoneRcmdtnTrendCountVO row : trendRows) {
                if (row == null || row.getPkltId() == null || row.getYm() == null) {
                    continue;
                }
                long rcnt = bfrFallbackApplied ? row.getCntRcntSplit() : row.getCntRcnt();
                long bfr = bfrFallbackApplied ? row.getCntBfrSplit() : row.getCntBfr();
                // 🔴 merge 다 — 1차는 한 연월이 이전·최근 양쪽에 걸릴 수 있어 같은 (lot, ym) 이
                //    한 번만 오지만, 칸이 갈려 있어 섞이지 않는다(매퍼 주석 참고).
                rcntByLot.computeIfAbsent(row.getPkltId(), k -> new LinkedHashMap<>())
                        .merge(row.getYm(), rcnt, Long::sum);
                bfrByLot.merge(row.getPkltId(), bfr, Long::sum);
            }
        }
        List<String> yms = trendYms != null ? trendYms : new ArrayList<>();
        for (ZoneRcmdtnLotVO l : lots) {
            Map<String, Long> mm = rcntByLot.getOrDefault(l.getPkltId(), Map.of());
            List<ZoneRcmdtnTrendVO> trend = new ArrayList<>(yms.size());
            long rcntTot = 0L;
            for (String ym : yms) {
                // 🔴 빈 달을 0 으로 채운다 — 빠뜨리면 주차장마다 x축 칸 수가 달라진다
                long cnt = mm.getOrDefault(ym, 0L);
                rcntTot += cnt;
                trend.add(new ZoneRcmdtnTrendVO(ym, cnt));
            }
            l.setTrendMm(trend);
            // 🔁 Step 15 — 블록 건수도 여기서 채운다(매퍼가 아니다). 🔴 배정이 한 건도 없는 주차장은
            //    trendRows 에 행이 없으므로 null 이 아니라 0 이다("창은 있었다" 가 사실이다).
            l.setAsgnCvlcptCntRcnt(rcntTot);
            l.setAsgnCvlcptCntBfr(bfrByLot.getOrDefault(l.getPkltId(), 0L));
            l.setCvlcptIncrsRt(incrsRt(l.getAsgnCvlcptCntRcnt(), l.getAsgnCvlcptCntBfr()));
        }
    }

    /**
     * 민원 증가율(%) = (최근 블록 − 이전 블록) ÷ 이전 블록 × 100, 소수 1자리.
     *
     * <p>🔴 이전이 {@link #MIN_TREND_BASE} <b>미만이면 {@code null}</b> 이다 — {@code 0} 도 당연히
     * 포함된다(0 으로 나누지 않는다). 실측: 84/66 → {@code 27.3} · 11/15 → {@code -26.7} ·
     * 17/3 → <b>{@code null}</b>(종전 산식이면 {@code +466.7}).
     *
     * <p>⚠️ {@code public static} 이라 단위테스트가 경계값(분모 0·9·10)을 직접 부를 수 있다 —
     * 테스트가 {@code jeju.datahub.analysis} 패키지라 패키지 공개로는 보이지 않는다.
     */
    public static BigDecimal incrsRt(Long recent, Long prev) {
        if (recent == null || prev == null || prev < MIN_TREND_BASE) {
            return null;
        }
        return BigDecimal.valueOf(recent - prev)
                .divide(BigDecimal.valueOf(prev), CALC_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 🆕 TSK-4282 — <b>증가계수</b> = 1 + {@code wi} × clamp(g, −상한/100, +상한/100). {@code g} 가 {@code null} 이면 1
     * (추세 중립). 가중치 0 이면 항상 1 이다(= 추세 미반영 순서). 반올림하지 않는다 — 표시는 호출부가 한다.
     *
     * <p>🔴 <b>정본은 여기 한 곳</b>이다 — 요금제({@link #scoreLots})와 공유주차제
     * ({@code ZoneRcmdtnShareCalculator})가 같은 산식을 쓴다(D4 — 한 화면 두 탭의 순서 규칙을 같게).
     * 본문은 형제 TSK-4283 이 공유주차제에 먼저 둔 private 메서드를 그대로 옮긴 것이고, 그쪽은 이제 위임 한 줄이다.
     *
     * <p>⚠️ {@code g} 는 <b>% 가 아니라 비율</b>이다(+35% → {@code 0.35}). 공유주차제가 정밀 비율을 넘기기 때문이다 —
     * 요금제는 {@code cvlcptIncrsRt}(%)를 {@code movePointLeft(2)} 로 바꿔 넘긴다(자리 이동이라 오차가 없다).
     * 상한 {@code uplmt} 는 설정 단위 그대로 <b>%</b> 다.
     *
     * <p>⚠️ {@code public static} 인 이유는 {@link #incrsRt} 와 같다 — 테스트 패키지가 다르다.
     *
     * @param g     증가율 비율. {@code null} 이면 1
     * @param wi    민원증가 가중치(배)
     * @param uplmt 민원증가율 상한(%)
     */
    public static BigDecimal incrsCfc(BigDecimal g, BigDecimal wi, BigDecimal uplmt) {
        if (g == null) {
            return BigDecimal.ONE;
        }
        BigDecimal cap = uplmt.movePointLeft(2);
        BigDecimal clamped = g.max(cap.negate()).min(cap);
        return BigDecimal.ONE.add(wi.multiply(clamped));
    }

    // =====================================================================================
    // 🆕 TSK-4282 — 요금제 검토지수 · 증가계수 · 우선도 단계 (§0-2 · D14)
    // =====================================================================================

    /**
     * 🆕 TSK-4282 — 각 주차장에 <b>민원강도·면당압력 점수 · 검토지수 · 증가계수 · 우선도 · 우선도 단계</b>를
     * 채운다(§0-2). 정렬·번호는 하지 않는다 — {@link #rank}/{@link #rankOnly} 가 {@link #RANK_ORDER} 로 한다.
     *
     * <pre>
     * mm = 배정민원 ÷ M          rt = mm ÷ 면수 × 100
     * s1 = mm ÷ A × 100          s2 = rt ÷ B × 100           (A·B = 시군구별 기준값)
     * rvwScr = (w1·s1 + w2·s2) ÷ (w1 + w2)                   🔴 상한 없음
     * incrsRfltRvwScr = rvwScr × incrsCfc(g)                 우선도 — 순위의 1차 키
     * rvwGrdCd = incrsRfltRvwScr 100↑ TOP · 60↑ HIGH · LOW   (D14 — 순위와 같은 값이라 단조)
     * </pre>
     *
     * <ul>
     *   <li>🔴 VO 에는 <b>원값</b>을 넣는다 — 반올림은 정렬·번호 매김 뒤 {@link #roundScores} 가 한다.</li>
     *   <li>시군구는 행의 {@code sggNm} 으로 고른다. {@code '제주시'}·{@code '서귀포시'} 가 아니거나 {@code null}
     *       ({@code LEFT JOIN} 미매칭)이면 <b>제주시 기준값</b> + {@link #WARN_COEF_FALLBACK}(한 번만).</li>
     *   <li>{@code ÷ (w1 + w2)} 가 곧 합 정규화다 — 한 행이 꺼져 0 이면 켜진 지표가 100% 다. 🔴 둘 다 0 이면
     *       0/0 이라 폴백 70/30 + {@link #WARN_COEF_FALLBACK}(평시에는 조달 쪽이 이미 막는다 — 방어선).</li>
     *   <li>기준값이 {@code null}·0 이하, 가중치·증가 가중치가 {@code null}·음수, 상한이 {@code null}·0 이하면
     *       같은 폴백 상수 + {@link #WARN_COEF_FALLBACK}. 면수 0·{@code null} 이면 {@code cvlcptRtScr = 0}.</li>
     *   <li>{@code months} 가 {@code null}·0 이하면 아무것도 채우지 않는다(점수 {@code null} → 2차 키 정렬).</li>
     * </ul>
     *
     * @param lots     후보 주차장 — {@link #applyTrend} 뒤(증가율이 채워진 상태)여야 한다
     * @param months   조회 개월 수({@code PrdMonths} — TSK-4284 정본)
     * @param c        적용 기준값(FEE 8칸을 getter 로 직접 읽는다)
     * @param warnings 폴백이면 {@link #WARN_COEF_FALLBACK} 을 한 번만 담는다({@code null} 이면 담지 않는다)
     */
    public void scoreLots(List<ZoneRcmdtnLotVO> lots, BigDecimal months, ZoneRcmdtnCrtrVls c,
                          List<String> warnings) {
        if (lots == null || lots.isEmpty() || months == null || months.signum() <= 0 || c == null) {
            return;
        }
        BigDecimal w1 = nonNegOr(c.getFeeCvlcptCntWgvl(), DEFAULT_FEE_CVLCPT_CNT_WGVL, warnings);
        BigDecimal w2 = nonNegOr(c.getFeeCvlcptRtWgvl(), DEFAULT_FEE_CVLCPT_RT_WGVL, warnings);
        if (w1.add(w2).signum() == 0) {
            // 🔴 0/0 방어 — 지표가 하나도 켜지지 않은 검토지수는 정의되지 않는다
            warn(warnings, WARN_COEF_FALLBACK);
            w1 = DEFAULT_FEE_CVLCPT_CNT_WGVL;
            w2 = DEFAULT_FEE_CVLCPT_RT_WGVL;
        }
        BigDecimal wSum = w1.add(w2);
        BigDecimal mmJeju = posOr(c.getFeeCvlcptMmCrtrJeju(), DEFAULT_FEE_CVLCPT_MM_CRTR_JEJU, warnings);
        BigDecimal mmSgp = posOr(c.getFeeCvlcptMmCrtrSeogwipo(), DEFAULT_FEE_CVLCPT_MM_CRTR_SEOGWIPO, warnings);
        BigDecimal rtJeju = posOr(c.getFeeCvlcptRtCrtrJeju(), DEFAULT_FEE_CVLCPT_RT_CRTR_JEJU, warnings);
        BigDecimal rtSgp = posOr(c.getFeeCvlcptRtCrtrSeogwipo(), DEFAULT_FEE_CVLCPT_RT_CRTR_SEOGWIPO, warnings);
        BigDecimal wi = nonNegOr(c.getFeeCvlcptIncrsWgvl(), DEFAULT_FEE_CVLCPT_INCRS_WGVL, warnings);
        BigDecimal uplmt = posOr(c.getFeeCvlcptIncrsRtUplmtVl(), DEFAULT_FEE_CVLCPT_INCRS_RT_UPLMT_VL, warnings);

        for (ZoneRcmdtnLotVO l : lots) {
            if (l == null) {
                continue;
            }
            boolean sgp = SGG_SEOGWIPO.equals(l.getSggNm());
            if (!sgp && !SGG_JEJU.equals(l.getSggNm())) {
                // 🔴 시군구를 모르면 제주시 기준으로 계산하되 그 사실을 응답에 남긴다(조용히 섞지 않는다)
                warn(warnings, WARN_COEF_FALLBACK);
            }
            BigDecimal a = sgp ? mmSgp : mmJeju;
            BigDecimal b = sgp ? rtSgp : rtJeju;

            BigDecimal asgn = BigDecimal.valueOf(l.getAsgnCvlcptCnt() != null ? l.getAsgnCvlcptCnt() : 0L);
            BigDecimal mm = asgn.divide(months, CALC_SCALE, RoundingMode.HALF_UP);
            BigDecimal s1 = mm.multiply(HUNDRED).divide(a, CALC_SCALE, RoundingMode.HALF_UP);
            Integer cpcty = l.getPkltCpcty();
            BigDecimal s2;
            if (cpcty == null || cpcty <= 0) {
                s2 = BigDecimal.ZERO;   // 후보 조건(20면 이상)이 이미 거르지만 0 으로 나누지 않는다
            } else {
                BigDecimal rt = mm.multiply(HUNDRED).divide(BigDecimal.valueOf(cpcty), CALC_SCALE, RoundingMode.HALF_UP);
                s2 = rt.multiply(HUNDRED).divide(b, CALC_SCALE, RoundingMode.HALF_UP);
            }
            BigDecimal rvw = w1.multiply(s1).add(w2.multiply(s2)).divide(wSum, CALC_SCALE, RoundingMode.HALF_UP);
            // 🔴 cvlcptIncrsRt 는 % 다 — 정본 incrsCfc 는 비율을 받는다(자리 이동이라 오차 없음)
            BigDecimal g = l.getCvlcptIncrsRt() == null ? null : l.getCvlcptIncrsRt().movePointLeft(2);
            BigDecimal cfc = incrsCfc(g, wi, uplmt);

            l.setCvlcptCntScr(s1);
            l.setCvlcptRtScr(s2);
            l.setRvwScr(rvw);
            BigDecimal prrt = rvw.multiply(cfc);
            l.setIncrsCfc(cfc);
            l.setIncrsRfltRvwScr(prrt);
            l.setRvwGrdCd(rvwGrdCd(prrt));   // 🔴 단계는 순위값(우선도 원값)으로 — 응답 순서대로 비감소(D14)
        }
    }

    /** 우선도(반올림 전 원값) → 단계 코드(D14). 이상 판정 — 100.0 은 TOP. {@code null} 이면 {@code null}. */
    static String rvwGrdCd(BigDecimal prrt) {
        if (prrt == null) {
            return null;
        }
        if (prrt.compareTo(PRRT_TOP) >= 0) {
            return GRD_CD_TOP;
        }
        if (prrt.compareTo(PRRT_HIGH) >= 0) {
            return GRD_CD_HIGH;
        }
        return GRD_CD_LOW;
    }

    /** {@code null}·0 이하면 폴백 + 경고 — 기준값·상한용(나눗셈 분모라 0 이 적법하지 않다). */
    private static BigDecimal posOr(BigDecimal v, BigDecimal dflt, List<String> warnings) {
        if (v == null || v.signum() <= 0) {
            warn(warnings, WARN_COEF_FALLBACK);
            return dflt;
        }
        return v;
    }

    /** {@code null}·음수면 폴백 + 경고 — 가중치용(🔴 0 은 적법하다: "그 지표를 끈다"). */
    private static BigDecimal nonNegOr(BigDecimal v, BigDecimal dflt, List<String> warnings) {
        if (v == null || v.signum() < 0) {
            warn(warnings, WARN_COEF_FALLBACK);
            return dflt;
        }
        return v;
    }

    /** {@link #addOnce} 의 {@code null} 안전판 — {@code warnings} 가 없으면 담지 않는다. */
    private static void warn(List<String> warnings, String w) {
        if (warnings != null) {
            addOnce(warnings, w);
        }
    }

    /**
     * 🆕 TSK-4282 — 표시용 반올림. 점수 4종은 소수 1자리, 증가계수는 3자리(공유주차제와 같다) · HALF_UP.
     * 🔴 정렬·번호·누적·단계 판정을 <b>마친 뒤</b> 부른다 — 반올림한 값으로 비교하면 동점이 생겨 순서가 흔들린다.
     */
    private static void roundScores(List<ZoneRcmdtnLotVO> lots) {
        for (ZoneRcmdtnLotVO l : lots) {
            l.setCvlcptCntScr(scale1(l.getCvlcptCntScr()));
            l.setCvlcptRtScr(scale1(l.getCvlcptRtScr()));
            l.setRvwScr(scale1(l.getRvwScr()));
            l.setIncrsRfltRvwScr(scale1(l.getIncrsRfltRvwScr()));
            l.setIncrsCfc(l.getIncrsCfc() == null ? null : l.getIncrsCfc().setScale(INCRS_CFC_SCALE, RoundingMode.HALF_UP));
        }
    }

    /**
     * 주차장 순위 — 🔁 TSK-4282 <b>§0-2 순번 규칙({@link #RANK_ORDER})으로 정렬한 뒤</b> 번호·누적을 매긴다
     * (종전: 매퍼 정렬 = 배정 민원 내림차순 그대로). 누적도 새 순서로 쌓는다(D10 — "이 순서대로 전환하면").
     * 🔴 <b>효과(감소 건수)는 계산하지 않는다</b> — 주차장 단위 효과 산식이 없다.
     * 🔁 TSK-4340(2026-11-11) — 필요 면수 도달({@code reachNeed}) 판정은 읍면동 판정과 함께 제거됐다.
     */
    private static List<ZoneRcmdtnLotVO> rank(List<ZoneRcmdtnLotVO> lots) {
        if (lots == null) {
            return new ArrayList<>();
        }
        lots.sort(RANK_ORDER);   // 🔴 안정 정렬 — 점수가 전부 null 이면 매퍼 순서(2차 키)와 같다
        BigDecimal cum = BigDecimal.ZERO;
        int rnk = 0;
        for (ZoneRcmdtnLotVO l : lots) {
            // 🔴 rnk 는 매퍼가 아니라 여기서 채운다 — 매퍼 ORDER BY 는 순서만 정하고 번호 칸은
            //    비어 있다. 빠뜨리면 응답 lots[].rnk 와 화면 '순위' 열이 전부 null 이다
            //    (컴파일은 통과한다).
            l.setRnk(++rnk);
            // 🔴 pklt_cpcty 는 integer → VO getter 가 Integer 다. BigDecimal.add(Integer) 는
            //    컴파일되지 않는다.
            cum = cum.add(BigDecimal.valueOf(l.getPkltCpcty()));
            l.setCumCpcty(cum);
            // 🔴 cumCpcty 는 "이 순위대로 담으면 몇 면이 되나" 를 보여줄 뿐 자르지 않는다 —
            //    담당자가 예산·현장 사정으로 몇 개소까지 할지 정한다.
        }
        return lots;
    }

    /**
     * 🔁 TSK-4259 — 시군구 모드 순위: {@code rnk} 만 1..N 으로 매긴다.
     *
     * <p>🔴 {@link #rank} 를 쓰지 않는다 — 그쪽은 누적 면수를 무조건 채운다. 시군구 모드는(D4)
     * {@code cumCpcty = null} 로 둔다.
     * 🔁 TSK-4282 — 번호를 매기기 전에 {@link #rank} 와 같은 {@link #RANK_ORDER} 로 정렬한다.
     */
    private static List<ZoneRcmdtnLotVO> rankOnly(List<ZoneRcmdtnLotVO> lots) {
        if (lots == null) {
            return new ArrayList<>();
        }
        lots.sort(RANK_ORDER);   // 🔴 읍면동 모드와 같은 순번 규칙
        int rnk = 0;
        for (ZoneRcmdtnLotVO l : lots) {
            l.setRnk(++rnk);
            l.setCumCpcty(null);
        }
        return lots;
    }

    /**
     * 배정 커버리지 — 🔴 "순위가 민원의 몇 %를 설명하는가".
     *
     * <p>총건수는 매퍼가 낸 <b>원 건수</b>({@code cvlcptCnt}) 그대로다 — 이도이동 정본 창이면 {@code 3110}.
     * 🔁 TSK-4284 · D3 — 종전 {@code 민원 월평균 × 개월수} 되곱은 버렸다. 개월 수가 소수(예: {@code 1.005376})가
     * 되면 되곱 반올림이 원 건수와 1건씩 어긋날 수 있다.
     *
     * <p>{@code cvlcptCnt == null} 은 0 으로 본다 — 매퍼는 {@code COALESCE} 라 null 이 오지 않지만 방어선이다.
     */
    private static ZoneRcmdtnResultVO.Asgn asgn(Long cvlcptCnt, List<ZoneRcmdtnLotVO> lots) {
        long tot = cvlcptCnt != null ? cvlcptCnt : 0L;
        long asgn = 0L;
        for (ZoneRcmdtnLotVO l : lots) {
            if (l.getAsgnCvlcptCnt() != null) {
                asgn += l.getAsgnCvlcptCnt();
            }
        }
        ZoneRcmdtnResultVO.Asgn a = new ZoneRcmdtnResultVO.Asgn();
        a.setCvlcptTot(tot);
        a.setCvlcptAsgn(asgn);
        // 민원 0건인 동은 배정률이 성립하지 않는다 — 0 이 아니라 null 이다(0% 와 "분모 없음" 은 다르다)
        a.setAsgnRt(tot == 0 ? null
                : scale1(BigDecimal.valueOf(asgn)
                        .divide(BigDecimal.valueOf(tot), CALC_SCALE, RoundingMode.HALF_UP)
                        .multiply(HUNDRED)));
        return a;
    }

    /** 화면 표시용 소수 1자리. 계산은 {@link #CALC_SCALE} 로 하고 표시만 줄인다. */
    private static BigDecimal scale1(BigDecimal v) {
        return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
    }
}
