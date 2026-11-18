// 🔴 짝이 있다 — 백(jeju.datahub.config.tls)·배치(jeju.datahub.batch.tls) 를 함께 고친다
package jeju.datahub.config.tls;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * 지정 호스트(TLS_INSECURE_HOSTS)만 인증서 검증 실패를 통과시키는 한시적 우회.
 *
 * - 기본 검증을 먼저 하고, 실패했을 때만 허용 호스트면 통과시킨다.
 * - 환경변수가 비면 아무것도 설치하지 않는다. "*"·와일드카드는 기동 시 예외.
 * - main() 의 SpringApplication.run 직전에 설치한다.
 * - 검증 결과를 (결과, 호스트) 최초 1회 로그로 남긴다.
 * - 통과 로그에 검증을 성립시킨 신뢰 앵커(subject·지문)를, 설치 시 신뢰 저장소 경로·앵커 수를 남긴다
 *   (CA 신뢰 저장소로 옮긴 뒤 "어느 CA 로 통과했는가" 를 로그로 확인하려고).
 * - 이 클래스 본문은 백·배치 두 레포에서 글자 그대로 같아야 한다 — 레포별로 다른 내용은 이 줄 위에만 둔다.
 */
public final class InsecureTls {

    // LoggerFactory 로 선언한다 — 배치는 전부 LoggerFactory(@Slf4j 0건), 백엔드는 혼용이라 배치에 맞춰 짝 코드를 같게 둔다
    private static final Logger log = LoggerFactory.getLogger(InsecureTls.class);

    static final String ENV = "TLS_INSECURE_HOSTS";

    static final String UNKNOWN_HOST = "(unknown)";

    /** 결과 로그 중복 억제 — 값은 "{키}|{host}". JVM 수명 동안 (결과, 호스트) 당 1번. */
    static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** 설치된 우회 안내 문구 — 설치 전·OFF 면 null. {@link #logActive()} 가 기동 뒤 다시 찍는다. */
    private static volatile String activeMsg;

    /** 신뢰 저장소 안내 문구 — activeMsg 와 같이 기동 뒤 다시 찍는다. */
    private static volatile String storeMsg;

    private InsecureTls() {
    }

    /** main() 첫 줄에서 부른다. 비어 있으면 아무것도 하지 않는다. */
    public static void installFromEnv() {
        install(System.getenv(ENV));
    }

    static void install(String raw) {
        Set<String> hosts = parse(raw);
        if (hosts.isEmpty()) {
            return;
        }
        SSLContext ctx;
        try {
            X509ExtendedTrustManager base = defaultTrustManager();
            // JAVA_TOOL_OPTIONS 로 바꾼 신뢰 저장소가 실제로 읽혔는지(경로·앵커 수)
            String store = System.getProperty("javax.net.ssl.trustStore");
            storeMsg = "[tls] 신뢰 저장소 — path=" + (store == null || store.isBlank() ? "(JVM 기본 cacerts)" : store)
                    + " anchors=" + base.getAcceptedIssuers().length;
            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { new AllowlistTrustManager(base, hosts) }, null);
        } catch (GeneralSecurityException e) {
            // 켰는데 설치를 못 하면 기동을 끊는다 — 조용히 OFF 로 떠서 같은 인증서 오류로 다시 죽는 것보다 원인이 분명하다
            throw new IllegalStateException("[tls] 인증서 검증 우회 설치 실패", e);
        }
        SSLContext.setDefault(ctx);
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        // 호스트명 우회도 로그 — HttpsURLConnection 은 호스트명 불일치를 이 verifier 로만 통과시킨다
        HttpsURLConnection.setDefaultHostnameVerifier(
                hostnameVerifier(hosts, HttpsURLConnection.getDefaultHostnameVerifier()));
        String msg = "[tls] 인증서 검증 우회 활성 — hosts=" + hosts + " (한시적 우회, removal-checklist 참조)";
        activeMsg = msg;
        log.warn(msg);
        log.info(storeMsg);
    }

    /**
     * SpringApplication.run 뒤에 부른다 — 🔴 배치(log4j2-spring.xml)는 Boot 로깅 초기화 전 WARN 이 버려져
     * {@link #install} 의 WARN 이 안 남는다. 그 자리를 메우던 System.err 출력은 시큐어코딩 6.2(디버그 코드)로
     * 걷었다 — 두 레포 코드를 같게 두려고 백엔드도 부른다(백엔드는 같은 WARN 이 두 번 남는다).
     */
    public static void logActive() {
        String msg = activeMsg;
        if (msg != null) {
            log.warn(msg);
        }
        String store = storeMsg;
        if (store != null) {
            log.info(store);
        }
    }

