package jeju.datahub.agent.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 답변 속 HTML 보고서 영역 1건 (TSK-4293 D2·D4).
 *
 * <p>assistant {@code content} 안의 {@code html_report_start_…} ~ {@code html_report_end_…} 영역을
 * 추출기({@code AgentReportExtractor})가 떼어 낸 결과다. 원래 자리는 {@code content} 에
 * 자리표시 {@code [[AI_REPORT:{idx}]]} 로 남는다 — 프론트는 HTML 을 다시 파싱하지 않는다.
 *
 * <p>🔴 <b>필드 선언 순서는 {@code idx, title, html, stts} 로 고정이다</b> — 4인자 생성자
 * {@code new AiReportBlockVO(idx, title, html, stts)} 를 픽스처가 쓴다. 순서를 바꾸면
 * 같은 타입({@code String})끼리 조용히 자리가 바뀐다.
 */
@Schema(description = "AI 답변 속 HTML 보고서 영역 1건 — content 의 [[AI_REPORT:{idx}]] 자리에 들어간다")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class AiReportBlockVO {

    /** 정상 — 시작·끝 마커가 모두 있다. */
    public static final String STTS_DONE = "DONE";
    /** 생성 중 — 시작 마커만 있고 끝 마커가 없다(D4). {@code title}·{@code html} 은 null. */
    public static final String STTS_PENDING = "PENDING";
    /** 잘림 확정 — 업스트림 {@code finish_reason=length}(D4, 전송 경로에서 판정). */
    public static final String STTS_TRUNCATED = "TRUNCATED";

    @Schema(description = "영역 순번 (0부터) — content 의 [[AI_REPORT:{idx}]] 와 같은 값", example = "0")
    private int idx;

    @Schema(description = "보고서 제목 — <title> → 첫 <h1> → 'AI 보고서_{yyyyMMdd_HHmm}' 순, 256자 절단. 미완성이면 null",
            example = "제주특별자치도 주차 민원 분석 보고서")
    private String title;

    @Schema(description = "보고서 HTML 본문. 미완성(PENDING·TRUNCATED)이면 null")
    private String html;

    @Schema(description = "상태 — DONE(정상) | PENDING(생성 중) | TRUNCATED(잘림 확정)", example = "DONE")
    private String stts;
}
