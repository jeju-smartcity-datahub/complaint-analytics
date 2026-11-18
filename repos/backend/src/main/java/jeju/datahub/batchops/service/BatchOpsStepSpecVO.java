package jeju.datahub.batchops.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 잡의 Step 명세 한 줄 — 배치 카탈로그 {@code steps[]}(순서 = 실행 순서) (TSK-4337 Step 7). */
@Schema(description = "Step 명세 (배치 카탈로그)")
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchOpsStepSpecVO {

    @Schema(description = "Step 이름", example = "complaintFetchStep")
    private String stepNm;

    @Schema(description = "Step 한글명", example = "민원 수집")
    private String stepKorNm;
}
