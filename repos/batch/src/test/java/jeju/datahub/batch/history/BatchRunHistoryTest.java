package jeju.datahub.batch.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.geocode.Coordinate;
import jeju.datahub.batch.geocode.GeocodeOutcome;
import jeju.datahub.batch.geocode.GeocodeQuotaExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code batch.tb_btch_excn_hstry} 적재 검증.
 *
 * <h2>🔁 2026-08-24 — 1단화로 대상 Job 이 하나가 됐다</h2>
 * 원본(staging) 폐지로 ②{@code cvlcptGeocodeJob} 이 사라져, 종전에 두 Job 을 나눠 보던 검증이
 * {@code complaintFetchJob} 하나로 모였다. 지오코딩 지표가 NULL 이던 ① 전용 케이스도 함께 없어졌다
 * — 이제 한 Job 이 수집과 지오코딩을 모두 하므로 지표가 항상 채워진다.
 *
 * <p>확인하는 것은 셋이다.
 * <ul>
 *   <li>{@code complaintFetchJob} 이 <b>종료 요약 로그와 같은 수치</b>를 한 행으로 남기는가
 *       — 지오코딩 지표를 서로 다른 값으로 스텁해 <b>컬럼이 뒤바뀌지 않았는지</b>까지 본다</li>
 *   <li>🔴 Job 이 {@code FAILED} 여도 행이 남는가({@code REQUIRES_NEW}) — 실패 경로의 롤백에
 *       휩쓸리면 "왜 죽었는지"가 통째로 사라진다</li>
 *   <li>🔒 {@code failure_message} 에 자격증명이 실리지 않는가</li>
 * </ul>
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다</b> — Job 은 자기 트랜잭션에서 커밋하므로 테스트
 * 트랜잭션으로 감싸도 롤백되지 않고, 오히려 {@code REQUIRES_NEW} 로 커밋되는 이력을 관찰할 수
 * 없게 된다. 정리는 명시적 DELETE 로 한다({@code ComplaintFetchJobTest} 와 같은 관례).
 *
 * <p>샘플은 {@code AAA-MOCK-} 접두라 실데이터({@code EPP-}/{@code SPP-})와 겹치지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 키·네트워크 없이 돌린다(mock 샘플 재생).
        "complaint.api.mode=mock",
        // 청크 1 — 앞 청크는 커밋되고 실패한 청크만 사라지는 상태를 재현한다.
        "batch.complaint.chunk-size=1",
        // 테스트 컨텍스트에 02:00 크론이 등록될 필요는 없다.
        "complaint.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class BatchRunHistoryTest {

    /** 샘플 6행 중 미분류(99)가 되는 유형 — 지오코딩을 건너뛴다. */
    private static final String ETC_ADDR = "제주특별자치도 제주시 삼도이동 1010";

    /** {@code mock/complaint-sample.json} 의 신고일(6행). */
    private static final String SAMPLE_DAY = "2026-06-30";

    private static final String SAMPLE_PREFIX = "AAA-MOCK-%";

    /** 제주시청 인근 — 실제 경계 폴리곤 안이라 1단 {@code ST_Contains} 로 매칭된다. */
    private static final Coordinate JEJU_POINT =
            new Coordinate(new BigDecimal("33.4996000000"), new BigDecimal("126.5312000000"));

    @MockitoBean
    private CompositeGeocoder geocoder;

    @Autowired
    private JobLauncher jobLauncher;

    @MockitoBean
    private jeju.datahub.batch.admdst.AdmDistResolver admDistResolver;

    @Autowired
    private Job complaintFetchJob;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void stubGeocoding() {
        deleteTemporaryRows();
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.found(JEJU_POINT));
        when(admDistResolver.resolve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(jeju.datahub.batch.admdst.AdmDistMatch.matched("50110107"));
    }

    /**
     * 임시 원천·적재분만 지운다. <b>{@code batch.tb_btch_excn_hstry} 은 지우지 않는다</b> —
     * Spring Batch 메타({@code batch_job_execution})가 테스트 실행분을 그대로 남기는 것과 같은
     * 취급이고, 실행 이력을 테스트가 지우기 시작하면 <b>실배치 이력까지 함께 날릴</b> 위험이 있다.
     */
    @AfterEach
    void deleteTemporaryRows() {
        jdbc.update("DELETE FROM batch.tb_geocode_skip WHERE src_se = 'CVLCPT' AND trgt_id LIKE 'AAA-%'");
        jdbc.update("DELETE FROM parking.tb_cvlcpt WHERE cvlcpt_id LIKE 'AAA-%'");
    }

    @Test
    @DisplayName("종료 요약과 같은 수치를 한 행으로 남긴다 — unknown_types 는 JSONB")
    void recordsRunWithSummaryMetrics() throws Exception {
        // 지오코딩 4 지표를 서로 다른 값으로 둬 컬럼이 뒤바뀌면 바로 드러나게 한다.
        when(geocoder.apiLookups()).thenReturn(7L);
        when(geocoder.cacheHits()).thenReturn(3L);
        when(geocoder.placeRecoveries()).thenReturn(5L);
        when(geocoder.strippedRecoveries()).thenReturn(1L);

        JobExecution execution = runFetch(SAMPLE_DAY);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Map<String, Object> run = runRow(execution.getId());
        assertThat(run.get("job_nm")).isEqualTo("complaintFetchJob");
        assertThat(run.get("status")).isEqualTo("COMPLETED");
        assertThat(run.get("run_mode")).isEqualTo("daily");
        assertThat(run.get("target_range")).isEqualTo(SAMPLE_DAY);
        assertThat(run.get("read_cnt")).isEqualTo(7L);
        assertThat(run.get("skip_geocode_failed")).isEqualTo(0L);
        assertThat(run.get("skip_emd_unresolved")).isEqualTo(0L);
        assertThat(run.get("filtered_transient")).isEqualTo(0L);
        assertThat(run.get("geocode_api_lookups")).isEqualTo(7L);
        assertThat(run.get("geocode_cache_hits")).isEqualTo(3L);
        // 3단(장소 검색)과 4단(부번제거)은 성격이 달라 컬럼도 따로다 — 합치면 정밀도 손실 규모를 잃는다.
        assertThat(run.get("geocode_place_recoveries")).isEqualTo(5L);
        assertThat(run.get("geocode_stripped")).isEqualTo(1L);
        // 샘플 7행 중 주차민원만 지오코딩을 탄다. 미분류(99)와 원천이 좌표를 준 행(0000007)은
        // 지오코딩 자체를 건너뛴다.
        assertThat((Long) run.get("unclsf_cnt")).isPositive();
        assertThat(run.get("failure_message")).isNull();
        // 진입점이 싣지 않은 실행은 세 칸 모두 NULL 이다(지어내지 않는다).
        assertThat(run.get("excn_se_cd")).isNull();
        assertThat(run.get("excutr_id")).isNull();
        assertThat(run.get("up_excn_id")).isNull();
        assertThat(run.get("started_dt")).isNotNull();
        assertThat(run.get("ended_dt")).isNotNull();
        assertThat((Long) run.get("duration_ms")).isNotNegative();

        // 어떤 값이 99 를 밀어올렸는지가 원천 표기 변경 탐지의 핵심이라 JSONB 로 남긴다.
        assertThat(jdbc.queryForObject(
                "SELECT unknown_types ->> '쓰레기무단투기' FROM batch.tb_btch_excn_hstry WHERE job_execution_id = ?",
                String.class, execution.getId())).isEqualTo("1");
    }

    @Test
    @DisplayName("🔴 Job 이 FAILED 여도 행이 남는다 — 🔒 failure_message 에 키는 마스킹된다")
    void recordsFailedRunWithRedactedFailureMessage() throws Exception {
        when(geocoder.geocode(anyString())).thenThrow(new GeocodeQuotaExceededException(
                "VWorld 호출 한도 초과(OVER_REQUEST_LIMIT) url=https://api.vworld.kr/req/address?key=TOP-SECRET-123"));

        JobExecution execution = runFetch(SAMPLE_DAY);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);

        // 🔴 실패 경로의 롤백에 휩쓸리지 않아야 한다 — 이 행이 없으면 "왜 죽었는지"가 로그뿐이다.
        Map<String, Object> run = runRow(execution.getId());
        assertThat(run.get("status")).isEqualTo("FAILED");

        String failure = (String) run.get("failure_message");
        assertThat(failure).contains("GeocodeQuotaExceededException").contains("OVER_REQUEST_LIMIT");
        // 🔒 예외 메시지에 섞여 온 키가 이력 테이블에 남으면 안 된다.
        assertThat(failure).doesNotContain("TOP-SECRET-123").contains("key=***");
        assertThat(failure.length()).isLessThanOrEqualTo(
                BatchRunHistoryRecorder.MAX_FAILURE_MESSAGE_CHARS);
    }

    @Test
    @DisplayName("v_btch_excn_hstry 가 최근 실행부터 요약을 돌려준다")
    void exposesHistoryView() throws Exception {
        long firstId = runFetch(SAMPLE_DAY).getId();
        long secondId = runFetch(SAMPLE_DAY).getId();

        List<Map<String, Object>> history = jdbc.queryForList("""
                SELECT job_execution_id, job_nm, status, run_mode, target_range,
                       duration_sec, read_cnt, write_cnt, skip_cnt, filtered_transient,
                       geocode_api_lookups, geocode_cache_hits, unclsf_cnt
                  FROM batch.v_btch_excn_hstry
                 WHERE job_execution_id IN (?, ?)""", firstId, secondId);

        assertThat(history).hasSize(2);
        assertThat(history).extracting(row -> row.get("job_nm"))
                .containsOnly("complaintFetchJob");
        // 스킵 사유 2종의 합 — 지오코딩이 전부 성공한 실행이라 0 이다(NULL 이 아니다).
        assertThat(history).allSatisfy(row -> assertThat(row.get("skip_cnt")).isEqualTo(0L));
    }

    @Test
    @DisplayName("비식별 파라미터 excnSe·excutrId·prntExcnId 가 새 컬럼에 남는다")
    void recordsExcnSeColumns() throws Exception {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addLong("runAt", System.nanoTime())
                .addString("mode", "daily")
                .addString("targetDate", SAMPLE_DAY);
        ExcnSe.manual(builder, "tester01");
        builder.addLong(ExcnSe.PARAM_PRNT_EXCN_ID, 4242L, false);

        JobExecution execution = jobLauncher.run(complaintFetchJob, builder.toJobParameters());

        Map<String, Object> run = runRow(execution.getId());
        assertThat(run.get("excn_se_cd")).isEqualTo(ExcnSe.MANUAL);
        assertThat(run.get("excutr_id")).isEqualTo("tester01");
        assertThat(run.get("up_excn_id")).isEqualTo(4242L);
    }

    /** {@code mode}/{@code targetDate} 는 String 파라미터다 — run_mode/target_range 가 이 값을 읽는다. */
    private JobExecution runFetch(String targetDate) throws Exception {
        return jobLauncher.run(complaintFetchJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime())
                .addString("mode", "daily")
                .addString("targetDate", targetDate)
                .toJobParameters());
    }

    private Map<String, Object> runRow(long jobExecutionId) {
        return jdbc.queryForMap(
                "SELECT * FROM batch.tb_btch_excn_hstry WHERE job_execution_id = ?", jobExecutionId);
    }

}
