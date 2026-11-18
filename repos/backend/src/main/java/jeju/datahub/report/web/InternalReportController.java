package jeju.datahub.report.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.report.service.ReportAutoCrtRequest;
import jeju.datahub.report.service.ReportAutoCrtService;
import jeju.datahub.report.service.ReportAutoCrtVO;
import lombok.RequiredArgsConstructor;

/**
 * 보고서 <b>자동 생성</b> 내부 REST 컨트롤러.
 *
 * <p>🔴 <b>배치({@code reportAutoCrtJob})만 부르는 내부 API</b> 다 — 화면이 부르는
 * {@link ReportController}({@code /api/reports})와 URL 접두사를 나눠
 * ({@code /api/internal/reports}) 인증 방식도 다르다. {@code /api/internal/**} 은
 * 세션이 아니라 {@code X-API-Key} 헤더로 인증한다({@code InternalApiKeyFilter}).
 *
 * <p>집계·멱등·AUTO 표식은 전부 {@link ReportAutoCrtService} 가 한다 — 여기에는 URL 과
 * 상태코드 결정만 둔다.
 */
@Tag(name = "보고서 자동 생성(내부)",
        description = "배치 전용 내부 API — 보고서 자동 생성(TB_RPTP). X-API-Key 헤더로 인증한다.")
@RestController
@RequestMapping("/api/internal/reports")
@RequiredArgsConstructor
public class InternalReportController {

    private final ReportAutoCrtService reportAutoCrtService;

    /**
     * 자동 생성 1건. 배치({@code reportAutoCrtJob})만 부른다.
     *
     * <pre>
     * curl -X POST 'http://localhost:8180/api/internal/reports/auto-crt' \
     *   -H 'Content-Type: application/json' \
     *   -H 'X-API-Key: local-dev-key' \
     *   -d '{"stngId":100,"rptpTypeCd":"CVLCPT","trgtPrdCd":"2026-08",
     *        "bgngYmd":"20260801","endYmd":"20260831",
     *        "sggNm":"제주시","emdCd":"50110137",
     *        "crltnDeptCd":"ALL","crltnCyclCd":"MONTH"}'
     * </pre>
     *
     * <p>🔴 {@code @Valid} 를 빼면 {@link ReportAutoCrtRequest} 제약이 통째로 발동하지 않아
     * 빈 바디가 400 이 아니라 500 으로 흐른다(전역 예외 핸들러가 없다).
     *
     * <p>🔴 <b>상태코드가 배치의 분기 기준이다</b> — 201(생성) · 200(이미 있음) · 400(확정 실패) ·
     * 401(키 오류) · 503(키 미설정). <b>뭉개지 않는다</b>.
     *
     * <p>🔴 그래서 {@code @ResponseStatus(CREATED)} 를 <b>쓰지 않는다</b> — 201 과 200 을
     * 런타임에 갈라야 해서 {@link ResponseEntity} 로 돌려준다
     * ({@code created=true} → 201, {@code created=false} → 200).
     */
    @Operation(summary = "보고서 자동 생성 (배치 전용)",
            description = "설정 1건·대상기간 1개로 보고서를 만든다. 멱등하다 — 같은 (stngId, trgtPrdCd) 로 "
                    + "몇 번을 불러도 TB_RPTP 에는 한 행만 남는다. "
                    + "새로 만들면 201(created=true), 이미 있으면 409 가 아니라 200(created=false) 이다. "
                    + "설정이 없거나·삭제됐거나·요청이 설정과 어긋나면 400, "
                    + "HOTSPOT 인데 분석 결과가 0건이어도 400(CVLCPT 는 0건도 정상 보고서다). "
                    + "X-API-Key 헤더가 틀리면 401, 서버에 키가 설정돼 있지 않으면 503.")
    @PostMapping("/auto-crt")
    public ResponseEntity<ReportAutoCrtVO> autoCreate(@Valid @RequestBody ReportAutoCrtRequest req) {
        ReportAutoCrtVO result = reportAutoCrtService.createReport(req);
        return ResponseEntity.status(result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result);
    }
}
