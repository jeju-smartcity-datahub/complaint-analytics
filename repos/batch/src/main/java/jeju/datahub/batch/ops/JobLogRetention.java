package jeju.datahub.batch.ops;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.schedule.BatchScheduleRegistry;
import java.io.UncheckedIOException;

/**
 * 실행별 로그 파일 보존 정리 — 기본 {@code batch.ops.log-retention-days: 90}일 (확정).
 *
 * <p>Log4j2 {@code DefaultRolloverStrategy} 의 Delete 는 롤오버가 일어날 때만 돌고 실행별 파일은 롤오버하지 않으므로
 * 앱이 직접 지운다. 기준은 파일 <b>마지막 수정 시각</b>(실행 종료 무렵)이다.
 *
 * <ul>
 *   <li><b>기동 시 1회</b> — {@link ApplicationReadyEvent} 에서 <b>별도 데몬 스레드</b>로(스케줄 스레드를 쓰지 않는다 —
 *       기동 직후 첫 스케줄과 겹쳐 밀어내지 않게).</li>
 *   <li><b>일 1회</b> — {@code batchTaskScheduler}(풀 1)에 cron 으로 건다. 🔴 {@code @Scheduled} 를 쓰지 않는다
 *       (0건 유지). 🔴 그 풀은 1스레드라 이 작업이 도는 동안 스케줄 잡이 기다린다 — 그래서 시각은 잡 스케줄(02:00~04:00)과
 *       겹치지 않는 {@code batch.ops.log-cleanup-cron}(기본 05:30)이고, 작업은 mtime 비교·삭제만 한다(파일을 읽지 않는다).</li>
 * </ul>
 * 🔴 지금 돌고 있는 실행의 파일은 건너뛴다({@link RunningJobRegistryListener}) · 심볼릭 링크는 따라가지 않는다 ·
 * 보존 일수가 1 미만이면 정리하지 않는다(설정 실수로 전량 삭제 방지).
 */
@Component
public class JobLogRetention {

    private static final Logger log = LoggerFactory.getLogger(JobLogRetention.class);

    private final JobLogService logService;
    private final RunningJobRegistryListener runningRegistry;
    private final TaskScheduler taskScheduler;
    private final String logDir;
    private final int retentionDays;
    private final String cleanupCron;
    private final Clock clock;

    @Autowired
    public JobLogRetention(JobLogService logService, RunningJobRegistryListener runningRegistry,
            @Qualifier("batchTaskScheduler") TaskScheduler taskScheduler,
            @Value("${batch.ops.log-dir:logs}") String logDir,
            @Value("${batch.ops.log-retention-days:90}") int retentionDays,
            @Value("${batch.ops.log-cleanup-cron:0 30 5 * * *}") String cleanupCron) {
        this(logService, runningRegistry, taskScheduler, logDir, retentionDays, cleanupCron, Clock.systemDefaultZone());
    }

    JobLogRetention(JobLogService logService, RunningJobRegistryListener runningRegistry, TaskScheduler taskScheduler,
            String logDir, int retentionDays, String cleanupCron, Clock clock) {
        this.logService = logService;
        this.runningRegistry = runningRegistry;
        this.taskScheduler = taskScheduler;
        this.logDir = logDir;
        this.retentionDays = retentionDays;
        this.cleanupCron = cleanupCron;
        this.clock = clock;
    }

