package jeju.datahub.batchops.service.impl;

import java.util.regex.Pattern;

/**
 * 🔒 배치 메시지 마스킹 — {@code key=...} / {@code Bearer ...} 형태의 <b>값만</b> {@code ***} 로 바꾼다 (TSK-4337 Step 6).
 *
 * <p>🔴 <b>배치 레포 {@code jeju.datahub.batch.history.SecretMasker} 와 같은 규칙이다 — 함께 고칠 것.</b>
 * 두 레포라 코드를 공유할 수 없어 패턴을 복사했다. 이력 {@code failure_message} 는 배치가 이미 마스킹했지만
 * Spring Batch 메타의 {@code exit_message}(Job·Step)는 <b>마스킹 전 스택 트레이스</b>일 수 있어 백엔드가 내리기 전에
 * 한 번 더 건다(이미 마스킹된 값에 다시 걸어도 결과가 같다 — 멱등).
 */
final class BatchOpsSecretMasker {

    /** 배치 SecretMasker.SECRET 과 같은 정규식 */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(bearer\\s+|\\b(?:api[-_]?key|authkey|auth|access[-_]?token|token|password|passwd"
                    + "|pwd|secret|credential|key)\\b\\s*[=:]\\s*)\\S+");

    /** 절단 표시 */
    static final String ELLIPSIS = "…";

    private BatchOpsSecretMasker() {
    }

    /** {@code token=abc} → {@code token=***}. {@code null} 은 {@code null}. */
    static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return SECRET.matcher(text).replaceAll("$1***");
    }

    /** 마스킹 후 {@code max} 자를 넘으면 잘라 {@link #ELLIPSIS} 를 붙인다. 🔴 마스킹이 먼저다(자른 뒤 마스킹하면 패턴이 깨질 수 있다). */
    static String maskAndCut(String text, int max) {
        String masked = mask(text);
        if (masked == null || masked.length() <= max) {
            return masked;
        }
        return masked.substring(0, max) + ELLIPSIS;
    }
}
