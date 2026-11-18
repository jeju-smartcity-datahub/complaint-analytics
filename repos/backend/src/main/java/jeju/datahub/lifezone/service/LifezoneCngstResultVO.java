package jeju.datahub.lifezone.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 혼잡도(주차 혼잡 지수) 분석 결과. (응답)
 *
 * <p>🔴 <b>민원건수 응답({@link LifezoneAnalysisResultVO})의 필드를 전부 같은 이름으로 싣는다</b> —
 * 프론트가 민원건수 컴포넌트를 재사용한다. 그러나 {@link LifezoneAnalysisResultVO} 에 혼잡도
 * 필드를 <b>더하지 않는다</b> — 민원건수 응답과 보고서 스냅샷에 빈 필드가 섞인다. 그래서 별도 VO 다.
 *
 * <p>🔴 혼잡도 요약은 <b>루트</b>에 둔다 — {@code totGridCnt}·{@code grdTrgtGridCnt}·{@code noEnfGridCnt}·
 * {@code refGridCnt}·{@code over1GridCnt}·{@code occUsedVl}.
 * <ul>
 *   <li>🔴 불법주차 근거(단속·민원)가 없는 격자와 면수 하한({@code MIN_CPCTY_VL}) 미만 격자는 등급 계산 전에
 *       <b>{@code cells}·요약·순위에서 모두 제외</b>한다(2026-10-08 사용자 결정 — 민원건수처럼 "값이 있는 격자만 나온다"). 그래서
 *       {@code totGridCnt} = 5등급 합 = {@code grdTrgtGridCnt} = {@code cells.size()} 로 <b>설계 결정이 성립</b>한다.</li>
 *   <li>{@code noEnfGridCnt} 는 근거가 없어 <b>제외된 격자 수</b>, {@code refGridCnt} 는 근거는 있으나 면수 하한 미만이라
 *       <b>제외된 격자 수</b>다(둘 다 해당하면 noEnf 로 센다). {@code noEnfGridCnt + refGridCnt + totGridCnt} = 공급&gt;0 격자 수.</li>
 *   <li>🔴 {@code over1GridCnt} 는 데이터가 아니라 <b>설정값의 함수</b>라 {@code occUsedVl} 과 반드시 같이 내린다.</li>
 * </ul>
 *
 * <p>{@code anlsVl}·{@code gradeScale.cap}·{@code gradeCriteria[].minVl} 은 <b>점수 × 100 정수</b>다
 * (혼잡 점수 = H × 100 · 상한 없음 — 10000 = 100점 · 2026-10-08 ⑪).
 */
