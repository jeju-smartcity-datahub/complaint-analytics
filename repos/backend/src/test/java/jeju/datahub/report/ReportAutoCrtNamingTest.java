package jeju.datahub.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.report.service.impl.ReportAutoCrtNaming;
import jeju.datahub.report.service.impl.ReportAutoCrtNaming.TargetPeriod;

/**
 * {@link ReportAutoCrtNaming} 단위 테스트 (TSK-4027 Step 3 검증).
 *
 * <p><b>스프링 컨텍스트도 DB 도 띄우지 않는다</b> — 검증 대상이 날짜 계산과 문자열 조립뿐이다
 * ({@code ComplaintReportNamingTest}·{@code HotspotReportNamingTest} 와 같은 성격).
 *
 * <p>🔴 <b>여기서 못박는 것이 멱등키({@code trgt_prd_cd})의 정의다.</b> 케이스 1~7 은 계획서 3-3
 * 의 표 7행을 <b>한 칸도 바꾸지 않고</b> 옮긴 것이며, 이 문자열이 달라지면 같은 주기가 다른 키로
 * 두 번 적재된다. 케이스 8~9 는 윤년, 케이스 10 은 비표준 기준월 폴백(D13),
 * 케이스 11 은 걷어낸 시드에서 채록한 형식 정본과의 대조다.
 */
class ReportAutoCrtNamingTest {

    // ── 계획서 3-3 케이스 표 7행 ────────────────────────────────────────────────

    @Test
    @DisplayName("1. MONTH · 실행일 2026-09-01 → 2026-08 / 20260801~20260831 / 제주시 연동")
    void case01_month_202609() {
        TargetPeriod p = ReportAutoCrtNaming.targetPeriod("MONTH", null, LocalDate.of(2026, 9, 1));

        assertThat(p.trgtPrdCd()).isEqualTo("2026-08");
        assertThat(p.bgngYmd()).isEqualTo("20260801");
        assertThat(p.endYmd()).isEqualTo("20260831");
        assertThat(ReportAutoCrtNaming.rptpNm(p, "CVLCPT", "제주시", "연동"))
                .isEqualTo("2026년 8월 월간 민원 보고서 - 제주시 연동");
    }

    @Test
    @DisplayName("2. MONTH · 실행일 2026-03-01 → 2026-02 / 20260201~20260228 (평년 28일)")
    void case02_month_202603() {
        TargetPeriod p = ReportAutoCrtNaming.targetPeriod("MONTH", null, LocalDate.of(2026, 3, 1));

        assertThat(p.trgtPrdCd()).isEqualTo("2026-02");
        assertThat(p.bgngYmd()).isEqualTo("20260201");
        assertThat(p.endYmd()).isEqualTo("20260228");
        assertThat(ReportAutoCrtNaming.rptpNm(p, "CVLCPT", "제주시", "연동"))
                .isEqualTo("2026년 2월 월간 민원 보고서 - 제주시 연동");
    }

    @Test
    @DisplayName("3. QUARTER(mm=4) · 실행일 2026-04-01 → 2026-Q1 / 20260101~20260331 / 제주시")
    void case03_quarter_2026q1() {
        TargetPeriod p = ReportAutoCrtNaming.targetPeriod("QUARTER", 4, LocalDate.of(2026, 4, 1));

        assertThat(p.trgtPrdCd()).isEqualTo("2026-Q1");
        assertThat(p.bgngYmd()).isEqualTo("20260101");
        assertThat(p.endYmd()).isEqualTo("20260331");
        assertThat(ReportAutoCrtNaming.rptpNm(p, "CVLCPT", "제주시", null))
                .isEqualTo("2026년 1/4분기 민원 보고서 - 제주시");
    }

    @Test
    @DisplayName("4. QUARTER(mm=4) · 실행일 2026-01-01 → 2025-Q4 / 20251001~20251231 (연 경계)")
    void case04_quarter_2025q4() {
        TargetPeriod p = ReportAutoCrtNaming.targetPeriod("QUARTER", 4, LocalDate.of(2026, 1, 1));

        assertThat(p.trgtPrdCd()).isEqualTo("2025-Q4");
        assertThat(p.bgngYmd()).isEqualTo("20251001");
        assertThat(p.endYmd()).isEqualTo("20251231");
        assertThat(ReportAutoCrtNaming.rptpNm(p, "CVLCPT", "제주시", null))
                .isEqualTo("2025년 4/4분기 민원 보고서 - 제주시");
    }

