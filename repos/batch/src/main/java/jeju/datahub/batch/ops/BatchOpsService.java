package jeju.datahub.batch.ops;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import jeju.datahub.batch.history.ExcnSe;
import jeju.datahub.batch.ops.BatchJobCatalog.JobSpec;
import jeju.datahub.batch.ops.BatchJobCatalog.ParamSpec;
import jeju.datahub.batch.ops.BatchJobCatalog.ParamType;
import jeju.datahub.batch.schedule.BatchScheduleRegistry;
import jeju.datahub.batch.schedule.BatchScheduleRepository;
import jeju.datahub.batch.schedule.ScheduleState;

/**
 * 배치 운영 API 본문 — 카탈로그 · 비동기 실행 · 스케줄 변경 · 중지 · 실패 처리 (TSK-4337 Step 4 · D4·D5·D8).
 *
 * <h2>"실행 중" 의 두 기준</h2>
 * <ul>
 *   <li><b>DB</b>({@link JobExplorer#findRunningJobExecutions}) — 중복 실행 409 판정. 좀비({@code STARTED} 로 멈춘 실행)도
 *       여기 걸린다 — 그래서 409 에 그 ID 와 {@code zombie} 를 싣는다.</li>
 *   <li><b>이 프로세스</b>({@link RunningJobRegistryListener}) — 좀비 판정·abandon 허용 판정. DB 상태만으로는 좀비와
 *       진짜 실행을 구분할 수 없다.</li>
 * </ul>
 *
 * <h2>⚠️ 잡별 락의 한계</h2>
 * 확인 ~ 실행 사이 경합은 운영 API 안에서만 막는다. 스케줄러·기존 동기 트리거와의 동시 실행은 막지 못한다
 * (스케줄 쪽은 {@code [schedule] skipped-running} 으로 이 프로세스에서 이미 도는 잡을 건너뛴다).
 */
@Service
public class BatchOpsService {

    private static final Logger log = LoggerFactory.getLogger(BatchOpsService.class);

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** {@code tb_btch_excn_hstry.excutr_id}·{@code tb_btch_schdl.mdfr_id} 가 {@code VARCHAR(20)}. */
    private static final int USER_ID_MAX = 20;
    /** {@code tb_btch_schdl.cron_expr} 가 {@code VARCHAR(100)}. */
    private static final int CRON_MAX = 100;
    private static final int NEXT_RUNS = 3;

    private final BatchJobCatalog catalog;
    private final Map<String, Job> jobs;
    private final AsyncJobLauncher asyncLauncher;
    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;
    private final JobOperator jobOperator;
    private final RunningJobRegistryListener runningRegistry;
    private final BatchScheduleRepository scheduleRepository;
    private final BatchScheduleRegistry scheduleRegistry;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public BatchOpsService(BatchJobCatalog catalog, List<Job> jobs, AsyncJobLauncher asyncLauncher,
            JobExplorer jobExplorer, JobRepository jobRepository, JobOperator jobOperator,
            RunningJobRegistryListener runningRegistry, BatchScheduleRepository scheduleRepository,
            BatchScheduleRegistry scheduleRegistry) {
        this.catalog = catalog;
        this.jobs = jobs.stream().collect(Collectors.toMap(Job::getName, j -> j, (a, b) -> a));
        this.asyncLauncher = asyncLauncher;
        this.jobExplorer = jobExplorer;
        this.jobRepository = jobRepository;
        this.jobOperator = jobOperator;
        this.runningRegistry = runningRegistry;
        this.scheduleRepository = scheduleRepository;
        this.scheduleRegistry = scheduleRegistry;
    }

    // ───────────────────────────── 카탈로그 ─────────────────────────────

