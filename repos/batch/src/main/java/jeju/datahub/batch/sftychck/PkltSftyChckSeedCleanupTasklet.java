package jeju.datahub.batch.sftychck;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 과거 임의 자료 시드 정리 — 2026-11-16 이후 수집 Job 에 연결하지 않는다.
 *
 * <p>같은 Job 의 앞 Step({@value PkltSftyChckFetchJobConfig#STEP_NAME})이 <b>COMPLETED 이고 적재({@code writeCount})가
 * 1건 이상</b>일 때만, 남은 시드 행({@code rgtr_id} 가 시드 등록자)을 지운다. 원천과 같은 키였던 시드 행은 Writer 가 이미
 * {@code BATCH} 로 인수했으므로(D3 ②) 여기서 지워지는 것은 <b>원천에 없는 키의 시드</b>뿐이다.
 *
 * <ul>
 *   <li>🔴 <b>판정은 {@code writeCount} 다 — {@code readCount} 가 아니다.</b> 읽기 기준이면 전량 스킵
 *       ({@code PKLT_NOT_FOUND})인 날에도 시드를 지워 투자우선순위 보드가 빈다.</li>
 *   <li>🔴 <b>표 비우기 명령·등록자 조건 없는 DELETE 를 쓰지 않는다</b> — 실자료(BATCH·화면 입력)까지 날아간다.</li>
 *   <li>앞 Step 결과는 {@code PblpkltSoftDeleteMarkingTasklet} 처럼 {@code JobExecution.getStepExecutions()} 에서 읽는다.
 *       {@code .next()} 가 이미 COMPLETED 를 보장하지만 두 번째 겹으로 상태도 본다.</li>
 * </ul>
 *
 * <h2>🔴 테스트 주입점 — {@code pkltIdScopeSql}</h2>
 * 운영 경로(빈 생성자)는 <b>필터가 없다</b>. Job 통합 테스트는 이 DELETE 가 로컬 시드 4,514행을 지우지 않도록
 * 패키지 전용 생성자로 {@code pklt_id} 범위 부분질의(예: 픽스처 {@code origin_pk} 접두의 주차장)를 넘긴 인스턴스를
 * {@code @Primary} 픽스처 빈으로 등록한다(선례 {@code CrackdownFetchJobTest} 의 Tasklet 교체).
 * 그 값은 SQL 조각이라 <b>테스트 코드의 상수만</b> 넘긴다 — 외부 입력이 닿는 경로가 없다.
 */
@Component
public class PkltSftyChckSeedCleanupTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(PkltSftyChckSeedCleanupTasklet.class);

    /** 🔴 시드 등록자로 좁힌 DELETE — 이 조건을 빼지 않는다. */
    static final String DELETE_SEED_SQL = "DELETE FROM parking.tb_pklt_sfty_chck WHERE rgtr_id = 'admin'";

    private final JdbcTemplate jdbc;

    /** 테스트 전용 {@code pklt_id} 범위 부분질의 — 운영은 {@code null}(필터 없음). */
    private final String pkltIdScopeSql;

    @Autowired
    public PkltSftyChckSeedCleanupTasklet(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    /** 🔴 테스트 전용 — 삭제 대상을 {@code pklt_id IN (pkltIdScopeSql)} 로 좁힌다. */
    PkltSftyChckSeedCleanupTasklet(JdbcTemplate jdbc, String pkltIdScopeSql) {
        this.jdbc = jdbc;
        this.pkltIdScopeSql = pkltIdScopeSql;
    }

    /** 이 인스턴스에 걸린 테스트 범위 — 운영 빈이면 {@code null}. 테스트가 교체 여부를 단언하는 데 쓴다. */
    String pkltIdScopeSql() {
        return pkltIdScopeSql;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        StepExecution self = chunkContext.getStepContext().getStepExecution();
        StepExecution fetch = self.getJobExecution().getStepExecutions().stream()
                .filter(s -> PkltSftyChckFetchJobConfig.STEP_NAME.equals(s.getStepName()))
                .findFirst().orElse(null); // 5.1 — Optional.get() 대신 null 검사 뒤 사용(TSK-4336)

        if (fetch == null || fetch.getStatus() != BatchStatus.COMPLETED) {
            log.warn("안전점검 시드 정리 생략 — 수집 Step({})이 정상 종료하지 않았습니다(상태 {}). 시드 유지.",
                    PkltSftyChckFetchJobConfig.STEP_NAME, fetch == null ? null : fetch.getStatus());
            return RepeatStatus.FINISHED;
        }

        long written = fetch.getWriteCount();
        if (written <= 0) {
            log.info("안전점검 시드 정리 생략 — 실자료 0건 — 시드 유지 (읽음 {} · 스킵 {})",
                    fetch.getReadCount(), fetch.getProcessSkipCount());
            return RepeatStatus.FINISHED;
        }

        String sql = pkltIdScopeSql == null
                ? DELETE_SEED_SQL
                : DELETE_SEED_SQL + " AND pklt_id IN (" + pkltIdScopeSql + ")";
        int deleted = jdbc.update(sql);
        self.getExecutionContext().putInt("sftychck.seedDeletedCount", deleted);
        log.info("안전점검 시드 정리 완료 — 실자료 적재 {}건, 원천에 없는 임의 자료 시드 {}건 삭제{}",
                written, deleted, pkltIdScopeSql == null ? "" : " (테스트 범위 한정)");
        return RepeatStatus.FINISHED;
    }
}
