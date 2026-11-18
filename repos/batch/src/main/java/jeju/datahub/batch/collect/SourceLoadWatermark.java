package jeju.datahub.batch.collect;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 커밋된 원천 load_dt만 사용한다. null이면 첫 수집이며 하한 없이 조회한다. */
@Component
public class SourceLoadWatermark {
    private final JdbcTemplate jdbc;
    public SourceLoadWatermark(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public LocalDateTime complaint() {
        return jdbc.queryForObject("SELECT max(load_dt) FROM parking.tb_cvlcpt", LocalDateTime.class);
    }
    public LocalDateTime crackdown() {
        return jdbc.queryForObject("SELECT max(load_dt) FROM parking.tb_crdn_dsctn", LocalDateTime.class);
    }
}
