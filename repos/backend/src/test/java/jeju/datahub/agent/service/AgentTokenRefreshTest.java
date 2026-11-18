package jeju.datahub.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

import jeju.datahub.agent.service.AgentApiException.Code;

/**
 * {@link AgentTokenStore} 의 <b>갱신</b> 단위 테스트.
 *
 * <p>🔴 <b>기존 {@code AgentTokenStoreTest} 를 손대지 않으려고 파일을 나눴다.</b> 그 클래스는
 * 계약(갱신 없음)을 지키는 회귀 방어이며, 이 작업으로 <b>한 줄도 바뀌면 안 된다</b>
 * — 특히 {@code new AgentTokenStore()}(무인자) 호출이 그대로 컴파일되는 것이 성공 판정이다.
 * 그래서 {@link TokenRefresher} 는 <b>생성자가 아니라 필드</b>로 꽂는다.
 *
 * <h2>🔴 업스트림을 띄우지 않는다</h2>
 * 이 테스트가 검증하는 것은 <b>{@code AgentTokenStore} 의 갱신 판단</b>이지 HTTP 왕복이 아니다.
 * {@code TokenRefresher} 를 람다로 대체해 "몇 번 불렀나 · 실패하면 어떻게 되나" 만 본다.
 * 실제 쿠키 왕복({@code Cookie: refresh_token=…})은 {@code DhubPasswordAuthClientTest} 의 몫이다.
 */
class AgentTokenRefreshTest {

    private static final String OLD_ACCESS = "old-access-token";
    private static final String NEW_ACCESS = "new-access-token";
    private static final String OLD_REFRESH = "old-refresh-token";
    private static final String ROTATED_REFRESH = "rotated-refresh-token";

    /** 🔴 무인자 생성 — 기존 테스트와 같은 방식이다. refresher 는 필요할 때만 필드로 꽂는다. */
    private final AgentTokenStore store = new AgentTokenStore();

    // ------------------------------------------------------------------ 픽스처

