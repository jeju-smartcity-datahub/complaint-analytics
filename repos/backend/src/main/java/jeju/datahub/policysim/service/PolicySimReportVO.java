package jeju.datahub.policysim.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 정책 시뮬레이션 시나리오 비교 보고서 응답 루트 (TSK-4229 Step 2).
 * (POST /api/analysis/policy-sim/reports 201 · GET /api/reports/{rptpId}/policy-sim)
 *
 * <p><b>보고서 메타 6칸 + 저장 시점의 내용</b>({@link #headSeq} · {@link #scenarios})이다. 내용 부분은
 * {@code TB_ANLS_RSLT.rslt_json} = {@code {headSeq, scenarios:[{seq, rcmdYn, rsltId, params, result}]}} 와 같은 모양이다.
 * 프론트는 이 응답 하나로 문서를 그린다.
 *
 * <p>🔴 POST 와 GET 이 같은 모양·같은 값이다 — GET 은 {@code rslt_json} 을 되살리고 다시 계산하지 않는다.
 */
@Schema(description = "정책 시뮬레이션 시나리오 비교 보고서 (저장 시점 스냅샷)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PolicySimReportVO {

    // --- 보고서 메타 ---

    /** 보고서아이디 (rptp_id — TB_RPTP PK) */
    @Schema(description = "보고서아이디", example = "101")
    private Long rptpId;

    /** 실행아이디 (excn_id — TB_ANLS_EXCN PK. 보고서용으로 새로 만든 실행 행 — D2) */
    @Schema(description = "분석실행아이디", example = "345")
    private Long excnId;

    /** 보고서명 — {@code {YYYYMMDD}({n})_정책 시뮬레이션 시나리오 비교}. 내려받는 파일 이름도 이 값이다 */
    @Schema(description = "보고서명 (파일 이름으로도 쓴다)", example = "20261014(1)_정책 시뮬레이션 시나리오 비교")
    private String rptpNm;

    /** 분석기간설명 (anls_prd_expln — {@code 예측 기간 {predcMm}개월}, 대표 시나리오 기준) */
    @Schema(description = "분석기간설명 (대표 시나리오의 예측 기간)", example = "예측 기간 3개월")
    private String anlsPrdExpln;

    /** 파일형식 (file_fmt — PDF | DOCX. 요청값 그대로) */
    @Schema(description = "파일형식 (PDF | DOCX)", example = "PDF")
    private String fileFmt;

    /** 생성일시 (crt_ymd — 보고서 저장 시각) */
    @Schema(description = "생성일시", example = "2026-10-14T10:21:03")
    private LocalDateTime crtYmd;

    // --- 저장 시점의 내용 (rslt_json 과 같은 모양) ---

    /** 대표 시나리오의 순번 (1-base = 요청 headIdx + 1). 프론트는 {@code seq === headSeq} 로 대표를 찾는다 */
    @Schema(description = "대표 시나리오 순번 (1-base)", example = "1")
    private Integer headSeq;

    /** 시나리오 — 요청 순서 그대로(seq 오름차순) */
    @Schema(description = "시나리오 (요청 순서 · seq 오름차순)")
    private List<PolicySimReportScenarioVO> scenarios = new ArrayList<>();
}