@Schema(description = "생활권 혼잡도 분석 결과")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneCngstResultVO {

    @Schema(description = "분석 시작일 (YYYYMMDD)", example = "20251101")
    private String bgngYmd;

    @Schema(description = "분석 종료일 (YYYYMMDD)", example = "20260630")
    private String endYmd;

    @Schema(description = "시군구명 (전 지역이면 null)", example = "제주시")
    private String sggNm;

    @Schema(description = "읍면동코드 (시군구 전체면 null)", example = "50110560")
    private String emdCd;

    @Schema(description = "분석내용코드", example = "CNGST")
    private String anlsTrgtCd;

    @Schema(description = "분석유형명", example = "혼잡도")
    private String anlsTrgtNm;

    @Schema(description = "격자크기(m)", example = "500")
    private int gridSz;

    @Schema(description = "격자크기 표시명", example = "500m")
    private String gridSzNm;

    @Schema(description = "등급 분류방법", example = "CNGST_ABSOLUTE")
    private String clsfMthd;

    @Schema(description = "분류방법명", example = "혼잡 1.0 기준")
    private String clsfMthdNm;

    @Schema(description = "이 유형이 허용하는 분류방법 목록", example = "[\"CNGST_ABSOLUTE\"]")
    private List<String> availableClsfMthds = new ArrayList<>();

    @Schema(description = "값 단위 (혼잡 점수)", example = "점")
    private String unit;

    @Schema(description = "결과 패널 제목", example = "혼잡 집중 구역")
    private String sectionTitle;

    @Schema(description = "결과 패널 부제", example = "격자 기반 혼잡도 분석 (심각, 경고, 주의)")
    private String sectionSub;

    @Schema(description = "분석유형 가용 목록")
    private List<LifezoneTargetVO> availableTargets = new ArrayList<>();

    @Schema(description = "구간 경계 산출 정보 (cap 은 점수×100 정수 — 상한 없음, 10000 = 100점)")
    private LifezoneGradeScale gradeScale;

    @Schema(description = "등급 판정 기준 5종 (minVl 은 점수×100 정수 · lgndTxt 는 점수 소수 첫째 자리, .0 생략)")
    private List<LifezoneGradeCriterionVO> gradeCriteria = new ArrayList<>();

    @Schema(description = "적용 가중치 (항상 빈 배열)")
    private List<Object> weights = new ArrayList<>();

    @Schema(description = "총 분석 격자 수 = 5등급 합 = cells 수 (불법주차 근거 없음·면수 하한 미만 격자 제외)", example = "475")
    private int totGridCnt;

    @Schema(description = "등급대상 격자 수 (= totGridCnt · 하위호환으로 유지)", example = "475")
    private int grdTrgtGridCnt;

    @Schema(description = "근거 없음으로 제외된 격자 수 (공급>0 이나 단속·민원이 모두 0 인 칸 — cells 에 없다 · 면수 하한 미만과 겹치면 여기로 센다)", example = "102")
    private int noEnfGridCnt;

    @Schema(description = "면수 하한 미만으로 제외된 격자 수 (S < MIN_CPCTY_VL 이고 근거는 있는 칸 — cells 에 없다)", example = "128")
    private int refGridCnt;

    @Schema(description = "100점 초과(anlsVl > 10000 — 수요가 면수 초과) 격자 수 (남은 cells 기준 · occUsedVl 의 함수 · EQUAL_INTERVAL_MIN 이면 severeCnt 와 같다)", example = "11")
    private int over1GridCnt;

    @Schema(description = "적용한 기준 점유율 occ (PCSHR_CRTR_VL)", example = "0.85")
    private BigDecimal occUsedVl;

    /**
     * 분석 계수 적용 내역 — {@code {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}}.
     * 혼잡도 탭 허용 목록 10행을 담는다(조정 안 한 행도 담는다). 문서에는 그리지 않는다.
     *
     * <p>🔴 <b>필드 초기값을 두지 않는다</b> — 이 값을 저장하기 전 보고서 스냅샷은 {@code null} 이다.
     */
    @Schema(description = "분석 계수 적용 내역 {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]} "
            + "— 혼잡도 10행. 이 값을 저장하기 전 보고서는 null", nullable = true)
    private Map<String, Object> vrblAjmt;

    @Schema(description = "심각 격자 수", example = "39")
    private int severeCnt;

    @Schema(description = "경고 격자 수", example = "55")
    private int warnCnt;

    @Schema(description = "주의 격자 수", example = "93")
    private int cautionCnt;

    @Schema(description = "보통 격자 수", example = "93")
    private int normalCnt;

    @Schema(description = "낮음 격자 수", example = "93")
    private int lowCnt;

    @Schema(description = "원천 보유 시작일 (YYYYMMDD · 단속 기준)", example = "20250201")
    private String dataBgngYmd;

    @Schema(description = "원천 보유 종료일 (YYYYMMDD · 단속 기준)", example = "20261030")
    private String dataEndYmd;

    @Schema(description = "격자 셀 목록 (공급 ≥ 면수 하한이고 불법주차 근거가 있는 격자 · 상한 전 혼잡지수 내림차순)")
    private List<LifezoneCngstCellVO> cells = new ArrayList<>();

    @Schema(description = "순위 목록 (심각·경고·주의 셀 전부 · limit 지정 시 그 값까지 · dtl 은 빈 배열)")
    private List<LifezoneRankVO> ranking = new ArrayList<>();
}
