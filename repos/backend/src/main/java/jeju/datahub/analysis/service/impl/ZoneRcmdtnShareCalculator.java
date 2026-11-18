package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.PrdMonths;
import jeju.datahub.analysis.service.ZoneRcmdtnShareBaseVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareWorkbookFixedVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareWorkbookRowVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareWorkbookVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareZoneVO;

/**
 * 구역추천(공유주차제 적용) 판정 계산기.
 *
 * <p><b>순수 계산이다</b> — Spring 빈이 아니고 DB·시계·로그에 기대지 않는다. 입력(읍면동별 건수·면수,
 * 개월 수, 기준값)이 같으면 결과가 같다. 그래서 경계 판정을 DB 없이 단위테스트로 고정할 수 있다
 * ({@code ZoneRcmdtnShareCalculatorTest}). 매퍼 호출·기간 파싱·400 처리는
 * {@link ZoneRcmdtnShareServiceImpl} 이 한다.
 *
 * <h2>판정 규칙</h2>
 * <pre>
 * C0 = 주차 민원 건수 ÷ 조회 개월 수            월평균 민원 (요금제 트랙과 같은 정의)
 *      개월 수 = PrdMonths — 달별 조회 비율 합. 온전한 달이면 정수, 아니면 소수
 * S  = 공영 주차면수 합                          소멸분 제외 · 유형 전부 포함
 * R  = C0 ÷ S × 100                              공영 100면당 월평균 민원
 * 후보 = C0 ≥ 민원 월기준값(50)  그리고  R ≥ 민원 비율기준값(7.0)
 * 핵심 = 후보 그리고 C0 ≥ 2 × 민원 월기준값  그리고  R ≥ 2 × 민원 비율기준값
 * </pre>
 *
 * <p>🔴 <b>경계는 전부 "이상(≥)" 이고 {@link BigDecimal#compareTo} 로 비교한다.</b> 분석에서 외도일동이
 * 기준값과 소수점까지 같은 값으로 걸린 전례가 있다. 부동소수 타입은 이 클래스 어디에도 쓰지 않는다.
 *
 * <p>🔴 <b>판정·순번·주의 표시는 전부 {@link #SCALE} 자리 값으로 하고, 반올림은 응답에 실을 때 한 번만
 * 한다</b>({@link RoundingMode#HALF_UP} — 🔴 검토 지수만 예외로 <b>버림</b>이다. 아래 참고). 반올림한
 * 값으로 판정하면 경계 값이 뒤집힌다.
 *
 * <h2>검토 지수 (2026-10-13 사용자 결정)</h2>
 * <pre>
 * 검토 지수 = min(C0 ÷ 민원 월기준값, R ÷ 민원 비율기준값)     기준을 몇 배 넘었는가
 * 1 이상 = 후보 · 2 이상 = 핵심 후보                          위 판정 규칙과 같은 뜻이다
 * </pre>
 * 🔴 <b>지수는 판정의 입력이 아니다</b> — 등급은 여전히 {@link #grade} 의 {@code compareTo} 가 정하고,
 * 지수는 같은 사실을 값 하나로 다시 적은 것이다. 두 표현이 어긋나지 않게 <b>나눗셈도 출력도 버림
 * ({@link RoundingMode#DOWN})</b>으로 한다({@link #rvwScr} · {@link #RVW_SCR_SCALE}).
 * 🔴 <b>지수는 "기준을 넘은 정도" 이지 공유주차 적합도·효과·사업 우선순위가 아니다</b> — 공유할
 * 부설주차장이 있는지, 어느 시간대에 부족한지는 이 값에 들어 있지 않다.
 *
 * <p>🔴 <b>핵심 배수({@link #CORE_MLTP})와 경계 폭({@link #BNDRY_RT})은 설정 변수가 아니라 코드 상수다.</b>
 * 데이터표준사전에 없는 개념이고, 설정으로 열면 "원하는 숫자를 만드는 칸" 이 된다(다른 상수와
 * 같은 판단).
 *
 * <p>🔴 <b>등급 라벨(한글)은 여기 없다</b> — 공통코드 {@code SHRN_RVW_GRD_CD} 가 출처다. 이 클래스는
 * 코드값만 낸다.
 *
 * <h2>추세 반영 지수 · 순번 (B안)</h2>
 * <pre>
 * 증가율 g        = (최근 블록 민원 − 이전 블록 민원) ÷ 이전 블록 민원      SCALE 자리 정밀 비율
 *                   이전 블록 &lt; MIN_TREND_BASE(10) 이면 null
 * 증가계수        = 1 + 가중치 × clamp(g, −상한/100, +상한/100)           g 가 null 이면 1(추세 중립)
 * 추세 반영 지수  = 검토 지수 × 증가계수                                  검토 지수가 null 이면 null
 * 순번            = 추세 반영 지수(우선도) 내림차순 → 민원 건수 내림차순 → emd_cd    후보 먼저 키 삭제
 * </pre>
 * 블록은 1차 <b>직전 동일 기간</b>(최근 = 조회 기간 D일 = {@code cvlcptCnt}, 이전 = 그 앞 D일)이고, 이전 블록이
 * <b>전 읍면동 합 0건</b>이면 반 분할(최근 = 뒤 D/2 · 이전 = 앞 D/2)로 떨어진다 — 🔴 판정은 <b>도 전체 1회</b>다
 * (요금제 {@link ZoneRcmdtnCalculator} 와 같은 규칙 · 같은 상수를 참조한다).
 * 🔴 <b>등급·후보·핵심 판정과 검토 지수는 그대로다</b> — 바뀌는 것은 <b>순서</b>뿐이고, 그래서 후보 안에서
 * 핵심({@code CORE})이 후보({@code CAND}) 뒤에 설 수 있다. 🔴 증가계수는 반올림 전 정밀 g 로 계산한다 —
 * 요금제 {@code ZoneRcmdtnCalculator.incrsRt}(1자리 반올림 %)를 입력으로 쓰지 않는다.
 */
public final class ZoneRcmdtnShareCalculator {

    /** 핵심 후보 배수. 🔴 설정 변수가 아니라 코드 상수다. */
    static final BigDecimal CORE_MLTP = new BigDecimal("2");
    /** 기준 경계 폭: 기준값의 +5% 미만 여유면 표시한다. */
    static final BigDecimal BNDRY_RT = new BigDecimal("1.05");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** 판정용 나눗셈 자릿수 ({@code HotspotWeightResolver.COEF_SCALE} 선례). */
    static final int SCALE = 6;
    /**
     * 검토 지수의 응답 자릿수. 🔴 <b>이 자리로 줄일 때는 반올림이 아니라 버림이다</b>
     * ({@link RoundingMode#DOWN}) — 올림이 섞이면 {@code 0.996} 이 {@code 1.00} 으로 보여 "지수 1.00 인데
     * 후보가 아님" 이 생긴다. 버림이면 표시값 ≥ 1.00 ⇔ 후보, ≥ 2.00 ⇔ 핵심 후보가 항상 성립한다.
     */
    static final int RVW_SCR_SCALE = 2;