    /** 기동 직후 — 경로 일치 확인 1줄 · 정리 1회(별도 스레드) · 일 1회 등록. 🔴 어느 것도 기동을 죽이지 않는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        logPathCheck();
        Thread init = new Thread(this::cleanupSafely, "job-log-cleanup-init");
        init.setDaemon(true);
        init.start();
        try {
            CronExpression.parse(cleanupCron);
            taskScheduler.schedule(this::cleanupSafely, new CronTrigger(cleanupCron, BatchScheduleRegistry.ZONE));
            log.info("[job-log] cleanup registered cron='{}' zone={} retentionDays={}",
                    cleanupCron, BatchScheduleRegistry.ZONE, retentionDays);
        } catch (IllegalArgumentException e) {
            log.error("[job-log] cleanup invalid-cron cron='{}' — 일 1회 정리를 등록하지 않는다: {}", cleanupCron, e.getClass().getSimpleName());
            log.debug("[job-log] cleanup invalid-cron 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만
        }
    }

    /** 🔴 {@code batch.ops.log-dir} 와 Log4j2 {@code LOG_PATH} 가 같은 디렉터리인지 — 갈리면 파일이 있어도 조회 API 가 404 다. */
    void logPathCheck() {
        Path ours = Paths.get(logDir).toAbsolutePath().normalize();
        String log4jValue = null;
        try {
            if (LogManager.getContext(false) instanceof LoggerContext ctx) {
                String v = ctx.getConfiguration().getStrSubstitutor().replace("${LOG_PATH}");
                log4jValue = v == null || v.contains("${") ? null : v;
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            // 시큐어코딩 4.3 — Log4j2 컨텍스트 미초기화(IllegalState) · 치환 실패(IllegalArgument).
            // 4.2 — 삼키는 사유를 남긴다 — 판정 불가로 두면 아래가 "확인 불가" 경로로 간다.
            log.debug("[job-log] LOG_PATH 를 읽지 못했다 ({})", e.getClass().getSimpleName());
            log4jValue = null;
        }
        if (log4jValue == null) {
            log.warn("[job-log] path-check log4j2 LOG_PATH 를 읽지 못했다 — log4j2-spring.xml 이 적용됐는지 확인. batch.ops.log-dir={}",
                    ours);
            return;
        }
        Path theirs = Paths.get(log4jValue).toAbsolutePath().normalize();
        if (ours.equals(theirs)) {
            log.info("[job-log] path-check match dir={} (batch.ops.log-dir = log4j2 LOG_PATH) jobLogs={}",
                    ours, logService.baseDir());
        } else {
            log.warn("[job-log] path-check MISMATCH batch.ops.log-dir={} log4j2 LOG_PATH={} — 로그 조회 API 가 파일을 못 찾는다"
                    + "(LOG_DIR 을 시스템 프로퍼티·환경변수 중 한쪽으로만 줬는지 확인)", ours, theirs);
        }
    }

    private void cleanupSafely() {
        try {
            cleanup();
        } catch (UncheckedIOException | SecurityException | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3 — 파일 순회·삭제의 비검사 예외(UncheckedIO·Security·InvalidPath=IllegalArgument)·상태 예외.
            // 그 밖의 예외도 다음 정리를 죽이지 않는다(반복 작업 기본 ErrorHandler 가 LOG_AND_SUPPRESS).
            log.error("[job-log] cleanup failed", e);
        }
    }

    /** @return 지운 파일 수 */
    public int cleanup() {
        if (retentionDays < 1) {
            log.warn("[job-log] cleanup skipped retentionDays={} — 1 미만이면 정리하지 않는다", retentionDays);
            return 0;
        }
        Path base = logService.baseDir();
        if (!Files.isDirectory(base)) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        AtomicInteger files = new AtomicInteger();
        AtomicInteger dirs = new AtomicInteger();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (attrs.isRegularFile() && name.endsWith(".log")
                            && attrs.lastModifiedTime().toInstant().isBefore(cutoff) && !running(name)) {
                        try {
                            Files.deleteIfExists(file);
                            files.incrementAndGet();
                        } catch (IOException e) {
                            log.warn("[job-log] cleanup delete-failed file={} — {}", file, e.getClass().getSimpleName());
                            log.debug("[job-log] cleanup delete-failed 상세 file={}", file, e);   // 4.1 — 예외 원문은 DEBUG 로만
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (!dir.equals(base) && isEmpty(dir)) {
                        try {
                            Files.deleteIfExists(dir);
                            dirs.incrementAndGet();
                        } catch (IOException e) {
                            // 그사이 새 실행이 파일을 만들었다 — 다음 정리에서 다시 본다. (4.2 — 삼키는 사유를 남긴다)
                            log.debug("[job-log] cleanup dir-skip dir={} ({})", dir, e.getClass().getSimpleName());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[job-log] cleanup walk-failed base={} — {}", base, e.getClass().getSimpleName());
            log.debug("[job-log] cleanup walk-failed 상세 base={}", base, e);   // 4.1 — 예외 원문은 DEBUG 로만
        }
        log.info("[job-log] cleanup done deletedFiles={} deletedDirs={} retentionDays={} base={}",
                files.get(), dirs.get(), retentionDays, base);
        return files.get();
    }

    private boolean running(String fileName) {
        try {
            return runningRegistry.isRunning(Long.parseLong(fileName.substring(0, fileName.length() - ".log".length())));
        } catch (NumberFormatException e) {
            // 4.2 — 삼키는 사유를 남긴다 — 실행 ID 이름이 아닌 파일은 실행 중이 아니다(정리 대상 판정은 날짜가 진다).
            log.debug("[job-log] 실행 ID 형식이 아닌 파일명 file={}", fileName);
            return false;
        }
    }

    private static boolean isEmpty(Path dir) {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            return !s.iterator().hasNext();
        } catch (IOException e) {
            // 4.2 — 삼키는 사유를 남긴다 — 비었는지 모르면 지우지 않는다(보수적).
            log.debug("[job-log] 디렉터리 확인 실패 — 지우지 않는다 dir={} ({})", dir, e.getClass().getSimpleName());
            return false;
        }
    }
}
