package jeju.datahub.lifezone.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LifezoneReportNaming} 단위 테스트 (TSK-4088 Step 11).
 *
 * <p><b>스프링 컨텍스트도 DB 도 띄우지 않는다</b> — 검증 대상이 문자열 조립 규칙뿐이라
 * {@code static} 호출만으로 충분하다.
 *
 * <p>🔴 마지막 케이스({@link #caseSqlCaseStaysInSync()})는 <b>이 클래스와 SQL 의 이중 구현</b>을 묶는다 —
 * 스냅샷 조회(GET)는 {@code ReportMapper.selectReportById} 의 CASE 가 {@code region_nm} 을 조립하므로
 * 두 곳이 갈리면 "만든 직후"와 "다시 연" 보고서의 지역명이 달라진다. 컴파일도 다른 테스트도 잡지
 * 못하는 종류의 어긋남이라, 매퍼 XML 의 리터럴(공백 위치 포함)을 직접 읽어 못박는다
 * ({@code HotspotReportNamingTest} 가 확립한 방식).
 */
class LifezoneReportNamingTest {

    @Test
    @DisplayName("1. 지역 조건이 없으면 '전체 지역'")
    void case01_allRegion() {
        assertThat(LifezoneReportNaming.regionNm(null, null)).isEqualTo("전체 지역");
        // 빈 문자열·공백만 있는 값도 "없음"으로 모은다 — 키를 비워 보낸 요청이 "null 전체"가 되지 않게.
        assertThat(LifezoneReportNaming.regionNm("", "   ")).isEqualTo("전체 지역");
    }

    @Test
    @DisplayName("2. 시군구만 있으면 '{시군구} 전체' · 읍면동까지 있으면 '{시군구} {읍면동}'")
    void case02_regionThreeForms() {
        assertThat(LifezoneReportNaming.regionNm("제주시", null)).isEqualTo("제주시 전체");
        assertThat(LifezoneReportNaming.regionNm("서귀포시", "")).isEqualTo("서귀포시 전체");
        assertThat(LifezoneReportNaming.regionNm("제주시", "연동")).isEqualTo("제주시 연동");
    }

    @Test
    @DisplayName("2-1. emdNm 만 있고 sggNm 이 없으면 폴백하지 않고 예외 — POST/GET 조용한 불일치 차단")
    void case02b_emdWithoutSgg() {
        // SQL 은 이 조합에서 region_nm 을 NULL 로 낸다. 여기서 "전체 지역" 으로 흘리면
        // 같은 보고서의 생성 응답과 재조회 응답이 갈린다 — 그래서 끊는다.
        assertThatThrownBy(() -> LifezoneReportNaming.regionNm(null, "연동"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sggNm");
    }

    @Test
    @DisplayName("3. 보고서명 = '생활권 {분석유형명} 보고서 {지역표시명} {격자}' (D14)")
    void case03_rptpNm() {
        assertThat(LifezoneReportNaming.rptpNm("민원건수", "전체 지역", "500m"))
                .isEqualTo("생활권 민원건수 보고서 전체 지역 500m");
        assertThat(LifezoneReportNaming.rptpNm("민원건수", "제주시 연동", "100m"))
                .isEqualTo("생활권 민원건수 보고서 제주시 연동 100m");

        // 🔴 격자가 다르면 이름도 달라야 한다 — 같은 지역·기간이라도 전혀 다른 결과다
        assertThat(LifezoneReportNaming.rptpNm("민원건수", "전체 지역", "1km"))
                .isNotEqualTo(LifezoneReportNaming.rptpNm("민원건수", "전체 지역", "500m"));

        // 필수 입력이 비면 'null' 이 이름에 박히기 전에 끊는다
        assertThatThrownBy(() -> LifezoneReportNaming.rptpNm(null, "전체 지역", "500m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LifezoneReportNaming.rptpNm("민원건수", "전체 지역", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("4. 기간 표기 — 시작=종료면 물결표 없이 한 번만")
    void case04_periodExpression() {
        assertThat(LifezoneReportNaming.anlsPrdExpln("20260704", "20260831"))
                .isEqualTo("2026.07.04 ~ 2026.08.31");
        assertThat(LifezoneReportNaming.anlsPrdExpln("20260704", "20260704"))
                .isEqualTo("2026.07.04")
                .doesNotContain("~");
        // 형식 오류·실재하지 않는 날짜는 조용히 통과시키지 않는다 (STRICT 파싱)
        assertThatThrownBy(() -> LifezoneReportNaming.anlsPrdExpln("20251232", "20251232"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LifezoneReportNaming.anlsPrdExpln(null, "20251231"))
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
        assertThat(LifezoneReportNaming.regionNm("제주시", "연동")).isEqualTo("제주시" + " " + "연동");
        assertThat(LifezoneReportNaming.regionNm("제주시", null)).isEqualTo("제주시" + " 전체");
        assertThat(LifezoneReportNaming.regionNm(null, null)).isEqualTo("전체 지역");

        // 스냅샷 조회 statement 가 인터페이스와 짝으로 존재하는지 — XML 만 고치면 호출부가 죽는다.
        assertThat(sql).contains("<select id=\"selectLifezoneSnapshot\" resultType=\"LifezoneSnapshotVO\">");
        // jsonb 는 ::text 로 읽어야 PGobject 가 오지 않는다 (매퍼 XML 선례)
        assertThat(sql).contains("e.anls_param_json::text");
        assertThat(sql).contains("r.rslt_json::text");
    }

    private static String readMapperXml() throws IOException {
        try (InputStream in = LifezoneReportNamingTest.class.getResourceAsStream("/mapper/report/ReportMapper.xml")) {
            assertThat(in).as("mapper/report/ReportMapper.xml 이 테스트 클래스패스에 있어야 한다").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** XML 들여쓰기·정렬용 연속 공백을 1칸으로 눌러 비교한다 (정렬이 바뀌어도 규칙 자체는 붙잡게). */
    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ");
    }
}