    /** {@code GET /api/batch/ops/jobs} — 카탈로그 순서 그대로 9개. */
    public List<Map<String, Object>> jobs() {
        Map<String, BatchScheduleRepository.Detail> schedules = scheduleRepository.findAllDetails().stream()
                .collect(Collectors.toMap(d -> d.schedule().jobNm(), d -> d, (a, b) -> a));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JobSpec spec : catalog.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobNm", spec.jobNm());
            row.put("jobKorNm", spec.jobKorNm());
            row.put("description", spec.description());
            row.put("countLabel", spec.countLabel());
            row.put("params", spec.params());
            row.put("schedulable", spec.schedulable());
            row.put("schedule", spec.schedulable() ? scheduleView(spec.jobNm(), schedules.get(spec.jobNm())) : null);
            row.put("chainNext", spec.chainNext());
            row.put("chainCondition", spec.chainCondition());
            row.put("steps", spec.steps());
            row.put("runningJobExecutionIds", runningRegistry.runningIds(spec.jobNm()).stream().sorted().toList());
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> scheduleView(String jobNm, BatchScheduleRepository.Detail detail) {
        Map<String, Object> view = new LinkedHashMap<>();
        boolean registered = scheduleRegistry.isRegistered(jobNm);
        view.put("cronExpr", detail == null ? null : detail.schedule().cronExpr());
        view.put("useYn", detail == null ? null : detail.schedule().useYn());
        view.put("mdfrId", detail == null ? null : detail.mdfrId());
        view.put("mdfcnDt", detail == null ? null : detail.mdfcnDt());
        view.put("registered", registered);
        view.put("blockedByEnv", scheduleRegistry.isBlockedByEnv(jobNm));
        view.put("envKey", scheduleRegistry.envKey(jobNm));
        view.put("nextRuns", registered && detail != null ? nextRuns(detail.schedule().cronExpr()) : List.of());
        return view;
    }

    // ───────────────────────────── 실행 ─────────────────────────────

    /**
     * {@code POST /api/batch/ops/jobs/{jobNm}/executions} — 비동기 실행. 반환은 202 본문.
     *
     * @throws BatchOpsException 404 미등록 잡 · 400 파라미터 · 409 실행 중
     */
    public Map<String, Object> execute(String jobNm, Map<String, String> params, String excutrId) {
        JobSpec spec = requireJob(jobNm);
        Job job = jobs.get(jobNm);
        if (job == null) {
            throw new BatchOpsException(HttpStatus.INTERNAL_SERVER_ERROR, "JOB_BEAN_MISSING",
                    "카탈로그에는 있는데 Job 빈이 없습니다: " + jobNm);
        }
        String executor = userId(excutrId, "excutrId");
        Map<String, String> clean = validateParams(spec, params);
        JobParametersBuilder builder;
        try {
            builder = ExcnSe.manual(build(jobNm, clean), executor);
        } catch (IllegalArgumentException e) {
            // 파라미터 조합 검증(단속 to 단독·뒤집힌 구간 등 — 단건 형식 검사는 validateParams 가 이미 했다) → 400.
            throw BatchOpsException.badRequest("INVALID_PARAM", e.getMessage());
        }

        Object lock = locks.computeIfAbsent(jobNm, k -> new Object());
        synchronized (lock) {
            Set<JobExecution> running = jobExplorer.findRunningJobExecutions(jobNm);
            if (!running.isEmpty()) {
                JobExecution first = running.stream().min(Comparator.comparing(JobExecution::getId)).orElseThrow();
                boolean zombie = !runningRegistry.isRunning(first.getId());
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("jobExecutionId", first.getId());
                extra.put("status", first.getStatus().name());
                extra.put("zombie", zombie);
                throw new BatchOpsException(HttpStatus.CONFLICT, "JOB_ALREADY_RUNNING",
                        "이미 실행 중입니다(실행 #" + first.getId() + ")"
                                + (zombie ? " — 이 프로세스에서 돌고 있지 않아 실패 처리(abandon) 대상입니다" : ""),
                        extra);
            }
            JobExecution execution;
            try {
                execution = asyncLauncher.run(job, builder.toJobParameters());
            } catch (JobExecutionException e) {
                throw new BatchOpsException(HttpStatus.INTERNAL_SERVER_ERROR, "LAUNCH_FAILED",
                        "실행을 시작하지 못했습니다: " + e.getClass().getSimpleName());
            }
            log.info("[ops] execute job={} jobExecutionId={} excutrId={} params={}",
                    jobNm, execution.getId(), executor, clean);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobExecutionId", execution.getId());
            body.put("jobNm", jobNm);
            body.put("status", execution.getStatus().name());
            return body;
        }
    }

    /** 화이트리스트 + 형식 검사. 빈 값은 "생략" 으로 본다(기본값 적용). */
    private static Map<String, String> validateParams(JobSpec spec, Map<String, String> params) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (params == null) {
            return clean;
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            ParamSpec ps = spec.param(e.getKey()).orElseThrow(() -> BatchOpsException.badRequest("INVALID_PARAM",
                    spec.jobNm() + " 에 없는 파라미터입니다: " + e.getKey()));
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (ps.type() == ParamType.DATE) {
                parseDate(ps.name(), value);
            } else if (ps.type() == ParamType.ENUM && !ps.options().contains(value)) {
                throw BatchOpsException.badRequest("INVALID_PARAM",
                        ps.name() + " 는 " + String.join("·", ps.options()) + " 중 하나여야 합니다: " + value);
            }
            clean.put(ps.name(), value);
        }
        return clean;
    }

