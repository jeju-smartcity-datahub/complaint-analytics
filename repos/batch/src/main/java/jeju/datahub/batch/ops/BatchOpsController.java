package jeju.datahub.batch.ops;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배치 운영 API (TSK-4337 Step 4) — 백엔드 {@code /api/ops/batch/**} 프록시(Step 7)가 부른다.
 *
 * <pre>
 * GET  /api/batch/ops/jobs                              카탈로그 9개(파라미터 명세·스케줄 상태·다음 실행)
 * POST /api/batch/ops/jobs/{jobNm}/executions           비동기 실행 → 202 {jobExecutionId} · 409 · 400 · 404
 *      {"params":{"baseYmd":"2026-11-01"},"excutrId":"user01"}
 * PUT  /api/batch/ops/schedules/{jobNm}                 {"cronExpr":"0 30 2 * * *","useYn":"Y","mdfrId":"user01"} → 200 · 400
 * POST /api/batch/ops/executions/{id}/stop              {"excutrId":"user01"}(선택) → 202 · 409 NOT_RUNNING · 404
 * POST /api/batch/ops/executions/{id}/abandon           {"excutrId":"user01"}(선택) → 200 · 409 STILL_RUNNING|NOT_RUNNING · 404
 * GET  /api/batch/ops/executions/{id}/log?tailKb=200     (Step 5) 마지막 N KB(1~1024) → 200 text/plain
 * GET  /api/batch/ops/executions/{id}/log?fromByte=N     (Step 5) N 바이트 뒤 증분(최대 256KB) → 200 text/plain
 * GET  /api/batch/ops/executions/{id}/live               (추가) 실행 중 단계별 수치 → 200 {jobExecutionId, metrics} · 404 LIVE_NOT_AVAILABLE
 *      헤더 X-Next-Byte(다음 fromByte) · X-Job-Running(true|false) · 400 INVALID_PATH|INVALID_PARAM
 *      · 404 EXECUTION_NOT_FOUND(실행 없음) | LOG_NOT_FOUND(파일 없음 — 도입 전·보존기간 경과)
 * </pre>
 *
 * <p>🔴 인증은 기존 키 필터({@code ApiKeyAuthFilterConfig}, 서블릿 패턴 {@code /api/batch/*} — 접두 매핑이라 하위 경로
 * 전부)가 진다. 오류 본문은 {@code {code, message, ...}}({@link BatchOpsExceptionHandler}).
 */
@RestController
@RequestMapping("/api/batch/ops")
public class BatchOpsController {

    /** 로그 응답 헤더 — 다음 증분 조회의 {@code fromByte}(원본 파일 바이트 위치). */
    public static final String HEADER_NEXT_BYTE = "X-Next-Byte";
    /** 로그 응답 헤더 — 이 프로세스에서 아직 돌고 있나(Step 4 실행 레지스트리 기준). false 면 폴링을 멈춘다. */
    public static final String HEADER_JOB_RUNNING = "X-Job-Running";

    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final BatchOpsService service;
    private final JobLogService logService;
    private final JobLiveMetrics liveMetrics;

    public BatchOpsController(BatchOpsService service, JobLogService logService, JobLiveMetrics liveMetrics) {
        this.service = service;
        this.logService = logService;
        this.liveMetrics = liveMetrics;
    }

    /** 실행 요청 본문. */
    public record ExecuteRequest(Map<String, String> params, String excutrId) {
    }

    /** 스케줄 변경 본문. */
    public record ScheduleRequest(String cronExpr, String useYn, String mdfrId) {
    }

    /** 중지·실패 처리 본문(선택) — 요청자. */
    public record CommandRequest(String excutrId) {
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs() {
        return service.jobs();
    }

    @PostMapping("/jobs/{jobNm}/executions")
    public ResponseEntity<Map<String, Object>> execute(@PathVariable String jobNm,
            @RequestBody(required = false) ExecuteRequest request) {
        ExecuteRequest req = request == null ? new ExecuteRequest(null, null) : request;
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.execute(jobNm, req.params(), req.excutrId()));
    }

    @PutMapping("/schedules/{jobNm}")
    public Map<String, Object> updateSchedule(@PathVariable String jobNm,
            @RequestBody ScheduleRequest request) {
        return service.updateSchedule(jobNm, request.cronExpr(), request.useYn(), request.mdfrId());
    }

    @PostMapping("/executions/{id}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable long id,
            @RequestBody(required = false) CommandRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.stop(id, request == null ? null : request.excutrId()));
    }

    /**
     * 실행별 텍스트 로그(Step 5 · D7 ②). 🔴 {@code produces} 를 걸지 않는다 — 걸면 오류 응답(JSON {@code {code,message}})이
     * {@code text/plain} 협상에 막혀 406 이 된다. Content-Type 은 응답에서 직접 정한다.
     */
    @GetMapping("/executions/{id}/log")
    public ResponseEntity<String> log(@PathVariable long id,
            @RequestParam(required = false) Integer tailKb,
            @RequestParam(required = false) Long fromByte) {
        JobLogService.LogChunk chunk = logService.read(id, tailKb, fromByte);
        return ResponseEntity.ok()
                .contentType(TEXT_UTF8)
                .header(HEADER_NEXT_BYTE, String.valueOf(chunk.nextByte()))
                .header(HEADER_JOB_RUNNING, String.valueOf(chunk.jobRunning()))
                .body(chunk.content());
    }

    /**
     * 실행 중 단계별 수치(TSK-4337 추가) — 공급자를 등록한 잡(지금은 {@code complaintFetchJob})이 이 프로세스에서 도는 동안만 200.
     * 끝났거나 공급자가 없는 잡이면 404 {@code LIVE_NOT_AVAILABLE} — 백엔드는 이를 "수치 없음" 으로 삼킨다.
     */
    @GetMapping("/executions/{id}/live")
    public Map<String, Object> live(@PathVariable long id) {
        Map<String, Object> metrics = liveMetrics.snapshot(id).orElseThrow(() -> BatchOpsException.notFound(
                "LIVE_NOT_AVAILABLE", "실행 중 단계별 수치가 없습니다(실행 #" + id + ")"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobExecutionId", id);
        body.put("metrics", metrics);
        return body;
    }

    @PostMapping("/executions/{id}/abandon")
    public Map<String, Object> abandon(@PathVariable long id,
            @RequestBody(required = false) CommandRequest request) {
        return service.abandon(id, request == null ? null : request.excutrId());
    }
}
