package jeju.datahub.batch.cvlcpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import jeju.datahub.batch.admdst.AdmDistMatch;
import jeju.datahub.batch.admdst.AdmDistResolver;
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
 * {@code complaintFetchJob} 통합 검증 — <b>수집 → 분류 → 지오코딩 → 적재</b> 한 Job.
 *
 * <h2>🔁 2026-08-24 — ①②가 합쳐지면서 이 테스트도 하나가 됐다</h2>
 * 종전에는 ①(수집→origin)을 이 파일이, ②(origin→tb_cvlcpt, 스킵·중단 정책)를
 * {@code CvlcptGeocodeJobTest} 가 나눠 봤다. 원본(staging) 폐지로 Job 이 하나가 됐으므로
 * <b>중단 정책·스킵 기록 검증도 여기로 합류</b>했다({@code CvlcptGeocodeJobTest} 는 삭제).
 *
 * <p>확인하는 것:
 * <ul>
 *   <li>API 응답 26컬럼이 {@code parking.tb_cvlcpt} 의 제자리에 들어가는가 (원문 보존 포함)</li>
 *   <li>감정({@code aprs_cd})이 원천 원문 → 코드로 변환되는가</li>
 *   <li><b>발생일시({@code ocrn_dt})에 시·분·초가 남는가</b> — 자정으로 뭉개지지 않아야 한다</li>
 *   <li>{@code prk_cvlcpt_type} 원문이 적재되는가 (재분류 근거)</li>
 *   <li><b>원천이 가공값을 채워 보낸 행은 지오코딩·공간매칭을 건너뛰는가</b></li>
 *   <li>스킵 2 사유는 기록하고 완주, 쿼터 초과는 즉시 FAILED (중단 정책)</li>
 *   <li>같은 구간을 두 번 돌려도 행 수가 늘지 않는가 (upsert 멱등성)</li>
 * </ul>
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다</b> — Job 은 자기 트랜잭션에서 커밋하므로 테스트
 * 트랜잭션으로 감싸도 롤백되지 않고, 오히려 {@code REQUIRES_NEW} 로 커밋되는 스킵 기록을
 * 관찰할 수 없게 된다. 정리는 명시적 DELETE 로 한다.
 *
 * <p>지오코딩만 스텁으로 바꾸고 Reader·Processor·Writer·리스너·트랜잭션은 <b>운영과 같은 빈</b>을
 * 쓴다. 쿼터 초과나 특정 주소의 실패는 실호출로 재현할 수 없어서다.
 *
 * <p>샘플 {@code cvlcpt_id} 는 {@code AAA-MOCK-} 로 시작해 원천 실데이터({@code EPP-}/{@code SPP-})와
 * 절대 겹치지 않는다 — <b>실데이터를 건드리지 않고</b> 검증한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 키·네트워크 없이 돌기 위한 스위치. 이 값이 없으면 real 어댑터가 붙어 CI 가 깨진다.
        "complaint.api.mode=mock",
        // 테스트 컨텍스트에서 02:00 크론이 등록될 필요는 없다.
        "complaint.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
        // 6행짜리 샘플이라 청크를 작게 잡아 여러 청크로 나뉘는 경로도 지나가게 한다.
        // 청크 1 이면 "직전 청크까지는 적재되고 실패한 청크만 사라진다" 도 재현된다.
        "batch.complaint.chunk-size=1"
})
class ComplaintFetchJobTest {

    /**
     * {@code mock/complaint-sample.json} 의 <b>발생일시({@code ocrn_dt})</b> 가 담긴 일자.
     * 조회 필터가 {@code dclr_ymd} 에서 {@code ocrn_dt} 로 바뀌었으므로 이 값이 기준이다.
     */
    private static final String SAMPLE_DAY = "2026-06-30";