    private static LocalDate parseDate(String name, String value) {
        if (value == null) {
            return null;
        }
        if (!DATE.matcher(value).matches()) {
            throw BatchOpsException.badRequest("INVALID_PARAM", name + " 는 yyyy-MM-dd 여야 합니다: " + value);
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw BatchOpsException.badRequest("INVALID_PARAM", name + " 가 실제 날짜가 아닙니다: " + value);
        }
    }

    /** 잡별 조립 — 기존 트리거와 같은 {@link JobParameterFactory} 메서드. */
    private static JobParametersBuilder build(String jobNm, Map<String, String> p) {
        return switch (jobNm) {
            case "complaintFetchJob" -> JobParameterFactory.complaintFetch(
                    p.get("mode"), p.get("targetDate"), p.get("from"), p.get("to"));
            case "riskAlertJudgeJob" -> JobParameterFactory.riskAlertJudge(parseDate("targetDate", p.get("targetDate")));
            case "riskAlertReceiverBackfillJob" -> JobParameterFactory.riskAlertReceiverBackfill(
                    parseDate("fromDt", p.get("fromDt")), parseDate("toDt", p.get("toDt")));
            case "crackdownFetchJob" -> JobParameterFactory.crackdownFetch(p.get("from"), p.get("to"));
            case "pkltAcdntFetchJob" -> JobParameterFactory.pkltAcdntFetch(p.get("from"), p.get("to"));
            case "reportAutoCrtJob" -> JobParameterFactory.reportAutoCrt(parseDate("baseYmd", p.get("baseYmd")));
            case "zoneNmPrefillJob" -> JobParameterFactory.zoneNmPrefill(
                    p.get("gridSz") == null ? null : Long.valueOf(p.get("gridSz")));
            default -> JobParameterFactory.base();
        };
    }

    // ───────────────────────────── 스케줄 ─────────────────────────────

    /**
     * {@code PUT /api/batch/ops/schedules/{jobNm}} — 표 저장 → 즉시 재등록 → 다음 실행 3회.
     *
     * <p>🔴 cron 검증은 여기서 먼저 한다 — 레지스트리는 env 차단 판정이 cron 검증보다 앞서라 차단 잡의 잘못된 cron 이
     * {@code BLOCKED} 로만 보인다. 🔴 env 차단 잡도 <b>표에는 저장</b>한다(정본은 표 — D5) · 응답 {@code blockedByEnv} 는
     * 레지스트리 결과가 아니라 대상 빈 부재로 따로 판정한다({@code use_yn='N'} 이면 결과가 {@code DISABLED} 라서).
     */
    public Map<String, Object> updateSchedule(String jobNm, String cronExpr, String useYn, String mdfrId) {
        JobSpec spec = requireJob(jobNm);
        if (!spec.schedulable() || !scheduleRegistry.isTarget(jobNm)) {
            throw BatchOpsException.badRequest("SCHEDULE_NOT_SUPPORTED",
                    jobNm + " 은 스케줄을 둘 수 없는 잡입니다(연쇄 전용 또는 수동 전용)");
        }
        if (!"Y".equals(useYn) && !"N".equals(useYn)) {
            throw BatchOpsException.badRequest("INVALID_USE_YN", "useYn 은 Y 또는 N 이어야 합니다: " + useYn);
        }
        String cron = cronExpr == null ? null : cronExpr.trim();
        if (cron == null || cron.isEmpty() || cron.length() > CRON_MAX) {
            throw BatchOpsException.badRequest("INVALID_CRON", "cronExpr 가 비었거나 너무 깁니다(최대 " + CRON_MAX + "자)");
        }
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            // 파서 메시지는 응답에 싣지 않는다(시큐어코딩 4.1 — TSK-4336) — 진단용 상세는 서버 로그에만.
            log.warn("[ops] invalid cron job={} cron='{}' — {}", jobNm, cron, e.getClass().getSimpleName());
            log.debug("[ops] invalid cron 상세 job={}", jobNm, e);   // 4.1 — 예외 원문은 DEBUG 로만 (TSK-4336)
            throw BatchOpsException.badRequest("INVALID_CRON",
                    "cron 형식이 잘못됐습니다(Spring 6필드 — 초 분 시 일 월 요일)");
        }
        String modifier = userId(mdfrId, "mdfrId");

