package jeju.datahub.batch.rptp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jeju.datahub.batch.rptp.ReportDueDateResolver.TargetPeriod;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ReportDueDateResolver} 단위 테스트 (검증).
 *
 * <p><b>스프링 컨텍스트도 DB 도 띄우지 않는다</b> — 검증 대상이 날짜 계산과 문자열 조립뿐이다.
 *
 * <p>🔴 <b>케이스 1~7 은 백엔드 {@code ReportAutoCrtNamingTest} 와 같은 7행이다</b>(D6).
 * 레포가 달라 코드를 공유할 수 없으므로 <b>같은 케이스 표를 양쪽에 두는 것</b>이 대응이다 —
 * 한쪽 규칙이 바뀌면 여기가 깨져 드러난다. {@code trgtPrdCd} 는 멱등키라 한 글자만 달라져도
 * 같은 주기가 두 번 적재된다.
 *
 * <p>⚠️ 배치는 <b>보고서명을 만들지 않는다</b>(백엔드 몫) — 그래서 표의 {@code rptpNm} 열은
 * 여기서 단언하지 않고, 멱등키·구간 세 값과 도래 판정을 단언한다.
 */
class ReportDueDateResolverTest {

    private final ReportDueDateResolver resolver = new ReportDueDateResolver();

    /** 케이스 표는 전부 배포기준일 1일 설정이다. */
    private static RptpStngRow stng(String cyclCd, Integer mm, Integer dd) {
        return new RptpStngRow(100L, "CVLCPT", cyclCd, mm, dd, null, null, "DEPT001");
    }

    // ── 케이스 표 7행 (백엔드 ReportAutoCrtNamingTest 와 같은 기대값) ──────────

    @Test
    @DisplayName("1. MONTH · 기준일 2026-09-01 → 도래 · 2026-08 / 20260801~20260831")
    void case01_month_202609() {
        RptpStngRow s = stng("MONTH", null, 1);
        assertThat(resolver.isDue(s, LocalDate.of(2026, 9, 1))).isTrue();

        TargetPeriod p = resolver.resolve(s, LocalDate.of(2026, 9, 1));
        assertThat(p.trgtPrdCd()).isEqualTo("2026-08");
        assertThat(p.bgngYmd()).isEqualTo("20260801");
        assertThat(p.endYmd()).isEqualTo("20260831");
    }

    @Test
    @DisplayName("2. MONTH · 기준일 2026-03-01 → 2026-02 / 20260201~20260228 (평년 28일)")
    void case02_month_202603() {
        RptpStngRow s = stng("MONTH", null, 1);
        assertThat(resolver.isDue(s, LocalDate.of(2026, 3, 1))).isTrue();

        TargetPeriod p = resolver.resolve(s, LocalDate.of(2026, 3, 1));
        assertThat(p.trgtPrdCd()).isEqualTo("2026-02");
        assertThat(p.bgngYmd()).isEqualTo("20260201");
        assertThat(p.endYmd()).isEqualTo("20260228");
    }

    @Test
    @DisplayName("3. QUARTER(mm=4) · 기준일 2026-04-01 → 2026-Q1 / 20260101~20260331")
    void case03_quarter_2026q1() {
        RptpStngRow s = stng("QUARTER", 4, 1);
        assertThat(resolver.isDue(s, LocalDate.of(2026, 4, 1))).isTrue();

        TargetPeriod p = resolver.resolve(s, LocalDate.of(2026, 4, 1));
        assertThat(p.trgtPrdCd()).isEqualTo("2026-Q1");
        assertThat(p.bgngYmd()).isEqualTo("20260101");
        assertThat(p.endYmd()).isEqualTo("20260331");
    }

    @Test
    @DisplayName("4. QUARTER(mm=4) · 기준일 2026-01-01 → 2025-Q4 / 20251001~20251231 (연 경계)")
    void case04_quarter_2025q4() {
        RptpStngRow s = stng("QUARTER", 4, 1);
        // 🔴 month - crltnMm = 1 - 4 = -3 (음수) — floorMod 가 0 을 내야 도래로 잡힌다.
        assertThat(resolver.isDue(s, LocalDate.of(2026, 1, 1))).isTrue();

        TargetPeriod p = resolver.resolve(s, LocalDate.of(2026, 1, 1));
        assertThat(p.trgtPrdCd()).isEqualTo("2025-Q4");
        assertThat(p.bgngYmd()).isEqualTo("20251001");
        assertThat(p.endYmd()).isEqualTo("20251231");
    }