    /** 민원 월기준값 변수코드 — 🔴 요금제 트랙과 <b>같은 행</b>을 읽는다(마이그레이션 84). */
    static final String VRBL_CVLCPT_MM_CRTR = "CVLCPT_MM_CRTR_VL";
    /** 민원 비율기준값 변수코드 (마이그레이션 85) */
    static final String VRBL_CVLCPT_RT_CRTR = "CVLCPT_RT_CRTR_VL";
    /** 민원증가가중치 변수코드 (마이그레이션 93) */
    static final String VRBL_CVLCPT_INCRS_WGVL = "CVLCPT_INCRS_WGVL";
    /** 민원증가율상한값 변수코드(%) (마이그레이션 93) */
    static final String VRBL_CVLCPT_INCRS_RT_UPLMT = "CVLCPT_INCRS_RT_UPLMT_VL";

    /**
     * 설정 행을 쓸 수 없을 때의 민원 월기준값.
     *
     * <p>🔴 {@code 84-RECOMMEND-VRBL-STNG.sql} 의 {@code CVLCPT_MM_CRTR_VL} 행 {@code bsc_stng_vl} 과
     * <b>같은 숫자</b>여야 한다 — 어긋나면 "행이 없을 때" 와 "행이 있을 때" 의 판정이 달라진다.
     */
    static final BigDecimal DEFAULT_CVLCPT_MM_CRTR = new BigDecimal("50");
    /**
     * 설정 행을 쓸 수 없을 때의 민원 비율기준값.
     *
     * <p>🔴 {@code 85-RECOMMEND-SHARE-VRBL-STNG.sql} 의 {@code CVLCPT_RT_CRTR_VL} 행 {@code bsc_stng_vl}
     * 과 <b>같은 숫자</b>여야 한다.
     */
    static final BigDecimal DEFAULT_CVLCPT_RT_CRTR = new BigDecimal("7.0");
    /**
     * 설정 행을 쓸 수 없을 때의 민원증가가중치. 🔴 {@code 93-RECOMMEND-SHARE-INCRS-VRBL.sql} 의
     * {@code CVLCPT_INCRS_WGVL} 행 {@code bsc_stng_vl} 과 <b>같은 숫자</b>여야 한다.
     */
    static final BigDecimal DEFAULT_CVLCPT_INCRS_WGVL = new BigDecimal("0.5");
    /**
     * 설정 행을 쓸 수 없을 때의 민원증가율상한값(%). 🔴 {@code 93-RECOMMEND-SHARE-INCRS-VRBL.sql} 의
     * {@code CVLCPT_INCRS_RT_UPLMT_VL} 행 {@code bsc_stng_vl} 과 <b>같은 숫자</b>여야 한다.
     */
    static final BigDecimal DEFAULT_CVLCPT_INCRS_RT_UPLMT = new BigDecimal("100");
    /** 증가율(%) 응답 자릿수 — HALF_UP (요금제 {@code cvlcptIncrsRt} 와 같다) */
    static final int INCRS_RT_SCALE = 1;
    /** 증가계수 응답 자릿수 — HALF_UP */
    static final int INCRS_CFC_SCALE = 3;

    /** 사용여부 컬럼({@code use_yn})의 '사용' 값. CHAR(1) 문자열이다. */
    private static final String USE_Y = "Y";

    /** 등급 — 후보이면서 두 기준의 2배 이상 */
    public static final String GRD_CORE = "CORE";
    /** 등급 — 두 기준 모두 이상 */
    public static final String GRD_CAND = "CAND";
    /** 등급 — 민원 기준만 충족 */
    public static final String GRD_CVLCPT_ONLY = "CVLCPT_ONLY";
    /** 등급 — 면수 0(등록된 공영주차장이 없거나 면수를 전부 모른다) */
    public static final String GRD_UNABLE = "UNABLE";
    /** 등급 — 그 밖 */
    public static final String GRD_NONE = "NONE";

    /** 주의 표시 — 민원 최다 좌표 1곳을 빼면 후보 조건에 못 미친다 */
    public static final String CUTN_TOP_PNT = "TOP_PNT";
    /** 주의 표시 — 적용 기준의 +5% 미만 여유 */
    public static final String CUTN_CRTR_BNDRY = "CRTR_BNDRY";

    /** 경고 — 설정 행(기준값 · 증가 가중치 · 증가율 상한)을 쓸 수 없어 코드 폴백 상수로 판정했다 */
    public static final String WARN_CRTR_FALLBACK = "CRTR_FALLBACK";
    /** 경고 — 조회 기간에 민원이 한 건도 없다 */
    public static final String WARN_NO_CVLCPT_IN_RANGE = "NO_CVLCPT_IN_RANGE";
    /**
     * 경고 — 두 기준값이 모두 0 이라 검토 지수를 낼 수 없다(나눌 기준이 없다).
     * 그때는 전 읍면동의 {@code rvwScr}·{@code rnk} 가 {@code null} 이다. 등급 판정은 그대로 한다
     * (기준 0 은 "항상 충족" 이라 면수가 있는 읍면동은 전부 {@code CORE} 가 된다).
     */
    public static final String WARN_CRTR_ALL_ZERO = "CRTR_ALL_ZERO";

    private static final String YN_Y = "Y";
    private static final String YN_N = "N";

    private ZoneRcmdtnShareCalculator() {
    }

    /**
     * 판정에 쓰는 기준값 한 벌 — <b>설정 원값</b>이다(꼬리 0 을 걷지 않았다).
     *
     * <p>응답·{@code anls_param_json} 에 싣는 다듬은 값은 {@link #calculate} 가 결과 VO 의
     * {@code crtrVls} 에 채운다. 두 값은 수치로 같다.
     *
     * @param cvlcptMmCrtrVl 민원 월기준값(건/월)
     * @param cvlcptRtCrtrVl 민원 비율기준값(건/100면·월)
     * @param incrsWgvl      민원증가가중치(0 이면 추세 미반영 — 계수가 항상 1)
     * @param incrsRtUplmtVl 민원증가율상한값(%) — 증가율을 ±이 값/100 으로 자른다
     */
    public record Crtr(BigDecimal cvlcptMmCrtrVl, BigDecimal cvlcptRtCrtrVl,
                       BigDecimal incrsWgvl, BigDecimal incrsRtUplmtVl) {
    }

    // =====================================================================================
    // 기준값 조달
    // =====================================================================================

