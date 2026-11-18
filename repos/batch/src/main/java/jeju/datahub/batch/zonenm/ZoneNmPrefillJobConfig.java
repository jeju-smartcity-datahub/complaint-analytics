package jeju.datahub.batch.zonenm;

import jeju.datahub.batch.ops.JobLogMdcListener;
import jeju.datahub.batch.ops.RunningJobRegistryListener;
import java.time.ZoneId;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code zoneNmPrefillJob} 조립 — 생활권 격자 구역명 캐시({@code tb_zone_nm_cache})를 미리 채운다.
 *
 * <pre>
 * Step zoneNmPrefillStep (Tasklet 1개)
 *   크기마다(gridSz 파라미터 · 없으면 1000→500→250→100)
 *     POST {backend}/api/internal/zone-names/prefill?gridSz=&limit=   (X-API-Key)
 *     remainingCnt == 0 → 다음 크기 · 직전보다 줄지 않음 → 그 크기 중단 + ERROR
 * </pre>
 *
 * <p>🔴 배치는 구역명을 짓지 않는다 — 이름 짓기·캐시 저장은 백엔드 {@code ZoneNameResolver} 한 곳이다
 * (보고서 자동 생성과 같은 모양). 배치가 DB 에 쓰는 것은 Spring Batch 메타와 실행 이력뿐이다.
 * 실패 갈래는 {@link ZoneNmPrefillTasklet} javadoc.
 */
@Configuration
public class ZoneNmPrefillJobConfig {

    /** 배치 잡 이름 — 트리거·스케줄러·이력 조회가 쓴다. */
    public static final String JOB_NAME = "zoneNmPrefillJob";

    static final String STEP_NAME = "zoneNmPrefillStep";

    /** 스케줄 시간대. */
    public static final String ZONE = "Asia/Seoul";

    public static final ZoneId SEOUL = ZoneId.of(ZONE);

    @Bean
    public Job zoneNmPrefillJob(JobRepository jobRepository,
                                Step zoneNmPrefillStep,
                                ZoneNmPrefillHistoryListener historyListener,
                                RunningJobRegistryListener runningJobRegistry) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // 실행별 로그 파일 MDC 는 체인 맨 앞(beforeJob 가장 먼저 — 다른 리스너 로그까지 파일에 담는다)
                .listener(JobLogMdcListener.INSTANCE)
                .listener(historyListener)
                // 실행 레지스트리는 리스너 체인 맨 뒤 — afterJob 이 역순이라 가장 먼저 불려 앞 리스너 예외에도 해제된다.
                .listener(runningJobRegistry)
                .start(zoneNmPrefillStep)
                .build();
    }

    @Bean
    public Step zoneNmPrefillStep(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  ZoneNmPrefillTasklet zoneNmPrefillTasklet) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(zoneNmPrefillTasklet, transactionManager)
                .build();
    }

    /**
     * 🔴 {@code @StepScope} — Tasklet 이 진행 상태를 필드에 들고 있어 실행마다 새 인스턴스여야 한다.
     *
     * @param gridSz         Job 파라미터(선택). 없으면 전 크기
     * @param limit          호출 1회당 처리 상한(백엔드 1~200). 읽기 타임아웃 120s 안에 끝나야 한다
     * @param callIntervalMs 호출 사이 대기 — VWorld 를 몰아치지 않는다
     */
    @Bean
    @StepScope
    public ZoneNmPrefillTasklet zoneNmPrefillTasklet(
            BackendZoneNmClient client,
            @Value("#{jobParameters['gridSz']}") Long gridSz,
            @Value("${batch.zone-nm.limit:100}") int limit,
            @Value("${batch.zone-nm.call-interval-ms:200}") long callIntervalMs) {
        return new ZoneNmPrefillTasklet(client, gridSz, limit, callIntervalMs);
    }
}
