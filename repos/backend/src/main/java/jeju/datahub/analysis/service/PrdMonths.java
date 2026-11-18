package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * 월평균의 분모 — 걸친 달마다 (조회 일수 ÷ 그 달 일수) 를 더한다. 온전한 달은 정확히 1.
 *
 * <p>규칙(A안):
 * <ul>
 *   <li>걸친 달마다 {@code 그 달에서 조회한 일수 ÷ 그 달의 일수} 를 더한다. 온전한 달은 나눗셈하지 않고
 *       정확히 {@code 1} 을 더한다.</li>
 *   <li>소수 {@value #SCALE}자리 HALF_UP · 꼬리 0 제거 · 하한 없음(1일 조회 = 1/31 등).</li>
 * </ul>
 * 예) {@code 2026-09-26 ~ 10-26} = 5/30 + 26/31 = {@code 1.005376} ·
 * {@code 2026-01-01 ~ 08-31} = {@code 8}(온전한 달만 고르면 종전 정수와 같다).
 *
 * <p>🔴 부분 달은 중간 자릿수({@code SCALE + 4})로 더한 뒤 <b>마지막에 한 번</b> 반올림한다 — 달마다
 * 반올림하면 오차가 쌓인다.
 *
 * <p>🔴 {@code stripTrailingZeros()} 는 {@code 20.000000} 을 {@code 2E+1}(scale −1)로 만든다. 이 프로젝트엔
 * Jackson {@code WRITE_BIGDECIMAL_AS_PLAIN} 설정이 없어 그대로 {@code 2E+1} 로 직렬화되므로 scale &lt; 0 이면
 * {@code setScale(0)} 한다({@code ZoneRcmdtnShareCalculator.plain()} 과 같은 식 — 그쪽은 impl 패키지
 * package-private 이라 직접 부르지 못한다).
 *
 * <p>⚠️ <b>쓰지 않는 곳</b> — 컨트롤러 {@code validatePrdSpan}(기간 상한 36개월) ·
 * {@code InvestPrrtServiceImpl}(이용패턴이 월 단위라 이용률 집계 달(시작일이 1일이 아니면 시작 달 제외)을 센다) · {@code PolicySimBaselineCalculator}
 * (보유 개월) · 생활권(이미 일수 기준) · {@code trendMm[]} 칸 수. 월평균의 분모가 아니라 다른 뜻의
 * "개월" 이다.
 */
public final class PrdMonths {

    /** 결과 소수 자릿수 */
    public static final int SCALE = 6;

    private PrdMonths() {
    }

    /**
     * 조회 기간의 개월 수(소수 허용).
     *
     * @param bgng 시작일(포함)
     * @param end  종료일(포함)
     * @return 개월 수 — 온전한 달이면 정수 모양(scale 0), 아니면 소수 최대 {@value #SCALE}자리
     * @throws IllegalArgumentException 날짜가 null 이거나 {@code end < bgng}
     */
    public static BigDecimal of(LocalDate bgng, LocalDate end) {
        // 🔴 호출부가 이미 400 으로 막지만 여기서도 끊는다 — 안 끊으면 달이 다를 때 0, 같은 달이면 음수가 나온다
        if (bgng == null || end == null || end.isBefore(bgng)) {
            throw new IllegalArgumentException("기간이 올바르지 않습니다: " + bgng + " ~ " + end);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (YearMonth ym = YearMonth.from(bgng); !ym.isAfter(YearMonth.from(end)); ym = ym.plusMonths(1)) {
            LocalDate from = bgng.isAfter(ym.atDay(1)) ? bgng : ym.atDay(1);          // LocalDate 엔 static max/min 이 없다
            LocalDate to   = end.isBefore(ym.atEndOfMonth()) ? end : ym.atEndOfMonth();
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            sum = sum.add(days == ym.lengthOfMonth() ? BigDecimal.ONE
                    : BigDecimal.valueOf(days).divide(BigDecimal.valueOf(ym.lengthOfMonth()), SCALE + 4, RoundingMode.HALF_UP));
        }
        BigDecimal s = sum.setScale(SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;   // 20 → 2E+1 방지 (ZoneRcmdtnShareCalculator.plain() 과 같은 식)
    }
}
