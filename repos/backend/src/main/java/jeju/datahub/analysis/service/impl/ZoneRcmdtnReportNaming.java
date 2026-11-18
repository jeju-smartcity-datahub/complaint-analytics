package jeju.datahub.analysis.service.impl;

/**
 * 구역추천 보고서 헤더 문자열(지역명·보고서명·분석기간설명) 조립 규칙 — 이 보고서에서 <b>세 값의 유일한 출처</b>.
 * (TSK-4240 Step 2)
 *
 * <p>집중구역({@link HotspotReportNaming})과 <b>보고서명 접두만 다르다.</b> 지역 3단 규칙과 기간 표기는
 * 같은 패키지의 그 클래스에 <b>위임</b>한다({@link InvestPrrtReportNaming} 과 같은 방침) — 지역명은 조회 SQL
 * ({@code ReportMapper.selectReportById} 의 CASE)과 공백 위치까지 같아야 하는 이중 구현이라, 사본을 하나 더
 * 만들면 맞춰야 할 곳이 늘어난다. 🔴 생성 응답의 {@code regionNm} 과 스냅샷 머리의 지역명이 같은 문자열이어야
 * 내려받은 파일과 재다운로드 문서의 지역 표기가 같다.
 *
 * <p>🔴 <b>두 보기는 보고서명으로만 구분된다</b>(D1 — 분석유형코드가 같아 보고서 목록의 출처 칸은 둘 다
 * {@code 구역추천} 이다). 그래서 접두에 보기 이름을 넣는다.
 *
 * <table border="1">
 *   <caption>결과 예</caption>
 *   <tr><th>보기</th><th>지역 조건</th><th>보고서명</th></tr>
 *   <tr><td>요금제 적용</td><td>시군구 + 읍면동(항상)</td><td>{@code 구역추천 요금제 적용 보고서 제주시 이도이동}</td></tr>
 *   <tr><td>공유주차제 적용</td><td>시군구만</td><td>{@code 구역추천 공유주차제 적용 보고서 제주시 전체}</td></tr>
 *   <tr><td>공유주차제 적용</td><td>없음</td><td>{@code 구역추천 공유주차제 적용 보고서 전체 지역}</td></tr>
 * </table>
 *
 * <p>기간은 이름에 넣지 않고 {@code anls_prd_expln}({@code 2025.01.01 ~ 2025.08.31}) 칸에 둔다 — 보고서 목록이
 * 이름과 기간을 서로 다른 칸으로 보여 주고, 다른 보고서들도 그렇게 저장한다. {@code rptp_nm} 은 256자라
 * 접두 + 지역명에 여유가 있다.
 */
public final class ZoneRcmdtnReportNaming {

    /** 요금제 적용 보고서명 접두. 뒤에 지역명이 공백 1칸으로 이어진다. */
    private static final String FEE_RPTP_NM_PREFIX = "구역추천 요금제 적용 보고서 ";

    /** 공유주차제 적용 보고서명 접두. 뒤에 지역명이 공백 1칸으로 이어진다. */
    private static final String SHARE_RPTP_NM_PREFIX = "구역추천 공유주차제 적용 보고서 ";

    private ZoneRcmdtnReportNaming() {
    }

    /**
     * 지역명 3단 규칙 — {@code 전체 지역} / {@code {시군구} 전체} / {@code {시군구} {읍면동}}.
     *
     * @param sggNm 시군구명. 없으면 {@code null}(빈 문자열·공백도 없는 것으로 본다)
     * @param emdNm 읍면동<b>명</b>(코드가 아니다). 없으면 {@code null}
     * @return 지역명. 절대 {@code null} 이 아니다
     * @throws IllegalArgumentException {@code emdNm} 만 있고 {@code sggNm} 이 없을 때
     *         (조회 SQL 이 그 조합에서 지역명을 NULL 로 내 생성 응답과 스냅샷이 갈린다)
     */
    public static String regionNm(String sggNm, String emdNm) {
        return HotspotReportNaming.regionNm(sggNm, emdNm);
    }

    /**
     * 요금제 적용 보고서명 = {@code 구역추천 요금제 적용 보고서 {regionNm}}.
     *
     * @param regionNm {@link #regionNm(String, String)} 의 결과. 비었으면 {@code 전체 지역}
     */
    public static String feeRptpNm(String regionNm) {
        return FEE_RPTP_NM_PREFIX + regionOrAll(regionNm);
    }

    /**
     * 공유주차제 적용 보고서명 = {@code 구역추천 공유주차제 적용 보고서 {regionNm}}. (TSK-4240 Step 3)
     *
     * @param regionNm {@link #regionNm(String, String)} 의 결과({@code {시군구} 전체} 또는 {@code 전체 지역} —
     *                 이 보기는 읍면동 조건이 없다). 비었으면 {@code 전체 지역}
     */
    public static String shareRptpNm(String regionNm) {
        return SHARE_RPTP_NM_PREFIX + regionOrAll(regionNm);
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

    private static String regionOrAll(String regionNm) {
        return (regionNm == null || regionNm.isBlank()) ? HotspotReportNaming.ALL_REGION_NM : regionNm.trim();
    }
}
