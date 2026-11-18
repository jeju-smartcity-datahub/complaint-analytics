package jeju.datahub.config.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@link InsecureTls} 실호출 테스트.
 *
 * <p>⚠️ 외부망(*.badssl.com · TLS_LIVE_MANAGER_OIDC_URL 로 지정한 OIDC discovery 주소) 접속이 필요하다 — 타임아웃·UnknownHostException 이면
 * 코드가 아니라 네트워크를 먼저 본다.
 *
 * <p>🔴 {@code install()} 은 JVM 전역 상태(기본 SSLContext · hostname verifier)를 바꾸므로 {@code @BeforeAll}·static 초기화에
 * 두지 않는다 — 가드가 메서드 단위라 {@code @BeforeAll} 은 키가 없어도 돈다. 가드된 각 {@code @Test} 안에서 {@link #installOnce()} 를 부른다.
 * 실행은 단독 {@code --tests} 로만 한다:
 * {@code TLS_LIVE_TEST=1 ./gradlew test --rerun --tests 'jeju.datahub.config.tls.InsecureTlsLiveTest'}
 */
class InsecureTlsLiveTest {

    private static final String ALLOWED = "untrusted-root.badssl.com,wrong.host.badssl.com";

    private static final String UNTRUSTED_ROOT = "https://untrusted-root.badssl.com/";
    private static final String WRONG_HOST = "https://wrong.host.badssl.com/";
    private static final String SELF_SIGNED = "https://self-signed.badssl.com/";
    private static final String MANAGER_OIDC = System.getenv("TLS_LIVE_MANAGER_OIDC_URL");

    private static final int TIMEOUT_MS = 10_000;

    private static boolean installed;

    /** 가드된 테스트 안에서만 부른다 — 실행 순서와 무관하게 1회만 설치한다. */
    private static synchronized void installOnce() {
        if (!installed) {
            InsecureTls.install(ALLOWED);
            installed = true;
        }
    }

    private static int getViaUrlConnection(String url) throws IOException {
        HttpsURLConnection conn = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        try {
            int status = conn.getResponseCode();
            try (InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                if (in != null) {
                    in.readAllBytes();
                }
            }
            return status;
        } finally {
            conn.disconnect();
        }
    }

    private static int getViaHttpClient(String url) throws IOException, InterruptedException {
        // 🔴 install 이후 생성해야 새 기본 SSLContext 를 잡는다
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .GET()
                .build();
        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return res.statusCode();
    }

    private static boolean hasSslHandshakeCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SSLHandshakeException) {
                return true;
            }
        }
        return false;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TLS_LIVE_TEST", matches = "1")
    @DisplayName("a. 허용 호스트 · 신뢰 안 되는 루트 — HttpsURLConnection → 200")
    void untrustedRootViaUrlConnection() throws Exception {
        installOnce();
        assertThat(getViaUrlConnection(UNTRUSTED_ROOT)).isEqualTo(200);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TLS_LIVE_TEST", matches = "1")
    @DisplayName("b. 허용 호스트 · 신뢰 안 되는 루트 — JDK HttpClient → 200")
    void untrustedRootViaHttpClient() throws Exception {
        installOnce();
        assertThat(getViaHttpClient(UNTRUSTED_ROOT)).isEqualTo(200);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TLS_LIVE_TEST", matches = "1")
    @DisplayName("c. 허용 호스트 · 호스트명 불일치 — HttpsURLConnection → 200 (hostname verifier 경로)")
    void wrongHostViaUrlConnection() throws Exception {
        installOnce();
        assertThat(getViaUrlConnection(WRONG_HOST)).isEqualTo(200);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TLS_LIVE_TEST", matches = "1")
    @DisplayName("d. 허용 호스트 · 호스트명 불일치 — JDK HttpClient → 200 (base catch 경로)")
    void wrongHostViaHttpClient() throws Exception {
        installOnce();
        assertThat(getViaHttpClient(WRONG_HOST)).isEqualTo(200);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TLS_LIVE_TEST", matches = "1")
    @DisplayName("e. 허용 밖 · 자체 서명 — HttpsURLConnection → SSLHandshakeException (범위가 새지 않는다)")
    void selfSignedOutsideAllowlistRejected() {
        installOnce();
        IOException e = assertThrows(IOException.class, () -> getViaUrlConnection(SELF_SIGNED));
        assertThat(hasSslHandshakeCause(e))
                .as("원인 체인에 SSLHandshakeException 이 있어야 한다: %s", e)
                .isTrue();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TLS_LIVE_TEST", matches = "1")
    @EnabledIfEnvironmentVariable(named = "TLS_LIVE_MANAGER_OIDC_URL", matches = ".+")
    @DisplayName("f. 정상 인증서 — manager OIDC discovery → 200 (base 경로로 통과)")
    void managerOidcDiscovery() throws Exception {
        installOnce();
        assertThat(getViaUrlConnection(MANAGER_OIDC)).isEqualTo(200);
    }
}
