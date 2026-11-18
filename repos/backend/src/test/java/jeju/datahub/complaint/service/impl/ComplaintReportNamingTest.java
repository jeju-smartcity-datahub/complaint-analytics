package jeju.datahub.complaint.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ComplaintReportNaming} 단위 테스트 (TSK-4164 Step 2 검증).
 *
 * <p><b>스프링 컨텍스트도 DB 도 띄우지 않는다</b> — 검증 대상이 문자열 조립 규칙뿐이라
 * {@code static} 호출만으로 충분하다({@code HotspotReportNamingTest} 와 같은 성격).
 *
 * <p>여기서 못박는 것은 D8 의 세 줄이다 — 지역 3형태 · 보고서명 접두({@code 민원 현황 보고서 }) ·
 * 기간 표기({@code yyyy.MM.dd ~ yyyy.MM.dd}, 시작=종료면 한 번만).
 */
class ComplaintReportNamingTest {

    @Test
    @DisplayName("1. 지역 조건이 없으면 '전체 지역'")
    void case01_allRegion() {
        assertThat(ComplaintReportNaming.regionNm(null, null)).isEqualTo("전체 지역");

        // 빈 문자열·공백만 있는 값도 "없음"으로 모은다 — 키를 비워 보낸 요청이 "null 전체"가 되지 않게.
        assertThat(ComplaintReportNaming.regionNm("", "   ")).isEqualTo("전체 지역");
    }

    @Test
    @DisplayName("2. 시군구만 있으면 '{시군구} 전체' (구분자 공백 1칸)")
    void case02_sggOnly() {
        assertThat(ComplaintReportNaming.regionNm("제주시", null)).isEqualTo("제주시 전체");
        assertThat(ComplaintReportNaming.regionNm("서귀포시", "")).isEqualTo("서귀포시 전체");
    }

    @Test
    @DisplayName("3. 읍면동까지 있으면 '{시군구} {읍면동}' — 입력은 emdCd 가 아니라 emdNm 이다")
    void case03_sggAndEmd() {
        assertThat(ComplaintReportNaming.regionNm("제주시", "연동")).isEqualTo("제주시 연동");
        assertThat(ComplaintReportNaming.regionNm("서귀포시", "서호동")).isEqualTo("서귀포시 서호동");
    }

