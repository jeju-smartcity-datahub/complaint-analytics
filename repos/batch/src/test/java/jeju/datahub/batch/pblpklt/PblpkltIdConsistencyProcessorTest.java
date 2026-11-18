package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jeju.datahub.batch.pblpklt.api.PblpkltOriginRow;

/**
 * {@link PblpkltIdConsistencyProcessor} 단위 테스트 — 회귀.
 *
 * <h2>🔴 이 테스트가 지키는 것</h2>
 * 종전 구현은 {@code pklt_id} 에 {@code '-'} 가 없으면 그 행을 조용히 버렸다. 새 원천의
 * {@code pklt_id} 는 {@code "12"} 라 <b>전 행이 여기서 필터돼 Job 이 {@code write_cnt=0} 으로
 * {@code COMPLETED}</b> 되는(= "성공했는데 아무것도 안 들어왔다") 상태가 된다.
 * 형식 검증을 되살리는 변경이 들어오면 이 테스트가 먼저 빨개진다.
 *
 * <p>반대로 <b>식별자가 아예 없는 행은 여전히 스킵</b>이어야 한다 — 없으면 origin_pk 장부도
 * Writer 의 UNIQUE 도 성립하지 않고, 그 방어는 지오코딩 쿼터를 쓰기 전에 걸려야 의미가 있다.
 */
class PblpkltIdConsistencyProcessorTest {

    private final PblpkltIdConsistencyProcessor processor = new PblpkltIdConsistencyProcessor();

    @ParameterizedTest(name = "pklt_id={0} 는 통과한다")
    @DisplayName("🔴 하이픈 없는 원천 id 가 필터되지 않는다 (§7-0 회귀)")
    @ValueSource(strings = {"12", "1916", "2272", "1", "한림읍-0001"})
    void keepsRowsWhateverTheSourceIdFormatIs(String pkltId) {
        PblpkltFetchRow item = row(pkltId);

        // 🔴 같은 객체가 그대로 나와야 한다 — 여기서 걸러지면 지오코딩까지 가지도 못한다.
        assertThat(processor.process(item)).isSameAs(item);
    }

    @Test
    @DisplayName("anomaly 관측 결과를 들고 그대로 통과한다 — 곁가지 기록이 여기서 유실되지 않는다")
    void carriesAnomalyTypesThrough() {
        PblpkltFetchRow item = new PblpkltFetchRow(
                PblpkltOriginRow.builder().pkltId("12").pkltAddr("한림리 산 1011").build(),
                List.of("SAN_PREFIX"));

        PblpkltFetchRow result = processor.process(item);

        assertThat(result).isNotNull();
        assertThat(result.anomalyTypes()).containsExactly("SAN_PREFIX");
    }

    @ParameterizedTest(name = "pklt_id={0} 는 스킵한다")
    @DisplayName("pklt_id 가 공백이면 여전히 스킵(null) — 원천 식별자 없이는 장부가 성립하지 않는다")
    @ValueSource(strings = {"", "   ", "\t"})
    void skipsBlankSourceId(String blank) {
        assertThat(processor.process(row(blank))).isNull();
    }

    @Test
    @DisplayName("item 또는 origin 이 null 이면 스킵(null) — NPE 로 청크를 죽이지 않는다")
    void skipsNullItemOrOrigin() {
        assertThat(processor.process(null)).isNull();
        assertThat(processor.process(new PblpkltFetchRow(null, List.of()))).isNull();
    }

    private static PblpkltFetchRow row(String pkltId) {
        return new PblpkltFetchRow(
                PblpkltOriginRow.builder().pkltId(pkltId).emdNm("한림읍").build(),
                List.of());
    }
}
