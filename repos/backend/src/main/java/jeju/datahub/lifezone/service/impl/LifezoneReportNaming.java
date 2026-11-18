package jeju.datahub.lifezone.service.impl;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/**
 * 생활권 보고서 헤더 문자열(지역명·보고서명·분석기간설명) 조립 규칙 — <b>이 세 값의 유일한 출처</b>.
 * (TSK-4088 Step 11 · D14)
 *
 * <p>규칙을 한 곳에 모으는 이유는 <b>생성(POST)과 목록·스냅샷 조회(GET)가 같은 문자열을 봐야</b>
 * 하기 때문이다. 같은 보고서인데 만든 직후 화면과 다시 연 화면의 제목이 다르면, 그 차이는
 * 컴파일도 테스트도 잡지 못한다.
 *
 * <h2>🔴 SQL 과의 이중 구현 — 반드시 같은 문자열을 내야 한다</h2>
 *
 * <p>GET 경로는 지역명에 이 클래스를 부르지 않는다. {@code mapper/report/ReportMapper.xml} 의
 * {@code selectReportById} 가 조인 결과에서 CASE 로 {@code region_nm} 을 이미 조립하기 때문이다
 * (헤더를 두 번 조립하지 않는다):
 *
 * <pre>{@code
 * CASE WHEN a.emd_nm  IS NOT NULL THEN e.sgg_nm || ' ' || a.emd_nm
 *      WHEN e.sgg_nm  IS NOT NULL THEN e.sgg_nm || ' 전체'
 *      WHEN r.excn_id IS NOT NULL THEN '전체 지역'
 *      ELSE NULL END AS region_nm
 * }</pre>
 *
 * <p>아래 {@link #regionNm(String, String)} 은 이 CASE 의 <b>공백 위치까지 같은</b> 재현이며,
 * {@code LifezoneReportNamingTest} 가 매퍼 XML 을 직접 읽어 기계 대조한다(TSK-4078 선례).
 *
 * <p>⚠️ 한 자리만 SQL 로 재현할 수 없다 — <b>{@code emdNm} 이 있는데 {@code sggNm} 이 없는</b> 경우다.
 * SQL 은 첫 분기를 타고 {@code NULL || ' ' || '연동'} 이 되어 {@code region_nm} 이 통째로 NULL 이 된다.
 * 여기서 {@code "전체 지역"} 으로 폴백하면 <b>같은 보고서의 POST 응답과 GET 응답이 조용히 갈리므로</b>
 * 폴백하지 않고 {@link IllegalArgumentException} 으로 끊는다. 이 조합은 애초에 도달하지 않는 것이
 * 전제다 — {@code emdCd} 만 온 요청에 분석 서비스가 {@code sggNm=null} 을 주므로, 저장 서비스가
 * {@code TB_ADMDST} 에서 {@code emdNm} 과 {@code sggNm} 을 <b>함께</b> 읽어 채운 뒤 저장한다.
 *
 * <p>인스턴스 상태가 없어 {@code static} 유틸로 둔다({@code HotspotReportNaming} 과 같은 형태).
 */
public final class LifezoneReportNaming {

    /** 지역 조건이 아예 없을 때의 지역명. SQL CASE 의 {@code '전체 지역'} 과 같은 리터럴이다. */
    public static final String ALL_REGION_NM = "전체 지역";

    /** 시군구만 있을 때 붙는 꼬리. SQL CASE 의 {@code || ' 전체'} 와 같다(앞 공백 1칸 포함). */
    private static final String SGG_ALL_SUFFIX = " 전체";

    /** 보고서명 접두. 뒤에 분석유형명이 이어진다 — {@code 생활권 민원건수 보고서 …} */
    private static final String RPTP_NM_PREFIX = "생활권 ";

    /** 보고서명 중간 토막. 분석유형명과 지역표시명 사이다. */
    private static final String RPTP_NM_MIDDLE = " 보고서 ";

    /** 기간 표기의 구분자. 앞뒤 공백 1칸씩이다 — {@code 2026.07.04 ~ 2026.08.31} */
    private static final String PERIOD_DELIMITER = " ~ ";

    /**
     * 입력 형식 {@code YYYYMMDD}. STRICT 라 {@code 20251232} 같은 실재하지 않는 날짜는 파싱에서 걸린다
     * ({@code LifezoneAnalysisServiceImpl.YMD} 와 같은 설정 — 두 곳의 허용 범위가 갈리지 않게).
     */
    private static final DateTimeFormatter YMD =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /** 출력 형식 {@code yyyy.MM.dd}. 화면·문서에 그대로 나가는 표기다. */
    private static final DateTimeFormatter DOTTED_YMD = DateTimeFormatter.ofPattern("uuuu.MM.dd");

    private LifezoneReportNaming() {
    }

