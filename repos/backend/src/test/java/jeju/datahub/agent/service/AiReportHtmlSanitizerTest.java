package jeju.datahub.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AiReportHtmlSanitizer} 단위 테스트.
 */
class AiReportHtmlSanitizerTest {

    @Test
    @DisplayName("null 은 null")
    void nullIsNull() {
        assertThat(AiReportHtmlSanitizer.sanitize(null)).isNull();
    }

    @Test
    @DisplayName("<script> 는 태그·내용째 제거된다")
    void scriptRemoved() {
        String r = AiReportHtmlSanitizer.sanitize("<p>앞</p><script>alert('x')</script><p>뒤</p>");
        assertThat(r).doesNotContainIgnoringCase("<script").doesNotContain("alert")
                .contains("<p>앞</p>").contains("<p>뒤</p>");
    }

    @Test
    @DisplayName("on* 이벤트 속성(onerror·onclick)은 제거된다")
    void eventAttributesRemoved() {
        String r = AiReportHtmlSanitizer.sanitize(
                "<img src=\"https://example.com/a.png\" onerror=\"alert(1)\"><p onclick=\"alert(2)\">x</p>");
        assertThat(r).doesNotContainIgnoringCase("onerror=").doesNotContainIgnoringCase("onclick=")
                .doesNotContain("alert");
    }

    @Test
    @DisplayName("javascript: 링크는 제거된다")
    void javascriptHrefRemoved() {
        String r = AiReportHtmlSanitizer.sanitize("<a href=\"javascript:alert(1)\">링크</a>");
        assertThat(r).doesNotContainIgnoringCase("javascript:").contains("링크");
    }

    @Test
    @DisplayName("<iframe> 은 제거된다")
    void iframeRemoved() {
        String r = AiReportHtmlSanitizer.sanitize("<p>a</p><iframe src=\"https://evil.example\"></iframe>");
        assertThat(r).doesNotContainIgnoringCase("<iframe").doesNotContain("evil.example");
    }

    @Test
    @DisplayName("표(colspan·rowspan)·목록·제목은 남는다")
    void structureKept() {
        String r = AiReportHtmlSanitizer.sanitize("<h1>제목</h1><h2>소제목</h2>"
                + "<table><thead><tr><th colspan=\"2\">머리</th></tr></thead>"
                + "<tbody><tr><td rowspan=\"2\">가</td><td>나</td></tr></tbody></table>"
                + "<ul><li>하나</li></ul><ol><li>둘</li></ol>");
        assertThat(r).contains("<h1>제목</h1>").contains("<h2>소제목</h2>")
                .contains("<table>").contains("<th colspan=\"2\">머리</th>").contains("<td rowspan=\"2\">가</td>")
                .contains("<ul><li>하나</li></ul>").contains("<ol><li>둘</li></ol>");
    }

    @Test
    @DisplayName("class 속성은 유지된다")
    void classKept() {
        String r = AiReportHtmlSanitizer.sanitize("<div class=\"bar-track\"><span class=\"bar-fill\">x</span></div>");
        assertThat(r).contains("class=\"bar-track\"").contains("class=\"bar-fill\"");
    }

    @Test
    @DisplayName("style=\"width: 75%;\" 는 남는다")
    void safeStyleKept() {
        String r = AiReportHtmlSanitizer.sanitize("<span class=\"bar-fill\" style=\"width: 75%;\"></span>");
        assertThat(r).contains("style=\"width: 75%;\"");
    }

    @Test
    @DisplayName("style 에 url( 가 있으면 속성째 제거 — class 는 남는다")
    void urlStyleRemoved() {
        String r = AiReportHtmlSanitizer.sanitize("<div class=\"k\" style=\"background:url(x)\">x</div>");
        assertThat(r).doesNotContain("style=").doesNotContain("url").contains("class=\"k\"");
    }

    @Test
    @DisplayName("style 에 expression·@import·javascript·괄호가 있으면(대소문자 무시) 속성째 제거")
    void otherUnsafeStylesRemoved() {
        assertThat(AiReportHtmlSanitizer.sanitize("<p style=\"width:EXPRESSION(alert(1))\">a</p>")).doesNotContain("style=");
        assertThat(AiReportHtmlSanitizer.sanitize("<p style=\"@IMPORT 'x.css'\">a</p>")).doesNotContain("style=");
        assertThat(AiReportHtmlSanitizer.sanitize("<p style=\"behavior: JavaScript\">a</p>")).doesNotContain("style=");
        assertThat(AiReportHtmlSanitizer.sanitize("<p style=\"width: calc(10px)\">a</p>")).doesNotContain("style=");
    }

    @Test
    @DisplayName("<html>/<head> 는 버린다 — 결과 첫머리에 <title> 텍스트가 남지 않는다 · <style> 블록도 버린다")
    void headDropped() {
        String r = AiReportHtmlSanitizer.sanitize("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<title>보고서 제목</title><style>body{color:red}</style></head>"
                + "<body><style>.x{}</style><h1>본문 제목</h1><p>본문</p></body></html>");
        assertThat(r).doesNotStartWith("보고서 제목").doesNotContain("보고서 제목")
                .doesNotContainIgnoringCase("<html").doesNotContainIgnoringCase("<head")
                .doesNotContainIgnoringCase("<body").doesNotContainIgnoringCase("<style")
                .doesNotContain("color:red").doesNotContain(".x{}")
                .startsWith("<h1>본문 제목</h1>").contains("<p>본문</p>");
    }

    @Test
    @DisplayName("구획 태그(main·header·section·footer·article)와 그 class 가 유지된다")
    void sectioningTagsKeptWithClass() {
        String r = AiReportHtmlSanitizer.sanitize("<main class=\"report\"><header class=\"report-header\"><h1>제목</h1></header>"
                + "<section class=\"report-section\"><article class=\"a\"><p>본문</p></article></section>"
                + "<footer class=\"report-footer\">끝</footer></main>");
        assertThat(r).contains("<main class=\"report\">").contains("<header class=\"report-header\">")
                .contains("<section class=\"report-section\">").contains("<article class=\"a\">")
                .contains("<footer class=\"report-footer\">끝</footer>").contains("</main>");
    }
}
