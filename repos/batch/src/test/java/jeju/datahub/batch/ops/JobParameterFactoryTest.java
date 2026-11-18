package jeju.datahub.batch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;

import jeju.datahub.batch.history.ExcnSe;
import jeju.datahub.batch.risknoti.RiskAlertProperties;

/**
 * {@link JobParameterFactory} — 기존 트리거·운영 API 가 공유하는 파라미터 조립.
 * 트리거 컨트롤러 테스트가 0건이라 기존 트리거의 파라미터 회귀를 이 테스트가 진다.
 */
class JobParameterFactoryTest {

    @Test
    @DisplayName("민원 daily — mode·targetDate 만, runAt 항상, 없는 값은 키째 없다")
    void complaintDaily() {
        JobParameters p = JobParameterFactory.complaintFetch("daily", "2026-05-31", null, " ").toJobParameters();

        assertThat(p.getLong("runAt")).isNotNull();
        assertThat(p.getString("mode")).isEqualTo("daily");
        assertThat(p.getString("targetDate")).isEqualTo("2026-05-31");
        assertThat(p.getParameters()).doesNotContainKeys("from", "to");
    }

    @Test
    @DisplayName("민원 backfill — from/to 정규화, mode trim")
    void complaintBackfill() {
        JobParameters p = JobParameterFactory.complaintFetch(" backfill ", null, " 2026-11-01 ", "2026-11-02")
                .toJobParameters();

        assertThat(p.getString("mode")).isEqualTo("backfill");
        assertThat(p.getString("from")).isEqualTo("2026-11-01");
        assertThat(p.getString("to")).isEqualTo("2026-11-02");
        assertThat(p.getParameters()).doesNotContainKey("targetDate");
    }

    @Test
    @DisplayName("민원 — 생략 전부면 runAt 만(mode 는 dhub.query-mode 가 정한다)")
    void complaintNoParams() {
        JobParameters p = JobParameterFactory.complaintFetch(null, null, null, null).toJobParameters();
        assertThat(p.getParameters()).containsOnlyKeys("runAt");
    }

    @Test
    @DisplayName("🆕 단속 — 생략이면 runAt 만(= 증분), from/to 는 정규화해 넣는다")
    void crackdownParams() {
        assertThat(JobParameterFactory.crackdownFetch(null, " ").toJobParameters().getParameters())
                .containsOnlyKeys("runAt");

        JobParameters p = JobParameterFactory.crackdownFetch(" 2026-09-01 ", "2026-10-01").toJobParameters();
        assertThat(p.getString("from")).isEqualTo("2026-09-01");
        assertThat(p.getString("to")).isEqualTo("2026-10-01");

        assertThat(JobParameterFactory.crackdownFetch("2026-09-01", null).toJobParameters().getParameters())
                .containsOnlyKeys("runAt", "from");
    }

