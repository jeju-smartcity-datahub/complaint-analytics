package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 생활권 보고서 <b>저장 → 스냅샷 조회 왕복</b> 통합 테스트.
 * (TSK-4088 Step 11 — {@code POST /api/analysis/lifezone/reports} ·
 *  {@code GET /api/reports/{rptpId}/lifezone})
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도
 * 스레드·별도 커넥션으로 처리해 테스트 트랜잭션이 서버 쪽 쓰기를 감싸지 못한다. 더구나 이 테스트가
 * 잡으려는 것이 <b>트랜잭션 밖에서 다시 읽은 스냅샷</b>이라, 롤백이 걸리는 조합을 택하면 검증 대상
 * 자체가 사라진다({@code HotspotReportIntegrationTest} 와 같은 근거).
 *
 * <h2>🔴 정리는 "내가 만든 id" 만 지운다</h2>
 * {@link #deleteOnlyWhatThisTestCreated()} 는 응답으로 받은 {@code rptpId}·{@code excnId} 만
 * 결과행 → 보고서 → 실행 순(FK 방향)으로 지운다. 범위 DELETE 는 쓰지 않는다 — 그 범위는
 * "내가 만든 것" 이 아니라 이 기능이 만드는 모든 보고서의 영역이라 수동 검증분까지 날아간다.
 *
 * <h2>🔴 응답을 VO 로 역직렬화하지 않는다</h2>
 * {@code LifezoneGradeScale} 은 {@code @JsonIgnore int[] minVls} 를 가진 record 라 기본 Jackson 으로
 * <b>역</b>직렬화가 되지 않는다(정규 생성자가 null 을 받아 터진다). 그래서 응답은 {@code String} 으로
 * 받아 {@link JsonNode} 로 본다 — 스냅샷과 원본을 JSON 레벨에서 통째로 대조하는 편이 이 테스트의
 * 목적(왕복 동일성)에도 정확하다.
 *
 * <h2>🔴 날짜·임계값을 상수로 박지 않는다</h2>
 * 조회 기간은 매퍼 {@code selectDataRange} 와 같은 SQL 로 DB 에서 읽고, 등급 경계는 응답의
 * {@code gradeCriteria} 에서 읽는다(D17 로 조회마다 달라진다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("생활권 보고서 저장·스냅샷 (POST /api/analysis/lifezone/reports)")
class LifezoneReportIntegrationTest {

    private static final String CREATE_URL = "/api/analysis/lifezone/reports";
    private static final String SNAPSHOT_URL = "/api/reports/{rptpId}/lifezone";
    private static final String ANALYSIS_URL = "/api/analysis/lifezone";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 조회 가능한 최대 구간(일, 양끝 포함). {@code jeju.datahub.common.DateRanges.MAX_RANGE_DAYS} 와
     * 같은 값이어야 한다 — 테스트가 독립적으로 다시 적는다.
     */
    private static final int MAX_RANGE_DAYS = 366;

    /** 기본 격자·유형 (서비스 계약). 데이터 양이 아니라 계약을 본다. 🔁 TSK-4227 D21 — 순위 기본 개수(15)는 없앴다 */
    private static final int DEFAULT_GRID_SZ = 500;
    private static final int FINEST_GRID_SZ = 100;
    private static final String TARGET_CVLCPT = "CVLCPT_CNT";

    /** 등급 수 — 저장된 {@code gradeCriteria} 행수의 계약. */
    private static final int GRADE_CNT = LifezoneGrade.values().length;

    /** 🔴 {@code rslt_json} 최상위 키. 조건({@code gridSz}·{@code anlsTrgtCd})은 여기 들어오지 않는다. */
    private static final Set<String> RSLT_KEYS = Set.of("summary", "ranking", "cells");

    /** 저장 크기 상한 — 실행 1건이 1MB 를 넘으면 JSONB 저장 전제가 무너진다(D13). */
    private static final int MAX_RSLT_BYTES = 1024 * 1024;

    /** 404-② 픽스처의 보고서명 — 어느 케이스가 남긴 행인지 DB 에서 바로 읽히게 한다. */
    private static final String FIXTURE_NM_OTHER_TYPE = "[테스트] 다른 유형 보고서 (CVLCPT)";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String dataBgngYmd;
    private String dataEndYmd;

    /**
     * 실제로 <b>요청 기간으로 쓰는</b> 시작일 = {@code max(dataBgngYmd, dataEndYmd - 365일)}.
     *
     * <p>🔴 이 클래스는 {@code fullPeriod()} 같은 헬퍼 없이 {@code request(…, dataBgngYmd, dataEndYmd)}
     * 로 보유 구간을 직접 넘기지만 위험은 같다 — {@code max} 는 수집 배치가 매일 밀어 올려 구간이
     * 하루씩 자라고, 366일({@code DateRanges.MAX_RANGE_DAYS})을 넘는 순간 이 요청들이 한꺼번에 400 을
     * 받는다. 시작일만 자른다 — 보유 구간이 1년 이내면 {@code dataBgngYmd} 와 같은 값이라 동작 변화가
     * 없다(TSK-4085 Step 4). <b>보유 구간 echo 단언은 {@code dataBgngYmd} 를 그대로 쓴다</b>(D11).
     */
    private String queryBgngYmd;

    private final Set<Long> createdRptpIds = new LinkedHashSet<>();
    private final Set<Long> createdExcnIds = new LinkedHashSet<>();

    /**
     * 🔴 TSK-4202 Step 5 — 저장은 {@code creatr_id} 에 실사용자를 적고(Step 3) 스냅샷은 소유자 가드를
     * 거친다(Step 4). {@code CurrentUserResolver} 가 principal 이 null 이면 401 이라 익명으로는 닿지
     * 않으므로 {@code mock-login} 세션을 쓴다. 익명이 401 인 것은 전용 케이스(8)가 지킨다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void loginAsMockUser() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
    }

    @AfterEach
    void logoutMockUser() {
        auth.cleanUp();
    }

    @BeforeEach
    void resolveDataFixtures() {
        String[] range = jdbcTemplate.queryForObject(dataRangeSql(),
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        dataBgngYmd = range[0];
        dataEndYmd = range[1];
        assertThat(dataBgngYmd)
                .as("원천(TB_CVLCPT)이 비어 있다 — 배치 적재분을 먼저 확인하라")
                .matches("\\d{8}");
        assertThat(dataEndYmd).matches("\\d{8}");
        queryBgngYmd = clampToMaxRange(dataBgngYmd, dataEndYmd);
    }

    /**
     * 366일 상한({@code DateRanges.MAX_RANGE_DAYS})에 맞춰 시작일을 자른다.
     * 365 를 빼면 양끝 포함 366일이다. 구간이 1년 이내면 {@code bgngYmd} 를 그대로 돌려준다.
     */
    private static String clampToMaxRange(String bgngYmd, String endYmd) {
        LocalDate bgng = LocalDate.parse(bgngYmd, YMD);
        LocalDate floor = LocalDate.parse(endYmd, YMD).minusDays(MAX_RANGE_DAYS - 1L);
        return bgng.isBefore(floor) ? floor.format(YMD) : bgngYmd;
    }

    /** 🔴 이 테스트가 만든 행만 지운다. 순서는 FK 방향 — 결과 → 보고서 → 실행. */
    @AfterEach
    void deleteOnlyWhatThisTestCreated() {
        List<RuntimeException> failures = new ArrayList<>();
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        }
        for (Long rptpId : createdRptpIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        }
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdRptpIds.clear();
        createdExcnIds.clear();
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    // ─────────────────────────────────────────── 1. 정상 저장 (3표 1/1/1 · 키 분담 · 보고서명)

    @Test
    @DisplayName("1. 정상 저장 — 세 표가 1행씩 늘고 rslt_json 은 결과 3키만 담는다 (D13)")
    void createReportStoresOneExecutionOneResultOneReport() {
        long[] before = tableCounts();

        JsonNode body = createOk(request(DEFAULT_GRID_SZ, null, queryBgngYmd, dataEndYmd));
        long rptpId = body.path("rptpId").asLong();
        long excnId = body.path("excnId").asLong();

        assertThat(rptpId).isPositive();
        assertThat(excnId).isPositive();
        assertThat(body.path("rptpNm").asText())
                .as("보고서명 = 생활권 {분석유형명} 보고서 {지역표시명} {격자} (D14)")
                .isEqualTo("생활권 민원건수 보고서 전체 지역 500m");
        assertThat(body.path("regionNm").asText()).isEqualTo("전체 지역");
        assertThat(body.path("fileFmt").asText()).isEqualTo("PDF");
        // 🔁 TSK-4227 — 순위는 심각·경고·주의만 (D5 — D12 ④) · 🔁 D21 — limit 생략 = 상한 없음:
        //    cells 중 listed 전부가 같은 순서로 실린다(크기·순서 일치)
        int listedCnt = body.path("severeCnt").asInt() + body.path("warnCnt").asInt()
                + body.path("cautionCnt").asInt();
        List<String> listedCds = new ArrayList<>();
        body.path("cells").forEach(c -> {
            if (Set.of("SEVERE", "WARN", "CAUTION").contains(c.path("grade").asText())) {
                listedCds.add(c.path("gridCd").asText());
            }
        });
        List<String> rankCds = new ArrayList<>();
        body.path("ranking").forEach(rk -> rankCds.add(rk.path("gridCd").asText()));
        assertThat(listedCds).as("등급별 카운트 = cells 의 listed 수").hasSize(listedCnt);
        assertThat(rankCds).as("limit 생략이면 심각·경고·주의 셀 전부가 순위에 실린다 (D21 — 15개 상한 없음)")
                .containsExactlyElementsOf(listedCds);
        body.path("ranking").forEach(rk -> assertThat(rk.path("grade").asText()).isIn("SEVERE", "WARN", "CAUTION"));

        // 🔴 세 표가 정확히 한 벌씩 늘었다 — 순위 행은 rslt_json 안에 있다(결과는 1행)
        long[] after = tableCounts();
        assertThat(after[0]).as("TB_ANLS_EXCN 은 1행 는다").isEqualTo(before[0] + 1);
        assertThat(after[1]).as("TB_ANLS_RSLT 는 순위 개수와 무관하게 1행만 는다 (D13)")
                .isEqualTo(before[1] + 1);
        assertThat(after[2]).as("TB_RPTP 는 1행 는다").isEqualTo(before[2] + 1);

        Map<String, Object> saved = jdbcTemplate.queryForMap(
                "SELECT crt_type, stts_cd, file_fmt, file_path, stng_id, excn_id, creatr_id, creatr_nm"
                        + "  FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        assertThat(saved.get("crt_type")).as("MANUAL 이어야 직접 생성 탭에 뜬다").isEqualTo("MANUAL");
        assertThat(saved.get("stts_cd")).isEqualTo("DONE");
        assertThat(saved.get("file_path")).as("파일을 서버에 굽지 않는다 (TSK-4078 D4)").isNull();
        assertThat(saved.get("stng_id")).isNull();
        assertThat(saved.get("excn_id")).isEqualTo(excnId);
        // 🔴 TSK-4202 Step 3 — 'system'/'관리자' 하드코딩이 아니라 세션 사용자다 (요청 ③④)
        assertThat(saved.get("creatr_id")).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(saved.get("creatr_nm")).isNotNull();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT anls_type_cd FROM parking.tb_anls_excn WHERE excn_id = ?", String.class, excnId))
                .as("이 값이 목록의 출처 칸을 '생활권시뮬레이션' 으로 만든다").isEqualTo("LIFEZONE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_rslt WHERE excn_id = ?", Long.class, excnId))
                .as("excn_id UNIQUE — 실행 1건 = 결과 1행").isEqualTo(1L);

        // 🔴 키 분담 — rslt_json 은 결과 3키뿐이고 조건은 들어오지 않는다
        Map<String, Object> shape = jdbcTemplate.queryForMap(
                "SELECT jsonb_typeof(rslt_json) AS t,"
                        + "       (SELECT string_agg(k, ',' ORDER BY k) FROM jsonb_object_keys(rslt_json) k) AS keys,"
                        + "       jsonb_array_length(rslt_json->'ranking') AS rank_len,"
                        + "       jsonb_array_length(rslt_json->'cells')   AS cell_len,"
                        + "       (rslt_json->'summary'->>'totGridCnt')::int AS tot,"
                        + "       pg_column_size(rslt_json) AS bytes"
                        + "  FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        assertThat(shape.get("t")).as("객체로 감싼다").isEqualTo("object");
        assertThat(Set.of(String.valueOf(shape.get("keys")).split(","))).isEqualTo(RSLT_KEYS);
        assertThat(shape.get("rank_len")).as("저장된 순위 = 응답 순위").isEqualTo(body.path("ranking").size());
        assertThat(shape.get("cell_len")).as("셀 수 == totGridCnt (등급별 5수치의 합)")
                .isEqualTo(shape.get("tot"));
        assertThat(((Number) shape.get("bytes")).intValue())
                .as("실행 1건이 1MB 미만이어야 JSONB 저장 전제가 성립한다 (D13)")
                .isLessThan(MAX_RSLT_BYTES);

        // 요약 6수치는 재계산 없이 응답과 같다
        JsonNode summary = readJson(jdbcTemplate.queryForObject(
                "SELECT rslt_json->'summary' FROM parking.tb_anls_rslt WHERE excn_id = ?",
                String.class, excnId));
        assertThat(summary.path("totGridCnt").asInt()).isEqualTo(body.path("totGridCnt").asInt());
        int sum = summary.path("severeCnt").asInt() + summary.path("warnCnt").asInt()
                + summary.path("cautionCnt").asInt() + summary.path("normalCnt").asInt()
                + summary.path("lowCnt").asInt();
        assertThat(sum).as("등급별 5수치의 합 = 총 격자 수").isEqualTo(summary.path("totGridCnt").asInt());

        // 🔴 재현 메타 — 경계를 굳히지 않으면 스냅샷에서 등급을 되살릴 수 없다 (D17)
        JsonNode param = readJson(jdbcTemplate.queryForObject(
                "SELECT anls_param_json FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, excnId));
        assertThat(param.fieldNames()).toIterable().contains(
                "gridSz", "gridSzNm", "anlsTrgtCd", "anlsTrgtNm", "clsfMthd", "clsfMthdNm",
                "limit", "unit", "sectionTitle", "sectionSub", "gradeScale", "gradeCriteria");
        assertThat(param.path("gridSz").asInt()).isEqualTo(DEFAULT_GRID_SZ);
        assertThat(param.path("anlsTrgtCd").asText()).isEqualTo(TARGET_CVLCPT);
        assertThat(param.path("gradeCriteria")).hasSize(GRADE_CNT);
        assertThat(param.path("gradeScale").path("method").asText()).isNotBlank();

        // D16 세부 내역이 저장됐다 — 순위 1행에 유형별 건수가 1~2건
        JsonNode dtl = body.path("ranking").get(0).path("dtl");
        assertThat(dtl.isArray()).isTrue();
        assertThat(dtl.size()).isBetween(1, 2);
        assertThat(dtl.get(0).path("cvlcptTypeCd").asText()).isNotBlank();
        assertThat(dtl.get(0).path("cvlcptTypeNm").asText()).isNotBlank();
        assertThat(dtl.get(0).path("cnt").asInt()).isPositive();
    }

    // ─────────────────────────────────────────── 2. 스냅샷 == 원본

    @Test
    @DisplayName("2. 스냅샷 조회가 저장 시점과 같다 — ranking 완전 일치 · cells 등급 재판정 일치")
    void snapshotReproducesSavedResult() {
        JsonNode saved = createOk(request(DEFAULT_GRID_SZ, null, queryBgngYmd, dataEndYmd));
        JsonNode snap = snapshotOk(saved.path("rptpId").asLong());

        // 조건 echo — 저장 시점과 같아야 문서가 같은 제목·같은 격자로 그려진다
        assertThat(snap.path("gridSz").asInt()).isEqualTo(saved.path("gridSz").asInt());
        assertThat(snap.path("gridSzNm").asText()).isEqualTo(saved.path("gridSzNm").asText());
        assertThat(snap.path("anlsTrgtCd").asText()).isEqualTo(saved.path("anlsTrgtCd").asText());
        assertThat(snap.path("clsfMthd").asText()).isEqualTo(saved.path("clsfMthd").asText());
        assertThat(snap.path("clsfMthd").asText())
                .as("🔁 TSK-4227 — clsfMthd 생략 저장은 유형 기본값(등간격)으로 굳는다").isEqualTo("EQUAL_INTERVAL");
        assertThat(snap.path("clsfMthdNm").asText()).isEqualTo(saved.path("clsfMthdNm").asText());
        assertThat(snap.path("unit").asText()).isEqualTo(saved.path("unit").asText());
        assertThat(snap.path("sectionTitle").asText()).isEqualTo(saved.path("sectionTitle").asText());
        assertThat(snap.path("bgngYmd").asText()).isEqualTo(saved.path("bgngYmd").asText());
        assertThat(snap.path("endYmd").asText()).isEqualTo(saved.path("endYmd").asText());
        assertThat(snap.path("rptpNm").asText()).isEqualTo(saved.path("rptpNm").asText());
        assertThat(snap.path("regionNm").asText())
                .as("POST(Java 조립)와 GET(SQL CASE)의 지역명이 같아야 한다")
                .isEqualTo(saved.path("regionNm").asText());

        // 🔴 gradeScale 은 int[] 필드를 가진 record 라 객체째 비교하지 않는다 — 세 값만 본다
        assertThat(snap.path("gradeScale").path("method").asText())
                .isEqualTo(saved.path("gradeScale").path("method").asText());
        assertThat(snap.path("gradeScale").path("cap").asInt())
                .isEqualTo(saved.path("gradeScale").path("cap").asInt());
        assertThat(snap.path("gradeScale").path("width").asInt())
                .isEqualTo(saved.path("gradeScale").path("width").asInt());
        assertThat(snap.path("gradeCriteria")).isEqualTo(saved.path("gradeCriteria"));

        // 요약 6수치
        for (String key : List.of("totGridCnt", "severeCnt", "warnCnt", "cautionCnt", "normalCnt", "lowCnt")) {
            assertThat(snap.path(key).asInt()).as(key).isEqualTo(saved.path(key).asInt());
        }

        // 🔴 순위는 저장 순서·저장 등급 그대로 — 재정렬·재판정하지 않는다
        assertThat(snap.path("ranking")).as("순위는 통째로 같아야 한다").isEqualTo(saved.path("ranking"));

        // 셀 — 등급은 저장하지 않고 gradeCriteria 로 재판정한다. 그래도 원본과 같아야 한다
        assertThat(snap.path("cells").size()).isEqualTo(saved.path("cells").size());
        assertThat(snap.path("cells")).isEqualTo(saved.path("cells"));
    }

    // ─────────────────────────────────────────── 3. 목록 밖 분류방법은 저장도 400 (TSK-4227)

    @Test
    @DisplayName("3. 🔁 TSK-4227 — clsfMthd=EQUAL_INTERVAL_P95(민원건수 목록 밖)로 저장하면 400 · 세 표 불변")
    void outOfListClassificationIsRejectedOnCreate() {
        // 보고서 저장 경로 대표 1건(D12 ①). P95 경계 산출은 LifezoneGradeScaleTest.EqualIntervalP95 로 이관했다.
        // ⚠️ 종전 이 케이스가 지키던 '비기본 분류방법 스냅샷 재현(rebuildScale — ofCode(method))' 은
        //    API 로 만들 수 없어 통합 커버리지가 빠진다(범위 밖으로 기록 — 계획서 Step 11).
        long[] before = tableCounts();
        JsonNode req = request(DEFAULT_GRID_SZ, null, queryBgngYmd, dataEndYmd);
        ((com.fasterxml.jackson.databind.node.ObjectNode) req).put("clsfMthd", "EQUAL_INTERVAL_P95");

        ResponseEntity<String> res = restTemplate.exchange(CREATE_URL, HttpMethod.POST,
                auth.jsonEntity(session, req), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tableCounts()).as("400 이면 세 표 어느 것도 늘지 않는다").isEqualTo(before);
    }

    // ─────────────────────────────────────────── 4. 격자가 다르면 보고서명도 다르다

    @Test
    @DisplayName("4. 격자가 다르면 보고서명도 다르다 — '… 전체 지역 100m'")
    void reportNameCarriesGridSize() {
        JsonNode fine = createOk(request(FINEST_GRID_SZ, null, queryBgngYmd, dataEndYmd));
        assertThat(fine.path("rptpNm").asText()).isEqualTo("생활권 민원건수 보고서 전체 지역 100m");

        // 목록 조회의 보고서명과도 같다 — 저장 문자열을 그대로 읽으므로 두 경로가 갈리지 않는다
        String listed = jdbcTemplate.queryForObject(
                "SELECT rptp_nm FROM parking.tb_rptp WHERE rptp_id = ?",
                String.class, fine.path("rptpId").asLong());
        assertThat(listed).isEqualTo(fine.path("rptpNm").asText());

        // 가장 잘게 쪼갠 격자(실측 2,015칸)에서도 저장 1건이 1MB 미만이다 (D13)
        Integer bytes = jdbcTemplate.queryForObject(
                "SELECT pg_column_size(rslt_json) FROM parking.tb_anls_rslt WHERE excn_id = ?",
                Integer.class, fine.path("excnId").asLong());
        assertThat(bytes).isLessThan(MAX_RSLT_BYTES);
    }

    // ─────────────────────────────────────────── 5. 0건은 400

    @Test
    @DisplayName("5. 결과 0건이면 400 — 열 수 없는 빈 보고서를 목록에 남기지 않는다")
    void emptyResultIsRejected() {
        long[] before = tableCounts();

        // 보유 구간 '밖' 을 계산해서 만든다 — 날짜를 상수로 박지 않는다
        LocalDate outEnd = LocalDate.parse(dataBgngYmd, YMD).minusDays(1);
        LocalDate outBgng = outEnd.minusDays(30);
        ResponseEntity<String> res = restTemplate.exchange(CREATE_URL, HttpMethod.POST,
                auth.jsonEntity(session, request(DEFAULT_GRID_SZ, null, outBgng.format(YMD), outEnd.format(YMD))),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tableCounts()).as("400 이면 세 표 어느 것도 늘지 않는다").isEqualTo(before);
    }

    // ─────────────────────────────────────────── 6. 조회(GET)는 여전히 저장하지 않는다

    @Test
    @DisplayName("6. 분석 조회는 아무것도 저장하지 않는다 — 5회 호출 후 실행 이력 불변 (D4)")
    void analysisGetStoresNothing() {
        long[] before = tableCounts();
        String url = ANALYSIS_URL + "?bgngYmd=" + queryBgngYmd + "&endYmd=" + dataEndYmd
                + "&gridSz=" + DEFAULT_GRID_SZ;
        for (int i = 0; i < 5; i++) {
            assertThat(restTemplate.getForEntity(url, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(tableCounts()).as("조회는 저장 경로가 아니다").isEqualTo(before);
    }

    // ─────────────────────────────────────────── 7. 404 3종

    @Test
    @DisplayName("7. 스냅샷 404 — 없는 보고서 · 다른 유형 보고서")
    void snapshotNotFoundCases() {
        Long maxRptpId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(rptp_id), 0) FROM parking.tb_rptp", Long.class);
        assertThat(snapshotStatus(maxRptpId + 1_000_000))
                .as("404-① 보고서가 없다").isEqualTo(HttpStatus.NOT_FOUND);

        long otherTypeRptpId = insertReportWithoutResult("CVLCPT", FIXTURE_NM_OTHER_TYPE);
        String otherType = jdbcTemplate.queryForObject(
                "SELECT COALESCE(e.anls_type_cd, 'NONE') FROM parking.tb_rptp p"
                        + "  LEFT JOIN parking.tb_anls_excn e ON e.excn_id = p.excn_id"
                        + " WHERE p.rptp_id = ?", String.class, otherTypeRptpId);
        assertThat(otherType).as("픽스처 보고서 %d 가 LIFEZONE 이면 이 케이스의 의미가 사라진다", otherTypeRptpId)
                .isNotEqualTo("LIFEZONE");
        assertThat(snapshotStatus(otherTypeRptpId))
                .as("404-② 생활권 실행이 아니다").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─────────────────────────────────────────── 8. 익명은 401 (TSK-4202 D5)

    @Test
    @DisplayName("8. 🔴 익명 HTTP 는 저장·스냅샷 모두 401 이고 세 표가 한 행도 늘지 않는다 (TSK-4202 D5)")
    void anonymousIs401AndInsertsNothing() {
        // 바디는 유효하다 — 같은 바디를 세션으로 보내면 201 이다(= 401 의 원인이 바디가 아니다)
        long rptpId = createOk(request(DEFAULT_GRID_SZ, null, queryBgngYmd, dataEndYmd))
                .path("rptpId").asLong();
        long[] before = tableCounts();

        assertThat(restTemplate.postForEntity(CREATE_URL,
                request(DEFAULT_GRID_SZ, null, queryBgngYmd, dataEndYmd), String.class).getStatusCode())
                .as("익명 저장").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity(SNAPSHOT_URL, String.class, rptpId).getStatusCode())
                .as("익명 스냅샷 — 내 보고서라도 세션이 없으면 401").isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(tableCounts()).as("401 이면 아무것도 저장하지 않는다").isEqualTo(before);
    }

    /** 세션을 실은 스냅샷 호출의 상태코드. */
    private HttpStatus snapshotStatus(long rptpId) {
        return HttpStatus.valueOf(restTemplate.exchange(SNAPSHOT_URL, HttpMethod.GET,
                new HttpEntity<>(session), String.class, rptpId).getStatusCode().value());
    }

    /**
     * 실행 이력 1행 + 그것을 참조하는 보고서 1행을 심고 {@code rptp_id} 를 돌려준다
     * (결과행 {@code TB_ANLS_RSLT} 은 넣지 않는다).
     *
     * <p>🔴 <b>시드를 빌리지 않는다</b> (TSK-4027). 종전에는 시드 보고서 {@code rptp_id=1} 을 상수로
     * 박아 "생활권이 아닌 보고서" 로 썼는데, 시드
     * ({@code database/migrations/sql/seeding/08-TB_RPTP.sql})가 보고서 목데이터를 전부 걷어
     * <b>그 번호가 더 이상 존재하지 않는다</b> — 이 케이스가 {@code EmptyResultDataAccessException} 으로
     * 깨졌다. 필요한 것은 "생활권 유형이 아닌 보고서 1건" 뿐이므로 여기서 직접 만든다.
     *
     * <p>🔴 PK 를 하드코딩하지 않는다 — identity 채번값을 {@code RETURNING} 으로 받아 즉시 추적 집합에
     * 넣으므로, 정리는 클래스 주석의 규칙 그대로 <b>이 테스트가 만든 id 만</b> 지운다
     * ({@link #deleteOnlyWhatThisTestCreated()}). 범위 DELETE 를 새로 들이지 않는다.
     *
     * <p>⚠️ {@code file_fmt} 는 <b>NULL</b> 이다 — NOT NULL 이 풀렸다(TSK-4027, 마이그레이션 72).
     */
    private long insertReportWithoutResult(String anlsTypeCd, String rptpNm) {
        Long excnId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_anls_excn"
                        + " (anls_type_cd, excutr_id, excn_stts_cd, excn_dt)"
                        + " VALUES (?, 'test', 'DONE', now()) RETURNING excn_id",
                Long.class, anlsTypeCd);
        assertThat(excnId).isNotNull();
        createdExcnIds.add(excnId);

        Long rptpId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_rptp"
                        + " (crt_type, excn_id, rptp_nm, anls_prd_expln, stts_cd, file_fmt,"
                        + "  creatr_id, creatr_nm, crt_ymd)"
                        + " VALUES ('MANUAL', ?, ?, '테스트 기간', 'DONE', NULL,"
                        + "         ?, '테스트', now()) RETURNING rptp_id",   // 🔴 TSK-4202 — 소유자 가드 통과
                Long.class, excnId, rptpNm, IntegrationLoginSupport.MOCK_ID);
        assertThat(rptpId).isNotNull();
        createdRptpIds.add(rptpId);
        return rptpId;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────────────────────

    /** 요청 본문. 조회 조건 + fileFmt 뿐이다 — 점수·건수를 보내지 않는다(D12). */
    private JsonNode request(int gridSz, String emdCd, String bgngYmd, String endYmd) {
        com.fasterxml.jackson.databind.node.ObjectNode node = JSON.createObjectNode();
        node.put("bgngYmd", bgngYmd);
        node.put("endYmd", endYmd);
        node.put("gridSz", gridSz);
        node.put("anlsTrgtCd", TARGET_CVLCPT);
        node.put("fileFmt", "PDF");
        if (emdCd != null) {
            node.put("emdCd", emdCd);
        }
        return node;
    }

    /** 저장 호출 — 201 을 확인하고 정리 대상 id 를 등록한다. */
    private JsonNode createOk(JsonNode request) {
        ResponseEntity<String> res = restTemplate.exchange(
                CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, request), String.class);
        assertThat(res.getStatusCode()).as("저장 응답: %s", res.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(res.getBody());
        createdRptpIds.add(body.path("rptpId").asLong());
        createdExcnIds.add(body.path("excnId").asLong());
        return body;
    }

    private JsonNode snapshotOk(long rptpId) {
        ResponseEntity<String> res = restTemplate.exchange(
                SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session), String.class, rptpId);
        assertThat(res.getStatusCode()).as("스냅샷 응답: %s", res.getBody()).isEqualTo(HttpStatus.OK);
        return readJson(res.getBody());
    }

    private static JsonNode readJson(String json) {
        assertThat(json).isNotBlank();
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 JSON 파싱 실패: " + json, e);
        }
    }

    /** {@code [실행, 결과, 보고서]} 행수. 생활권 실행만 세지 않고 표 전체를 본다(다른 유형이 섞여도 증분은 같다). */
    private long[] tableCounts() {
        return new long[] {
                count("SELECT count(*) FROM parking.tb_anls_excn"),
                count("SELECT count(*) FROM parking.tb_anls_rslt"),
                count("SELECT count(*) FROM parking.tb_rptp"),
        };
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Object id) {
        try {
            jdbcTemplate.update(sql, id);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    /** 매퍼 {@code selectDataRange} 와 같은 SQL — 날짜를 상수로 박지 않기 위한 유일한 출처. */
    private static String dataRangeSql() {
        return "SELECT to_char(min(ocrn_dt), 'YYYYMMDD'), to_char(max(ocrn_dt), 'YYYYMMDD')"
                + "  FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'";
    }

}
