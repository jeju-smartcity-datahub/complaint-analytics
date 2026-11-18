package jeju.datahub.lifezone.service;

import static jeju.datahub.lifezone.service.LifezoneClsfMthd.CNGST_ABSOLUTE;
import static jeju.datahub.lifezone.service.LifezoneClsfMthd.EQUAL_INTERVAL;

import java.util.List;

/**
 * 생활권 분석유형 — 단위·문구·가용여부의 <b>단일 출처</b> (TSK-4088 D2 · D15 · D17).
 *
 * <p>시안이 유형마다 흩어 갖고 있던 {@code unit}·{@code sectionTitle}·{@code sectionSub} 를 한
 * enum 에 모은다. 프론트가 이 값들을 <b>응답으로 받아</b> 쓰므로(D5) 문구를 두 곳에 적지 않는다.
 *
 * <p>상수명은 Step 1 이 만든 공통코드 {@code ANLS_TRGT_CD} 의 {@code cd_vl} 과
 * <b>문자 단위로 같아야 한다</b>(D15) — 요청 {@code anlsTrgtCd} 를 이 enum 으로 바로 해석한다.
 *
 * <p>🔴 <b>임계값 필드를 두지 않는다</b>(D17). 시안이 유형마다 갖고 있던 고정 임계값 3세트는 경계가 <b>조회 결과에서 산출</b>되도록 바뀌면서 폐기됐다 —
 * {@link LifezoneGradeScale} 이 그 자리를 대신한다. 수급({@code %})처럼 0~100 고정 범위가
 * 자연스러운 유형은 <b>그 유형을 구현할 때</b> 다시 정한다(지금은 미구현이라 결정을 미룬다).
 *
 * <p>{@code available=false} 인 유형으로 조회하면 서비스가 <b>400</b> 이다(D2). 그래도
 * {@code availableTargets} 목록에는 사유와 함께 실린다 — 프론트가 세그먼트를 비활성 상태로
 * 그려야 하기 때문이다.
 */
public enum LifezoneTarget {

    /** 민원건수 — 격자에 떨어진 민원 건수를 센다 */
    CVLCPT_CNT("민원건수", "건", "민원 집중 구역", "격자 기반 민원 집중 구역 (심각, 경고, 주의)",
            true, null, GridSzs.TB_GRID, 500, "",
            ClsfMthds.CVLCPT, EQUAL_INTERVAL),

    /**
     * 수급 — 공영주차 공급 대비 민원 발생량 (TSK-4089).
     *
     * <p>🔁 <b>단위가 {@code 건} → {@code %} 다(TSK-4258).</b> 값의 주축이 민원 건수에서
     * <b>부족률</b>(민원 ÷ (면수 + 민원) — 공간 집중 분석 수요 부족 지역과 같은 식)로 바뀌었다.
     * ⚠️ 그래도 확보율(수요 대비 공급 비율)은 아니다 — 주차 수요 자료(등록차량·법정주차대수·실태조사)가
     * 없어 민원을 수요의 대리값으로 쓴 비율이다({@code disclaimer} 유지).
     * 부제는 시안 문구 {@code 격자 기반 수요·공급 격차 (심각, 경고, 주의)} 다(D12).
     *
     * <p>🔁 <b>TSK-4227 로 제목 개정(D8)</b> — 제목은 {@code 주차 수급 부족 구역} 이다. 종전에는
     * "부족" 을 단정하지 않는다는 인계서 §4-4 규칙으로 {@code 주차 부족 의심 구역} 을 썼으나 사용자 결정으로
     * 되돌렸다. 바뀐 것은 제목뿐이고 면책 문구({@code disclaimer})는 그대로다(🔁 사분면은 TSK-4258 로 폐기).
     * 유형명도 {@code 수급} → {@code 주차 수급} 이다(보고서명에 들어가므로 새로 저장되는 보고서부터 바뀐다).
     *
     * <p>🔴 이 유형은 {@code GET /api/analysis/lifezone} 이 아니라
     * <b>{@code GET /api/analysis/lifezone/supply}</b> 로 조회한다 — 격자 체계·응답 형태가 달라
     * 같은 핸들러를 쓸 수 없다. 격자크기 목록은 🔁 민원건수와 같다(TSK-4206).
     *
     * <p>분류방법 목록이 비어 있다 — 🔁 등급이 부족률 등간격(D19 · 경계는 설정 표 {@code SPLY_GRD_BNDRY1~4} — D20)이라
     * 고를 분류방법이 없다(TSK-4258 · 종전 사유는 사분면 — TSK-4090).
     */
    SPLY_DMND("주차 수급", "%", "주차 수급 부족 구역", "격자 기반 수요·공급 격차 (심각, 경고, 주의)",
            true, null, LifezoneSupply.GRID_SZS, LifezoneSupply.DEFAULT_GRID_SZ, "/supply",
            List.of(), null),

