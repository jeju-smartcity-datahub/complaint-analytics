package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jeju.datahub.analysis.service.InvestPrrtLotBaseVO;
import jeju.datahub.analysis.service.InvestPrrtLotVO;

/**
 * 투자우선순위 <b>점수·순위·등급 계산기</b> (TSK-4036 Step 5). 순수 계산이다 — DB 도 설정 표도 모른다.
 *
 * <p>매퍼가 낸 지표 원값({@link InvestPrrtLotBaseVO})과 {@link InvestPrrtStngResolver} 가 읽은
 * 가중치·등급 임계를 받아 분석 정본의 산식(계획서 0-2 · 분석 DEC-009~011)으로 계산한다.
 *
 * <pre>
 * S 안전   = 코드 → 점수 (A 0 / B 50 / C 100)                       백분위가 아니다
 * C·U·E·A  = 백분위 = (자기보다 값이 엄격히 작은 대상 수) ÷ (n − 1) × 100   n = 1 이면 0
 * 종합점수 = Σ(지표 점수 × 가중치) ÷ Σ가중치 → 소수 9자리(HALF_UP)
 * 순위     = 종합점수 내림차순, 동점이면 pklt_id 오름차순
 * 등급     = 점수 ≥ 높음 임계 → H / ≥ 중간 임계 → M / 그 밖 → L
 * </pre>
 *
 * <h2>🔴 반올림은 끝에서 한 번만 한다 — 중간값을 소수로 만들지 않는다</h2>
 * 백분위는 {@code 100·k ÷ (n−1)} 이라 대부분 무한소수다. 지표마다 소수로 만든 뒤 더하면 그 반올림
 * 오차가 9자리째를 흔들고, 분석에서는 float 덧셈 순서 때문에 <b>수학적 동점이 갈려 순위가 바뀐</b>
 * 전례가 있다(DEC-009). 그래서 백분위를 <b>개수 {@code k}(정수)</b>로 들고 다니다가
 *
 * <pre>
 * 종합점수 = [ Σ_백분위지표 w·100·k  +  w_S·S·(n−1) ] ÷ [ (n−1)·Σw ]
 * </pre>
 *
 * 분자·분모를 정확한 값으로 만든 다음 <b>나눗셈 한 번</b>에 {@code divide(분모, 9, HALF_UP)} 한다.
 * 결과는 참값의 올바른 9자리 반올림이고, 수학적 동점은 항상 같은 값이 된다({@code MathContext} 를
 * 쓰지 않는다 — 정밀도를 고를 일이 없다).
 *
 * <h2>🔴 비어 있는 입력의 처리 — 조용히 0 으로 치지 않는다</h2>
 * <ul>
 *   <li><b>안전점검이 없다</b>({@code sftyChckRsltCd == null}) — 0점으로 계산하고
 *       {@link Result#sftyChckMissingCnt()} 에 센다.</li>
 *   <li><b>대응표에 없는 안전 코드</b> — 0점으로 계산하고 {@link Result#unknownSftyCds()} 에
 *       코드별 건수를 싣고 WARN 1줄을 남긴다. 코드가 늘었는데 대응표를 안 고친 상황이다.</li>
 *   <li><b>회전율이 없다</b>({@code dayAvgTrnvrRt == null} — 이용률 집계 달에 이용패턴이 없다) —
 *       <b>0 으로 보고</b> 백분위를 매기고 {@link Result#utztnMissingCnt()} 에 센다. 그 주차장만
 *       이용 지표를 빼고 평균하면 지표 수가 다른 점수끼리 한 줄에 서게 되어 비교가 깨진다.
 *       출력의 {@code dayAvgTrnvrRt} 는 {@code null} 그대로 둔다(0 은 "이용이 없었다" 로 읽힌다).</li>
 *   <li>민원·단속 건수 {@code null} 은 0 이다(매퍼 계약상 나오지 않는다 — 방어).</li>
 *   <li><b>사고 건수가 없다</b> — 0 이다(TSK-4296 "값 없음 = 0"). 그 주차장에 사고가 없어도, 사고이력
 *       표가 통째로 비어도 같다 — 전 주차장이 0 이면 사고 점수가 전부 0 이 되고 종합점수 상한이
 *       {@code 100 − w_A} 로 내려간다(D1=A — 재정규화하지 않는다).</li>
 * </ul>
 * 세 개수는 호출자(서비스)가 응답 {@code warnings} 로 옮긴다 — 이 클래스는 문구를 만들지 않는다.
 *
 * <h2>가중치</h2>
 * {@code 변수코드 → 값} 에서 <b>이 산식이 아는 5개</b>({@link #VRBL_SAFETY}·{@link #VRBL_COMPLAINT}·
 * {@link #VRBL_USAGE}·{@link #VRBL_ENFORCE}·{@link #VRBL_ACCIDENT}) 중 <b>값이 0 보다 큰 것만</b> 쓴다.
 * 0 이거나 목록에 없는 지표는 분자에도 분모에도 넣지 않는다. 합이 100 일 필요는 없다(합으로 나눈다).
 */
