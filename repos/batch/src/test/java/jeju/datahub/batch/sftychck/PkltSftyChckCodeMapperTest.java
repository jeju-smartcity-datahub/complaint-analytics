package jeju.datahub.batch.sftychck;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import jeju.datahub.batch.sftychck.PkltSftyChckCodeMapper.Result;

/** §4 코드 대응표 회귀. */
class PkltSftyChckCodeMapperTest {

    private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);

    @ParameterizedTest
    @ValueSource(strings = { "A", "B", "C", "D", " A ", "C\t" })
    @DisplayName("결과: A/B/C 는 trim 후 그대로")
    void rsltAllowed(String raw) {
        Result r = PkltSftyChckCodeMapper.mapRslt(raw);
        assertThat(r.mapped()).isTrue();
        assertThat(r.code()).isEqualTo(raw.trim());
    }

    @ParameterizedTest
    @ValueSource(strings = { "E", "a", "", "  ", "ETC", "양호" })
    @DisplayName("🔴 결과: 목록 밖 → RSLT_UNMAPPED (fallback·대소문자 보정 없음)")
    void rsltUnmapped(String raw) {
        Result r = PkltSftyChckCodeMapper.mapRslt(raw);
        assertThat(r.mapped()).isFalse();
        assertThat(r.failRsn()).isEqualTo(PkltSftyChckSkippableException.RSLT_UNMAPPED);
    }

    @Test
    @DisplayName("결과: null → RSLT_UNMAPPED")
    void rsltNull() {
        assertThat(PkltSftyChckCodeMapper.mapRslt(null).failRsn())
                .isEqualTo(PkltSftyChckSkippableException.RSLT_UNMAPPED);
    }

    @ParameterizedTest
    @ValueSource(strings = { "REGULAR", "OCCASION", "PRECISION", " PRECISION " })
    @DisplayName("유형: REGULAR/OCCASION/PRECISION 은 trim 후 그대로")
    void typeAllowed(String raw) {
        assertThat(PkltSftyChckCodeMapper.mapType(raw).code()).isEqualTo(raw.trim());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "regular", "SPECIAL", "정기" })
    @DisplayName("🔴 유형: 목록 밖·없음 → TYPE_UNMAPPED")
    void typeUnmapped(String raw) {
        assertThat(PkltSftyChckCodeMapper.mapType(raw).failRsn())
                .isEqualTo(PkltSftyChckSkippableException.TYPE_UNMAPPED);
    }

    @ParameterizedTest
    @ValueSource(strings = { "H1", "H2", " H2 " })
    @DisplayName("시기: H1/H2 는 trim 후 그대로 — 점검월과 어긋나도 원천 값을 따른다")
    void prdAllowed(String raw) {
        assertThat(PkltSftyChckCodeMapper.mapPrd(raw, MARCH).code()).isEqualTo(raw.trim());
    }

    @Test
    @DisplayName("시기 유도: 원천 값이 없으면 점검월로 — 6월=H1 · 7월=H2 · 1월=H1 · 12월=H2")
    void prdDerivedFromMonth() {
        assertThat(PkltSftyChckCodeMapper.mapPrd(null, LocalDate.of(2026, 6, 30)).code()).isEqualTo("H1");
        assertThat(PkltSftyChckCodeMapper.mapPrd(null, LocalDate.of(2026, 7, 1)).code()).isEqualTo("H2");
        assertThat(PkltSftyChckCodeMapper.mapPrd("", LocalDate.of(2026, 1, 1)).code()).isEqualTo("H1");
        assertThat(PkltSftyChckCodeMapper.mapPrd("  ", LocalDate.of(2026, 12, 31)).code()).isEqualTo("H2");
    }

    @ParameterizedTest
    @ValueSource(strings = { "H3", "h1", "상반기", "1" })
    @DisplayName("🔴 시기: 값이 있는데 목록 밖이면 유도하지 않고 PRD_UNMAPPED")
    void prdUnmappedIsNotDerived(String raw) {
        Result r = PkltSftyChckCodeMapper.mapPrd(raw, MARCH);
        assertThat(r.mapped()).isFalse();
        assertThat(r.failRsn()).isEqualTo(PkltSftyChckSkippableException.PRD_UNMAPPED);
    }
}
