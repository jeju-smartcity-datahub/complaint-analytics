package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 투자우선순위 분석 응답 루트. (GET /api/analysis/investment-priority — TSK-4036 Step 6)
 *
 * <p>적용한 기간·가중치·등급 기준·계수를 함께 담는다 — <b>같은 응답만으로 결과를 재현·설명할 수 있게</b>
 * 하려는 의도다({@link HotspotAnalysisResultVO} 와 같은 방침).
 *
 * <h2>🔴 무엇이 "전체 기준" 이고 무엇이 "필터 통과분" 인가</h2>
 * <ul>
 *   <li>{@link #lots} — 행정구역 필터({@code sggNm}·{@code emdCd})를 <b>통과한 것만</b>. 단 그 안의
 *       점수·순위는 전 지역 대상 기준 그대로다(필터가 순위를 다시 매기지 않는다).</li>
 *   <li>{@link #smry}·{@link #warnings} — <b>필터와 무관하게 전 지역 대상 기준</b>이다. 화면 상단
 *       카드("분석대상 N개소")가 필터에 따라 줄어들지 않게 하고, 자료의 한계는 조회 조건과 무관한
 *       사실이라서다.</li>
 * </ul>
 *
 * <p>{@link #weights} 는 <b>실제 계산에 쓴 변수만</b> 싣는다(가중치 0·꺼진 변수·산식이 모르는 변수는
 * 빠진다). 타입은 {@link AnalysisWeightVO} 를 재사용한다 — 가중치 타입의 정본은 TSK-4020 이다.
 *
 * <p>{@code null} 을 내리는 칸은 {@code null} 그대로 내린다({@code @JsonInclude(NON_NULL)} 을 붙이지
 * 않는다 — 키가 사라지면 "없다" 와 "안 준다" 가 구분되지 않는다).
 * 🔴 <b>예외는 {@link #wbk} 하나</b>다(TSK-4343) — 보고서 경로에만 있는 칸이라 필드 단위 NON_NULL 로 조회 응답에서 키째 뺀다.
 */
@Schema(description = "투자우선순위 분석 결과")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class InvestPrrtResultVO {

    /** 적용한 분석 시작일 (YYYYMMDD). 요청에 없었으면 서버가 정한 기본값이다 */
    @Schema(description = "분석 시작일 (YYYYMMDD) — 생략하면 오늘에서 12개월 전", example = "20260713")
    private String bgngYmd;

    /** 적용한 분석 종료일 (YYYYMMDD, 당일 포함). 요청에 없었으면 오늘이다 */
    @Schema(description = "분석 종료일 (YYYYMMDD, 포함) — 생략하면 오늘", example = "20261013")
    private String endYmd;

    // ---- TSK-4352 — 화면 전용(InfoTip·헤더). 🔴 보고서 스냅샷(rslt_json)·InvestPrrtReportVO 에는 싣지 않는다 · 초기값 없음

    /** 이용률 집계 시작 달 (YYYYMM) — 시작일이 1일이 아니면 시작 달 제외(D1) · 한 달 안 창이면 끝 달로 clamp */
    @Schema(description = "이용률 집계 시작 달 (YYYYMM) — 시작일이 1일이 아니면 시작 달 제외", example = "202512", nullable = true)
    private String utztnBgngYm;

    /** 이용률 집계 끝 달 (YYYYMM) — 종료일의 달(진행 중이어도 포함) */
    @Schema(description = "이용률 집계 끝 달 (YYYYMM) — 종료일의 달", example = "202611", nullable = true)
    private String utztnEndYm;

    /** 안전점검 기준일 (YYYYMMDD) — "이 날짜 이전 가장 최근 점검" 의 기준일 = 종료일 */
    @Schema(description = "안전점검 기준일 (YYYYMMDD) — 이 날짜 이전 가장 최근 점검을 쓴다(= 종료일)", example = "20261112", nullable = true)
    private String sftyCrtrYmd;

    /** 주차 민원 최근 발생일 (YYYYMMDD) — 종료일 이하 전체의 최댓값. 자료 없으면 null */
    @Schema(description = "민원 건수 최근 자료일 (YYYYMMDD) — 종료일 이하 주차 민원 최근 발생일 · 자료 없으면 null", example = "20261112", nullable = true)
    private String cvlcptLastYmd;

    /** 이용률 최근 자료 달 (YYYYMM) — 종료 달 이하 대상 주차장의 값 있는 최근 달. 자료 없으면 null */
    @Schema(description = "이용률 최근 자료 달 (YYYYMM) — 종료 달 이하 대상 주차장의 회전율 값 있는 최근 달 · 자료 없으면 null", example = "202609", nullable = true)
    private String utztnLastYm;

    /** 안전점검 최근 자료일 (YYYYMMDD) — 종료일 이하 대상 주차장의 최근 점검일. 자료 없으면 null */
    @Schema(description = "안전위험도 최근 자료일 (YYYYMMDD) — 종료일 이하 대상 주차장의 최근 점검일 · 자료 없으면 null", example = "20260901", nullable = true)
    private String sftyChckLastYmd;

    /** 사고 최근 자료일 (YYYYMMDD) — 종료일 이하 대상 주차장의 최근 사고일. 자료 없으면 null */
    @Schema(description = "사고 이력 최근 자료일 (YYYYMMDD) — 종료일 이하 대상 주차장의 최근 사고일 · 자료 없으면 null", example = "20261111", nullable = true)
    private String acdntLastYmd;

    /** 점수 산식 표기 — 실제 반영한 변수와 그 가중치(%) */
    @Schema(description = "점수 산식 표기 (실제 반영한 변수만)",
            example = "안전 25% + 민원 25% + 이용 25% + 단속 25% (안전은 점검 결과 점수 · 그 밖은 백분위 점수)")
    private String scoreFormula;

    /** 계산에 실제로 쓴 가중치 목록. 설정이 정상이면 {@code stngVl} 합이 100 이다 */
    @Schema(description = "계산에 실제로 쓴 가중치 목록 (stngVl = 가중치 %)")
    private List<AnalysisWeightVO> weights = new ArrayList<>();

    /** 등급 판정 기준 — {@code minScr} 내림차순(H → M → L) */
    @Schema(description = "등급 판정 기준 목록 (minScr 내림차순)")
    private List<GradeCriterion> gradeCriteria = new ArrayList<>();

    /** 적용한 계수 */
    @Schema(description = "적용한 계수")
    private CrtrVls crtrVls;

    /**
     * 분석 계수 적용 내역 (TSK-4325 D10) — {@code {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}}.
     *
     * <p>{@link #weights} 와 달리 0 인 가중치도 남는다(적용값 0 이 곧 조정 내역이다).
     */
    @Schema(description = "분석 계수 적용 내역 {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]} — 가중치 4 + 계수 4")
    private Map<String, Object> vrblAjmt;

    /** 자료의 한계 알림. 🔴 필터와 무관하게 전 지역 대상 기준으로 판정한다. 없으면 빈 배열 */
    @Schema(description = "자료의 한계 알림 (전 지역 대상 기준 — 필터와 무관)")
    private List<Warning> warnings = new ArrayList<>();

    /** 요약. 🔴 필터와 무관하게 전 지역 대상 기준이다 */
    @Schema(description = "요약 (전 지역 대상 기준 — 필터와 무관)")
    private Smry smry;

    /** 주차장 목록 — 전체 순위 오름차순. 필터를 통과한 것만이며 빈 배열도 정상이다 */
    @Schema(description = "주차장 목록 (전체 순위 오름차순 · 행정구역 필터 통과분)")
    private List<InvestPrrtLotVO> lots = new ArrayList<>();

    /**
     * 🆕 TSK-4343 — 엑셀 재계산 데이터. <b>보고서 경로({@code analyzeWithWorkbook})만</b> 채운다.
     *
     * <p>🔴 <b>필드 초기값을 두지 않는다</b>(구 보고서·{@code ENFORCE} 가중치 갈래는 {@code null} — D2-1·D13). 🔴 NON_NULL 은 <b>필드 단위</b>다 —
     * 전역 NON_NULL 설정이 없어 안 붙이면 조회 GET 에 {@code "wbk": null} 키가 생긴다. 클래스 단위로 걸면 다른 null 칸까지 사라진다.
     */
    @Schema(description = "엑셀 재계산 데이터 (보고서 경로만 · 조회 응답에는 없다)", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private InvestPrrtWorkbookVO wbk;

    /** 등급 1종의 판정 기준. 이름은 공통코드 {@code IVST_PRRT_LVL_CD} 에서 읽는다 */
    @Schema(description = "등급 판정 기준")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GradeCriterion {

        @Schema(description = "우선순위 등급 코드 (H | M | L)", example = "H")
        private String prrtLvlCd;

        @Schema(description = "우선순위 등급명 (공통코드 IVST_PRRT_LVL_CD)", example = "높음")
        private String prrtLvlNm;

        /** 이 등급의 최소 종합점수(<b>이상</b>). 가장 낮은 등급은 0 이다 */
        @Schema(description = "이 등급의 최소 종합점수 (이상)", example = "80")
        private BigDecimal minScr;
    }

    /** 적용한 계수({@code TB_ANLS_VRBL_STNG} 의 {@code CFC} 행) */
    @Schema(description = "적용한 계수")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrtrVls {

        /** 최근접 배정 반경(m) — 민원·단속을 이 거리 안의 가장 가까운 대상 주차장에 배정했다 */
        @Schema(description = "최근접 배정 반경 (m)", example = "300")
        private BigDecimal altmntRngVl;

        /** 목록·지도에 표시할 상위 주차장 수. 🔴 계산에 쓰지 않는 화면 표시 설정이다({@code lots} 를 자르지 않는다) */
        @Schema(description = "목록·지도에 표시할 상위 주차장 수 (화면 표시 설정 — lots 를 자르지 않는다)", example = "50")
        private Integer indctPkltCnt;
    }

    /** 자료의 한계 알림 1건 */
    @Schema(description = "자료의 한계 알림")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Warning {

        @Schema(description = "알림 코드 (CRDN_SGG_SKEW | SFTY_CHCK_MISSING | SFTY_CD_UNKNOWN | ACDNT_NO_DATA | "
                + "UTZTN_PARTIAL | UTZTN_MISSING | WGVL_NOT_APPLIED)", example = "SFTY_CHCK_MISSING")
        private String code;

        @Schema(description = "사람이 읽는 설명", example = "안전점검 기록이 없는 주차장 3곳을 안전 0점으로 계산했습니다.")
        private String message;

        /** 해당 주차장 수. 개수로 셀 수 없는 알림이면 {@code null} */
        @Schema(description = "해당 주차장 수 — 개수가 없는 알림이면 null", example = "3", nullable = true)
        private Integer cnt;
    }

    /** 요약(화면 상단 카드의 숫자) */
    @Schema(description = "요약")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Smry {

        /** 분석 대상 주차장 수(전 지역). 필터가 없으면 {@code lots} 의 길이와 같다 */
        @Schema(description = "분석 대상 주차장 수 (전 지역)", example = "509")
        private Integer trgtCnt;

        /** 등급이 '높음'({@code H})인 주차장 수(전 지역) */
        @Schema(description = "등급 '높음'(H) 주차장 수 (전 지역)", example = "1")
        private Integer highCnt;

        /** 기간 안 사고 건수. 🔴 사고이력 자료가 한 행도 없으면 {@code null} 이다 — 0 은 "사고가 없었다" 로 읽힌다 */
        @Schema(description = "기간 안 사고 건수 — 사고이력 자료가 없으면 null", example = "12", nullable = true)
        private Long acdntCnt;
    }
}
