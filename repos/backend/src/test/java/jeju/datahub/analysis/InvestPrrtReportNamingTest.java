package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.analysis.service.impl.HotspotReportNaming;
import jeju.datahub.analysis.service.impl.InvestPrrtReportNaming;

/**
 * {@link InvestPrrtReportNaming} 단위 테스트 (TSK-4036 Step 7).
 *
 * <p>스프링 컨텍스트도 DB 도 띄우지 않는다 — 검증 대상이 문자열 조립 규칙뿐이다
 * ({@code HotspotReportNamingTest} 와 같은 성격). 패키지를 {@code jeju.datahub.analysis} 에 둔 것은
 * 계획서의 검증 명령({@code --tests 'jeju.datahub.analysis.InvestPrrtReport*'})이 이 테스트까지 돌리게 하려는 것이다.
 *
 * <p>지역 3단 규칙과 기간 표기는 {@link HotspotReportNaming} 에 위임하므로, 여기서는 <b>접두가 이 보고서의
 * 것인지</b>와 <b>위임이 실제로 같은 문자열을 내는지</b>를 본다(SQL CASE 와의 일치는 그쪽 테스트가 못박는다).
 */
class InvestPrrtReportNamingTest {

    @Test
    @DisplayName("1. 지역 조건이 없으면 '투자 우선순위 보고서 전체 지역'")
    void allRegion() {
        String region = InvestPrrtReportNaming.regionNm(null, null);
        assertThat(region).isEqualTo("전체 지역");
        assertThat(InvestPrrtReportNaming.rptpNm(region)).isEqualTo("투자 우선순위 보고서 전체 지역");
        // 빈 문자열·공백만 있는 값도 "없음" 이다 — 화면이 키를 비워 보내도 "null 전체" 가 되지 않는다
        assertThat(InvestPrrtReportNaming.regionNm("", "   ")).isEqualTo("전체 지역");
    }

    @Test
    @DisplayName("2. 시군구만 있으면 '{시군구} 전체'")
    void sggOnly() {
        String region = InvestPrrtReportNaming.regionNm("서귀포시", null);
        assertThat(region).isEqualTo("서귀포시 전체");
        assertThat(InvestPrrtReportNaming.rptpNm(region)).isEqualTo("투자 우선순위 보고서 서귀포시 전체");
    }

    @Test
    @DisplayName("3. 읍면동까지 있으면 '{시군구} {읍면동}' — 입력은 emdCd 가 아니라 emdNm 이다")
    void sggAndEmd() {
        String region = InvestPrrtReportNaming.regionNm("제주시", "연동");
        assertThat(region).isEqualTo("제주시 연동");
        assertThat(InvestPrrtReportNaming.rptpNm(region)).isEqualTo("투자 우선순위 보고서 제주시 연동");
    }

    @Test
    @DisplayName("4. emdNm 만 있고 sggNm 이 없으면 폴백하지 않고 예외 — POST/GET 지역명이 조용히 갈리는 것을 막는다")
    void emdWithoutSgg() {
        assertThatThrownBy(() -> InvestPrrtReportNaming.regionNm(null, "연동"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("5. 보고서명에 넘긴 지역명이 비었으면 '전체 지역' 으로 채운다 (이름이 접두로 끝나지 않는다)")
    void blankRegionFallsBack() {
        assertThat(InvestPrrtReportNaming.rptpNm(null)).isEqualTo("투자 우선순위 보고서 전체 지역");
        assertThat(InvestPrrtReportNaming.rptpNm("  ")).isEqualTo("투자 우선순위 보고서 전체 지역");
        assertThat(InvestPrrtReportNaming.rptpNm(" 제주시 전체 ")).isEqualTo("투자 우선순위 보고서 제주시 전체");
    }

    @Test
    @DisplayName("6. 기간 표기 — 'yyyy.MM.dd ~ yyyy.MM.dd', 하루짜리는 한 번만, 형식이 틀리면 예외")
    void period() {
        assertThat(InvestPrrtReportNaming.anlsPrdExpln("20260713", "20261013")).isEqualTo("2026.07.13 ~ 2026.10.13");
        assertThat(InvestPrrtReportNaming.anlsPrdExpln("20261013", "20261013")).isEqualTo("2026.10.13");
        assertThatThrownBy(() -> InvestPrrtReportNaming.anlsPrdExpln(null, "20261013"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvestPrrtReportNaming.anlsPrdExpln("20260230", "20261013"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("7. 지역명·기간은 집중구역 규칙과 같은 문자열이다 — 한 목록에 섞이는 보고서의 표기가 유형마다 갈리지 않는다")
    void sameAsHotspotRules() {
        assertThat(InvestPrrtReportNaming.regionNm("제주시", "연동")).isEqualTo(HotspotReportNaming.regionNm("제주시", "연동"));
        assertThat(InvestPrrtReportNaming.regionNm("제주시", null)).isEqualTo(HotspotReportNaming.regionNm("제주시", null));
        assertThat(InvestPrrtReportNaming.regionNm(null, null)).isEqualTo(HotspotReportNaming.ALL_REGION_NM);
        assertThat(InvestPrrtReportNaming.anlsPrdExpln("20260101", "20260331"))
                .isEqualTo(HotspotReportNaming.anlsPrdExpln("20260101", "20260331"));
    }
}
