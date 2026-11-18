package jeju.datahub.agent.service;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 대화 세션 목록 — {@code GET /api/ai/sessions} 응답.
 *
 * <pre>
 * 필드 대응표 — 원천 snake_case → 우리 camelCase.
 *   sessions[] → sessions[] ({@link AiSessionVO}) | next_cursor → nextCursor
 * </pre>
 *
 * <p>{@code nextCursor} 는 base64(JSON) 문자열이며 <b>더 보기</b>의 입력이다.
 * 다음 페이지가 없으면 {@code null} 이다.
 */
@Schema(description = "AI 대화 세션 목록")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AiSessionListVO {

    @Schema(description = "세션 목록")
    private List<AiSessionVO> sessions = new ArrayList<>();

    @Schema(description = "다음 페이지 커서 (없으면 null)", example = "eyJvZmZzZXQiOjIwfQ==")
    private String nextCursor;
}
