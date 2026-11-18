package jeju.datahub.batchops.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 실행 파라미터 1개 — {@code batch.batch_job_execution_params} (TSK-4337 Step 6).
 */
@Schema(description = "실행 파라미터")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BatchOpsParamVO {

    @Schema(description = "파라미터 이름", example = "targetDate")
    private String parameterName;

    @Schema(description = "파라미터 타입 (Java 클래스명)", example = "java.lang.String")
    private String parameterType;

    @Schema(description = "파라미터 값 (마스킹)", example = "2026-11-10")
    private String parameterValue;

    @Schema(description = "식별 파라미터 여부 (Y/N)", example = "Y")
    private String identifying;
}
