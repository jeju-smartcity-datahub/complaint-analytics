package jeju.datahub.analysis.service;

import java.time.LocalDateTime;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천 보고서 응답 루트 — 요금제 적용 · 공유주차제 적용 <b>두 보기가 같이 쓴다</b>.
 * (POST /api/analysis/zone-recommendation/reports 201)
 *
 * <p><b>보고서 머리 9칸 + 판별 키 + 본문 하나</b>다. 프론트는 이 응답 하나로 문서를 그린다 — 화면에 떠 있는
 * 값으로 파일을 만들면 저장된 것과 내려받은 것이 달라질 수 있다.
 *
 * <h2>🔴 {@link #rcmdtnTypeCd} 가 본문 모양을 정한다</h2>
 * 두 보기는 분석유형코드가 같아({@code RECOMMEND}) 보고서 목록만 보고는 가를 수 없다. 그래서 응답 최상위에
 * 판별 키를 두고 <b>본문은 둘 중 하나만</b> 채운다.
 * <table border="1">
 *   <caption>판별 키 → 본문</caption>
 *   <tr><th>{@code rcmdtnTypeCd}</th><th>{@link #fee}</th><th>{@link #share}</th></tr>
 *   <tr><td>{@code FEE}</td><td>채움</td><td>{@code null}</td></tr>
 *   <tr><td>{@code SHARE}</td><td>{@code null}</td><td>채움</td></tr>
 * </table>
 *
 * <p>본문 타입은 <b>새로 만들지 않고</b> 조회 응답({@link ZoneRcmdtnResultVO} · {@link ZoneRcmdtnShareResultVO})을
 * 그대로 싣는다({@link InvestPrrtReportVO} 와 같은 방침) — 분석 화면과 보고서가 같은 값을 다른 모양으로 받지
 * 않게 한다.
 *
 * <h2>🔴 실행 ID 는 머리의 {@link #excnId} 한 곳이다</h2>
 * 본문({@link ZoneRcmdtnResultVO#getExcnId() fee.excnId} 등)의 실행 ID 는 <b>채우지 않는다({@code null})</b>.
 * 저장된 결과 JSON 에 그 키가 없어 스냅샷 조회에서는 항상 {@code null} 이므로, 생성 응답에만 채우면 같은
 * 보고서의 두 응답이 갈린다.
 */
@Schema(description = "구역추천 보고서 (저장 시점 스냅샷 — rcmdtnTypeCd 가 본문 모양을 정한다)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnReportVO {

    // --- 보고서 머리 ---

    /** 보고서아이디 (rptp_id — TB_RPTP PK) */
    @Schema(description = "보고서아이디", example = "170")
    private Long rptpId;

    /** 실행아이디 (excn_id — TB_ANLS_EXCN PK). 🔴 실행 ID 는 여기 한 곳이다(본문의 excnId 는 null) */
    @Schema(description = "분석실행아이디 (본문 fee·share 의 excnId 는 null 이다)", example = "290")
    private Long excnId;

    /** 보고서명. {@code 구역추천 요금제 적용 보고서 {regionNm}} ({@code ZoneRcmdtnReportNaming} 이 조립) */
    @Schema(description = "보고서명", example = "구역추천 요금제 적용 보고서 제주시 이도이동")
    private String rptpNm;

    /** 지역명. 서버 3단 규칙 — 요금제 적용은 항상 {@code {시군구} {읍면동}} 이다 */
    @Schema(description = "지역명 ({시군구} {읍면동} | {시군구} 전체 | 전체 지역)", example = "제주시 이도이동")
    private String regionNm;

    /** 분석기간설명 (anls_prd_expln — {@code yyyy.MM.dd ~ yyyy.MM.dd}. 민원 창이다) */
    @Schema(description = "분석기간설명 (민원 창)", example = "2025.01.01 ~ 2025.08.31")
    private String anlsPrdExpln;

    /** 파일형식 (file_fmt — PDF | DOCX. 요청값을 그대로 저장·반환한다) */
    @Schema(description = "파일형식 (PDF | DOCX)", example = "PDF")
    private String fileFmt;

    /** 생성일시 (crt_ymd — 보고서 저장 시각) */
    @Schema(description = "생성일시", example = "2026-10-14T14:03:11")
    private LocalDateTime crtYmd;

    /** 실제 적용한 분석 시작일 (YYYYMMDD — 실행 행의 anls_bgng_ymd) */
    @Schema(description = "적용한 분석 시작일 (YYYYMMDD)", example = "20250101")
    private String bgngYmd;

    /** 실제 적용한 분석 종료일 (YYYYMMDD, 당일 포함 — 실행 행의 anls_end_ymd) */
    @Schema(description = "적용한 분석 종료일 (YYYYMMDD, 포함)", example = "20250831")
    private String endYmd;

    /**
     * 생성 당시 분석 계수 적용 내역 — 문서에는 그리지 않고 저장·전달만 한다.
     * 🔴 <b>필드 초기값을 두지 않는다</b> — 이 값을 저장하기 전 보고서는 {@code null} 이다.
     * 🔴 본문({@code fee}·{@code share})의 같은 칸은 스냅샷에서 비므로 생성 응답도 비우고 여기 한 곳에 둔다.
     */
    @Schema(description = "생성 당시 분석 계수 적용 내역 {ajmtYn, vrbls:[…]}. 이 값을 저장하기 전 보고서는 null",
            nullable = true)
    private Map<String, Object> vrblAjmt;

    // --- 판별 키 + 본문 (둘 중 하나만 채운다) ---

    /** 보기 판별 키 — {@code FEE}(요금제 적용) | {@code SHARE}(공유주차제 적용). 실행 행의 anls_param_json 과 같은 값 */
    @Schema(description = "보기 판별 키 (FEE = 요금제 적용 → fee 채움 | SHARE = 공유주차제 적용 → share 채움)",
            example = "FEE", allowableValues = {"FEE", "SHARE"})
    private String rcmdtnTypeCd;

    /** 요금제 적용 본문 (저장 시점 — 조회 응답과 같은 모양). {@code rcmdtnTypeCd='FEE'} 일 때만 채운다 */
    @Schema(description = "요금제 적용 본문 (rcmdtnTypeCd=FEE 일 때만 · 그 밖은 null)", nullable = true)
    private ZoneRcmdtnResultVO fee;

    /** 공유주차제 적용 본문 (저장 시점 — 조회 응답과 같은 모양). {@code rcmdtnTypeCd='SHARE'} 일 때만 채운다 */
    @Schema(description = "공유주차제 적용 본문 (rcmdtnTypeCd=SHARE 일 때만 · 그 밖은 null)", nullable = true)
    private ZoneRcmdtnShareResultVO share;
}
