package jeju.datahub.complaint.service;

import java.time.LocalDate;
import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 추이 차트 응답. 배열이 아니라 객체로 감싸는 이유는 <b>프론트가 x축 라벨을 만들려면
 * 어떤 버킷으로 집계됐는지 알아야 하기 때문</b>이다({@code "07"} 을 "07시"로 쓸지 날짜로 쓸지).
 * 서버가 버킷을 자동 결정하므로 요청만 봐서는 알 수 없다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class DashboardTrendVO {

    /** 실제 적용된 버킷(요청이 생략하면 서버가 결정한 값) */
    private TrendBucket bucket;

    /** 집계 구간 시작일(포함) */
    private LocalDate startDt;

    /** 집계 구간 종료일(포함) */
    private LocalDate endDt;

    /** x축 칸 목록. 빈 칸 포함, 키 오름차순. */
    private List<TrendPointVO> points;
}
