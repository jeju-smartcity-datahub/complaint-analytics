package jeju.datahub.batchops.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.batchops.service.BatchOpsExcnDetailVO;
import jeju.datahub.batchops.service.BatchOpsExcnSearchVO;
import jeju.datahub.batchops.service.BatchOpsExcnVO;
import jeju.datahub.batchops.service.BatchOpsJobsVO;
import jeju.datahub.batchops.service.BatchOpsLogVO;
import jeju.datahub.batchops.service.BatchOpsProxyException;
import jeju.datahub.batchops.service.BatchOpsService;
import jeju.datahub.batchops.service.impl.BatchOpsClient;
import jeju.datahub.batchops.service.impl.BatchOpsClient.Upstream;
import jeju.datahub.common.PageResponse;
import jeju.datahub.common.web.CurrentUserResolver;
import lombok.RequiredArgsConstructor;

/**
 * 배치 운영 화면({@code /ops/batch} — 숨김 메뉴 {@code M0604}) API — 조회(DB 직접) + 명령 프록시.
 *
 * <p>🔴 <b>명령은 배치 앱({@code /api/batch/ops/**})으로 프록시한다</b>({@link BatchOpsClient}). 실행자·수정자는
 * <b>로그인 {@code user_id}</b> 다({@link CurrentUserResolver}) — 프론트가 보낸 {@code excutrId}·{@code mdfrId} 는 받지 않는다.
 * 배치의 400·404·409 는 상태·본문 그대로, 401·5xx·연결 실패·타임아웃은 503 {@code BATCH_UNAVAILABLE},
 * 미설정은 503 {@code BATCH_OPS_NOT_CONFIGURED}({@link BatchOpsExceptionHandler}).
 * 🔴 POST·PUT 은 CSRF 면제가 아니다(기존 체인 그대로 — 프론트는 공용 {@code api} 인스턴스).
 *
 * <p>🔴 <b>조회(GET)에도 인가를 건다</b> — {@code /api/ops/batch/**} 전부가 {@code M0604} 다.
 * 조회 GET 인가의 두 번째 예외다(첫 사례 {@code GET /api/noti-receivers/candidates}). 롤 리터럴을 두지 않는다.
 *
 * <p>전역 예외 핸들러가 없으므로 잘못된 입력은 {@link ResponseStatusException} 으로 직접 400 을 낸다.
 */
@Tag(name = "배치 운영", description = "배치 실행 이력·스케줄 조회(batch 스키마 직접) · 실행·스케줄·중지·로그 명령 프록시 (M0604)")
@RestController
@RequestMapping("/api/ops/batch")
@RequiredArgsConstructor
public class BatchOpsController {

    /** 페이지 크기 상한 (PageResponse 관례) */
    static final int MAX_PAGE_SIZE = 100;

    /** 상태 필터 화이트리스트 — Spring Batch {@code BatchStatus} */
    static final Set<String> STATUSES = Set.of(
            "STARTING", "STARTED", "STOPPING", "STOPPED", "COMPLETED", "FAILED", "ABANDONED", "UNKNOWN");

    /** batch_job_instance.job_name 길이 */
    private static final int JOB_NM_MAX = 100;

    /** 잡 이름 — 배치 카탈로그 이름 모양(영문 시작 영숫자). 경로 조작 차단용 1차 검증 */
    private static final Pattern JOB_NM = Pattern.compile("[A-Za-z][A-Za-z0-9]{0," + (JOB_NM_MAX - 1) + "}");

    private static final Logger log = LoggerFactory.getLogger(BatchOpsController.class);

    private final BatchOpsService batchOpsService;
    private final BatchOpsClient batchOpsClient;
    private final CurrentUserResolver currentUser;
    private final ObjectMapper objectMapper;

    /** 실행 요청 — 🔴 {@code excutrId} 칸이 없다(보내도 무시 — 로그인 user_id 를 쓴다) */
    @Schema(description = "배치 실행 요청")
    public record ExecuteRequest(
            @Schema(description = "Job 파라미터 (카탈로그 params 명세의 name → 값 · 비우면 기본값)") Map<String, String> params) {
    }

    /** 스케줄 변경 요청 — 🔴 {@code mdfrId} 칸이 없다(로그인 user_id 를 쓴다) */
    @Schema(description = "스케줄 변경 요청")
    public record ScheduleRequest(
            @Schema(description = "cron (Spring 6필드)", example = "0 30 2 * * *") String cronExpr,
            @Schema(description = "사용여부 Y/N", example = "Y") String useYn) {
    }