    /** 샘플 7행 중 원천이 가공값(유형·행정동·좌표·유입경로)을 채워 보낸 유일한 행. */
    private static final String SOURCE_PROVIDED_ID = "AAA-MOCK-0000007";
    private static final String SOURCE_EMD_CD = "50110105";

    private static final String SAMPLE_PREFIX = "AAA-MOCK-%";

    /** 소화전(01) 샘플 — 주차민원이라 지오코딩 경로를 탄다. */
    private static final String PARKING_ID = "AAA-MOCK-0000003";
    private static final String PARKING_ADDR = "제주특별자치도 제주시 이도이동 1176";

    /** 제주시청 인근 — 실제 경계 폴리곤 안이라 1차 공간매칭({@code ST_Contains})으로 이도이동 {@code 50110104} 가 나온다. */
    private static final Coordinate JEJU_POINT =
            new Coordinate(new BigDecimal("33.4996000000"), new BigDecimal("126.5312000000"));

    /** mock {@code AdmDistResolver} 반환값 — {@code JEJU_POINT} 의 실제 행정동은 이도이동 {@code 50110104} 다({@code 50110107} 은 건입동). */
    private static final String EMD_CD = "50110107";

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
        when(geocoder.geocode(anyString())).thenReturn(GeocodeOutcome.found(JEJU_POINT));
        when(admDistResolver.resolve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(AdmDistMatch.matched(EMD_CD));
        // 원천이 보낸 emd_cd 는 실재하는 코드일 때만 채택된다(FK 위반 방지). 샘플의 값이 그 경우다.
        when(admDistResolver.isKnownEmdCd(SOURCE_EMD_CD)).thenReturn(true);
    }

    @AfterEach
    void deleteSampleRows() {
        jdbc.update("DELETE FROM batch.tb_geocode_skip WHERE src_se = 'CVLCPT' AND trgt_id LIKE ?",
                SAMPLE_PREFIX);
        jdbc.update("DELETE FROM parking.tb_cvlcpt WHERE origin_pk LIKE ?", SAMPLE_PREFIX);
    }

    @Test
    @DisplayName("수집분이 tb_cvlcpt 에 바로 적재되고 prk_cvlcpt_type 원문도 함께 남는다")
    void loadsRowsDirectlyKeepingSourceTypeName() throws Exception {
        JobExecution execution = runFetch(SAMPLE_DAY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(loadedIds()).hasSize(7);

        // 🔁 2026-09-01 — 리터럴 NULL 을 그만두고 원천 원문을 적재한다. 분류에 쓴 입력이
        //    행에 남아 있어야 규칙을 고쳤을 때 적재분을 다시 분류할 수 있다.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM parking.tb_cvlcpt
                 WHERE origin_pk LIKE ? AND prk_cvlcpt_type IS NOT NULL""",
                Long.class, SAMPLE_PREFIX)).isEqualTo(4L);

        // 원천값이 있어도 분류 결과는 CASE 와 같다 — 실원천 78,386행 전수 대조에서 불일치 0 이었다.
        assertThat(jdbc.queryForObject(
                "SELECT cvlcpt_type_cd FROM parking.tb_cvlcpt WHERE origin_pk = 'AAA-MOCK-0000002'",
                String.class)).isEqualTo("02");
    }

    @Test
    @DisplayName("🔴 원천이 가공값을 보낸 행은 지오코딩·공간매칭 없이 그 값으로 적재된다")
    void usesSourceProvidedDerivedValuesWithoutLookups() throws Exception {
        runFetch(SAMPLE_DAY);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT cvlcpt_type_cd, emd_cd, lat, lot, src_cd
                  FROM parking.tb_cvlcpt WHERE origin_pk = ?""", SOURCE_PROVIDED_ID);

        // dclr_type='인도' 의 CASE 결과는 02 지만 원천이 03 을 줬으므로 03 이 이긴다.
        assertThat(row.get("cvlcpt_type_cd")).isEqualTo("03");
        assertThat(row.get("emd_cd")).isEqualTo(SOURCE_EMD_CD);
        assertThat((java.math.BigDecimal) row.get("lat"))
                .isEqualByComparingTo(new BigDecimal("33.5133"));
        assertThat((java.math.BigDecimal) row.get("lot"))
                .isEqualByComparingTo(new BigDecimal("126.53"));
        assertThat(row.get("src_cd")).isEqualTo("PORTAL");

        // 🔴 요점은 값이 아니라 **부르지 않았다는 것**이다 — 지오코딩 쿼터를 태우지 않는다.
        verify(geocoder, never()).geocode("제주특별자치도 제주시 일도이동 1234");
        verify(admDistResolver, never()).resolve(
                new BigDecimal("33.5133000000"), new BigDecimal("126.5300000000"));
    }

