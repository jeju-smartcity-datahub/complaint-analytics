package jeju.datahub.batch.cvlcpt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link DhubSql} — 질의 문자열 조립의 주입 방어.
 *
 * <p>이 API 는 SQL 을 문자열로 그대로 받고 바인딩 파라미터가 없다. 조립에 들어가는 값이
 * <b>따옴표·백틱을 깨고 나올 수 없는지</b>가 이 테스트의 관심사다.
 */
class DhubSqlTest {

    @Test
    @DisplayName("반개구간 술어를 만든다 — report_date 가 DATE 든 DATETIME 이든 맞아떨어진다")
    void buildsHalfOpenRange() {
        String predicate = DhubSql.halfOpenRange(
                "report_date", LocalDate.parse("2025-01-01"), LocalDate.parse("2026-08-04"));

        assertThat(predicate)
                .isEqualTo("report_date >= '2025-01-01' AND report_date < '2026-08-04'");
    }

    @Test
    @DisplayName("구간이 비었거나 뒤집히면 조용히 0건을 읽지 않고 실패한다")
    void rejectsEmptyOrInvertedRange() {
        LocalDate day = LocalDate.parse("2026-08-04");

        assertThatThrownBy(() -> DhubSql.halfOpenRange("report_date", day, day))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DhubSql.halfOpenRange("report_date", day, day.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("일자는 LocalDate 로 파싱한 뒤 다시 포맷한 값만 조립에 쓴다")
    void normalizesDatesThroughParsing() {
        assertThat(DhubSql.dateLiteral(DhubSql.date(" 2026-08-04 "))).isEqualTo("'2026-08-04'");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "2026-8-4",                        // 0 패딩 없음
            "20260804",                        // 구분자 없음
            "2026-13-01",                      // 존재하지 않는 달
            "2026-08-04' OR '1'='1",           // 따옴표 탈출 시도
            "2026-08-04'; DROP TABLE x; --",   // 문장 종료 시도
    })
    @DisplayName("파싱을 통과하지 못하는 일자는 조립 전에 막힌다")
    void rejectsMalformedDates(String raw) {
        assertThatThrownBy(() -> DhubSql.date(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("백틱 인용 테이블명은 통과한다 — dhub.source-table 의 실제 형태")
    void acceptsBacktickQuotedTable() {
        String table = "`jeju_smart_city_test`.`tb_safety_report_202607161505_clean`";
        assertThat(DhubSql.table(table)).isEqualTo(table);
        assertThat(DhubSql.column("report_no")).isEqualTo("report_no");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "`db`.`tbl` ; DROP TABLE x",       // 문장 이어붙이기
            "`db`.`tbl` UNION ALL SELECT 1",   // UNION 주입
            "db`.`tbl",                        // 백틱 짝 깨기
            "tbl'",                            // 따옴표 삽입
            "  ",                              // 빈 값
    })
    @DisplayName("식별자에 공백·세미콜론·따옴표가 섞이면 막힌다")
    void rejectsUnsafeIdentifiers(String raw) {
        assertThatThrownBy(() -> DhubSql.table(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("source-table 이 비면 하드코딩으로 때우지 않고 프로퍼티를 설정하라고 실패한다")
    void requiresSourceTableProperty() {
        DhubProperties properties = new DhubProperties();

        assertThatThrownBy(properties::requireSourceTable)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dhub.source-table");
    }

    @Test
    @DisplayName("페이지 크기는 숫자 타입으로 받고 범위를 검증한다")
    void validatesLimitRange() {
        assertThat(DhubSql.limit(1000)).isEqualTo(1000);
        assertThatThrownBy(() -> DhubSql.limit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DhubSql.limit(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DhubSql.limit(DhubSql.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("keyset 커서(report_no)는 형식을 검증한 뒤에만 리터럴이 된다")
    void validatesKeysetCursorLiteral() {
        assertThat(DhubSql.stringLiteral("SPP-2501-0103787")).isEqualTo("'SPP-2501-0103787'");
        assertThatThrownBy(() -> DhubSql.stringLiteral("SPP-2501' OR 1=1 --"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
