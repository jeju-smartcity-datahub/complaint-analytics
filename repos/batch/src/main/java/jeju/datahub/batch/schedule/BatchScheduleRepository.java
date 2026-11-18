package jeju.datahub.batch.schedule;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code batch.tb_btch_schdl} 읽기 (TSK-4337 Step 3).
 *
 * <p>배치 레포 관례대로 Spring JDBC 다(JPA/MyBatis 를 쓰지 않는다 — {@code rule_batch-project.md}).
 * 쓰기(스케줄 변경)는 Step 4 운영 API({@code PUT /api/batch/ops/schedules/{jobNm}})만 한다.
 */
@Repository
public class BatchScheduleRepository {

    private static final String SELECT = "SELECT job_nm, cron_expr, use_yn FROM batch.tb_btch_schdl";

    private static final RowMapper<BatchSchedule> MAPPER = (rs, i) -> new BatchSchedule(
            rs.getString("job_nm"), rs.getString("cron_expr"), rs.getString("use_yn"));

    private final JdbcTemplate jdbcTemplate;

    public BatchScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 전 행(잡 이름순). */
    public List<BatchSchedule> findAll() {
        return jdbcTemplate.query(SELECT + " ORDER BY job_nm", MAPPER);
    }

    /** 한 잡의 행. 없으면 빈 값. */
    public Optional<BatchSchedule> find(String jobNm) {
        return jdbcTemplate.query(SELECT + " WHERE job_nm = ?", MAPPER, jobNm).stream().findFirst();
    }

    /** 전 행 + 변경자·변경 시각(운영 카탈로그 표시용). */
    public List<Detail> findAllDetails() {
        return jdbcTemplate.query(
                "SELECT job_nm, cron_expr, use_yn, mdfr_id, mdfcn_dt FROM batch.tb_btch_schdl ORDER BY job_nm",
                (rs, i) -> {
                    Timestamp ts = rs.getTimestamp("mdfcn_dt");
                    return new Detail(new BatchSchedule(rs.getString("job_nm"), rs.getString("cron_expr"),
                            rs.getString("use_yn")), rs.getString("mdfr_id"),
                            ts == null ? null : ts.toLocalDateTime());
                });
    }

    /**
     * 스케줄 저장(TSK-4337 Step 4). 행이 없으면 넣는다 — 정본은 이 표라 env 차단 잡도 저장한다(D5).
     * {@code mdfcn_dt} 는 DB {@code now()} 다.
     */
    public void save(String jobNm, String cronExpr, String useYn, String mdfrId) {
        jdbcTemplate.update("""
                INSERT INTO batch.tb_btch_schdl (job_nm, cron_expr, use_yn, mdfr_id, mdfcn_dt)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (job_nm) DO UPDATE
                   SET cron_expr = EXCLUDED.cron_expr, use_yn = EXCLUDED.use_yn,
                       mdfr_id = EXCLUDED.mdfr_id, mdfcn_dt = EXCLUDED.mdfcn_dt""",
                jobNm, cronExpr, useYn, mdfrId);
    }

    /** 표 한 행 + 변경 기록. {@code mdfrId}·{@code mdfcnDt} 가 NULL 이면 초기값 그대로다. */
    public record Detail(BatchSchedule schedule, String mdfrId, LocalDateTime mdfcnDt) {
    }
}