    /** 만료 시각을 지정해 세션에 토큰 3종을 심는다. */
    private static MockHttpSession sessionWith(Instant expiresAt) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AgentTokenStore.ATTR_ACCESS, OLD_ACCESS);
        session.setAttribute(AgentTokenStore.ATTR_EXPIRES, expiresAt);
        session.setAttribute(AgentTokenStore.ATTR_REFRESH, OLD_REFRESH);
        return session;
    }

    /** 갱신 성공 응답 — {@code rotated} 가 있으면 회전된 refresh 값까지 실어 준다. */
    private static OAuth2AccessTokenResponse renewed(String rotated) {
        OAuth2AccessTokenResponse.Builder b = OAuth2AccessTokenResponse.withToken(NEW_ACCESS)
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(900L);       // 🔴 운영 코드에는 이 상수가 없다 — exp-iat 에서 온다
        if (rotated != null) {
            b.refreshToken(rotated);
        }
        return b.build();
    }

    // ------------------------------------------------------------------ 선제 갱신

    @Test
    @DisplayName("만료된 토큰 + refresher 있음 → 갱신하고 새 토큰으로 call 을 1회 실행한다")
    void expiredTokenIsRefreshedBeforeCall() {
        MockHttpSession session = sessionWith(Instant.now().minus(1, ChronoUnit.MINUTES));
        AtomicInteger refreshCount = new AtomicInteger();
        store.tokenRefresher = (s, refresh) -> {
            assertThat(refresh).isEqualTo(OLD_REFRESH);   // 🔴 세션에 담아 둔 값을 되돌려준다
            refreshCount.incrementAndGet();
            return renewed(null);
        };

        AtomicInteger callCount = new AtomicInteger();
        String used = store.withToken(session, token -> {
            callCount.incrementAndGet();
            return token;
        });

        assertThat(used).isEqualTo(NEW_ACCESS);
        assertThat(refreshCount).hasValue(1);
        assertThat(callCount).hasValue(1);
        // 갱신 결과가 세션에 반영된다 — 다음 요청이 다시 갱신하지 않는다.
        assertThat(session.getAttribute(AgentTokenStore.ATTR_ACCESS)).isEqualTo(NEW_ACCESS);
        assertThat(session.getAttribute(AgentTokenStore.ATTR_EXPIRES)).isNotNull();
        // 🔴 회전이 없으면 종전 refresh 값이 그대로 남는다 — 지워 버리면 다음 갱신이 불가능해진다.
        assertThat(session.getAttribute(AgentTokenStore.ATTR_REFRESH)).isEqualTo(OLD_REFRESH);
    }

    @Test
    @DisplayName("만료 임박(60초 여유 안)이면 부르기 전에 선제 갱신한다")
    void tokenExpiringSoonIsRefreshedPreemptively() {
        MockHttpSession session = sessionWith(Instant.now().plusSeconds(10));
        AtomicInteger refreshCount = new AtomicInteger();
        store.tokenRefresher = (s, refresh) -> {
            refreshCount.incrementAndGet();
            return renewed(null);
        };

        String used = store.withToken(session, token -> token);

        assertThat(used).isEqualTo(NEW_ACCESS);
        assertThat(refreshCount).hasValue(1);
    }

    @Test
    @DisplayName("여유가 넉넉하면 갱신하지 않고 기존 토큰을 그대로 쓴다")
    void healthyTokenIsNotRefreshed() {
        MockHttpSession session = sessionWith(Instant.now().plusSeconds(600));
        AtomicInteger refreshCount = new AtomicInteger();
        store.tokenRefresher = (s, refresh) -> {
            refreshCount.incrementAndGet();
            return renewed(null);
        };

        String used = store.withToken(session, token -> token);

        assertThat(used).isEqualTo(OLD_ACCESS);
        assertThat(refreshCount).hasValue(0);
    }

    @Test
    @DisplayName("갱신 응답에 새 Set-Cookie 가 실려 오면(회전) 세션의 refresh 값을 교체한다")
    void rotatedRefreshTokenReplacesStoredValue() {
        MockHttpSession session = sessionWith(Instant.now().minusSeconds(1));
        store.tokenRefresher = (s, refresh) -> renewed(ROTATED_REFRESH);

        store.withToken(session, token -> token);

        assertThat(session.getAttribute(AgentTokenStore.ATTR_REFRESH)).isEqualTo(ROTATED_REFRESH);
    }

    // ------------------------------------------------------------------ 사후 갱신 (upstream 401)

    @Test
    @DisplayName("upstream 401 → 갱신 후 정확히 1회만 재시도한다")
    void upstream401TriggersRefreshAndExactlyOneRetry() {
        MockHttpSession session = sessionWith(Instant.now().plusSeconds(600));   // 아직 유효하다
        AtomicInteger refreshCount = new AtomicInteger();
        store.tokenRefresher = (s, refresh) -> {
            refreshCount.incrementAndGet();
            return renewed(null);
        };

        AtomicInteger callCount = new AtomicInteger();
        String used = store.withToken(session, token -> {
            if (callCount.incrementAndGet() == 1) {
                // 첫 호출은 upstream 401 — AgentApiClient 가 이 코드로 던진다.
                throw new AgentApiException(Code.AGENT_UNAUTHORIZED);
            }
            return token;
        });

        assertThat(used).isEqualTo(NEW_ACCESS);
        assertThat(refreshCount).hasValue(1);
        assertThat(callCount).hasValue(2);      // 🔴 최초 1 + 재시도 1 = 2. 그 이상이면 루프다.
    }

    @Test
    @DisplayName("🔴 재시도도 401 이면 그대로 전파한다 — 다시 갱신하지 않는다(무한 루프 방지)")
    void retryIsNotRepeated() {
        MockHttpSession session = sessionWith(Instant.now().plusSeconds(600));
        AtomicInteger refreshCount = new AtomicInteger();
        store.tokenRefresher = (s, refresh) -> {
            refreshCount.incrementAndGet();
            return renewed(null);
        };

        AtomicInteger callCount = new AtomicInteger();
        Throwable thrown = catchThrowable(() -> store.withToken(session, token -> {
            callCount.incrementAndGet();
            throw new AgentApiException(Code.AGENT_UNAUTHORIZED);
        }));

        assertThat(thrown).isInstanceOf(AgentApiException.class);
        assertThat(((AgentApiException) thrown).getCode()).isEqualTo(Code.AGENT_UNAUTHORIZED);
        assertThat(callCount).hasValue(2);      // 🔴 3 이면 재시도가 반복된 것이다
        assertThat(refreshCount).hasValue(1);
    }

    @Test
    @DisplayName("401 이 아닌 실패(502 등)는 갱신하지 않고 그대로 전파한다")
    void nonAuthFailureIsNotRefreshed() {
        MockHttpSession session = sessionWith(Instant.now().plusSeconds(600));
        AtomicInteger refreshCount = new AtomicInteger();
        store.tokenRefresher = (s, refresh) -> {
            refreshCount.incrementAndGet();
            return renewed(null);
        };

        AtomicInteger callCount = new AtomicInteger();
        Throwable thrown = catchThrowable(() -> store.withToken(session, token -> {
            callCount.incrementAndGet();
            throw new AgentApiException(Code.AGENT_UPSTREAM_ERROR);
        }));

        assertThat(((AgentApiException) thrown).getCode()).isEqualTo(Code.AGENT_UPSTREAM_ERROR);
        assertThat(callCount).hasValue(1);
        assertThat(refreshCount).hasValue(0);
    }

    // ------------------------------------------------------------------ 전송(POST) 경로 (②)

    /**
     * ② 전송 경로의 401 재시도.
     *
     * <p>🔴 <b>전송 전용 재시도를 새로 만들지 않았다</b> — {@code AiSessionController.dispatch} 는
     * 조회와 똑같이 {@code withToken} 으로 감싼다. 그래서 이 케이스가 여기(갱신 정책이 모인 자리)에
     * 있고 {@code AgentChatClientTest} 에 없다.
     *
     * <p>🔴 <b>POST 라도 안전한 이유</b>: 재시도 대상은 upstream 401 뿐이고, 401 은 업스트림이 요청을
     * <b>거절</b>한 것이라 {@code user} 턴이 적재되지 않는다. "응답 받다가 끊김"(502)은 401 이 아니라
     * 재시도되지 않는다 — 그것은 {@link #nonAuthFailureIsNotRefreshed} 와
     * {@code AgentChatClientTest.brokenResponseIsNotRetried} 가 함께 지킨다.
     *
     * <p>🔴 그리고 <b>세션 ID 가 재시도 사이에 바뀌지 않는지</b>를 함께 단언한다 — 컨트롤러가
     * {@code portal-{UUID}} 를 람다 <b>바깥</b>에서 만들기 때문에 성립하는 성질이고, 안으로 옮기면
     * 응답의 {@code sessionId} 와 실제로 전송된 {@code X-Session-Id} 가 갈린다.
     */
    @Test
    @DisplayName("② 🔴 전송(POST) 도 upstream 401 → 갱신 → 정확히 1회 재시도, 세션 ID 는 그대로다")
    void chatSendRetriesOnceWithSameSessionIdAfterRefresh() {
        MockHttpSession session = sessionWith(Instant.now().plusSeconds(600));   // 아직 유효하다
        AtomicInteger refreshCount = new AtomicInteger();
        store.tokenRefresher = (s, refresh) -> {
            refreshCount.incrementAndGet();
            return renewed(null);
        };

        // 🔴 컨트롤러와 같은 자리 — withToken 람다 **바깥**에서 한 번만 만든다.
        String sessionId = "portal-" + java.util.UUID.randomUUID();

        List<String> sentTokens = new ArrayList<>();
        List<String> sentSessionIds = new ArrayList<>();
        String answer = store.withToken(session, token -> {
            // AgentApiClient.sendMessage(token, sessionId, message) 가 나가는 지점을 대신한다.
            sentTokens.add(token);
            sentSessionIds.add(sessionId);
            if (sentTokens.size() == 1) {
                // 첫 전송은 upstream 401 — 만료·변조·누락 4종 전부 401 이라는 것이 실측됐다(§2-3 ⑤-f).
                throw new AgentApiException(Code.AGENT_UNAUTHORIZED);
            }
            return "오후 6시대입니다.";
        });

        assertThat(answer).isEqualTo("오후 6시대입니다.");
        assertThat(refreshCount).hasValue(1);
        // 🔴 최초 1 + 재시도 1 = 2. 그 이상이면 질문이 여러 번 적재된다.
        assertThat(sentTokens).containsExactly(OLD_ACCESS, NEW_ACCESS);
        // 🔴 두 번 다 같은 세션 ID 여야 한다 — 갈리면 응답의 sessionId 가 거짓이 된다.
        assertThat(sentSessionIds).containsExactly(sessionId, sessionId);
    }

    // ------------------------------------------------------------------ 갱신 실패

    @Test
    @DisplayName("🔴 갱신 실패는 401 이 아니라 503 이고, 세션 토큰 3종이 지워진다")
    void refreshFailureClearsSessionAndYields503() {
        MockHttpSession session = sessionWith(Instant.now().minusSeconds(1));
        store.tokenRefresher = (s, refresh) -> {
            throw new IllegalStateException("refresh expired");   // 사유는 밖으로 새지 않는다
        };

        Throwable thrown = catchThrowable(() -> store.withToken(session, token -> {
            throw new AssertionError("갱신에 실패하면 call 이 실행되면 안 된다");
        }));

        assertThat(thrown).isInstanceOf(AgentApiException.class);
        AgentApiException ex = (AgentApiException) thrown;
        // 🔴 401 이면 인터셉터가 /login 으로 보내 무한 왕복이 된다.
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_TOKEN_UNAVAILABLE);
        assertThat(ex.httpStatus()).isEqualTo(503);
        // 🔴 죽은 토큰을 남기면 다음 요청이 같은 값으로 다시 401 을 맞는다.
        assertThat(session.getAttribute(AgentTokenStore.ATTR_ACCESS)).isNull();
        assertThat(session.getAttribute(AgentTokenStore.ATTR_EXPIRES)).isNull();
        assertThat(session.getAttribute(AgentTokenStore.ATTR_REFRESH)).isNull();
        // 🔴 예외 메시지는 코드 이름 고정 — 갱신 실패 원인이 응답으로 새지 않는다.
        assertThat(ex.getMessage()).isEqualTo(Code.AGENT_TOKEN_UNAVAILABLE.name());
    }

    @Test
    @DisplayName("만료됐는데 세션에 refresh 값이 없으면 503 + clear")
    void missingRefreshValueYields503() {
        MockHttpSession session = sessionWith(Instant.now().minusSeconds(1));
        session.removeAttribute(AgentTokenStore.ATTR_REFRESH);
        store.tokenRefresher = (s, refresh) -> renewed(null);

        Throwable thrown = catchThrowable(() -> store.withToken(session, token -> token));

        assertThat(((AgentApiException) thrown).getCode()).isEqualTo(Code.AGENT_TOKEN_UNAVAILABLE);
        assertThat(session.getAttribute(AgentTokenStore.ATTR_ACCESS)).isNull();
    }

    // ------------------------------------------------------------------ refresher 가 없을 때 (종전 1-A)

    @Test
    @DisplayName("🔴 refresher 가 없으면 종전 동작 — 만료는 곧 503, 세션은 건드리지 않는다")
    void withoutRefresherExpiredBehavesLikeStep1A() {
        MockHttpSession session = sessionWith(Instant.now().minusSeconds(1));
        // store.tokenRefresher 를 꽂지 않는다 (= OIDC 전용 환경)

        Throwable thrown = catchThrowable(() -> store.withToken(session, token -> {
            throw new AssertionError("만료 토큰으로 call 이 실행되면 안 된다");
        }));

        assertThat(((AgentApiException) thrown).getCode()).isEqualTo(Code.AGENT_TOKEN_UNAVAILABLE);
        // 🔴 1-A 는 clear 하지 않았다 — 그 동작을 그대로 유지한다.
        assertThat(session.getAttribute(AgentTokenStore.ATTR_ACCESS)).isEqualTo(OLD_ACCESS);
    }

    @Test
    @DisplayName("🔴 refresher 가 없으면 upstream 401 은 그대로 전파된다 — 재시도하지 않는다")
    void withoutRefresher401IsPropagated() {
        MockHttpSession session = sessionWith(Instant.now().plusSeconds(600));

        AtomicInteger callCount = new AtomicInteger();
        Throwable thrown = catchThrowable(() -> store.withToken(session, token -> {
            callCount.incrementAndGet();
            throw new AgentApiException(Code.AGENT_UNAUTHORIZED);
        }));

        assertThat(((AgentApiException) thrown).getCode()).isEqualTo(Code.AGENT_UNAUTHORIZED);
        assertThat(callCount).hasValue(1);      // 🔴 재시도 없음
    }

    @Test
    @DisplayName("🔴 refresher 가 있어도 토큰이 아예 없으면 갱신하지 않고 곧장 503 이다")
    void absentTokenIsNotRefreshed() {
        MockHttpSession session = new MockHttpSession();     // 토큰을 한 번도 저장하지 않았다
        AtomicInteger refreshCount = new AtomicInteger();
        store.tokenRefresher = (s, refresh) -> {
            refreshCount.incrementAndGet();
            return renewed(null);
        };

        Throwable thrown = catchThrowable(() -> store.withToken(session, token -> token));

        assertThat(((AgentApiException) thrown).getCode()).isEqualTo(Code.AGENT_TOKEN_UNAVAILABLE);
        assertThat(refreshCount).hasValue(0);   // 갱신할 대상이 없다 — refresh 값도 없다
    }

    @Test
    @DisplayName("만료 시각을 모르면(=null) 유효한 것으로 보고 갱신하지 않는다")
    void unknownExpiryIsTreatedAsValid() {
        MockHttpSession session = sessionWith(null);
        session.removeAttribute(AgentTokenStore.ATTR_EXPIRES);
        AtomicInteger refreshCount = new AtomicInteger();
        store.tokenRefresher = (s, refresh) -> {
            refreshCount.incrementAndGet();
            return renewed(null);
        };

        String used = store.withToken(session, token -> token);

        assertThat(used).isEqualTo(OLD_ACCESS);
        assertThat(refreshCount).hasValue(0);
    }
    // ------------------------------------------------------------------ 동시 갱신

    /**
     * 회전형 refresh 를 흉내 낸다 — 한 번 쓴 값을 다시 쓰면 IdP 처럼 거절한다(가이드 4절 {@code Refresh token revoked}).
     * {@code inside} 는 갱신 왕복 중 다른 스레드가 끼어들 틈을 만드는 지연이다.
     */
    private static TokenRefresher rotatingRefresher(AtomicInteger count, CountDownLatch inside) {
        java.util.Set<String> used = java.util.concurrent.ConcurrentHashMap.newKeySet();
        return (s, refresh) -> {
            count.incrementAndGet();
            if (!used.add(refresh)) {
                throw new IllegalStateException("Refresh token revoked");
            }
            inside.countDown();
            try {
                Thread.sleep(200);              // 갱신 왕복 — 이 사이에 두 번째 요청이 도착한다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return renewed(ROTATED_REFRESH);
        };
    }

    @Test
    @DisplayName("🔴 같은 세션의 두 요청이 동시에 만료를 보면 갱신은 1회뿐이고 둘 다 새 토큰을 쓴다")
    void concurrentRefreshInSameSessionRunsOnce() throws Exception {
        MockHttpSession session = sessionWith(Instant.now().minusSeconds(1));
        AtomicInteger refreshCount = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        store.tokenRefresher = rotatingRefresher(refreshCount, inside);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> store.withToken(session, token -> token));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();   // 첫 요청이 갱신 왕복 중이다
            Future<String> second = pool.submit(() -> store.withToken(session, token -> token));

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(NEW_ACCESS);
            // 🔴 잠금이 없으면 두 번째가 같은 refresh 로 갱신해 거절되고, clear 가 세션을 비워 503 이 된다.
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(NEW_ACCESS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(refreshCount).hasValue(1);
        assertThat(session.getAttribute(AgentTokenStore.ATTR_ACCESS)).isEqualTo(NEW_ACCESS);
        assertThat(session.getAttribute(AgentTokenStore.ATTR_REFRESH)).isEqualTo(ROTATED_REFRESH);
    }

    @Test
    @DisplayName("upstream 401 사이에 다른 요청이 이미 갱신했으면 refresh 를 또 쓰지 않고 그 토큰으로 재시도한다")
    void retryAfter401ReusesTokenRefreshedByAnotherRequest() {
        MockHttpSession session = sessionWith(Instant.now().plusSeconds(600));
        AtomicInteger refreshCount = new AtomicInteger();
        store.tokenRefresher = (s, refresh) -> {
            refreshCount.incrementAndGet();
            return renewed(null);
        };

        AtomicInteger callCount = new AtomicInteger();
        String used = store.withToken(session, token -> {
            if (callCount.incrementAndGet() == 1) {
                // 이 요청이 401 을 받는 사이 다른 요청이 갱신을 끝내 세션에 새 토큰을 넣었다.
                session.setAttribute(AgentTokenStore.ATTR_ACCESS, NEW_ACCESS);
                session.setAttribute(AgentTokenStore.ATTR_EXPIRES, Instant.now().plusSeconds(600));
                throw new AgentApiException(Code.AGENT_UNAUTHORIZED);
            }
            return token;
        });

        assertThat(used).isEqualTo(NEW_ACCESS);
        assertThat(refreshCount).hasValue(0);
        assertThat(callCount).hasValue(2);
    }
}
