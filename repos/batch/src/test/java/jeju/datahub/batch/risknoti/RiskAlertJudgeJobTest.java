package jeju.datahub.batch.risknoti;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * ③{@code riskAlertJudgeJob} 통합 검증 — <b>집계 → 판정 → 적재</b> 전 구간.
 *
 * <p>케이스 표 12행 중 <b>11행</b>을 여기서 본다. #9(기존 잡 회귀)는 이 클래스 밖이다
 * ({@code ComplaintFetchJobTest} · {@code ComplaintSyncSchedulerTest} 가 맡는다).
 *
 * <h2>🔴 픽스처를 심고 지우는 규칙</h2>
 * <ul>
 *   <li>🔁 <b>판정일은 상수가 아니다</b> — {@code @BeforeEach} 가 {@link #EMPTY_DAY_SEARCH_FROM} 부터
 *       <b>전일·당일에 민원·알림·스킵이 한 건도 없는 첫 날</b>을 DB 로 확인해 고른다. 종전 상수 {@code 2026-06-10}
 *       은 "실민원 밖"(2026-08-26 실측)이었지만 시드가 {@code 2025-11~2026-08} 로 옮겨지자 실민원 33개 읍면동이
 *       판정에 섞여 9건이 한꺼번에 깨졌다. 빈 날을 못 찾으면 단언 전에 원인이 보이는 메시지로 실패한다.</li>
 *   <li>민원 키는 <b>{@code AAA-RISKTEST-} 접두</b> — 실데이터({@code EPP-}/{@code SPP-})와 겹치지 않는다.
 *       ⚠️ {@code cvlcpt_id} 는 {@code VARCHAR(20)} 이고 접두가 13자라 <b>뒤에 7자</b>만 남는다.</li>
 *   <li>{@code emd_cd} 는 {@code tb_admdst} 에 실재하는 코드다 — FK {@code fk_tb_cvlcpt_emd_cd} 가 있어
 *       지어낸 코드는 INSERT 부터 죽는다.</li>
 *   <li>정리는 <b>{@code @AfterEach} 가 정본</b>이다. 알림 시드는 {@code tb_risk_stp_noti_hstry} 만
 *       18행으로 되돌리고 <b>{@code parking.tb_cvlcpt}·{@code batch.tb_job_skip} 은 어떤 시더도 손대지
 *       않는다</b> — 합성 민원이 남으면 다음 실행이 그 행까지 다시 집계한다.
 *       ⚠️ {@code batch.tb_btch_excn_hstry} 는 지우지 않는다(선례도 지우지 않는다. 케이스 8 은
 *       <b>이번 실행의 행</b>을 최신 행으로 확인한다).</li>
 * </ul>
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다</b> — Job 은 자기 트랜잭션에서 커밋하므로 테스트
 * 트랜잭션으로 감싸도 롤백되지 않고, 오히려 {@code REQUIRES_NEW} 로 커밋되는 스킵 기록
 * ({@link RiskAlertSkipListener})을 관찰할 수 없게 된다(선례 {@code ComplaintFetchJobTest}).
 *
 * <h2>⚠️ 판정 기준값(30/20/10)에 기대지 않는다</h2>
 * {@code parking.tb_risk_stp_noti_crtr} 는 화면({@code PUT /api/risk-criteria})에서 언제든 바뀌고
 * 검증은 <b>{@code 0 < x <= 100}</b> 만 본다. 그래서 픽스처를 <b>+200%/+1900%</b>(어떤 기준도 넘는다)와
 * <b>0%/−50%</b>(어떤 기준도 못 넘는다)로만 짜고, 단계 코드는 DB 에서 <b>임계가 가장 큰 단계</b>를
 * 읽어 비교한다(시드 기준으로는 {@code CRIT}). 산식·경계값 자체는
 * {@link RiskAlertJudgeProcessorTest} 가 기준을 직접 심어 고정한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 청크 1 — 스킵(8-1)이 "그 행만" 걷히는 경로와 청크 롤백/재처리 경로를 모두 지나가게 한다.
        //          Reader 페이지 크기도 같은 키라 페이징까지 함께 돈다.
        "batch.risknoti.chunk-size=1",
        // 테스트 컨텍스트에 02:00 크론이 등록될 필요는 없다.
        "complaint.sync.scheduled=false",
        "pblpklt.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class RiskAlertJudgeJobTest {

    /**
     * 빈 판정일 탐색 시작점. 실데이터·시드가 날짜를 옮겨도 닿기 어려운 과거 구간이다 — 그래도 <b>가정하지 않고</b>
     * {@link #pickEmptyJudgeDate()} 가 매번 DB 로 확인한다.
     */
    private static final LocalDate EMPTY_DAY_SEARCH_FROM = LocalDate.of(2000, 1, 2);

    /** 탐색 범위(일). 이 안에 빈 날이 없으면 픽스처를 심지 않고 실패한다. */
    private static final int EMPTY_DAY_SEARCH_DAYS = 366;

    /** 판정일 (전일 = {@link #fromDe}). {@code @BeforeEach} 에서 정한다. */
    private LocalDate judgeDate;

    /** {@code batch.tb_job_skip.trgt_de} 는 <b>DATE</b> 다. */
    private LocalDate fromDe;

    /** {@code parking.tb_risk_stp_noti_hstry.ocrn_dt}·{@code tb_cvlcpt.ocrn_dt} 는 <b>TIMESTAMP</b> 다. */
    private LocalDateTime from;

    private LocalDateTime to;

    /** 적재된 알림의 {@code ocrn_dt} 는 '판정일 00:00:00' 이어야 한다(D1·D6 — 실행 시각이 아니다). */
    private Timestamp expectedOcrnDt;

    /** ⚠️ 13자. {@code cvlcpt_id} 가 {@code VARCHAR(20)} 이라 일련번호는 7자리다. */
    private static final String ID_PREFIX = "AAA-RISKTEST-";

    private static final String INSERT_SQL = """
            INSERT INTO parking.tb_cvlcpt (cvlcpt_id, cvlcpt_type_cd, emd_cd, ocrn_dt, load_dt)
            VALUES (?, ?, ?, ?, now())""";

    /** 🔴 emd_cd 를 아예 컬럼 목록에서 뺀다 — 케이스 7(미분류 민원)용. */
    private static final String INSERT_NO_EMD_SQL = """
            INSERT INTO parking.tb_cvlcpt (cvlcpt_id, cvlcpt_type_cd, ocrn_dt, load_dt)
            VALUES (?, ?, ?, now())""";

    /** 주차민원 유형(실데이터에 있는 값). 판정은 유형을 가리지 않지만 NOT NULL 이라 채운다. */
    private static final String TYPE_PARKING = "01";

    /** 미분류 — 실측상 {@code emd_cd IS NULL} 전량이 이 유형이다(D3). */
    private static final String TYPE_UNCLASSIFIED = "99";

    private static final String EMD_SURGE = "50110101";   // 일도일동 — 급증(적재된다)
    private static final String EMD_FLAT = "50110102";    // 일도이동 — 변동 없음(알림 없음)
    private static final String EMD_DROP = "50110103";    // 이도일동 — 감소(알림 없음)
    private static final String EMD_NEW = "50110104";     // 이도이동 — 전일 0건(D2)

    /** 접두 뒤 7자리 일련번호. 같은 실행 안에서 키가 겹치지 않게만 하면 된다. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * 🔴 <b>{@code @MockitoBean} 이 아니라 스파이</b>다 — mock 으로 덮으면 이 클래스의 나머지 케이스가
     * 통째로 무력화된다(Writer 가 아무것도 쓰지 않게 된다). 스파이라 스텁하지 않은 케이스는
     * <b>실제 Writer 그대로</b> 돌고, 케이스 8-2 에서만 예외를 던지게 만든다.
     * 스텁은 매 테스트 뒤 리셋된다({@code MockReset.AFTER} 기본값).
     */
    @MockitoSpyBean
    private RiskAlertItemWriter writer;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job riskAlertJudgeJob;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 앞선 실행이 비정상 종료해 남긴 합성 민원을 먼저 걷고, <b>빈 판정일을 고른 뒤</b> 그 구간을 비운다.
     * 순서가 중요하다 — 합성 민원이 남은 채로 고르면 그 날이 "비어 있지 않음" 으로 보여 다음 날로 밀린다.
     */
    @BeforeEach
    void cleanBefore() {
        jdbc.update("DELETE FROM parking.tb_cvlcpt WHERE cvlcpt_id LIKE 'AAA-RISKTEST-%'");
        pickEmptyJudgeDate();
        deleteFixtures();
    }

    /**
     * 🔴 세 곳을 지운다. 알림·스킵은 <b>고른 판정일 구간으로만</b> 좁힌다 — 고를 때 비어 있음을 확인한
     * 구간이라 합성 행만 지워진다. 스킵 삭제에 {@code job_se='RISKNOTI'} 를 반드시 건다 —
     * {@code batch.tb_job_skip} 은 <b>잡 공통 표</b>라(D9) 조건을 빼면 나중에 붙을 다른 잡의 기록까지 지운다.
     * ⚠️ 종전 {@code trgt_de >= 전일} 은 판정일 이후의 실제 스킵까지 지웠다 — 상한을 함께 건다.
     */
    @AfterEach
    void deleteFixtures() {
        jdbc.update("DELETE FROM parking.tb_risk_stp_noti_hstry WHERE ocrn_dt >= ? AND ocrn_dt < ?",
                from, to);
        jdbc.update("DELETE FROM batch.tb_job_skip WHERE job_se = 'RISKNOTI' AND trgt_de BETWEEN ? AND ?",
                fromDe, judgeDate);
        jdbc.update("DELETE FROM parking.tb_cvlcpt WHERE cvlcpt_id LIKE 'AAA-RISKTEST-%'");
    }

    /**
     * 전일·당일에 <b>민원(미분류 포함)·알림·RISKNOTI 스킵이 한 건도 없는</b> 첫 날을 판정일로 고른다.
     * 민원은 {@code emd_cd IS NULL} 까지 본다 — 케이스 7 이 "읽힌 행 0건" 을 단언하기 때문이다.
     */
    private void pickEmptyJudgeDate() {
        List<LocalDate> found = jdbc.queryForList("""
                SELECT d::date
                  FROM generate_series(CAST(? AS date), CAST(? AS date), INTERVAL '1 day') d
                 WHERE NOT EXISTS (SELECT 1 FROM parking.tb_cvlcpt c
                                    WHERE c.ocrn_dt >= d - INTERVAL '1 day' AND c.ocrn_dt < d + INTERVAL '1 day')
                   AND NOT EXISTS (SELECT 1 FROM parking.tb_risk_stp_noti_hstry h
                                    WHERE h.ocrn_dt >= d - INTERVAL '1 day' AND h.ocrn_dt < d + INTERVAL '1 day')
                   AND NOT EXISTS (SELECT 1 FROM batch.tb_job_skip s
                                    WHERE s.job_se = 'RISKNOTI'
                                      AND s.trgt_de BETWEEN CAST(d - INTERVAL '1 day' AS date) AND CAST(d AS date))
                 ORDER BY d
                 LIMIT 1""",
                LocalDate.class,
                java.sql.Date.valueOf(EMPTY_DAY_SEARCH_FROM),
                java.sql.Date.valueOf(EMPTY_DAY_SEARCH_FROM.plusDays(EMPTY_DAY_SEARCH_DAYS - 1)));
        assertThat(found)
                .as("판정일 후보 %s 부터 %d일 안에 전일·당일이 모두 빈 날이 없다 — 로컬 DB 데이터 구간을 확인할 것",
                        EMPTY_DAY_SEARCH_FROM, EMPTY_DAY_SEARCH_DAYS)
                .isNotEmpty();
        judgeDate = found.get(0);
        fromDe = judgeDate.minusDays(1);
        from = fromDe.atStartOfDay();
        to = judgeDate.plusDays(1).atStartOfDay();
        expectedOcrnDt = Timestamp.valueOf(judgeDate.atStartOfDay());
    }

    // ------------------------------------------------------------------ 케이스 1
    @Test
    @DisplayName("1. 기준을 넘은 읍면동만 이력에 적재된다 — noti_ctt·incrs_rt_thrsh 가 NULL 이 아니다")
    void loadsOnlyEmdsOverThreshold() throws Exception {
        insertMixedFixture();

        JobExecution execution = runJudge();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 세 읍면동을 읽고(D4 — 당일 0건은 애초에 안 읽는다) 급증 한 곳만 적재된다.
        assertThat(readCount(execution)).isEqualTo(3);
        assertThat(alertedEmds()).containsExactly(EMD_SURGE);

        Map<String, Object> row = alertRow(EMD_SURGE);
        assertThat(row.get("risk_lvl_cd")).isEqualTo(highestLevelCd());   // 시드 기준으로는 CRIT
        assertThat(row.get("cvlcpt_cnt")).isEqualTo(30);                  // 보정 전 실제 당일 건수
        assertThat((BigDecimal) row.get("incrs_rt")).isEqualByComparingTo("200.00");
        // 🔴 스냅샷 2종(D8) — 비면 화면에 빈 칸이 나가고 과거 판정 근거도 사라진다.
        assertThat((String) row.get("noti_ctt")).isEqualTo("일도일동 민원 200% 증가");
        assertThat(row.get("incrs_rt_thrsh")).isNotNull();
        // 🔴 실행 시각이 아니라 '판정일 00:00:00' 이다 — 아니면 UNIQUE(emd_cd, ocrn_dt) 가 무의미해진다.
        assertThat(row.get("ocrn_dt")).isEqualTo(expectedOcrnDt);
        // 신규 행은 DDL 기본값으로 들어간다(배치가 만들지 않는 값 — D7).
        assertThat(row.get("prcs_stts_cd")).isEqualTo("OPEN");
        assertThat(row.get("prsl_yn")).isEqualTo("N");
        assertThat(row.get("idnty_yn")).isEqualTo("N");
    }

    // ------------------------------------------------------------------ 케이스 2
    @Test
    @DisplayName("2. 🔁 전일 0건 → 당일 20건은 읽히지만 하한이 있으면 알림이 없다 — filtered 이지 스킵이 아니다")
    void readsZeroPreviousDayButFiltersByMinimum() throws Exception {
        // 하한 0(꺼짐) 설정에서는 이 단언이 성립하지 않는다. 1900% 산식은 RiskAlertJudgeProcessorTest 가 고정한다.
        assumeThat(jdbc.queryForObject(
                "SELECT min(min_cvlcpt_cnt) FROM parking.tb_risk_stp_noti_crtr", Integer.class))
                .isGreaterThanOrEqualTo(1);
        // 전일은 한 건도 넣지 않는다. LEFT JOIN + COALESCE 로 prev_cnt=0 이 그대로 흘러온다.
        insertComplaints(EMD_NEW, judgeDate, 20);

        JobExecution execution = runJudge();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 🔴 INNER JOIN 이면 여기가 0 이 된다 — 하한 0 설정에서 신규 급증이 통째로 사라진다.
        assertThat(readCount(execution)).isEqualTo(1);
        assertThat(alertedEmds()).isEmpty();
        assertThat(skipRows()).isEmpty();
    }

    // ------------------------------------------------------------------ 케이스 3
    @Test
    @DisplayName("3. 기준 미달(변동 없음)이면 알림이 생기지 않는다 — Processor null 반환이 정상 경로")
    void doesNotAlertBelowThreshold() throws Exception {
        insertMixedFixture();

        JobExecution execution = runJudge();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 0.00% 는 어떤 기준(0 < x <= 100)도 넘지 못한다. 스킵이 아니라 '알림 없음' 이다.
        assertThat(alertedEmds()).doesNotContain(EMD_FLAT);
        assertThat(skipRows()).isEmpty();
        // read > write 는 정상이다 — 대부분의 읍면동이 매일 여기다.
        assertThat(writeCount(execution)).isLessThan(readCount(execution));
    }

    // ------------------------------------------------------------------ 케이스 4
    @Test
    @DisplayName("4. 감소 구간(당일 < 전일)은 음수 증가율이라 알림이 없다")
    void doesNotAlertOnDecrease() throws Exception {
        insertMixedFixture();

        JobExecution execution = runJudge();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(alertedEmds()).doesNotContain(EMD_DROP);   // 10 → 5 = -50.00%
        assertThat(skipRows()).isEmpty();
    }

    // ------------------------------------------------------------------ 케이스 5
    @Test
    @DisplayName("5. 🔴 같은 targetDate 로 두 번 돌려도 이력 행수가 같다 — upsert 멱등성(D6)")
    void isIdempotentAcrossReruns() throws Exception {
        insertMixedFixture();

        runJudge();
        List<String> afterFirst = alertedEmds();

        // 값이 어긋난 상황을 흉내낸다 — 재판정이 최신 판정값으로 되돌려야 한다.
        jdbc.update("UPDATE parking.tb_risk_stp_noti_hstry SET cvlcpt_cnt = 1, noti_ctt = '변경됨'"
                + " WHERE emd_cd = ? AND ocrn_dt = ?", EMD_SURGE, expectedOcrnDt);

        JobExecution second = runJudge();

        // ON CONFLICT 가 없으면 여기서 UNIQUE(emd_cd, ocrn_dt) 위반으로 Job 이 죽는다.
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(alertedEmds()).isEqualTo(afterFirst);
        Map<String, Object> row = alertRow(EMD_SURGE);
        assertThat(row.get("cvlcpt_cnt")).isEqualTo(30);
        assertThat(row.get("noti_ctt")).isEqualTo("일도일동 민원 200% 증가");
    }

    // ------------------------------------------------------------------ 케이스 6
    @Test
    @DisplayName("6. 🔴 열람 상태는 재판정에도 보존된다 — prsl_yn='Y' 가 'N' 으로 되돌아가면 FAIL(D7)")
    void preservesReadStateOnRejudge() throws Exception {
        insertMixedFixture();
        runJudge();

        // 사람이 화면에서 열람 처리한 상황. (제약 ck_risk_hstry_read_cnfm 은 prsl_yn='Y' 를 허용한다)
        jdbc.update("UPDATE parking.tb_risk_stp_noti_hstry SET prsl_yn = 'Y', prsl_dt = now()"
                + " WHERE emd_cd = ? AND ocrn_dt = ?", EMD_SURGE, expectedOcrnDt);

        JobExecution second = runJudge();

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> row = alertRow(EMD_SURGE);
        // 🔴 SET 에 prsl_yn = EXCLUDED.prsl_yn 이 들어가면 여기서 'N' 으로 되돌아간다.
        assertThat(row.get("prsl_yn")).isEqualTo("Y");
        assertThat(row.get("prsl_dt")).isNotNull();
        // 판정값은 그대로 갱신된다 — '보존' 이 '아무것도 안 함' 이 아님을 함께 못박는다.
        assertThat((BigDecimal) row.get("incrs_rt")).isEqualByComparingTo("200.00");
    }

    // ------------------------------------------------------------------ 케이스 7
    @Test
    @DisplayName("7. emd_cd 가 NULL 인 미분류 민원만 있는 날은 판정 대상이 0건이다")
    void excludesRowsWithoutEmdCd() throws Exception {
        insertUnclassified(fromDe, 5);
        insertUnclassified(judgeDate, 40);

        JobExecution execution = runJudge();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // SQL 이 emd_cd IS NOT NULL 로 이미 걸러 낸다 — 읽히는 행 자체가 없다.
        assertThat(readCount(execution)).isZero();
        assertThat(alertedEmds()).isEmpty();
        assertThat(skipRows()).isEmpty();
    }

    // ------------------------------------------------------------------ 케이스 8
    @Test
    @DisplayName("8. 실행 이력 최신 행이 riskAlertJudgeJob·COMPLETED·write_cnt>0 이다")
    void recordsRunHistory() throws Exception {
        insertMixedFixture();

        JobExecution execution = runJudge();

        // 🔴 절대 행수로 보지 않는다 — 같은 클래스의 앞선 케이스들이 이미 여러 번 돌려 놓았다.
        Map<String, Object> latest = jdbc.queryForMap("""
                SELECT job_execution_id, job_nm, status, read_cnt, write_cnt
                  FROM batch.tb_btch_excn_hstry
                 ORDER BY started_dt DESC, job_execution_id DESC
                 LIMIT 1""");

        assertThat(latest.get("job_execution_id")).isEqualTo(execution.getId());
        assertThat(latest.get("job_nm")).isEqualTo(RiskAlertJudgeJobConfig.JOB_NAME);
        assertThat(latest.get("status")).isEqualTo("COMPLETED");
        assertThat((Long) latest.get("read_cnt")).isEqualTo(3L);
        assertThat((Long) latest.get("write_cnt")).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ 케이스 8-1
    @Test
    @DisplayName("8-1. 🔴 증가율이 저장 범위를 넘으면 그 행만 스킵되고 나머지는 적재된다 — Job 은 COMPLETED(D9)")
    void skipsRateOverflowRowAndKeepsGoing() throws Exception {
        // 전일 1 → 당일 1001 = 100000.00% > NUMERIC(7,2) 상한(99999.99)
        insertComplaints(EMD_SURGE, fromDe, 1);
        insertComplaints(EMD_SURGE, judgeDate, 1001);
        // 같은 실행 안의 정상 읍면동 — 한 곳의 병적인 값이 그날 전체 판정을 죽이지 않아야 한다.
        insertComplaints(EMD_FLAT, fromDe, 10);
        insertComplaints(EMD_FLAT, judgeDate, 30);

        JobExecution execution = runJudge();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(alertedEmds()).containsExactly(EMD_FLAT);

        List<Map<String, Object>> skips = skipRows();
        assertThat(skips).hasSize(1);
        Map<String, Object> skip = skips.get(0);
        assertThat(skip.get("trgt_id")).isEqualTo(EMD_SURGE);
        assertThat(skip.get("fail_rsn")).isEqualTo(RiskAlertSkippableException.RATE_OVERFLOW);
        assertThat(skip.get("trgt_de")).isEqualTo(java.sql.Date.valueOf(judgeDate));
        // 🔴 청크 롤백(스킵 스캔)에 휩쓸리지 않아야 남는다 — REQUIRES_NEW 가 그 장치다.
        assertThat((String) skip.get("note")).contains("target_cnt=1001").contains("prev_cnt=1");
    }

    // ------------------------------------------------------------------ 케이스 8-2
    @Test
    @DisplayName("8-2. 🔴 스킵 대상이 아닌 예외는 삼켜지지 않는다 — Job 이 FAILED 로 끝난다(D9)")
    void failsJobOnNonSkippableException() throws Exception {
        insertMixedFixture();
        // 🔴 이 케이스에서만 스텁한다. BadSqlGrammarException 은 skip() 목록 밖이다.
        doThrow(new BadSqlGrammarException("판정 적재(강제)", RiskAlertItemWriter.UPSERT_SQL,
                new SQLException("forced"))).when(writer).write(any());

        JobExecution execution = runJudge();

        // skip(Exception.class) 로 넓히면 행 단위로 삼켜져 여기가 COMPLETED 가 된다 — 그러면 FAIL 이다.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(t -> assertThat(causedByBadSqlGrammar(t)).isTrue());
        // 적재도 스킵 기록도 없다 — 이 실패는 '행 단위 확정 실패' 가 아니다.
        assertThat(alertedEmds()).isEmpty();
        assertThat(skipRows()).isEmpty();
    }

    // ------------------------------------------------------------------ 케이스 10
    @Test
    @DisplayName("10. 문구에 '.%' 가 없다 — 45.00 은 '45', 37.50 은 '37.5' 로 남는다(D8)")
    void formatsNotiCttWithoutTrailingDot() throws Exception {
        // 16 → 22 = +37.50% (소수부가 살아 있어야 한다) — 🔁 전일도 하한 이상이어야 한다
        insertComplaints(EMD_SURGE, fromDe, 16);
        insertComplaints(EMD_SURGE, judgeDate, 22);
        // 10 → 30 = +200.00% (소수점만 남기면 '200.% 증가' 가 된다)
        insertComplaints(EMD_FLAT, fromDe, 10);
        insertComplaints(EMD_FLAT, judgeDate, 30);

        runJudge();

        assertThat(alertRow(EMD_SURGE).get("noti_ctt")).isEqualTo("일도일동 민원 37.5% 증가");
        assertThat(alertRow(EMD_FLAT).get("noti_ctt")).isEqualTo("일도이동 민원 200% 증가");
        // 검증과 같은 조건 — '.%' 가 한 건이라도 있으면 안 된다(ESCAPE 로 % 를 리터럴 취급).
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM parking.tb_risk_stp_noti_hstry
                 WHERE ocrn_dt >= ? AND ocrn_dt < ? AND noti_ctt LIKE '%.!%%' ESCAPE '!'""",
                Long.class, from, to)).isZero();
    }

    // ------------------------------------------------------------------ 헬퍼

    private JobExecution runJudge() throws Exception {
        return jobLauncher.run(riskAlertJudgeJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime())              // 매 실행 새 JobInstance
                .addString("targetDate", judgeDate.toString())
                .toJobParameters());
    }

    /** 급증(적재) · 변동 없음(알림 없음) · 감소(알림 없음) 세 읍면동. 케이스 1·3·4·5·6·8 이 함께 쓴다. */
    private void insertMixedFixture() {
        insertComplaints(EMD_SURGE, fromDe, 10);
        insertComplaints(EMD_SURGE, judgeDate, 30);    // +200.00% — 어떤 기준도 넘는다
        insertComplaints(EMD_FLAT, fromDe, 10);
        insertComplaints(EMD_FLAT, judgeDate, 10);     //    0.00% — 어떤 기준도 못 넘는다
        insertComplaints(EMD_DROP, fromDe, 10);
        insertComplaints(EMD_DROP, judgeDate, 5);      //  -50.00%
    }

    /** 하루 안의 시각으로 넣는다 — 자정에 몰아넣으면 반개구간 경계 버그가 드러나지 않는다. */
    private void insertComplaints(String emdCd, LocalDate day, int count) {
        Timestamp ocrnDt = Timestamp.valueOf(day.atTime(9, 30));
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(new Object[] {nextId(), TYPE_PARKING, emdCd, ocrnDt});
        }
        jdbc.batchUpdate(INSERT_SQL, batch);
    }

    /** {@code emd_cd} 를 컬럼 목록에서 아예 뺀다 — 케이스 7. */
    private void insertUnclassified(LocalDate day, int count) {
        Timestamp ocrnDt = Timestamp.valueOf(day.atTime(9, 30));
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(new Object[] {nextId(), TYPE_UNCLASSIFIED, ocrnDt});
        }
        jdbc.batchUpdate(INSERT_NO_EMD_SQL, batch);
    }

    /** {@code AAA-RISKTEST-} + 7자리 = 20자 (컬럼 상한 정확히). */
    private static String nextId() {
        return ID_PREFIX + String.format("%07d", SEQ.incrementAndGet());
    }

    /** 판정일 구간에 적재된 알림의 읍면동 목록. */
    private List<String> alertedEmds() {
        return jdbc.queryForList("""
                SELECT emd_cd FROM parking.tb_risk_stp_noti_hstry
                 WHERE ocrn_dt >= ? AND ocrn_dt < ? ORDER BY emd_cd""",
                String.class, from, to);
    }

    private Map<String, Object> alertRow(String emdCd) {
        return jdbc.queryForMap("""
                SELECT risk_lvl_cd, emd_cd, cvlcpt_cnt, incrs_rt, noti_ctt, incrs_rt_thrsh,
                       ocrn_dt, prcs_stts_cd, prsl_yn, prsl_dt, idnty_yn
                  FROM parking.tb_risk_stp_noti_hstry
                 WHERE emd_cd = ? AND ocrn_dt >= ? AND ocrn_dt < ?""", emdCd, from, to);
    }

    /** 🔴 {@code job_se='RISKNOTI'} 와 판정일 구간으로 좁힌다 — 공통 표라 다른 잡·다른 날의 기록을 보지 않는다. */
    private List<Map<String, Object>> skipRows() {
        return jdbc.queryForList("""
                SELECT trgt_id, trgt_de, fail_rsn, note FROM batch.tb_job_skip
                 WHERE job_se = ? AND trgt_de BETWEEN ? AND ? ORDER BY trgt_id""",
                RiskAlertSkipListener.JOB_SE, fromDe, judgeDate);
    }

    /**
     * 임계가 가장 큰 단계(D5). 기준은 화면에서 바뀌므로 {@code "CRIT"} 을 상수로 박지 않는다 —
     * 어떤 기준도 넘는 픽스처(+200%/+1900%)면 <b>반드시 이 단계</b>가 나온다.
     */
    private String highestLevelCd() {
        return jdbc.queryForObject("""
                SELECT risk_lvl_cd FROM parking.tb_risk_stp_noti_crtr
                 WHERE incrs_rt IS NOT NULL ORDER BY incrs_rt DESC LIMIT 1""", String.class);
    }

    private static long readCount(JobExecution execution) {
        return execution.getStepExecutions().stream().mapToLong(StepExecution::getReadCount).sum();
    }

    private static long writeCount(JobExecution execution) {
        return execution.getStepExecutions().stream().mapToLong(StepExecution::getWriteCount).sum();
    }

    /** 프레임워크가 몇 겹으로 감싸도 "왜 죽었는지"는 사슬에 남아야 한다. */
    private static boolean causedByBadSqlGrammar(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof BadSqlGrammarException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }
}