    @Test
    @DisplayName("5. HALF(mm=7) · 실행일 2026-07-01 → 2026-H1 / 20260101~20260630 / 서귀포시")
    void case05_half_2026h1() {
        TargetPeriod p = ReportAutoCrtNaming.targetPeriod("HALF", 7, LocalDate.of(2026, 7, 1));

        assertThat(p.trgtPrdCd()).isEqualTo("2026-H1");
        assertThat(p.bgngYmd()).isEqualTo("20260101");
        assertThat(p.endYmd()).isEqualTo("20260630");
        assertThat(ReportAutoCrtNaming.rptpNm(p, "CVLCPT", "서귀포시", null))
                .isEqualTo("2026년 상반기 민원 보고서 - 서귀포시");
    }

    @Test
    @DisplayName("6. HALF(mm=7) · 실행일 2026-01-01 → 2025-H2 / 20250701~20251231 (연 경계)")
    void case06_half_2025h2() {
        TargetPeriod p = ReportAutoCrtNaming.targetPeriod("HALF", 7, LocalDate.of(2026, 1, 1));

        assertThat(p.trgtPrdCd()).isEqualTo("2025-H2");
        assertThat(p.bgngYmd()).isEqualTo("20250701");
        assertThat(p.endYmd()).isEqualTo("20251231");
        assertThat(ReportAutoCrtNaming.rptpNm(p, "CVLCPT", "서귀포시", null))
                .isEqualTo("2025년 하반기 민원 보고서 - 서귀포시");
    }

    @Test
    @DisplayName("7. YEAR(mm=1) · 실행일 2026-01-01 → 2025 / 20250101~20251231 / 집중구역 · 전체 지역")
    void case07_year_2025() {
        TargetPeriod p = ReportAutoCrtNaming.targetPeriod("YEAR", 1, LocalDate.of(2026, 1, 1));

        assertThat(p.trgtPrdCd()).isEqualTo("2025");
        assertThat(p.bgngYmd()).isEqualTo("20250101");
        assertThat(p.endYmd()).isEqualTo("20251231");
        assertThat(ReportAutoCrtNaming.rptpNm(p, "HOTSPOT", null, null))
                .isEqualTo("2025년 전체 집중구역 보고서 - 전체 지역");
    }

    // ── 윤년 2케이스 (28·30·31 하드코딩 금지) ──────────────────────────────────

    @Test
    @DisplayName("8. 윤년 아님 — 2026-02 는 28일")
    void case08_leap_2026_28days() {
        assertThat(ReportAutoCrtNaming.targetPeriod("MONTH", null, LocalDate.of(2026, 3, 1)).endYmd())
                .isEqualTo("20260228");
    }

    @Test
    @DisplayName("9. 윤년 — 2028-02 는 29일")
    void case09_leap_2028_29days() {
        TargetPeriod p = ReportAutoCrtNaming.targetPeriod("MONTH", null, LocalDate.of(2028, 3, 1));

        assertThat(p.trgtPrdCd()).isEqualTo("2028-02");
        assertThat(p.bgngYmd()).isEqualTo("20280201");
        assertThat(p.endYmd()).isEqualTo("20280229");
    }

    // ── 비표준 기준월 폴백 (D13) ──────────────────────────────────────────────

    @Test
    @DisplayName("10. QUARTER(mm=2) 비표준 경계 → 2026-02~04 로 떨어뜨린다 (Q 표기 금지)")
    void case10_nonStandardAnchorFallback() {
        TargetPeriod p = ReportAutoCrtNaming.targetPeriod("QUARTER", 2, LocalDate.of(2026, 5, 1));

        // 🔴 멱등키는 결정적이기만 하면 된다 — 사람이 읽는 값이 아니다.
        assertThat(p.trgtPrdCd()).isEqualTo("2026-02~04");
        assertThat(p.trgtPrdCd()).doesNotContain("Q");
        assertThat(p.trgtPrdCd().length()).isLessThanOrEqualTo(20);   // trgt_prd_cd 는 VARCHAR(20)
        assertThat(p.bgngYmd()).isEqualTo("20260201");
        assertThat(p.endYmd()).isEqualTo("20260430");
        assertThat(ReportAutoCrtNaming.rptpNm(p, "CVLCPT", null, null))
                .startsWith("2026년 2월~4월 민원 보고서")
                .isEqualTo("2026년 2월~4월 민원 보고서 - 전체 지역");
    }

