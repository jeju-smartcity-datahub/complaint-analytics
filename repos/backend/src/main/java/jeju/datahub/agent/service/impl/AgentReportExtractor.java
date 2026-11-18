package jeju.datahub.agent.service.impl;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jeju.datahub.agent.service.AiReportBlockVO;
import jeju.datahub.agent.service.AiReportHtmlSanitizer;

/**
 * assistant 답변에서 HTML 보고서 영역을 떼어 내는 추출기 (새니타이즈).
 *
 * <p>Agent 는 보고서를 {@code html_report_start_{UUID}} ~ {@code html_report_end_{UUID}} 마커로 감싸
 * 답변 본문에 섞어 보낸다. 이 클래스는 영역마다 {@code content} 를 자리표시
 * {@code [[AI_REPORT:{n}]]}(n = 0부터)로 바꾸고, 떼어 낸 HTML 을 {@link AiReportBlockVO} 로 따로 돌려준다.
 *
 * <p>🔴 <b>모델 필터({@code AgentResponseFilter})가 아니다</b> — 모델과 무관한 우리 표시 규칙이라
 * 모델 필터 <b>다음</b> 단계에서 돈다. 필터에 넣으면 모델을 바꿀 때 함께 빠진다.
 *
 * <p>🔴 <b>{@code html} 은 새니타이즈된 값이다</b> — 처리 순서는 펜스 제거 → 제목 추출(원문) →
 * {@link AiReportHtmlSanitizer}. 제목을 새니타이즈 <b>전</b>에 뽑는 이유는 {@code <title>} 이 {@code <head>} 안에 있어
 * 새니타이즈 뒤에는 사라지기 때문이다.
 *
 * <p>상태가 없는 일반 클래스다(빈이 아니다). 호출부가 {@code new} 로 들고 있는다.
 *
 * <ul>
 *   <li>마커: UUID 는 고정, {@code START:}·{@code END:} 접두와 앞뒤 공백은 있어도 없어도 된다.
 *       영역 안 {@code ```html} 펜스는 벗긴다.</li>
 *   <li>제목: {@code <title>} → 첫 {@code <h1>} 의 텍스트 → {@code AI 보고서_{yyyyMMdd_HHmm}}, 256자 절단.</li>
 *   <li>크기: 영역 1건이 UTF-8 1MB 를 넘으면 추출하지 않고 원문을 그대로 두며 WARN 1줄.</li>
 *   <li>미완성: 시작 마커만 있고 끝 마커가 없으면 시작 마커부터 끝까지를 자리표시로 바꾸고
 *       {@code stts=PENDING}({@code title}·{@code html} null). {@code TRUNCATED} 판정은 이 클래스가 하지 않는다.</li>
 * </ul>
 */
public class AgentReportExtractor {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(AgentReportExtractor.class);

    /** 시작 마커 접두. 🔴 마커 문자열 리터럴은 이 상수 한 곳이다. */
    static final String MARKER_START = "html_report_start_";

    /** 끝 마커 접두. */
    static final String MARKER_END = "html_report_end_";

    /** 마커 UUID — 고정값(사용자 확인 2026-11-10). 시작·끝이 같은 값을 쓴다. */
    static final String MARKER_UUID = "7b1d8f43-6a92-4c3f-a5e7-9d20e4b8610c";

    /** 영역 1건 크기 상한 — UTF-8 바이트 기준 1MB. */
    static final int MAX_BLOCK_BYTES = 1024 * 1024;

    /** 제목 최대 길이 — {@code rptp_nm VARCHAR(256)}. */
    static final int MAX_TITLE_LEN = 256;

    /** 제목이 없을 때의 접두. */
    static final String DEFAULT_TITLE_PREFIX = "AI 보고서_";

    private static final DateTimeFormatter DEFAULT_TITLE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 시작 마커(접두 {@code START:} 선택) — 정규식 조각. */
    private static final String START_RE = "(?:START:\\s*)?" + Pattern.quote(MARKER_START + MARKER_UUID);

    /** 끝 마커(접두 {@code END:} 선택) — 정규식 조각. */
    private static final String END_RE = "(?:END:\\s*)?" + Pattern.quote(MARKER_END + MARKER_UUID);

    /** 완성된 영역 — 시작 마커부터 <b>첫</b> 끝 마커까지(비탐욕). group(1) 이 본문. */
    private static final Pattern BLOCK = Pattern.compile("(?s)" + START_RE + "\\s*(.*?)\\s*" + END_RE);

