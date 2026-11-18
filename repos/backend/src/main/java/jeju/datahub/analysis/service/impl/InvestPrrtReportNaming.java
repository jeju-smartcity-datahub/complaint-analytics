package jeju.datahub.analysis.service.impl;

/**
 * 투자우선순위 보고서 헤더 문자열(지역명·보고서명·분석기간설명) 조립 규칙 — 이 보고서에서 <b>세 값의 유일한 출처</b>.
 * (TSK-4036 Step 7)
 *
 * <p>집중구역({@link HotspotReportNaming})과 <b>보고서명 접두만 다르다.</b> 지역 3단 규칙과 기간 표기는
 * 같은 패키지의 그 클래스에 <b>위임</b>한다 — 지역명은 조회 SQL({@code ReportMapper.selectReportById} 의 CASE)과
 * 공백 위치까지 같아야 하는 이중 구현이라, 사본을 하나 더 만들면 맞춰야 할 곳이 셋이 된다.
 *
 * <table border="1">
 *   <caption>결과 예</caption>
 *   <tr><th>지역 조건</th><th>보고서명</th></tr>
 *   <tr><td>없음</td><td>{@code 투자 우선순위 보고서 전체 지역}</td></tr>
 *   <tr><td>시군구</td><td>{@code 투자 우선순위 보고서 제주시 전체}</td></tr>
 *   <tr><td>시군구 + 읍면동</td><td>{@code 투자 우선순위 보고서 제주시 연동}</td></tr>
 * </table>
 *
 * <p>기간은 이름에 넣지 않고 {@code anls_prd_expln}({@code 2026.07.13 ~ 2026.10.13}) 칸에 둔다 — 보고서 목록이
 * 이름과 기간을 서로 다른 칸으로 보여 주고, 집중구역·민원현황 보고서가 모두 그렇게 저장한다.
 */
public final class InvestPrrtReportNaming {

    /** 보고서명 접두. 뒤에 지역명이 공백 1칸으로 이어진다. */
    private static final String RPTP_NM_PREFIX = "투자 우선순위 보고서 ";

    private InvestPrrtReportNaming() {
    }

    /**
     * 지역명 3단 규칙 — {@code 전체 지역} / {@code {시군구} 전체} / {@code {시군구} {읍면동}}.
     *
     * @param sggNm 시군구명. 없으면 {@code null}(빈 문자열·공백도 없는 것으로 본다)
     * @param emdNm 읍면동<b>명</b>(코드가 아니다). 없으면 {@code null}
     * @return 지역명. 절대 {@code null} 이 아니다
     * @throws IllegalArgumentException {@code emdNm} 만 있고 {@code sggNm} 이 없을 때
     *         (조회 SQL 이 그 조합에서 지역명을 NULL 로 내 POST 와 GET 이 갈린다)
     */
    public static String regionNm(String sggNm, String emdNm) {
        return HotspotReportNaming.regionNm(sggNm, emdNm);
    }

    /**
     * 보고서명 = {@code 투자 우선순위 보고서 {regionNm}}.
     *
     * @param regionNm {@link #regionNm(String, String)} 의 결과. 비었으면 {@code 전체 지역}
     */
    public static String rptpNm(String regionNm) {
        String region = (regionNm == null || regionNm.isBlank()) ? HotspotReportNaming.ALL_REGION_NM : regionNm.trim();
        return RPTP_NM_PREFIX + region;
    }

    /**
     * 분석기간설명 = {@code yyyy.MM.dd ~ yyyy.MM.dd}. 시작=종료면 한 번만 적는다.
     *
     * @param bgngYmd 분석 시작일 {@code YYYYMMDD} — 🔴 요청값이 아니라 <b>실제 적용된 창</b>을 넘긴다
     * @param endYmd  분석 종료일 {@code YYYYMMDD} (당일 포함)
     * @throws IllegalArgumentException 값이 비었거나 실재하는 날짜가 아닐 때
     */
    public static String anlsPrdExpln(String bgngYmd, String endYmd) {
        return HotspotReportNaming.anlsPrdExpln(bgngYmd, endYmd);
    }
}
