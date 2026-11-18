package jeju.datahub.policysim.service;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 내 저장 결과 목록의 한 행 (TSK-4037 Step 7-4 — {@code GET /api/analysis/policy-sim/results}).
 *
 * <p>효과 예측 카드를 <b>저장 당시 값 그대로</b> 다시 그릴 수 있게 계산 응답({@link PolicySimResultVO})
 * 필드 전부 + 식별자·저장 시각 + 요청 조건을 담는다. 모델을 재계산하지 않는다 —
 * {@code cstAmt} 만 DB 칸이 없어 {@code addPkltSlot × aplcnParam 의 방식별 단가}로 다시 만든다(이면도로는 null).
 * 이면도로 행은 상속한 {@code roadMtncType}·{@code roadMtncLen}·{@code adjcCvlcptCnt} 가 저장값이고
 * {@code adjcEmdCnt} 는 저장 칸이 없어 null 이다(TSK-4038).
 */
@Schema(description = "정책 시뮬레이션 저장 결과 (내 목록 한 행)")
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
public class PolicySimSavedResultVO extends PolicySimResultVO {

    @Schema(description = "결과아이디 — DELETE 경로 변수", example = "12")
    private Long rsltId;

    @Schema(description = "저장 일시 (TB_ANLS_EXCN.excn_dt)", example = "2026-10-08T14:03:21.123")
    private LocalDateTime excnDt;

    @Schema(description = "요청 정책유형코드", example = "EXPAND")
    private String plcyType;

    @Schema(description = "요청 확충 방식 — VACLOT · MULTFLR · NEW (이면도로는 null)", example = "VACLOT", nullable = true)
    private String expandMethod;

    @Schema(description = "요청 추가 면수 N (이면도로는 null)", example = "100", nullable = true)
    private Integer addPkltSlot;

    @Schema(description = "요청 예측기간(개월)", example = "12")
    private Integer predcMm;

    @Schema(description = "요청 시군구명 (요청 그대로 — 생략했으면 null)", nullable = true)
    private String sggNm;

    @Schema(description = "요청 읍면동코드 (생략했으면 null)", example = "50110137", nullable = true)
    private String emdCd;
}