    @Operation(summary = "배치 잡 목록",
            description = "잡별 마지막 실행(상태·소요·처리 건수·실행구분) · 마지막 성공 시각 · 스케줄(cron·사용여부). "
                    + "응답은 객체 {catalogAvailable, jobs} 다. catalogAvailable=false 면 DB 에 실행 기록이 있는 잡 이름만으로 그린 목록이다. "
                    + "M0604(배치 운영) 권한이 없으면 403.")
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0604')")
    @GetMapping("/jobs")
    public BatchOpsJobsVO jobs() {
        return batchOpsService.selectJobs();
    }

    @Operation(summary = "배치 실행 이력",
            description = "실행 이력 페이지(최신순). jobNm 정확 일치·status(BatchStatus) 필터. "
                    + "page 는 0-base(기본 0), size 는 기본 10·최대 100. 메시지는 마스킹 후 500자로 자른다. "
                    + "subRslts 는 이 실행에 이어 돈 연쇄 실행이다. M0604 권한이 없으면 403.")
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0604')")
    @GetMapping("/executions")
    public PageResponse<BatchOpsExcnVO> executions(
            @RequestParam(name = "jobNm", required = false) String jobNm,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 는 0 이상이어야 합니다: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size 는 1 ~ " + MAX_PAGE_SIZE + " 범위여야 합니다: " + size);
        }
        String job = blankToNull(jobNm);
        if (job != null && job.length() > JOB_NM_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobNm 은 " + JOB_NM_MAX + "자 이하여야 합니다");
        }
        String st = blankToNull(status);
        if (st != null && !STATUSES.contains(st)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 가 올바르지 않습니다: " + st);
        }

