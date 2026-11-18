package jeju.datahub.lifezone.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 수급 분석 결과.
 *
 * <p>민원건수 분석의 {@link LifezoneAnalysisResultVO} 와 <b>같은 타입을 쓰지 않는다</b> —
 * 5등급(순서형) ↔ 4사분면(범주형)이라 요약 필드·범례·셀 지표가 전부 다르다. 억지로 합치면
 * 한쪽에서 항상 비어 있는 필드가 절반씩 생긴다.
 *
 * <p>응답은 조회 조건을 <b>되비추고</b> 화면 문구(제목·단위·범례·안내문)까지 함께 내린다 —
 * 민원건수 분석과 같은 설계다. 응답 하나로 화면을 그리고 결과를 설명할 수 있게 하려는 것.
 *
 * <p>🔁 <b>사분면 → 부족률 등간격 5단계.</b> 신규 필드({@link #anlsMthd}·등급별 수·
 * {@link #zeroSplyGridCnt}·{@link #minMnthAvgCvlcpt}·{@link #gradeCriteria})는 신규 분기(조회·생성 응답·신규 스냅샷)에서
 * <b>항상 채운다</b>(0건이면 {@code 0}, 범례는 5행). null 은 구 스냅샷 역직렬화에서만 남아 키가 생략된다.
 * 구 필드({@link #cvlcptHighThld}·{@link #splyLowThld}·{@link #qdrnCriteria}·{@code q1Cnt~q4Cnt})는
 * <b>구 스냅샷 전용</b>으로 남긴다 — 래퍼형·초기값 {@code null} 이라 신규 응답에서 키가 생략된다.
 * 🔴 NON_NULL 은 <b>필드 단위</b>다 — 클래스 단위로 걸면 {@code sggNm}·{@code emdCd} 처럼
 * "null 이 곧 전 지역" 인 키까지 사라진다({@code HotspotReportVO} 주석 관례).
 * 🔴 신규 숫자 필드를 {@code int} 로 두지 않는다 — 구 스냅샷에서 {@code 0} 으로 채워져 "0건" 으로 읽힌다.
 */
@Schema(description = "생활권 수급 분석 결과")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneSupplyResultVO {

    /* ── 조회 조건 되비추기 ─────────────────────────── */

    /** 분석 시작일 (YYYYMMDD) */
    @Schema(description = "분석 시작일 (YYYYMMDD)", example = "20250101")
    private String bgngYmd;

    /** 분석 종료일 (YYYYMMDD) */
    @Schema(description = "분석 종료일 (YYYYMMDD)", example = "20250831")
    private String endYmd;

    /** 시군구명 (전 지역이면 null) */
    @Schema(description = "시군구명 (전 지역이면 null)", example = "제주시", nullable = true)
    private String sggNm;

    /** 읍면동코드 (시군구 전체면 null) */
    @Schema(description = "읍면동코드 (시군구 전체면 null)", example = "50110137", nullable = true)
    private String emdCd;

    /** 분석내용코드. 항상 {@code SPLY_DMND} */
    @Schema(description = "분석내용코드", example = "SPLY_DMND")
    private String anlsTrgtCd;

    /** 분석유형명 */
    @Schema(description = "분석유형명", example = "주차 수급")
    private String anlsTrgtNm;

    /** 격자크기(m) */
    @Schema(description = "격자크기(m)", example = "500")
    private int gridSz;

    /** 격자크기 표시명 */
    @Schema(description = "격자크기 표시명", example = "500m")
    private String gridSzNm;

    /** 격자 좌표계. 프론트가 폴리곤을 되돌려 그릴 때 근거가 된다 */
    @Schema(description = "격자 좌표계", example = "EPSG:5179")
    private String crs;

    /* ── 화면 문구 ─────────────────────────────────── */

    /** 결과 패널 제목 */
    @Schema(description = "결과 패널 제목", example = "주차 수급 부족 구역")
    private String sectionTitle;

    /** 결과 패널 부제 */
    @Schema(description = "결과 패널 부제", example = "격자 기반 수요·공급 격차 (심각, 경고, 주의)")
    private String sectionSub;

    /**
     * 🔴 화면에 <b>상시</b> 표기해야 하는 안내 문구.
     * 이 값을 화면에서 빼면 산출물이 "주차 부족 면수" 로 읽힌다.
     */
    @Schema(description = "상시 표기 안내 문구",
            example = "공영주차장 기준이며 민영·부설주차장은 포함되지 않습니다. "
                    + "민원 발생량 기준으로, 주차 수요를 직접 측정한 값이 아닙니다.")
    private String disclaimer;

    /**
     * 🔴 100m 격자 해석 주의 안내. 100m 에서만 {@code LifezoneSupply.SMALL_GRID_NOTICE},
     * 그 밖 격자크기는 {@code null} 이라 키가 생략된다.
     */
    @Schema(description = "격자 해석 주의 안내 (100m 에서만 · 그 밖은 생략)", nullable = true,
            example = "100m 격자는 대부분 공영주차장이 없어(공급 0면) 심각·경고·주의 등급이 "
                    + "주차장이 없는 격자 중 민원이 많은 순으로 채워집니다. 해석에 주의하세요.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String gridNotice;

    /* ── 등급 · 요약 ────────────────────── */

    /**
     * 분석 방법 코드 — 항상 {@code SHRT_RT}({@code LifezoneSupply.ANLS_MTHD_SHRT_RT}).
     *
     * <p>🔴 조회 응답에서도 채운다 — 프론트 저장 실패(unsaved) 경로가 조회 결과로 보고서를 그리기 때문이다.
     * 구 스냅샷(사분면)만 이 값이 없다(키 생략) — 프론트는 {@code anlsMthd === 'SHRT_RT'} 로 렌더러를 고른다.
     */
    @Schema(description = "분석 방법 코드 (SHRT_RT = 부족률 등간격 5단계 · 구 스냅샷은 생략)", example = "SHRT_RT")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String anlsMthd;

    /** 심각 격자 수 (부족률 80% 이상) */
    @Schema(description = "심각 격자 수 (부족률 80% 이상)", example = "17")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer severeCnt;

    /** 경고 격자 수 (60~80%) */
    @Schema(description = "경고 격자 수 (부족률 60~80%)", example = "25")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer warnCnt;

    /** 주의 격자 수 (40~60%) */
    @Schema(description = "주의 격자 수 (부족률 40~60%)", example = "43")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer cautionCnt;

    /** 보통 격자 수 (20~40%) */
    @Schema(description = "보통 격자 수 (부족률 20~40%)", example = "42")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer normalCnt;

    /** 낮음 격자 수 (20% 미만) */
    @Schema(description = "낮음 격자 수 (부족률 20% 미만)", example = "43")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer lowCnt;

    /**
     * 공영주차면 0 인 격자 수. 이 비율이 높으면 상위 등급이 "주차장 없는 격자 중 민원 많은 순" 이 된다.
     */
    @Schema(description = "공영주차면 0 인 격자 수", example = "36")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer zeroSplyGridCnt;

    /**
     * 모집단 최소 기준 — 월평균 민원 N건 이상 격자만 평가 (설정 표 {@code SPLY_MIN_MNTH_AVG_CVLCPT}, 기본 5).
     * 범례에 "월평균 민원 N건 이상 격자만 평가" 를 띄워 걸러진 격자가 "민원 없음" 으로 오해되지 않게 한다.
     */
    @Schema(description = "모집단 최소 기준 — 월평균 민원 건수", example = "5")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer minMnthAvgCvlcpt;

    /**
     * 등급 범례 5행 (심각 → 낮음 · 0건이어도 남는다). 🔴 초기값 {@code null} — 구 스냅샷에서 빈 배열로
     * 나가지 않고 키가 생략되게 한다. 신규 분기는 항상 5행이다.
     */
    @Schema(description = "등급 범례 5행 (부족률 등간격 — 심각 → 낮음)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<LifezoneSupplyGradeCriterionVO> gradeCriteria;

    /**
     * 분석 계수 적용 내역 — {@code {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}}.
     * 수급 탭 허용 목록 5행을 담는다(조정 안 한 행도 담는다). 문서에는 그리지 않는다.
     *
     * <p>🔴 <b>필드 초기값을 두지 않는다</b> — 이 값을 저장하기 전 보고서 스냅샷은 {@code null}(키 생략)이다.
     * 조회·생성 응답·신규 스냅샷은 항상 채운다.
     */
    @Schema(description = "분석 계수 적용 내역 {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]} "
            + "— 수급 5행. 이 값을 저장하기 전 보고서는 생략", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> vrblAjmt;

    /**
     * 🔁 총 격자 수 (= 셀 수 = 순위의 최댓값 N). 주차 민원 <b>월평균 최소 기준을 넘은</b> 격자만 센다.
     */
    @Schema(description = "총 격자 수 (월평균 최소 기준 통과 격자)", example = "170")
    private int totGridCnt;

    /**
     * 🔁 총 민원 건수 — <b>최소 기준 통과 격자의 합</b>(민원 유형 01~05).
     *
     * <p>⚠️ 종전 "격자크기를 바꿔도 같아야 한다"(합계 보존)는 <b>더 이상 성립하지 않는다</b> —
     * 최소 기준이 격자 단위로 걸려 격자크기마다 걸러지는 민원이 다르다.
     */
    @Schema(description = "총 민원 건수 (최소 기준 통과 격자의 합 · 격자크기마다 다를 수 있다)", example = "6120")
    private int totCvlcptCnt;

    /** 🔁 총 공영주차면 수 — 최소 기준 통과 격자의 합 (주차장 유형 PBLPKLT·ONSTR·VACLOT) */
    @Schema(description = "총 공영주차면 수 (최소 기준 통과 격자의 합)", example = "9480")
    private int totPkltCpctySum;

    /* ── 구 스냅샷(사분면) 전용 — ─────────────
     * 서비스는 더 이상 채우지 않는다. 🔴 래퍼형·초기값 null 이어야 NON_NULL 로 실제 생략된다
     * (원시형이면 0 이, new ArrayList 면 [] 가 나간다). 보고서 서비스·테스트가 아직 getter 를 쓴다. */

    /**
     * 민원高 임계값 P80 <b>원값</b>.
     *
     * <p>🔴 보간 결과라 정수가 아닐 수 있다(300m = 23.4). <b>반올림해 저장하지 않는다</b> —
     * 경계 판정이 달라진다. 표시할 때만 올림한다.
     *
     * @deprecated 구 스냅샷 전용.
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) 민원高 임계값 P80 (원값)", example = "29.0", deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double cvlcptHighThld;

    /**
     * 공급低 임계값 P70 <b>원값</b>.
     *
     * @deprecated 구 스냅샷 전용.
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) 공급低 임계값 P70 (원값)", example = "10.0", deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double splyLowThld;

    /**
     * 사분면 범례 4행 (Q1~Q4 · 0건이어도 남는다).
     *
     * @deprecated 구 스냅샷 전용. 🔴 초기값 {@code null}.
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) 사분면 범례 4행 (Q1~Q4)", deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<LifezoneQuadrantCriterionVO> qdrnCriteria;

    /**
     * Q1 격자 수 (부족 의심).
     *
     * @deprecated 구 스냅샷 전용.
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) Q1 격자 수", example = "44", deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer q1Cnt;

    /**
     * Q2 격자 수.
     *
     * @deprecated 구 스냅샷 전용.
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) Q2 격자 수", example = "159", deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer q2Cnt;

    /**
     * Q3 격자 수.
     *
     * @deprecated 구 스냅샷 전용.
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) Q3 격자 수", example = "662", deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer q3Cnt;

    /**
     * Q4 격자 수.
     *
     * @deprecated 구 스냅샷 전용.
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) Q4 격자 수", example = "143", deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer q4Cnt;

    /* ── 원천 보유 구간 ─────────────────────────────── */

    /**
     * 원천 보유 시작일 (YYYYMMDD).
     *
     * <p>기준 컬럼은 {@code ocrn_dt}(발생일시)로 민원건수 분석과 <b>같다</b>(🔁 2026-09-13).
     * ⚠️ 다만 집계 조건이 달라(민원건수는 미분류 제외·좌표 무관 / 수급은 🔁 유형 01~05·좌표 유효 범위만)
     * 값이 조금 다를 수 있다.
     */
    @Schema(description = "원천 보유 시작일 (YYYYMMDD)", example = "20250101", nullable = true)
    private String dataBgngYmd;

    /** 원천 보유 종료일 (YYYYMMDD) */
    @Schema(description = "원천 보유 종료일 (YYYYMMDD)", example = "20250831", nullable = true)
    private String dataEndYmd;

    /** 🔁 격자 셀 목록 (최소 기준 통과 격자 전량 · 부족 순위 {@code rnk} 오름차순) */
    @Schema(description = "격자 셀 목록 (월평균 최소 기준 통과 격자 전량 · rnk 오름차순)")
    private List<LifezoneSupplyCellVO> cells = new ArrayList<>();
}
