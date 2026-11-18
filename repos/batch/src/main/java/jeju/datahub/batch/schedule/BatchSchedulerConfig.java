package jeju.datahub.batch.schedule;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import jeju.datahub.batch.crackdown.CrackdownSyncScheduler;
import jeju.datahub.batch.acdnt.PkltAcdntSyncScheduler;
import jeju.datahub.batch.ops.RunningJobRegistryListener;
import jeju.datahub.batch.cvlcpt.ComplaintSyncScheduler;
import jeju.datahub.batch.pblpklt.PblpkltSyncScheduler;
import jeju.datahub.batch.rptp.ReportAutoCrtScheduler;
import jeju.datahub.batch.sftychck.PkltSftyChckSyncScheduler;
import jeju.datahub.batch.utztn.PkltUtztnSyncScheduler;
import jeju.datahub.batch.zonenm.ZoneNmPrefillScheduler;

/**
 * 동적 스케줄러 배선 — 전용 {@link ThreadPoolTaskScheduler} + 잡명 → 실행 메서드 매핑.
 *
 * <h2>왜 전용 스케줄러 빈인가</h2>
 * Boot 자동 {@code TaskScheduler} 는 {@code @EnableScheduling} 이 있을 때만 생기는데, 이 Step 에서
 * {@code @Scheduled}·{@code @EnableScheduling} 을 전부 걷었다.
 *
 * <h2>🔴 풀 크기는 1이다 — 종전 동작 그대로</h2>
 * 종전 Boot 기본 스케줄러도 1스레드였고, 잡 사이의 <b>선후 관계가 이 직렬 실행에 기대고 있다</b> —
 * 02:30 보고서·03:10 구역명은 02:00 민원 수집이 끝난 뒤, 03:00 이용패턴·04:00 안전점검은 분기 첫날 02:00
 * 주차장 수집(→ 이용패턴 연쇄)이 끝난 뒤에 돈다. 풀을 늘리면 이 순서가 깨져 덜 적재된 데이터를 읽거나 같은 달을
 * 두 실행이 동시에 upsert 한다(검증에서 확인 — 풀 4 안을 되돌렸다). 늘리려면 의존 잡끼리 잠금을 먼저 넣는다.
 * 수동 실행(운영 API)은 이 스케줄러가 아니라 별도 비동기 런처라 이 직렬화의 영향을 받지 않는다.
 *
 * <h2>매핑</h2>
 * 기존 스케줄러는 {@code @ConditionalOnProperty(*.sync.scheduled)} 라 env=false 면 빈이 없다.
 * 🔴 그래서 {@link ObjectProvider} 로 받는다 — 직접 주입하면 env 를 끄는 순간 <b>기동이 실패</b>한다.
 * 빈 부재는 {@link ScheduleTarget#action()} {@code null} = env 차단으로 레지스트리가 다룬다.
 * 🔴 env 키 문자열은 각 클래스의 {@code @ConditionalOnProperty(name=…)} 와 같아야 한다(사유 로그용).
 */
@Configuration
public class BatchSchedulerConfig {

    /** 스케줄 잡을 한 줄로 돌린다 — 종전 Boot 기본(1스레드)과 같다. 🔴 늘리지 말 것(위 클래스 주석). */
    public static final int POOL_SIZE = 1;

    @Bean(name = "batchTaskScheduler")
    public ThreadPoolTaskScheduler batchTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("batch-schdl-");
        // 취소된 future 를 큐에서 바로 뺀다 — reschedule 이 반복돼도 큐에 시체가 쌓이지 않는다.
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    public BatchScheduleRegistry batchScheduleRegistry(
            ThreadPoolTaskScheduler batchTaskScheduler,
            BatchScheduleRepository repository,
            Environment environment,
            ObjectProvider<ComplaintSyncScheduler> complaint,
            ObjectProvider<PblpkltSyncScheduler> pblpklt,
            ObjectProvider<PkltUtztnSyncScheduler> utztn,
            ObjectProvider<ReportAutoCrtScheduler> rptp,
            ObjectProvider<PkltSftyChckSyncScheduler> sftychck,
            ObjectProvider<ZoneNmPrefillScheduler> zonenm,
            ObjectProvider<CrackdownSyncScheduler> crackdown,
            ObjectProvider<PkltAcdntSyncScheduler> acdnt,
            RunningJobRegistryListener runningJobRegistry) {
        List<ScheduleTarget> targets = List.of(
                target("complaintFetchJob", "complaint.sync.scheduled", complaint, ComplaintSyncScheduler::syncMonthly),
                target("pblpkltFetchJob", "pblpklt.sync.scheduled", pblpklt, PblpkltSyncScheduler::syncQuarterly),
                target("pkltUtztnFetchJob", "utztn.sync.scheduled", utztn, PkltUtztnSyncScheduler::syncMonthly),
                target("reportAutoCrtJob", "rptp.sync.scheduled", rptp, ReportAutoCrtScheduler::syncDaily),
                target("pkltSftyChckFetchJob", "sftychck.sync.scheduled", sftychck,
                        PkltSftyChckSyncScheduler::syncSemiannual),
                target("zoneNmPrefillJob", "zonenm.sync.scheduled", zonenm, ZoneNmPrefillScheduler::prefillDaily),
                // 🔴 단속은 env 차단 스위치가 없다 — 빈이 항상 있고 표 값만 따른다.
                target("crackdownFetchJob", null, crackdown, CrackdownSyncScheduler::fetch),
                target("pkltAcdntFetchJob", "acdnt.sync.scheduled", acdnt, PkltAcdntSyncScheduler::syncMonthly));
        // 같은 잡이 운영 API·HTTP 트리거로 이미 돌고 있으면 스케줄 실행을 건너뛴다.
        return new BatchScheduleRegistry(batchTaskScheduler, repository, environment, targets,
                runningJobRegistry::isJobRunning);
    }

    private static <T> ScheduleTarget target(String jobNm, String envKey, ObjectProvider<T> provider,
            Consumer<T> method) {
        T bean = provider.getIfAvailable();
        return new ScheduleTarget(jobNm, envKey, bean == null ? null : () -> method.accept(bean));
    }
}
