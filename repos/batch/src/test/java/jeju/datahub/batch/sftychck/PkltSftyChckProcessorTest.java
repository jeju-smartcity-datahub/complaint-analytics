package jeju.datahub.batch.sftychck;

import static jeju.datahub.batch.sftychck.PkltSftyChckResolverFixture.src;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import jeju.datahub.batch.sftychck.PkltSftyChckResolverFixture.Lot;
import jeju.datahub.batch.sftychck.api.PkltSftyChckOriginRow;

/** Processor 판정 순서·정규화 회귀 (TSK-4254 Step 4·8). 픽스처는 가짜 이름·주소다. */
class PkltSftyChckProcessorTest {

    /** 실행일 고정 — 점검일이 해석되지 않을 때의 trgt_de. */
    private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 19);

    private PkltSftyChckProcessor processor;

    @BeforeEach
    void setUp() {
        PkltSftyChckResolverFixture fx = new PkltSftyChckResolverFixture(List.of(
                new Lot(1, "900", "가람 주차장", "가람동 10"),
                new Lot(2, "901", "나래 주차장", "나래동 20")));
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        processor = new PkltSftyChckProcessor(fx.resolver, clock);
    }

    private static PkltSftyChckOriginRow ok(long id) {
        return src(id, null, "가람 주차장", "가람동 10", "20260301", "H1", "REGULAR", "A", "비고");
    }

    private PkltSftyChckSkippableException skipOf(PkltSftyChckOriginRow row) {
        PkltSftyChckSkippableException e =
                catchThrowableOfType(PkltSftyChckSkippableException.class, () -> processor.process(row));
        assertThat(e).as("🔴 탈락은 null 이 아니라 스킵 예외다").isNotNull();
        return e;
    }

    @Test
    @DisplayName("정상 행 → 적재 행 (pklt_id 는 해석한 우리 id)")
    void happyPath() {
        PkltSftyChckRow out = processor.process(ok(1));
        assertThat(out).isEqualTo(new PkltSftyChckRow(1L, "20260301", "H1", "REGULAR", "A", "비고"));
    }

    @Test
    @DisplayName("일자 정규화: 앞뒤 공백을 걷어 CHAR(8) 로 · 코드도 trim")
    void normalizesYmdAndCodes() {
        PkltSftyChckRow out = processor.process(
                src(1, null, "가람 주차장", "가람동 10", " 20260901 ", " H2 ", " PRECISION ", " C ", null));
        assertThat(out.chckYmd()).isEqualTo("20260901");
        assertThat(out.chckPrdCd()).isEqualTo("H2");
        assertThat(out.sftyChckTypeCd()).isEqualTo("PRECISION");
        assertThat(out.sftyChckRsltCd()).isEqualTo("C");
        assertThat(out.rmrkCn()).isNull();
    }

    @Test
    @DisplayName("원천 점검기간이 없으면 NULL 을 유지한다")
    void derivesPeriod() {
        PkltSftyChckRow out = processor.process(
                src(1, null, "가람 주차장", "가람동 10", "20260901", null, "REGULAR", "A", null));
        assertThat(out.chckPrdCd()).isNull();
    }

    @Test
    @DisplayName("PKLT_NOT_FOUND — trgt_id = 원천 sfty_chck_id · trgt_de = 점검일")
    void pkltNotFound() {
        PkltSftyChckSkippableException e = skipOf(
                src(123, null, "없는 주차장", "없는 주소 1", "20260301", "H1", "REGULAR", "A", null));
        assertThat(e.reason()).isEqualTo(PkltSftyChckSkippableException.PKLT_NOT_FOUND);
        assertThat(e.trgtId()).isEqualTo("123");
        assertThat(e.trgtDe()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(e.note()).contains("후보 0개");
    }

    @Test
    @DisplayName("🔴 판정 순서: 주차장 실패가 일자 실패보다 먼저다 — trgt_de 는 실행일")
    void pkltCheckedBeforeYmd() {
        PkltSftyChckSkippableException e = skipOf(
                src(5, null, "없는 주차장", "없는 주소 1", "2026-03-01", "H1", "BAD", "Z", null));
        assertThat(e.reason()).isEqualTo(PkltSftyChckSkippableException.PKLT_NOT_FOUND);
        assertThat(e.trgtDe()).as("점검일을 해석할 수 없으면 실행일").isEqualTo(RUN_DATE);
    }

    @ParameterizedTest
    @ValueSource(strings = { "2026031", "202603011", "2026-03-01", "20260230", "20261301", "20260100", "abcdefgh" })
    @DisplayName("YMD_INVALID — 없음·8자리 아님·비실존 날짜(STRICT) · trgt_de 는 실행일")
    void ymdInvalid(String ymd) {
        PkltSftyChckSkippableException e = skipOf(
                src(7, null, "가람 주차장", "가람동 10", ymd, "H1", "REGULAR", "A", null));
        assertThat(e.reason()).isEqualTo(PkltSftyChckSkippableException.YMD_INVALID);
        assertThat(e.trgtDe()).isEqualTo(RUN_DATE);
    }

    @Test
    @DisplayName("윤년 2월 29일은 실존 날짜다")
    void leapDayIsValid() {
        assertThat(PkltSftyChckProcessor.parseYmd("20280229")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(PkltSftyChckProcessor.parseYmd("20270229")).isNull();
    }

    @Test
    @DisplayName("🔴 판정 순서: 유형 → 결과 → 시기")
    void codeOrder() {
        assertThat(skipOf(src(1, null, "가람 주차장", "가람동 10", "20260301", "H9", "BAD", "Z", null)).reason())
                .isEqualTo(PkltSftyChckSkippableException.TYPE_UNMAPPED);
        assertThat(skipOf(src(1, null, "가람 주차장", "가람동 10", "20260301", "H9", "REGULAR", "Z", null)).reason())
                .isEqualTo(PkltSftyChckSkippableException.RSLT_UNMAPPED);
        assertThat(skipOf(src(1, null, "가람 주차장", "가람동 10", "20260301", "H9", "REGULAR", "A", null)).reason())
                .isEqualTo(PkltSftyChckSkippableException.PRD_UNMAPPED);
    }

    @Test
    @DisplayName("일자 실패가 코드 실패보다 먼저다")
    void ymdBeforeCodes() {
        assertThat(skipOf(src(1, null, "가람 주차장", "가람동 10", "20260230", "H9", "BAD", "Z", null)).reason())
                .isEqualTo(PkltSftyChckSkippableException.YMD_INVALID);
    }

    @Test
    @DisplayName("같은 날짜·유형이라도 다른 원천 행은 각각 관측으로 보낸다")
    void repeatedNaturalKeyIsStillAnObservation() {
        assertThat(processor.process(ok(1))).isNotNull();
        assertThat(processor.process(ok(2))).isNotNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingDateAndTypeArePreserved(String date) {
        var row=processor.process(src(1,null,"가람 주차장","가람동 10",date,null,null,"D",null));
        assertThat(row.chckYmd()).isNull();
        assertThat(row.chckPrdCd()).isNull();
        assertThat(row.sftyChckTypeCd()).isNull();
        assertThat(row.sftyChckRsltCd()).isEqualTo("D");
    }

    @Test
    @DisplayName("🔴 앞 행이 RSLT_UNMAPPED 면 같은 자연키의 뒤 정상 행이 채택된다 — 실패 행이 키를 선점하지 않는다")
    void failedRowDoesNotTakeKey() {
        PkltSftyChckOriginRow bad = src(1, null, "가람 주차장", "가람동 10", "20260301", "H1", "REGULAR", "Z", null);
        assertThat(skipOf(bad).reason()).isEqualTo(PkltSftyChckSkippableException.RSLT_UNMAPPED);

        PkltSftyChckRow out = processor.process(ok(2));
        assertThat(out.sftyChckRsltCd()).isEqualTo("A");
    }

    @Test
    @DisplayName("🔴 앞 행이 PRD_UNMAPPED 여도 뒤 정상 행이 채택된다(키 등록은 맨 마지막)")
    void prdFailureDoesNotTakeKey() {
        skipOf(src(1, null, "가람 주차장", "가람동 10", "20260301", "H9", "REGULAR", "A", null));
        assertThat(processor.process(ok(2))).isNotNull();
    }

    @Test
    @DisplayName("rmrk_cn 4000자 절단 — 4000자는 그대로, 4001자는 4000자로")
    void truncatesRmrk() {
        String exact = "가".repeat(4000);
        assertThat(PkltSftyChckProcessor.truncateRmrk(exact)).isSameAs(exact);
        assertThat(PkltSftyChckProcessor.truncateRmrk("가".repeat(4001))).hasSize(4000);

        PkltSftyChckRow out = processor.process(
                src(1, null, "가람 주차장", "가람동 10", "20260301", "H1", "REGULAR", "A", "x".repeat(5000)));
        assertThat(out.rmrkCn()).hasSize(4000);
    }

    @Test
    @DisplayName("rmrk_cn 절단은 문자 단위다 — 서로게이트 쌍(이모지)을 가르지 않는다")
    void truncatesByCodePoint() {
        String emoji = "🚗"; // 1문자 · UTF-16 2단위
        String cut = PkltSftyChckProcessor.truncateRmrk(emoji.repeat(4001));
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(4000);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("원천 키 경로로도 같은 판정을 탄다 — pklt_id 901 + 이름 검증 → 우리 2")
    void keyPath() {
        PkltSftyChckRow out = processor.process(
                src(1, 901L, "나래 주차장", "다른 주소", "20260301", "H1", "REGULAR", "A", null));
        assertThat(out.pkltId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("스킵 예외는 정해진 7종 사유만 쓴다")
    void reasonsAreWhitelisted() {
        assertThatThrownBy(() -> processor.process(
                src(1, null, "없는 주차장", "없는 주소", "20260301", "H1", "REGULAR", "A", null)))
                .isInstanceOf(PkltSftyChckSkippableException.class)
                .satisfies(t -> assertThat(((PkltSftyChckSkippableException) t).reason())
                        .isIn("PKLT_NOT_FOUND", "PKLT_AMBIGUOUS", "YMD_INVALID", "TYPE_UNMAPPED",
                                "RSLT_UNMAPPED", "PRD_UNMAPPED", "DUP_KEY"));
    }
}
