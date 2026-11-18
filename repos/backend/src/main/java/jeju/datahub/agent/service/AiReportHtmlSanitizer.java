package jeju.datahub.agent.service;

import java.util.List;
import java.util.Locale;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

/**
 * AI 답변 속 HTML 보고서 영역의 새니타이저.
 *
 * <p>보고서 HTML 은 외부 서비스(Agent)가 준 값이고, 프론트 {@code exportPdf} 가 {@code innerHTML} 에 넣는다 —
 * {@code <img onerror>} 가 <b>실행된다</b>. 그래서 화면에 내리기 전과 저장 직전에
 * <b>이 클래스 한 곳</b>을 통과시킨다.
 *
 * <p>🔴 {@code impl} 이 아니라 {@code agent.service} 에 둔다 — {@code report} 도메인이 저장 직전에 부르므로
 * 다른 도메인이 {@code impl} 을 참조하는 첫 사례를 만들지 않는다.
 *
 * <ul>
 *   <li>{@link Safelist#relaxed()} 기반 + 구획 태그({@code main}·{@code header}·{@code section}·{@code footer}·{@code article}) + 표 {@code colspan}·{@code rowspan} + 전 태그 {@code class}·{@code style}.
 *       {@code <script>}·{@code <iframe>}·{@code <style>}·{@code on*} 이벤트 속성·{@code javascript:} 링크는 safelist 밖이라 사라진다.</li>
 *   <li>{@code style} 은 값에 {@code (} 가 있거나 {@code url}·{@code expression}·{@code @import}·{@code javascript} 를
 *       포함하면(대소문자 무시) 속성을 <b>통째로</b> 지운다 — jsoup {@code Safelist} 는 속성 값을 검사하지 않으므로
 *       {@code clean} 뒤에 순회해 거른다.</li>
 *   <li>{@code <html>}/{@code <head>} 는 버리고 {@code <body>} 안쪽만 남긴다. 🔴 원문을 바로 {@code clean} 하면
 *       body fragment 로 파싱돼 {@code <title>} 태그만 지워지고 제목 텍스트가 본문 첫머리에 남는다 —
 *       그래서 {@code Jsoup.parse(원문).body().html()} 을 넘긴다. 제목은 추출기가 새니타이즈 <b>전</b> 원문에서 뽑는다.</li>
 * </ul>
 *
 * <p>상태 없는 유틸리티다.
 */
public final class AiReportHtmlSanitizer {

    /** {@code style} 값에 이것이 들어 있으면 속성째 지운다(소문자 비교). */
    private static final List<String> STYLE_DENY = List.of("(", "url", "expression", "@import", "javascript");

    private static final Safelist SAFELIST = Safelist.relaxed()
            // 구획 태그 — Agent 가 class 를 이 껍데기에 단다. 빼면 태그와 함께 class 가 사라져
            // 프론트 기본 디자인(applyAiReportStyles)이 report-header·report-section 등을 찾지 못한다.
            .addTags("main", "header", "section", "footer", "article")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes(":all", "class", "style");

    private AiReportHtmlSanitizer() {
    }

    /**
     * 보고서 HTML 을 새니타이즈한다.
     *
     * @param html 업스트림 원문(완전한 문서든 조각이든). null 이면 null
     * @return {@code <body>} 안쪽만 남긴 안전한 HTML 조각
     */
    public static String sanitize(String html) {
        if (html == null) {
            return null;
        }
        Document.OutputSettings out = new Document.OutputSettings().prettyPrint(false);

        Document parsed = Jsoup.parse(html);
        parsed.outputSettings(out); // pretty print 가 켜져 있으면 줄바꿈·들여쓰기가 섞인다
        String bodyHtml = parsed.body().html();
        String cleaned = Jsoup.clean(bodyHtml, "", SAFELIST, out);

        Document doc = Jsoup.parseBodyFragment(cleaned);
        doc.outputSettings(out);
        for (Element el : doc.body().select("[style]")) {
            if (isUnsafeStyle(el.attr("style"))) {
                el.removeAttr("style");
            }
        }
        return doc.body().html();
    }

    private static boolean isUnsafeStyle(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        for (String deny : STYLE_DENY) {
            if (v.contains(deny)) {
                return true;
            }
        }
        return false;
    }
}
