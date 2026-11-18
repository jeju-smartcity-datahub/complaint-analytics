package jeju.datahub.batch.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.batch.tls.InsecureTls.AllowlistTrustManager;

/**
 * {@link InsecureTls} 단위 테스트 (TSK-4279 Step 1).
 *
 * <p>네트워크 없이 Mockito 로 기본 TrustManager({@code base})를 흉내 낸다. 결과 로그는 캡처하지 않고
 * 중복 억제 집합 {@link InsecureTls#LOGGED} 에 기록됐는지로 단언한다.
 *
 * <p>⚠️ {@code install()} 은 JVM 전역 상태를 바꾸므로 OFF 경로(케이스 1)만 호출한다 — ON 경로는 Step 4 실호출로 본다.
 */
class InsecureTlsTest {

    private static final String MANAGER = "manager.project.dtonic.io";
    private static final String AGENT = "agent.project.dtonic.io";
    private static final String OTHER = "api.vworld.kr";
    private static final Set<String> HOSTS = Set.of(MANAGER, AGENT);

    private X509ExtendedTrustManager base;
    private AllowlistTrustManager tm;
    private X509Certificate[] chain;

    @BeforeEach
    void setUp() {
        InsecureTls.LOGGED.clear();
        base = mock(X509ExtendedTrustManager.class);
        tm = new AllowlistTrustManager(base, HOSTS);
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=" + MANAGER));
        when(cert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Inspection CA, O=Example"));
        chain = new X509Certificate[] { cert };
    }

    private void baseFails() throws CertificateException {
        CertificateException e = new CertificateException("PKIX path building failed");
        doThrow(e).when(base).checkServerTrusted(any(), anyString(), any(SSLEngine.class));
        doThrow(e).when(base).checkServerTrusted(any(), anyString(), any(Socket.class));
        doThrow(e).when(base).checkServerTrusted(any(), anyString());
    }

    private static SSLEngine engine(String peerHost) {
        SSLEngine engine = mock(SSLEngine.class);
        when(engine.getPeerHost()).thenReturn(peerHost);
        return engine;
    }

    @Test
    @DisplayName("1. 빈 입력 → 빈 집합, install 이 기본 SSLContext 를 바꾸지 않는다")
    void emptyInputIsOff() throws Exception {
        assertThat(InsecureTls.parse(null)).isEmpty();
        assertThat(InsecureTls.parse("")).isEmpty();
        assertThat(InsecureTls.parse(" , ")).isEmpty();

        SSLContext before = SSLContext.getDefault();
        InsecureTls.install(null);
        InsecureTls.install("");
        InsecureTls.install(" , ");
        assertThat(SSLContext.getDefault()).isSameAs(before);
    }

    @Test
    @DisplayName("2. trim · 소문자 정규화 2건")
    void parseNormalizes() {
        assertThat(InsecureTls.parse(" Manager.Project.dtonic.io ,agent.project.dtonic.io"))
                .containsExactlyInAnyOrder(MANAGER, AGENT);
    }

    @Test
    @DisplayName("3. 와일드카드는 IllegalArgumentException")
    void wildcardRejected() {
        assertThatThrownBy(() -> InsecureTls.parse("*")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InsecureTls.parse("*.dtonic.io")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("4. base 통과 + 허용 밖 호스트 → 예외 없음, PASS 기록")
    void basePassOutsideAllowlist() {
        assertThatCode(() -> tm.checkServerTrusted(chain, "RSA", engine(OTHER))).doesNotThrowAnyException();
        assertThat(InsecureTls.LOGGED).contains("PASS|" + OTHER);
    }

    @Test
    @DisplayName("5. base 실패 + 허용 호스트(SSLEngine, 대소문자 혼용) → 통과, BYPASS 기록")
    void bypassViaEngine() throws Exception {
        baseFails();
        assertThatCode(() -> tm.checkServerTrusted(chain, "RSA", engine("Manager.Project.dtonic.io")))
                .doesNotThrowAnyException();
        assertThat(InsecureTls.LOGGED).contains("BYPASS|" + MANAGER);
    }

    @Test
    @DisplayName("6. base 실패 + 허용 밖 호스트 → 예외 그대로, REJECT 기록")
    void rejectOutsideAllowlist() throws Exception {
        baseFails();
        assertThatThrownBy(() -> tm.checkServerTrusted(chain, "RSA", engine(OTHER)))
                .isInstanceOf(CertificateException.class);
        assertThat(InsecureTls.LOGGED).contains("REJECT|" + OTHER);
    }

    @Test
    @DisplayName("7. base 실패 + 2인자 오버로드 → 예외 그대로, REJECT|(unknown) 기록")
    void twoArgNeverBypasses() throws Exception {
        baseFails();
        assertThatThrownBy(() -> tm.checkServerTrusted(chain, "RSA"))
                .isInstanceOf(CertificateException.class);
        assertThat(InsecureTls.LOGGED).contains("REJECT|(unknown)");
    }

    @Test
    @DisplayName("8. base 실패 + 허용 호스트(Socket 경로) → 통과, BYPASS 기록")
    void bypassViaSocket() throws Exception {
        baseFails();
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerHost()).thenReturn(AGENT);
        SSLSocket socket = mock(SSLSocket.class);
        when(socket.getHandshakeSession()).thenReturn(session);

        assertThatCode(() -> tm.checkServerTrusted(chain, "RSA", socket)).doesNotThrowAnyException();
        assertThat(InsecureTls.LOGGED).contains("BYPASS|" + AGENT);
    }

    @Test
    @DisplayName("9. base 실패 + 피어 호스트 null(SSLEngine · Socket) → 예외 그대로")
    void nullPeerHostRejects() throws Exception {
        baseFails();
        assertThatThrownBy(() -> tm.checkServerTrusted(chain, "RSA", engine(null)))
                .isInstanceOf(CertificateException.class);

        SSLSocket socket = mock(SSLSocket.class);
        when(socket.getHandshakeSession()).thenReturn(null);
        assertThatThrownBy(() -> tm.checkServerTrusted(chain, "RSA", socket))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    @DisplayName("10. hostnameVerifier — 허용 호스트 true(+기록) · 허용 밖은 dflt 결과(false, 기록 없음)")
    void hostnameVerifier() {
        HostnameVerifier dflt = mock(HostnameVerifier.class);
        when(dflt.verify(anyString(), any())).thenReturn(false);
        SSLSession session = mock(SSLSession.class);
        HostnameVerifier hv = InsecureTls.hostnameVerifier(HOSTS, dflt);

        assertThat(hv.verify("Manager.Project.Dtonic.IO", session)).isTrue();
        assertThat(InsecureTls.LOGGED).contains("HOSTNAME_BYPASS|" + MANAGER);

        assertThat(hv.verify(OTHER, session)).isFalse();
        assertThat(InsecureTls.LOGGED).doesNotContain("HOSTNAME_BYPASS|" + OTHER);
    }

    @Test
    @DisplayName("TSK-4349 D8 — anchorOf: 체인 최상위의 issuer 와 subject 가 같은 앵커를 고른다")
    void anchorOfMatchesIssuer() {
        X509Certificate other = mock(X509Certificate.class);
        when(other.getSubjectX500Principal()).thenReturn(new X500Principal("CN=ISRG Root X1"));
        X509Certificate inspection = mock(X509Certificate.class);
        when(inspection.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Inspection CA, O=Example"));

        assertThat(InsecureTls.anchorOf(chain, new X509Certificate[] { other, inspection })).isSameAs(inspection);
        assertThat(InsecureTls.anchorOf(chain, new X509Certificate[] { other })).isNull();
        assertThat(InsecureTls.anchorOf(new X509Certificate[0], new X509Certificate[] { inspection })).isNull();
        assertThat(InsecureTls.anchorOf(chain, null)).isNull();
    }

    @Test
    @DisplayName("TSK-4349 D8 — 통과 로그는 앵커 조회가 실패해도(지문 계산 불가) 통과 판정을 바꾸지 않는다")
    void passLogsAnchorWithoutAffectingResult() throws Exception {
        X509Certificate inspection = mock(X509Certificate.class);
        when(inspection.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Inspection CA, O=Example"));
        when(base.getAcceptedIssuers()).thenReturn(new X509Certificate[] { inspection });

        assertThatCode(() -> tm.checkServerTrusted(chain, "RSA", engine(MANAGER))).doesNotThrowAnyException();
        assertThat(InsecureTls.LOGGED).contains("PASS|" + MANAGER);
        assertThat(InsecureTls.fingerprint(inspection)).isEqualTo("?");   // 목은 getEncoded()=null
    }
}
