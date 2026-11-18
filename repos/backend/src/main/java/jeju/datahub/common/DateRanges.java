package jeju.datahub.common;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 조회 기간 공통 검증.
 *
 * <p>화면마다 제각각이던 규칙을 한곳으로 모은다 — 종전에는 대시보드에 역전 검증이 아예 없었고,
 * 상한은 {@code bucket=DAY} 경로에만 366일이 걸려 있었다.
 *
 * <p>상한이 366인 이유: 윤년 1년이 366일이고, 추이 DAY 버킷 상한과 같은 숫자를 써야
 * "같은 구간인데 한 API 만 거절" 이 생기지 않는다.
 */
public final class DateRanges {

    /**
     * 조회 가능한 최대 구간(일, 양끝 포함) = 1년.
     *
     * <p>상한이 필요한 이유: 추이(trend) DAY 버킷의 <b>제로필을 자바에서 하므로 무제한 구간이
     * 들어오면 응답이 폭발한다</b>. 이 근거가 사라지면 상한을 무심코 올리게 되므로
     * 여기에 남긴다.
     */
    public static final int MAX_RANGE_DAYS = 366;

    private DateRanges() {}

    /** 역전·상한을 검사하고 어기면 400. 둘 중 하나라도 null 이면 검사하지 않는다. */
    public static void validate(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return;
        }
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "종료일이 시작일보다 빠릅니다: " + start + " ~ " + end);
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "조회 기간은 최대 " + MAX_RANGE_DAYS + "일(1년)까지입니다. 요청 구간: " + days + "일");
        }
    }
}