    /**
     * {@code RECOMMEND} 분석변수 목록에서 기준값 2종과 증가 가중치·상한을 고른다. 3단 폴백이며 예외를 던지지 않는다.
     * <ol>
     *   <li>{@code use_yn='Y'} 이고 {@code stng_vl} 이 있으면 그 값</li>
     *   <li>아니면 {@code bsc_stng_vl}(최초 등재값)</li>
     *   <li>그것도 없거나 행 자체가 없으면 코드 상수(50 · 7.0 · 가중치 0.5 · 상한 100) + {@code warnings} 에
     *       {@link #WARN_CRTR_FALLBACK}</li>
     * </ol>
     *
     * <p>🔴 설정 한 행이 빠졌다고 분석 화면이 500 이 되면 안 된다 — 대신 경고 코드로 드러낸다.
     * 음수는 값이 없는 것으로 보고 상수로 떨어뜨린다(DB CHECK 밖의 직접 UPDATE 방어).
     *
     * <p>매퍼를 부르지 않는 이유 — 목록을 받는 순수 함수여야 DB 없이 단위테스트로 고정된다.
     * 조회는 {@link ZoneRcmdtnShareServiceImpl} 이 한다.
     *
     * @param rows     {@code AnalysisWeightMapper.selectWeightsByType("RECOMMEND")} 결과 ({@code null} 가능)
     * @param warnings 상수 폴백이 일어나면 {@link #WARN_CRTR_FALLBACK} 을 <b>한 번만</b> 담는다
     */
    public static Crtr resolveCrtr(List<AnalysisWeightVO> rows, List<String> warnings) {
        return new Crtr(
                resolveOne(rows, VRBL_CVLCPT_MM_CRTR, DEFAULT_CVLCPT_MM_CRTR, warnings),
                resolveOne(rows, VRBL_CVLCPT_RT_CRTR, DEFAULT_CVLCPT_RT_CRTR, warnings),
                resolveOne(rows, VRBL_CVLCPT_INCRS_WGVL, DEFAULT_CVLCPT_INCRS_WGVL, warnings),
                resolveOne(rows, VRBL_CVLCPT_INCRS_RT_UPLMT, DEFAULT_CVLCPT_INCRS_RT_UPLMT, warnings));
    }

    private static BigDecimal resolveOne(List<AnalysisWeightVO> rows, String vrblCd,
                                         BigDecimal dflt, List<String> warnings) {
        BigDecimal value = null;
        if (rows != null) {
            for (AnalysisWeightVO row : rows) {
                if (row == null || !vrblCd.equals(row.getVrblCd())) {
                    continue;
                }
                value = USE_Y.equals(row.getUseYn()) ? row.getStngVl() : null;   // ① 설정
                if (value == null) {
                    value = row.getBscStngVl();                                   // ② 최초 등재값
                }
                break;
            }
        }
        if (value == null || value.signum() < 0) {                               // ③ 코드 상수
            addOnce(warnings, WARN_CRTR_FALLBACK);
            return dflt;
        }
        return value;
    }

    // =====================================================================================
    // 판정 · 주의 표시 · 순번 · 요약
    // =====================================================================================

    /**
     * 지역 필터 없이 읍면동 전량을 판정한다 — {@link #calculate(List, BigDecimal, Crtr, List, String, String)} 에
     * 필터 둘을 {@code null} 로 넘긴 것과 같다.
     */
    public static ZoneRcmdtnShareResultVO calculate(List<ZoneRcmdtnShareBaseVO> rows, BigDecimal months,
                                                    Crtr crtr, List<String> warnings) {
        return calculate(rows, months, crtr, warnings, null, null);
    }

    /**
     * 추세 창 없이 판정한다 — {@link #calculate(List, BigDecimal, Crtr, List, String, String, Integer, Integer)}
     * 에 추세 일수 둘을 {@code null} 로 넘긴 것과 같다(폴백 판정 생략 · 증가율 {@code null} · 계수 1 —
     * 순번은 후보 먼저 → 검토 지수 내림차순과 같아진다).
     */
    public static ZoneRcmdtnShareResultVO calculate(List<ZoneRcmdtnShareBaseVO> rows, BigDecimal months,
                                                    Crtr crtr, List<String> warnings,
                                                    String sggNm, String emdCd) {
        return calculate(rows, months, crtr, warnings, sggNm, emdCd, null, null);
    }

    /**
     * 읍면동 전량을 판정해 결과 한 벌(기준값·요약·정렬된 목록)로 돌려준다.
     *
     * <p>🔴 <b>반드시 읍면동 전량을 넣는다</b> — 요약({@code smry})과 경고는 지역 필터와 무관한 도 전체
     * 값이다. 지역으로 거른 목록을 넣으면 그 값이 줄어든다. 지역 조건은 {@code sggNm}·{@code emdCd}
     * 인자로 준다.
     *
     * <p>순서는 <b>판정(전량) → 요약(전량) → 지역 필터 → 순번 → 정렬</b>이다.
     * <ul>
     *   <li>판정(등급·검토 지수·주의 표시·공한지 제외값)은 읍면동마다 고정 기준값과 견주는 것이라
     *       필터와 무관하게 같은 값이다.</li>
     *   <li>🔴 <b>순번({@code rnk})만 필터 뒤에 매긴다</b> — 응답에 실리는 읍면동 안에서 1..n 이다
     *       ({@link #assignRnk}). 필터가 없으면 도 전체에서 매긴 것과 같다.</li>
     * </ul>
     *
     * <p>{@code excnId}·자료 구간은 채우지 않는다(이 클래스가 모르는 값이다).
     *
     * @param rows     매퍼 {@code selectShareBase} 결과 (읍면동 1개 = 1건)
     * @param months   월평균의 분모 — {@link PrdMonths} — 달별 조회 비율 합. 소수 허용.
     *                 🔴 {@code null}·0 이하면 {@link IllegalArgumentException} — 1 로 올리지 않는다
     *                 (1일 조회 = {@code 0.032258} 이 정상이다. 0 이하는 호출부 버그다)
     * @param crtr     적용 기준값({@link #resolveCrtr})
     * @param warnings 기준값 조달 단계의 경고(+ 서비스의 {@code TREND_WINDOW_SHORT}). 여기에 {@link #WARN_NO_CVLCPT_IN_RANGE}·
     *                 {@code TREND_BFR_FALLBACK} 을 덧붙여 싣는다
     * @param sggNm    시군구명 필터. {@code null} 이면 걸지 않는다
     * @param emdCd    읍면동 코드 필터. {@code null} 이면 걸지 않는다. 둘 다 주면 둘 다 만족하는 읍면동만 남는다
     * @param trendDayCnt 1차 추세 블록 일수 D(조회 일수). 🔴 기간 하한 미달(D/2 &lt; {@code MIN_TREND_DAYS})이면
     *                    {@code null} — 그때는 폴백 판정을 하지 않고(세 칸이 모두 0 이라 폴백이 참이 되는 것을
     *                    막는다) 전 행 증가율 {@code null} · 계수 1 이다. {@code TREND_WINDOW_SHORT} 는 서비스가
     *                    {@code warnings} 에 담아 넘긴다
     * @param splitDayCnt 폴백 블록 일수 D/2 — 🔴 서비스가 매퍼 경계를 만든 <b>그 값</b>을 넘긴다(여기서 다시
     *                    나누지 않는다). 미달이면 {@code null}
     */
    public static ZoneRcmdtnShareResultVO calculate(List<ZoneRcmdtnShareBaseVO> rows, BigDecimal months,
                                                    Crtr crtr, List<String> warnings,
                                                    String sggNm, String emdCd,
                                                    Integer trendDayCnt, Integer splitDayCnt) {
        return calculate(rows, months, crtr, warnings, sggNm, emdCd, trendDayCnt, splitDayCnt, null, null);
    }

