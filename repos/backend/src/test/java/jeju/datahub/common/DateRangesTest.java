package jeju.datahub.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 조회 기간 공통 검증 단위 테스트 (TSK-4085 Step 4 케이스 5).
 *
 * <p><b>무엇을 지키는 테스트인가</b> — {@link DateRanges#validate} 의 <b>경계</b>다. 대시보드·집중구역·
 * 생활권·위험알림 4영역이 이 한 곳을 호출하므로, 여기가 흔들리면 네 화면이 함께 흔들린다.
 * 366(통과)/367(400)을 한 쌍으로 못박아야 상한이 고정된다 — 한쪽만 있으면 규칙이 통째로
 * 빠져도(또는 과하게 좁아져도) 테스트가 통과한다.
 *
 * <p>🔴 <b>날짜를 상수로 박지 않는다.</b> 구간은 {@link LocalDate#now()} 기준 상대값으로 만든다 —
 * 고정 날짜로 366/367 을 세면 윤년이 끼는 해에 하루가 어긋난다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다 — 검증기는 정적 유틸이라 DB·웹 계층이 필요 없다.
 */
@DisplayName("조회 기간 공통 검증 (DateRanges.validate)")
class DateRangesTest {

    /** 오늘을 기준으로 잡는다 — 이 클래스 안에서만 쓰는 앵커라 실행 시각에 흔들릴 여지가 없다. */
    private final LocalDate end = LocalDate.now();

    @Test
    @DisplayName("종료일이 시작일보다 빠르면 400")
    void reversedRangeIsRejected() {
        LocalDate start = end.plusDays(1);

        assertThatThrownBy(() -> DateRanges.validate(start, end))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("366일(양끝 포함, 상한과 같음)은 통과한다")
    void maxRangeIsAccepted() {
        // 365 를 빼면 양끝 포함 366일 = MAX_RANGE_DAYS
        assertThatCode(() -> DateRanges.validate(end.minusDays(DateRanges.MAX_RANGE_DAYS - 1), end))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("367일(상한 +1)은 400")
    void overMaxRangeIsRejected() {
        assertThatThrownBy(() -> DateRanges.validate(end.minusDays(DateRanges.MAX_RANGE_DAYS), end))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("한쪽이라도 null 이면 검사하지 않는다 — 기간 미지정 호출을 막지 않기 위함")
    void nullBoundSkipsValidation() {
        assertThatCode(() -> DateRanges.validate(null, end)).doesNotThrowAnyException();
        assertThatCode(() -> DateRanges.validate(end, null)).doesNotThrowAnyException();
        assertThatCode(() -> DateRanges.validate(null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 날(1일 구간)은 통과한다 — 하루 조회가 역전으로 오인되지 않는다")
    void singleDayIsAccepted() {
        assertThatCode(() -> DateRanges.validate(end, end)).doesNotThrowAnyException();
    }
}