@Component
public class InvestPrrtCalculator {

    private static final Logger log = LoggerFactory.getLogger(InvestPrrtCalculator.class);

    /** 안전점검 결과 변수코드 — 코드 → 점수 */
    public static final String VRBL_SAFETY = "SAFETY";
    /** 주변 민원 변수코드 — 백분위 */
    public static final String VRBL_COMPLAINT = "COMPLAINT";
    /** 이용 강도(일평균 회전율) 변수코드 — 백분위 */
    public static final String VRBL_USAGE = "USAGE";
    /** 주변 단속 변수코드 — 백분위 */
    public static final String VRBL_ENFORCE = "ENFORCE";
    /** 사고 변수코드 — 백분위(TSK-4296 · 그 주차장에 기록된 건수, 공간 배정 없음) */
    public static final String VRBL_ACCIDENT = "ACCIDENT";

    /** 이 산식이 항을 가진 변수코드(순서 고정). 그 밖의 코드는 반영할 방법이 없다 */
    public static final List<String> KNOWN_VRBL_CDS =
            List.of(VRBL_SAFETY, VRBL_COMPLAINT, VRBL_USAGE, VRBL_ENFORCE, VRBL_ACCIDENT);

    /** 등급 코드({@code IVST_PRRT_LVL_CD}) */
    public static final String LVL_HIGH = "H";
    public static final String LVL_MID = "M";
    public static final String LVL_LOW = "L";

    /** 사유 코드 */
    public static final String RSN_SAFETY = "SAFETY";
    public static final String RSN_DEMAND = "DEMAND";
    public static final String RSN_BOTH = "BOTH";
    public static final String RSN_NONE = "NONE";

    /**
     * 🔴 안전점검 결과 코드({@code SFTY_CHCK_RSLT_CD}) → 점수. <b>대응은 이 한 곳에만 둔다.</b>
     * 코드가 늘면 여기를 고친다 — 안 고치면 그 코드는 0점 + {@link Result#unknownSftyCds()} 로 드러난다.
     */
    static final Map<String, Integer> SFTY_SCR_BY_CD = Map.of("A", 0, "B", 50, "C", 100);

    /** 종합점수 소수 자리 — 분석 정본과 같은 9자리. 순위는 이 값으로 매긴다 */
    public static final int SCORE_SCALE = 9;

    /** 지표별 백분위 점수 소수 자리(표시용 — 종합점수 계산에는 이 반올림값을 쓰지 않는다) */
    public static final int IDX_SCALE = 6;

    /** 수요 사유의 상위 비율 = 1/{@value} (25% — 분석 설정 {@code demand_top_share}) */
    private static final int DEMAND_TOP_DIVISOR = 4;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 계산 결과 한 벌.
     *
     * @param lots               전 대상의 점수·순위·등급. <b>순위 오름차순</b>
     * @param sftyChckMissingCnt 안전점검 기록이 없어 0점으로 계산한 주차장 수
     * @param unknownSftyCds     대응표에 없는 안전 코드 → 그 코드를 가진 주차장 수(0점으로 계산). 코드순
     * @param utztnMissingCnt    회전율이 없어 0 으로 보고 백분위를 매긴 주차장 수
     * @param lessCnts           🆕 TSK-4343 — {@code pklt_id → 백분위 개수 k}(민원·이용·사고). 엑셀 재계산 데이터
     *                           ({@code wbk.rows}) 전용이며 이미 계산한 값을 버리지 않을 뿐이다(산식 불변).
     *                           🔴 단속 {@code kE} 는 싣지 않는다(TSK-4343 D2-1 — 단속 설정 행이 없다)
     */
    public record Result(
            List<InvestPrrtLotVO> lots,
            int sftyChckMissingCnt,
            Map<String, Integer> unknownSftyCds,
            int utztnMissingCnt,
            Map<Long, LessCnt> lessCnts) {
    }

    /**
     * 주차장 1곳의 백분위 개수 {@code k}(자기보다 값이 <b>엄격히</b> 작은 대상 수 — 모집단은 필터 전 전 지역). TSK-4343.
     *
     * @param cvlcpt 민원 건수의 {@code k}
     * @param utztn  일평균 회전율의 {@code k}(회전율 없음 = 0 으로 본 값 기준)
     * @param acdnt  사고 건수의 {@code k}
     */
    public record LessCnt(int cvlcpt, int utztn, int acdnt) {
    }