    /**
     * 지역명 3단 규칙. 🔴 <b>입력은 {@code emdCd} 가 아니라 {@code emdNm}</b> 이다 —
     * {@code TB_ANLS_EXCN} 은 {@code sgg_nm}·{@code emd_cd} 만 갖고 읍면동<b>명</b>이 없어,
     * 호출부가 {@code TB_ADMDST} 에서 {@code emd_cd → emd_nm} 을 읽어 넘겨야 한다.
     *
     * @param sggNm 시군구명. 없으면 {@code null}(빈 문자열·공백도 없는 것으로 본다)
     * @param emdNm 읍면동명. 없으면 {@code null}
     * @return 지역명. <b>절대 {@code null} 이 아니다</b> — 보고서명이 {@code "... null"} 이 되는 것을 막는다
     * @throws IllegalArgumentException {@code emdNm} 만 있고 {@code sggNm} 이 없을 때 (위 ⚠️ 참고)
     */
    public static String regionNm(String sggNm, String emdNm) {
        String sgg = trimToNull(sggNm);
        String emd = trimToNull(emdNm);
        if (emd != null && sgg == null) {
            throw new IllegalArgumentException(
                    "emdNm 이 있으면 sggNm 도 있어야 합니다 — TB_ADMDST 에서 emd_nm 과 sgg_nm 을 함께"
                            + " 읽어 넘길 것 (emdNm=" + emdNm + ")");
        }
        if (emd != null) {
            return sgg + " " + emd;
        }
        if (sgg != null) {
            return sgg + SGG_ALL_SUFFIX;
        }
        return ALL_REGION_NM;
    }

    /**
     * 보고서명 = {@code 생활권 {분석유형명} 보고서 {지역표시명} {격자표시명}} (D14).
     *
     * <p>예: {@code 생활권 민원건수 보고서 전체 지역 500m} · {@code 생활권 민원건수 보고서 제주시 연동 100m}
     *
     * <p>🔴 <b>격자표시명이 이름에 들어간다.</b> 같은 지역·같은 기간이라도 격자가 다르면 전혀 다른
     * 결과이므로, 빼면 목록에서 구분이 되지 않는다. 값은 분석 응답의 {@code gridSzNm} 을 그대로 쓴다
     * ({@code 1000} 은 {@code 1km} 다 — 여기서 다시 만들지 않는다).
     *
     * @param anlsTrgtNm 분석유형명 ({@code LifezoneTarget#getAnlsTrgtNm()}) — 예 {@code 민원건수}
     * @param regionNm   {@link #regionNm(String, String)} 의 결과
     * @param gridSzNm   격자크기 표시명 (분석 응답의 {@code gridSzNm}) — 예 {@code 500m}
     */
    public static String rptpNm(String anlsTrgtNm, String regionNm, String gridSzNm) {
        String target = trimToNull(anlsTrgtNm);
        String region = trimToNull(regionNm);
        String grid = trimToNull(gridSzNm);
        if (target == null) {
            throw new IllegalArgumentException("anlsTrgtNm 은 필수입니다 (보고서명 조립)");
        }
        if (grid == null) {
            throw new IllegalArgumentException("gridSzNm 은 필수입니다 (보고서명 조립)");
        }
        return RPTP_NM_PREFIX + target + RPTP_NM_MIDDLE
                + (region != null ? region : ALL_REGION_NM) + " " + grid;
    }

    /**
     * 분석기간설명 = {@code yyyy.MM.dd ~ yyyy.MM.dd}. <b>시작=종료면 {@code yyyy.MM.dd} 한 번만</b> 적는다
     * — 하루짜리 분석에 {@code 2026.07.04 ~ 2026.07.04} 가 찍히면 화면에서 오기(誤記)로 읽힌다.
     *
     * @param bgngYmd 분석 시작일 {@code YYYYMMDD}
     * @param endYmd  분석 종료일 {@code YYYYMMDD} (당일 포함)
     * @throws IllegalArgumentException 값이 비었거나 {@code YYYYMMDD} 형식/실재하는 날짜가 아닐 때
     */
    public static String anlsPrdExpln(String bgngYmd, String endYmd) {
        LocalDate bgng = parseYmd(bgngYmd, "bgngYmd");
        LocalDate end = parseYmd(endYmd, "endYmd");
        String bgngText = bgng.format(DOTTED_YMD);
        if (bgng.equals(end)) {
            return bgngText;
        }
        return bgngText + PERIOD_DELIMITER + end.format(DOTTED_YMD);
    }

    private static LocalDate parseYmd(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(field + " 는 필수입니다 (YYYYMMDD)");
        }
        try {
            return LocalDate.parse(trimmed, YMD);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                    field + " 는 YYYYMMDD 형식의 실재하는 날짜여야 합니다: " + value, e);
        }
    }

    /** {@code null}·빈 문자열·공백만 있는 값을 모두 "없음"으로 모은다. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