    /**
     * 혼잡도 — 주차 혼잡 지수 (TSK-4090).
     *
     * <p>🔴 <b>시안이 전제한 도로 혼잡도가 아니다.</b> 도로 통행량 원천이 없어
     * 명세 7.2 의 주차 혼잡 지수 {@code (U0+E0)/S} 로 대체했다. 보조값 "피크시간 통행률" 도
     * 같은 이유로 공급·불법주차 추정으로 바뀐다.
     *
     * <p>🔴 <b>단위는 {@code 점} 이다</b> — 혼잡 점수 = {@code H × 100} (상한 없음 · 100점 초과 = 수요가 면수 초과 —
     * ⑪ 2026-10-08 상한 해제).
     * 계획서 Step 1 은 "점수로 환산하지 않는다"(대부분이 85점 근처에 몰린다)였으나 <b>2026-10-08 사용자 결정으로
     * 번복</b>했다(변경 이력 ⑩). 내부 정밀도는 점수×100({@code anlsVl})으로 둔다 — 정수 점수로 등급을 매기면
     * 동점에 경계가 무너진다. 🔁 TSK-4227 — 분류방법은 {@code CNGST_ABSOLUTE}(혼잡 1.0 기준) 1종으로 확정했다
     * (종전 TSK-4090 은 분위 기본 + 4종 선택).
     *
     * <p>🔴 격자크기는 {@code TB_GRID} 가 아니라 <b>수급과 같은 계산 격자</b>다 — 공급·단속이
     * 둘 다 점 데이터이고 매퍼를 재사용한다.
     */
    CNGST("혼잡도", "점", "혼잡 집중 구역", "격자 기반 혼잡도 분석 (심각, 경고, 주의)",
            true, null, LifezoneSupply.GRID_SZS, LifezoneSupply.DEFAULT_GRID_SZ, "/congestion",
            ClsfMthds.CNGST, CNGST_ABSOLUTE);

    /**
     * 격자크기 목록 보관용 중첩 클래스.
     *
     * <p>🔴 <b>enum 상수는 자기 enum 의 static 필드를 참조할 수 없다</b>(illegal forward reference) —
     * 상수 초기화가 static 필드보다 먼저 일어나기 때문이다. 중첩 클래스에 두면 그 시점에 이미
     * 초기화돼 있어 참조할 수 있다. ({@code LifezoneSupply.GRID_SZS} 는 다른 클래스라 그대로 쓴다.)
     */
    private static final class GridSzs {

        /**
         * {@code TB_GRID} 가 보유한 격자크기 4종. 마스터 테이블에 공간조인하는 유형이 쓴다.
         *
         * <p>수급({@code SPLY_DMND})은 격자를 계산하므로 마스터에 매이지 않지만, 🔁 목록은
         * 이것과 <b>같은 값</b>을 {@link LifezoneSupply#GRID_SZS} 에 둔다(TSK-4206).
         */
        private static final List<Integer> TB_GRID = List.of(1000, 500, 250, 100);

        private GridSzs() {
        }
    }

    /**
     * 분류방법 목록 보관용 중첩 클래스 — {@link GridSzs} 와 같은 이유(enum 상수의 forward reference)로 둔다.
     *
     * <p>🔴 <b>목록 밖 값으로 조회하면 400 이다</b>(TSK-4090).
     *
     * <p>🔁 <b>TSK-4227 — 확정(필터 제거).</b> 유형마다 <b>1종</b>만 허용한다(D1·D2) — 화면의 등급 분류 필터를
     * 걷었고 프론트는 {@code clsfMthd} 를 보내지 않는다. 🔴 {@link LifezoneClsfMthd} 의 나머지 상수는
     * <b>지우지 않는다</b> — 저장된 보고서 스냅샷({@code anls_param_json.clsfMthd})의 과거 값 해석용이다.
     */
    private static final class ClsfMthds {

        /**
         * 민원건수 — 🔁 TSK-4227 확정(필터 제거): 등간격(최댓값 5등분) 1종(D2).
         * 종전(TSK-4088·4090)은 자연분류 기본 + 3종 선택이었다.
         */
        private static final List<LifezoneClsfMthd> CVLCPT = List.of(EQUAL_INTERVAL);

        /**
         * 혼잡도 — 🔁 TSK-4227 확정(필터 제거): 혼잡 1.0 기준(설정값 경계) 1종.
         * 종전(TSK-4090)은 분위 기본 + 4종 선택이었다(등간격 계열은 등급이 한 칸에 몰려 일부 제외 — 계획서 Step 5-2 실측).
         */
        private static final List<LifezoneClsfMthd> CNGST = List.of(CNGST_ABSOLUTE);

        private ClsfMthds() {
        }
    }

    /** 분석유형명. 세그먼트 라벨 */
    private final String anlsTrgtNm;

