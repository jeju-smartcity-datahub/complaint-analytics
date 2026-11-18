package jeju.datahub.report.service.impl;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * 보고서 자동 생성의 <b>대상기간·보고서명 규칙</b> — 이 두 값의 유일한 출처. (TSK-4027 Step 3, D6·D13)
 *
 * <p>🔴 <b>{@code trgtPrdCd} 는 멱등키다</b>({@code uq_rptp_stng_prd} 의 한 칸). 규칙이 나중에
 * 바뀌면 <b>같은 주기가 다른 키로 두 번 적재된다</b> — 그래서 로직을 붙이기 전에 규칙을 여기서
 * 고정하고, 케이스 표 전량을 {@code ReportAutoCrtNamingTest} 가 문자 단위로 지킨다.
 *
 * <h2>대상기간 = 실행일 <b>직전 완료 구간</b></h2>
 *
 * <p>실행일이 속한 구간은 아직 끝나지 않았으므로 <b>그 바로 앞 구간</b>을 대상으로 삼는다.
 * 구간 경계는 설정의 배포기준월({@code crltn_mm})이 정한다 — {@code MONTH} 는 기준월과 무관하다.
 *
 * <table border="1">
 *   <caption>케이스 표 (계획서 3-3 — 정본)</caption>
 *   <tr><th>주기</th><th>실행일</th><th>trgtPrdCd</th><th>기간</th><th>기간표기</th></tr>
 *   <tr><td>MONTH</td><td>2026-09-01</td><td>{@code 2026-08}</td>
 *       <td>20260801~20260831</td><td>{@code 2026년 8월 월간}</td></tr>
 *   <tr><td>MONTH</td><td>2026-03-01</td><td>{@code 2026-02}</td>
 *       <td>20260201~20260228</td><td>{@code 2026년 2월 월간}</td></tr>
 *   <tr><td>QUARTER(mm=4)</td><td>2026-04-01</td><td>{@code 2026-Q1}</td>
 *       <td>20260101~20260331</td><td>{@code 2026년 1/4분기}</td></tr>
 *   <tr><td>QUARTER(mm=4)</td><td>2026-01-01</td><td>{@code 2025-Q4}</td>
 *       <td>20251001~20251231</td><td>{@code 2025년 4/4분기}</td></tr>
 *   <tr><td>HALF(mm=7)</td><td>2026-07-01</td><td>{@code 2026-H1}</td>
 *       <td>20260101~20260630</td><td>{@code 2026년 상반기}</td></tr>
 *   <tr><td>HALF(mm=7)</td><td>2026-01-01</td><td>{@code 2025-H2}</td>
 *       <td>20250701~20251231</td><td>{@code 2025년 하반기}</td></tr>
 *   <tr><td>YEAR(mm=1)</td><td>2026-01-01</td><td>{@code 2025}</td>
 *       <td>20250101~20251231</td><td>{@code 2025년 전체}</td></tr>
 * </table>
 *
 * <p>🔴 <b>윤년은 계산으로 구한다</b> — {@code 2026-02} 는 28일, {@code 2028-02} 는 29일이다.
 * 구간 끝날은 {@link LocalDate#lengthOfMonth()} 로 얻으며 28·30·31 을 상수로 박지 않는다.
 *
 * <p>⚠️ <b>비표준 기준월 폴백</b>(D13) — {@code QUARTER} 에 {@code crltn_mm=2} 처럼 표준 경계가
 * 아닌 값이 오면 {@code 2026-Q1} 표기가 실제 구간과 어긋난다. 이때는 분기/반기 표기를 포기하고
 * {@code 2026-02~04} 로 떨어뜨린다 — <b>멱등키는 결정적이기만 하면 되고 사람이 읽는 값이 아니다.</b>
 * 보고서명의 기간표기는 {@code 2026년 2월~4월} 이 된다. 표준 경계는 {@code QUARTER} 가
 * {@code 1·4·7·10}, {@code HALF} 가 {@code 1·7}, {@code YEAR} 가 {@code 1} 이다.
 *
 * <h2>보고서명 = {@code {기간표기} {유형어} 보고서 - {지역}}</h2>
 *
 * <p>유형어는 {@code CVLCPT} → {@code 민원}, {@code HOTSPOT} → {@code 집중구역}.
 * 지역 접미는 3단이다 — {@code 제주시 연동} / {@code 제주시} / {@code 전체 지역}.
 *
 * <p>🔴 <b>{@link ComplaintReportNaming#regionNm(String, String)} 을 재사용하지 않는다</b>(의도된
 * 중복). 그 쪽은 시군구만 있을 때 {@code "제주시 전체"} 를 만든다 —
 * {@code ReportMapper.xml} 의 {@code region_nm} CASE({@code || ' 전체'})와 <b>공백까지 같아야</b>
 * 하기 때문이다. 여기 규칙은 계획서 3-3 의 표대로 {@code "제주시"}(꼬리 없음)라 <b>값이 다르다.</b>
 * 억지로 공용화하면 한쪽을 고칠 때 다른 쪽이 조용히 따라 바뀐다 — 두 규칙은 쓰이는 자리가 다르다
 * (이쪽은 <b>보고서명에 굳어 저장되는</b> 문자열, 저쪽은 <b>조회 때마다 조립되는</b> 표시용 지역명).
 *
 * <p>🔴 <b>입력은 {@code emdCd} 가 아니라 {@code emdNm}</b> 이다 — 요청 VO 는 설정 원문인
 * {@code emd_cd} 를 싣고 오므로, 호출부(Step 4)가 {@code tb_admdst} 에서
 * {@code emd_cd → emd_nm}·{@code sgg_nm} 을 <b>함께</b> 읽어 넘긴다.
 *
 * <p>인스턴스 상태가 없어 {@code static} 유틸로 둔다({@code ComplaintReportNaming} 과 같은 형태 —
 * {@code final class} + private 생성자).
 */
public final class ReportAutoCrtNaming {

    /** 지역 조건이 아예 없을 때의 지역 접미. */
    public static final String ALL_REGION_NM = "전체 지역";

    /** 보고서명의 지역 구분자. {@code ... 보고서 - 제주시} 의 가운데 다섯 글자다(공백 포함). */
    private static final String REGION_DELIMITER = " 보고서 - ";

    /** {@code YYYYMMDD} 출력 형식 — 요청 VO 의 {@code @Pattern("\\d{8}")} 과 같은 모양이다. */
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuuMMdd");

    private ReportAutoCrtNaming() {
    }

    /**
     * 산출된 대상기간. {@code trgtPrdCd} 는 멱등키, {@code prdLabel} 은 보고서명에 들어가는 기간표기다.
     *
     * @param trgtPrdCd 대상기간 코드 — {@code 2026-08} · {@code 2026-Q1} · {@code 2026-H1} ·
     *                  {@code 2025} · (폴백) {@code 2026-02~04}
     * @param bgngYmd   구간 시작일 {@code YYYYMMDD}
     * @param endYmd    구간 종료일 {@code YYYYMMDD} (당일 포함)
     * @param prdLabel  기간표기 — {@code 2026년 8월 월간} · {@code 2026년 1/4분기} ·
     *                  {@code 2026년 상반기} · {@code 2025년 전체} · (폴백) {@code 2026년 2월~4월}
     */
    public record TargetPeriod(String trgtPrdCd, String bgngYmd, String endYmd, String prdLabel) {
    }

    /**
     * 실행일 <b>직전 완료 구간</b>을 구한다.
     *
     * @param crltnCyclCd 배포주기 코드 {@code MONTH|QUARTER|HALF|YEAR}
     * @param crltnMm     배포기준월 1~12. {@code MONTH} 면 무시한다(NULL 허용)
     * @param excnYmd     실행일(배치가 도는 날). 이 날이 속한 구간의 <b>바로 앞</b> 구간이 대상이다
     * @throws IllegalArgumentException 주기 코드가 4종이 아니거나, {@code MONTH} 가 아닌데
     *                                  {@code crltnMm} 이 비었거나 1~12 밖일 때
     */
    public static TargetPeriod targetPeriod(String crltnCyclCd, Integer crltnMm, LocalDate excnYmd) {
        String cycl = trimToNull(crltnCyclCd);
        if (cycl == null) {
            throw new IllegalArgumentException("crltnCyclCd 는 필수입니다 (MONTH|QUARTER|HALF|YEAR)");
        }
        if (excnYmd == null) {
            throw new IllegalArgumentException("excnYmd 는 필수입니다");
        }
        int len = periodMonths(cycl);
        int anchor = resolveAnchorMm(cycl, len, crltnMm);

        // 구간 시작월을 "연*12+월" 의 일차원 인덱스로 다룬다 — 연 경계(2026-01-01 → 2025-Q4)를
        // 따로 분기하지 않기 위해서다.
        long execIdx = monthIndex(YearMonth.from(excnYmd));
        long delta = Math.floorMod(execIdx - (anchor - 1), len);
        YearMonth start = yearMonth(execIdx - delta - len);     // 직전 구간의 첫 달
        YearMonth endMonth = yearMonth(monthIndex(start) + len - 1L);

        LocalDate bgng = start.atDay(1);
        LocalDate endFirstDay = endMonth.atDay(1);
        // 🔴 윤년: 28·30·31 을 상수로 박지 않는다.
        LocalDate end = endFirstDay.withDayOfMonth(endFirstDay.lengthOfMonth());

        boolean standard = isStandardAnchor(cycl, anchor);
        return new TargetPeriod(
                standard ? standardPrdCd(cycl, start) : fallbackPrdCd(start, endMonth),
                bgng.format(YMD),
                end.format(YMD),
                standard ? standardPrdLabel(cycl, start) : fallbackPrdLabel(start, endMonth));
    }

    /**
     * 보고서명 = {@code {기간표기} {유형어} 보고서 - {지역}}.
     *
     * <p>예: {@code 2026년 8월 월간 민원 보고서 - 제주시 연동}
     *
     * @param period     {@link #targetPeriod(String, Integer, LocalDate)} 의 결과
     * @param rptpTypeCd 보고서종류 {@code CVLCPT}(민원) | {@code HOTSPOT}(집중구역)
     * @param sggNm      시군구명. 없으면 {@code null}(빈 문자열·공백도 없는 것으로 본다)
     * @param emdNm      읍면동<b>명</b>. 없으면 {@code null}
     * @throws IllegalArgumentException 종류 코드가 2종이 아니거나, {@code emdNm} 만 있고
     *                                  {@code sggNm} 이 없을 때
     */
    public static String rptpNm(TargetPeriod period, String rptpTypeCd, String sggNm, String emdNm) {
        if (period == null) {
            throw new IllegalArgumentException("period 는 필수입니다");
        }
        return period.prdLabel() + " " + typeWord(rptpTypeCd)
                + REGION_DELIMITER + regionNm(sggNm, emdNm);
    }

    /**
     * 보고서명의 지역 접미 3단 규칙.
     *
     * <table border="1">
     *   <caption>3단 규칙 (계획서 3-3)</caption>
     *   <tr><th>조건</th><th>결과</th><th>예</th></tr>
     *   <tr><td>{@code emdNm} 있음</td><td>{@code {sggNm} {emdNm}}</td><td>{@code 제주시 연동}</td></tr>
     *   <tr><td>{@code sggNm} 만 있음</td><td>{@code {sggNm}}</td><td>{@code 제주시}</td></tr>
     *   <tr><td>둘 다 없음</td><td>{@code 전체 지역}</td><td>{@code 전체 지역}</td></tr>
     * </table>
     *
     * <p>🔴 시군구만 있을 때 {@code " 전체"} 를 <b>붙이지 않는다</b> —
     * {@link ComplaintReportNaming#regionNm(String, String)} 과 갈리는 유일한 자리이며,
     * 그 이유는 클래스 javadoc 에 있다.
     *
     * <p>{@code emdNm} 만 있고 {@code sggNm} 이 없으면 {@code "null 연동"} 으로 새지 않도록 끊는다 —
     * 호출부가 {@code tb_admdst} 에서 두 칸을 함께 읽어 넘기는 것이 전제다.
     */
    public static String regionNm(String sggNm, String emdNm) {
        String sgg = trimToNull(sggNm);
        String emd = trimToNull(emdNm);
        if (emd != null && sgg == null) {
            throw new IllegalArgumentException(
                    "emdNm 이 있으면 sggNm 도 있어야 합니다 — tb_admdst 에서 emd_nm 과 sgg_nm 을 함께"
                            + " 읽어 넘길 것 (emdNm=" + emdNm + ")");
        }
        if (emd != null) {
            return sgg + " " + emd;
        }
        if (sgg != null) {
            return sgg;
        }
        return ALL_REGION_NM;
    }

    /** 보고서종류 → 이름에 들어가는 유형어. */
    public static String typeWord(String rptpTypeCd) {
        String type = trimToNull(rptpTypeCd);
        if ("CVLCPT".equals(type)) {
            return "민원";
        }
        if ("HOTSPOT".equals(type)) {
            return "집중구역";
        }
        throw new IllegalArgumentException(
                "rptpTypeCd 는 CVLCPT 또는 HOTSPOT 이어야 합니다: " + rptpTypeCd);
    }

    /** 주기 코드 → 구간 길이(개월). */
    private static int periodMonths(String cycl) {
        switch (cycl) {
            case "MONTH":
                return 1;
            case "QUARTER":
                return 3;
            case "HALF":
                return 6;
            case "YEAR":
                return 12;
            default:
                throw new IllegalArgumentException(
                        "crltnCyclCd 는 MONTH|QUARTER|HALF|YEAR 여야 합니다: " + cycl);
        }
    }

    /** 기준월 정규화. {@code MONTH} 는 기준월이 없어도(NULL) 되고 있어도 결과가 같다. */
    private static int resolveAnchorMm(String cycl, int len, Integer crltnMm) {
        if (len == 1) {
            return 1;
        }
        if (crltnMm == null) {
            throw new IllegalArgumentException(cycl + " 주기는 crltnMm(1~12)이 필요합니다");
        }
        if (crltnMm < 1 || crltnMm > 12) {
            throw new IllegalArgumentException("crltnMm 은 1~12 여야 합니다: " + crltnMm);
        }
        return crltnMm;
    }

    /** 표준 경계인가 — QUARTER 1·4·7·10 / HALF 1·7 / YEAR 1 / MONTH 항상 참. */
    private static boolean isStandardAnchor(String cycl, int anchor) {
        switch (cycl) {
            case "MONTH":
                return true;
            case "QUARTER":
                return anchor % 3 == 1;
            case "HALF":
                return anchor % 6 == 1;
            default:
                return anchor == 1;
        }
    }

    private static String standardPrdCd(String cycl, YearMonth start) {
        switch (cycl) {
            case "MONTH":
                return String.format("%04d-%02d", start.getYear(), start.getMonthValue());
            case "QUARTER":
                return start.getYear() + "-Q" + quarterNo(start);
            case "HALF":
                return start.getYear() + "-H" + halfNo(start);
            default:
                return String.valueOf(start.getYear());
        }
    }

    private static String standardPrdLabel(String cycl, YearMonth start) {
        switch (cycl) {
            case "MONTH":
                return start.getYear() + "년 " + start.getMonthValue() + "월 월간";
            case "QUARTER":
                return start.getYear() + "년 " + quarterNo(start) + "/4분기";
            case "HALF":
                return start.getYear() + "년 " + (halfNo(start) == 1 ? "상반기" : "하반기");
            default:
                return start.getYear() + "년 전체";
        }
    }

    /**
     * 비표준 기준월 폴백 코드 — {@code 2026-02~04}. 구간이 해를 넘기면 끝쪽에도 연도를 적어
     * ({@code 2025-11~2026-01}) 서로 다른 구간이 같은 키가 되지 않게 한다. 최대 15자라 20자 제약 안이다.
     */
    private static String fallbackPrdCd(YearMonth start, YearMonth end) {
        String head = String.format("%04d-%02d", start.getYear(), start.getMonthValue());
        if (start.getYear() == end.getYear()) {
            return head + "~" + String.format("%02d", end.getMonthValue());
        }
        return head + "~" + String.format("%04d-%02d", end.getYear(), end.getMonthValue());
    }

    /** 비표준 기준월 폴백 기간표기 — {@code 2026년 2월~4월}. */
    private static String fallbackPrdLabel(YearMonth start, YearMonth end) {
        String head = start.getYear() + "년 " + start.getMonthValue() + "월";
        if (start.getYear() == end.getYear()) {
            return head + "~" + end.getMonthValue() + "월";
        }
        return head + "~" + end.getYear() + "년 " + end.getMonthValue() + "월";
    }

    private static int quarterNo(YearMonth start) {
        return (start.getMonthValue() - 1) / 3 + 1;
    }

    private static int halfNo(YearMonth start) {
        return start.getMonthValue() <= 6 ? 1 : 2;
    }

    private static long monthIndex(YearMonth ym) {
        return ym.getYear() * 12L + (ym.getMonthValue() - 1);
    }

    private static YearMonth yearMonth(long monthIndex) {
        return YearMonth.of((int) Math.floorDiv(monthIndex, 12L),
                (int) Math.floorMod(monthIndex, 12L) + 1);
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
