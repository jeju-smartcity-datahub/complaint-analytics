package jeju.datahub.batch.rptp;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * "오늘이 이 설정의 배포일인가" 와 "그렇다면 대상기간은 언제부터 언제까지인가" 를 판정한다
 * (TSK-4027 Step 7-2 · D6·D13).
 *
 * <h2>🔴 백엔드 {@code ReportAutoCrtNaming} 과 <b>의도된 중복</b>이다</h2>
 * 레포가 달라 코드를 공유할 수 없다(계획서 D6). 그래서 <b>같은 케이스 표를 양쪽에 두는 것</b>이
 * 대응이다 — {@code ReportDueDateResolverTest}(배치)와 {@code ReportAutoCrtNamingTest}(백엔드)가
 * 계획서 3-3 의 <b>같은 7행을 같은 기대값으로</b> 단언한다. 규칙을 고칠 일이 생기면
 * <b>두 테스트를 함께</b> 고쳐야 하며, 한쪽만 바뀌면 그 순간 테스트가 깨져 드러난다.
 *
 * <p>🔴 {@code trgtPrdCd} 는 <b>멱등키</b>({@code uq_rptp_stng_prd} 의 한 칸)다. 이 문자열이
 * 달라지면 같은 주기가 다른 키로 <b>두 번 적재된다.</b>
 *
 * <h2>도래 판정 (계획서 7-2 표 — 정본)</h2>
 * <table border="1">
 *   <caption>오늘이 배포일인 조건</caption>
 *   <tr><th>주기</th><th>조건</th></tr>
 *   <tr><td>MONTH</td><td>{@code today.dayOfMonth == crltnDd}</td></tr>
 *   <tr><td>QUARTER</td><td>위 + {@code floorMod(today.month - crltnMm, 3) == 0}</td></tr>
 *   <tr><td>HALF</td><td>위 + {@code floorMod(today.month - crltnMm, 6) == 0}</td></tr>
 *   <tr><td>YEAR</td><td>위 + {@code today.month == crltnMm}</td></tr>
 * </table>
 *
 * <p>🔴 <b>{@code %} 가 아니라 {@link Math#floorMod}</b> 다. 자바의 {@code %} 는 왼쪽이 음수면
 * 음수를 낸다 — {@code (1 - 4) % 3 == 0} 은 참이지만 {@code (1 - 7) % 6} 처럼 다른 조합에서는
 * 부호 때문에 판정이 뒤집힌다. {@code crltnMm} 이 실행월보다 큰 경우가 흔해(1월 실행 ·
 * 기준월 7월) 음수는 예외가 아니라 일상이다.
 *
 * <p>🔴 <b>{@code WEEK} 은 판정하지 않고 false + WARN 1줄</b>이다(예외를 던지지 않는다).
 * 백엔드가 저장을 막고 있어 실제로는 오지 않지만 DB 직접 조작으로 들어올 수 있고, 그때
 * <b>조용히 매일 생성되면 안 된다.</b> 예외로 끊으면 설정 하나 때문에 Job 전체가 죽으므로
 * "그 설정만 건너뛰되 로그로 드러낸다" 를 택한다.
 *
 * <p>🔴 <b>{@code crltnDd} 는 1~28 로 제한돼 있다</b>({@code ck_rptp_stng_crltn_dd}). 그래서
 * "31일이 없는 달에는 영원히 도래하지 않는다" 는 함정이 <b>여기에는 없다.</b>
 * ⚠️ <b>그 제약이 사라지면 이 클래스가 깨진다</b> — 말일 보정({@code min(crltnDd, 말일)})을
 * 그때 여기에 넣어야 한다.
 *
 * <h2>대상기간 = 실행일 <b>직전 완료 구간</b></h2>
 * 실행일이 속한 구간은 아직 끝나지 않았으므로 그 바로 앞 구간을 대상으로 삼는다. 구간 경계는
 * 설정의 배포기준월({@code crltnMm})이 정한다 — {@code MONTH} 는 기준월과 무관하다.
 *
 * <p>🔴 <b>윤년은 계산으로 구한다</b> — {@code 2026-02} 는 28일, {@code 2028-02} 는 29일이다.
 * 구간 끝날은 {@link LocalDate#lengthOfMonth()} 로 얻으며 28·30·31 을 상수로 박지 않는다.
 *
 * <p>⚠️ <b>비표준 기준월 폴백</b>(D13) — {@code QUARTER} 에 {@code crltn_mm=2} 처럼 표준
 * 경계(QUARTER 1·4·7·10 / HALF 1·7 / YEAR 1)가 아닌 값이 오면 {@code 2026-Q1} 표기가 실제
 * 구간과 어긋난다. 이때는 분기/반기 표기를 포기하고 {@code 2026-02~04} 로 떨어뜨린다 —
 * <b>멱등키는 결정적이기만 하면 되고 사람이 읽는 값이 아니다.</b>
 *
 * <p>⚠️ <b>기준일은 Job 파라미터 {@code baseYmd} 하나가 정본이다.</b> 이 클래스는
 * "오늘" 을 직접 읽는 호출({@code LocalDate.now} 계열)을 <b>두지 않는다</b> — Reader·Processor·Writer 가 각자 "오늘" 을
 * 읽으면 자정을 걸칠 때 하루가 갈린다({@code RiskAlertProperties} 와 같은 이유).
 *
 * <p>상태가 없어 스레드 안전하다. 스프링 빈으로 두는 이유는 Processor 주입 편의뿐이며,
 * 단위 테스트는 {@code new ReportDueDateResolver()} 로 직접 만든다.
 */
@Component
public class ReportDueDateResolver {

    private static final Logger log = LoggerFactory.getLogger(ReportDueDateResolver.class);

    /** {@code YYYYMMDD} — 백엔드 요청 VO 의 {@code @Pattern("\\d{8}")} 과 같은 모양이다. */
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuuMMdd");

    /**
     * 산출된 대상기간. 세 값 모두 백엔드 {@code POST /api/internal/reports/auto-crt} 요청에
     * 그대로 실린다.
     *
     * @param trgtPrdCd 대상기간 코드 — {@code 2026-08} · {@code 2026-Q1} · {@code 2026-H1} ·
     *                  {@code 2025} · (폴백) {@code 2026-02~04}. 🔴 <b>멱등키</b>
     * @param bgngYmd   구간 시작일 {@code YYYYMMDD}
     * @param endYmd    구간 종료일 {@code YYYYMMDD} (당일 포함)
     */
    public record TargetPeriod(String trgtPrdCd, String bgngYmd, String endYmd) {
    }

    /**
     * 오늘({@code baseDate})이 이 설정의 배포일인가.
     *
     * <p>🔴 <b>대부분의 설정이 대부분의 날에 false 다</b> — 월간 설정도 한 달에 하루만 참이다.
     * false 가 정상이며 오류가 아니다.
     *
     * @param stng     설정 한 행
     * @param baseDate 기준일(Job 파라미터 {@code baseYmd})
     * @return 도래했으면 {@code true}. 판정할 수 없는 설정({@code WEEK}·미등록 주기·
     *         {@code crltnDd} 없음·기준월이 필요한데 없음)은 <b>WARN 1줄 뒤 {@code false}</b>
     *         이며 예외를 던지지 않는다
     */
    public boolean isDue(RptpStngRow stng, LocalDate baseDate) {
        if (stng == null || baseDate == null) {
            throw new IllegalArgumentException("stng·baseDate 는 필수입니다");
        }
        String cycl = trimToNull(stng.crltnCyclCd());
        Integer dd = stng.crltnDd();
        if (dd == null) {
            log.warn("자동생성 설정 stngId={} 의 배포기준일(crltn_dd)이 비어 있어 도래 판정을 건너뜁니다"
                    + " — 설정을 고치기 전에는 보고서가 생성되지 않습니다.", stng.stngId());
            return false;
        }
        if (baseDate.getDayOfMonth() != dd) {
            return false;
        }
        if (cycl == null) {
            log.warn("자동생성 설정 stngId={} 의 배포주기(crltn_cycl_cd)가 비어 있어 도래 판정을"
                    + " 건너뜁니다.", stng.stngId());
            return false;
        }

        int month = baseDate.getMonthValue();
        switch (cycl) {
            case "MONTH":
                // 기준월과 무관하다 — 매달 crltnDd 에 도래한다.
                return true;
            case "QUARTER":
                return anchorMatches(stng, cycl, month, 3);
            case "HALF":
                return anchorMatches(stng, cycl, month, 6);
            case "YEAR":
                return anchorMatches(stng, cycl, month, 12);
            default:
                // 🔴 WEEK 을 포함한 미등록 주기 — false + WARN 1줄. 예외를 던지지 않는다.
                log.warn("자동생성 설정 stngId={} 의 배포주기 '{}' 는 배치가 판정하지 않는 값이라"
                        + " 건너뜁니다(지원: MONTH|QUARTER|HALF|YEAR). 백엔드 저장 경로가 막고 있는"
                        + " 값이므로 DB 직접 조작이 있었는지 확인하십시오.", stng.stngId(), cycl);
                return false;
        }
    }

    /**
     * 대상기간(실행일 직전 완료 구간)을 구한다. {@link #isDue} 가 {@code true} 일 때만 부른다.
     *
     * @param stng     설정 한 행
     * @param baseDate 기준일(Job 파라미터 {@code baseYmd})
     * @throws IllegalArgumentException 주기 코드가 4종이 아니거나, {@code MONTH} 가 아닌데
     *                                  {@code crltnMm} 이 비었거나 1~12 밖일 때.
     *                                  🔴 {@code isDue} 가 이미 걸러 낸 상태라 여기 도달하면
     *                                  <b>호출 순서가 잘못된 것</b>이므로 조용히 넘기지 않는다
     */
    public TargetPeriod resolve(RptpStngRow stng, LocalDate baseDate) {
        if (stng == null || baseDate == null) {
            throw new IllegalArgumentException("stng·baseDate 는 필수입니다");
        }
        String cycl = trimToNull(stng.crltnCyclCd());
        if (cycl == null) {
            throw new IllegalArgumentException("crltnCyclCd 는 필수입니다 (MONTH|QUARTER|HALF|YEAR)");
        }
        int len = periodMonths(cycl);
        int anchor = resolveAnchorMm(cycl, len, stng.crltnMm());

        // 구간 시작월을 "연*12+월" 의 일차원 인덱스로 다룬다 — 연 경계(2026-01-01 → 2025-Q4)를
        // 따로 분기하지 않기 위해서다.
        long execIdx = monthIndex(YearMonth.from(baseDate));
        long delta = Math.floorMod(execIdx - (anchor - 1), len);
        YearMonth start = yearMonth(execIdx - delta - len);      // 직전 구간의 첫 달
        YearMonth endMonth = yearMonth(monthIndex(start) + len - 1L);

        LocalDate bgng = start.atDay(1);
        LocalDate endFirstDay = endMonth.atDay(1);
        // 🔴 윤년: 28·30·31 을 상수로 박지 않는다.
        LocalDate end = endFirstDay.withDayOfMonth(endFirstDay.lengthOfMonth());

        boolean standard = isStandardAnchor(cycl, anchor);
        return new TargetPeriod(
                standard ? standardPrdCd(cycl, start) : fallbackPrdCd(start, endMonth),
                bgng.format(YMD),
                end.format(YMD));
    }

    /**
     * 기준일(Job 파라미터 {@code baseYmd}) 파싱 — {@code yyyy-MM-dd}.
     *
     * <p>트리거·스케줄러가 {@code addString("baseYmd", base.toString())} 로 넣는다(Step 9).
     * 🔴 <b>비어 있으면 "오늘" 로 떨어지지 않고 예외다</b> — 기준일을 각자 계산하기 시작하면
     * 자정 근처에서 Reader·Processor 가 하루씩 갈린다. "오늘" 을 정하는 곳은 트리거 한 곳뿐이다.
     */
    public static LocalDate parseBaseYmd(String baseYmdParam) {
        if (baseYmdParam == null || baseYmdParam.isBlank()) {
            throw new IllegalArgumentException(
                    "Job 파라미터 baseYmd(yyyy-MM-dd)는 필수입니다 — 기준일은 트리거가 확정해 넘깁니다");
        }
        return LocalDate.parse(baseYmdParam.trim());
    }

    /** 기준월 주기(QUARTER·HALF·YEAR)의 월 일치 판정. 🔴 {@code %} 가 아니라 {@code floorMod}. */
    private boolean anchorMatches(RptpStngRow stng, String cycl, int month, int len) {
        Integer mm = stng.crltnMm();
        if (mm == null) {
            log.warn("자동생성 설정 stngId={} 는 주기 {} 인데 배포기준월(crltn_mm)이 비어 있어 도래"
                    + " 판정을 건너뜁니다.", stng.stngId(), cycl);
            return false;
        }
        if (mm < 1 || mm > 12) {
            log.warn("자동생성 설정 stngId={} 의 배포기준월 {} 이 1~12 밖이라 도래 판정을 건너뜁니다.",
                    stng.stngId(), mm);
            return false;
        }
        // YEAR 은 len=12 라 floorMod(month - mm, 12) == 0 이 곧 month == mm 이다.
        return Math.floorMod(month - mm, len) == 0;
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

    /**
     * 비표준 기준월 폴백 코드 — {@code 2026-02~04}. 구간이 해를 넘기면 끝쪽에도 연도를 적어
     * ({@code 2025-11~2026-01}) 서로 다른 구간이 같은 키가 되지 않게 한다. 최대 15자라
     * {@code trgt_prd_cd VARCHAR(20)} 안이다.
     */
    private static String fallbackPrdCd(YearMonth start, YearMonth end) {
        String head = String.format("%04d-%02d", start.getYear(), start.getMonthValue());
        if (start.getYear() == end.getYear()) {
            return head + "~" + String.format("%02d", end.getMonthValue());
        }
        return head + "~" + String.format("%04d-%02d", end.getYear(), end.getMonthValue());
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
