package jeju.datahub.batchops.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 실행 중 진행 단계 (TSK-4337 Step 6 · D6) — 근거는 {@code batch_step_execution} 뿐이다(별도 진행률 저장소 없음).
 *
 * <p>⚠️ 청크 안에서 오래 걸리는 구간(지오코딩 API 왕복)은 다음 커밋까지 숫자가 멈춰 보인다 — 정상이다.
 * {@code totalStepCnt} 는 카탈로그 Step 수라 Step 7 병합이 채운다(배치가 꺼져 있으면 null → 화면 {@code n / ?}).
 */
@Schema(description = "진행 단계 (실행 중일 때만)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BatchOpsProgressVO {

    /** 현재 Step — 상태 STARTED 인 Step 중 가장 나중 것 (Step 사이 전환 중이면 null) */
    @Schema(description = "현재 Step 이름 (Step 전환 중이면 null)", example = "complaintFetchStep")
    private String currentStep;

    /** 현재 Step 한글명 (카탈로그) */
    @Schema(description = "현재 Step 한글명 (카탈로그 — 못 받으면 null)")
    private String currentStepKorNm;

    /** 완료(COMPLETED) Step 수 */
    @Schema(description = "완료 Step 수", example = "1")
    private int cmptnStepCnt;

    /** 전체 Step 수 (카탈로그) */
    @Schema(description = "전체 Step 수 (카탈로그 — 못 받으면 null)")
    private Integer totalStepCnt;

    /** 현재 Step 읽기 수 */
    @Schema(description = "현재 Step 읽기 수")
    private Long readCount;

    /** 현재 Step 쓰기 수 */
    @Schema(description = "현재 Step 쓰기 수")
    private Long writeCount;

    /** 단계별 수치(TSK-4337 추가) — 지원 잡(민원 수집)이 배치 프로세스에서 도는 동안만. 그 밖·배치 무응답이면 null */
    @Schema(description = "단계별 수치 (API 수집·지오코딩 — 민원 수집 실행 중일 때만, 아니면 null)")
    private BatchOpsLiveVO live;
}