    // ── 시드 형식 정본 대조 (계획서 "보고서명·대상기간의 형식 정본") ─────────────

    @Test
    @DisplayName("11. 걷어낸 시드 형식과 문자 단위로 같다 — 2026-06 / 2026-Q2 / 2026-H1 / 2025")
    void case11_seedFormatParity() {
        TargetPeriod month = ReportAutoCrtNaming.targetPeriod("MONTH", null, LocalDate.of(2026, 7, 1));
        TargetPeriod quarter = ReportAutoCrtNaming.targetPeriod("QUARTER", 4, LocalDate.of(2026, 7, 1));
        TargetPeriod half = ReportAutoCrtNaming.targetPeriod("HALF", 7, LocalDate.of(2026, 7, 1));
        TargetPeriod year = ReportAutoCrtNaming.targetPeriod("YEAR", 1, LocalDate.of(2026, 1, 1));

        assertThat(month.trgtPrdCd()).isEqualTo("2026-06");
        assertThat(quarter.trgtPrdCd()).isEqualTo("2026-Q2");
        assertThat(half.trgtPrdCd()).isEqualTo("2026-H1");
        assertThat(year.trgtPrdCd()).isEqualTo("2025");

        // 시드 이름에는 지역이 없었다 — 접미(" - {지역}")만 새로 붙고 앞부분은 그대로여야 한다(D4).
        assertThat(ReportAutoCrtNaming.rptpNm(month, "CVLCPT", null, null))
                .startsWith("2026년 6월 월간 민원 보고서");
        assertThat(ReportAutoCrtNaming.rptpNm(quarter, "CVLCPT", null, null))
                .startsWith("2026년 2/4분기 민원 보고서");
        assertThat(ReportAutoCrtNaming.rptpNm(half, "CVLCPT", null, null))
                .startsWith("2026년 상반기 민원 보고서");
        assertThat(ReportAutoCrtNaming.rptpNm(year, "CVLCPT", null, null))
                .startsWith("2025년 전체 민원 보고서");
    }

    // ── 지역 접미 3단 · 유형어 ────────────────────────────────────────────────

    @Test
    @DisplayName("12. 지역 접미 3단 — 시군구만 있으면 ' 전체' 를 붙이지 않는다(민원 규칙과 갈리는 자리)")
    void case12_regionNm() {
        assertThat(ReportAutoCrtNaming.regionNm(null, null)).isEqualTo("전체 지역");
        assertThat(ReportAutoCrtNaming.regionNm("", "  ")).isEqualTo("전체 지역");
        assertThat(ReportAutoCrtNaming.regionNm("제주시", null)).isEqualTo("제주시");
        assertThat(ReportAutoCrtNaming.regionNm("서귀포시", "")).isEqualTo("서귀포시");
        assertThat(ReportAutoCrtNaming.regionNm("제주시", "연동")).isEqualTo("제주시 연동");

        // emdNm 만 오면 "null 연동" 으로 새지 않도록 끊는다.
        assertThatThrownBy(() -> ReportAutoCrtNaming.regionNm(null, "연동"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("13. 유형어 — CVLCPT=민원 · HOTSPOT=집중구역 · 그 밖은 예외")
    void case13_typeWord() {
        assertThat(ReportAutoCrtNaming.typeWord("CVLCPT")).isEqualTo("민원");
        assertThat(ReportAutoCrtNaming.typeWord("HOTSPOT")).isEqualTo("집중구역");
        assertThatThrownBy(() -> ReportAutoCrtNaming.typeWord("DASHBOARD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportAutoCrtNaming.typeWord(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("14. 잘못된 입력 — 미등록 주기 코드 · MONTH 아닌데 기준월 없음 · 범위 밖 기준월")
    void case14_invalidInput() {
        assertThatThrownBy(
                () -> ReportAutoCrtNaming.targetPeriod("WEEK", 1, LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> ReportAutoCrtNaming.targetPeriod("QUARTER", null, LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> ReportAutoCrtNaming.targetPeriod("HALF", 13, LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
