package jeju.datahub.batch.cvlcpt.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 총건수 파싱 형태별 검증 — 2026-08-18 실측(GET 엔드포인트가 count SQL 을 무시하고 데이터 행을
 * 돌려줘 Job 이 죽었던 사고)의 회귀 테스트다. 어떤 형태가 와도 예외가 아니라 값 또는
 * {@link DhubDatasetCount#UNAVAILABLE} 로 수렴해야 한다.
 *
 * <p>🔁 2026-09-17 — {@code DhubCrackdownApiClientTest} 에서 이관했다. 파싱이 단속
 * 클라이언트에서 공용 {@link DhubDatasetCount} 로 옮겨졌고, 이제 단속·공영주차장이 같은 식을 쓴다.
 */
class DhubDatasetCountTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("정상 형태 [{\"cnt\": N}] — 별칭이 살아 있다 (주차장 데이터셋 실측 형태)")
    void parsesAliasedCount() throws Exception {
        assertThat(DhubDatasetCount.parse(MAPPER.readTree("[{\"cnt\": 5}]")))
                .isEqualTo(5L);
        // 2026-09-17 공영주차장 스파이크 실측값
        assertThat(DhubDatasetCount.parse(MAPPER.readTree("[{\"cnt\": 2272}]")))
                .isEqualTo(2_272L);
    }

    @Test
    @DisplayName("별칭 무시 — 필드가 1개면 이름이 무엇이든 그 값을 쓴다 (문자열 숫자 포함)")
    void parsesSingleFieldWhateverItsName() throws Exception {
        assertThat(DhubDatasetCount.parse(MAPPER.readTree("[{\"count()\": 7}]")))
                .isEqualTo(7L);
        // ClickHouse 가 UInt64 를 문자열로 줄 수도 있다
        assertThat(DhubDatasetCount.parse(MAPPER.readTree("[{\"COUNT(*)\": \"159530\"}]")))
                .isEqualTo(159_530L);
    }

    @Test
    @DisplayName("데이터 행 반환(SQL 무시)·여러 행·빈 배열·배열 아님·숫자 아님 — 전부 확인 불가로 강등")
    void degradesToUnavailableOnUnexpectedShapes() throws Exception {
        // 필드 여러 개 = count 결과가 아니라 데이터 행이다
        assertThat(DhubDatasetCount.parse(MAPPER.readTree(
                "[{\"crdn_dt\": \"2025-01-01 00:07:00.000\", \"ffnlg\": 40000.0}]")))
                .isEqualTo(DhubDatasetCount.UNAVAILABLE);
        assertThat(DhubDatasetCount.parse(MAPPER.readTree("[{\"cnt\": 1}, {\"cnt\": 2}]")))
                .isEqualTo(DhubDatasetCount.UNAVAILABLE);
        assertThat(DhubDatasetCount.parse(MAPPER.readTree("[]")))
                .isEqualTo(DhubDatasetCount.UNAVAILABLE);
        assertThat(DhubDatasetCount.parse(MAPPER.readTree("{\"cnt\": 5}")))
                .isEqualTo(DhubDatasetCount.UNAVAILABLE);
        assertThat(DhubDatasetCount.parse(MAPPER.readTree("[{\"cnt\": \"abc\"}]")))
                .isEqualTo(DhubDatasetCount.UNAVAILABLE);
        assertThat(DhubDatasetCount.parse(null))
                .isEqualTo(DhubDatasetCount.UNAVAILABLE);
    }

    @Test
    @DisplayName("음수는 확인 불가로 본다 — 총건수가 음수일 수는 없다")
    void degradesOnNegative() throws Exception {
        assertThat(DhubDatasetCount.parse(MAPPER.readTree("[{\"cnt\": -1}]")))
                .isEqualTo(DhubDatasetCount.UNAVAILABLE);
    }
}
