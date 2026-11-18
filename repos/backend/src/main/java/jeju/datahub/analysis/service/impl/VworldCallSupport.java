package jeju.datahub.analysis.service.impl;

import org.springframework.web.client.RestClientException;
import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 두 VWorld 클라이언트({@link VworldPlaceClient} · {@link VworldReverseGeocodeClient})가
 * <b>공유하는 호출 규율</b> — HTTP 클라이언트 구성 · 재시도 대상 예외 · 선형 백오프.
 *
 * <p><b>응답 파싱은 여기 없다.</b> {@code /req/search} 와 {@code /req/address} 는 상태 코드 이름만
 * 같을 뿐 결과 구조가 다르다({@code result.items[]} vs {@code result[]}). 파싱을 공유하면 한쪽
 * 스키마 변화가 다른 쪽을 조용히 망가뜨린다 — 공유하는 것은 <b>"어떻게 부르고 실패를 어떻게
 * 분류하는가"</b> 뿐이다.
 *
 * <p>⚠️ 배치({@code repos/jeju-datahub-batch} 의 {@code geocode.VworldCallSupport})에 같은 이름의
 * 클래스가 있지만 <b>별개 레포·별개 책임</b>이다. 배치는 지오코딩(주소 → 좌표) 실패를 Job 중단까지
 * 끌고 가야 해서 쿼터 초과를 별도 예외로 전파하지만, 여기(백엔드)는 <b>이름을 못 구해도 분석은
 * 성공</b>해야 하므로 모든 실패가 일시 실패로 수렴한다(호출부가 {@code {읍면동명} 일대} 로 내려간다).
 * 배치는 {@code spring-retry} 를 쓰지만 백엔드 {@code build.gradle} 에는 그 의존성이 없어
 * 재시도를 {@link #withRetry} 한 메서드로 직접 둔다 — 의존성 하나를 이 용도로 추가하지 않는다.
 */
final class VworldCallSupport {

    private static final Logger log = LoggerFactory.getLogger(VworldCallSupport.class);

    /** 재시도 간 대기 = 이 값 × 시도횟수 (300 / 600ms). 배치의 선형 백오프와 같은 간격이다. */
    static final long BACKOFF_STEP_MS = 300L;

    private VworldCallSupport() {
    }

    /** 조회 1건의 결말. 세 갈래를 섞지 않는 것이 폴백 체인·캐시 정책의 전제다. */
    enum LookupStatus {
        /** 값을 얻었다. */
        FOUND,
        /**
         * <b>확정</b> 실패 — 그 좌표에 답이 없다. 재시도하지 않고, 캐시에 남겨 재호출을 막는다.
         */
        NOT_FOUND,
        /**
         * <b>일시</b> 실패 — 타임아웃 · 5xx · 파싱 실패 · 키 없음. 캐시에 남기지 <b>않는다</b>
         * (다음 조회가 다시 시도한다). 배치 규칙 rule_batch-project.md "캐시" 와 같은 사고방식이다.
         */
        TRANSIENT_FAILURE
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

    /** 재시도해 볼 만한 실패(네트워크·타임아웃·5xx·파싱). 이 타입만 재시도 대상이다. */
    static class TransientVworldException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TransientVworldException(String message) {
            super(message);
        }

        TransientVworldException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * {@code call} 을 최대 {@code maxAttempts} 회 시도한다. {@link TransientVworldException} 만
     * 재시도 대상이며, 모두 소진하면 {@code onExhausted} 를 돌려준다 — <b>예외를 밖으로 던지지
     * 않는다.</b> 구역명은 없어도 분석이 성공해야 하기 때문이다(D6 ④).
     *
     * <p>{@code NOT_FOUND} 는 예외가 아니라 정상 반환값이라 자연히 재시도되지 않는다.
     */
    static <T> T withRetry(String what, int maxAttempts, Supplier<T> call, Supplier<T> onExhausted) {
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.get();
            } catch (TransientVworldException e) {
                if (attempt >= attempts) {
                    log.warn("{} 이(가) {}회 시도 후에도 실패했습니다 — 구역명 폴백으로 넘깁니다.",
                            what, attempts, e);
                    return onExhausted.get();
                }
                sleep(BACKOFF_STEP_MS * attempt);
            } catch (RestClientException | IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) { // 4.3: 호출·URI 조립·응답 가공이 낼 수 있는 갈래(호출·파싱 실패는 위에서 Transient 로 바뀐다)
                // 예상 못 한 예외도 삼킨다 — 이름 하나 때문에 분석 응답이 죽어서는 안 된다.
                log.warn("{} 중 예기치 못한 오류 — 구역명 폴백으로 넘깁니다.", what, e);
                return onExhausted.get();
            }
        }
        return onExhausted.get();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 인터럽트 상태를 되살려 호출부가 중단을 알게 한다 (4.2 — TSK-4336)
            log.debug("VWorld 재시도 대기 중 인터럽트 — 대기 중단");
            Thread.currentThread().interrupt();
        }
    }
}