    /**
     * 자료 시작일 가드를 켠 판정. 나머지는
     * {@link #calculate(List, BigDecimal, Crtr, List, String, String, Integer, Integer)} 와 같다.
     *
     * @param trendFrom  1차 이전 블록 시작(요금제 {@code bfrFrom}) — 서비스가 매퍼 경계로 쓴 <b>그 값</b>.
     *                   {@code null} 이면 가드를 끈다
     * @param dataBgngDt 민원 자료 시작일 00:00({@code selectDataRange} 의 {@code min(ocrn_dt)}). {@code null}
     *                   (자료 없음)이면 가드를 끈다 — 종전 동작
     */
    public static ZoneRcmdtnShareResultVO calculate(List<ZoneRcmdtnShareBaseVO> rows, BigDecimal months,
                                                    Crtr crtr, List<String> warnings,
                                                    String sggNm, String emdCd,
                                                    Integer trendDayCnt, Integer splitDayCnt,
                                                    LocalDateTime trendFrom, LocalDateTime dataBgngDt) {
        return calculate(rows, months, crtr, warnings, sggNm, emdCd, trendDayCnt, splitDayCnt,
                trendFrom, dataBgngDt, false);
    }

    /**
     * {@link #calculate(List, BigDecimal, Crtr, List, String, String, Integer, Integer,
     * LocalDateTime, LocalDateTime)} 와 같은 판정에 엑셀 재계산 데이터 {@code wbk}를 붙인다. 보고서 경로 전용.
     *
     * <p>{@code rows}·{@code months}·{@code trendMode}·{@code fixed} 를 채운다. 🔴 {@code vrbls} 는 채우지 않는다 —
     * 계수 설정 행({@code AnlsVrblOvrd.row})을 아는 서비스가 붙인다. {@code Judged}·{@code Trend}·정렬이 이 클래스
     * private 이라 행 조립은 여기서 한다(응답 {@code zones} 에는 최다 지점 건수·공한지 제외 면수·확정 블록 건수가 없다).
     */
    public static ZoneRcmdtnShareResultVO calculateWithWorkbook(List<ZoneRcmdtnShareBaseVO> rows, BigDecimal months,
                                                                Crtr crtr, List<String> warnings,
                                                                String sggNm, String emdCd,
                                                                Integer trendDayCnt, Integer splitDayCnt,
                                                                LocalDateTime trendFrom, LocalDateTime dataBgngDt) {
        return calculate(rows, months, crtr, warnings, sggNm, emdCd, trendDayCnt, splitDayCnt,
                trendFrom, dataBgngDt, true);
    }

    private static ZoneRcmdtnShareResultVO calculate(List<ZoneRcmdtnShareBaseVO> rows, BigDecimal months,
                                                     Crtr crtr, List<String> warnings,
                                                     String sggNm, String emdCd,
                                                     Integer trendDayCnt, Integer splitDayCnt,
                                                     LocalDateTime trendFrom, LocalDateTime dataBgngDt,
                                                     boolean withWbk) {
        // 🔴 PrdMonths 는 유효 기간에 0 이하를 내지 않는다 — 들어오면 호출부 버그라 끊는다(종전 1 하한 폐기)
        if (months == null || months.signum() <= 0) {
            throw new IllegalArgumentException("개월 수는 0 보다 커야 합니다: " + months);
        }

        // 🔴 추세 블록 폴백 판정 — 판정 루프 앞에서 도 전체 1회(읍면동마다 하지 않는다)
        Trend trend = trendMode(rows, trendDayCnt, trendFrom, dataBgngDt);

        List<Judged> all = new ArrayList<>();
        if (rows != null) {
            for (ZoneRcmdtnShareBaseVO row : rows) {
                if (row != null) {
                    all.add(judge(row, months, crtr, trend));
                }
            }
        }
        // 🔴 요약은 필터 앞(도 전체), 순번은 필터 뒤(응답에 실리는 행)다
        ZoneRcmdtnShareResultVO.Smry smry = summarize(all);
        List<Judged> shown = filter(all, sggNm, emdCd);
        assignRnk(shown);
        shown.sort(DISPLAY_ORDER);

        ZoneRcmdtnShareResultVO result = new ZoneRcmdtnShareResultVO();
        if (withWbk) {
            result.setWbk(workbook(shown, months, trend));
        }

        ZoneRcmdtnShareResultVO.CrtrVls crtrVls = new ZoneRcmdtnShareResultVO.CrtrVls();
        crtrVls.setCvlcptMmCrtrVl(plain(crtr.cvlcptMmCrtrVl()));
        crtrVls.setCvlcptRtCrtrVl(plain(crtr.cvlcptRtCrtrVl()));
        crtrVls.setCoreMltp(CORE_MLTP);
        crtrVls.setMonths(months);
        crtrVls.setIncrsWgvl(plain(crtr.incrsWgvl()));
        crtrVls.setIncrsRtUplmtVl(plain(crtr.incrsRtUplmtVl()));
        crtrVls.setMinTrendBase(ZoneRcmdtnCalculator.MIN_TREND_BASE);
        crtrVls.setTrendDayCnt(switch (trend) {
            case PRIMARY -> trendDayCnt;
            case FALLBACK -> splitDayCnt;
            case NONE -> null;
        });
        result.setCrtrVls(crtrVls);

        List<ZoneRcmdtnShareZoneVO> zones = new ArrayList<>(shown.size());
        for (Judged j : shown) {
            zones.add(toZone(j));
        }
        result.setZones(zones);
        result.setSmry(smry);

        List<String> out = new ArrayList<>();
        if (warnings != null) {
            for (String w : warnings) {
                addOnce(out, w);
            }
        }
        if (crtr.cvlcptMmCrtrVl().signum() <= 0 && crtr.cvlcptRtCrtrVl().signum() <= 0) {
            addOnce(out, WARN_CRTR_ALL_ZERO);
        }
        if (smry.getCvlcptTot() == 0L) {
            addOnce(out, WARN_NO_CVLCPT_IN_RANGE);
        }
        if (trend == Trend.FALLBACK) {
            addOnce(out, ZoneRcmdtnCalculator.WARN_TREND_BFR_FALLBACK);
        }
        result.setWarnings(out);
        return result;
    }

