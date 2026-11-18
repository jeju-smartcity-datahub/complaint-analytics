package jeju.datahub.common;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.ToString;

/**
 * 페이징 응답 공통 형식. (TSK-4026 D8 — 이 프로젝트 페이징 API 의 <b>정본</b>)
 *
 * <p>Spring {@code Page} 직렬화를 쓰지 않는다 — Boot 3 에서 경고가 뜨고 필드 계약이 불안정하다.
 * 대신 아래 5개 필드를 명시적으로 내린다.
 *
 * <ul>
 *   <li>{@code content} — 현재 페이지의 행 목록</li>
 *   <li>{@code page} — <b>0-base</b> 페이지 번호</li>
 *   <li>{@code size} — 페이지 크기 (기본 10, 상한 100 — 검증은 각 컨트롤러가 한다)</li>
 *   <li>{@code totalElements} — 필터 전체 건수</li>
 *   <li>{@code totalPages} — 전체 페이지 수 (생성자가 계산한다)</li>
 * </ul>
 *
 * <p>알림 가이드(TSK-4028)도 같은 클래스를 재사용한다 — 형식을 두 벌 만들지 않는다.
 *
 * @param <T> 목록 행 타입
 */
@Schema(description = "페이징 응답 공통 형식 (page 는 0-base)")
@Getter
@ToString
public class PageResponse<T> {

    /** 현재 페이지의 행 목록 */
    @Schema(description = "현재 페이지의 행 목록")
    private final List<T> content;

    /** 페이지 번호 (0-base) */
    @Schema(description = "페이지 번호 (0-base)", example = "0")
    private final int page;

    /** 페이지 크기 */
    @Schema(description = "페이지 크기", example = "10")
    private final int size;

    /** 필터 전체 건수 */
    @Schema(description = "필터 전체 건수", example = "14")
    private final long totalElements;

    /** 전체 페이지 수 (totalElements / size 올림) */
    @Schema(description = "전체 페이지 수", example = "2")
    private final int totalPages;

    /**
     * @param content       현재 페이지의 행 목록
     * @param page          0-base 페이지 번호
     * @param size          페이지 크기 (0 이하면 totalPages 를 0 으로 둔다 — 0 나눗셈 방지)
     * @param totalElements 필터 전체 건수
     */
    public PageResponse(List<T> content, int page, int size, long totalElements) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = size > 0 ? (int) ((totalElements + size - 1) / size) : 0;
    }

    /** 정적 팩터리 — 제네릭 추론으로 호출부를 짧게 쓴다. */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        return new PageResponse<>(content, page, size, totalElements);
    }
}
