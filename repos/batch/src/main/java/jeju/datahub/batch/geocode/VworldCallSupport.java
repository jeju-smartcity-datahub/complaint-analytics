package jeju.datahub.batch.geocode;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * 두 VWorld 클라이언트가 <b>공유하는 호출 규율</b> — HTTP 클라이언트 구성 · 재시도 대상 예외 ·
 * 선형 백오프 · 쿼터 초과 코드.
 *
 * <ul>
 *   <li>{@link VworldGeocodingClient} — {@code /req/address} (지번·도로명 지오코딩, D7 1·2·4단)</li>
 *   <li>{@link VworldPlaceSearchClient} — {@code /req/search} (장소 POI 검색, D7 3단)</li>
 * </ul>
 *
 * <p><b>응답 파싱은 여기 없다.</b> 두 엔드포인트는 상태 코드 이름만 같을 뿐 결과 구조가 다르므로
 * ({@code result.point} vs {@code result.items[].point}) 파싱을 공유하면 한쪽 스키마 변화가 다른 쪽을
 * 조용히 망가뜨린다(D7 2026-08-05 재개정 경고). 공유하는 것은 <b>"어떻게 부르고 실패를 어떻게
 * 분류하는가"</b> 뿐이다.
 *
 * <p>2026-08-05 이전에는 이 코드가 {@code VworldGeocodingClient} 안의 중첩 타입이었다. 장소 검색
 * 클라이언트가 같은 규율을 따라야 하는데, 형제 클라이언트의 내부 타입을 가져다 쓰는 모양이 되어
 * 공용 지점으로 끌어냈다(동작은 그대로다).
 */
final class VworldCallSupport {

    /** 실측으로 확인된 VWorld 쿼터 초과 코드. 재시도해도 회복되지 않는다(D9) — 두 엔드포인트 공통. */
    static final String QUOTA_ERROR_CODE = "OVER_REQUEST_LIMIT";

    /** 재시도 간 대기 = 이 값 × 시도횟수 (300 / 600 / 900ms). */
    static final long BACKOFF_STEP_MS = 300L;

    private VworldCallSupport() {
    }

    /** 연결·읽기 타임아웃을 동일하게 건 {@link RestClient}. */
    static RestClient restClient(String baseUrl, int timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 재시도 대상은 {@link TransientGeocodeException} <b>하나뿐</b>이다.
     * {@link GeocodeQuotaExceededException} 은 목록에 없으므로 {@link RetryTemplate} 이 그대로 밖으로
     * 던진다(= 재시도 0회, D9).
     */
    static RetryTemplate retryTemplate(int maxAttempts) {
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(
                new SimpleRetryPolicy(maxAttempts, Map.of(TransientGeocodeException.class, true)));
        template.setBackOffPolicy(new LinearBackOffPolicy(BACKOFF_STEP_MS));
        return template;
    }

    /** 재시도해 볼 만한 실패(네트워크·타임아웃·일시 {@code ERROR}). 이 타입만 재시도 대상이다. */
    static class TransientGeocodeException extends RuntimeException {

        TransientGeocodeException(String message) {
            super(message);
        }

        TransientGeocodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 대기 시간이 {@code step × 시도횟수} 로 늘어나는 백오프(300 / 600 / 900ms).
     *
     * <p>{@code ExponentialBackOffPolicy}(300/600/1200) 대신 직접 둔 이유는 이식 원본인 Node
     * 프로토타입이 {@code sleep(300 * attempt)} 이기 때문이다 — 호출 간격을 원본과 같게 유지한다.
     */
    static final class LinearBackOffPolicy implements BackOffPolicy {

        private final long stepMs;

        LinearBackOffPolicy(long stepMs) {
            this.stepMs = stepMs;
        }

        @Override
        public BackOffContext start(RetryContext context) {
            return new AttemptCounter();
        }

        @Override
        public void backOff(BackOffContext backOffContext) throws BackOffInterruptedException {
            long attempt = ((AttemptCounter) backOffContext).next();
            try {
                Thread.sleep(stepMs * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BackOffInterruptedException("백오프 대기 중 인터럽트", e);
            }
        }

        private static final class AttemptCounter implements BackOffContext {
            private long attempts;

            long next() {
                return ++attempts;
            }
        }
    }
}