    /**
     * 지역 필터. 🔴 <b>판정·요약 뒤, 순번 앞</b>에 건다 — 순번은 이 결과 안에서 매긴다.
     *
     * <p>두 조건을 함께 주면 둘 다 만족하는 읍면동만 남는다. 둘 다 {@code null} 이면 전량이다.
     * 🔴 밖으로 열지 않는다 — 이미 번호가 매겨진 목록을 다시 거르면 번호가 이어지지 않는다.
     */
    private static List<Judged> filter(List<Judged> all, String sggNm, String emdCd) {
        List<Judged> out = new ArrayList<>(all.size());
        for (Judged j : all) {
            if (sggNm != null && !sggNm.equals(j.row.getSggNm())) {
                continue;
            }
            if (emdCd != null && !emdCd.equals(j.row.getEmdCd())) {
                continue;
            }
            out.add(j);
        }
        return out;
    }

    /**
     * 추세 블록 방식 — 🔴 <b>도 전체 1회</b> 판정한다(요금제 {@code trendWindowOk && bfrFallbackApplied}
     * 와 같은 처리).
     * <ul>
     *   <li>D 가 {@code null}(기간 하한 미달) → {@link Trend#NONE}. 폴백 판정 자체를 하지 않는다</li>
     *   <li>1차 이전 블록의 전 읍면동 합이 0 → {@link Trend#FALLBACK}(반 분할)</li>
     *   <li>1차 이전 블록 시작({@code trendFrom})이 자료 시작일보다 앞 →
     *       {@link Trend#FALLBACK}. 자료 공백이 분모에 섞여 증가율이 부풀고(실측 +1956% · 상한 1.5 쏠림)
     *       순위가 왜곡되기 때문이다. 판정은 요금제와 같은 {@link ZoneRcmdtnCalculator#bfrBeforeData}</li>
     *   <li>그 밖 → {@link Trend#PRIMARY}(직전 동일 기간)</li>
     * </ul>
     */
    private static Trend trendMode(List<ZoneRcmdtnShareBaseVO> rows, Integer trendDayCnt,
                                   LocalDateTime trendFrom, LocalDateTime dataBgngDt) {
        if (trendDayCnt == null) {
            return Trend.NONE;
        }
        if (ZoneRcmdtnCalculator.bfrBeforeData(trendFrom, dataBgngDt)) {
            return Trend.FALLBACK;
        }
        BigDecimal bfrSum = BigDecimal.ZERO;
        if (rows != null) {
            for (ZoneRcmdtnShareBaseVO row : rows) {
                if (row != null) {
                    bfrSum = bfrSum.add(nz(row.getBfrCvlcptCnt()));
                }
            }
        }
        return bfrSum.signum() == 0 ? Trend.FALLBACK : Trend.PRIMARY;
    }

    /** 읍면동 1곳의 판정 — 등급·주의 표시·공한지 제외값·추세까지. 순번은 {@link #assignRnk} 가 채운다. */
    private static Judged judge(ZoneRcmdtnShareBaseVO row, BigDecimal months, Crtr crtr, Trend trend) {
        Judged j = new Judged();
        j.row = row;
        j.cvlcptCnt = nz(row.getCvlcptCnt());
        j.cpcty = nz(row.getPkltCpcty());
        BigDecimal topCnt = nz(row.getTopPntCvlcptCnt());

        j.mm = j.cvlcptCnt.divide(months, SCALE, RoundingMode.HALF_UP);        // 월평균 민원
        j.rt = rt(j.mm, j.cpcty);                                               // 100면당 월평균
        j.grd = grade(j.mm, j.rt, crtr);
        j.cand = isCand(j.grd);
        j.scr = rvwScr(j.mm, j.rt, crtr);                                       // 검토 지수(판정의 입력이 아니다)

        // 추세 — 🔴 면수와 무관하게 건수로 계산한다(판정 불가 행도 증가율·계수는 있다. 추세 반영 지수만 null)
        Block block = blockOf(row, trend);
        j.rcntCnt = block.rcnt();
        j.bfrCnt = block.bfr();
        j.incrsRatio = incrsRatio(j.rcntCnt, j.bfrCnt);       // 정밀 비율 g (SCALE 자리) · 분모 하한 미달이면 null
        j.incrsCfc = incrsCfc(j.incrsRatio, crtr);         // null 이면 1 · 반올림 안 한 g 로 계산
        j.rfltScr = j.scr == null ? null : j.scr.multiply(j.incrsCfc);

        // 민원 최다 좌표 1곳의 비중 — 민원 0건이면 비중을 말할 수 없다(0 이 아니라 null)
        j.topPntRt = j.cvlcptCnt.signum() == 0 ? null
                : topCnt.multiply(HUNDRED).divide(j.cvlcptCnt, SCALE, RoundingMode.HALF_UP);

        if (j.cand) {
            // TOP_PNT — 최다 지점 1곳을 뺀 건수로 '같은 판정' 을 다시 했을 때 후보가 아니다
            BigDecimal mmExcl = j.cvlcptCnt.subtract(topCnt).divide(months, SCALE, RoundingMode.HALF_UP);
            if (!isCand(grade(mmExcl, rt(mmExcl, j.cpcty), crtr))) {
                j.cutnCds.add(CUTN_TOP_PNT);
            }
            // CRTR_BNDRY — 적용 기준의 +5% 미만 여유. 핵심이면 핵심 기준(2배)도 본다
            boolean bndry = nearBndry(j.mm, j.rt, crtr.cvlcptMmCrtrVl(), crtr.cvlcptRtCrtrVl());
            if (!bndry && GRD_CORE.equals(j.grd)) {
                bndry = nearBndry(j.mm, j.rt,
                        crtr.cvlcptMmCrtrVl().multiply(CORE_MLTP),
                        crtr.cvlcptRtCrtrVl().multiply(CORE_MLTP));
            }
            if (bndry) {
                j.cutnCds.add(CUTN_CRTR_BNDRY);
            }
        }

        // 공한지 제외 — 면수만 바꿔 같은 판정을 한다
        j.vaclotExclRt = rt(j.mm, nz(row.getVaclotExclPkltCpcty()));
        j.vaclotExclCand = isCand(grade(j.mm, j.vaclotExclRt, crtr));
        return j;
    }

