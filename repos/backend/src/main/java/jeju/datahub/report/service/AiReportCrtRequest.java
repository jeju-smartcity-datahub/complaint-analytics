package jeju.datahub.report.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 보고서 등록 요청 바디.
 *
 * <p>🔴 <b>HTML 을 받지 않는다</b> — 프론트가 보낸 본문을 믿지 않는다. 서버가 세션 상세를 다시 받아
 * {@code msgId} 가 같은 assistant 메시지의 {@code reports[rptIdx]} 를 꺼낸다.
 *
 * <p>검증(400)은 컨트롤러가 재조회 <b>전에</b> 한다 — {@code msgId} 필수·64자 이하
 * ({@code msg_id VARCHAR(64)} — 넘기면 INSERT 500), {@code rptIdx >= 0}.
 */
@Schema(description = "AI 보고서 등록 요청 — 메시지 식별자 + 메시지 안 보고서 순번")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AiReportCrtRequest {

    @Schema(description = "assistant 메시지 식별자 — 세션 상세·전송 응답의 msgId (필수, 64자 이하)",
            example = "chatcmpl-6b098ee996e7", maxLength = 64, requiredMode = Schema.RequiredMode.REQUIRED)
    private String msgId;

    @Schema(description = "메시지 안 보고서 순번 — reports[].idx (0 이상, 필수)", example = "0", minimum = "0",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer rptIdx;
}
