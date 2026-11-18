package jeju.datahub.batch.cvlcpt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DhubProperties} 의 조회 구간 산출.
 *
 * <p>두 가지가 핵심이다 — <b>backfill 끝을 비우면 실행일</b>이 되어 연 단위 고정으로 인한 누락이
 * 생기지 않는 것, 그리고 <b>daily 는 실행일이 아니라 전일</b>을 읽는 것(02:00 스케줄에서 당일분을
 * 조회하면 그날 2시간치만 잡힌다).
 */
class DhubPropertiesTest {

    /** 2026-08-04 02:00 KST = 2026-08-03 17:00 UTC — 날짜가 UTC 와 갈리는 시각으로 잡았다. */
    private static final Clock KST_0200 = Clock.fixed(Instant.parse("2026-08-03T17:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("backfill-to 를 비우면 실행일이 반개구간 끝이 된다 — 연 단위 고정으로 인한 누락 방지")
    void backfillToDefaultsToRunDate() {
        DhubProperties properties = new DhubProperties();
        properties.setBackfillFrom(LocalDate.parse("2025-01-01"));

        assertThat(properties.resolveBackfillTo(KST_0200)).isEqualTo(LocalDate.parse("2026-08-04"));
    }

    @Test
    @DisplayName("backfill-to 를 지정하면 그 값을 쓴다")
    void backfillToHonoursExplicitValue() {
        DhubProperties properties = new DhubProperties();
        properties.setBackfillTo(LocalDate.parse("2026-06-01"));

        assertThat(properties.resolveBackfillTo(KST_0200)).isEqualTo(LocalDate.parse("2026-06-01"));
    }

    @Test
    @DisplayName("daily 기준일은 Asia/Seoul 의 전일 — 실행 서버 시간대(UTC)가 아니다")
    void dailyReadsPreviousSeoulDay() {
        // UTC 로는 아직 2026-08-03 이지만 KST 로는 2026-08-04 02:00 이다.
        // 시간대를 잘못 잡으면 전일이 08-02 로 밀려 하루가 통째로 어긋난다.
        assertThat(new DhubProperties().resolveDailyDate(KST_0200))
                .isEqualTo(LocalDate.parse("2026-08-03"));
    }

    @Test
    @DisplayName("BATCH_TODAY 를 주면 그 전날을 읽는다 — 정적 스냅샷에서 daily 를 실데이터로 검증하는 수단")
    void dailyHonoursTodayOverride() {
        // 원천이 2026-05-31 까지라 실제 "어제"는 늘 0건이다. 2026-06-01 을 오늘로 보면
        // daily 가 2026-05-31(345건)을 읽어 전 구간이 실데이터로 돈다.
        DhubProperties properties = new DhubProperties();
        properties.setTodayOverride("2026-06-01");

        assertThat(properties.resolveToday(KST_0200)).isEqualTo(LocalDate.parse("2026-06-01"));
        assertThat(properties.resolveDailyDate(KST_0200)).isEqualTo(LocalDate.parse("2026-05-31"));
        // backfill 끝도 같은 "오늘"을 따른다 — 두 모드가 다른 오늘을 보면 구간이 어긋난다.
        assertThat(properties.resolveBackfillTo(KST_0200)).isEqualTo(LocalDate.parse("2026-06-01"));
    }

    @Test
    @DisplayName("BATCH_TODAY 가 비면 실제 날짜로 돌아간다 — 원천이 실시간화되면 값만 비우면 된다")
    void todayOverrideFallsBackToRealDate() {
        DhubProperties blank = new DhubProperties();
        blank.setTodayOverride("   ");
        assertThat(blank.resolveToday(KST_0200)).isEqualTo(LocalDate.parse("2026-08-04"));

        DhubProperties unset = new DhubProperties();
        assertThat(unset.resolveToday(KST_0200)).isEqualTo(LocalDate.parse("2026-08-04"));
        assertThat(unset.resolveDailyDate(KST_0200)).isEqualTo(LocalDate.parse("2026-08-03"));
    }

    @Test
    @DisplayName("BATCH_TODAY 형식이 틀리면 즉시 실패한다 — 조용히 실제 날짜로 넘어가면 엉뚱한 구간을 수집한다")
    void todayOverrideRejectsBadFormat() {
        DhubProperties properties = new DhubProperties();
        properties.setTodayOverride("2026/06/01");

        assertThatThrownBy(() -> properties.resolveToday(KST_0200))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BATCH_TODAY")
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    @DisplayName("toString 은 todayOverride 를 드러낸다 — 수집 구간이 실제 날짜와 다른 이유가 기동 로그에 남아야 한다")
    void toStringRevealsTodayOverrideButNeverCredentials() {
        DhubProperties properties = new DhubProperties();
        properties.setEmail("someone@example.com");
        properties.setPassword("s3cret");
        properties.setTodayOverride("2026-06-01");

        assertThat(properties.toString())
                .contains("todayOverride=2026-06-01")
                .doesNotContain("someone@example.com")
                .doesNotContain("s3cret");

        assertThat(new DhubProperties().toString()).contains("(실제 날짜)");
    }

    @Test
    @DisplayName("query-mode 기본값은 incremental이다 — 원천 load_dt 기반 증분 수집")
    void defaultsToIncrementalMode() {
        assertThat(new DhubProperties().getQueryMode()).isEqualTo(DhubProperties.QueryMode.INCREMENTAL);
        assertThat(new DhubProperties().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("🔴 pblpklt-page-size 기본값은 페이지 크기 1000(max_rows 상한)이다 — 0 이면 LIMIT 0 이 된다")
    void pblpkltPageSizeDefaultsToMaxPage() {
        // 🔁 2026-09-17 — keyset 페이징 전환으로 의미가 "전량 여부를 가르는 limit" → "페이지 크기".
        //    종전 기본값 0(쿼리스트링 limit 없이 전량)을 그대로 두면 LIMIT 0 으로 0행을 받고 성공해,
        //    같은 Job 의 소멸 마킹이 전량을 지우는 경로가 된다. (그 전 기본값 500 은 1,682행 소멸 사고의 원인이었다.)
        DhubProperties defaults = new DhubProperties();

        assertThat(defaults.getPblpkltPageSize()).isEqualTo(1000);
        assertThat(defaults.requirePblpkltPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("🔴 pblpklt-page-size 가 1..1000 밖이면(특히 종전 관례 0) Job 시작 시점 검증이 명확히 실패한다")
    void requirePblpkltPageSizeRejectsOutOfRange() {
        for (int invalid : new int[] {0, -1, 1001}) {
            DhubProperties properties = new DhubProperties();
            properties.setPblpkltPageSize(invalid);

            assertThatThrownBy(properties::requirePblpkltPageSize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dhub.pblpklt-page-size")
                    .hasMessageContaining(String.valueOf(invalid));
        }
        DhubProperties edge = new DhubProperties();
        edge.setPblpkltPageSize(1);
        assertThat(edge.requirePblpkltPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 crackdown-page-size 기본값은 페이지 크기 1000(max_rows 상한)이다 — 0 이면 LIMIT 0 이 된다")
    void crackdownPageSizeDefaultsToMaxPage() {
        DhubProperties defaults = new DhubProperties();

        assertThat(defaults.getCrackdownPageSize()).isEqualTo(1000);
        assertThat(defaults.requireCrackdownPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("🔴 crackdown-page-size 가 1..1000 밖이면(특히 종전 관례 0) Job 시작 시점 검증이 명확히 실패한다")
    void requireCrackdownPageSizeRejectsOutOfRange() {
        for (int invalid : new int[] {0, -1, 1001}) {
            DhubProperties properties = new DhubProperties();
            properties.setCrackdownPageSize(invalid);

            assertThatThrownBy(properties::requireCrackdownPageSize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dhub.crackdown-page-size")
                    .hasMessageContaining(String.valueOf(invalid));
        }
        DhubProperties one = new DhubProperties();
        one.setCrackdownPageSize(1);
        assertThat(one.requireCrackdownPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 pblpklt 원천 테이블이 비면 즉시 실패한다 — 빈 FROM 절로 총건수 교차검증이 조용히 무력화되지 않게")
    void requirePblpkltSourceTableRejectsBlank() {
        // 2026-09-17 — 이 값이 analytics/query 의 FROM 절에 들어가기 시작했다.
        // 비어 있는 채로 진행하면 교차검증이 매 실행 실패해 UNAVAILABLE 로 강등되고,
        // "가드가 있는데 아무것도 막지 못하는" 상태가 된다.
        DhubProperties blank = new DhubProperties();
        blank.setPblpkltSourceTable("   ");

        assertThatThrownBy(blank::requirePblpkltSourceTable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dhub.pblpklt-source-table")
                .hasMessageContaining("422");

        assertThatThrownBy(new DhubProperties()::requirePblpkltSourceTable)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("pblpklt 원천 테이블 값이 있으면 식별자 검증을 통과해 돌려준다")
    void requirePblpkltSourceTableReturnsValidatedIdentifier() {
        DhubProperties properties = new DhubProperties();
        // 2026-09-17 실측 — analytics/query 가 422 로 알려준 허용 테이블 이름.
        properties.setPblpkltSourceTable("`jeju_mini`.`tb_pblpklt`");

        assertThat(properties.requirePblpkltSourceTable()).contains("tb_pblpklt");
    }
}