    /**
     * 추세 블록 선택 — 🔴 판정({@link #judge})과 엑셀 {@code wbk.rows}가 함께 부르는 <b>한 곳</b>이다.
     * 규칙을 엑셀용으로 다시 적지 않는다.
     * <ul>
     *   <li>{@link Trend#PRIMARY} — 최근 = 조회 기간 그대로 · 이전 = 직전 동일 기간</li>
     *   <li>{@link Trend#FALLBACK} — 최근 = 반 분할 뒤 · 이전 = 반 분할 앞</li>
     *   <li>{@link Trend#NONE} — 기간 하한 미달이라 계산하지 않는다(둘 다 {@code null})</li>
     * </ul>
     */
    private static Block blockOf(ZoneRcmdtnShareBaseVO row, Trend trend) {
        return switch (trend) {
            case PRIMARY -> new Block(nz(row.getCvlcptCnt()), nz(row.getBfrCvlcptCnt()));
            case FALLBACK -> new Block(nz(row.getHalfAftrCvlcptCnt()), nz(row.getHalfBfrCvlcptCnt()));
            case NONE -> new Block(null, null);
        };
    }

    /** 증가율 블록 건수 쌍 — 최근·이전. {@link Trend#NONE} 이면 둘 다 {@code null} */
    private record Block(BigDecimal rcnt, BigDecimal bfr) {
    }

    /**
     * 엑셀 재계산 데이터 — 🔴 <b>응답 {@code zones} 와 같은 읍면동</b>({@code shown} — 필터 뒤)을
     * {@link #BY_CNT_DESC} 순으로(엑셀 시트 2 동점 깨기 순서). {@code shown} 의 표시 순서는 건드리지 않는다.
     */
    private static ZoneRcmdtnShareWorkbookVO workbook(List<Judged> shown, BigDecimal months, Trend trend) {
        List<Judged> ordered = new ArrayList<>(shown);
        ordered.sort(BY_CNT_DESC);
        List<ZoneRcmdtnShareWorkbookRowVO> wbkRows = new ArrayList<>(ordered.size());
        for (Judged j : ordered) {
            ZoneRcmdtnShareBaseVO row = j.row;
            ZoneRcmdtnShareWorkbookRowVO r = new ZoneRcmdtnShareWorkbookRowVO();
            r.setEmdCd(row.getEmdCd());
            r.setSggNm(row.getSggNm());
            r.setEmdNm(row.getEmdNm());
            r.setCvlcptCnt(j.cvlcptCnt.longValue());
            r.setPkltCnt(nz(row.getPkltCnt()).longValue());
            r.setPkltCpcty(j.cpcty.longValue());
            r.setTopPntCvlcptCnt(nz(row.getTopPntCvlcptCnt()).longValue());
            r.setVaclotExclPkltCpcty(nz(row.getVaclotExclPkltCpcty()).longValue());
            r.setRcntCnt(j.rcntCnt == null ? null : j.rcntCnt.longValue());
            r.setBfrCnt(j.bfrCnt == null ? null : j.bfrCnt.longValue());
            wbkRows.add(r);
        }
        ZoneRcmdtnShareWorkbookFixedVO fixed = new ZoneRcmdtnShareWorkbookFixedVO();
        fixed.setCoreMltp(CORE_MLTP);
        fixed.setBndryRt(BNDRY_RT);
        fixed.setMinTrendBase(ZoneRcmdtnCalculator.MIN_TREND_BASE);

        ZoneRcmdtnShareWorkbookVO wbk = new ZoneRcmdtnShareWorkbookVO();
        wbk.setRows(wbkRows);
        wbk.setMonths(months);
        wbk.setTrendMode(trend.name());
        wbk.setFixed(fixed);
        return wbk;
    }

    /** 100면당 월평균. 면수가 0 이면 {@code null} — 0 으로 나누지 않고 "판정 불가" 로 남긴다. */
    private static BigDecimal rt(BigDecimal mm, BigDecimal cpcty) {
        return cpcty.signum() <= 0 ? null
                : mm.multiply(HUNDRED).divide(cpcty, SCALE, RoundingMode.HALF_UP);
    }

    /** 등급 판정. 🔴 경계는 전부 "이상(≥)" 이다. {@code rt == null} 은 면수 0 을 뜻한다. */
    private static String grade(BigDecimal mm, BigDecimal rt, Crtr crtr) {
        if (rt == null) {
            return GRD_UNABLE;
        }
        BigDecimal mmCrtr = crtr.cvlcptMmCrtrVl();
        BigDecimal rtCrtr = crtr.cvlcptRtCrtrVl();
        boolean c1 = mm.compareTo(mmCrtr) >= 0;
        boolean c2 = rt.compareTo(rtCrtr) >= 0;
        if (c1 && c2) {
            boolean core = mm.compareTo(mmCrtr.multiply(CORE_MLTP)) >= 0
                    && rt.compareTo(rtCrtr.multiply(CORE_MLTP)) >= 0;
            return core ? GRD_CORE : GRD_CAND;
        }
        // 🔴 민원 기준 미달이면 비율이 아무리 높아도 CVLCPT_ONLY 가 아니다
        return c1 ? GRD_CVLCPT_ONLY : GRD_NONE;
    }

    private static boolean isCand(String grd) {
        return GRD_CORE.equals(grd) || GRD_CAND.equals(grd);
    }

    /**
     * 검토 지수 = min(월평균 ÷ 민원 월기준값, 비율 ÷ 민원 비율기준값) — {@link #SCALE} 자리(버림).
     *
     * <p>🔴 <b>{@link #grade} 와 같은 입력({@link #SCALE} 자리 {@code mm}·{@code rt})에서 나눈다.</b>
     * 🔴 <b>나눗셈은 {@link RoundingMode#DOWN} 이다</b> — 올림이 섞이면 기준에 아주 조금 못 미친 값
     * (예: 49.999999 ÷ 50 = 0.99999998)이 {@code 1.000000} 이 되어 "지수 1 인데 후보가 아님" 이 생긴다.
     * 버림은 단조이고 1·2 를 그대로 두므로 {@code mm ≥ 기준 ⇔ 몫 ≥ 1}, {@code mm ≥ 2×기준 ⇔ 몫 ≥ 2} 가
     * 정확히 성립한다. 그래서 <b>지수 ≥ 1 ⇔ 후보, 지수 ≥ 2 ⇔ 핵심 후보</b>다(단위테스트가 고정한다).
     *
     * <p>{@code null} 인 경우는 둘이다.
     * <ul>
     *   <li>면수 0({@code rt == null}, 등급 {@code UNABLE}) — 비율이 없어 계산할 수 없다.</li>
     *   <li>두 기준값이 모두 0 — 나눌 기준이 없다({@link #WARN_CRTR_ALL_ZERO}).</li>
     * </ul>
     * 기준값이 한쪽만 0 이면 0 으로 나누지 않고 <b>그 항을 뺀다</b> — {@link #grade} 에서 기준 0 은
     * "항상 충족"({@code x ≥ 0}, 2배 조건도 {@code x ≥ 0})이라 등급을 가르는 것은 다른 항뿐이고, 지수도
     * 그 항만으로 내야 등급과 맞는다.
     */
    private static BigDecimal rvwScr(BigDecimal mm, BigDecimal rt, Crtr crtr) {
        if (rt == null) {
            return null;
        }
        BigDecimal byMm = ratio(mm, crtr.cvlcptMmCrtrVl());
        BigDecimal byRt = ratio(rt, crtr.cvlcptRtCrtrVl());
        if (byMm == null) {
            return byRt;
        }
        return byRt == null ? byMm : byMm.min(byRt);
    }