        BatchOpsExcnSearchVO search = new BatchOpsExcnSearchVO();
        search.setJobNm(job);
        search.setStatus(st);
        search.setSize(size);
        // offset 은 long 으로 계산한다 — page 가 크면 page * size 가 int 범위를 넘는다
        search.setOffset((long) page * size);
        return batchOpsService.selectExecutions(search, page);
    }

    @Operation(summary = "배치 실행 상세",
            description = "이력 지표 전체 + 파라미터 + Step 별 읽기/쓰기/필터/스킵/커밋/롤백·종료 메시지(마스킹 · 전체). "
                    + "실행 중이면 progress(현재 Step·완료 Step 수·현재 Step 읽기/쓰기 수)를 싣는다. "
                    + "없는 실행이면 404. M0604 권한이 없으면 403.")
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0604')")
    @GetMapping("/executions/{id}")
    public BatchOpsExcnDetailVO execution(@PathVariable("id") long id) {
        return batchOpsService.selectExecution(id);
    }

    // ================================================================ 명령 프록시

    @Operation(summary = "배치 실행",
            description = "배치에 비동기 실행을 요청한다 → 202 {jobExecutionId, jobNm, status}. 실행자는 로그인 user_id. "
                    + "409 JOB_ALREADY_RUNNING · 400(파라미터) · 404 JOB_NOT_FOUND 는 배치 본문 그대로. "
                    + "배치 미설정·불통은 503. M0604 권한이 없으면 403.")
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0604')")
    @PostMapping("/jobs/{jobNm}/executions")
    public ResponseEntity<JsonNode> execute(@AuthenticationPrincipal OidcUser user,
            @PathVariable("jobNm") String jobNm,
            @RequestBody(required = false) ExecuteRequest request) {
        String job = requireJobNm(jobNm);
        String userId = currentUser.resolveUserId(user);
        return relay(batchOpsClient.execute(job, request == null ? null : request.params(), userId));
    }

    @Operation(summary = "배치 스케줄 변경",
            description = "cron·사용여부를 바꾼다 → 200 {state, registered, blockedByEnv, nextRuns}. 수정자는 로그인 user_id. "
                    + "400(cron·useYn) · 404 는 배치 본문 그대로. M0604 권한이 없으면 403.")
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0604')")
    @PutMapping("/schedules/{jobNm}")
    public ResponseEntity<JsonNode> updateSchedule(@AuthenticationPrincipal OidcUser user,
            @PathVariable("jobNm") String jobNm,
            @RequestBody(required = false) ScheduleRequest request) {
        String job = requireJobNm(jobNm);
        String userId = currentUser.resolveUserId(user);
        ScheduleRequest req = request == null ? new ScheduleRequest(null, null) : request;
        return relay(batchOpsClient.updateSchedule(job, req.cronExpr(), req.useYn(), userId));
    }

    @Operation(summary = "배치 실행 중지",
            description = "현재 묶음(청크)까지 처리하고 멈춘다 → 202 {…, zombie}. 이어서 도는 잡은 실행되지 않는다. "
                    + "409 NOT_RUNNING · 404 는 배치 본문 그대로. M0604 권한이 없으면 403.")
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0604')")
    @PostMapping("/executions/{id}/stop")
    public ResponseEntity<JsonNode> stop(@AuthenticationPrincipal OidcUser user, @PathVariable("id") long id) {
        return relay(batchOpsClient.stop(id, currentUser.resolveUserId(user)));
    }

    @Operation(summary = "배치 실행 실패 처리",
            description = "실제로 돌지 않는 실행(좀비)을 FAILED 로 정리한다 → 200. "
                    + "409 STILL_RUNNING·NOT_RUNNING · 404 는 배치 본문 그대로. M0604 권한이 없으면 403.")
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0604')")
    @PostMapping("/executions/{id}/abandon")
    public ResponseEntity<JsonNode> abandon(@AuthenticationPrincipal OidcUser user, @PathVariable("id") long id) {
        return relay(batchOpsClient.abandon(id, currentUser.resolveUserId(user)));
    }

    @Operation(summary = "배치 실행 텍스트 로그",
            description = "tailKb=N(마지막 N KB, 1~1024) 또는 fromByte=N(증분). 응답은 {content, nextByte, jobRunning} — "
                    + "nextByte 는 원본 파일 바이트 기준(다음 fromByte), jobRunning=false 면 폴링을 멈춘다. "
                    + "404 EXECUTION_NOT_FOUND·LOG_NOT_FOUND · 400 은 배치 본문 그대로. M0604 권한이 없으면 403.")
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0604')")
    @GetMapping("/executions/{id}/log")
    public ResponseEntity<?> executionLog(@PathVariable("id") long id,
            @RequestParam(name = "tailKb", required = false) Integer tailKb,
            @RequestParam(name = "fromByte", required = false) Long fromByte) {
        Upstream up = batchOpsClient.log(id, tailKb, fromByte);
        if (!up.is2xx()) {
            return relay(up);
        }
        String content = up.body() == null ? "" : new String(up.body(), StandardCharsets.UTF_8);
        return ResponseEntity.ok(new BatchOpsLogVO(content,
                parseLong(up.headers().getFirst(BatchOpsClient.HEADER_NEXT_BYTE)),
                parseBool(up.headers().getFirst(BatchOpsClient.HEADER_JOB_RUNNING))));
    }

    // ---------------------------------------------------------------- 프록시 헬퍼

    private static String requireJobNm(String jobNm) {
        if (jobNm == null || !JOB_NM.matcher(jobNm).matches()) {
            throw new BatchOpsProxyException(HttpStatus.BAD_REQUEST, BatchOpsProxyException.INVALID_PATH, "잡 이름 형식이 잘못됐습니다");
        }
        return jobNm;
    }

    /**
     * 배치 응답을 상태 그대로 옮긴다(2xx·400·404·409 — 그 밖은 클라이언트가 이미 503 으로 끊었다).
     * 본문은 UTF-8 JSON 으로 읽는다. 🔴 2xx 인데 JSON 이 아니면 503(예상 밖 응답) · 오류인데 JSON 이 아니면 {@code {code, message}} 를 만든다.
     */
    private ResponseEntity<JsonNode> relay(Upstream up) {
        JsonNode body = readJson(up.body());
        if (body == null) {
            if (up.is2xx()) {
                log.warn("[batch-ops] 배치 성공 응답이 JSON 이 아니다 — status={}", up.status());
                throw BatchOpsProxyException.unavailable();
            }
            Map<String, String> synth = new LinkedHashMap<>();
            synth.put("code", "BATCH_REJECTED");
            synth.put("message", "배치가 요청을 거절했습니다 (HTTP " + up.status() + ")");
            body = objectMapper.valueToTree(synth);
        }
        return ResponseEntity.status(up.status()).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private JsonNode readJson(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            // 🔴 바이트를 UTF-8 문자열로 먼저 고정한다 — 헤더 charset 표기에 기대지 않는다
            JsonNode node = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException e) {
            // JSON 이 아닌 본문은 "본문 없음" 으로 본다 — 호출부가 상태코드로 판정한다 (4.2)
            log.debug("[batch-ops] 배치 응답 본문이 JSON 이 아님 — null 로 처리: 사유={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static Long parseLong(String v) {
        try {
            return v == null ? null : Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            // 숫자가 아닌 값은 "값 없음" 으로 본다 (4.2)
            log.debug("[batch-ops] 숫자 변환 실패 — null 로 처리: value={}", v);
            return null;
        }
    }

    private static Boolean parseBool(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return "true".equalsIgnoreCase(t) ? Boolean.TRUE : "false".equalsIgnoreCase(t) ? Boolean.FALSE : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
