package jeju.datahub.batch.schedule;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

import jeju.datahub.batch.ops.JobLogMdcListener;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * DB 기반 동적 스케줄러 — {@code batch.tb_btch_schdl} 이 cron 의 정본이다.
 *
 * <p>종전 6개 스케줄러의 {@code @Scheduled}(상수·yml cron)를 대신한다. 기동이 끝나면
 * ({@link ApplicationReadyEvent}) 표를 읽어 {@code use_yn='Y'} 인 잡만 전용 {@link TaskScheduler}
 * ({@link BatchSchedulerConfig#POOL_SIZE} 스레드)에 {@link CronTrigger} 로 건다. 각 잡의 실행 본문
 * (연쇄 포함)은 그대로 기존 스케줄러 클래스의 메서드다 — 여기서는 "언제 부르나" 만 정한다.
 *
 * <h2>env 상위 차단(D5)</h2>
 * {@code *.sync.scheduled=false} 면 기존 스케줄러 빈이 {@code @ConditionalOnProperty} 로 아예 없다.
 * {@link ScheduleTarget#action()} 이 {@code null} 로 오며, 표 값과 무관하게 등록하지 않고
 * {@code [schedule] blocked job=… reason=env <키>=<값>} 1줄을 남긴다(기동은 살린다).
 * 🔴 단속({@code crackdownFetchJob})은 차단 스위치가 없다 — 표 값만 따른다.
 *
 * <h2>로그 접두(🔴 운영 확인·검증이 이 문자열로 센다 — 바꾸지 않는다)</h2>
 * {@code [schedule] registered|unregistered|disabled|blocked|invalid-cron|no-target|no-row|skipped-running job=<잡>}.
 *
 * <h2>⚠️ 배치 인스턴스 1개 전제</h2>
 * 등록은 프로세스 메모리 안의 일이다. 배치 앱을 2개 이상 띄우면 <b>같은 잡이 인스턴스 수만큼 돈다</b>
 * (분산 락이 없다). 늘리려면 ShedLock 류 잠금을 먼저 넣어야 한다.
 *
 * <h2>⚠️ 동시성</h2>
 * 같은 잡은 겹치지 않는다 — {@link CronTrigger} 는 앞 실행이 끝난 뒤 다음 시각을 계산한다. 다른 잡끼리도
 * 겹치지 않는다 — 풀이 1({@link BatchSchedulerConfig#POOL_SIZE})이라 종전처럼 한 줄로 돈다(뒤 잡은 앞 잡이 끝날 때까지
 * 기다린다). 잡 사이 선후 관계가 이 직렬 실행에 기대므로 풀을 늘리지 않는다.
 */
public class BatchScheduleRegistry {

    private static final Logger log = LoggerFactory.getLogger(BatchScheduleRegistry.class);

    /** 스케줄 시간대 — 종전 스케줄러들의 {@code ZONE} 과 같은 값. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final TaskScheduler taskScheduler;
    private final BatchScheduleRepository repository;
    private final Environment environment;
    private final Map<String, ScheduleTarget> targets = new LinkedHashMap<>();
    private final Map<String, ScheduledFuture<?>> futures = new HashMap<>();
    /** 같은 잡이 이 프로세스에서 이미 돌고 있나 — 운영 API 수동 실행과 겹치면 스케줄 실행을 건너뛴다. */
    private final Predicate<String> jobRunning;

    public BatchScheduleRegistry(TaskScheduler taskScheduler, BatchScheduleRepository repository,
            Environment environment, List<ScheduleTarget> targets) {
        this(taskScheduler, repository, environment, targets, jobNm -> false);
    }

    /**
     * @param jobRunning 잡 이름 → 지금 이 프로세스에서 실행 중인가({@code RunningJobRegistryListener::isJobRunning}).
     *                   풀이 1이라 스케줄끼리는 겹치지 않고, 겹치는 상대는 운영 API·기존 HTTP 트리거의 실행이다
     */
    public BatchScheduleRegistry(TaskScheduler taskScheduler, BatchScheduleRepository repository,
            Environment environment, List<ScheduleTarget> targets, Predicate<String> jobRunning) {
        this.taskScheduler = taskScheduler;
        this.repository = repository;
        this.environment = environment;
        targets.forEach(t -> this.targets.put(t.jobNm(), t));
        this.jobRunning = jobRunning;
    }

    /** 스케줄 대상(표 7행과 같은 잡)인가 — 운영 API 가 "스케줄 불가 잡" 400 판정에 쓴다. */
    public boolean isTarget(String jobNm) {
        return targets.containsKey(jobNm);
    }

    /**
     * env 상위 차단(D5)인가 — 대상인데 실행 메서드가 없다(= 스케줄러 빈이 {@code @ConditionalOnProperty} 로 빠졌다).
     * 🔴 {@link #reschedule} 결과로는 알 수 없다 — {@code use_yn='N'} 이면 차단 판정보다 먼저 {@code DISABLED} 로 끝난다.
     */
    public boolean isBlockedByEnv(String jobNm) {
        ScheduleTarget target = targets.get(jobNm);
        return target != null && target.action() == null;
    }

    /** 차단 스위치 키(없으면 null — 단속). */
    public String envKey(String jobNm) {
        ScheduleTarget target = targets.get(jobNm);
        return target == null ? null : target.envKey();
    }

    /**
     * 기동 시 전 잡 등록. 🔴 표를 못 읽어도 기동을 죽이지 않는다 — 수동 트리거·헬스체크까지 함께
     * 죽으면 원인 파악이 어려워진다(ERROR 1줄 · 스케줄 0건으로 뜬다).
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void registerAll() {
        List<BatchSchedule> rows;
        try {
            rows = repository.findAll();
        } catch (DataAccessException e) {
            // 시큐어코딩 4.3 — 표 조회(JdbcTemplate) 실패만 삼킨다.
            log.error("[schedule] load-failed — batch.tb_btch_schdl 을 읽지 못해 스케줄을 하나도 등록하지 않았다", e);
            return;
        }
        Map<String, BatchSchedule> byJob = new LinkedHashMap<>();
        rows.forEach(r -> byJob.put(r.jobNm(), r));

        byJob.keySet().stream().filter(jobNm -> !targets.containsKey(jobNm))
                .forEach(jobNm -> log.warn("[schedule] no-target job={} — 표에는 있는데 실행 메서드가 없어 등록하지 않는다", jobNm));

        for (ScheduleTarget target : targets.values()) {
            BatchSchedule row = byJob.get(target.jobNm());
            if (row == null) {
                log.warn("[schedule] no-row job={} — batch.tb_btch_schdl 에 행이 없어 등록하지 않는다", target.jobNm());
                continue;
            }
            apply(target, row);
        }
    }

    /**
     * 한 잡을 다시 건다 — 기존 등록 취소 → 표 다시 읽기 → 등록 (운영 API 가 부른다).
     *
     * @return 다시 건 결과
     */
    public synchronized ScheduleState reschedule(String jobNm) {
        cancel(jobNm);
        ScheduleTarget target = targets.get(jobNm);
        if (target == null) {
            log.warn("[schedule] no-target job={} — 실행 메서드가 매핑돼 있지 않다", jobNm);
            return ScheduleState.NO_TARGET;
        }
        BatchSchedule row = repository.find(jobNm).orElse(null); // 5.1 — Optional.get() 대신 null 검사 뒤 사용
        if (row == null) {
            log.warn("[schedule] no-row job={} — batch.tb_btch_schdl 에 행이 없어 등록하지 않는다", jobNm);
            return ScheduleState.NO_ROW;
        }
        return apply(target, row);
    }

    /** 지금 등록돼 있나(취소·완료되지 않은 future 가 있나). */
    public synchronized boolean isRegistered(String jobNm) {
        ScheduledFuture<?> future = futures.get(jobNm);
        return future != null && !future.isCancelled();
    }

    private ScheduleState apply(ScheduleTarget target, BatchSchedule row) {
        String jobNm = target.jobNm();
        if (!row.inUse()) {
            log.info("[schedule] disabled job={} use_yn={} — 스케줄 중지(수동 실행은 가능)", jobNm, row.useYn());
            return ScheduleState.DISABLED;
        }
        if (target.action() == null) {
            String value = target.envKey() == null ? null : environment.getProperty(target.envKey());
            log.info("[schedule] blocked job={} reason=env {}={} — 스케줄러 빈이 없어 표 값과 무관하게 등록하지 않는다",
                    jobNm, target.envKey(), value);
            return ScheduleState.BLOCKED;
        }
        CronExpression cron;
        try {
            cron = CronExpression.parse(row.cronExpr());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.error("[schedule] invalid-cron job={} cron='{}' — 그 잡만 등록하지 않는다: {}",
                    jobNm, row.cronExpr(), e.getClass().getSimpleName());
            log.debug("[schedule] invalid-cron 상세 job={}", jobNm, e);   // 4.1 — 예외 원문은 DEBUG 로만
            return ScheduleState.INVALID_CRON;
        }
        Runnable action = target.action();
        ScheduledFuture<?> future = taskScheduler.schedule(() -> run(jobNm, action, jobRunning),
                new CronTrigger(row.cronExpr(), ZONE));
        futures.put(jobNm, future);
        log.info("[schedule] registered job={} cron='{}' zone={} next={}",
                jobNm, row.cronExpr(), ZONE, cron.next(ZonedDateTime.now(ZONE)));
        return ScheduleState.REGISTERED;
    }

    private void cancel(String jobNm) {
        ScheduledFuture<?> future = futures.remove(jobNm);
        if (future != null) {
            // 🔴 false — 실행 중인 잡을 인터럽트하지 않는다(중지는 JobOperator.stop 몫).
            future.cancel(false);
            log.info("[schedule] unregistered job={}", jobNm);
        }
    }

    /** 실행 메서드는 스스로 예외를 삼키지만, 놓친 런타임 예외가 다음 주기를 죽이지 않게 한 번 더 감싼다. */
    private static void run(String jobNm, Runnable action, Predicate<String> jobRunning) {
        if (jobRunning.test(jobNm)) {
            // 🔴 운영 API(비동기)·기존 HTTP 트리거로 같은 잡이 이미 돌고 있다 — 겹쳐 돌리면 같은 구간을 두 실행이
            //    동시에 upsert 한다. 이번 주기는 건너뛰고 다음 주기를 기다린다(연쇄도 함께 건너뛴다).
            log.warn("[schedule] skipped-running job={} — 같은 잡이 이 프로세스에서 이미 실행 중이라 이번 주기를 건너뛴다", jobNm);
            return;
        }
        log.info("[schedule] fired job={}", jobNm);
        try {
            new SyncTaskExecutor().execute(action);   // 시큐어코딩 7.2 — run() 직접 호출 대신 같은 스레드 실행기(동작 같음)
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3 — 실행 메서드가 놓칠 수 있는 저장소·인자·상태 예외. 그 밖의 예외도 다음 주기를 죽이지 않는다
            // (ThreadPoolTaskScheduler 반복 작업의 기본 ErrorHandler 가 LOG_AND_SUPPRESS 다).
            log.error("[schedule] run-failed job={}", jobNm, e);
        } finally {
            // 스케줄 스레드는 1개를 계속 재사용한다. 리스너 afterJob 해제가 앞 리스너 예외로 빠져도
            // 다음 주기·무관한 로그가 이전 실행 파일로 라우팅되지 않게 진입점에서 지운다(JobLogMdcListener 주석).
            JobLogMdcListener.clear();
        }
    }
}