    /** 기준 대비 배수({@link #SCALE} 자리 버림). 기준이 0 이하면 {@code null} — "항상 충족" 이라 항에서 뺀다. */
    private static BigDecimal ratio(BigDecimal value, BigDecimal crtrVl) {
        return crtrVl.signum() <= 0 ? null : value.divide(crtrVl, SCALE, RoundingMode.DOWN);
    }

    /**
     * 증가율 정밀 비율 g = (최근 − 이전) ÷ 이전, {@link #SCALE} 자리(HALF_UP). 🔴 % 가 아니라 비율이다.
     *
     * <p>이전이 {@code ZoneRcmdtnCalculator.MIN_TREND_BASE}(10) <b>미만이면 {@code null}</b>(0 포함 — 작은 수
     * 폭발 방지). 🔴 요금제 {@code ZoneRcmdtnCalculator.incrsRt}(1자리 반올림 %)를 쓰지 않는다 — 반올림한
     * 값으로 계수를 내면 끝자리가 갈린다(연동 0.938 → 0.939).
     */
    private static BigDecimal incrsRatio(BigDecimal recent, BigDecimal bfr) {
        if (recent == null || bfr == null
                || bfr.compareTo(BigDecimal.valueOf(ZoneRcmdtnCalculator.MIN_TREND_BASE)) < 0) {
            return null;
        }
        return recent.subtract(bfr).divide(bfr, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 증가계수 = 1 + 가중치 × clamp(g, −상한/100, +상한/100). g 가 {@code null} 이면 1(추세 중립).
     * 가중치 0 이면 항상 1 이다(= 추세 미반영 순서). 반올림하지 않는다 — 표시만 {@link #toZone} 에서 한다.
     *
     * <p>본문은 정본 {@link ZoneRcmdtnCalculator#incrsCfc(BigDecimal, BigDecimal, BigDecimal)} 로
     * 옮겼다(요금제와 같은 산식 — 복사 금지). 여기는 위임 한 줄이다. {@code g} 는 그대로 <b>비율</b>이다.
     */
    private static BigDecimal incrsCfc(BigDecimal g, Crtr crtr) {
        return ZoneRcmdtnCalculator.incrsCfc(g, crtr.incrsWgvl(), crtr.incrsRtUplmtVl());
    }

    /** 월평균 또는 비율이 기준의 {@link #BNDRY_RT} 배에 못 미치는가(= 여유가 5% 미만인가). */
    private static boolean nearBndry(BigDecimal mm, BigDecimal rt, BigDecimal mmCrtr, BigDecimal rtCrtr) {
        return mm.compareTo(mmCrtr.multiply(BNDRY_RT)) < 0
                || rt.compareTo(rtCrtr.multiply(BNDRY_RT)) < 0;
    }

    /**
     * 표시 순번을 매긴다 — 넘겨받은 목록에서 <b>검토 지수가 있는 읍면동 전부</b>(판정 불가 제외)에 1..n.
     *
     * <p>🔴 <b>넘겨받는 것은 응답에 실리는 읍면동(지역 필터 적용 후)이다</b> — 그래서 순번은 조회 결과
     * 안에서 1 부터 이어진다. 시군구로 거르면 그 시군구 안의 번호이고, 필터가 없으면 도 전체 번호다.
     * 🔁 종전(~2026-10-13)에는 도 전체에서 매긴 뒤 걸러, 필터 결과의 번호가 중간부터 시작했다.
     * 순번 부여는 <b>여기 한 곳</b>이다.
     *
     * <p>순서는 <b>우선도(추세 반영 지수 · 반올림 전 정밀값) 내림차순</b> → 민원 건수 내림차순 →
     * {@code emd_cd} 오름차순이다. 🔴 <b>후보 여부를 1차 키로 두지 않는다</b> — 지수 0.99 · 계수 1.2 인 기준 미달
     * 동이 지수 1.00 · 계수 0.6 인 후보보다 앞에 선다(사용자 요청 2026-10-26 — "목록 순위를 우선도 기준으로").
     * 그래서 후보(깃발)의 번호는 1..n 으로 이어지지 않을 수 있다. 등급·후보 판정은 검토 지수 그대로다.
     * (~2026-10-26)는 후보 먼저 → 추세 반영 지수 순이었고, 그 전에는 검토 지수 내림차순이었다.
     * 지수가 없는 읍면동은 {@code rnk} 가 {@code null} 로 남는다.
     *
     * <p>🔴 <b>사업 우선순위가 아니라 표시 순번이다</b> — 지수는 기준을 넘은 정도일 뿐, "몇 번까지 하라" 를
     * 가르는 근거가 없다(분석 보고서 8절). 🔁 종전(~2026-10-13)에는 후보에만, 민원 순위와 비율 순위의
     * 합으로 매겼다.
     */
    private static void assignRnk(List<Judged> shown) {
        List<Judged> scored = new ArrayList<>();
        for (Judged j : shown) {
            if (j.scr != null) {
                scored.add(j);
            }
        }
        scored.sort(Comparator.comparing((Judged j) -> j.rfltScr, Comparator.reverseOrder())
                .thenComparing(BY_CNT_DESC));
        for (int i = 0; i < scored.size(); i++) {
            scored.get(i).rnk = i + 1;
        }
    }

    private static ZoneRcmdtnShareResultVO.Smry summarize(List<Judged> all) {
        int rvwTrgt = 0;
        int core = 0;
        int cvlcptOnly = 0;
        int unable = 0;
        int vaclotExcl = 0;
        BigDecimal tot = BigDecimal.ZERO;
        BigDecimal candTot = BigDecimal.ZERO;
        for (Judged j : all) {
            tot = tot.add(j.cvlcptCnt);
            if (j.cand) {
                rvwTrgt++;
                candTot = candTot.add(j.cvlcptCnt);
            }
            if (GRD_CORE.equals(j.grd)) {
                core++;
            } else if (GRD_CVLCPT_ONLY.equals(j.grd)) {
                cvlcptOnly++;
            } else if (GRD_UNABLE.equals(j.grd)) {
                unable++;
            }
            if (j.vaclotExclCand) {
                vaclotExcl++;
            }
        }
        ZoneRcmdtnShareResultVO.Smry smry = new ZoneRcmdtnShareResultVO.Smry();
        smry.setEmdCnt(all.size());
        smry.setRvwTrgtCnt(rvwTrgt);
        smry.setCoreCnt(core);
        smry.setCvlcptOnlyCnt(cvlcptOnly);
        smry.setUnableCnt(unable);
        smry.setVaclotExclRvwTrgtCnt(vaclotExcl);
        smry.setCvlcptTot(tot.longValue());
        smry.setRvwTrgtCvlcptRt(tot.signum() == 0 ? null
                : candTot.multiply(HUNDRED).divide(tot, 1, RoundingMode.HALF_UP));
        return smry;
    }

    /** 판정 결과 → 응답 VO. 🔴 반올림은 여기 한 곳에서만 한다. */
    private static ZoneRcmdtnShareZoneVO toZone(Judged j) {
        ZoneRcmdtnShareBaseVO row = j.row;
        ZoneRcmdtnShareZoneVO z = new ZoneRcmdtnShareZoneVO();
        z.setRnk(j.rnk);
        z.setEmdCd(row.getEmdCd());
        z.setSggNm(row.getSggNm());
        z.setEmdNm(row.getEmdNm());
        z.setLat(row.getLat());
        z.setLot(row.getLot());
        z.setCvlcptCnt(j.cvlcptCnt.longValue());
        z.setCvlcptMmAvg(round(j.mm, 1));
        z.setPkltCnt(nz(row.getPkltCnt()).longValue());
        z.setPkltCpcty(j.cpcty.longValue());
        z.setCvlcptRt(round(j.rt, 2));
        // 🔴 검토 지수만 버림이다 — RVW_SCR_SCALE 주석 참고
        z.setRvwScr(j.scr == null ? null : j.scr.setScale(RVW_SCR_SCALE, RoundingMode.DOWN));
        z.setRvwGrdCd(j.grd);
        z.setRvwTrgtYn(j.cand ? YN_Y : YN_N);
        z.setCutnCds(j.cutnCds);
        z.setTopPntRt(round(j.topPntRt, 1));
        z.setVaclotExclCvlcptRt(round(j.vaclotExclRt, 2));
        z.setVaclotExclRvwTrgtYn(j.vaclotExclCand ? YN_Y : YN_N);
        // 추세 — 🔴 정밀값에서 표시만 줄인다(반올림한 값을 되먹이지 않는다)
        z.setBfrCvlcptCnt(j.bfrCnt == null ? null : j.bfrCnt.longValue());
        z.setCvlcptIncrsRt(j.incrsRatio == null ? null
                : j.incrsRatio.multiply(HUNDRED).setScale(INCRS_RT_SCALE, RoundingMode.HALF_UP));
        z.setIncrsCfc(round(j.incrsCfc, INCRS_CFC_SCALE));
        // 🔴 추세 반영 지수도 검토 지수처럼 버림이다
        z.setIncrsRfltRvwScr(j.rfltScr == null ? null : j.rfltScr.setScale(RVW_SCR_SCALE, RoundingMode.DOWN));
        return z;
    }

    // =====================================================================================
    // 정렬 · 자릿수
    // =====================================================================================

    private static final Comparator<Judged> BY_EMD_CD =
            Comparator.comparing((Judged j) -> j.row.getEmdCd(), Comparator.nullsLast(Comparator.naturalOrder()));

    /** 민원 내림차순, 동률은 {@code emd_cd} 오름차순 */
    private static final Comparator<Judged> BY_CNT_DESC =
            Comparator.comparing((Judged j) -> j.cvlcptCnt, Comparator.reverseOrder()).thenComparing(BY_EMD_CD);

    /**
     * 목록 순서 — {@code rnk} 오름차순(= 우선도 내림차순, {@link #assignRnk}) → 그 뒤에
     * 순번이 없는 읍면동(민원 내림차순 →
     * {@code emd_cd}). 순번이 없는 것은 평시에는 판정 불가({@code UNABLE})뿐이다. 두 기준이 모두 0 이라
     * 전 읍면동의 지수가 없을 때도 판정 불가를 맨 뒤에 둔다.
     */
    private static final Comparator<Judged> DISPLAY_ORDER =
            Comparator.comparing((Judged j) -> j.rnk, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingInt(j -> GRD_UNABLE.equals(j.grd) ? 1 : 0)
                    .thenComparing(BY_CNT_DESC);

    private static BigDecimal round(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * {@code NUMERIC(12,4)} 설정값의 꼬리 0 제거 — {@code 50.0000 → 50}, {@code 7.5000 → 7.5}.
     * {@code stripTrailingZeros()} 만 쓰면 {@code 5E+1} 로 직렬화되므로 음수 스케일은 0 으로 되돌린다
     * ({@code PolicySimOutputCalculator.plain} 과 같은 식).
     */
    static BigDecimal plain(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0, RoundingMode.UNNECESSARY) : s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void addOnce(List<String> list, String code) {
        if (list != null && !list.contains(code)) {
            list.add(code);
        }
    }

    /** 추세 블록 방식 — {@link #trendMode} 가 도 전체 1회 정한다 */
    private enum Trend {
        /** 기간 하한 미달 — 추세를 계산하지 않는다 */
        NONE,
        /** 1차 — 직전 동일 기간 */
        PRIMARY,
        /** 폴백 — 1차 이전 블록이 전 읍면동 합 0건이거나 자료 시작일 앞이라 반 분할 */
        FALLBACK
    }

    /** 판정 중간값 — 전부 {@link #SCALE} 자리(반올림 전)다. 응답에는 {@link #toZone} 을 거쳐서만 나간다. */
    private static final class Judged {
        private ZoneRcmdtnShareBaseVO row;
        private BigDecimal cvlcptCnt;
        private BigDecimal cpcty;
        private BigDecimal mm;
        private BigDecimal rt;
        private String grd;
        private boolean cand;
        /** 검토 지수({@link #SCALE} 자리, 버림). 면수 0 이거나 두 기준이 모두 0 이면 {@code null} */
        private BigDecimal scr;
        /** 증가율의 분자 쪽 최근 블록 건수({@link #blockOf}). 기간 하한 미달이면 {@code null} */
        private BigDecimal rcntCnt;
        /** 증가율의 분모로 쓴 이전 블록 건수(폴백이면 앞 D/2). 기간 하한 미달이면 {@code null} */
        private BigDecimal bfrCnt;
        /** 증가율 정밀 비율 g({@link #SCALE} 자리). 분모 하한 미달·기간 하한 미달이면 {@code null} */
        private BigDecimal incrsRatio;
        /** 증가계수(반올림 전). g 가 {@code null} 이면 1 */
        private BigDecimal incrsCfc;
        /** 추세 반영 지수 = scr × incrsCfc(반올림 전). {@code scr} 가 {@code null} 이면 {@code null} */
        private BigDecimal rfltScr;
        private final List<String> cutnCds = new ArrayList<>();
        private BigDecimal topPntRt;
        private BigDecimal vaclotExclRt;
        private boolean vaclotExclCand;
        private Integer rnk;
    }
}
