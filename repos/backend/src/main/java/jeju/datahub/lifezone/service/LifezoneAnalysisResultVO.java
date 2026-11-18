package jeju.datahub.lifezone.service;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 격자 분석 응답 루트. (GET /api/analysis/lifezone)
 *
 * <p>조회 조건을 그대로 되비추고(echo) 분석유형 문구·구간 경계·원천 보유 구간까지 함께 담는다 —
 * <b>같은 응답만으로 결과를 재현·설명할 수 있게</b> 하려는 의도다. 보고서 스냅샷(Step 11)이
 * 이 응답을 통째로 저장하므로, 여기 없는 값은 나중에 재현할 수 없다.
 *
 * <p>🔴 {@link #gradeScale}·{@link #gradeCriteria} 는 <b>조회마다 값이 달라진다</b>(D17) —
 * 경계를 조회 결과의 <b>최대값</b>에서 로그 등간격으로 산출하기 때문이다.
 * 프론트 범례는 전적으로 이 두 키로 조립된다.
 * 그래서 결과가 <b>0건이어도 반드시 채워 보낸다</b>({@code cap=0, width=1}, 경계 {@code 1·2·3·4·5}).
 *
 * <p>🔴 {@link #cells} 와 {@link #ranking} 은 <b>서로 다른 집합</b>이다 — 셀은 값&gt;0 전량(D5),
 * 순위는 <b>심각·경고·주의</b> 셀 전부(🔁 TSK-4227 D21 — {@code limit} 지정 시 그 값까지)다. 등급별 카운트 5개의 합은 {@link #totGridCnt} 이자
 * {@code cells} 의 길이와 같다.
 *
 * <p>{@link #cells} 가 <b>빈 배열이어도 200</b> 이다 — 조건에 데이터가 없는 정상 상태다(D11).
 * 이때도 원천 보유 구간({@link #dataBgngYmd}·{@link #dataEndYmd})을 채워 안내한다.
 */
@Schema(description = "생활권 격자 분석 결과")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneAnalysisResultVO {

    /** 요청한 분석 시작일 (YYYYMMDD) */
    @Schema(description = "분석 시작일 (YYYYMMDD)", example = "20251201")
    private String bgngYmd;

    /** 요청한 분석 종료일 (YYYYMMDD, 당일 포함) */
    @Schema(description = "분석 종료일 (YYYYMMDD)", example = "20251231")
    private String endYmd;

    /** 요청한 시군구명. 전 지역이면 null */
    @Schema(description = "시군구명 (전 지역이면 null)", example = "제주시")
    private String sggNm;

    /** 요청한 읍면동코드. 시군구 전체면 null */
    @Schema(description = "읍면동코드 (시군구 전체면 null)", example = "50110560")
    private String emdCd;

    /** 적용된 분석내용코드. {@link LifezoneTarget} 상수명 */
    @Schema(description = "분석내용코드", example = "CVLCPT_CNT")
    private String anlsTrgtCd;

    /** 분석유형명. {@link LifezoneTarget#getAnlsTrgtNm()} */
    @Schema(description = "분석유형명", example = "민원건수")
    private String anlsTrgtNm;

    /** 적용된 격자크기(m). 재현용으로 되비춘다 */
    @Schema(description = "격자크기(m)", example = "500")
    private int gridSz;

    /** 격자크기 표시명. {@code 1km}·{@code 500m} 처럼 화면이 쓰는 라벨 */
    @Schema(description = "격자크기 표시명", example = "500m")
    private String gridSzNm;

    /**
     * 적용된 등급 분류방법. {@link LifezoneClsfMthd} 상수명 (예: {@code EQUAL_INTERVAL}).
     *
     * <p>{@code gradeScale.method} 와 <b>항상 같은 값</b>이다 — 조건 echo 는 다른 조건들과 나란히
     * 루트에 두고, {@code gradeScale} 쪽은 "경계가 어떻게 나왔나"를 설명하는 묶음으로 남긴다.
     */
    @Schema(description = "등급 분류방법", example = "EQUAL_INTERVAL")
    private String clsfMthd;

    /** 분류방법명. {@link LifezoneClsfMthd#getClsfMthdNm()} — 화면 문구를 프론트에 박지 않는다 */
    @Schema(description = "분류방법명", example = "등간격")
    private String clsfMthdNm;

    /** 값 단위. 범례·라벨 문구에 붙는다. {@link LifezoneTarget#getUnit()} */
    @Schema(description = "값 단위", example = "건")
    private String unit;

    /** 결과 패널 제목. {@link LifezoneTarget#getSectionTitle()} — 문구를 프론트에 박지 않는다 */
    @Schema(description = "결과 패널 제목", example = "민원 집중 구역")
    private String sectionTitle;

    /** 결과 패널 부제. {@link LifezoneTarget#getSectionSub()} */
    @Schema(description = "결과 패널 부제", example = "격자 기반 민원 집중 구역")
    private String sectionSub;

    /** 분석유형 가용 목록 3종 (미구현 유형 포함 — D2). {@link LifezoneTargetVO#allOf()} */
    @Schema(description = "분석유형 가용 목록 (미구현 유형 포함)")
    private List<LifezoneTargetVO> availableTargets = new ArrayList<>();

    /** 구간 경계 산출 정보 {@code {method, cap, width}} — 재현·설명용 (D17) */
    @Schema(description = "구간 경계 산출 정보 (재현용)")
    private LifezoneGradeScale gradeScale;

    /** 등급 판정 기준 5종. 범례 문구까지 서버가 만들어 내린다 (D5 · D17) */
    @Schema(description = "등급 판정 기준 5종 (경계 내림차순)")
    private List<LifezoneGradeCriterionVO> gradeCriteria = new ArrayList<>();

    /**
     * 적용 가중치 — <b>항상 빈 배열</b>이다 (D8).
     *
     * <p>생활권은 분석유형을 <b>배타 선택</b>하므로 지표를 섞지 않는다 — 가중치를 쓸 자리가 없다.
     * 그래도 키를 내리는 이유는 분석 응답의 모양을 집중구역과 맞춰 프론트·내보내기가 같은
     * 처리를 쓰게 하려는 것이다.
     *
     * <p>⚠️ 타입을 집중구역의 {@code AnalysisWeightVO} 로 두지 <b>않는다</b> — 한 건도 싣지 않는
     * 목록 때문에 집중구역 패키지로의 의존이 생기는 것을 피한다. 가중치를 실제로 쓰게 되는 날
     * 그 유형과 함께 타입을 정한다.
     */
    @Schema(description = "적용 가중치 (배타 선택이라 항상 빈 배열)")
    private List<Object> weights = new ArrayList<>();

    /** 값&gt;0 격자 수 = 그리는 셀 수. 등급별 카운트 5개의 합과 같다 (D5) */
    @Schema(description = "값>0 격자 수 (= 셀 수)", example = "597")
    private int totGridCnt;

    /** 심각 격자 수 */
    @Schema(description = "심각 격자 수", example = "35")
    private int severeCnt;

    /** 경고 격자 수 */
    @Schema(description = "경고 격자 수", example = "19")
    private int warnCnt;

    /** 주의 격자 수 */
    @Schema(description = "주의 격자 수", example = "23")
    private int cautionCnt;

    /** 보통 격자 수 */
    @Schema(description = "보통 격자 수", example = "53")
    private int normalCnt;

    /** 낮음 격자 수 */
    @Schema(description = "낮음 격자 수", example = "467")
    private int lowCnt;

    /** 원천 보유 시작일 (YYYYMMDD). 0건 안내용(D11). 원천이 비면 null */
    @Schema(description = "원천 보유 시작일 (YYYYMMDD)", example = "20251104")
    private String dataBgngYmd;

    /** 원천 보유 종료일 (YYYYMMDD). 0건 안내용(D11). 원천이 비면 null */
    @Schema(description = "원천 보유 종료일 (YYYYMMDD)", example = "20251231")
    private String dataEndYmd;

    /** 격자 셀 목록 — 값&gt;0 전량, 분석값 내림차순. 빈 배열도 정상이다(D11) */
    @Schema(description = "격자 셀 목록 (값>0 전량)")
    private List<LifezoneCellVO> cells = new ArrayList<>();

    /** 순위 목록 — 심각·경고·주의 셀 전부(limit 지정 시 그 값까지 · TSK-4227 D21). 빈 배열도 정상이다(D11) */
    @Schema(description = "순위 목록 (심각·경고·주의 셀 전부 · limit 지정 시 그 값까지 — TSK-4227)")
    private List<LifezoneRankVO> ranking = new ArrayList<>();
}
