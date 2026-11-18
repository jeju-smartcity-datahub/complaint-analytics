package jeju.datahub.lifezone.service;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 격자 등급 <b>분류방법</b> — 구간 경계를 어떤 규칙으로 긋는가 (TSK-4088 D17 개정 · 2026-08-31).
 *
 * <p>이름은 데이터표준사전을 따랐다 — <b>분류 {@code CLSF}</b> + <b>방법 {@code MTHD}</b>
 * (기존 {@code crdn_mthd} 선례와 같은 조합). 요청 파라미터 이름도 {@code clsfMthd} 다.
 *
 * <h2>왜 고를 수 있게 하나 — GVF 실측 (2026-08-31 · 전 기간 · 전 지역)</h2>
 * GVF(Goodness of Variance Fit, 1 에 가까울수록 구간이 분포를 잘 설명한다):
 *
 * <pre>
 *   방식            1km     500m    250m    100m
 *   등간격+P95     0.688   0.709   0.551   0.413
 *   등간격+P99     0.810   0.870   0.792   0.671
 *   등간격+최대    0.922   0.865   0.787   0.764
 *   Jenks          0.977   0.956   0.942   0.943
 * </pre>
 *
 * <p>🔴 <b>등간격은 100m 에서 2,015칸 중 1,997칸(99%)이 최하위</b>라 지도가 사실상 단색이다.
 * 백분위를 80~100 전 구간 훑어도 최선이 0.787 로 Jenks 에 못 미쳤고, <b>최적 백분위가 격자마다
 * 달라</b> 상수로 고정할 수도 없었다. 종전(TSK-4088)에는 기본이 {@link #JENKS} 였다 — TSK-4227 로
 * 민원건수 {@link #EQUAL_INTERVAL} 확정.
 *
 * <p>🔁 <b>TSK-4227 — 유형마다 1종 확정</b>(민원건수 {@link #EQUAL_INTERVAL} · 혼잡도 {@link #CNGST_ABSOLUTE}).
 * 나머지 상수는 <b>과거 보고서 해석용</b>으로 남긴다(D1 — 저장된 스냅샷 {@code anls_param_json.clsfMthd} 에
 * 과거 값이 있다). 허용 목록은 {@link LifezoneTarget#getClsfMthds()} 가 정본이며 목록 밖 값은 400 이다.
 *
 * <p>⚠️ <b>상수명이 곧 API 값</b>이다({@code clsfMthd=JENKS}) — 응답 {@code gradeScale.method} ·
 * {@code clsfMthd} 로도 그대로 나간다. 이름을 바꾸면 프론트 타입
 * ({@code src/api/lifezone.ts} 의 {@code LifezoneClsfMthd})까지 함께 가야 한다.
 */
@Schema(description = "격자 등급 분류방법 (EQUAL_INTERVAL = 등간격(민원건수 기본)"
        + " · CNGST_ABSOLUTE = 혼잡 1.0 기준(혼잡도 기본)"
        + " · JENKS = 자연분류 · EQUAL_INTERVAL_P95 = 등간격 P95상한"
        + " · EQUAL_INTERVAL_MIN = 100점 초과 심각 + 최솟값~100점 4등분 · QUANTILE = 분위)."
        + " 🔁 TSK-4227 — 유형마다 1종 확정. 나머지 상수는 과거 보고서 해석용(D1)."
        + " 허용 목록·기본값은 /targets 의 clsfMthds·dfltClsfMthd")
public enum LifezoneClsfMthd {

    /**
     * <b>자연분류(Fisher-Jenks natural breaks)</b> — 구간 내 편차제곱합을 최소화하는 경계를 찾는다.
     *
     * <p>분포가 한쪽으로 심하게 치우친 민원 격자에서 등간격보다 GVF 가 0.94~0.98 로 높다.
     * 대신 경계가 "왜 21 인가"처럼 산술로 설명되지 않고 데이터에서 나온다.
     */
    JENKS("자연분류"),

    /**
     * <b>등간격</b> — 값 범위를 5등분한다. 경계 사이의 차가 {@code ceil(최대값/5)} 로 일정하다.
     *
     * <p>"폭이 균등하다"를 그대로 설명할 수 있는 대신, 치우친 분포에서는 대부분의 셀이
     * 최하위 칸에 몰린다.
     */
    EQUAL_INTERVAL("등간격"),

    /**
     * <b>등간격 (P95 상한)</b> — 자의 길이를 최대값이 아니라 <b>95번째 백분위수</b>로 잡고 5등분한다.
     *
     * <p>하위가 더 잘게 나뉘는 대신 <b>상한을 넘는 상위 5% 가 최상위 등급에 전부 뭉친다</b>
     * (100m 실측: {@code P95=14 · 최대=247} 이라 12 이상이 141칸 한 덩어리). 아무것도 버리지
     * 않는다 — 자의 눈금 밖으로 나갈 뿐 지도·순위·집계에 그대로 남는다.
     *
     * <p>⚠️ <b>{@code 95} 라는 숫자에 데이터 근거가 없다</b>(지도학의 흔한 관행을 가져온 값).
     * 비교·검토용으로 남겨 둔 선택지이며 기본값이 아니다.
     */
    EQUAL_INTERVAL_P95("등간격 P95"),

    /**
     * <b>등간격 (최솟값 기준)</b> — 🔴 <b>혼잡도 전용 규칙</b> (TSK-4090 · 2026-10-08 추가 · 같은 날 ⑪ 개정).
     * <b>심각 = 100점 초과</b>(수요가 면수를 넘는 격자)만이고, 나머지(≤ 100점)는 <b>최솟값 ~ 100점</b>을
     * 4등분해 낮음·보통·주의·경고로 나눈다.
     *
     * <pre>
     *   mn = min(등급대상 anlsVl ≤ 10000)   (anlsVl = 점수×100 정수 · 10000 = 100점)
     *   w  = (10000 − mn) / 4                -- 실수 폭
     *   경계 = [1, round(mn+w), round(mn+2w), round(mn+3w), 10001]   -- 경고 상한 100점(포함)
     *   축퇴: 100점 이하 값 없음 · w &lt; 1 → mn = 9996 (w = 1). 심각 하한 10001 은 고정
     * </pre>
     *
     * <p>⚠️ <b>쏠림을 알고 고른 선택지다</b> — 혼잡지수는 꼬리가 길어 하위 등급에 몰릴 수 있다
     * ({@link #EQUAL_INTERVAL}(0 기준)은 혼잡도에서 '주의'에 99% 가 몰려 제외됐다 — 계획서 "버린 후보").
     * 민원건수에는 허용하지 않는다({@link LifezoneTarget#getClsfMthds()} 밖 → 400).
     */
    EQUAL_INTERVAL_MIN("등간격(최솟값 기준)"),

    /**
     * <b>분위</b> — 등급대상 값의 25·50·75·90 번째 백분위수를 경계로 쓴다 (TSK-4090 · 종전 혼잡도 기본 — TSK-4227 로 허용 목록에서 빠졌다).
     *
     * <p>⚠️ 등급은 <b>선택 지역 내 상대 순위</b>일 뿐 절대 수준이 아니다.
     * 경계는 {@link Percentiles#linear} 를 반올림한 정수다({@link LifezoneGradeScale#of}).
     * 🔴 추정 0({@code noEnfYn='Y'}) 격자는 분모에서 뺀다 — 값이 몰려 분위가 무너진다(호출부 책임).
     */
    QUANTILE("분위"),

    /**
     * <b>혼잡 1.0 기준</b> — 데이터가 아니라 <b>설정값</b>({@code CNGST_GRD_BNDRY1~4})에서 경계를 받는다
     * (TSK-4090 혼잡도 전용). 절대 수준을 보려면 이쪽을 쓴다. 🔁 2026-10-08 — 설정 단위가 <b>점</b>이 됐다
     * (100점 = 지수 1.0 · 기본 88/92/96/100 · 점수에 상한이 없으므로 100점 초과도 심각). 서비스가 × 100 해
     * anlsVl 단위로 맞춘다. 이름은 그대로 둔다.
     *
     * <p>🔴 데이터에서 경계를 뽑는 {@link LifezoneGradeScale#of} 로는 산출할 수 없다 — 전용 팩토리
     * ({@link LifezoneGradeScale#ofFixed})만 받으며 {@code of} 는 이후에도 {@link IllegalArgumentException} 이다.
     */
    CNGST_ABSOLUTE("혼잡 1.0 기준");

    /**
     * 민원건수의 기본 분류방법 — {@link #JENKS}.
     *
     * <p>🔁 TSK-4090 — 요청이 {@code clsfMthd} 를 생략했을 때의 기본값은 이제 <b>유형이 정한다</b>
     * ({@link LifezoneTarget#getDfltClsfMthd()}). 이 상수는 {@link LifezoneGradeScale#of(java.util.List)}
     * 의 기본과 {@link #ofCode} 의 null 처리에만 남는다.
     */
    public static final LifezoneClsfMthd DEFAULT = JENKS;

    private final String clsfMthdNm;

    LifezoneClsfMthd(String clsfMthdNm) {
        this.clsfMthdNm = clsfMthdNm;
    }

    /** 화면 표시용 방법명 ({@code 자연분류}/{@code 등간격}). 응답 {@code clsfMthdNm} 의 출처다 */
    public String getClsfMthdNm() {
        return clsfMthdNm;
    }

    /**
     * 코드 → 상수. <b>미지원 값이면 {@code null}</b> 이다 — 호출부(서비스)가 400 으로 바꾼다.
     *
     * <p>{@code valueOf} 를 그대로 쓰지 않는 이유: {@link IllegalArgumentException} 은 전역 핸들러가
     * 없어 <b>500</b> 으로 나간다. 다른 검증({@code anlsTrgtCd}·{@code gridSz})과 같은 형태를 지킨다.
     *
     * @param code 요청 {@code clsfMthd}. {@code null}·빈 문자열이면 {@link #DEFAULT}
     */
    public static LifezoneClsfMthd ofCode(String code) {
        if (code == null || code.isBlank()) {
            return DEFAULT;
        }
        String key = code.trim().toUpperCase();
        for (LifezoneClsfMthd mthd : values()) {
            if (mthd.name().equals(key)) {
                return mthd;
            }
        }
        return null;
    }

    /** 지원 코드 목록 문자열 — 400 메시지에 붙인다 */
    public static String codes() {
        StringBuilder sb = new StringBuilder();
        for (LifezoneClsfMthd mthd : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(mthd.name());
        }
        return sb.toString();
    }
}