    @Test
    @DisplayName("🆕 단속 — to 단독·뒤집힌 구간·형식 오류는 Job 시작 전에 끊는다")
    void crackdownBadParams() {
        assertThatThrownBy(() -> JobParameterFactory.crackdownFetch(null, "2026-10-01"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> JobParameterFactory.crackdownFetch("2026-10-01", "2026-10-01"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobParameterFactory.crackdownFetch("2026/10/01", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("민원 — 날짜 형식이 틀리면 Job 시작 전에 끊는다")
    void complaintBadDate() {
        assertThatThrownBy(() -> JobParameterFactory.complaintFetch("backfill", null, "2026/11/01", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("위험판정 — 생략이면 어제(Asia/Seoul)가 항상 실린다 · 지정이면 그대로")
    void riskAlertJudge() {
        JobParameters dflt = JobParameterFactory.riskAlertJudge(null).toJobParameters();
        assertThat(dflt.getString("targetDate")).isEqualTo(RiskAlertProperties.resolveTargetDate(null).toString());
        assertThat(LocalDate.parse(dflt.getString("targetDate")))
                .isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));

        JobParameters given = JobParameterFactory.riskAlertJudge(LocalDate.parse("2025-12-31")).toJobParameters();
        assertThat(given.getString("targetDate")).isEqualTo("2025-12-31");
        assertThat(given.getLong("runAt")).isNotNull();
    }

    @Test
    @DisplayName("백필 — 생략은 키째 없음(= 전 기간) · 지정은 그대로")
    void receiverBackfill() {
        assertThat(JobParameterFactory.riskAlertReceiverBackfill(null, null).toJobParameters().getParameters())
                .containsOnlyKeys("runAt");

        JobParameters p = JobParameterFactory.riskAlertReceiverBackfill(
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")).toJobParameters();
        assertThat(p.getString("fromDt")).isEqualTo("2026-11-01");
        assertThat(p.getString("toDt")).isEqualTo("2026-11-02");

        JobParameters onlyTo = JobParameterFactory.riskAlertReceiverBackfill(null, LocalDate.parse("2026-11-02"))
                .toJobParameters();
        assertThat(onlyTo.getParameters()).containsOnlyKeys("runAt", "toDt");
    }

    @Test
    @DisplayName("보고서 — 생략이면 오늘(Asia/Seoul — UTC 전날 15시 이후는 다음 날) · 지정이면 그대로")
    void reportAutoCrt() {
        // 2026-10-31T16:00Z = 2026-11-01 01:00 KST — 서버가 UTC 여도 한국 날짜다.
        Clock utc = Clock.fixed(Instant.parse("2026-10-31T16:00:00Z"), ZoneId.of("UTC"));
        assertThat(JobParameterFactory.reportAutoCrt(null, utc).toJobParameters().getString("baseYmd"))
                .isEqualTo("2026-11-01");
        assertThat(JobParameterFactory.reportAutoCrt(LocalDate.parse("2026-10-01")).toJobParameters()
                .getString("baseYmd")).isEqualTo("2026-10-01");
        assertThat(JobParameterFactory.reportAutoCrt(null).toJobParameters().getString("baseYmd"))
                .isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")).toString());
    }

    @Test
    @DisplayName("구역명 — gridSz 생략이면 키 없음(전 크기) · 지원 크기는 Long · 지원 밖은 예외")
    void zoneNmPrefill() {
        assertThat(JobParameterFactory.zoneNmPrefill(null).toJobParameters().getParameters())
                .containsOnlyKeys("runAt");
        assertThat(JobParameterFactory.zoneNmPrefill(500L).toJobParameters().getLong("gridSz")).isEqualTo(500L);
        assertThatThrownBy(() -> JobParameterFactory.zoneNmPrefill(300L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("실행 구분은 호출자가 붙인다 — 비식별 · 업무 파라미터는 식별")
    void excnSeIsNonIdentifying() {
        JobParameters api = ExcnSe.tag(JobParameterFactory.reportAutoCrt(LocalDate.parse("2026-10-01")), ExcnSe.API)
                .toJobParameters();
        assertThat(api.getString(ExcnSe.PARAM_EXCN_SE)).isEqualTo(ExcnSe.API);
        assertThat(api.getParameter(ExcnSe.PARAM_EXCN_SE).isIdentifying()).isFalse();
        assertThat(api.getParameter("baseYmd").isIdentifying()).isTrue();

        JobParameters manual = ExcnSe.manual(JobParameterFactory.base(), "user01").toJobParameters();
        assertThat(manual.getString(ExcnSe.PARAM_EXCN_SE)).isEqualTo(ExcnSe.MANUAL);
        assertThat(manual.getString(ExcnSe.PARAM_EXCUTR_ID)).isEqualTo("user01");
        assertThat(manual.getParameter(ExcnSe.PARAM_EXCUTR_ID).isIdentifying()).isFalse();
    }
}
