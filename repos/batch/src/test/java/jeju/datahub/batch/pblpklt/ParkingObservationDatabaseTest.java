package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jeju.datahub.batch.utztn.*;
import jeju.datahub.batch.sftychck.*;

@EnabledIfEnvironmentVariable(named="PBLPKLT_TEST_DB_URL", matches=".+")
class ParkingObservationDatabaseTest {
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    PkltUtztnItemWriter usage;
    PkltSftyChckItemWriter safety;

    @BeforeEach void setup() throws Exception {
        ds = new DriverManagerDataSource(System.getenv("PBLPKLT_TEST_DB_URL"),
                System.getenv("PBLPKLT_TEST_DB_USER"), System.getenv("PBLPKLT_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).startsWith("jeju_pblpklt_test_");
        jdbc.execute("""
            DROP SCHEMA IF EXISTS parking CASCADE; CREATE SCHEMA parking;
            CREATE TABLE parking.tb_pblpklt(pklt_id bigint PRIMARY KEY);
            INSERT INTO parking.tb_pblpklt VALUES (1);
            CREATE TABLE parking.tb_pklt_utztn_pttn(
                utztn_stts_id bigserial PRIMARY KEY, pklt_id bigint REFERENCES parking.tb_pblpklt,
                tot_ym char(6) NOT NULL, prk_cntom integer, day_avg_trnvr_rt numeric,
                CONSTRAINT uq_tb_pklt_utztn_pttn_pklt_ym UNIQUE(pklt_id,tot_ym));
            CREATE TABLE parking.tb_pklt_sfty_chck(
                sfty_chck_id bigserial PRIMARY KEY, pklt_id bigint REFERENCES parking.tb_pblpklt,
                chck_ymd char(8) NOT NULL, chck_prd_cd varchar(30) NOT NULL,
                sfty_chck_type_cd varchar(30) NOT NULL, sfty_chck_rslt_cd varchar(30),
                rmrk_cn varchar(4000),rgtr_id varchar(20),reg_dt timestamp,
                CONSTRAINT uq_tb_pklt_sfty_chck UNIQUE(pklt_id,chck_ymd,sfty_chck_type_cd));
            INSERT INTO parking.tb_pklt_utztn_pttn(pklt_id,tot_ym,prk_cntom) VALUES(1,'202601',7);
            INSERT INTO parking.tb_pklt_sfty_chck(pklt_id,chck_ymd,chck_prd_cd,sfty_chck_type_cd,sfty_chck_rslt_cd,rgtr_id)
                VALUES(1,'20260101','H1','REGULAR','A','admin');
            """);
        jdbc.execute(Files.readString(Path.of("../../database/migrations/sql/113-PARKING-OBSERVATION-HISTORY.sql")));
        usage=new PkltUtztnItemWriter(ds); safety=new PkltSftyChckItemWriter(ds);
    }

    void execution(long id) {
        var step=new JobExecution(id,new JobParameters()).createStepExecution("fixture");
        usage.beforeStep(step); safety.beforeStep(step);
    }

    @Test void eachExecutionAppendsWhileSameExecutionRetryDoesNotDuplicate() {
        var u=new PkltUtztnRow(1,"202601",20,new BigDecimal("1.2"),"{\"index\":1}");
        var s=new PkltSftyChckRow(1,"20260101","H1","REGULAR","B",null,"{\"sfty_chck_id\":1}");
        execution(101); usage.write(new Chunk<>(List.of(u))); safety.write(new Chunk<>(List.of(s)));
        usage.write(new Chunk<>(List.of(u))); safety.write(new Chunk<>(List.of(s)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_pklt_utztn_pttn",Integer.class)).isEqualTo(2);
        execution(102); usage.write(new Chunk<>(List.of(u))); safety.write(new Chunk<>(List.of(s)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_pklt_utztn_pttn",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_pklt_sfty_chck",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT prk_cntom FROM parking.tb_pklt_utztn_pttn WHERE utztn_stts_id=1",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT rgtr_id FROM parking.tb_pklt_sfty_chck WHERE sfty_chck_id=1",String.class)).isEqualTo("admin");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.v_pklt_utztn_pttn_latest",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT prk_cntom FROM parking.v_pklt_utztn_pttn_latest",Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.v_pklt_sfty_chck_latest",Integer.class)).isEqualTo(1);
    }

    @Test void unknownDatesAndSourceDGradeArePreservedWithoutInventingBusinessDates() {
        execution(103);
        usage.write(new Chunk<>(List.of(new PkltUtztnRow(1,null,14,null,"{\"msmt_ym\":\"\",\"use_rate\":\"14.89\"}"))));
        safety.write(new Chunk<>(List.of(new PkltSftyChckRow(1,null,null,null,"D","원천 D","{\"chck_ymd\":\"\"}"))));
        assertThat(jdbc.queryForObject("SELECT tot_ym FROM parking.tb_pklt_utztn_pttn WHERE btch_excn_id=103",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT day_avg_trnvr_rt FROM parking.tb_pklt_utztn_pttn WHERE btch_excn_id=103",BigDecimal.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT src_json->>'use_rate' FROM parking.tb_pklt_utztn_pttn WHERE btch_excn_id=103",String.class)).isEqualTo("14.89");
        assertThat(jdbc.queryForObject("SELECT chck_ymd FROM parking.tb_pklt_sfty_chck WHERE btch_excn_id=103",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT sfty_chck_rslt_cd FROM parking.tb_pklt_sfty_chck WHERE btch_excn_id=103",String.class)).isEqualTo("D");
        assertThat(jdbc.queryForObject("SELECT load_dt IS NOT NULL FROM parking.tb_pklt_sfty_chck WHERE btch_excn_id=103",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT load_dt FROM parking.tb_pklt_sfty_chck WHERE sfty_chck_id=1",java.sql.Timestamp.class)).isNull();
    }

    @Test void datedSourceDGradeRemainsRawAndIsNotAssignedAnInventedAnalysisScore() {
        execution(105);
        safety.write(new Chunk<>(List.of(new PkltSftyChckRow(1,"20260201","H1","REGULAR","D",null,"{}"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_pklt_sfty_chck WHERE sfty_chck_rslt_cd='D'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.v_pklt_sfty_chck_latest WHERE sfty_chck_rslt_cd='D'",Integer.class)).isZero();
    }

    @Test void failedChunkRollsBackNewObservations() {
        execution(104);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            usage.write(new Chunk<>(List.of(new PkltUtztnRow(1,null,30,null,"{}"))));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_pklt_utztn_pttn",Integer.class)).isEqualTo(1);
    }
}
