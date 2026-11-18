package jeju.datahub.lifezone.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 구간 경계 산출 — <b>분류방법을 골라 쓴다</b> (TSK-4088 D17 개정 · 2026-08-31).
 *
 * <p>임계값이 <b>조회 결과에 달렸으므로</b> enum 상수가 아니라 값 객체다.
 * 방법은 {@link LifezoneClsfMthd} 여러 가지이며 🔁 <b>민원건수 기본은 등간격({@link LifezoneClsfMthd#EQUAL_INTERVAL})</b>
 * 이다(TSK-4227 — 유형마다 {@link LifezoneTarget#getDfltClsfMthd()} 가 정한다). 종전(TSK-4088)에는 자연분류(Jenks)가
 * 기본이었다 — 1인자 {@link #of(List)} 의 기본({@link LifezoneClsfMthd#DEFAULT})으로만 남는다.
 * ⚠️ 수급(TSK-4258)은 이 클래스를 쓰지 않는다 — 부족률 등간격({@link LifezoneGrade#ofShrtRt})이다.
 *
 * <h2>1. {@link LifezoneClsfMthd#JENKS} — 자연분류 (과거 기본)</h2>
 * Fisher-Jenks natural breaks. 구간 내 편차제곱합(SSD)의 합을 최소화하는 5구간을 동적계획법으로 찾는다.
 *
 * <pre>
 *   freq = 값→빈도,  u = 정렬된 고유값,  w[i] = freq[u[i]]
 *   cw/cs/cs2 누적합  →  var(i, j) = cs2 - cs² / cw          (구간 [i, j] 의 SSD, O(1))
 *   dp[j][i] = min over t&lt;i of dp[j-1][t] + var(t, i-1)     (앞 i 개 고유값을 j 구간으로)
 *   역추적 → 각 구간의 첫 고유값이 경계. 첫 경계는 1 로 낮추고 단조 보정
 * </pre>
 *
 * <p>🔴 <b>고유값+빈도로 압축</b>해서 돌린다. 격자 셀은 2,000칸이 넘지만 <b>고유값은 56~79개</b>뿐이라
 * (실측 2026-08-31) {@code O(고유값² × 5)} 로 끝난다 — 셀 수로 돌리면 {@code O(2000² × 5)} = 2천만
 * 연산이 되지만 압축하면 3만 이하다.
 *
 * <p>실측 경계(전 기간·전 지역):
 * <pre>
 *   1km   [1, 21, 77, 200, 551]   셀 274/41/12/11/3     GVF 0.977
 *   500m  [1, 20, 63, 142, 371]   셀 501/64/20/10/2     GVF 0.956
 *   250m  [1, 10, 29,  78, 228]   셀 868/149/47/12/3    GVF 0.942
 *   100m  [1,  8, 27,  66, 183]   셀 1769/203/32/8/3    GVF 0.943
 * </pre>
 *
 * <h2>2. {@link LifezoneClsfMthd#EQUAL_INTERVAL} — 등간격</h2>
 *
 * <pre>
 *   mx    = max(값&gt;0 격자의 anlsVl)
 *   w     = max(1, ceil(mx / 5))       -- 값 범위를 5등분한 폭
 *   경계  = [1, w, 2w, 3w, 4w]          -- 낮음 · 보통 · 주의 · 경고 · 심각의 하한
 * </pre>
 *
 * <p>예) {@code mx=160} → {@code w=32} → {@code [1,32,64,96,128]} →
 * {@code 1~31 / 32~63 / 64~95 / 96~127 / 128+}.
 * 하한이 0 이 아니라 1 인 것은 <b>0건 격자를 아예 그리지 않기 때문</b>이다(D5) — 첫 칸만 폭이 하나 짧다.
 *
 * <p>⚠️ <b>알려진 한계 — 분포가 치우쳐 첫 칸에 몰린다.</b> 100m 실측(2026-08-31):
 * {@code mx=247 · w=50} 에서 <b>2,015칸 중 1,997칸(99%)이 첫 칸</b>이라 지도가 사실상 단색이다.
 * 이것이 TSK-4088 이 기본을 Jenks 로 바꾼 이유였다 — 🔁 TSK-4227 에서 민원건수는 다시 등간격으로 확정됐다
 * ({@link LifezoneClsfMthd} 클래스 주석).
 *
 * <h2>3. 혼잡도 전용 2종 (TSK-4090 Step 5)</h2>
 *
 * <ul>
 *   <li>{@link LifezoneClsfMthd#QUANTILE} — 값의 P25·P50·P75·P90({@link Percentiles#linear} 선형 보간 ·
 *       {@link Math#round})을 보통·주의·경고·심각 하한으로 쓴다. {@code cap} = 최대 · {@code width} = 0.</li>
 *   <li>{@link LifezoneClsfMthd#CNGST_ABSOLUTE} — 경계가 <b>설정값</b>이라 {@link #ofFixed} 로만 만든다.
 *       {@link #of(List, LifezoneClsfMthd)} 에 넘기면 {@link IllegalArgumentException} 이다.</li>
 * </ul>
 * <ul>
 *   <li>{@link LifezoneClsfMthd#EQUAL_INTERVAL_MIN} (2026-10-08 추가 · 같은 날 ⑪ 개정) — 🔴 <b>혼잡도 전용 규칙</b>이다
 *       (민원건수 허용 목록에 없다). <b>심각 = 100점 초과</b>({@code anlsVl > }{@link #CNGST_FULL_VL})만이고,
 *       나머지(≤ 100점)는 <b>최솟값 ~ 100점</b>을 4등분해 낮음·보통·주의·경고로 나눈다.
 *       {@code mn} = 100점 이하 값의 최솟값 · {@code w = (10000 − mn)/4} (실수) ·
 *       경계 {@code [1, round(mn+w), round(mn+2w), round(mn+3w), 10001]} · 경고 상한은 100점(포함) ·
 *       {@code cap} = 최대(100점 초과 포함) · {@code width} = {@code round(w)}.
 *       축퇴 — 100점 이하 값이 없거나(빈 값·전부 100점 초과) {@code w < 1} 이면 {@code mn} 을
 *       {@code 10000 − 4} 로 내려 {@code w = 1}({@code [1, 9997, 9998, 9999, 10001]})로 세운다.
 *       🔴 <b>심각 하한 10001 은 어떤 축퇴에서도 움직이지 않는다</b>(경고 이하가 비어도 심각 판정은 유지).</li>
 * </ul>
 * 🔴 혼잡도는 호출부가 <b>점수 × 100 정수</b>({@code round(H × 10000)} · 상한 없음)로 넘긴다(이 값 객체는 정수 기반이다).
 * 추정 0({@code noEnfYn='Y'}) 격자를 입력에서 빼는 것도 호출부 책임이다.
 *
 * <h2>두 방법에 공통인 규약</h2>
 *
 * <p>🔴 <b>단조 증가 보정</b> — 좁은 조회에서 경계가 붙는다(등간격: {@code mx=3} → {@code w=1} →
 * {@code [1,1,2,3,4]} / Jenks: 고유값이 5개 미만이면 구간을 다 채우지 못한다). 연속한 경계가
 * 같거나 역전되면 뒤엣것을 {@code 앞 경계 + 1} 로 올려 {@code [1,2,3,4,5]} 로 만든다.
 * 이게 없으면 범례가 {@code 1~0} 으로 깨진다.
 *
 * <p>🔴 <b>빈 목록(D11 결과 0건)이면 예외를 던지지 않는다</b> — {@code cap=0}, 경계 {@code [1,2,3,4,5]}.
 * 0건 응답도 {@code gradeScale} 과 {@code gradeCriteria} 5종을 그대로 실어야 한다
 * (프론트 범례가 응답으로만 조립되므로 키가 비면 범례가 사라진다).
 *
 * <p>⚠️ <b>조회 간 비교가 안 된다</b> — 경계가 조회 결과에서 나오므로 같은 빨강이 이번엔 128건,
 * 다음엔 200건일 수 있다. 두 기간을 비교하려면 {@code anlsVl} 자체를 봐야 한다.
 *
 * @param cap    분류에 쓴 상한 = <b>최대값</b>. 빈 목록이면 0. <b>두 방법 모두 같다</b>
 * @param width  구간 폭 {@code ceil(mx/5)} ({@code EQUAL_INTERVAL_MIN} 은 {@code round((10000-mn)/4)}).
 *               🔴 <b>등간격 계열에서만 의미가 있다</b> — 자연분류는 경계 간격이 일정하지 않으므로 <b>항상 0</b> 을 담는다
 *               (응답 계약을 {@code {method, cap, width}} 3키로 유지하려고 키 자체는 남긴다).
 *               등간격의 하한은 1 이다
 * @param mthd   실제로 쓴 분류방법. 응답에는 {@link #method()} 로 나간다
 * @param minVls 등급별 최소값(이상). <b>{@link LifezoneGrade} 선언 순서</b>(SEVERE→LOW)로 담는다 —
 *               {@code minVls[grade.ordinal()]} 로 읽는다. 응답에는 싣지 않는다
 *               ({@code gradeCriteria[].minVl} 이 이미 싣는다)
 */
@Schema(description = "구간 경계 산출 정보 (재현·설명용)")
public record LifezoneGradeScale(
        @Schema(description = "분류 상한 = 최대값 (결과 0건이면 0)", example = "247") int cap,
        @Schema(description = "구간 폭 — 등간격에서만 의미가 있다 (자연분류는 0)", example = "0") int width,
        @JsonIgnore LifezoneClsfMthd mthd,
        @JsonIgnore int[] minVls) {

    /** 등급 수. {@link LifezoneGrade} 상수 개수와 항상 같다 */
    private static final int GRADE_CNT = LifezoneGrade.values().length;

    /** 최하위 등급({@code LOW})의 하한. 값 0 인 격자는 아예 그리지 않으므로 1 이다 (D5) */
    private static final int MIN_FLOOR = 1;

    /**
     * 혼잡 점수 100점(= 혼잡 지수 1.0)의 {@code anlsVl} — 🔴 <b>혼잡도 전용</b>(TSK-4090 ⑪).
     * 서비스가 {@code anlsVl = round(H × 이 값)} 의 배율로, {@link LifezoneClsfMthd#EQUAL_INTERVAL_MIN} 이
     * 심각 경계(이 값 초과)로 <b>같은 상수</b>를 쓴다 — 둘이 갈리면 100점 초과 수와 심각 수가 어긋난다.
     */
    public static final int CNGST_FULL_VL = 10_000;

    /** 자연분류에서 {@link #width} 에 담는 값 — 간격이 일정하지 않으므로 "없음"을 뜻하는 0 이다 */
    private static final int NO_WIDTH = 0;

    /** 배열을 그대로 물지 않게 복사해 담는다 — 값 객체가 외부 변경에 흔들리지 않게 한다 */
    public LifezoneGradeScale {
        if (mthd == null) {
            throw new IllegalArgumentException("분류방법(mthd)은 필수입니다");
        }
        if (minVls == null || minVls.length != GRADE_CNT) {
            throw new IllegalArgumentException("minVls 는 등급 수(" + GRADE_CNT + ")와 같아야 합니다");
        }
        minVls = minVls.clone();
    }

    /**
     * 응답 {@code gradeScale} 은 {@code {method, cap, width}} 3키다.
     *
     * <p>산출 방식을 응답에 남겨, 같은 응답만으로 등급이 어떻게 나뉘었는지 설명·재현할 수 있게 한다.
     *
     * @return 실제로 쓴 {@link LifezoneClsfMthd} 의 상수명 ({@code JENKS}/{@code EQUAL_INTERVAL})
     */
    @JsonProperty("method")
    @Schema(description = "구간 분류 방식", example = "JENKS")
    public String method() {
        return mthd.name();
    }

    /**
     * 조회 결과의 값 목록에서 <b>기본 방법(자연분류)</b>으로 구간 경계를 산출한다.
     *
     * @param values 값&gt;0 격자의 {@code anlsVl} 목록
     * @see #of(List, LifezoneClsfMthd)
     */
    public static LifezoneGradeScale of(List<Integer> values) {
        return of(values, LifezoneClsfMthd.DEFAULT);
    }

    /**
     * 조회 결과의 값 목록에서 구간 경계를 산출한다.
     *
     * @param values 값&gt;0 격자의 {@code anlsVl} 목록. 정렬돼 있지 않아도 되고,
     *               {@code null}·빈 목록이어도 예외를 던지지 않는다(D11).
     *               {@code null} 원소와 0 이하 값은 무시한다
     * @param mthd   분류방법. {@code null} 이면 {@link LifezoneClsfMthd#DEFAULT}
     * @throws IllegalArgumentException {@link LifezoneClsfMthd#CNGST_ABSOLUTE} — 경계를 데이터가 아니라
     *         설정값에서 받으므로 {@link #ofFixed} 전용이다(값이 비어 있어도 던진다)
     */
    public static LifezoneGradeScale of(List<Integer> values, LifezoneClsfMthd mthd) {
        LifezoneClsfMthd applied = mthd == null ? LifezoneClsfMthd.DEFAULT : mthd;
        // 🔴 TSK-4090 — CNGST_ABSOLUTE 는 데이터에서 경계를 뽑지 않는다(ofFixed 전용). if 체인이라
        //    막지 않으면 조용히 jenks() 로 빠진다. 축퇴 분기(mx <= 0) **앞**에 둔다 — 뒤에 두면 값이
        //    없을 때 IAE 대신 기본 경계가 나가 "of(…, CNGST_ABSOLUTE) 는 IAE" 계약이 깨진다.
        if (applied == LifezoneClsfMthd.CNGST_ABSOLUTE) {
            throw new IllegalArgumentException(
                    "CNGST_ABSOLUTE 는 설정값 경계로만 산출합니다 — ofFixed(…) 를 쓰십시오");
        }

        int mx = maxOf(values);

        // 🔴 EQUAL_INTERVAL_MIN 은 공통 축퇴 경로보다 **앞**이다 — 빈 값이어도 심각 하한(100점 초과)을
        //    지켜야 한다. 공통 경로로 빠지면 [1,2,3,4,5] 가 나가 "심각 5 이상" 범례가 된다.
        if (applied == LifezoneClsfMthd.EQUAL_INTERVAL_MIN) {
            return equalIntervalFromMin(values, mx, applied);
        }

        // 🔴 공통 축퇴 경로 — 값이 하나도 없으면 경계를 데이터에서 뽑을 수 없다.
        //    예외 대신 [1,2,3,4,5] 로 세워 범례 5행이 반드시 나가게 한다(D11).
        //    폭은 **등간격 계열만 1** 이다 — 자연분류·분위는 간격이 일정하지 않아 0(NO_WIDTH).
        if (mx <= 0) {
            int width = isEqualInterval(applied) ? 1 : NO_WIDTH;
            return build(0, width, applied, defaultAsc());
        }

        if (applied == LifezoneClsfMthd.EQUAL_INTERVAL) {
            return equalInterval(mx, applied);
        }
        if (applied == LifezoneClsfMthd.EQUAL_INTERVAL_P95) {
            // 🔴 자의 길이만 P95 로 짧게 잡고 나머지는 등간격과 동일하다.
            //    상한을 넘는 셀을 버리는 게 아니라 최상위 등급에 뭉치게 두는 것이다.
            return equalInterval(percentile(values, 95), applied);
        }
        if (applied == LifezoneClsfMthd.QUANTILE) {
            return quantile(values, mx, applied);
        }
        return jenks(values, mx, applied);
    }

    /**
     * <b>설정값 경계</b>로 산출한다 — {@link LifezoneClsfMthd#CNGST_ABSOLUTE} 전용 팩토리 (TSK-4090 Step 5).
     *
     * <p>경계는 데이터가 아니라 설정({@code CNGST_GRD_BNDRY1~4} — 단위 점 × 100 반올림)에서 온다.
     * 🔴 <b>{@code ascBounds} 는 보통·주의·경고·심각 하한 4개</b>다 — 앞에 {@code MIN_FLOOR}(1)를 붙여
     * 5개 경계를 만드는 것은 <b>이 팩토리 책임</b>이다(호출부가 4개를 그대로 생성자에 넘기면 등급 수
     * 검증에서 터진다). 겹치거나 역전되면 {@link #monotonic} 이 앞+1 로 민다.
     *
     * @param ascBounds 오름차순 경계 4개 ({@code b1~b4})
     * @param cap       등급대상 값의 최대(없으면 0)
     * @param mthd      응답 {@code method} 로 나갈 분류방법
     * @throws IllegalArgumentException {@code ascBounds} 가 {@code null} 이거나 길이가 4 가 아님 · {@code mthd} 가 {@code null}
     */
    public static LifezoneGradeScale ofFixed(int[] ascBounds, int cap, LifezoneClsfMthd mthd) {
        if (ascBounds == null || ascBounds.length != GRADE_CNT - 1) {
            throw new IllegalArgumentException(
                    "ascBounds 는 최하위 등급을 뺀 경계 " + (GRADE_CNT - 1) + "개여야 합니다");
        }
        int[] asc = new int[GRADE_CNT];
        asc[0] = MIN_FLOOR;
        System.arraycopy(ascBounds, 0, asc, 1, GRADE_CNT - 1);
        return build(Math.max(0, cap), NO_WIDTH, mthd, monotonic(asc));
    }

    private static boolean isEqualInterval(LifezoneClsfMthd mthd) {
        return mthd == LifezoneClsfMthd.EQUAL_INTERVAL || mthd == LifezoneClsfMthd.EQUAL_INTERVAL_P95
                || mthd == LifezoneClsfMthd.EQUAL_INTERVAL_MIN;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 분위 (TSK-4090)
    // ─────────────────────────────────────────────────────────────────────────────

    /** 분위 경계에 쓰는 비율 — 보통·주의·경고·심각 하한 (0~1 비율이다. 25·50 이 아니다) */
    private static final double[] QUANTILE_PS = { 0.25, 0.50, 0.75, 0.90 };

    /**
     * 값의 25·50·75·90 번째 백분위수({@link Percentiles#linear} 선형 보간)를 {@link Math#round} 해
     * 보통·주의·경고·심각 하한으로 쓴다. {@code asc = [MIN_FLOOR, b1..b4]} · {@code cap} = 최대 ·
     * {@code width} = {@link #NO_WIDTH}.
     *
     * <p>🔴 <b>입력에서 제외할 격자를 빼는 것은 호출부 책임</b>이다 — 혼잡도는 추정 0
     * ({@code noEnfYn='Y'}) 격자를 넘기지 않는다(같은 값이 몰려 분위가 무너진다).
     */
    private static LifezoneGradeScale quantile(List<Integer> values, int mx, LifezoneClsfMthd mthd) {
        List<Integer> kept = new ArrayList<>(values.size());
        for (Integer v : values) {
            if (v != null && v >= MIN_FLOOR) {
                kept.add(v);
            }
        }
        double[] sorted = new double[kept.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = kept.get(i);
        }
        java.util.Arrays.sort(sorted);

        int[] asc = new int[GRADE_CNT];
        asc[0] = MIN_FLOOR;
        for (int i = 0; i < QUANTILE_PS.length; i++) {
            asc[i + 1] = (int) Math.round(Percentiles.linear(sorted, QUANTILE_PS[i]));
        }
        return build(mx, NO_WIDTH, mthd, monotonic(asc));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 등간격
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 값 범위를 5등분한다. 폭은 <b>올림</b>이라 {@code 5w >= mx} 가 보장된다
     * (최대값이 마지막 칸에 들어간다 — {@code mx ∈ {6,7,11}} 만 예외다).
     */
    private static LifezoneGradeScale equalInterval(int mx, LifezoneClsfMthd mthd) {
        int width = Math.max(1, (mx + GRADE_CNT - 1) / GRADE_CNT);

        int[] asc = new int[GRADE_CNT];
        asc[0] = MIN_FLOOR;
        for (int i = 1; i < GRADE_CNT; i++) {
            asc[i] = width * i;
        }
        return build(mx, width, mthd, monotonic(asc));
    }

    /**
     * 🔴 <b>혼잡도 전용</b> — 심각 = 100점 초과, 나머지는 <b>최솟값 ~ 100점</b> 4등분
     * ({@link LifezoneClsfMthd#EQUAL_INTERVAL_MIN} · TSK-4090 2026-10-08 ⑪ 사용자 결정).
     *
     * <pre>
     *   mn  = min(값 ≥ 1 이고 ≤ 10000)           -- 100점 초과 격자는 자에 넣지 않는다(전부 심각)
     *   w   = (10000 − mn) / 4                     -- 실수 폭, 경계마다 round
     *   asc = [1, round(mn+w), round(mn+2w), round(mn+3w), 10001]   -- 경고 상한 = 100점(포함)
     * </pre>
     *
     * <p>축퇴 — 100점 이하 값이 없거나(빈 값·전부 100점 초과) {@code w < 1}({@code mn > 9996})이면
     * {@code mn = 9996}(→ {@code w = 1})으로 세운다. {@code mn = 9996} 의 정상 결과와 같은 경계라 연속이다.
     * 🔴 심각 하한 {@code 10001} 은 축퇴에서도 고정이다 — 그래서 {@link #monotonic} 이 밀 일이 없다
     * ({@code round(mn+3w) ≤ 10000 − w < 10001}).
     *
     * <p>🔴 첫 경계는 {@code mn} 이 아니라 {@code MIN_FLOOR}(1) 다 — 다른 방법과 같은 범례 하한을 쓴다.
     */
    private static LifezoneGradeScale equalIntervalFromMin(List<Integer> values, int mx, LifezoneClsfMthd mthd) {
        int full = CNGST_FULL_VL;
        int lowerGrades = GRADE_CNT - 1;          // 낮음·보통·주의·경고 = 4
        int degenerateMn = full - lowerGrades;    // 9996 → w = 1
        int mn = Integer.MAX_VALUE;
        if (values != null) {
            for (Integer v : values) {
                if (v != null && v >= MIN_FLOOR && v <= full && v < mn) {
                    mn = v;
                }
            }
        }
        if (mn > degenerateMn) {
            mn = degenerateMn;                     // 빈 값·전부 100점 초과(MAX_VALUE) · w<1 — 한 규칙으로 축퇴
        }
        double w = (full - mn) / (double) lowerGrades;

        int[] asc = new int[GRADE_CNT];
        asc[0] = MIN_FLOOR;
        for (int i = 1; i < lowerGrades; i++) {
            asc[i] = (int) Math.round(mn + w * i);
        }
        asc[lowerGrades] = full + 1;              // 심각 = 100점 초과
        return build(Math.max(0, mx), (int) Math.round(w), mthd, monotonic(asc));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 자연분류 (Fisher-Jenks)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Fisher-Jenks 동적계획법으로 경계 5개를 찾는다.
     *
     * <p>🔴 <b>고유값 + 빈도로 압축</b>해서 돈다 — 같은 값끼리는 어떤 구간에 넣어도 같으므로
     * 셀 수가 아니라 고유값 수가 문제의 크기다(실측 56~79). 구간 편차제곱합은 누적합 세 개
     * ({@code Σw}·{@code Σwu}·{@code Σwu²})로 {@code O(1)} 에 구한다.
     *
     * <p>고유값이 {@link #GRADE_CNT} 보다 적으면 구간을 다 채울 수 없다 — 있는 만큼만 나누고
     * 나머지는 {@link #monotonic} 이 {@code 앞 + 1} 로 밀어 5칸을 세운다.
     */
    private static LifezoneGradeScale jenks(List<Integer> values, int mx, LifezoneClsfMthd mthd) {
        // ── ① 고유값 + 빈도 압축 ──────────────────────────────────────────────────
        Map<Integer, Integer> freq = new HashMap<>();
        for (Integer v : values) {
            if (v != null && v >= MIN_FLOOR) {
                freq.merge(v, 1, Integer::sum);
            }
        }
        List<Integer> uniq = new ArrayList<>(freq.keySet());
        uniq.sort(null);
        int m = uniq.size();

        // ── ② 누적합 (long/double) — cw:빈도 · cs:Σwu · cs2:Σwu² ────────────────────
        //     cs2 는 값 719²×341 정도까지 커지므로 double 로 둔다(정밀도 손실이 순위를 뒤집지 않는다).
        long[] cw = new long[m + 1];
        double[] cs = new double[m + 1];
        double[] cs2 = new double[m + 1];
        for (int i = 0; i < m; i++) {
            int u = uniq.get(i);
            long w = freq.get(u);
            cw[i + 1] = cw[i] + w;
            cs[i + 1] = cs[i] + (double) w * u;
            cs2[i + 1] = cs2[i] + (double) w * u * u;
        }

        // ── ③ DP — dp[j][i] = 앞 i 개 고유값을 j 구간으로 나눈 SSD 최소 ────────────────
        int k = Math.min(GRADE_CNT, m);
        double[][] dp = new double[k + 1][m + 1];
        int[][] back = new int[k + 1][m + 1];
        for (double[] row : dp) {
            java.util.Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        dp[0][0] = 0.0;
        for (int j = 1; j <= k; j++) {
            for (int i = j; i <= m; i++) {
                double best = Double.POSITIVE_INFINITY;
                int bestT = j - 1;
                for (int t = j - 1; t < i; t++) {
                    if (Double.isInfinite(dp[j - 1][t])) {
                        continue;
                    }
                    double cost = dp[j - 1][t] + ssd(cw, cs, cs2, t, i - 1);
                    if (cost < best) {
                        best = cost;
                        bestT = t;
                    }
                }
                dp[j][i] = best;
                back[j][i] = bestT;
            }
        }

        // ── ④ 역추적 — 각 구간의 첫 고유값이 그 등급의 하한이다 ──────────────────────
        int[] starts = new int[k];
        int idx = m;
        for (int j = k; j >= 1; j--) {
            starts[j - 1] = back[j][idx];
            idx = back[j][idx];
        }

        int[] asc = new int[GRADE_CNT];
        for (int i = 0; i < GRADE_CNT; i++) {
            // 고유값이 5개 미만이면 남는 칸은 아래에서 monotonic 이 밀어 채운다
            asc[i] = i < k ? uniq.get(starts[i]) : 0;
        }
        asc[0] = MIN_FLOOR;   // 첫 경계는 항상 1 — 값 0 격자를 그리지 않으므로 (D5)

        return build(mx, NO_WIDTH, mthd, monotonic(asc));
    }

    /**
     * 고유값 구간 {@code [a, b]}(양끝 포함)의 편차제곱합 {@code Σw(u-ū)² = Σwu² - (Σwu)²/Σw}.
     *
     * @return 구간이 비면 0
     */
    private static double ssd(long[] cw, double[] cs, double[] cs2, int a, int b) {
        long w = cw[b + 1] - cw[a];
        if (w <= 0) {
            return 0.0;
        }
        double s = cs[b + 1] - cs[a];
        double s2 = cs2[b + 1] - cs2[a];
        double v = s2 - s * s / w;
        return v > 0 ? v : 0.0;   // 부동소수 오차로 -1e-10 이 나오는 것을 0 으로 눌러 둔다
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 공통
    // ─────────────────────────────────────────────────────────────────────────────

    /** 🔴 단조 증가 보장 — 범례가 {@code 1~0} 으로 깨지지 않게 뒤엣것을 앞+1 로 올린다 */
    private static int[] monotonic(int[] asc) {
        for (int i = 1; i < asc.length; i++) {
            if (asc[i] <= asc[i - 1]) {
                asc[i] = asc[i - 1] + 1;
            }
        }
        return asc;
    }

    /** 결과 0건일 때의 경계 {@code [1,2,3,4,5]} */
    private static int[] defaultAsc() {
        int[] asc = new int[GRADE_CNT];
        for (int i = 0; i < GRADE_CNT; i++) {
            asc[i] = MIN_FLOOR + i;
        }
        return asc;
    }

    /** 오름차순(낮음→심각) 경계를 {@link LifezoneGrade} 선언 순서(심각→낮음)로 뒤집어 담는다 */
    private static LifezoneGradeScale build(int cap, int width, LifezoneClsfMthd mthd, int[] asc) {
        int[] byGrade = new int[GRADE_CNT];
        for (int i = 0; i < GRADE_CNT; i++) {
            byGrade[i] = asc[GRADE_CNT - 1 - i];
        }
        return new LifezoneGradeScale(cap, width, mthd, byGrade);
    }

    /**
     * 값 → 등급.
     *
     * @param value 격자 값
     * @return 값이 0 이하면 {@code null} — 호출부가 그 셀을 버린다(D5)
     */
    public LifezoneGrade gradeOf(int value) {
        if (value < MIN_FLOOR) {
            return null;
        }
        for (LifezoneGrade grade : LifezoneGrade.values()) {
            if (value >= minVls[grade.ordinal()]) {
                return grade;
            }
        }
        return LifezoneGrade.LOW;
    }

    /** 등급의 최소값(이상). {@code gradeCriteria[].minVl} 의 출처다 */
    public int minVlOf(LifezoneGrade grade) {
        return minVls[grade.ordinal()];
    }

    /** 배열을 그대로 내주지 않는다 — 받은 쪽이 고쳐도 이 값 객체가 흔들리지 않게 한다 */
    @Override
    @JsonIgnore
    public int[] minVls() {
        return minVls.clone();
    }

    /**
     * {@code p} 번째 백분위수 (PostgreSQL {@code percentile_disc} 와 같은 정의).
     *
     * <p>오름차순 정렬 후 {@code idx0 = ceil(p/100 × n) - 1} 번째(0-based) 원소다.
     * 부동소수 오차를 배제하려고 {@code ceil} 을 정수 연산 {@code (p·n + 99) / 100} 으로 낸다.
     * ⚠️ 값을 <b>버리는 게 아니라</b> 자의 길이를 정할 뿐이다.
     */
    private static int percentile(List<Integer> values, int p) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(values.size());
        for (Integer v : values) {
            sorted.add(v == null ? 0 : v);
        }
        Collections.sort(sorted);
        int n = sorted.size();
        int idx0 = (int) (((long) n * p + 99) / 100) - 1;
        idx0 = Math.max(0, Math.min(n - 1, idx0));
        return Math.max(0, sorted.get(idx0));
    }

    /** 값>0 격자의 최대값. 빈 목록·null 이면 0 — 두 분류방법 모두 {@code cap} 은 최대값이다 */
    private static int maxOf(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int mx = 0;
        for (Integer v : values) {
            if (v != null && v > mx) {
                mx = v;
            }
        }
        return mx;
    }
}