    /**
     * 전 대상의 점수·순위·등급·보조 값을 계산한다.
     *
     * @param bases        매퍼가 낸 대상 주차장 전체(🔴 행정구역으로 거르기 <b>전</b> — 백분위 모집단이다).
     *                     {@code null}·빈 목록이면 빈 결과
     * @param weights      변수코드 → 가중치. 아는 5개 중 0 보다 큰 것만 쓴다
     * @param upGrdCrtrScr '높음' 임계(이상이면 {@code H})
     * @param mdGrdCrtrScr '중간' 임계(이상이면 {@code M})
     * @return 순위순 목록과 0점 처리 개수
     * @throws IllegalArgumentException 반영할 가중치가 하나도 없을 때 — 🔴 조용히 전부 0점을 만들지
     *                                  않는다. 정상 경로에서는 {@link InvestPrrtStngResolver} 가
     *                                  먼저 409 로 끊으므로 여기까지 오면 호출 실수다
     */
    public Result calculate(List<InvestPrrtLotBaseVO> bases,
                            Map<String, BigDecimal> weights,
                            BigDecimal upGrdCrtrScr,
                            BigDecimal mdGrdCrtrScr) {
        Map<String, BigDecimal> used = usedWeights(weights);
        if (used.isEmpty()) {
            throw new IllegalArgumentException(
                    "투자우선순위 점수에 반영할 가중치가 없습니다 (필요=" + KNOWN_VRBL_CDS + ", 입력=" + weights + ")");
        }
        if (upGrdCrtrScr == null || mdGrdCrtrScr == null) {
            throw new IllegalArgumentException("등급 임계가 비었습니다 (높음=" + upGrdCrtrScr
                    + ", 중간=" + mdGrdCrtrScr + ")");
        }
        if (bases == null || bases.isEmpty()) {
            return new Result(List.of(), 0, Map.of(), 0, Map.of());
        }

        int n = bases.size();

        // 1) 지표 원값 → 안전 점수 / 백분위 개수 k
        int[] sfty = new int[n];
        BigDecimal[] cvlcpt = new BigDecimal[n];
        BigDecimal[] utztn = new BigDecimal[n];
        BigDecimal[] crdn = new BigDecimal[n];
        BigDecimal[] acdnt = new BigDecimal[n];
        int sftyMissing = 0;
        int utztnMissing = 0;
        Map<String, Integer> unknownSfty = new TreeMap<>();

        for (int i = 0; i < n; i++) {
            InvestPrrtLotBaseVO b = bases.get(i);

            String cd = b.getSftyChckRsltCd();
            Integer scr = cd == null ? null : SFTY_SCR_BY_CD.get(cd);
            if (cd == null) {
                sftyMissing++;
            } else if (scr == null) {
                unknownSfty.merge(cd, 1, Integer::sum);
            }
            sfty[i] = scr == null ? 0 : scr;

            cvlcpt[i] = b.getCvlcptCnt() == null ? BigDecimal.ZERO : BigDecimal.valueOf(b.getCvlcptCnt());
            crdn[i] = b.getCrdnCnt() == null ? BigDecimal.ZERO : BigDecimal.valueOf(b.getCrdnCnt());
            acdnt[i] = b.getAcdntCnt() == null ? BigDecimal.ZERO : BigDecimal.valueOf(b.getAcdntCnt());
            if (b.getDayAvgTrnvrRt() == null) {
                utztnMissing++;
                utztn[i] = BigDecimal.ZERO;
            } else {
                utztn[i] = b.getDayAvgTrnvrRt();
            }
        }
        if (!unknownSfty.isEmpty()) {
            log.warn("⚠️ [INVEST] 대응표에 없는 안전점검 결과 코드를 0점으로 계산했습니다: {} (대응표={}). "
                    + "코드가 늘었으면 InvestPrrtCalculator.SFTY_SCR_BY_CD 를 고쳐야 합니다.",
                    unknownSfty, new TreeMap<>(SFTY_SCR_BY_CD));
        }

        int[] kC = lessCounts(cvlcpt);
        int[] kU = lessCounts(utztn);
        int[] kE = lessCounts(crdn);
        int[] kA = lessCounts(acdnt);

        // 2) 종합점수(9자리) → 순위
        BigDecimal[] score = scores(used, n, sfty, kC, kU, kE, kA);
        int[] rnk = ranks(bases, score);

        // 3) 단속을 뺀 순위 — 단속이 반영되지 않았으면 본 순위와 같고, 단속뿐이면 매길 수 없다
        int[] crdnExclRnk;
        if (!used.containsKey(VRBL_ENFORCE)) {
            crdnExclRnk = rnk;
        } else {
            Map<String, BigDecimal> without = new LinkedHashMap<>(used);
            without.remove(VRBL_ENFORCE);
            crdnExclRnk = without.isEmpty() ? null : ranks(bases, scores(without, n, sfty, kC, kU, kE, kA));
        }

        // 4) 수요 사유 — (민원 백분위 + 이용 백분위) ÷ 2 의 순위가 ceil(n × 25%) 이내.
        //    평균의 순서는 kC + kU 의 순서와 같다(둘 다 같은 (n−1) 로 나눈다) — 정수로 비교한다.
        BigDecimal[] demandIdx = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            demandIdx[i] = BigDecimal.valueOf((long) kC[i] + kU[i]);
        }
        int[] demandRnk = ranks(bases, demandIdx);
        int demandCut = (n + DEMAND_TOP_DIVISOR - 1) / DEMAND_TOP_DIVISOR;

