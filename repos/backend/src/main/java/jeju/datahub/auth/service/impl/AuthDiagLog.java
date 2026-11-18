package jeju.datahub.auth.service.impl;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🔴 한시적 진단 로그 (TSK-4349) — 운영서버에서 {@code /userinfo}·{@code /revoke}·토큰 교환·Manager {@code /me}
 * 가 주고받는 값을 확인한 뒤
 * <b>걷어낸다</b>(계획서 Step 8). 스위치 {@code app.auth.diag-log-enabled}(env {@code AUTH_DIAG_LOG}, 기본 false).
 *
 * <p>🔴 <b>토큰 원문은 남기지 않는다</b>(CLAUDE.md "토큰 값은 어떤 레벨로도 남기지 않는다" — 한때 원문을 찍던
 * D1 예외는 걷었다). 토큰은 {@link #fingerprint 지문}과 대조용 claim 만 남긴다. 응답 본문(개인정보 포함)은
 * <b>스위치가 켜졌을 때만</b> 찍힌다. 확인이 끝나면 env 를 끄고, 이 클래스와 호출부를 지운다.
 *
 * <p>⚠️ {@link #fingerprint} 의 SHA-256 은 <b>세션 해시가 아니다</b> — {@code SesnIdHasher} 와 통합하지 않는다
 * (AuthController 의 PKCE {@code sha256()} 와 같은 취급). 원문을 주고받지 않고 Dtonic 과 대조하는 용도다:
 * {@code printf %s '<토큰>' | sha256sum | cut -c1-12}.
 */
public final class AuthDiagLog {

    private static final Logger log = LoggerFactory.getLogger(AuthDiagLog.class);

    static final String TAG = "[oidc-diag] ";

    /** 응답 본문 절단 길이. */
    static final int BODY_MAX = 300;

    /** 토큰 payload 에서 남기는 claim — 대조에 필요한 것만. */
    static final List<String> TOKEN_CLAIMS =
            List.of("iss", "sub", "aud", "azp", "exp", "iat", "scope", "sid");

    private AuthDiagLog() {
    }

    // ------------------------------------------------------------------ 로그

    /** {@code /userinfo} 호출 직전. 토큰은 원문이 아니라 지문·claim 만 — {@code on} 일 때만. */
    public static void userinfoRequest(boolean on, String path, String uri, String token) {
        if (!on) {
            return;
        }
        log.info(TAG + "userinfo 요청 path={} uri={} fp={} claims={}",
                path, uri, fingerprint(token), tokenClaims(token));
    }

    /** {@code /userinfo} 정상 응답. claims 에 개인정보가 섞인다 — {@code on} 일 때만. */
    public static void userinfoResponse(boolean on, String path, int status, long elapsedMs,
                                        Map<String, Object> claims) {
        if (!on) {
            return;
        }
        log.info(TAG + "userinfo 응답 path={} status={} elapsedMs={} claims={}", path, status, elapsedMs, claims);
    }

    /** {@code /userinfo} 실패. status 를 모르면(전송 오류) null. */
    public static void userinfoFailure(boolean on, String path, Integer status, long elapsedMs,
                                       String body, String cause) {
        if (!on) {
            return;
        }
        log.info(TAG + "userinfo 실패 path={} status={} elapsedMs={} body={} cause={}",
                path, status == null ? "-" : status, elapsedMs, truncate(body), cause);
    }

    /**
     * 토큰 교환 성공 직후 — 원재료(로그인 토큰)와 결과(교환 토큰)의 지문, 결과 토큰의 claim 을 남긴다.
     * 토큰 원문은 남기지 않는다 — {@code on} 일 때만.
     */
    public static void exchanged(boolean on, String audience, String subjectToken, String resultToken,
                                 long expiresInSec) {
        if (!on) {
            return;
        }
        log.info(TAG + "exchange 결과 audience={} expiresIn={}s subjectFp={} fp={} claims={}",
                audience, expiresInSec, fingerprint(subjectToken), fingerprint(resultToken),
                tokenClaims(resultToken));
    }

    /** Manager {@code /api/v1/auth/me} 호출 직전. 토큰은 지문·claim 만 — {@code on} 일 때만. */
    public static void meRequest(boolean on, String uri, String token) {
        if (!on) {
            return;
        }
        log.info(TAG + "me 요청 uri={} fp={} claims={}", uri, fingerprint(token), tokenClaims(token));
    }

    /** {@code /me} 정상 응답. 본문에 개인정보가 섞인다 — {@code on} 일 때만, {@link #BODY_MAX} 자 절단. */
    public static void meResponse(boolean on, long elapsedMs, Object body) {
        if (!on) {
            return;
        }
        log.info(TAG + "me 응답 status=200 elapsedMs={} body={}", elapsedMs, truncate(String.valueOf(body)));
    }

    /** {@code /me} 실패. status 를 모르면(전송 오류) null. */
    public static void meFailure(boolean on, Integer status, long elapsedMs, String body, String cause) {
        if (!on) {
            return;
        }
        log.info(TAG + "me 실패 status={} elapsedMs={} body={} cause={}",
                status == null ? "-" : status, elapsedMs, truncate(body), cause);
    }

    // ------------------------------------------------------------------ 부품

    /** SHA-256(UTF-8) 앞 12 hex. null·빈 값은 {@code "-"}. */
    public static String fingerprint(String token) {
        if (token == null || token.isEmpty()) {
            return "-";
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    /**
     * JWT payload 의 {@link #TOKEN_CLAIMS} 만 뽑는다. 🔴 <b>서명 검증이 아니다</b> — 진단 전용.
     * JWT 가 아니면 {@code {opaque}}.
     */
    public static Map<String, Object> tokenClaims(String token) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (token == null || token.isEmpty()) {
            return out;
        }
        JWTClaimsSet set;
        try {
            set = JWTParser.parse(token).getJWTClaimsSet();
        } catch (ParseException | RuntimeException e) {
            out.put("opaque", true);
            return out;
        }
        for (String k : TOKEN_CLAIMS) {
            Object v = set.getClaim(k);
            if (v instanceof java.util.Date date) {
                v = date.toInstant().toString();
            }
            if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** 개행 제거 + {@link #BODY_MAX} 자 절단. null·빈 값은 {@code (empty)}. */
    public static String truncate(String body) {
        if (body == null || body.isBlank()) {
            return "(empty)";
        }
        String flat = body.replace('\r', ' ').replace('\n', ' ');
        return flat.length() <= BODY_MAX ? flat : flat.substring(0, BODY_MAX) + "…(" + flat.length() + "자)";
    }
}