    @Test
    @DisplayName("5. HALF(mm=7) · 기준일 2026-07-01 → 2026-H1 / 20260101~20260630")
    void case05_half_2026h1() {
        RptpStngRow s = stng("HALF", 7, 1);
        assertThat(resolver.isDue(s, LocalDate.of(2026, 7, 1))).isTrue();

        TargetPeriod p = resolver.resolve(s, LocalDate.of(2026, 7, 1));
        assertThat(p.trgtPrdCd()).isEqualTo("2026-H1");
        assertThat(p.bgngYmd()).isEqualTo("20260101");
        assertThat(p.endYmd()).isEqualTo("20260630");
    }

    @Test
    @DisplayName("6. HALF(mm=7) · 기준일 2026-01-01 → 2025-H2 / 20250701~20251231 (연 경계)")
    void case06_half_2025h2() {
        RptpStngRow s = stng("HALF", 7, 1);
        assertThat(resolver.isDue(s, LocalDate.of(2026, 1, 1))).isTrue();

        TargetPeriod p = resolver.resolve(s, LocalDate.of(2026, 1, 1));
        assertThat(p.trgtPrdCd()).isEqualTo("2025-H2");
        assertThat(p.bgngYmd()).isEqualTo("20250701");
        assertThat(p.endYmd()).isEqualTo("20251231");
    }

    @Test
    @DisplayName("7. YEAR(mm=1) · 기준일 2026-01-01 → 2025 / 20250101~20251231")
    void case07_year_2025() {
        RptpStngRow s = stng("YEAR", 1, 1);
        assertThat(resolver.isDue(s, LocalDate.of(2026, 1, 1))).isTrue();

        TargetPeriod p = resolver.resolve(s, LocalDate.of(2026, 1, 1));
        assertThat(p.trgtPrdCd()).isEqualTo("2025");
        assertThat(p.bgngYmd()).isEqualTo("20250101");
        assertThat(p.endYmd()).isEqualTo("20251231");
    }

    // ── 도래하지 <b>않는</b> 날 — 주기 4종 전부 ─────────────────────────────────

    @Test
    @DisplayName("8. 일자가 다르면 주기 4종 모두 false — crltnDd=1 설정 × 2026-09-15")
    void case08_notDue_wrongDayOfMonth() {
        LocalDate notDue = LocalDate.of(2026, 9, 15);

        assertThat(resolver.isDue(stng("MONTH", null, 1), notDue)).isFalse();
        assertThat(resolver.isDue(stng("QUARTER", 4, 1), notDue)).isFalse();
        assertThat(resolver.isDue(stng("HALF", 7, 1), notDue)).isFalse();
        assertThat(resolver.isDue(stng("YEAR", 1, 1), notDue)).isFalse();
    }

    @Test
    @DisplayName("9. 일자는 맞지만 월이 어긋나면 false — MONTH 만 통과한다")
    void case09_notDue_wrongMonth() {
        LocalDate base = LocalDate.of(2026, 9, 1);   // 기준일(1일)은 맞다

        assertThat(resolver.isDue(stng("MONTH", null, 1), base)).isTrue();    // 매달 도래
        assertThat(resolver.isDue(stng("QUARTER", 4, 1), base)).isFalse();    // 9 - 4 = 5 → %3 = 2
        assertThat(resolver.isDue(stng("HALF", 7, 1), base)).isFalse();       // 9 - 7 = 2 → %6 = 2
        assertThat(resolver.isDue(stng("YEAR", 1, 1), base)).isFalse();       // 9 ≠ 1
    }

    // ── 🔴 floorMod — 기준월이 실행월보다 큰(음수) 조합 ──────────────────────────

    @Test
    @DisplayName("10. floorMod 음수 — QUARTER(mm=10) × 2026-01-01 은 도래, (mm=11) 은 아니다")
    void case10_floorMod_negativeQuarter() {
        // 1 - 10 = -9 → floorMod(-9, 3) = 0  (도래)
        assertThat(resolver.isDue(stng("QUARTER", 10, 1), LocalDate.of(2026, 1, 1))).isTrue();
        // 1 - 11 = -10 → floorMod(-10, 3) = 2 (아님)
        assertThat(resolver.isDue(stng("QUARTER", 11, 1), LocalDate.of(2026, 1, 1))).isFalse();
    }

