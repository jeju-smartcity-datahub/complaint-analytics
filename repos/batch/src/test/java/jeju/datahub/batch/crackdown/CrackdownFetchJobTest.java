package jeju.datahub.batch.crackdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jeju.datahub.batch.crackdown.api.CrackdownOriginRow;
import jeju.datahub.batch.crackdown.api.DhubCrackdownApiClient;
import jeju.datahub.batch.crackdown.geocode.CrackdownAddressReader;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeAddress;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeAddress.AddressKind;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeAddress.GeocodeInput;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.geocode.Coordinate;
import jeju.datahub.batch.geocode.GeocodeOutcome;
import jeju.datahub.batch.geocode.GeocodeQuotaExceededException;

/**
 * {@code crackdownFetchJob} 통합 검증 — <b>수집 → 좌표 사전 전파 → 주소 단위 지오코딩</b> 한 Job.
 *
 * <h2>🔴 테이블 전체를 대상으로 하는 두 빈만 픽스처 한정으로 교체한다</h2>
 * Job 은 자기 트랜잭션으로 커밋하므로 테스트 롤백이 안 먹는다. 운영 빈 그대로 돌리면
 * {@code crackdownCoordDictStep} 이 실 행 약 9.4만 건을 전파·커밋하고, {@code crackdownGeocodeStep} Reader 가
 * 실 주소 약 1만 개를 mock 지오코더로 흘려 {@code tb_geocode_skip(CRDN)} 을 오염시킨다. 그래서 중첩
 * {@link FixtureConfig}({@code @TestConfiguration} — {@code @Configuration} 이면 운영 설정을 대체한다)가
 * Tasklet·Reader 를 {@code @Primary} 로 덮는다. 나머지(Job 조립·faultTolerant·스킵 리스너·Recorder·이력
 * 리스너·Processor·Writer·실 {@code AdmDistResolver})는 운영 빈이다.
 * <ul>
 *   <li>{@code @TestBean} 을 쓰지 않는다 — static 무인자 팩토리라 주입·{@code afterPropertiesSet()}·
 *       {@code @StepScope} 가 적용되지 않는다.</li>
 *   <li>이 교체는 {@code CrackdownFetchJobConfig} 의 가공 Step 이 협력 객체를 <b>파라미터 타입으로 주입</b>받을
 *       때만 먹는다. 파라미터 이름이 {@code tasklet}·{@code reader} 라 {@code @Primary} 가 사라지면 이름 폴백이
 *       없어 조용히 운영 빈으로 가지 않고 {@code NoUniqueBeanDefinitionException} 으로 기동부터 죽는다.</li>
 *   <li>그래도 마지막 방어선으로 <b>실 행·실 스킵 수치가 클래스 시작 시점과 같음</b>을 매 케이스 끝에 단언한다.</li>
 * </ul>
 *
 * <h2>픽스처</h2>
 * 주소 접두 {@code 테스트단속주소-} 로 식별·정리한다(수집 행의 {@code src_hash} 는 원천 7컬럼 SHA-256 이라 접두를
 * 붙일 수 없다). A·C 는 지번·도로명 정규식에 안 걸리는 PLACE, B 는 {@code 동 1234} 가 걸리는 JIBUN 이다.
 * 실행 이력 표({@code batch.tb_btch_excn_hstry})는 지우지 않는다(선례 {@code RiskAlertJudgeJobTest}).
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다</b> — {@code REQUIRES_NEW} 로 커밋되는 스킵 기록을 관찰해야 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // DhubCrackdownItemReader.open() 이 API 호출 전에 requireCredentials() 를 부른다.
        // gradle test 는 루트 .env 를 읽지 않아 비어 있으면 수집 Step 이 FAILED 다.
        "dhub.email=test@example.com",
        "dhub.password=test",
        // 테스트 컨텍스트에 02:00 크론이 등록될 필요는 없다.
        "complaint.sync.scheduled=false",
        "pblpklt.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class CrackdownFetchJobTest {

    private static final String ADDR_PREFIX_LIKE = "테스트단속주소-%";
    private static final String ADDR_A = "테스트단속주소-A";
    /** {@code 동 1234} 가 지번 정규식에 걸려 JIBUN. 첫 토큰은 {@code tb_admdst.emd_nm} 에 없어 접두 없음. */
    private static final String ADDR_B = "테스트단속주소-B 노형동 1234";
    private static final String ADDR_C = "테스트단속주소-C";

    /** 사전 A 픽스처 행 — {@code char(32)} 이하. */
    private static final String DICT_HASH_A = "AAA-CRDNTEST-A";

    /**
     * 제주시청 인근 — {@code ComplaintFetchJobTest} 의 JEJU_POINT. 실 경계 폴리곤 안이라 1차 {@code ST_Contains}
     * 로 매칭된다. ⚠️ 행정동 코드는 하드코딩하지 않고 {@link #expectedEmdCd()} 가 DB 에서 구한다.
     */
    private static final Coordinate JEJU_POINT =
            new Coordinate(new BigDecimal("33.4996000000"), new BigDecimal("126.5312000000"));

    /** 사전 A 픽스처의 {@code emd_cd} — FK 라 실재 코드여야 한다(이도이동, 로컬 {@code ST_Contains} 실측). */
    private static final String DICT_EMD_CD = "50110104";

    private static final String REAL_ROWS_SQL = """
            SELECT count(*) AS total, count(lat) AS lat_cnt, count(emd_cd) AS emd_cnt
              FROM parking.tb_crdn_dsctn WHERE crdn_addr NOT LIKE '테스트단속주소-%'""";

    private static final String REAL_SKIP_SQL = """
            SELECT count(*) FROM batch.tb_geocode_skip
             WHERE src_se = 'CRDN' AND trgt_addr NOT LIKE '테스트단속주소-%'""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 클래스 시작 시점의 실 DB 수치 — 첫 {@code @BeforeEach} 에서 한 번만 잡는다(하드코딩 금지). */
    private static Map<String, Object> realRowsAtStart;
    private static Long realSkipsAtStart;

    @TestConfiguration
    static class FixtureConfig {

        /** 사전 전파 UPDATE 를 픽스처 주소로 좁힌다. 사전 원천(d)은 운영과 같게 전 테이블이다. */
        @Bean
        @Primary
        CrackdownCoordDictionaryTasklet fixtureCoordDictTasklet(JdbcTemplate jdbc) {
            return new CrackdownCoordDictionaryTasklet(jdbc) {
                @Override
                int propagate() {
                    return jdbc.update(CrackdownCoordDictionaryTasklet.UPDATE_SQL
                            + " AND t.crdn_addr LIKE '테스트단속주소-%'");
                }
            };
        }

        /** 좌표 없는 주소 Reader 를 픽스처 주소로 좁힌다. 컨테이너가 만들어 {@code afterPropertiesSet()} 이 불린다. */
        @Bean
        @StepScope
        @Primary
        JdbcPagingItemReader<String> fixtureAddressReader(DataSource dataSource) {
            return CrackdownAddressReader.create(dataSource,
                    CrackdownAddressReader.WHERE_CLAUSE + " AND crdn_addr LIKE '테스트단속주소-%'", 50, 0);
        }
    }

    @MockitoBean
    private DhubCrackdownApiClient apiClient;

    @MockitoBean
    private CompositeGeocoder geocoder;

    @Autowired
    private CrackdownGeocodeAddress addressBuilder;

    @Autowired
    private CrackdownCoordDictionaryTasklet coordDictTasklet;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job crackdownFetchJob;

    @Autowired
    private JdbcTemplate jdbc;

    /** 운영 빌더가 만든 B 의 지오코딩 입력 — 스텁·verify 인자의 단일 출처. */
    private String queryB;

    @BeforeEach
    void setUp() {
        deleteFixtures();
        if (realRowsAtStart == null) {
            realRowsAtStart = jdbc.queryForMap(REAL_ROWS_SQL);
            realSkipsAtStart = jdbc.queryForObject(REAL_SKIP_SQL, Long.class);
        }

        // 교체가 먹었는지 — 운영 Tasklet 이 주입되면 실 행 9.4만 건이 커밋된다. Job 을 돌리기 전에 멈춘다.
        assertThat(coordDictTasklet.getClass()).isNotEqualTo(CrackdownCoordDictionaryTasklet.class);

        // 분류가 어긋나면 "스텁 미호출" 로 헷갈리게 실패하지 않고 여기서 멈춘다(라운드 3 🟡③ — kind 만 단언).
        GeocodeInput inputB = addressBuilder.build(ADDR_B);
        assertThat(inputB.kind()).isEqualTo(AddressKind.JIBUN);
        queryB = inputB.query();

        // 사전 A — 좌표·행정동 있는 1행(시드·이전 실행 역할).
        jdbc.update("""
                INSERT INTO parking.tb_crdn_dsctn (src_hash, crdn_addr, lat, lot, emd_cd)
                VALUES (?, ?, ?, ?, ?)""",
                DICT_HASH_A, ADDR_A, JEJU_POINT.lat(), JEJU_POINT.lot(), DICT_EMD_CD);

    }

    @AfterEach
    void tearDown() {
        deleteFixtures();
        // 🔴 실 DB 불변 — 픽스처 밖 행·스킵이 클래스 시작 시점과 같다.
        assertThat(jdbc.queryForMap(REAL_ROWS_SQL)).isEqualTo(realRowsAtStart);
        assertThat(jdbc.queryForObject(REAL_SKIP_SQL, Long.class)).isEqualTo(realSkipsAtStart);
    }

    private void deleteFixtures() {
        jdbc.update("DELETE FROM parking.tb_crdn_dsctn WHERE crdn_addr LIKE ?", ADDR_PREFIX_LIKE);
        jdbc.update("DELETE FROM batch.tb_geocode_skip WHERE src_se = 'CRDN' AND trgt_addr LIKE ?",
                ADDR_PREFIX_LIKE);
    }

    @Test
    @DisplayName("(1) A 는 사전 전파, B 는 지오코딩 FOUND → COMPLETED, 원천 3행 모두 좌표·행정동")
    void propagatesDictionaryAndGeocodesRemainingAddress() throws Exception {
        givenSource(row(ADDR_A, "2026-09-01 10:00:00"), row(ADDR_A, "2026-09-02 11:00:00"),
                row(ADDR_B, "2026-09-03 12:00:00"));
        when(geocoder.geocode(eq(queryB))).thenReturn(GeocodeOutcome.found(JEJU_POINT));

        JobExecution execution = runJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepNames(execution)).containsExactly(
                "crackdownFetchStep", "crackdownCoordDictStep", "crackdownGeocodeStep");

        // 수집 Step 이 넣은 원천 3행(사전 픽스처 제외) 모두 좌표·행정동.
        List<Map<String, Object>> loaded = jdbc.queryForList("""
                SELECT crdn_addr, lat, lot, emd_cd FROM parking.tb_crdn_dsctn
                 WHERE crdn_addr LIKE ? AND src_hash <> ?""", ADDR_PREFIX_LIKE, DICT_HASH_A);
        assertThat(loaded).hasSize(3);
        assertThat(loaded).allSatisfy(r -> {
            assertThat(r.get("lat")).isNotNull();
            assertThat(r.get("emd_cd")).isNotNull();
        });
        // A 두 행은 사전 값, B 는 지오코딩 좌표 + 실 공간매칭 행정동.
        assertThat(loaded).filteredOn(r -> ADDR_A.equals(r.get("crdn_addr")))
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.get("emd_cd")).isEqualTo(DICT_EMD_CD));
        Map<String, Object> b = loaded.stream().filter(r -> ADDR_B.equals(r.get("crdn_addr"))).findFirst().orElseThrow();
        assertThat((BigDecimal) b.get("lat")).isEqualByComparingTo(JEJU_POINT.lat());
        assertThat((BigDecimal) b.get("lot")).isEqualByComparingTo(JEJU_POINT.lot());
        assertThat(b.get("emd_cd")).isEqualTo(expectedEmdCd());

        // A 는 API 0회 — B 만 정확히 1회.
        verify(geocoder, times(1)).geocode(eq(queryB));
        verify(geocoder, times(1)).geocode(anyString());
        assertThat(fixtureSkipCount()).isZero();

        // 라운드 3 🟡② — 실 AdmDistResolver 가 B 를 1차 ST_Contains 로 잡았다.
        Map<String, Object> history = history(execution);
        assertThat((Long) history.get("emd_contains")).isGreaterThanOrEqualTo(1L);
        assertThat((Long) history.get("skip_geocode_failed")).isZero();
    }

    @Test
    @DisplayName("(2) B 가 NOT_FOUND → COMPLETED, B 행 NULL, CRDN 스킵 1행(trgt_id=sha256)")
    void recordsSkipWhenNotFound() throws Exception {
        givenSource(row(ADDR_A, "2026-09-01 10:00:00"), row(ADDR_A, "2026-09-02 11:00:00"),
                row(ADDR_B, "2026-09-03 12:00:00"));
        when(geocoder.geocode(eq(queryB))).thenReturn(GeocodeOutcome.notFound("parcel=NOT_FOUND, road=NOT_FOUND"));

        JobExecution execution = runJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM parking.tb_crdn_dsctn WHERE crdn_addr = ? AND lat IS NULL""",
                Long.class, ADDR_B)).isEqualTo(1L);

        Map<String, Object> skip = jdbc.queryForMap("""
                SELECT fail_rsn, trgt_addr FROM batch.tb_geocode_skip
                 WHERE src_se = 'CRDN' AND trgt_id = encode(sha256(convert_to(?, 'UTF8')), 'hex')""", ADDR_B);
        assertThat(skip.get("fail_rsn")).isEqualTo("GEOCODE_FAILED");
        assertThat(skip.get("trgt_addr")).isEqualTo(ADDR_B);
        assertThat(fixtureSkipCount()).isEqualTo(1L);

        assertThat((Long) history(execution).get("skip_geocode_failed")).isEqualTo(1L);
    }

    @Test
    @DisplayName("(3) 🔴 B 가 쿼터 초과 → FAILED, 픽스처 주소의 스킵 행 0")
    void failsFastOnQuotaExceeded() throws Exception {
        givenSource(row(ADDR_A, "2026-09-01 10:00:00"), row(ADDR_A, "2026-09-02 11:00:00"),
                row(ADDR_B, "2026-09-03 12:00:00"));
        when(geocoder.geocode(eq(queryB))).thenThrow(new GeocodeQuotaExceededException("OVER_REQUEST_LIMIT"));

        JobExecution execution = runJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(fixtureSkipCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM parking.tb_crdn_dsctn WHERE crdn_addr = ? AND lat IS NULL""",
                Long.class, ADDR_B)).isEqualTo(1L);
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(t -> assertThat(causedByQuota(t)).isTrue());
    }

    @Test
    @DisplayName("(4) 장소명 C → 지오코더 호출 없이 GEOCODE_FAILED 스킵")
    void skipsPlaceNameWithoutCallingGeocoder() throws Exception {
        givenSource(row(ADDR_C, "2026-09-04 13:00:00"));

        JobExecution execution = runJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM parking.tb_crdn_dsctn WHERE crdn_addr = ? AND lat IS NULL""",
                Long.class, ADDR_C)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT fail_rsn FROM batch.tb_geocode_skip
                 WHERE src_se = 'CRDN' AND trgt_id = encode(sha256(convert_to(?, 'UTF8')), 'hex')""", String.class, ADDR_C))
                .isEqualTo("GEOCODE_FAILED");
        verify(geocoder, never()).geocode(anyString());
    }

    @Test
    @DisplayName("(5) 수집 Step FAILED(원천 스키마 불일치) → 뒤 두 Step 미실행")
    void doesNotRunProcessingStepsWhenFetchFails() throws Exception {
        // 옛 필드명(crackdown_addr) — requireSourceColumns 가 open() 에서 막는다.
        ObjectNode legacy = row(ADDR_B, "2026-09-03 12:00:00");
        legacy.remove("crdn_addr");
        legacy.put("crackdown_addr", ADDR_B);
        givenSource(legacy);

        JobExecution execution = runJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stepNames(execution)).containsExactly("crackdownFetchStep");
        verify(geocoder, never()).geocode(anyString());
    }

    @Test
    @DisplayName("(6) 이력 행의 지오코딩 지표는 null 이 아니다 — none() 이 아니다")
    void recordsGeocodeMetricsInHistory() throws Exception {
        givenSource(row(ADDR_A, "2026-09-01 10:00:00"), row(ADDR_B, "2026-09-03 12:00:00"));
        when(geocoder.geocode(eq(queryB))).thenReturn(GeocodeOutcome.found(JEJU_POINT));

        JobExecution execution = runJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> history = history(execution);
        // CompositeGeocoder 가 mock 이라 카운터 getter 는 0 — 호출 횟수는 verify 가 진다.
        assertThat(history.get("geocode_api_lookups")).isEqualTo(0L);
        assertThat(history).extractingByKeys("geocode_cache_hits", "geocode_place_recoveries",
                        "geocode_stripped", "emd_contains", "emd_buffer",
                        "skip_geocode_failed", "skip_emd_unresolved", "filtered_transient")
                .doesNotContainNull();
        // 이 잡이 하지 않는 지표는 NULL 로 남는다.
        assertThat(history.get("unclsf_cnt")).isNull();
        assertThat(history.get("emd_name_fallback")).isNull();
    }

    // ---------------------------------------------------------------------------------------------

    private JobExecution runJob() throws Exception {
        return jobLauncher.run(crackdownFetchJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime())   // 매 실행 새 JobInstance
                .toJobParameters());
    }

    private void givenSource(ObjectNode... rows) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ObjectNode r : rows) {
            array.add(r);
        }
        // 🔁 2026-09-17 — keyset 페이지 1장(마지막 페이지)으로 준다. 커서는 쓰이지 않는다.
        when(apiClient.fetchPage(any(), isNull(), anyInt()))
                .thenReturn(new DhubKeysetPage(array, false, array.isEmpty() ? null : "1"));
    }

    /** {@link CrackdownOriginRow#SOURCE_COLUMNS} 7필드를 모두 가진 원천 1행. */
    private static ObjectNode row(String addr, String crdnDt) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("crdn_dt", crdnDt);
        node.put("ffnlg", 40000.0);
        node.put("crdn_mthd", "테스트");
        node.put("carmdl", "테스트");
        node.put("orgnl_cn", "테스트");
        node.put("crdn_plc", "테스트");
        node.put("crdn_addr", addr);
        assertThat(CrackdownOriginRow.SOURCE_COLUMNS).allMatch(node::has);
        return node;
    }

    private static List<String> stepNames(JobExecution execution) {
        return execution.getStepExecutions().stream().map(StepExecution::getStepName).toList();
    }

    private String expectedEmdCd() {
        return jdbc.queryForObject("""
                SELECT emd_cd FROM parking.tb_admdst
                 WHERE ST_Contains(bndry_geom, ST_SetSRID(ST_MakePoint(?, ?), 4326)) LIMIT 1""",
                String.class, JEJU_POINT.lot(), JEJU_POINT.lat());
    }

    private long fixtureSkipCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM batch.tb_geocode_skip
                 WHERE src_se = 'CRDN' AND trgt_addr LIKE ?""", Long.class, ADDR_PREFIX_LIKE);
    }

    /** 이번 실행의 이력 행 — 최신 행을 job_execution_id 로 정확히 집는다. */
    private Map<String, Object> history(JobExecution execution) {
        return jdbc.queryForMap("SELECT * FROM batch.tb_btch_excn_hstry WHERE job_execution_id = ?",
                execution.getId());
    }

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
}