    @Test
    @DisplayName("🔴 발생일시에 시·분·초가 남는다 — 신고일 자정으로 뭉개지지 않는다")
    void keepsTimeOfDayInOccurrenceTimestamp() throws Exception {
        runFetch(SAMPLE_DAY);

        // 원천 dclr_at 을 그대로 실어 나른다. 종전에는 dclr_ymd.atStartOfDay() 라 전 행이 00:00:00 이었다.
        assertThat(jdbc.queryForObject(
                "SELECT ocrn_dt FROM parking.tb_cvlcpt WHERE origin_pk = 'AAA-MOCK-0000001'",
                Timestamp.class)).isEqualTo(Timestamp.valueOf("2026-06-30 09:15:00"));

        // 신고일과 발생일시가 다른 행 — dclr_ymd 파생으로는 살릴 수 없는 값이다.
        // 이 행은 dclr_ymd 가 하루 앞(2026-06-29)인데도 조회에 걸린다: 필터가 ocrn_dt 이기 때문이다.
        assertThat(jdbc.queryForObject(
                "SELECT ocrn_dt FROM parking.tb_cvlcpt WHERE origin_pk = 'AAA-MOCK-0000002'",
                Timestamp.class)).isEqualTo(Timestamp.valueOf("2026-06-30 20:17:00"));
        assertThat(jdbc.queryForObject(
                "SELECT dclr_ymd FROM parking.tb_cvlcpt WHERE origin_pk = 'AAA-MOCK-0000002'",
                String.class)).isEqualTo("2026-06-29");

        // 원천이 발생일시를 주지 못한 행만 신고일 자정으로 대체된다(ocrn_dt 는 NOT NULL).
        assertThat(jdbc.queryForObject(
                "SELECT ocrn_dt FROM parking.tb_cvlcpt WHERE origin_pk = 'AAA-MOCK-0000005'",
                Timestamp.class)).isEqualTo(Timestamp.valueOf(SAMPLE_DAY + " 00:00:00"));
    }

    @Test
    @DisplayName("감정은 원천 원문(긍정/부정/보통) → APRS_CD 코드로 변환된다. 결측은 NULL")
    void mapsSentimentToAppraisalCode() throws Exception {
        runFetch(SAMPLE_DAY);

        assertThat(aprsCd("AAA-MOCK-0000001")).isEqualTo("NEUTRAL");   // 보통
        assertThat(aprsCd("AAA-MOCK-0000002")).isEqualTo("NEGATIVE");  // 부정
        assertThat(aprsCd("AAA-MOCK-0000003")).isEqualTo("POSITIVE");  // 긍정

        // 🔴 결측은 코드값을 지어내지 않는다 — "분석 안 함"과 "중립"이 뭉개지면 집계가 오염된다.
        assertThat(aprsCd("AAA-MOCK-0000004")).isNull();
    }

