package jeju.datahub.risknoti.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jeju.datahub.common.PageResponse;
import lombok.Getter;
import lombok.ToString;

/**
 * 알림 조회 화면의 <b>한 응답</b> — 요약 카드 + 목록 + 페이징 메타 (TSK-4028 D8).
 *
 * <p>시안이 같은 필터로 카드와 표를 동시에 갱신한다. 한 응답으로 내리면 왕복이 1회로 줄고,
 * 무엇보다 <b>카드와 표가 서로 다른 시점의 데이터를 보여줄 수 없다.</b>
 *
 * <p>🔴 <b>{@link PageResponse} 를 상속</b>해 5필드({@code content}·{@code page}·{@code size}·
 * {@code totalElements}·{@code totalPages})를 그대로 물려받고 {@code summary} 하나만 더한다.
 * 5필드를 복제하면 {@code totalPages} 올림 계산식이 두 벌이 되고, 페이징 응답 형식이 갈리면
 * 프론트 {@code Pager} 도 두 벌이 된다 (D10 — {@code PageResponse} 가 이 프로젝트 페이징의 정본).
 */
@Schema(description = "위험단계 알림 조회 응답 (요약 + 목록 + 페이징 메타)")
@Getter
@ToString(callSuper = true)
public class RiskAlertPageVO extends PageResponse<RiskAlertVO> {

    /** 요약 카드 3종. <b>페이지가 아니라 필터 전체 기준</b>이다 */
    @Schema(description = "요약 (필터 전체 기준)")
    private final RiskAlertSummaryVO summary;

    /**
     * @param content       현재 페이지의 알림 이력 행 목록
     * @param page          0-base 페이지 번호
     * @param size          페이지 크기
     * @param totalElements 필터 전체 건수 ({@code summary.totalCnt} 와 같은 집합을 센 값)
     * @param summary       요약 카드 3종
     */
    public RiskAlertPageVO(List<RiskAlertVO> content, int page, int size, long totalElements,
                           RiskAlertSummaryVO summary) {
        // totalPages 는 부모 생성자가 계산한다 — 여기서 다시 계산하지 않는다
        super(content, page, size, totalElements);
        this.summary = summary;
    }
}
