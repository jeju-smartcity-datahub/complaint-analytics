package jeju.datahub.report.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 보고서 자동 생성 응답. (POST /api/internal/reports/auto-crt — TSK-4027)
 *
 * <p>🔴 <b>{@link #created} 가 이 VO 의 존재 이유다</b>(D5). 같은 {@code (stngId, trgtPrdCd)} 로
 * 두 번 들어오면 <b>새로 만들지 않고 이미 있던 행의 ID 를 그대로 돌려주며</b> {@code created=false}
 * 로 표시한다 — 409 가 아니라 <b>200</b> 이다. 배치가 재시도해도 같은 주기가 두 번 적재되지 않고,
 * 배치 쪽은 이 값으로 "새로 만든 건수" 와 "이미 있어 건너뛴 건수" 를 이력에 나눠 적는다.
 */
@Schema(description = "보고서 자동 생성 결과")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ReportAutoCrtVO {

    /** 보고서아이디 (TB_RPTP.rptp_id). {@code created=false} 면 <b>이미 있던 행</b>의 ID 다. */
    @Schema(description = "보고서아이디", example = "101")
    private Long rptpId;

    /** 분석실행아이디 (TB_RPTP.excn_id) — 보고서가 물고 있는 분석 스냅샷. */
    @Schema(description = "분석실행아이디", example = "55")
    private Long excnId;

    /** 보고서명 — {@code ReportAutoCrtNaming} 이 만든 값. */
    @Schema(description = "보고서명", example = "2026년 8월 월간 민원 보고서 - 제주시 연동")
    private String rptpNm;

    /** 대상기간 코드 (멱등키) — 요청값을 되비춘다. */
    @Schema(description = "대상기간 코드", example = "2026-08")
    private String trgtPrdCd;

    /** 이번 호출이 새로 만들었으면 {@code true}, 이미 있어 그대로 돌려줬으면 {@code false}. */
    @Schema(description = "신규 생성 여부 (false = 이미 있던 행을 돌려줌)", example = "true")
    private boolean created;
}
