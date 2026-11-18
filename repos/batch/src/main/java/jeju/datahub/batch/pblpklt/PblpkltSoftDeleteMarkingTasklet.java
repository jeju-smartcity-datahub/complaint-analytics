package jeju.datahub.batch.pblpklt;

import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import jeju.datahub.batch.pblpklt.api.DhubPblpkltItemReader;

/**
 * 전량 수집이 완료된 뒤 이번 원천에 없는 주소의 주차장만 소멸로 표시한다.
 * 주소가 같은 기존 행은 모두 유지한다. 원천 ID 가 바뀌어도 소멸로 오인하지 않는다.
 * 빈 원천, 주소 결측, 중지 또는 실패한 수집으로는 소멸을 판정하지 않는다.
 */
public class PblpkltSoftDeleteMarkingTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(PblpkltSoftDeleteMarkingTasklet.class);

    static final String MARKING_SQL = """
            UPDATE parking.tb_pblpklt
               SET extsh_dt = now()
             WHERE extsh_dt IS NULL
               AND origin_pk IS NOT NULL
               AND pklt_addr IS NOT NULL
               AND pklt_addr NOT IN (:seenAddresses)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    /**
     * 🔁 2026-08-12 — set 을 직접 보관하지 않고 Reader 빈을 보관. execute() 시점에 다시
     * {@code getSeenAddresses()} 를 호출해야 Step 1 의 open() 으로 교체된 최신 set 을 본다
     * (Tasklet 빈은 Step 1 보다 먼저 wiring 되므로 그 시점엔 아직 emptySet).
     */
    private final DhubPblpkltItemReader reader;

    public PblpkltSoftDeleteMarkingTasklet(DataSource dataSource, DhubPblpkltItemReader reader) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.reader = reader;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // Step 1 이 끝난 시점의 최신 seen set 을 본다. (Tasklet 빈이 wire 된 시점엔 emptySet)
        Set<String> seen = reader.getSeenAddresses();

        // Spring 의 NamedParameterJdbcTemplate 은 빈 컬렉션을 IN (:...) 으로 넘기면
        // "unexpanded" 예외를 던진다. seen 이 비어 있으면 SQL 을 안전하게 no-op 으로 분기.
        if (seen == null || seen.isEmpty() || reader.hasUnaddressedRows()) {
            log.warn("pblpklt 소멸 마킹 생략 — 수신 주소가 없거나 주소가 누락된 원천 행이 있습니다. "
                    + "주소 집합이 완전하지 않아 기존 주차장을 유지합니다.");
            return RepeatStatus.FINISHED;
        }

        // 🔴 2026-08-24 — 원본 테이블이 사라지면서 이 UPDATE 의 대상이 **서비스 테이블**이 됐다.
        //    잘못 마킹하면 화면에서 멀쩡한 주차장이 사라진다. 수집이 온전했는지를 먼저 확인한다.
        //    앞 Step 이 쓰기에 실패했는데도 seen 만 채워져 있으면(부분 수집) 전량이 소멸로 몰릴 수
        //    있어서다. 앞 Step 의 종료 상태가 COMPLETED 가 아니면 손대지 않는다.
        boolean upstreamHealthy = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getStepExecutions().stream()
                .filter(step -> !step.getStepName().equals(
                        chunkContext.getStepContext().getStepExecution().getStepName()))
                .allMatch(step -> step.getStatus() == org.springframework.batch.core.BatchStatus.COMPLETED);
        if (!upstreamHealthy) {
            log.warn("pblpklt 소멸 마킹 생략 — 앞 Step 이 정상 종료하지 않았습니다. "
                    + "부분 수집분으로 마킹하면 멀쩡한 주차장이 소멸 처리된다.");
            return RepeatStatus.FINISHED;
        }

        MapSqlParameterSource params = new MapSqlParameterSource("seenAddresses", seen);
        int updated = jdbc.update(MARKING_SQL, params);

        // ExecutionContext 에 결과를 남겨두면 운영자가 StepExecution 로그에서 바로 본다.
        chunkContext.getStepContext().getStepExecution()
                .getExecutionContext().put("pblpklt.extshMarkedCount", updated);

        log.info("pblpklt 소멸 마킹 완료 — 수신 주소={}개, 그 외 기존 행 중 "
                        + "{}건을 소멸로 마킹", seen.size(), updated);
        return RepeatStatus.FINISHED;
    }

    /**
     * 가이드 3-3 의 "단일 인스턴스 전제" — 두 Job 인스턴스가 동시에 같은 분기 수집을 돌리면
     * 양쪽 set 이 합쳐지지 않아 같은 행을 "못 봤다" 로 잘못 판정할 수 있다. 이 set 자체가
     * 인메모리라 공유 채널이 없기 때문이다.
     */
    @SuppressWarnings("unused")  // API 가이드 본문 참조용으로만 남겨둔 마커 — 인스턴스 전제 명시.
    private static final String SCALEOUT_CAVEAT =
            "single-instance-only: seenAddresses 는 in-memory. 두 인스턴스 동시 실행 시 위험.";
}
