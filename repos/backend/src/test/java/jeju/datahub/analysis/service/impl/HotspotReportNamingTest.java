package jeju.datahub.analysis.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HotspotReportNaming} 단위 테스트 (검증 4케이스).
 *
 * <p><b>스프링 컨텍스트도 DB 도 띄우지 않는다</b> — 검증 대상이 문자열 조립 규칙뿐이라
 * {@code static} 호출만으로 충분하다. 같은 패키지의 {@code ZoneNameResolverTest} 와 같은 성격이다.
 *
 * <p>🔴 마지막 케이스({@link #caseSqlCaseStaysInSync()})는 <b>이 클래스와 SQL 의 이중 구현</b>을 묶는다 —
 * GET 경로는 {@code ReportMapper.selectReportById} 의 CASE 가 {@code region_nm} 을 조립하므로
 * 두 곳이 갈리면 "만든 직후"와 "다시 연" 보고서의 지역명이 달라진다. 컴파일도 다른 테스트도 잡지
 * 못하는 종류의 어긋남이라, 매퍼 XML 의 리터럴(공백 위치 포함)을 직접 읽어 못박는다.
 */
class HotspotReportNamingTest {

    @Test
    @DisplayName("1. 지역 조건이 없으면 '전체 지역'")
    void case01_allRegion() {
        assertThat(HotspotReportNaming.regionNm(null, null)).isEqualTo("전체 지역");
        assertThat(HotspotReportNaming.rptpNm(HotspotReportNaming.regionNm(null, null)))
                .isEqualTo("집중 구역 분석 보고서 전체 지역");

        // 빈 문자열·공백만 있는 값도 "없음"으로 모은다 — 키를 비워 보낸 요청이 "null 전체"가 되지 않게.
        assertThat(HotspotReportNaming.regionNm("", "   ")).isEqualTo("전체 지역");
    }

    @Test
    @DisplayName("2. 시군구만 있으면 '{시군구} 전체' (구분자 공백 1칸)")
    void case02_sggOnly() {
        assertThat(HotspotReportNaming.regionNm("제주시", null)).isEqualTo("제주시 전체");
        assertThat(HotspotReportNaming.rptpNm(HotspotReportNaming.regionNm("제주시", null)))
                .isEqualTo("집중 구역 분석 보고서 제주시 전체");
        assertThat(HotspotReportNaming.regionNm("서귀포시", "")).isEqualTo("서귀포시 전체");
    }

    @Test
    @DisplayName("3. 읍면동까지 있으면 '{시군구} {읍면동}' — 입력은 emdCd 가 아니라 emdNm 이다")
    void case03_sggAndEmd() {
        assertThat(HotspotReportNaming.regionNm("제주시", "연동")).isEqualTo("제주시 연동");
        assertThat(HotspotReportNaming.rptpNm(HotspotReportNaming.regionNm("제주시", "연동")))
                .isEqualTo("집중 구역 분석 보고서 제주시 연동");
    }

    @Test
    @DisplayName("3-1. emdNm 만 있고 sggNm 이 없으면 폴백하지 않고 예외 — POST/GET 조용한 불일치 차단")
    void case03b_emdWithoutSgg() {
        // SQL 은 이 조합에서 region_nm 을 NULL 로 낸다. 여기서 "전체 지역" 으로 흘리면
        // 같은 보고서의 생성 응답과 재조회 응답이 갈린다 — 그래서 끊는다.
        assertThatThrownBy(() -> HotspotReportNaming.regionNm(null, "연동"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sggNm");
        assertThatThrownBy(() -> HotspotReportNaming.regionNm("  ", "연동"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("4. 기간 표기 — 시작=종료면 물결표 없이 한 번만")
    void case04_periodExpression() {
        // (1) 일반 기간
        assertThat(HotspotReportNaming.anlsPrdExpln("20251201", "20251231"))
                .isEqualTo("2025.12.01 ~ 2025.12.31");

        // (2) 동일 일자 — '2025.12.01 ~ 2025.12.01' 은 화면에서 오기로 읽힌다
        assertThat(HotspotReportNaming.anlsPrdExpln("20251201", "20251201"))
                .isEqualTo("2025.12.01")
                .doesNotContain("~");

        // (3) 해를 넘기는 기간도 자릿수가 흔들리지 않는다 (한 자리 월·일의 0 패딩)
        assertThat(HotspotReportNaming.anlsPrdExpln("20251231", "20260101"))
                .isEqualTo("2025.12.31 ~ 2026.01.01");

        // (4) 형식 오류·실재하지 않는 날짜는 조용히 통과시키지 않는다 (STRICT 파싱)
        assertThatThrownBy(() -> HotspotReportNaming.anlsPrdExpln("20251232", "20251232"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HotspotReportNaming.anlsPrdExpln(null, "20251231"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bgngYmd");
    }

    @Test
    @DisplayName("5. ReportMapper.selectReportById 의 CASE 와 같은 리터럴을 쓴다 (GET/POST 지역명 동기화)")
    void caseSqlCaseStaysInSync() throws IOException {
        String sql = normalizeWhitespace(readMapperXml());

        // 3단 CASE 의 각 분기 — 연결 리터럴의 '공백 위치'가 곧 이 클래스의 구분자다.
        assertThat(sql).contains("WHEN a.emd_nm IS NOT NULL THEN e.sgg_nm || ' ' || a.emd_nm");
        assertThat(sql).contains("WHEN e.sgg_nm IS NOT NULL THEN e.sgg_nm || ' 전체'");
        assertThat(sql).contains("WHEN r.excn_id IS NOT NULL THEN '전체 지역'");

        // 같은 입력에 대해 Java 가 만드는 문자열이 위 SQL 의 연결 결과와 같은지 직접 맞춰 본다.
        assertThat(HotspotReportNaming.regionNm("제주시", "연동")).isEqualTo("제주시" + " " + "연동");
        assertThat(HotspotReportNaming.regionNm("제주시", null)).isEqualTo("제주시" + " 전체");
        assertThat(HotspotReportNaming.regionNm(null, null)).isEqualTo("전체 지역");
    }

    private static String readMapperXml() throws IOException {
        try (InputStream in = HotspotReportNamingTest.class.getResourceAsStream("/mapper/report/ReportMapper.xml")) {
            assertThat(in).as("mapper/report/ReportMapper.xml 이 테스트 클래스패스에 있어야 한다").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** XML 들여쓰기·정렬용 연속 공백을 1칸으로 눌러 비교한다 (정렬이 바뀌어도 규칙 자체는 붙잡게). */
    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ");
    }
}