        scheduleRepository.save(jobNm, cron, useYn, modifier);
        ScheduleState state = scheduleRegistry.reschedule(jobNm);
        boolean registered = state == ScheduleState.REGISTERED;
        log.info("[ops] schedule job={} cron='{}' useYn={} mdfrId={} state={}", jobNm, cron, useYn, modifier, state);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobNm", jobNm);
        body.put("cronExpr", cron);
        body.put("useYn", useYn);
        body.put("mdfrId", modifier);
        body.put("state", state.name());
        body.put("registered", registered);
        body.put("blockedByEnv", scheduleRegistry.isBlockedByEnv(jobNm));
        body.put("nextRuns", registered ? nextRuns(cron) : List.of());
        return body;
    }

    /** 표의 cron 으로 다음 3회(Asia/Seoul). */
    static List<OffsetDateTime> nextRuns(String cron) {
        CronExpression expr;
        try {
            expr = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            // 4.2 — 삼키는 사유를 남긴다(TSK-4336) — 표의 cron 이 깨졌으면 다음 실행 미리보기만 비운다(재등록 상태는 INVALID_CRON 으로 따로 내린다).
            log.debug("[ops] nextRuns 계산 불가 cron='{}' ({})", cron, e.getClass().getSimpleName());
            return List.of();
        }
        List<OffsetDateTime> out = new ArrayList<>();
        ZonedDateTime cursor = ZonedDateTime.now(BatchScheduleRegistry.ZONE);
        for (int i = 0; i < NEXT_RUNS; i++) {
            cursor = expr.next(cursor);
            if (cursor == null) {
                break;
            }
            out.add(cursor.toOffsetDateTime());
        }
        return out;
    }

    // ───────────────────────────── 중지 · 실패 처리 ─────────────────────────────

    /**
     * {@code POST /api/batch/ops/executions/{id}/stop} — 중지 요청(202). 실제 멈춤은 현재 청크 커밋 뒤다.
     *
     * @throws BatchOpsException 404 없는 실행 · 409 {@code NOT_RUNNING}(이미 끝남)
     */
    public Map<String, Object> stop(long jobExecutionId, String excutrId) {
        String requester = userId(excutrId, "excutrId");
        JobExecution execution = requireExecution(jobExecutionId);
        if (!execution.isRunning()) {
            throw new BatchOpsException(HttpStatus.CONFLICT, "NOT_RUNNING",
                    "이미 끝난 실행입니다(실행 #" + jobExecutionId + " · " + execution.getStatus() + ")",
                    Map.of("jobExecutionId", jobExecutionId, "status", execution.getStatus().name()));
        }
        boolean zombie = !runningRegistry.isRunning(jobExecutionId);
        if (execution.getStatus() != BatchStatus.STOPPING) {
            try {
                jobOperator.stop(jobExecutionId);
            } catch (NoSuchJobExecutionException e) {
                throw BatchOpsException.notFound("EXECUTION_NOT_FOUND", "없는 실행입니다: " + jobExecutionId);
            } catch (JobExecutionNotRunningException e) {
                throw new BatchOpsException(HttpStatus.CONFLICT, "NOT_RUNNING",
                        "이미 끝난 실행입니다(실행 #" + jobExecutionId + ")", Map.of("jobExecutionId", jobExecutionId));
            }
        }
        log.info("[ops] stop jobExecutionId={} job={} requester={} zombie={}", jobExecutionId,
                execution.getJobInstance().getJobName(), requester, zombie);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobExecutionId", jobExecutionId);
        body.put("status", BatchStatus.STOPPING.name());
        body.put("zombie", zombie);
        return body;
    }

    /**
     * {@code POST /api/batch/ops/executions/{id}/abandon} — 이 프로세스에서 돌지 않는 {@code STARTING}/{@code STARTED}/
     * {@code STOPPING} 실행을 {@code FAILED} 로 정리한다.
     *
     * <p>{@code JobOperator.abandon} 은 상태를 {@code ABANDONED} 로 만들고 실행 중 상태를 거부해 쓰지 않는다.
     * Job·실행 중 Step 을 {@link JobRepository} 로 직접 {@code FAILED} + {@code endTime} + 종료 메시지로 갱신한다.
     *
     * @throws BatchOpsException 404 없는 실행 · 409 {@code STILL_RUNNING}(실제로 돌고 있음) · 409 {@code NOT_RUNNING}(이미 끝남)
     */
    public Map<String, Object> abandon(long jobExecutionId, String excutrId) {
        String requester = userId(excutrId, "excutrId");
        JobExecution execution = requireExecution(jobExecutionId);
        String jobNm = execution.getJobInstance().getJobName();
        Object lock = locks.computeIfAbsent(jobNm, k -> new Object());
        synchronized (lock) {
            if (!execution.isRunning()) {
                throw new BatchOpsException(HttpStatus.CONFLICT, "NOT_RUNNING",
                        "이미 끝난 실행입니다(실행 #" + jobExecutionId + " · " + execution.getStatus() + ")",
                        Map.of("jobExecutionId", jobExecutionId, "status", execution.getStatus().name()));
            }
            if (runningRegistry.isRunning(jobExecutionId)) {
                throw new BatchOpsException(HttpStatus.CONFLICT, "STILL_RUNNING",
                        "이 프로세스에서 실제로 실행 중입니다(실행 #" + jobExecutionId + ") — 중지(stop)를 쓰세요",
                        Map.of("jobExecutionId", jobExecutionId, "status", execution.getStatus().name()));
            }
            String who = requester == null ? "unknown" : requester;
            String message = "운영 화면에서 실패 처리 — " + who;
            ExitStatus exit = new ExitStatus(ExitStatus.FAILED.getExitCode(), message);
            LocalDateTime now = LocalDateTime.now();
            for (StepExecution step : execution.getStepExecutions()) {
                if (step.getStatus().isRunning()) {
                    step.setStatus(BatchStatus.FAILED);
                    step.setExitStatus(exit);
                    step.setEndTime(now);
                    jobRepository.update(step);
                }
            }
            execution.setStatus(BatchStatus.FAILED);
            execution.setExitStatus(exit);
            execution.setEndTime(now);
            jobRepository.update(execution);
            log.warn("[ops] abandon jobExecutionId={} job={} requester={} — FAILED 로 정리", jobExecutionId, jobNm, who);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobExecutionId", jobExecutionId);
            body.put("jobNm", jobNm);
            body.put("status", BatchStatus.FAILED.name());
            body.put("exitMessage", message);
            return body;
        }
    }

    // ───────────────────────────── 공통 ─────────────────────────────

    private JobSpec requireJob(String jobNm) {
        return catalog.find(jobNm).orElseThrow(() -> BatchOpsException.notFound("JOB_NOT_FOUND",
                "카탈로그에 없는 잡입니다: " + jobNm));
    }

    private JobExecution requireExecution(long jobExecutionId) {
        JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);
        if (execution == null) {
            throw BatchOpsException.notFound("EXECUTION_NOT_FOUND", "없는 실행입니다: " + jobExecutionId);
        }
        return execution;
    }

    private static String userId(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > USER_ID_MAX) {
            throw BatchOpsException.badRequest("INVALID_" + name.toUpperCase(java.util.Locale.ROOT),
                    name + " 는 " + USER_ID_MAX + "자 이하여야 합니다");
        }
        return trimmed;
    }
}