        // 5) 조립 (순위순) + 시군구 안 등수
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingInt(i -> rnk[i]));

        Map<String, Integer> sggSeq = new HashMap<>();
        List<InvestPrrtLotVO> lots = new ArrayList<>(n);
        Map<Long, LessCnt> lessCnts = new LinkedHashMap<>();
        for (int i : order) {
            InvestPrrtLotBaseVO b = bases.get(i);
            lessCnts.put(b.getPkltId(), new LessCnt(kC[i], kU[i], kA[i]));
            InvestPrrtLotVO vo = new InvestPrrtLotVO();

            vo.setRnk(rnk[i]);
            vo.setSggRnk(b.getSggNm() == null ? null : sggSeq.merge(b.getSggNm(), 1, Integer::sum));
            vo.setCrdnExclRnk(crdnExclRnk == null ? null : crdnExclRnk[i]);

            vo.setPkltId(b.getPkltId());
            vo.setPkltNm(b.getPkltNm());
            vo.setPkltAddr(b.getPkltAddr());
            vo.setSggNm(b.getSggNm());
            vo.setEmdCd(b.getEmdCd());
            vo.setEmdNm(b.getEmdNm());
            vo.setPkltCpcty(b.getPkltCpcty());
            vo.setInstlYm(b.getInstlYm());
            vo.setLat(b.getLat());
            vo.setLot(b.getLot());

            vo.setSnthsScr(score[i]);
            vo.setPrrtLvlCd(gradeOf(score[i], upGrdCrtrScr, mdGrdCrtrScr));
            boolean safety = sfty[i] > 0;
            boolean demand = demandRnk[i] <= demandCut;
            vo.setRsnCd(safety && demand ? RSN_BOTH : safety ? RSN_SAFETY : demand ? RSN_DEMAND : RSN_NONE);

            vo.setSftyChckRsltCd(b.getSftyChckRsltCd());
            vo.setSftyChckYmd(b.getSftyChckYmd());
            vo.setSftyRiskScr(BigDecimal.valueOf(sfty[i]));
            vo.setCvlcptFrqScr(percentile(kC[i], n));
            vo.setUtztnPttnScr(percentile(kU[i], n));
            vo.setCrdnScr(percentile(kE[i], n));
            vo.setAcdntScr(percentile(kA[i], n));

            vo.setCvlcptCnt(b.getCvlcptCnt() == null ? 0L : b.getCvlcptCnt());
            vo.setDayAvgTrnvrRt(b.getDayAvgTrnvrRt());
            vo.setCrdnCnt(b.getCrdnCnt() == null ? 0L : b.getCrdnCnt());
            vo.setAcdntCnt(b.getAcdntCnt() == null ? 0L : b.getAcdntCnt());
            lots.add(vo);
        }

        return new Result(Collections.unmodifiableList(lots), sftyMissing,
                Collections.unmodifiableMap(unknownSfty), utztnMissing,
                Collections.unmodifiableMap(lessCnts));
    }

    /**
     * 종합점수 → 등급 코드. 경계는 <b>이상</b>이다(정확히 임계값이면 위 등급).
     *
     * @param snthsScr     종합점수(9자리로 반올림된 값 — 순위를 매긴 바로 그 값으로 판정한다)
     * @param upGrdCrtrScr '높음' 임계
     * @param mdGrdCrtrScr '중간' 임계
     */
    public static String gradeOf(BigDecimal snthsScr, BigDecimal upGrdCrtrScr, BigDecimal mdGrdCrtrScr) {
        if (snthsScr.compareTo(upGrdCrtrScr) >= 0) {
            return LVL_HIGH;
        }
        if (snthsScr.compareTo(mdGrdCrtrScr) >= 0) {
            return LVL_MID;
        }
        return LVL_LOW;
    }

    // ------------------------------------------------------------------ 내부 헬퍼

    /** 아는 5개 중 값이 0 보다 큰 것만, {@link #KNOWN_VRBL_CDS} 순서로. */
    private static Map<String, BigDecimal> usedWeights(Map<String, BigDecimal> weights) {
        Map<String, BigDecimal> used = new LinkedHashMap<>();
        if (weights == null) {
            return used;
        }
        for (String cd : KNOWN_VRBL_CDS) {
            BigDecimal w = weights.get(cd);
            if (w != null && w.signum() > 0) {
                used.put(cd, w);
            }
        }
        return used;
    }

    /**
     * 값마다 "자기보다 <b>엄격히</b> 작은 값의 개수". 같은 값은 같은 개수를 받는다(동점 = 같은 백분위).
     * 비교는 {@code compareTo} 라 스케일이 달라도({@code 2.0} · {@code 2.00}) 같은 값이다.
     */
    private static int[] lessCounts(BigDecimal[] values) {
        BigDecimal[] sorted = values.clone();
        Arrays.sort(sorted);
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            int lo = 0;
            int hi = sorted.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (sorted[mid].compareTo(values[i]) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            out[i] = lo;
        }
        return out;
    }

    /** 표시용 백분위 = 100·k ÷ (n−1), {@link #IDX_SCALE} 자리. n = 1 이면 0. */
    private static BigDecimal percentile(int k, int n) {
        if (n <= 1) {
            return BigDecimal.ZERO.setScale(IDX_SCALE);
        }
        return BigDecimal.valueOf(100L * k).divide(BigDecimal.valueOf(n - 1L), IDX_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 종합점수 — 분자·분모를 정확히 만든 뒤 한 번만 나눈다(클래스 주석).
     * n = 1 이면 백분위가 전부 0 이라 {@code k} 도 0 이다 — 분모의 (n−1) 자리에 1 을 넣어도 값이 같다.
     */
    private static BigDecimal[] scores(Map<String, BigDecimal> used, int n,
                                       int[] sfty, int[] kC, int[] kU, int[] kE, int[] kA) {
        BigDecimal n1 = BigDecimal.valueOf(Math.max(n - 1L, 1L));
        BigDecimal wS = used.get(VRBL_SAFETY);
        BigDecimal wC = used.get(VRBL_COMPLAINT);
        BigDecimal wU = used.get(VRBL_USAGE);
        BigDecimal wE = used.get(VRBL_ENFORCE);
        BigDecimal wA = used.get(VRBL_ACCIDENT);

        BigDecimal wSum = BigDecimal.ZERO;
        for (BigDecimal w : used.values()) {
            wSum = wSum.add(w);
        }
        BigDecimal den = n1.multiply(wSum);

        BigDecimal[] out = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            BigDecimal num = BigDecimal.ZERO;
            if (wS != null) {
                num = num.add(wS.multiply(BigDecimal.valueOf(sfty[i])).multiply(n1));
            }
            if (wC != null) {
                num = num.add(wC.multiply(HUNDRED).multiply(BigDecimal.valueOf(kC[i])));
            }
            if (wU != null) {
                num = num.add(wU.multiply(HUNDRED).multiply(BigDecimal.valueOf(kU[i])));
            }
            if (wE != null) {
                num = num.add(wE.multiply(HUNDRED).multiply(BigDecimal.valueOf(kE[i])));
            }
            if (wA != null) {
                num = num.add(wA.multiply(HUNDRED).multiply(BigDecimal.valueOf(kA[i])));
            }
            out[i] = num.divide(den, SCORE_SCALE, RoundingMode.HALF_UP);
        }
        return out;
    }

    /** 값 내림차순 · 동점이면 {@code pklt_id} 오름차순으로 1부터 매긴 순위(입력 순서대로 돌려준다). */
    private static int[] ranks(List<InvestPrrtLotBaseVO> bases, BigDecimal[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int c = values[b].compareTo(values[a]);
            if (c != 0) {
                return c;
            }
            return Comparator.nullsLast(Comparator.<Long>naturalOrder())
                    .compare(bases.get(a).getPkltId(), bases.get(b).getPkltId());
        });
        int[] rnk = new int[n];
        for (int pos = 0; pos < n; pos++) {
            rnk[order[pos]] = pos + 1;
        }
        return rnk;
    }
}