    /** trim · 소문자(Locale.ROOT) · 빈 값 제거. "*" 를 포함하면 IllegalArgumentException. */
    static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String h = token.trim().toLowerCase(Locale.ROOT);
            if (h.isEmpty()) {
                continue;
            }
            if (h.contains("*")) {
                throw new IllegalArgumentException(
                        "[tls] " + ENV + " 에 와일드카드는 허용하지 않는다 — 호스트를 정확히 적는다: " + h);
            }
            hosts.add(h);
        }
        return Collections.unmodifiableSet(hosts);
    }

    /** 허용 호스트면 true(+ HOSTNAME_BYPASS 기록), 그 밖은 dflt 결과 그대로. */
    static HostnameVerifier hostnameVerifier(Set<String> hosts, HostnameVerifier dflt) {
        return (String hostname, SSLSession session) -> {
            String h = hostname == null ? null : hostname.toLowerCase(Locale.ROOT);
            if (h != null && hosts.contains(h)) {
                if (LOGGED.add("HOSTNAME_BYPASS|" + h)) {
                    log.warn("[tls] 호스트명 검증 우회 — host={} (HttpsURLConnection)", h);
                }
                return true;
            }
            return dflt.verify(hostname, session);
        };
    }

    /**
     * 체인 최상위 인증서를 서명한 신뢰 앵커 — 최상위의 issuer 와 subject 가 같은 앵커, 없으면 최상위 자신과 같은 앵커.
     * 못 찾으면 null(로그 전용, 판정에 쓰지 않는다).
     */
    static X509Certificate anchorOf(X509Certificate[] chain, X509Certificate[] anchors) {
        if (chain == null || chain.length == 0 || anchors == null) {
            return null;
        }
        X509Certificate top = chain[chain.length - 1];
        for (X509Certificate a : anchors) {
            if (a != null && a.getSubjectX500Principal().equals(top.getIssuerX500Principal())) {
                return a;
            }
        }
        for (X509Certificate a : anchors) {
            if (a != null && a.equals(top)) {
                return a;
            }
        }
        return null;
    }

    /** 인증서 SHA-256 지문 앞 16 hex. 실패하면 "?". */
    static String fingerprint(X509Certificate cert) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))
                    .substring(0, 16);
        } catch (GeneralSecurityException | RuntimeException e) {
            return "?";
        }
    }

    /** TrustManagerFactory.init((KeyStore) null) = JVM 기본 cacerts. */
    private static X509ExtendedTrustManager defaultTrustManager() throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager x) {
                return x;
            }
        }
        throw new KeyStoreException("[tls] JVM 기본 X509ExtendedTrustManager 를 찾지 못했다");
    }

    /**
     * 기본 검증 먼저, 실패 시 허용 호스트면 통과. 결과는 (결과, 호스트) 최초 1회 로그.
     */
    static final class AllowlistTrustManager extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager base;
        private final Set<String> hosts;

        AllowlistTrustManager(X509ExtendedTrustManager base, Set<String> hosts) {
            this.base = base;
            this.hosts = hosts;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            try {
                base.checkServerTrusted(chain, authType, socket);
            } catch (CertificateException e) {
                onFailure(chain, peerHost(socket), e);
                return;
            }
            logPass(chain, peerHost(socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            try {
                base.checkServerTrusted(chain, authType, engine);
            } catch (CertificateException e) {
                onFailure(chain, peerHost(engine), e);
                return;
            }
            logPass(chain, peerHost(engine));
        }

        /** 피어 호스트를 얻을 수 없으므로 우회하지 않는다(base 그대로). */
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                base.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                logReject(chain, null, e);
                throw e;
            }
            logPass(chain, null);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            base.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            base.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            base.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return base.getAcceptedIssuers();
        }

        /** 허용 호스트면 삼키고 통과, 없거나 피어 호스트를 못 얻으면(null) 그대로 던진다. */
        private void onFailure(X509Certificate[] chain, String host, CertificateException e)
                throws CertificateException {
            if (host != null && hosts.contains(host)) {
                if (LOGGED.add("BYPASS|" + host)) {
                    log.warn("[tls] 인증서 검증 우회 — host={} issuer={} cause={}",
                            host, lastIssuer(chain), e.getMessage());
                }
                return;
            }
            logReject(chain, host, e);
            throw e;
        }

        private void logPass(X509Certificate[] chain, String host) {
            String h = host == null ? UNKNOWN_HOST : host;
            if (LOGGED.add("PASS|" + h)) {
                // 어느 신뢰 앵커(CA)로 통과했는지. 최초 1회라 getAcceptedIssuers() 비용은 무시한다
                X509Certificate anchor = anchorOf(chain, base.getAcceptedIssuers());
                log.info("[tls] 인증서 검증 통과 — host={} subject={} issuer={} anchor={} anchorFp={}",
                        h, firstSubject(chain), lastIssuer(chain),
                        anchor == null ? "(not found)" : String.valueOf(anchor.getSubjectX500Principal()),
                        anchor == null ? "-" : fingerprint(anchor));
            }
        }

        private static void logReject(X509Certificate[] chain, String host, CertificateException e) {
            String h = host == null ? UNKNOWN_HOST : host;
            if (LOGGED.add("REJECT|" + h)) {
                log.warn("[tls] 인증서 검증 실패(거부) — host={} issuer={} cause={}",
                        h, lastIssuer(chain), e.getMessage());
            }
        }

        private static String peerHost(Socket socket) {
            if (socket instanceof SSLSocket ssl) {
                SSLSession session = ssl.getHandshakeSession();
                if (session != null) {
                    return lower(session.getPeerHost());
                }
            }
            return null;
        }

        private static String peerHost(SSLEngine engine) {
            return engine == null ? null : lower(engine.getPeerHost());
        }

        private static String lower(String s) {
            return s == null ? null : s.toLowerCase(Locale.ROOT);
        }

        private static String firstSubject(X509Certificate[] chain) {
            if (chain == null || chain.length == 0) {
                return "(none)";
            }
            return String.valueOf(chain[0].getSubjectX500Principal());
        }

        private static String lastIssuer(X509Certificate[] chain) {
            if (chain == null || chain.length == 0) {
                return "(none)";
            }
            return String.valueOf(chain[chain.length - 1].getIssuerX500Principal());
        }
    }
}
