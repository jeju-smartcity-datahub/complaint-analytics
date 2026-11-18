package jeju.datahub.batch.acdnt;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import jeju.datahub.batch.acdnt.api.PkltAcdntOriginRow;
import jeju.datahub.batch.collect.SourceFingerprint;
import jeju.datahub.batch.collect.SourceTimestamp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 전용 임시 DB만 허용한다. 서비스 DB의 스키마/데이터에는 접근하지 않는다. */
@EnabledIfEnvironmentVariable(named = "ACDNT_TEST_DB_URL", matches = "jdbc:postgresql:.*?/jeju_acdnt_batch_test_[0-9]+")
class PkltAcdntDatabaseTest {
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private static TransactionTemplate tx;
    private static final LocalDateTime DATE = LocalDateTime.of(2026, 5, 6, 14, 55, 12, 123456000);

    @BeforeAll static void migrateExistingData() throws Exception {
        dataSource = new DriverManagerDataSource(System.getenv("ACDNT_TEST_DB_URL"),
                System.getenv("ACDNT_TEST_DB_USER"), System.getenv("ACDNT_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("""
                CREATE SCHEMA parking;
                CREATE SCHEMA batch;
                CREATE TABLE parking.tb_pblpklt(pklt_id bigint PRIMARY KEY, pklt_nm varchar(300), pklt_addr varchar(200));
                INSERT INTO parking.tb_pblpklt VALUES (9001, '노형제5', '노형동 3793');
                CREATE TABLE parking.tb_pklt_acdnt_hstry(
                    acdnt_id varchar(20) PRIMARY KEY, pklt_id bigint NOT NULL REFERENCES parking.tb_pblpklt,
                    acdnt_dt timestamp NOT NULL, svrt_cd varchar(30), acdnt_type varchar(100));
                INSERT INTO parking.tb_pklt_acdnt_hstry VALUES ('41', 9001, '2025-01-01', NULL, '기존');
                INSERT INTO parking.tb_pklt_acdnt_hstry VALUES ('SEED-test', 9001, '2099-01-01', NULL, '임의 사고');
                CREATE TABLE batch.tb_btch_schdl(job_nm varchar(100) PRIMARY KEY, cron_expr varchar(100), use_yn char(1));
                """);
        Path sql = Path.of("../../database/migrations/sql");
        String monthly = Files.readString(sql.resolve("109-CVLCPT-CRDN-MONTHLY-LOAD.sql"));
        int start = monthly.indexOf("CREATE OR REPLACE FUNCTION");
        int end = monthly.indexOf("$hash$;", start) + "$hash$;".length();
        jdbc.execute(monthly.substring(start, end));
        jdbc.execute(Files.readString(sql.resolve("114-PKLT-ACDNT-BATCH.sql")));
    }
    @BeforeEach void resetFixture() {
        jdbc.update("DELETE FROM parking.tb_pklt_acdnt_hstry WHERE acdnt_id <> '41'");
        jdbc.update("INSERT INTO parking.tb_pklt_acdnt_hstry(acdnt_id,pklt_id,acdnt_dt,acdnt_type) "
                + "VALUES ('SEED-test',9001,'2099-01-01','임의 사고')");
    }
    PkltAcdntRow row(long parkingId, String status) {
        var source = new PkltAcdntOriginRow("1", "노형 제5", "노형동 3793", DATE, null,
                "입구 볼라드 파손", "보수 완료", status, "{\"seq_no\":\"1\"}");
        return new PkltAcdntRow(parkingId, source,
                SourceFingerprint.hash(Long.toString(parkingId), SourceTimestamp.format(DATE), source.acdntType()));
    }
    PkltAcdntItemWriter writer() {
        var writer = new PkltAcdntItemWriter(dataSource);
        writer.beforeStep(new StepExecution("pkltAcdntFetchStep", new JobExecution(100L)));
        return writer;
    }
    @Test void migrationPreservesIdsAndExcludesSeedsFromWatermark() {
        assertThat(jdbc.queryForObject("SELECT acdnt_id FROM parking.tb_pklt_acdnt_hstry WHERE acdnt_id='41'", String.class))
                .isEqualTo("41");
        assertThat(jdbc.queryForObject("SELECT max(acdnt_dt) FROM parking.tb_pklt_acdnt_hstry WHERE acdnt_id NOT LIKE 'SEED-%'",
                LocalDateTime.class)).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='parking' "
                + "AND table_name='tb_pklt_acdnt_hstry' AND column_name='load_dt'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT use_yn FROM batch.tb_btch_schdl WHERE job_nm='pkltAcdntFetchJob'", String.class))
                .isEqualTo("N");
    }
    @Test void rerunUpdatesStatusPreservesSequenceIdAndDoesNotDuplicate() throws Exception {
        var writer = writer();
        writer.write(new Chunk<>(java.util.List.of(row(9001L, "처리 중"))));
        String id = jdbc.queryForObject("SELECT acdnt_id FROM parking.tb_pklt_acdnt_hstry WHERE src_hash=?",
                String.class, row(9001L, "처리 중").srcHash());
        assertThat(Long.parseLong(id)).isGreaterThan(41L);
        writer.write(new Chunk<>(java.util.List.of(row(9001L, "완료"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_pklt_acdnt_hstry WHERE src_hash=?",
                Integer.class, row(9001L, "완료").srcHash())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT acdnt_id FROM parking.tb_pklt_acdnt_hstry WHERE src_hash=?",
                String.class, row(9001L, "완료").srcHash())).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT prcs_stts_nm FROM parking.tb_pklt_acdnt_hstry WHERE acdnt_id=?", String.class, id))
                .isEqualTo("완료");
        assertThat(jdbc.queryForObject("SELECT acdnt_dt FROM parking.tb_pklt_acdnt_hstry WHERE acdnt_id=?", LocalDateTime.class, id))
                .isEqualTo(DATE);
        assertThat(jdbc.queryForObject("SELECT src_hash=batch.source_row_hash(pklt_id::text, "
                + "to_char(acdnt_dt,'YYYY-MM-DD HH24:MI:SS.US'),acdnt_type) "
                + "FROM parking.tb_pklt_acdnt_hstry WHERE acdnt_id=?", Boolean.class, id)).isTrue();
    }
    @Test void failedChunkRollsBackAllRowsAndKeepsWatermark() {
        var writer = writer();
        // 시큐어코딩 4.3 — 람다 안의 넓은 예외 catch 대신 트랜잭션을 직접 열고, 실패는 assertThatThrownBy 가 받는다.
        var txManager = tx.getTransactionManager();
        var status = txManager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
        try {
            assertThatThrownBy(() -> writer.write(new Chunk<>(java.util.List.of(row(9001, "완료"), row(9999, "완료")))));
        } finally {
            txManager.rollback(status);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_pklt_acdnt_hstry WHERE acdnt_dt=?", Integer.class, DATE))
                .isZero();
    }
    @Test void cleanupRequiresSuccessfulNonemptyFetchAndDeletesOnlySeeds() throws Exception {
        var job = new JobExecution(100L);
        var fetch = new StepExecution("pkltAcdntFetchStep", job);
        job.addStepExecutions(java.util.List.of(fetch));
        var cleanup = new StepExecution("pkltAcdntSeedCleanupStep", job);
        var tasklet = new PkltAcdntSeedCleanupTasklet(jdbc);
        tasklet.execute(new StepContribution(cleanup), new ChunkContext(new StepContext(cleanup)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_pklt_acdnt_hstry WHERE acdnt_id LIKE 'SEED-%'", Integer.class))
                .isEqualTo(1);
        writer().write(new Chunk<>(java.util.List.of(row(9001, "완료"))));
        fetch.setWriteCount(1);
        tasklet.execute(new StepContribution(cleanup), new ChunkContext(new StepContext(cleanup)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_pklt_acdnt_hstry WHERE acdnt_id LIKE 'SEED-%'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_pklt_acdnt_hstry WHERE acdnt_id='41'", Integer.class))
                .isEqualTo(1);
    }
}
