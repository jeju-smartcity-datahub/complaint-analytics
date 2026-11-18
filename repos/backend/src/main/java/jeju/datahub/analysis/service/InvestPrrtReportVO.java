package jeju.datahub.analysis.service;

import java.time.LocalDateTime;
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
 * 투자우선순위 보고서 응답 루트.
 * (POST /api/analysis/investment-priority/reports 201 · GET /api/reports/{rptpId}/investment-priority)
 *
 * <p><b>보고서 메타 7칸 + 저장 시점의 분석 내용</b>이다. 프론트는 이 응답 하나로 문서를 그린다 —
 * 화면에 떠 있는 값으로 파일을 만들면 저장된 것과 내려받은 것이 달라질 수 있다.
 *
 * <p>분석 내용의 타입은 <b>새로 만들지 않고</b> 조회 응답({@link InvestPrrtResultVO})의 것을 그대로 싣는다
 * (집중구역 {@link HotspotReportVO} 와 같은 방침) — 분석 화면과 보고서 화면이 같은 값을 다른 모양으로
 * 받지 않게 한다. 키 이름도 조회 응답과 같다.
 *
 * <h2>🔴 무엇이 "전체 기준" 이고 무엇이 "필터 통과분" 인가 (조회 응답과 같다)</h2>
 * {@link #lots} 는 보고서의 지역 필터를 통과한 주차장만이고 그 안의 {@code rnk} 는 <b>전 지역 순위 그대로</b>다
 * (1부터 다시 매기지 않는다). {@link #smry}·{@link #warnings} 는 필터와 무관하게 전 지역 대상 기준이다.
 *
 * <p>🔴 POST 와 GET 이 <b>같은 모양·같은 값</b>이다 — GET 은 {@code TB_ANLS_RSLT.rslt_json} 을 그대로
 * 되살리고 다시 계산하지 않는다. (집중구역처럼 일부 칸이 GET 에서 비는 비대칭이 없다.)
 */
@Schema(description = "투자우선순위 보고서 (저장 시점 스냅샷)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class InvestPrrtReportVO {

    // --- 보고서 메타 ---

    /** 보고서아이디 (rptp_id — TB_RPTP PK) */
    @Schema(description = "보고서아이디", example = "170")
    private Long rptpId;

    /** 실행아이디 (excn_id — TB_ANLS_EXCN PK) */
    @Schema(description = "분석실행아이디", example = "290")
    private Long excnId;

    /** 보고서명. {@code 투자 우선순위 보고서 {regionNm}} ({@code InvestPrrtReportNaming} 이 조립) */
    @Schema(description = "보고서명", example = "투자 우선순위 보고서 전체 지역")
    private String rptpNm;

    /** 지역명. 서버 3단 규칙 — {@code 전체 지역} / {@code 제주시 전체} / {@code 제주시 연동} */
    @Schema(description = "지역명 (전체 지역 | {시군구} 전체 | {시군구} {읍면동})", example = "전체 지역")
    private String regionNm;

    /** 분석기간설명 (anls_prd_expln — {@code yyyy.MM.dd ~ yyyy.MM.dd}) */
    @Schema(description = "분석기간설명", example = "2026.07.13 ~ 2026.10.13")
    private String anlsPrdExpln;

    /** 파일형식 (file_fmt — PDF | DOCX | XLSX. 요청값을 그대로 저장·반환한다) */
    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF")
    private String fileFmt;

    /** 생성일시 (crt_ymd — 보고서 저장 시각) */
    @Schema(description = "생성일시", example = "2026-10-14T14:03:11")
    private LocalDateTime crtYmd;

    // --- 저장 시점의 분석 내용 (키는 조회 응답과 같다) ---

    /** 실제 적용한 분석 시작일 (YYYYMMDD — 요청에 없었으면 저장 시점의 기본값) */
    @Schema(description = "적용한 분석 시작일 (YYYYMMDD)", example = "20260713")
    private String bgngYmd;

    /** 실제 적용한 분석 종료일 (YYYYMMDD, 당일 포함) */
    @Schema(description = "적용한 분석 종료일 (YYYYMMDD, 포함)", example = "20261013")
    private String endYmd;

    /** 점수 산식 표기 (저장 시점) */
    @Schema(description = "점수 산식 표기 (저장 시점)",
            example = "안전 25% + 민원 25% + 이용 25% + 단속 25% (안전은 점검 결과 점수 · 그 밖은 백분위 점수)")
    private String scoreFormula;

    /** 계산에 실제로 쓴 가중치 목록 (저장 시점 — 설정을 고쳐도 바뀌지 않는다) */
    @Schema(description = "계산에 실제로 쓴 가중치 목록 (저장 시점 · stngVl = 가중치 %)")
    private List<AnalysisWeightVO> weights = new ArrayList<>();

    /** 등급 판정 기준 (저장 시점) */
    @Schema(description = "등급 판정 기준 목록 (저장 시점 · minScr 내림차순)")
    private List<InvestPrrtResultVO.GradeCriterion> gradeCriteria = new ArrayList<>();

    /** 적용한 계수 (저장 시점) */
    @Schema(description = "적용한 계수 (저장 시점)")
    private InvestPrrtResultVO.CrtrVls crtrVls;

    /**
     * 생성 당시 분석 계수 적용 내역 — 문서에는 그리지 않고 저장·전달만 한다.
     * 🔴 <b>필드 초기값을 두지 않는다</b> — 이 값을 저장하기 전 보고서는 {@code null} 이다.
     */
    @Schema(description = "생성 당시 분석 계수 적용 내역 {ajmtYn, vrbls:[…]}. 이 값을 저장하기 전 보고서는 null", nullable = true)
    private Map<String, Object> vrblAjmt;

    /** 자료의 한계 알림 (전 지역 대상 기준) */
    @Schema(description = "자료의 한계 알림 (전 지역 대상 기준 — 필터와 무관)")
    private List<InvestPrrtResultVO.Warning> warnings = new ArrayList<>();

    /** 요약 (전 지역 대상 기준) */
    @Schema(description = "요약 (전 지역 대상 기준 — 필터와 무관)")
    private InvestPrrtResultVO.Smry smry;

    /** 주차장 목록 — 지역 필터 통과분, 전체 순위 오름차순. 상위 N 으로 자르지 않는다 */
    @Schema(description = "주차장 목록 (지역 필터 통과분 전체 · rnk 는 전 지역 순위 그대로)")
    private List<InvestPrrtLotVO> lots = new ArrayList<>();

    /**
     * 엑셀 재계산 데이터. 형식과 무관하게 새 보고서마다 저장된다.
     * 🔴 <b>필드 초기값을 두지 않는다</b> — 이 값을 저장하기 전 보고서·{@code ENFORCE} 가중치 갈래는 {@code null} 이고,
     * 🔴 필드 단위 NON_NULL 이라 그때는 응답에 <b>키가 없다</b>. 빠뜨리면 {@code readValue} 가 예외 없이 조용히 버린다.
     */
    @Schema(description = "엑셀 재계산 데이터 (모집단 수 · 주차장별 백분위 개수 · 계수 8행). 이 값을 저장하기 전 보고서는 키가 없다", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private InvestPrrtWorkbookVO wbk;
}