    @Test
    @DisplayName("원문 컬럼이 제자리에 들어간다 — 개행 있는 제목·JSON null·'-' placeholder 포함")
    void mapsAllColumns() throws Exception {
        runFetch(SAMPLE_DAY);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT dclr_ymd, aplcnt_se, dclr_fld, src_path, dclr_ocrn_rgn, ttl, cvlcpt_cn,
                       prgrs_stts, ans_prcs_stts, ans_ymd, rwrd_yn, prcs_inst_cnt, prcs_inst_nm,
                       prcs_inst_pic_nm, dclr_grp_nm, mbr_grp_nm, dclr_type, src_cd
                  FROM parking.tb_cvlcpt WHERE origin_pk = 'AAA-MOCK-0000001'""");

        assertThat(row.get("dclr_ymd")).isEqualTo(SAMPLE_DAY);
        assertThat(row.get("aplcnt_se")).isEqualTo("개인");
        assertThat(row.get("dclr_fld")).isEqualTo("교통");
        assertThat(row.get("dclr_ocrn_rgn")).isEqualTo("제주특별자치도 서귀포시 성산읍 고성리 297-8");
        assertThat((String) row.get("ttl")).contains("\n");   // 원본 보존 — CSV 였다면 깨진다
        assertThat(row.get("prgrs_stts")).isEqualTo("완료");
        assertThat(row.get("ans_prcs_stts")).isEqualTo("수용");
        // 원천은 '2026.7.1' 로 준다 — load_dt 조립이 LocalDate.parse 로 읽으므로 ISO 로 정규화된다.
        assertThat(row.get("ans_ymd")).isEqualTo("2026-07-01");
        assertThat(row.get("rwrd_yn")).isEqualTo("X");
        assertThat(row.get("prcs_inst_cnt")).isEqualTo(1);
        assertThat(row.get("prcs_inst_pic_nm")).isEqualTo("홍길동");
        assertThat(row.get("dclr_grp_nm")).isNull();               // JSON null → SQL NULL
        assertThat(row.get("mbr_grp_nm")).isNull();
        assertThat(row.get("dclr_type")).isEqualTo("장애인전용구역");

        // 원문(src_path)과 변환값(src_cd)이 함께 남는다 — 매핑을 고쳤을 때 되돌아볼 근거다.
        assertThat(row.get("src_path")).isEqualTo("모바일앱");
        assertThat(row.get("src_cd")).isEqualTo("APP");

        // 🔒 NOT NULL 개인정보 컬럼이 결측이면 빈 문자열이 아니라 '-' 다.
        assertThat(jdbc.queryForObject(
                "SELECT prcs_inst_pic_nm FROM parking.tb_cvlcpt WHERE origin_pk = 'AAA-MOCK-0000006'",
                String.class)).isEqualTo("-");
    }

    @Test
    @DisplayName("스킵 2 사유는 기록하고 Job 은 완주한다 — note 에 단계 내역이 남는다")
    void recordsSkipWithDiagnosticNote() throws Exception {
        String detail = "parcel=NOT_FOUND, road=NOT_FOUND, stripped(제주특별자치도 제주시 이도이동 1176)=NOT_FOUND";
        when(geocoder.geocode(PARKING_ADDR)).thenReturn(GeocodeOutcome.notFound(detail));

        JobExecution execution = runFetch(SAMPLE_DAY);

        // 스킵은 정상 경로다 — 건수로 Job 을 죽이지 않는다(skipLimit=MAX_VALUE).
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(loadedIds()).doesNotContain(PARKING_ID);

        // 🔴 스킵 기록이 청크 롤백에도 살아남아야 한다(REQUIRES_NEW). 사라지면 다음 실행이 같은
        //    주소를 또 조회하고, 그 사실을 아무도 모른다.
        Map<String, Object> skip = jdbc.queryForMap("""
                SELECT fail_rsn, note, trgt_addr FROM batch.tb_geocode_skip
                 WHERE src_se = 'CVLCPT' AND trgt_id = ?""", PARKING_ID);
        assertThat(skip.get("fail_rsn")).isEqualTo("GEOCODE_FAILED");
        assertThat(skip.get("trgt_addr")).isEqualTo(PARKING_ADDR);
        assertThat(skip.get("note")).isEqualTo(detail);
    }

    @Test
    @DisplayName("🔴 쿼터 초과는 스킵이 아니라 즉시 중단 — Job FAILED, 스킵 테이블에 기록 없음")
    void failsFastOnQuotaExceeded() throws Exception {
        when(geocoder.geocode(anyString()))
                .thenThrow(new GeocodeQuotaExceededException("OVER_REQUEST_LIMIT"));

        JobExecution execution = runFetch(SAMPLE_DAY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        // 쿼터 초과 건은 실패 확정이 아니다 — 스킵 테이블에 넣으면 영구 제외로 굳는다.
        assertThat(skipRowCount()).isZero();
        assertThatThrownBy(() -> {
            throw execution.getAllFailureExceptions().get(0);
        }).satisfies(t -> assertThat(causedByQuota(t)).isTrue());
    }

    @Test
    @DisplayName("🔴 일시 장애는 스킵 기록 없이 이번 실행에서만 제외된다 — 다음 실행에 다시 들어온다")
    void filtersTransientWithoutRecordingSkip() throws Exception {
        when(geocoder.geocode(PARKING_ADDR)).thenReturn(GeocodeOutcome.transientFailure("타임아웃"));

        JobExecution execution = runFetch(SAMPLE_DAY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(loadedIds()).doesNotContain(PARKING_ID);
        // 기록이 남으면 다음 실행이 이 행을 영구히 건너뛴다(D8 이 고친 바로 그 버그).
        assertThat(skipRowCount()).isZero();
    }

    @Test
    @DisplayName("같은 targetDate 로 두 번 돌려도 행 수가 늘지 않는다 — upsert 멱등성")
    void isIdempotentAcrossReruns() throws Exception {
        runFetch(SAMPLE_DAY);
        int afterFirst = loadedIds().size();

        // 값이 어긋난 상황을 흉내낸다 — 재실행이 원천 최신값으로 되돌려야 한다.
        jdbc.update("UPDATE parking.tb_cvlcpt SET prgrs_stts = '변경됨' WHERE origin_pk = ?",
                "AAA-MOCK-0000001");
        JobExecution second = runFetch(SAMPLE_DAY);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(loadedIds()).hasSize(afterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT prgrs_stts FROM parking.tb_cvlcpt WHERE origin_pk = 'AAA-MOCK-0000001'",
                String.class)).isEqualTo("완료");
    }

    @Test
    @DisplayName("데이터가 없는 일자는 0건으로 정상 완료한다 — 필터가 ocrn_dt 라 0000002 도 걸리지 않는다")
    void completesWithZeroRowsWhenDayIsEmpty() throws Exception {
        JobExecution execution = runFetch("2026-06-29");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(loadedIds()).isEmpty();
    }

    /** 쿼터 예외가 사슬 어딘가에 있는지 — 프레임워크가 감싸도 "왜 죽었는지"는 남아야 한다. */
    private static boolean causedByQuota(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof GeocodeQuotaExceededException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    private JobExecution runFetch(String targetDate) throws Exception {
        return jobLauncher.run(complaintFetchJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime())   // 매 실행 새 JobInstance
                .addString("mode", "daily")
                .addString("targetDate", targetDate)
                .toJobParameters());
    }

    private List<String> loadedIds() {
        return jdbc.queryForList(
                "SELECT origin_pk FROM parking.tb_cvlcpt WHERE origin_pk LIKE 'AAA-MOCK-%' ORDER BY 1",
                String.class);
    }

    private String aprsCd(String cvlcptId) {
        return jdbc.queryForObject(
                "SELECT aprs_cd FROM parking.tb_cvlcpt WHERE origin_pk = ?", String.class, cvlcptId);
    }

    private long skipRowCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM batch.tb_geocode_skip
                 WHERE src_se = 'CVLCPT' AND trgt_id LIKE 'AAA-MOCK-%'""", Long.class);
    }
}