    /** 미완성 영역 — 마지막 완성 영역 뒤에 남은 시작 마커부터 끝까지. */
    private static final Pattern UNCLOSED = Pattern.compile("(?s)" + START_RE + ".*$");

    /** 영역 안 코드 펜스 — {@code ```html … ```}(언어 표기는 선택). group(1) 이 안쪽. */
    private static final Pattern FENCE = Pattern.compile("(?s)^```[A-Za-z0-9_-]*[ \\t]*\\R?(.*?)\\s*```$");

    /** 추출 결과 — 자리표시로 바뀐 본문 + 떼어 낸 영역 목록(순번 순). */
    public record Extracted(String content, List<AiReportBlockVO> reports) {}

    private final Clock clock;

    public AgentReportExtractor() {
        this(Clock.system(ZONE));
    }

    /** 테스트 시임 — 기본 제목의 시각을 고정한다. */
    AgentReportExtractor(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 자리표시 문자열 — {@code [[AI_REPORT:{n}]]}. */
    static String placeholder(int idx) {
        return "[[AI_REPORT:" + idx + "]]";
    }

    /**
     * 모델 필터를 거친 {@code content} 에서 보고서 영역을 떼어 낸다.
     *
     * @param content 필터 결과 문자열(null 이면 그대로 돌려준다)
     * @return 자리표시로 바뀐 본문과 영역 목록. 영역이 없으면 원문 그대로 + 빈 목록
     */
    public Extracted extract(String content) {
        if (content == null || !content.contains(MARKER_START + MARKER_UUID)) {
            return new Extracted(content, List.of());
        }

        List<AiReportBlockVO> reports = new ArrayList<>();
        StringBuilder out = new StringBuilder(content.length());
        Matcher m = BLOCK.matcher(content);
        int last = 0;
        while (m.find()) {
            out.append(content, last, m.start());
            last = m.end();

            String body = m.group(1);
            int bytes = body.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BLOCK_BYTES) {
                // 추출하지 않고 원문 유지. 🔒 본문은 로그에 남기지 않는다
                log.warn("[agent-report] 보고서 영역이 상한을 넘어 추출하지 않는다 — bytes={} limit={}",
                        bytes, MAX_BLOCK_BYTES);
                out.append(m.group());
                continue;
            }

            int idx = reports.size();
            String html = stripFence(body);
            String title = extractTitle(html); // 🔴 새니타이즈 전 원문에서 — <title> 은 새니타이즈 뒤 사라진다
            reports.add(new AiReportBlockVO(idx, title, AiReportHtmlSanitizer.sanitize(html),
                    AiReportBlockVO.STTS_DONE));
            out.append(placeholder(idx));
        }

        // 마지막 완성 영역 뒤에 시작 마커만 남았으면 그 뒤 전부를 미완성 영역으로 본다
        String tail = content.substring(last);
        Matcher u = UNCLOSED.matcher(tail);
        if (u.find()) {
            int idx = reports.size();
            out.append(tail, 0, u.start());
            reports.add(new AiReportBlockVO(idx, null, null, AiReportBlockVO.STTS_PENDING));
            out.append(placeholder(idx));
        } else {
            out.append(tail);
        }

        return new Extracted(out.toString(), List.copyOf(reports));
    }

    /** 영역 안 {@code ```html … ```} 펜스를 벗긴다. 펜스가 없으면 앞뒤 공백만 정리한다. */
    private static String stripFence(String body) {
        String s = body.strip();
        Matcher f = FENCE.matcher(s);
        return f.matches() ? f.group(1).strip() : s;
    }

    /** {@code <title>} → 첫 {@code <h1>} → {@code AI 보고서_{yyyyMMdd_HHmm}}, 256자 절단. */
    String extractTitle(String html) {
        Document doc = Jsoup.parse(html);
        String title = doc.title().strip();
        if (title.isEmpty()) {
            Element h1 = doc.selectFirst("h1");
            title = h1 == null ? "" : h1.text().strip();
        }
        if (title.isEmpty()) {
            title = DEFAULT_TITLE_PREFIX + LocalDateTime.now(clock.withZone(ZONE)).format(DEFAULT_TITLE_FMT);
        }
        return truncate(title, MAX_TITLE_LEN);
    }

    /** 코드포인트 기준으로 자른다 — 서로게이트 쌍을 반으로 가르지 않는다. */
    private static String truncate(String s, int max) {
        if (s.codePointCount(0, s.length()) <= max) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, max));
    }
}
