package jeju.datahub.batch.collect;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.batch.item.Chunk;
import jeju.datahub.batch.cvlcpt.*;
import jeju.datahub.batch.crackdown.*;
import jeju.datahub.batch.crackdown.api.CrackdownOriginRow;

/** 반드시 별도 임시 DB에서 실행한다. 실제 PostgreSQL로 마이그레이션/시퀀스/Writer/해시를 검증한다. */
@EnabledIfEnvironmentVariable(named="MONTHLY_TEST_DB_URL", matches=".+")
class MonthlyLoadDatabaseTest {
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    final LocalDateTime dt=LocalDateTime.parse("2026-11-01T12:00:00.123456");
    @BeforeEach void setup() throws Exception {
        ds=new DriverManagerDataSource(System.getenv("MONTHLY_TEST_DB_URL"),System.getProperty("user.name"),"");
        jdbc=new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).startsWith("jeju_monthly_test");
        jdbc.execute("DROP SCHEMA IF EXISTS parking CASCADE; DROP SCHEMA IF EXISTS batch CASCADE; CREATE SCHEMA parking; CREATE SCHEMA batch;");
        jdbc.execute("""
          CREATE TABLE parking.tb_cvlcpt (
            cvlcpt_id varchar(50) PRIMARY KEY, cvlcpt_type_cd varchar(30) NOT NULL,
            emd_cd varchar(8), lat numeric, lot numeric, ocrn_dt timestamp NOT NULL,
            risk_lvl_cd varchar(30), src_cd varchar(30), cvlcpt_cn text, load_dt timestamp NOT NULL,
            aprs_cd varchar(30), dclr_ymd char(10), ttl text, dclr_ocrn_rgn text, prgrs_stts text,
            ans_prcs_stts text, ans_ymd char(10), aplcnt_se text, dclr_fld text, rwrd_yn text,
            prcs_inst_cnt integer, prcs_inst_nm text, prcs_inst_pic_nm text, dclr_grp_nm text,
            mbr_grp_nm text, dclr_type text, prk_cvlcpt_type text, src_path text);
          CREATE TABLE parking.tb_crdn_dsctn (
            crdn_id bigserial PRIMARY KEY, crdn_dt timestamp, ffnlg text, crdn_mthd text,
            carmdl text, orgnl_cn text, crdn_plc text, crdn_addr text,
            src_hash char(32) NOT NULL, load_dt timestamp NOT NULL DEFAULT now(),
            cvlcpt_id varchar(50) REFERENCES parking.tb_cvlcpt(cvlcpt_id),
            CONSTRAINT uk_tb_crdn_dsctn_src_hash UNIQUE(src_hash));
          CREATE TABLE batch.tb_btch_schdl(job_nm text PRIMARY KEY, cron_expr text, use_yn char(1), mdfcn_dt timestamp);
          INSERT INTO batch.tb_btch_schdl VALUES ('complaintFetchJob','0 0 2 * * *','Y',null),('crackdownFetchJob','0 0 3 * * *','N',null);
          INSERT INTO parking.tb_cvlcpt(cvlcpt_id,cvlcpt_type_cd,ocrn_dt,cvlcpt_cn,load_dt)
            VALUES ('77','99','2026-01-01','legacy','2026-10-15');
          INSERT INTO parking.tb_crdn_dsctn(crdn_dt,ffnlg,orgnl_cn,src_hash,cvlcpt_id)
            VALUES ('2026-01-01','40000.0','legacy','11111111111111111111111111111111','77');
          """);
        jdbc.execute(Files.readString(Path.of("../../database/migrations/sql/109-CVLCPT-CRDN-MONTHLY-LOAD.sql")));
    }
    CvlcptRecord complaint(String origin,String content,LocalDateTime load) {
        return new CvlcptRecord(origin,"99",null,null,null,LocalDateTime.parse("2026-01-01T00:00:00"),
            null,content,load,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
    }
    CvlcptItemWriter complaintWriter() throws Exception {
        var writer=new CvlcptItemWriter(ds);writer.afterPropertiesSet();return writer;
    }
    @Test void migrationPreservesLegacyPkForeignKeyAndOldLoadDate() {
        assertThat(jdbc.queryForObject("SELECT cvlcpt_id FROM parking.tb_crdn_dsctn",String.class)).isEqualTo("77");
        assertThat(jdbc.queryForObject("SELECT origin_pk FROM parking.tb_cvlcpt",String.class)).isEqualTo("77");
        assertThat(new SourceLoadWatermark(jdbc).complaint()).isNull();
        assertThat(new SourceLoadWatermark(jdbc).crackdown()).isNull();
        assertThat(jdbc.queryForObject("SELECT orgnl_load_dt FROM parking.tb_cvlcpt",LocalDateTime.class)).isEqualTo(LocalDateTime.parse("2026-10-15T00:00:00"));
        assertThat(jdbc.queryForObject("SELECT cron_expr FROM batch.tb_btch_schdl WHERE job_nm='complaintFetchJob'",String.class)).isEqualTo("0 0 2 1 * *");
        assertThat(jdbc.queryForObject("SELECT use_yn FROM batch.tb_btch_schdl WHERE job_nm='crackdownFetchJob'",String.class)).isEqualTo("N");
    }
    @Test void complaintSequencesAllowDuplicateAndNullOriginIdsAndReplayWithoutChangingPk() throws Exception {
        var writer=complaintWriter();
        writer.write(new Chunk<>(java.util.List.of(complaint("dup","one",dt),complaint("dup","two",dt),complaint(null,"three",dt))));
        assertThat(jdbc.queryForList("SELECT cvlcpt_id FROM parking.tb_cvlcpt WHERE cvlcpt_cn <> 'legacy' ORDER BY cvlcpt_id",String.class))
            .containsExactly("78","79","80");
        writer.write(new Chunk<>(java.util.List.of(complaint("dup","one",dt.plusDays(1)),complaint(null,"three",null))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_cvlcpt",Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT cvlcpt_id FROM parking.tb_cvlcpt WHERE cvlcpt_cn='one'",String.class)).isEqualTo("78");
        assertThat(new SourceLoadWatermark(jdbc).complaint()).isEqualTo(dt.plusDays(1));
        // 원천 재동기화가 기존 legacy 행도 새 PK 없이 갱신한다.
        writer.write(new Chunk<>(java.util.List.of(complaint("77","legacy",dt))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_cvlcpt",Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT load_dt FROM parking.tb_cvlcpt WHERE cvlcpt_id='77'",LocalDateTime.class)).isEqualTo(dt);
    }
    @Test void crackdownPreservesLocalPkAndUpdatesOnlySourceTimestampOnReplay() throws Exception {
        var writer=new CrackdownItemWriter(ds);
        String hash=SourceFingerprint.hash(SourceTimestamp.format(LocalDateTime.parse("2026-01-01T00:00:00")),"40000.0",null,null,"legacy",null,null);
        writer.write(new Chunk<>(java.util.List.of(new CrackdownOriginRow(LocalDateTime.parse("2026-01-01T00:00:00"),"40000.0",null,null,"legacy",null,null,hash,"duplicate",dt))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_crdn_dsctn",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT crdn_id FROM parking.tb_crdn_dsctn",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT origin_pk FROM parking.tb_crdn_dsctn",String.class)).isEqualTo("duplicate");
        assertThat(new SourceLoadWatermark(jdbc).crackdown()).isEqualTo(dt);
        assertThat(jdbc.queryForObject("SELECT length(src_hash) FROM parking.tb_crdn_dsctn",Integer.class)).isEqualTo(64);
    }
    @Test void failedChunkIsRolledBackAndNextRunStartsAtCommittedMaximum() throws Exception {
        var writer=complaintWriter();
        writer.write(new Chunk<>(java.util.List.of(complaint(null,"committed",dt))));
        // 시큐어코딩 4.3 — 람다 안의 넓은 예외 catch 대신 트랜잭션을 직접 열고 되돌린다(실패한 청크 롤백과 같은 효과).
        var txManager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        var status=txManager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
        try {
            writer.write(new Chunk<>(java.util.List.of(complaint(null,"rolled-back",dt.plusDays(1)))));
        } finally {
            txManager.rollback(status);
        }
        var range=SourceLoadRange.resolve(null,null,new SourceLoadWatermark(jdbc)::complaint,LocalDate.of(2026,11,14));
        assertThat(range.fromInclusive()).isEqualTo(dt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_cvlcpt WHERE cvlcpt_cn='rolled-back'",Long.class)).isZero();
        writer.write(new Chunk<>(java.util.List.of(complaint(null,"committed",dt),complaint(null,"rolled-back",dt.plusDays(1)))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_cvlcpt",Long.class)).isEqualTo(3);
        assertThat(new SourceLoadWatermark(jdbc).complaint()).isEqualTo(dt.plusDays(1));
    }
    @Test void repeatedMigrationPreservesSourceMaximumAndExistingDuplicateRows() throws Exception {
        var writer=complaintWriter();
        writer.write(new Chunk<>(java.util.List.of(complaint(null,"committed",dt))));
        jdbc.update("INSERT INTO parking.tb_cvlcpt(cvlcpt_type_cd,ocrn_dt,cvlcpt_cn) VALUES ('99','2026-01-01','legacy')");
        jdbc.execute(Files.readString(Path.of("../../database/migrations/sql/109-CVLCPT-CRDN-MONTHLY-LOAD.sql")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tb_cvlcpt",Long.class)).isEqualTo(3);
        assertThat(new SourceLoadWatermark(jdbc).complaint()).isEqualTo(dt);
    }
    @Test void sqlAndJavaHashAgreeForUnicodeNullEmptyAndFieldBoundaries() {
        String result=jdbc.queryForObject("SELECT batch.source_row_hash(?,NULL,?,?)",String.class,"제주🙂","","a:b");
        assertThat(result).isEqualTo(SourceFingerprint.hash("제주🙂",null,"","a:b"));
    }
}
