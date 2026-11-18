package jeju.datahub.policysim.service;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 보고서에 담을 시나리오 한 건 — {@code rsltId} <b>또는</b> {@code cond} 중 <b>정확히 하나</b>.
 *
 * <ul>
 *   <li>저장분 {@code { "rsltId": 12 }} — 저장된 값 그대로 싣는다(내 것이 아니면 404)</li>
 *   <li>미저장분 {@code { "cond": { "plcyType": … } }} — 서버가 그 조건으로 재계산한다</li>
 * </ul>
 *
 * <p>🔴 조건 칸을 이 항목에 <b>평면으로 펼치지 않는다</b>({@link PolicySimRequest} 상속 · {@code @JsonUnwrapped} 금지) —
 * 그러면 {@code plcyType}·{@code predcMm} 의 {@code @NotNull} 이 {@code rsltId} 만 온 항목까지 400 으로 만든다.
 * 필드로 품으면 {@code cond} 가 null 인 항목에는 {@code @Valid} 가 내려가지 않는다.
 */
@Schema(description = "보고서 시나리오 한 건 — rsltId(저장분) 또는 cond(미저장분) 중 정확히 하나")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PolicySimReportScenarioRequest {

    /** 저장된 결과 아이디 (TB_PLCY_SMLT_RSLT.rslt_id) — 저장분일 때만. 없거나 남의 것이면 404 */
    @Schema(description = "저장된 결과 아이디 — 저장분일 때만(cond 와 둘 중 하나). 없거나 남의 것이면 404",
            example = "12", nullable = true)
    private Long rsltId;

    /** 시뮬레이션 조건 — 미저장분일 때만. 조회·저장 요청과 같은 칸·같은 검증 */
    @Valid
    @Schema(description = "시뮬레이션 조건 — 미저장분일 때만(rsltId 와 둘 중 하나). PolicySimRequest 와 같은 칸",
            nullable = true)
    private PolicySimRequest cond;

    /**
     * {@code rsltId} 와 {@code cond} 중 정확히 하나 — 둘 다 있거나 둘 다 없으면 400.
     *
     * <p>🔴 {@code @Schema(hidden = true)}·{@code @JsonIgnore} 필수 — 없으면 {@code validItem} 속성이
     * Swagger 스키마와 JSON 에 드러난다({@link PolicySimRequest#isValidForType()} 선례).
     */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "시나리오는 rsltId 또는 cond 중 정확히 하나만 가져야 합니다")
    public boolean isValidItem() {
        return (rsltId == null) != (cond == null);
    }
}