    @Test
    @DisplayName("3-1. emdNm 만 있고 sggNm 이 없으면 폴백하지 않고 예외 — POST/GET 조용한 불일치 차단")
    void case03b_emdWithoutSgg() {
        // SQL CASE 는 이 조합에서 region_nm 을 NULL 로 낸다. 여기서 "전체 지역" 으로 흘리면
        // 같은 보고서의 생성 응답과 재조회 응답이 갈린다 — 그래서 끊는다.
        assertThatThrownBy(() -> ComplaintReportNaming.regionNm(null, "연동"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sggNm");
        assertThatThrownBy(() -> ComplaintReportNaming.regionNm("  ", "연동"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("4. 보고서명 = '민원 현황 보고서 {지역표시명}' — 접두가 집중구역과 다르다")
    void case04_rptpNm() {
        assertThat(ComplaintReportNaming.rptpNm(ComplaintReportNaming.regionNm(null, null)))
                .isEqualTo("민원 현황 보고서 전체 지역");
        assertThat(ComplaintReportNaming.rptpNm(ComplaintReportNaming.regionNm("제주시", null)))
                .isEqualTo("민원 현황 보고서 제주시 전체");
        assertThat(ComplaintReportNaming.rptpNm(ComplaintReportNaming.regionNm("제주시", "연동")))
                .isEqualTo("민원 현황 보고서 제주시 연동");

        // 지역명이 비어 들어와도 "민원 현황 보고서 null" 이 되지 않는다.
        assertThat(ComplaintReportNaming.rptpNm(null)).isEqualTo("민원 현황 보고서 전체 지역");
        assertThat(ComplaintReportNaming.rptpNm("   ")).isEqualTo("민원 현황 보고서 전체 지역");
    }

    @Test
    @DisplayName("5. 기간 표기 — 시작=종료면 물결표 없이 한 번만")
    void case05_periodExpression() {
        // (1) 일반 기간 (D8 의 예시 그대로)
        assertThat(ComplaintReportNaming.anlsPrdExpln("20260901", "20260917"))
                .isEqualTo("2026.09.01 ~ 2026.09.17");

        // (2) 동일 일자 — '2026.09.01 ~ 2026.09.01' 은 화면에서 오기로 읽힌다
        assertThat(ComplaintReportNaming.anlsPrdExpln("20260901", "20260901"))
                .isEqualTo("2026.09.01")
                .doesNotContain("~");

        // (3) 해를 넘기는 기간도 자릿수가 흔들리지 않는다 (한 자리 월·일의 0 패딩)
        assertThat(ComplaintReportNaming.anlsPrdExpln("20251231", "20260101"))
                .isEqualTo("2025.12.31 ~ 2026.01.01");

        // (4) 형식 오류·실재하지 않는 날짜는 조용히 통과시키지 않는다 (STRICT 파싱)
        assertThatThrownBy(() -> ComplaintReportNaming.anlsPrdExpln("20260931", "20260931"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ComplaintReportNaming.anlsPrdExpln(null, "20260917"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bgngYmd");
    }

    /**
     * 🔴 <b>이 클래스와 SQL 의 이중 구현</b>을 묶는다 (Step 4 — 스냅샷 GET 이 생기면서 발동한다).
     *
     * <p>생성(POST)은 {@link ComplaintReportNaming#regionNm} 이, 스냅샷 조회(GET)는
     * {@code ReportMapper.selectReportById} 의 3단 CASE 가 지역명을 만든다. 두 곳이 갈리면
     * "만든 직후" 와 "다시 연" 보고서의 제목·지역명이 달라지는데, <b>컴파일도 다른 테스트도 잡지
     * 못한다</b> — 그래서 매퍼 XML 의 리터럴(공백 위치 포함)을 직접 읽어 못박는다.
     * ({@code HotspotReportNamingTest.caseSqlCaseStaysInSync()} 와 같은 방식 · 같은 CASE 를 본다 —
     * 민원현황 스냅샷도 헤더 5칸을 {@code selectReportById} 에서 그대로 받기 때문이다.)
     */
    @Test
    @DisplayName("6. SQL 3단 CASE 와 같은 문자열을 낸다 — 생성/조회 지역명 불일치 감시")
    void caseSqlCaseStaysInSync() throws IOException {
        String sql = normalizeWhitespace(readReportMapperXml());

        // 3단 CASE 의 각 분기 — 연결 리터럴의 '공백 위치'가 곧 이 클래스의 구분자다.
        assertThat(sql).contains("WHEN a.emd_nm IS NOT NULL THEN e.sgg_nm || ' ' || a.emd_nm");
        assertThat(sql).contains("WHEN e.sgg_nm IS NOT NULL THEN e.sgg_nm || ' 전체'");
        assertThat(sql).contains("WHEN r.excn_id IS NOT NULL THEN '전체 지역'");

        // 같은 입력에 대해 Java 가 만드는 문자열이 위 SQL 의 연결 결과와 같은지 직접 맞춰 본다.
        assertThat(ComplaintReportNaming.regionNm("제주시", "연동")).isEqualTo("제주시" + " " + "연동");
        assertThat(ComplaintReportNaming.regionNm("제주시", null)).isEqualTo("제주시" + " 전체");
        assertThat(ComplaintReportNaming.regionNm(null, null)).isEqualTo("전체 지역");
    }

    /**
     * 🔴 민원현황 스냅샷 statement 는 <b>지역명을 만들지 않는다</b> — 헤더 5칸은
     * {@code selectReportById} 를 재사용한다. CASE 를 여기 복사하면 같은 규칙이 세 곳에 살게 되므로,
     * 복사가 들어오는 순간 이 테스트가 깨지게 둔다.
     */
    @Test
    @DisplayName("6-1. 민원 매퍼는 지역 3단 CASE 를 복제하지 않는다 (헤더는 selectReportById 재사용)")
    void complaintMapperDoesNotCopyRegionCase() throws IOException {
        String sql = normalizeWhitespace(readComplaintReportMapperXml());

        assertThat(sql).doesNotContain("|| ' 전체'");
        assertThat(sql).doesNotContain("AS region_nm");

        // 스냅샷 statement 는 있어야 한다 — 매퍼 인터페이스와 id 가 어긋나면 호출 시점에야 터진다.
        assertThat(sql).contains("<select id=\"selectComplaintSnapshot\"");
        // 🔴 두 jsonb 칸은 ::text 캐스팅이 필수다 (그대로 매핑하면 PGobject 가 String 에 붙지 않는다).
        assertThat(sql).contains("e.anls_param_json::text AS anls_param_json");
        assertThat(sql).contains("r.rslt_json::text AS rslt_json");
    }

    private static String readReportMapperXml() throws IOException {
        return readClasspath("/mapper/report/ReportMapper.xml");
    }

    private static String readComplaintReportMapperXml() throws IOException {
        return readClasspath("/mapper/complaint/ComplaintReportMapper.xml");
    }

    private static String readClasspath(String path) throws IOException {
        try (InputStream in = ComplaintReportNamingTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path + " 이 테스트 클래스패스에 있어야 한다").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** XML 들여쓰기·정렬용 연속 공백을 1칸으로 눌러 비교한다 (정렬이 바뀌어도 규칙 자체는 붙잡게). */
    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ");
    }
}
