package jeju.datahub.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

/** {@link AuthDiagLog} 단위 테스트 (TSK-4349 Step 1 — 한시적 진단 로그). */
class AuthDiagLogTest {

    private static String jwt() {
        return new PlainJWT(new JWTClaimsSet.Builder()
                .issuer("https://manager.example.test")
                .subject("user-1")
                .audience(List.of("oidc-test-innopam"))
                .claim("azp", "oidc-test-innopam")
                .claim("scope", "openid profile email")
                .claim("email", "secret@example.test")          // 뽑지 않아야 하는 claim
                .issueTime(Date.from(Instant.parse("2026-11-15T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2026-11-15T00:15:00Z")))
                .build()).serialize();
    }

    @Test
    @DisplayName("지문은 sha256sum 앞 12자와 같다 — 같은 토큰이면 같은 값")
    void fingerprintMatchesSha256sum() throws Exception {
        String token = jwt();
        String expected = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 12);
        assertThat(AuthDiagLog.fingerprint(token)).isEqualTo(expected).hasSize(12);
        assertThat(AuthDiagLog.fingerprint(token)).isEqualTo(AuthDiagLog.fingerprint(new String(token)));
        assertThat(AuthDiagLog.fingerprint(null)).isEqualTo("-");
    }

    @Test
    @DisplayName("토큰 claim 은 대조용 8개만 — exp/iat 는 ISO 문자열")
    void tokenClaimsPicksWhitelist() {
        Map<String, Object> claims = AuthDiagLog.tokenClaims(jwt());
        assertThat(claims).containsEntry("iss", "https://manager.example.test")
                .containsEntry("sub", "user-1")
                .containsEntry("azp", "oidc-test-innopam")
                .containsEntry("exp", "2026-11-15T00:15:00Z")
                .containsEntry("iat", "2026-11-15T00:00:00Z")
                .doesNotContainKey("email");
        assertThat(claims.get("aud")).isEqualTo(List.of("oidc-test-innopam"));
    }

    @Test
    @DisplayName("JWT 가 아니면 {opaque}")
    void opaqueToken() {
        assertThat(AuthDiagLog.tokenClaims("not-a-jwt")).containsEntry("opaque", true);
        assertThat(AuthDiagLog.tokenClaims(null)).isEmpty();
    }

    @Test
    @DisplayName("본문은 개행 제거 + 300자 절단, 비면 (empty)")
    void truncate() {
        assertThat(AuthDiagLog.truncate(null)).isEqualTo("(empty)");
        assertThat(AuthDiagLog.truncate("  ")).isEqualTo("(empty)");
        assertThat(AuthDiagLog.truncate("a\nb")).isEqualTo("a b");
        String longBody = "x".repeat(500);
        assertThat(AuthDiagLog.truncate(longBody)).startsWith("x".repeat(300)).endsWith("…(500자)");
    }
}
