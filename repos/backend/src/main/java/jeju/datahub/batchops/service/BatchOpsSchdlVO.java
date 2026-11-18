package jeju.datahub.batchops.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 잡 스케줄 — {@code batch.tb_btch_schdl} 한 행.
 *
 * <p>{@code registered}·{@code blockedByEnv}·{@code envKey}·{@code nextRuns} 는 배치 프로세스만 아는 값이라
 * 배치 카탈로그의 {@code schedule} 객체(같은 필드명)를 병합이 옮겨 담는다 — 못 받으면 {@code null}.
 */
@Schema(description = "배치 잡 스케줄")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BatchOpsSchdlVO {

    /** Spring 6필드 cron */
    @Schema(description = "cron (Spring 6필드)", example = "0 0 2 * * *")
    private String cronExpr;

    /** 사용여부 — N 이면 스케줄 중지 */
    @Schema(description = "사용여부 (N = 스케줄 중지)", example = "Y")
    private String useYn;

    /** 마지막 변경자 (초기값 그대로면 null) */
    @Schema(description = "마지막 변경자")
    private String mdfrId;

    /** 마지막 변경 시각 (초기값 그대로면 null) */
    @Schema(description = "마지막 변경 시각")
    private LocalDateTime mdfcnDt;

    // ---------------------------------------------------------------- 배치 프로세스 칸 (병합 — 못 받으면 null)

    /** 배치 스케줄러에 지금 등록돼 있는가 */
    @Schema(description = "스케줄러 등록 여부 (카탈로그 — 못 받으면 null)")
    private Boolean registered;

    /** env 차단 스위치({@code *.sync.scheduled=false})로 막혔는가 */
    @Schema(description = "env 차단 여부 (카탈로그 — 못 받으면 null)")
    private Boolean blockedByEnv;

    /** 차단 스위치 env 이름 */
    @Schema(description = "차단 스위치 env 이름 (카탈로그 — 못 받으면 null)")
    private String envKey;

    /** 다음 실행 시각 3회 */
    @Schema(description = "다음 실행 시각 (카탈로그 — 못 받으면 null)")
    private List<OffsetDateTime> nextRuns;
}
