package jeju.datahub.batchops.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 잡 파라미터 명세 한 줄 — 배치 카탈로그 {@code params[]} 를 그대로 옮긴다 (TSK-4337 Step 7).
 * 실행 모달이 이것으로 폼을 만든다(날짜 = date input · ENUM = 선택).
 */
@Schema(description = "잡 파라미터 명세 (배치 카탈로그)")
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchOpsParamSpecVO {

    @Schema(description = "Job 파라미터 이름 (실행 요청 params 의 키)", example = "baseYmd")
    private String name;

    @Schema(description = "형식 — DATE(yyyy-MM-dd) · ENUM(options 중 하나)", example = "DATE")
    private String type;

    @Schema(description = "필수 여부")
    private boolean required;

    @Schema(description = "비웠을 때의 동작 설명", example = "오늘")
    private String defaultDesc;

    @Schema(description = "ENUM 허용값 (그 밖은 빈 목록)")
    private List<String> options = List.of();

    @Schema(description = "화면 라벨")
    private String description;
}
