package jeju.datahub.batch.collect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 원천 ID/적재시각과 독립적인 내용 식별자. DB batch.source_row_hash와 같은 길이 접두 인코딩. */
public final class SourceFingerprint {
    private SourceFingerprint() { }
    public static String hash(String... parts) {
        StringBuilder input = new StringBuilder();
        for (String part : parts) {
            if (part == null) input.append("-1:");
            else input.append(part.codePointCount(0, part.length())).append(':').append(part);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