    /** 값 단위. 범례 문구({@code 심각 (56건 이상)})와 라벨에 붙는다 */
    private final String unit;

    /** 결과 패널 제목 */
    private final String sectionTitle;

    /** 결과 패널 부제 */
    private final String sectionSub;

    /** 구현 여부. false 면 조회 시 400 (D2) */
    private final boolean available;

    /** 미구현 사유. {@code available=true} 면 null */
    private final String unavailableRsn;

    /**
     * 이 유형이 지원하는 격자크기(m) 목록.
     *
     * <p>프론트가 격자 탭을 그리는 근거다. 🔁 지금은 구현 유형 둘이 같은 목록이지만(TSK-4206)
     * 유형별 필드로 남겨 둔다 — 다른 해상도를 쓰는 유형이 생기면 프론트를 고치지 않고 갈린다.
     */
    private final List<Integer> gridSzs;

    /** 기본 격자크기(m) */
    private final int dfltGridSz;

    /**
     * 조회 경로 접미사 — {@code /api/analysis/lifezone} 뒤에 붙는다.
     *
     * <p>🔴 유형마다 <b>파이프라인이 다르다.</b> 수급은 격자 체계(계산 격자)·기간 컬럼
     * ·응답 형태(사분면)가 달라 {@code /supply} 라는 별도 핸들러가 진다.
     * 이 값이 있는 유형을 기본 핸들러로 보내면 <b>민원건수 분석 결과가 "수급" 이라는 이름표를
     * 달고 나간다</b> — 200 으로 나가는 조용한 오답이라, 기본 핸들러가 이 값으로 막는다.
     *
     * <p>프론트도 이 값으로 호출 경로를 고른다 — 경로를 화면에 박지 않기 위해서다.
     */
    private final String anlsPathSfx;

    /**
     * 이 유형이 허용하는 등급 분류방법 목록 (TSK-4090). 목록 밖 값으로 조회하면 400 이다.
     * 등급 분류가 없는 유형(수급 — 사분면)은 빈 목록이다.
     */
    private final List<LifezoneClsfMthd> clsfMthds;

    /** 요청이 {@code clsfMthd} 를 생략했을 때의 기본 분류방법. 등급 분류가 없는 유형이면 {@code null} */
    private final LifezoneClsfMthd dfltClsfMthd;

    LifezoneTarget(String anlsTrgtNm, String unit, String sectionTitle, String sectionSub,
                   boolean available, String unavailableRsn,
                   List<Integer> gridSzs, int dfltGridSz, String anlsPathSfx,
                   List<LifezoneClsfMthd> clsfMthds, LifezoneClsfMthd dfltClsfMthd) {
        this.anlsTrgtNm = anlsTrgtNm;
        this.unit = unit;
        this.sectionTitle = sectionTitle;
        this.sectionSub = sectionSub;
        this.available = available;
        this.unavailableRsn = unavailableRsn;
        this.gridSzs = gridSzs;
        this.dfltGridSz = dfltGridSz;
        this.anlsPathSfx = anlsPathSfx;
        this.clsfMthds = clsfMthds;
        this.dfltClsfMthd = dfltClsfMthd;
    }

    public String getAnlsTrgtNm() {
        return anlsTrgtNm;
    }

    public String getUnit() {
        return unit;
    }

    public String getSectionTitle() {
        return sectionTitle;
    }

    public String getSectionSub() {
        return sectionSub;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getUnavailableRsn() {
        return unavailableRsn;
    }

    public List<Integer> getGridSzs() {
        return gridSzs;
    }

    public int getDfltGridSz() {
        return dfltGridSz;
    }

    public String getAnlsPathSfx() {
        return anlsPathSfx;
    }

    public List<LifezoneClsfMthd> getClsfMthds() {
        return clsfMthds;
    }

    public LifezoneClsfMthd getDfltClsfMthd() {
        return dfltClsfMthd;
    }

    /** 기본 핸들러({@code GET /api/analysis/lifezone})가 처리하는 유형인지 */
    public boolean isDefaultHandled() {
        return anlsPathSfx.isEmpty();
    }

    /**
     * 분석내용코드를 유형으로 해석한다.
     *
     * <p>미지원 코드면 {@code null} 이다 — <b>400 판정은 서비스가 한다</b>(전역 예외 핸들러가 없어
     * 여기서 예외를 던지면 500 으로 나간다).
     *
     * @param anlsTrgtCd 분석내용코드. {@code null}·공백이면 {@code null}
     */
    public static LifezoneTarget ofCode(String anlsTrgtCd) {
        if (anlsTrgtCd == null || anlsTrgtCd.isBlank()) {
            return null;
        }
        for (LifezoneTarget target : values()) {
            if (target.name().equals(anlsTrgtCd)) {
                return target;
            }
        }
        return null;
    }
}
