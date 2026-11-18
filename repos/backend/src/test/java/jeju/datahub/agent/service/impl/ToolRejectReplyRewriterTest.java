package jeju.datahub.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TSK-4345 — 자동 거절 뒤 답변 고쳐 쓰기. */
class ToolRejectReplyRewriterTest {

    private static final String NOTICE = ToolRejectReplyRewriter.NOTICE;

    @Test
    @DisplayName("2026-11-17 실측 답변 — 승인 문단을 빼고 고정 문구 + 나머지 문단")
    void realReplyKeepsFollowUp() {
        String reply = """
                죄송합니다. 요청하신 보고서 생성 기능에 대해 사용자의 승인이 확인되지 않아 보고서를 생성하지 못했습니다.

                혹시 특정 데이터의 분석이나 요약이 필요하신가요? 원하시는 구체적인 방향을 말씀해 주시면 텍스트로 상세히 안내해 드리겠습니다.""";

        assertThat(ToolRejectReplyRewriter.rewrite(reply)).isEqualTo(NOTICE + "\n\n"
                + "혹시 특정 데이터의 분석이나 요약이 필요하신가요? 원하시는 구체적인 방향을 말씀해 주시면 텍스트로 상세히 안내해 드리겠습니다.");
    }

    @Test
    @DisplayName("승인 문단뿐이면 고정 문구만 · 승인 거절 문단이 없으면(직접 승인해 만들어진 답 등) 원문 그대로")
    void onlyDenialBecomesNoticeOtherwiseUnchanged() {
        assertThat(ToolRejectReplyRewriter.rewrite("사용자 승인 없이 실행할 수 없습니다.")).isEqualTo(NOTICE);
        String made = "보고서를 생성했습니다.\n\n다운로드 링크를 확인하세요.";
        assertThat(ToolRejectReplyRewriter.rewrite(made)).isEqualTo(made);
        assertThat(ToolRejectReplyRewriter.rewrite(null)).isNull();
    }

    @Test
    @DisplayName("🔴 단어 '승인' 만으로는 빼지 않는다 — 승인 거절을 말하는 표현만 · 띄어쓰기가 달라도 걸린다")
    void plainApprovalWordIsKept() {
        assertThat(ToolRejectReplyRewriter.mentionsDenial("주차장 설치 승인 절차는 다음과 같습니다.")).isFalse();
        assertThat(ToolRejectReplyRewriter.mentionsDenial("승인 이 확인 되지 않아")).isTrue();
        assertThat(ToolRejectReplyRewriter.mentionsDenial("요청이 승인되지 않았습니다")).isTrue();
    }

    @Test
    @DisplayName("승인 필요 도구 판정 — tool_calls[].function.name 이 목록에 있을 때만")
    void callsApprovalTool() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(ToolRejectReplyRewriter.callsApprovalTool(m.readTree(
                "{\"tool_calls\":[{\"function\":{\"name\":\"reports_generate_from_conversation\"}}]}"))).isTrue();
        assertThat(ToolRejectReplyRewriter.callsApprovalTool(m.readTree(
                "{\"tool_calls\":[{\"function\":{\"name\":\"query_dataset\"}}]}"))).isFalse();
        assertThat(ToolRejectReplyRewriter.callsApprovalTool(m.readTree("{\"content\":\"x\"}"))).isFalse();
    }
}
