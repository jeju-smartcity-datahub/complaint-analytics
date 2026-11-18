package jeju.datahub.lifezone.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 수급 분석의 <b>고정 상수 단일 출처</b>.
 *
 * <p>산출 로직 원본이 정한 값들이다. 원본은 같은
 * 폴더의 {@code code/agent1_grid_ui.py} 이며, 이 클래스의 숫자를 바꾸면 검증
 * 기준값과 어긋난다 — 바꾸려면 기준값을 함께 다시 만들어야 한다.
 *
 * <p>🔁 <b>사분면(P80·P70) 판정을 폐기했다.</b> 등급은 부족률 등간격 5단계
 * ({@link #GRADE_SHRT_RT_SEVERE} 등 — 종전 순위 백분위 폐기)다.
 *
 * <p>🔁 <b>경계 4개·최소 월평균 민원은 설정 표가 정본이다.</b>
 * {@code parking.tb_anls_vrbl_stng}({@code anls_type_cd='LIFEZONE'}·{@code anls_trgt_cd='SPLY_DMND'} 5행 —
 * 마이그레이션 91)을 관리 화면(M0603)에서 바꾼다. 여기 남은 숫자({@link #GRADE_SHRT_RT_SEVERE}~{@link #GRADE_SHRT_RT_NORMAL}·
 * {@link #MIN_MNTH_AVG_CVLCPT})는 <b>정본이 아니라 폴백 기본값</b>이다 — 설정을 읽을 수 없을 때만 쓴다.
 * 요청 파라미터로는 여전히 열지 않는다(화면 조작이 곧 기준 변경이 되지 않게).
 */
public final class LifezoneSupply {

    /**
     * 격자크기(m) 허용 목록 — 🔁 <b>민원건수 분석과 같다</b>.
     *
     * <p>종전에는 {@code 100/200/300/500} 이었는데, 같은 화면에서 분석 유형을 바꾸면
     * 격자 탭이 통째로 달라져 두 결과를 같은 격자로 비교할 수 없었다. 수급은 격자를 <b>계산</b>하므로
     * ({@code floor(좌표 / 격자크기)}) 해상도에 제약이 없어 민원건수({@code TB_GRID} 보유분)에 맞췄다.
     * ⚠️ 그 대가로 기준값 중 200·300m 행은 API 로 재현할 수 없게 됐다 — 테스트는
     * 남은 100·500m 와 새로 실측한 250·1000m 로 대조한다.
     */
    public static final List<Integer> GRID_SZS = List.of(1000, 500, 250, 100);

    /** 기본 격자크기(m). 화면의 기존 기본값과 맞춘 값이다(권장은 300) */
    public static final int DEFAULT_GRID_SZ = 500;

    /*
     * ── 등급 경계 — 부족률 등간격 ─────────────────
     *
     * 부족률 0~100% 를 20% 폭으로 고정 5등분하고, 아래 경계 포함(≥)이다. 판정은 반올림 전 정수 비교로
     * 한다(LifezoneGrade#ofShrtRt — 민원 × 100 ≥ 경계 × 수요지수). 그 밖(20% 미만)은 낮음.
     * 🔁 종전 순위 백분위(10/25/50/75)는 부족률 100% 격자가 심각·경고로 갈려(같은 값 다른 등급) 폐기했다.
     * 순위(rnk)는 리스트 순서에만 쓴다.
     *
     * 아래 4개는 폴백 기본값이다. 정본은 설정 표 SPLY_GRD_BNDRY1~4(보통·주의·경고·심각 하한)이고,
     * 마이그레이션 91 의 bsc_stng_vl 과 같은 값이어야 한다(한쪽만 바꾸면 "기본값 복원" 과 폴백이 갈린다).
     */

    /** 심각 — 부족률 80% 이상 */
    public static final int GRADE_SHRT_RT_SEVERE = 80;

    /** 경고 — 60~80% */
    public static final int GRADE_SHRT_RT_WARN = 60;

    /** 주의 — 40~60% */
    public static final int GRADE_SHRT_RT_CAUTION = 40;

    /** 보통 — 20~40%. 그 밖(20% 미만)은 낮음 */
    public static final int GRADE_SHRT_RT_NORMAL = 20;

    /**
     * 모집단 최소 기준 — 주차 민원 <b>월평균 5건 이상</b> 격자만 평가한다.
     *
     * <p>판정은 매퍼 {@code HAVING count(*) × }{@link #MNTH_DAYS}{@code  >= 이 값 × 조회 일수} 다(정수 비교).
     * 31일이면 6건부터다. 민원 1건 격자가 160건 격자와 같은 "공급 0 · 부족률 100%" 로 심각이 되는 것을
     * 막는다. 응답 {@code minMnthAvgCvlcpt} 로 내려 범례에 "월평균 민원 5건 이상 격자만 평가" 를 띄운다 —
     * 걸러진 격자가 "민원 없음" 으로 오해되지 않게.
     * 폴백 기본값이다. 정본은 설정 표 {@link #VRBL_MIN_MNTH_AVG_CVLCPT}.
     */
    public static final int MIN_MNTH_AVG_CVLCPT = 5;

    /*
     * ── 설정 표 변수코드 (마이그레이션 91) ─────────────────────
     *
     * parking.tb_anls_vrbl_stng 의 (anls_type_cd='LIFEZONE', vrbl_cd) 키다. 저장 검증
     * (AnalysisWeightServiceImpl — 정수·BNDRY1<…<BNDRY4)도 같은 이름을 본다 — 한쪽만 바꾸지 않는다.
     */

    /** 설정 표 분석유형코드 — 혼잡도와 같은 {@code LIFEZONE} 이다(🔴 보고서의 {@code LIFEZONE_SPLY} 가 아니다) */
    public static final String STNG_ANLS_TYPE_CD = "LIFEZONE";

    /** 최소 월평균 민원 (기본 {@link #MIN_MNTH_AVG_CVLCPT}건 · 범위 1~30 · 정수) */
    public static final String VRBL_MIN_MNTH_AVG_CVLCPT = "SPLY_MIN_MNTH_AVG_CVLCPT";

    /** 등급 경계 변수코드 접두 — {@code 1}~{@code 4} = 보통·주의·경고·심각 하한(%, 오름차순 · 정수) */
    public static final String VRBL_GRD_BNDRY_PREFIX = "SPLY_GRD_BNDRY";

    /**
     * 부족률 등급 경계 4개 — 보통·주의·경고·심각 <b>하한</b>(%, 포함).
     *
     * <p>설정 표 {@code SPLY_GRD_BNDRY1~4} 와 같은 오름차순이다(혼잡도 {@code CNGST_GRD_BNDRY1~4} 와 같은 방향).
     * 🔴 정수다 — 판정이 {@code 민원 × 100 ≥ 경계 × 수요지수} 정수 비교라 소수 경계를 담을 칸이 없다.
     * 생성 시 {@code 1 ≤ normal < caution < warn < severe ≤ 99} 를 검사한다 — 저장 검증이 막지만,
     * DB 를 직접 고친 값이 등급을 뒤집지 않게 한 번 더 본다(호출부가 잡아 폴백한다).
     */
    public record GrdBndry(int normal, int caution, int warn, int severe) {

        /** 폴백 기본값 — 20·40·60·80 */
        public static final GrdBndry DEFAULT = new GrdBndry(
                GRADE_SHRT_RT_NORMAL, GRADE_SHRT_RT_CAUTION, GRADE_SHRT_RT_WARN, GRADE_SHRT_RT_SEVERE);

        public GrdBndry {
            if (normal < 1 || severe > 99 || !(normal < caution && caution < warn && warn < severe)) {
                throw new IllegalArgumentException(
                        "수급 등급 경계는 1 ≤ BNDRY1 < BNDRY2 < BNDRY3 < BNDRY4 ≤ 99 여야 합니다: "
                                + normal + ", " + caution + ", " + warn + ", " + severe);
            }
        }

        /** 등급의 부족률 하한(%, 포함) — 낮음은 0 */
        public int minOf(LifezoneGrade grade) {
            return switch (grade) {
                case SEVERE -> severe;
                case WARN -> warn;
                case CAUTION -> caution;
                case NORMAL -> normal;
                case LOW -> 0;
            };
        }

        /** 설정 표 순서(BNDRY1~4 = 오름차순) — 스냅샷 {@code crtrVls.grdBndrys} 의 모양 */
        public List<Integer> toList() {
            return List.of(normal, caution, warn, severe);
        }
    }

    /** 월평균 환산의 한 달 일수. 고정 건수가 아니라 월평균으로 한 이유는 조회 기간마다 의미가 바뀌어서다 */
    public static final int MNTH_DAYS = 30;

    /**
     * 🔴 100m 격자 해석 주의 안내 (응답 {@code gridNotice}, 100m 에서만).
     *
     * <p>100m 는 최소 기준 뒤에도 공급 0 격자가 약 71%(2026-09 실측)라 심각~주의가 사실상
     * "주차장 없는 격자 중 민원이 많은 순" 이 된다. 계산 오류가 아니라 구조적 현상이라 막지 않고 알린다.
     * 문구는 프론트에 두지 않고 응답으로 내린다({@link #DISCLAIMER} 와 같은 이유).
     */
    public static final String SMALL_GRID_NOTICE =
            "100m 격자는 대부분 공영주차장이 없어(공급 0면) 부족률 100%인 심각 등급이 "
                    + "많이 나옵니다. 해석에 주의하세요.";

    /**
     * 분석 방법 코드 — 부족률 등간격 5단계.
     *
     * <p>🔴 조회 응답·생성 응답·스냅샷 {@code anlsMthd} 의 <b>유일한 출처</b>다. 구 스냅샷(사분면)에는
     * 이 값이 없어 프론트가 {@code anlsMthd === 'SHRT_RT'} 로 렌더러를 고른다.
     */
    public static final String ANLS_MTHD_SHRT_RT = "SHRT_RT";

    /**
     * 격자 좌표계. 정방격자를 만들려면 투영좌표여야 한다 — 위경도에서 바로 정사각형을 그리면
     * 위도에 따라 가로 길이가 달라져 실제와 어긋난다.
     */
    public static final String CRS = "EPSG:5179";

    /**
     * 🔴 <b>화면에 상시 표기해야 하는 문구</b>. 이 산출물은 "주차 부족 면수"가 아니다 —
     * 주차 수요 자료(등록차량·법정주차대수·실태조사)가 없어 확보율을 계산할 수 없다.
     * 프론트에 적지 않고 <b>응답으로 내린다</b> — 문구를 두 곳에 두지 않기 위해서다.
     */
    public static final String DISCLAIMER =
            "공영주차장 기준이며 민영·부설주차장은 포함되지 않습니다. "
                    + "민원 발생량 기준으로, 주차 수요를 직접 측정한 값이 아닙니다.";

    /**
     * 소수 지점 집중 주의 판정 — {@code distinctPoints <= 3 && complaints >= 50}.
     *
     * <p>민원 좌표가 지번 중심점 기준이라 한두 지번에 몰린 격자는 실제 발생 위치가 더 넓게
     * 퍼져 있을 수 있다. 🔴 <b>판정을 프론트에 두지 않는다</b> — 두 곳에 두면 갈린다.
     */
    public static final int CNCNT_WARN_MAX_DSTNC_PT = 3;

    /** 소수 지점 집중 주의 판정의 민원 건수 하한 */
    public static final int CNCNT_WARN_MIN_CVLCPT = 50;

    private LifezoneSupply() {
    }

    /**
     * 조회 일수 = {@code end − bgng + 1} (당일 포함 · 최소 월평균 기준의 환산 재료).
     *
     * <p>⚠️ 같은 계산이 {@code LifezoneCngstServiceImpl} 에도 있다(이번엔 통합하지 않았다).
     *
     * @param bgng 분석 시작일 (포함)
     * @param end  분석 종료일 (포함)
     */
    public static int prdDaysOf(LocalDate bgng, LocalDate end) {
        return Math.toIntExact(ChronoUnit.DAYS.between(bgng, end) + 1);
    }

    /**
     * 격자크기 표시명. 민원건수 분석과 같은 규칙으로 <b>1000 만 {@code 1km}</b> 이고 나머지는 {@code {n}m} 다
     * (1000m 가 생기면서 {@code 1000m} 로 새지 않게 맞췄다).
     */
    public static String gridSzNm(int gridSz) {
        return gridSz == 1000 ? "1km" : gridSz + "m";
    }
}
