package jeju.datahub.batch.cvlcpt.api;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DHub 총건수 질의({@code POST /api/v1/analytics/query}) 응답의 {@code rows} 파싱 —
 * <b>전량 수집 교차검증</b>의 공용 부품.
 *
 * <h2>🔴 왜 한 곳에만 두는가</h2>
 * 이 파싱은 2026-08-18 단속 수집에 도입돼 {@code DhubCrackdownApiClient} 안에 있었고,
 * 2026-09-17 공영주차장(TSK-4161)에도 필요해졌다. 복사해 두면 <b>한쪽만 고쳐져 조용히 어긋난다</b> —
 * 바로 직전 작업(TSK-4054)이 같은 교훈이었다. 스킵 기록 upsert 가 세 리스너에 흩어져 있던 것을
 * {@code GeocodeSkipRecorder} 한 곳으로 모은 것과 같은 이유로, 총건수 파싱도 여기만 둔다.
 *
 * <p>배치 위치가 {@code cvlcpt.api} 인 것은 이 패키지가 이미 <b>사실상의 공용 패키지</b>이기
 * 때문이다({@link DhubProperties}·{@link DhubSql}·{@link DhubApiException}·{@link DhubAuthClient} 를
 * 주차장·단속이 모두 import 한다). 패키지 이름이 {@code cvlcpt} 인 것은 역사적 잔재이며 재편은
 * 이 작업 범위 밖이다.
 *
 * <h2>실패를 예외로 만들지 않는다</h2>
 * 어떤 형태가 와도 값 또는 {@link #UNAVAILABLE} 로 <b>수렴</b>한다. 교차검증은 best-effort 이고,
 * 이것이 수집을 막으면 가드가 오히려 가용성을 떨어뜨린다. 판정은 호출부(Reader)가 한다.
 */
public final class DhubDatasetCount {

    private static final Logger log = LoggerFactory.getLogger(DhubDatasetCount.class);

    /** 총건수를 확인하지 못했음 — 호출부가 보수적 가드로 폴백한다. */
    public static final long UNAVAILABLE = -1L;

    private DhubDatasetCount() {
    }

    /**
     * count 응답의 {@code rows} 파싱 — 세 형태를 순서대로 시도한다.
     * <ol>
     *   <li>{@code [{"cnt": N}]} — 별칭이 살아 있는 정상 케이스.
     *       공영주차장 데이터셋 실측(2026-09-17)이 이 형태다</li>
     *   <li>{@code [{"count()": N}]} 처럼 <b>필드가 정확히 1개</b>인 1행 — 별칭 무시 케이스.
     *       이름이 무엇이든 그 값을 쓴다</li>
     *   <li>그 외(필드 여러 개, 여러 행, 빈 배열, 배열 아님, 숫자 아님) — {@link #UNAVAILABLE}</li>
     * </ol>
     *
     * <p>2번째 분기가 있는 이유는 실제 사고다 — 이 GET 엔드포인트가 count SQL 을 무시하고
     * <b>데이터 행</b>을 돌려줘 Job 이 죽은 전례가 있다(2026-08-18). 필드가 여러 개면 그것은
     * count 결과가 아니라 데이터 행이므로 {@link #UNAVAILABLE} 로 강등한다.
     */
    public static long parse(JsonNode rows) {
        if (rows == null || !rows.isArray() || rows.size() != 1 || !rows.get(0).isObject()) {
            return UNAVAILABLE;
        }
        JsonNode row = rows.get(0);
        JsonNode cnt = row.path("cnt");
        if (cnt.isMissingNode() || cnt.isNull()) {
            if (row.size() != 1) {
                return UNAVAILABLE;
            }
            cnt = row.iterator().next();
        }
        try {
            // ClickHouse 가 UInt64 를 문자열로 줄 수도 있어 텍스트 경유로 파싱한다.
            long value = Long.parseLong(cnt.asText().trim());
            return value < 0 ? UNAVAILABLE : value;
        } catch (NumberFormatException e) {
            // 4.2 — 삼키는 사유를 남긴다(TSK-4336) — 총건수를 못 읽으면 호출부가 보수적 가드로 폴백한다.
            log.warn("총건수 응답의 cnt 가 숫자가 아닙니다 — UNAVAILABLE 로 폴백 ({})", e.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }
}
