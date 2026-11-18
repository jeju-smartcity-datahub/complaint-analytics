package jeju.datahub.analysis.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 불법주차 집중구역 분석 응답 루트. (GET /api/analysis/hotspot)
 *
 * <p>조회 조건을 그대로 되비추고(echo) 군집 파라미터·가중치·원천 보유 구간까지 함께 담는다 —
 * <b>같은 응답만으로 결과를 재현·설명할 수 있게</b> 하려는 의도다.
 *
 * <p>{@link #weights} 는 <b>실제 계산에 쓴 값</b>이다. TSK-4020 의
 * {@code AnalysisWeightService#selectEffectiveWeights("HOTSPOT")} 가 돌려준(use_yn='N' 을 뺀 뒤
 * 재정규화된) 목록을 그대로 싣는다(D4). 화면 부제 {@code 민원 60% / 단속 40%} 의 출처이며,
 * 프론트가 가중치를 따로 조회하지 않게 하려는 것이다. 타입을 새로 만들지 않고
 * {@link AnalysisWeightVO} 를 재사용한다 — 가중치 타입의 정본은 TSK-4020 이다.
 *
 * <p>{@link #gradeCriteria} 는 {@link HotspotGrade} 를 그대로 투영한 <b>등급 판정 기준</b>이다 —
 * 지도 범례가 {@code 심각 (80점 이상)} 같은 문구를 이 값으로 조립한다. 임계값을 프론트에 박으면
 * enum 이 바뀔 때 범례만 조용히 틀려지므로 응답으로 내린다(D5).
 *
 * <p>{@link #zones} 가 <b>빈 배열이어도 200</b> 이다 — 조건에 데이터가 없거나 군집이 형성되지
 * 않은 정상 상태다(D11). 그래서 두 목록은 {@code null} 이 아닌 빈 목록으로 시작한다.
 */
@Schema(description = "불법주차 집중구역 분석 결과")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotAnalysisResultVO {

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

    /**
     * 적용된 DBSCAN 반경(m). 재현용으로 되비춘다.
     *
     * <p>미터 좌표계(EPSG:5179)로 변환해 계산하므로 단위가 m 이다(D2).
     */
    @Schema(description = "적용된 군집 반경(m)", example = "50")
    private int eps;

    /** 적용된 DBSCAN 최소 포인트 수. 유일좌표 기준이라 "서로 다른 지점 수"다(D2) */
    @Schema(description = "적용된 군집 최소 포인트 수", example = "3")
    private int minPoints;

    /** 점수 산식 표기. 정규화 없는 가중합이다(D3) */
    @Schema(description = "점수 산식 (정규화 없음)", example = "CVLCPT*0.6 + CRDN*0.4")
    private String scoreFormula;

    /** 계산에 실제로 쓴 가중치 목록 (TB_ANLS_VRBL_STNG 의 vrbl_se_cd='WGVL' 행을 읽어 재정규화한 값 — D4) */
    @Schema(description = "계산에 실제로 쓴 가중치 목록")
    private List<AnalysisWeightVO> weights = new ArrayList<>();

    /** 가중치 조정 여부 (Y | N). 입력값이 설정값과 값으로 다를 때만 Y, 입력이 없으면 N (TSK-4306 D16) */
    @Schema(description = "가중치 조정 여부 (Y | N) — 입력값이 설정값과 값으로 다를 때만 Y", example = "N")
    private String wgvlAjmtYn;

    /** 원천 보유 시작일 (YYYYMMDD). 0건 안내용(D11). 원천이 비면 null */
    @Schema(description = "원천 보유 시작일 (YYYYMMDD)", example = "20251104")
    private String dataBgngYmd;

    /** 원천 보유 종료일 (YYYYMMDD). 0건 안내용(D11). 원천이 비면 null */
    @Schema(description = "원천 보유 종료일 (YYYYMMDD)", example = "20251231")
    private String dataEndYmd;

    /**
     * 등급 판정 기준. <b>프론트 범례가 이 값으로 문구를 만든다</b> — 임계값을 화면에 박지 않기 위함(D5).
     *
     * <p>{@link HotspotGrade} 에서 파생한 읽기 전용 투영이며(값을 두 번 적지 않는다),
     * 선언 순서 그대로 {@code maxRankPct} 오름차순이다. <b>표시 3종</b>(심각·경고·주의)만 싣는다 —
     * 이유는 {@link GradeCriterionVO#displayedOf()} 주석 참고.
     *
     * <p>{@link #zones} 가 비어도 채워 보낸다 — 기준은 조회 결과와 무관한 상수다.
     */
    @Schema(description = "등급 판정 기준 목록 (maxRankPct 오름림차순, 표시 3종)")
    private List<GradeCriterionVO> gradeCriteria = new ArrayList<>();

    /**
     * 분석 계수 적용 내역 (TSK-4325 D10) — {@code {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}}.
     *
     * <p>등급 비율 3행만 담는다. 가중치만 조정하면 이 값은 {@code ajmtYn='N'} 이고 {@link #wgvlAjmtYn} 이 {@code 'Y'} 다.
     */
    @Schema(description = "분석 계수 적용 내역 {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]} — 등급 비율 3행")
    private Map<String, Object> vrblAjmt;

    /** 집중구역 목록. 종합점수 내림차순 <b>주의(40점) 이상</b>만이고, {@code limit} 이 있으면 그 상위 N 건이다
     *  (미지정이면 전량 — TSK-4172). 빈 배열도 정상이다(D11) */
    @Schema(description = "집중구역 목록 (종합점수 내림차순)")
    private List<HotspotZoneVO> zones = new ArrayList<>();
}