    @Test
    @DisplayName("11. floorMod 음수 — HALF(mm=12) × 2026-06-01 도래 · YEAR(mm=7) × 2026-01-01 아님")
    void case11_floorMod_negativeHalfAndYear() {
        // 6 - 12 = -6 → floorMod(-6, 6) = 0  (도래)
        assertThat(resolver.isDue(stng("HALF", 12, 1), LocalDate.of(2026, 6, 1))).isTrue();
        // 1 - 7 = -6 → floorMod(-6, 12) = 6 (아님) — YEAR 은 월이 정확히 같아야 한다
        assertThat(resolver.isDue(stng("YEAR", 7, 1), LocalDate.of(2026, 1, 1))).isFalse();
        assertThat(resolver.isDue(stng("YEAR", 7, 1), LocalDate.of(2026, 7, 1))).isTrue();
    }

    // ── 🔴 WEEK · 판정 불가 설정 — false + WARN (예외 금지) ──────────────────────

    @Test
    @DisplayName("12. WEEK 은 판정하지 않는다 — false + WARN 1줄, 예외를 던지지 않는다")
    void case12_weekIsNotJudged() {
        List<String> warns = captureWarn();
        try {
            RptpStngRow week = stng("WEEK", null, 1);

            assertThat(resolver.isDue(week, LocalDate.of(2026, 9, 1))).isFalse();
            assertThat(warns).hasSize(1);
            assertThat(warns.get(0)).contains("WEEK").contains("100");
        } finally {
            releaseWarn();
        }
    }

    @Test
    @DisplayName("13. 판정 불가 설정도 false + WARN — 기준일 없음 · 기준월 없음/범위 밖")
    void case13_unjudgeableSettings() {
        List<String> warns = captureWarn();
        try {
            LocalDate base = LocalDate.of(2026, 9, 1);

            // crltn_dd 가 NULL — SMALLINT nullable 이라 실제로 올 수 있다
            assertThat(resolver.isDue(stng("MONTH", null, null), base)).isFalse();
            // QUARTER 인데 기준월이 없다
            assertThat(resolver.isDue(stng("QUARTER", null, 1), base)).isFalse();
            // 기준월이 1~12 밖이다
            assertThat(resolver.isDue(stng("HALF", 13, 1), base)).isFalse();
            // 주기 코드 자체가 비었다
            assertThat(resolver.isDue(stng(null, null, 1), base)).isFalse();

            assertThat(warns).hasSize(4);
        } finally {
            releaseWarn();
        }
    }

    // ── 윤년 (28·30·31 하드코딩 금지) ────────────────────────────────────────

    @Test
    @DisplayName("14. 윤년 — 2028-02 는 29일 (2026-02 는 28일: 케이스 2)")
    void case14_leapYear() {
        TargetPeriod p = resolver.resolve(stng("MONTH", null, 1), LocalDate.of(2028, 3, 1));

        assertThat(p.trgtPrdCd()).isEqualTo("2028-02");
        assertThat(p.bgngYmd()).isEqualTo("20280201");
        assertThat(p.endYmd()).isEqualTo("20280229");
    }

    // ── 비표준 기준월 폴백 (D13) ────────────────────────────────────────────

    @Test
    @DisplayName("15. QUARTER(mm=2) 비표준 경계 → 2026-02~04 로 떨어뜨린다 (Q 표기 금지)")
    void case15_nonStandardAnchorFallback() {
        RptpStngRow s = stng("QUARTER", 2, 1);
        assertThat(resolver.isDue(s, LocalDate.of(2026, 5, 1))).isTrue();

        TargetPeriod p = resolver.resolve(s, LocalDate.of(2026, 5, 1));
        // 🔴 멱등키는 결정적이기만 하면 된다 — 사람이 읽는 값이 아니다.
        assertThat(p.trgtPrdCd()).isEqualTo("2026-02~04");
        assertThat(p.trgtPrdCd()).doesNotContain("Q");
        assertThat(p.trgtPrdCd().length()).isLessThanOrEqualTo(20);   // trgt_prd_cd 는 VARCHAR(20)
        assertThat(p.bgngYmd()).isEqualTo("20260201");
        assertThat(p.endYmd()).isEqualTo("20260430");
    }

