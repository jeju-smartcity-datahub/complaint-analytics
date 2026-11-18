package jeju.datahub.batch.history;

import java.util.regex.Pattern;

/**
 * 🔒 비밀값 마스킹 공용 규칙 — {@code key=...} / {@code Bearer ...} 형태의 <b>값만</b> {@code ***} 로 바꾼다.
 *
 * <p>TSK-4337 Step 5 에서 {@link BatchRunHistoryRecorder} 의 {@code private static SECRET} 을 옮겨 왔다.
 * 실행 이력의 실패 메시지 · 실행별 로그 파일(Log4j2 {@code %mask{}} — {@link SecretMaskConverter}) · 로그 조회 API
 * 응답이 <b>같은 규칙</b>을 쓴다. 🔴 패턴을 복사해 따로 두지 않는다 — 한쪽만 고치면 다른 쪽으로 샌다.
 *
 * <p>완벽한 차단이 아니라 <b>흔한 유출 형태를 막는 그물</b>이다. 최종 방어선은 이력의 길이 제한·로그 파일의 INFO 하한이며,
 * 클라이언트 계층이 애초에 응답 본문·자격증명을 예외 메시지·로그에 싣지 않는 것이 1차 계약이다
 * ({@code DhubQueryClient} 주석).
 */
public final class SecretMasker {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(bearer\\s+|\\b(?:api[-_]?key|authkey|auth|access[-_]?token|token|password|passwd"
                    + "|pwd|secret|credential|key)\\b\\s*[=:]\\s*)\\S+");

    private SecretMasker() {
    }

    /** {@code token=abc} → {@code token=***}. {@code null} 은 {@code null}. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return SECRET.matcher(text).replaceAll("$1***");
    }
}
