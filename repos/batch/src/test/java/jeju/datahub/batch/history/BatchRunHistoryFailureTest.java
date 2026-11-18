package jeju.datahub.batch.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import jeju.datahub.batch.admdst.AdmDistMatch;
import jeju.datahub.batch.admdst.AdmDistResolver;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.geocode.Coordinate;
import jeju.datahub.batch.geocode.GeocodeOutcome;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 🔴 <b>이력 기록이 실패해도 Job 은 죽지 않는다</b>.
 *
 * <p>이력은 관측 수단이지 배치의 목적이 아니다. {@code batch.tb_btch_excn_hstry} 이 없거나 DB 커넥션이
 * 잠깐 끊겼다고 <b>15만 행 수집·가공이 FAILED 로 뒤집히면</b> 배보다 배꼽이 크다. 그래서
 * 리스너가 {@code record()} 를 try/catch 로 감싸고 WARN 만 남긴다.
 *
 * <p>⚠️ Spring Batch 도 {@code afterJob} 예외를 잡아 로그만 남기므로({@code AbstractJob}:
 * "Exception encountered in afterJob callback") <b>이 테스트만으로는 try/catch 유무를 구분하지
 * 못한다.</b> 그럼에도 리스너에 try/catch 를 두는 이유는 ②의 {@code CvlcptJobSummaryListener}
 * 에서 <b>기록 뒤에 오는 비율 경고 로그가 계속 실행돼야</b> 하기 때문이다 — 프레임워크에 맡기면
 * 거기서 {@code afterJob} 이 통째로 중단된다.
 *
 * <p>기록 실패를 강제하려면 {@link BatchRunHistoryRecorder} 자체를 mock 으로 갈아끼워야 해
 * ({@code @MockitoBean}) 실기록을 검증하는 {@link BatchRunHistoryTest} 와 컨텍스트를 분리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "complaint.api.mode=mock",
        "batch.complaint.chunk-size=2",
        "complaint.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class BatchRunHistoryFailureTest {

    private static final String SAMPLE_DAY = "2026-06-30";

    private static final String SAMPLE_PREFIX = "AAA-MOCK-%";

    @MockitoBean
    private BatchRunHistoryRecorder recorder;

    /**
     * 🔁 2026-08-24 — 1단화로 이 Job 이 지오코딩까지 한다. 스텁하지 않으면 외부 API 를
     * 실제로 부르고, 실패한 행이 스킵돼 "수집분이 온전한가" 라는 이 테스트의 관심사가 흐려진다.
     */
    @MockitoBean
    private CompositeGeocoder geocoder;

    @MockitoBean
    private AdmDistResolver admDistResolver;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job complaintFetchJob;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void stubGeocoding() {
        deleteSampleRows();
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.found(new Coordinate(
                new BigDecimal("33.4996000000"), new BigDecimal("126.5312000000"))));
        when(admDistResolver.resolve(any(), any())).thenReturn(AdmDistMatch.matched("50110107"));
    }

    @AfterEach
    void deleteSampleRows() {
        jdbc.update("DELETE FROM parking.tb_cvlcpt WHERE cvlcpt_id LIKE ?", SAMPLE_PREFIX);
    }

    @Test
    @DisplayName("기록이 터져도 Job 은 COMPLETED 로 끝난다 — 수집분도 그대로 남는다")
    void jobSurvivesHistoryRecordingFailure() throws Exception {
        doThrow(new DataAccessResourceFailureException("이력 테이블 접속 실패(강제)"))
                .when(recorder).record(any(), any());

        JobExecution execution = jobLauncher.run(complaintFetchJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime())
                .addString("mode", "daily")
                .addString("targetDate", SAMPLE_DAY)
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(recorder).record(any(), any());
        // 기록은 실패했으니 행은 없다 — 그래도 수집 결과는 온전하다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM batch.tb_btch_excn_hstry WHERE job_execution_id = ?",
                Long.class, execution.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt WHERE cvlcpt_id LIKE ?",
                Long.class, SAMPLE_PREFIX)).isEqualTo(7L);
    }
}