    @Test
    @DisplayName("16. 비표준 폴백이 해를 넘기면 끝쪽에도 연도를 적는다 — 2025-11~2026-01")
    void case16_nonStandardAnchorAcrossYear() {
        TargetPeriod p = resolver.resolve(stng("QUARTER", 2, 1), LocalDate.of(2026, 2, 1));

        assertThat(p.trgtPrdCd()).isEqualTo("2025-11~2026-01");
        assertThat(p.trgtPrdCd().length()).isLessThanOrEqualTo(20);
        assertThat(p.bgngYmd()).isEqualTo("20251101");
        assertThat(p.endYmd()).isEqualTo("20260131");
    }

    // ── 시드 형식 정본 대조 (백엔드 케이스 11 과 같은 값) ───────────────────────

    @Test
    @DisplayName("17. 형식 정본 — 2026-06 / 2026-Q2 / 2026-H1 / 2025 (백엔드와 문자 단위로 같다)")
    void case17_formatParityWithBackend() {
        assertThat(resolver.resolve(stng("MONTH", null, 1), LocalDate.of(2026, 7, 1)).trgtPrdCd())
                .isEqualTo("2026-06");
        assertThat(resolver.resolve(stng("QUARTER", 4, 1), LocalDate.of(2026, 7, 1)).trgtPrdCd())
                .isEqualTo("2026-Q2");
        assertThat(resolver.resolve(stng("HALF", 7, 1), LocalDate.of(2026, 7, 1)).trgtPrdCd())
                .isEqualTo("2026-H1");
        assertThat(resolver.resolve(stng("YEAR", 1, 1), LocalDate.of(2026, 1, 1)).trgtPrdCd())
                .isEqualTo("2025");
    }

    // ── resolve 의 방어 · 기준일 파싱 ───────────────────────────────────────

    @Test
    @DisplayName("18. resolve 는 판정 불가 설정에서 예외다 — isDue 를 건너뛴 호출 순서 오류를 드러낸다")
    void case18_resolveRejectsUnjudgeable() {
        LocalDate base = LocalDate.of(2026, 9, 1);

        assertThatThrownBy(() -> resolver.resolve(stng("WEEK", 1, 1), base))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(stng("QUARTER", null, 1), base))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(stng("HALF", 13, 1), base))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("19. baseYmd 는 필수다 — 비면 '오늘' 로 떨어지지 않고 예외다")
    void case19_parseBaseYmd() {
        assertThat(ReportDueDateResolver.parseBaseYmd("2026-09-01"))
                .isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(ReportDueDateResolver.parseBaseYmd(" 2026-09-01 "))
                .isEqualTo(LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> ReportDueDateResolver.parseBaseYmd(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportDueDateResolver.parseBaseYmd("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── WARN 캡처 유틸 (Log4j2) ─────────────────────────────────────────────

    private static final String CAPTURE_NAME = "reportDueDateResolverWarnCapture";

    private Appender captureAppender;

    /**
     * {@link ReportDueDateResolver} 로거에 임시 Appender 를 붙여 WARN 메시지를 모은다.
     *
     * <p>⚠️ {@code Configuration#getLoggerConfig(name)} 에 붙이면 전용 LoggerConfig 가 없는 한
     * <b>루트</b>에 붙어 이벤트가 오지 않는다(실측). {@code core.Logger#addAppender} 는 그 로거
     * 전용 LoggerConfig 를 만들어 붙이므로 확실하다.
     */
    private List<String> captureWarn() {
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        captureAppender = new AbstractAppender(CAPTURE_NAME, (Filter) null,
                (Layout<? extends Serializable>) null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN) {
                    messages.add(event.getMessage().getFormattedMessage());
                }
            }
        };
        captureAppender.start();
        // ⚠️ 설정 파일 없이 도는 단위 테스트라 Log4j2 는 DefaultConfiguration(루트 ERROR)로 뜬다.
        //    레벨을 올려 두지 않으면 WARN 이벤트 자체가 만들어지지 않아 Appender 가 영영 빈다(실측).
        Configurator.setLevel(ReportDueDateResolver.class.getName(), Level.WARN);
        coreLogger().addAppender(captureAppender);
        return messages;
    }

    @BeforeEach
    void resetCapture() {
        captureAppender = null;
    }

    @AfterEach
    void releaseWarn() {
        if (captureAppender != null) {
            coreLogger().removeAppender(captureAppender);
            Configurator.setLevel(ReportDueDateResolver.class.getName(), (Level) null);
            captureAppender.stop();
            captureAppender = null;
        }
    }

    private static org.apache.logging.log4j.core.Logger coreLogger() {
        return (org.apache.logging.log4j.core.Logger)
                LogManager.getLogger(ReportDueDateResolver.class);
    }
}
