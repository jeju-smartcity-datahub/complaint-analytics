package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.GradeCriterionVO;
import jeju.datahub.analysis.service.HotspotAnalysisResultVO;
import jeju.datahub.analysis.service.HotspotReportVO;
import jeju.datahub.analysis.service.HotspotGrade;
import jeju.datahub.analysis.service.HotspotZoneVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 집중구역 분석 보고서 <b>저장 → 조회 왕복</b> 통합 테스트.
 * ({@code POST /api/analysis/hotspot/reports} · {@code GET /api/reports/{rptpId}/hotspot})
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도 스레드·별도
 * 커넥션으로 처리해 테스트 트랜잭션이 서버 쪽 쓰기를 감싸지 못한다(롤백이 걸리지 않는다). 같은 패키지의
 * {@code HotspotAnalysisIntegrationTest}·{@code AnalysisWeightIntegrationTest} 와
 * {@code report.ReportIntegrationTest} 가 전부 같은 근거로 "실제 커밋 + 수동 복원" 을 쓴다.
 * 더구나 이 테스트가 잡으려는 것이 <b>트랜잭션 밖에서 다시 읽은 스냅샷</b>(케이스 5)이라,
 * 롤백이 걸리는 조합을 택하면 검증 대상 자체가 사라진다.
 *
 * <h2>🔴 정리는 "내가 만든 id" 만 지운다 — 범위로 지우지 않는다</h2>
 * {@link #deleteOnlyWhatThisTestCreated()} 는 각 케이스가 응답으로 받은 {@code rptpId}·{@code excnId} 를
 * 모아 뒀다가 <b>그 id 만</b> 결과행 → 보고서 → 실행 순(FK 방향)으로 지운다.
 * <ul>
 *   <li>🔴 {@code DELETE ... WHERE rptp_id >= 100} 처럼 <b>범위로 지우면 안 된다.</b> 그 범위는
 *       "내가 만든 것" 이 아니라 <b>이 기능이 만드는 모든 보고서의 영역</b>이다(identity
 *       {@code setval} 이후). 범위로 지우면 사용자·수동 검증이 남긴 보고서까지 함께 날아간다.</li>
 *   <li>🔴 {@code parking.TB_RPTP} 를 {@code ReportIntegrationTest} 와 <b>공유</b>한다. 그쪽도
 *       자기 픽스처만 만들고 자기가 심은 것만 지우고, 이쪽은 자기가 만든 id 만
 *       지운다 — 겹치는 행이 없으므로 <b>실행 순서를 바꿔도 결과가 같다.</b> 둘 중 하나라도 범위를
 *       넓히면 먼저 도는 쪽이 나중 쪽의 단언을 무너뜨린다.</li>
 *   <li>identity 를 {@code RESTART} 하지 않는다 — 남아 있는 행의 PK 를 재사용해 다음 등록이 충돌한다
 *       (같은 이유로 {@code ReportIntegrationTest} 도 {@code rptp_id} 만은 되돌리지 않는다).</li>
 *   <li>{@code TB_ANLS_EXCN} 은 <b>이 테스트가 만든 행만</b> 지운다 — 분석 결과 표
 *       ({@code TB_ANLS_RSLT}·{@code TB_PLCY_SMLT_RSLT})가 FK 로 참조하는 공유 허브라
 *       시드·다른 실행 이력을 건드리면 안 된다.</li>
 * </ul>
 *
 * <h2>🔴 날짜·기대값을 상수로 박지 않는다 (데이터 독립성)</h2>
 * 민원·단속 적재분은 배치가 소유한다 — 재적재 한 번이면 구간이 통째로 옮겨간다(실제로 그 이유로
 * {@code ComplaintTrendIntegrationTest} 가 깨져 있다). 그래서
 * <ul>
 *   <li><b>조회 기간</b>은 {@link #resolveDataFixtures()} 가 매퍼 {@code selectDataRange} 와 <b>같은
 *       조건</b>으로 DB 에서 읽는다({@link #dataBgngYmd} ~ {@link #dataEndYmd}).</li>
 *   <li><b>0건 구간</b>도 리터럴이 아니라 <b>보유 구간 밖</b>을 계산해서 만든다(케이스 3) —
 *       그리고 정말 0건인지 SQL 로 먼저 확인한다.</li>
 *   <li><b>점수는 절대값을 단언하지 않는다</b> — 불변식으로 본다. 점수는 0~100 의 정규화 점수
 *       ({@link #assertScoreSatisfiesNormalizedFormula}), 등급 = 점수와 임계값 80/60/40 의 관계
 *       ({@link #assertGradeMatchesScore}), {@code rnk} 는 1..N 연속.
 *       계수는 응답의 {@code weights} 에서 뽑는다(설정이 60/40 이 아니게 돼도 성립한다).</li>
 * </ul>
 * 🔁 <b>{@code zones} 개수도 더 이상 리터럴로 단언하지 않는다.</b> {@code limit} 은 미지정이면
 * <b>전량</b>이고({@code @Max} 제거) 응답에는 <b>주의(40점) 이상만</b> 실리므로, 몇 칸이 남는지는 적재분·
 * 월기준값·가중치에 달렸다. 그래서 "비어 있지 않다" 와 "저장 JSON 길이 == 응답 칸 수" 만 본다.
 *
 * <p>🔴 <b>VWorld 실호출에 의존하지 않는다</b> — 키를 빈 값으로 고정해 구역명 폴백의 외부 호출 경로를
 * 원천 차단한다({@code HotspotAnalysisIntegrationTest} 와 <b>같은 프로퍼티 문자열</b>이라 스프링 컨텍스트도
 * 공유돼 기동이 한 번으로 줄어든다).
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다 — 모든 프로파일이 PostgreSQL 이고 H2 는 없다.
 * 🔴 <b>보고서 시드는 더 이상 전제하지 않는다</b> — 시드({@code seeding/08-TB_RPTP.sql})가 보고서
 * 목데이터를 걷었으므로, 404 케이스가 쓰던 {@code rptp_id=1}·{@code 14} 는
 * {@link #insertReportWithoutResult} 가 직접 만든다. 전제로 남은 것은 마이그레이션 53 의 가중치 시드뿐이다.
 *
 * <p>응답 바디의 {@code message} 문자열은 단언하지 않는다 — devtools 유무에 따라 있고 없고가 갈려
 * 환경에 따라 깨지는 단언이 된다. <b>상태코드만</b> 본다. 같은 이유로 실패가 예상되는 호출은
 * 응답 타입을 {@code String.class} 로 받는다(에러 바디를 VO 로 역직렬화하려다 터지는 것을 막는다).
 *
 * <p>🔴 {@link BigDecimal} 단언은 전부 {@code isEqualByComparingTo} 다 — {@code snths_scr} 는
 * {@code NUMERIC(6,2)} 라 {@code 164.00} 으로 오고 계산값은 {@code 164.0} 이라 {@code isEqualTo} 는
 * 스케일 차이로 실패한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 키를 비워 VWorld HTTP 호출 경로를 원천 차단한다 (위 클래스 주석 참고).
        properties = "analysis.zone-name.vworld.api-key=")
class HotspotReportIntegrationTest {

    private static final String CREATE_URL = "/api/analysis/hotspot/reports";
    private static final String SNAPSHOT_URL = "/api/reports/{rptpId}/hotspot";

    /** 보유 구간·기간 파라미터 표기 형식 */
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 조회 가능한 최대 구간(일, 양끝 포함). {@code jeju.datahub.common.DateRanges.MAX_RANGE_DAYS} 와
     * 같은 값이어야 한다 — 테스트가 독립적으로 다시 적는다(등급 임계값과 같은 방식).
     */
    private static final int MAX_RANGE_DAYS = 366;

    /** 연동 읍면동코드 — 케이스 6 의 "시군구 + 읍면동" 형태 입력값. {@code TB_ADMDST} 의 고정 코드다. */
    private static final String EMD_YEONDONG = "50110137";

    /** 케이스 6 의 "시군구 전체" 형태 입력값 */
    private static final String SGG_JEJU = "제주시";

    /** 404-② · 404-③ 픽스처의 보고서명 — 어느 케이스가 남긴 행인지 DB 에서 바로 읽히게 한다 */
    private static final String FIXTURE_NM_CVLCPT = "[테스트] 다른 유형 보고서 (CVLCPT)";
    private static final String FIXTURE_NM_HOTSPOT_EMPTY = "[테스트] 결과 0행 보고서 (HOTSPOT)";

    /** 점수 상한. 조회 결과 1위가 정확히 이 값이다. */
    private static final BigDecimal SCORE_MAX = new BigDecimal("100");

    private static final String VRBL_COMPLAINT = "COMPLAINT";
    private static final String VRBL_ENFORCE = "ENFORCE";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 원천 보유 구간 시작일 — 매퍼 {@code selectDataRange} 와 같은 조건으로 DB 에서 읽는다. */
    private String dataBgngYmd;

    /** 원천 보유 구간 종료일. */
    private String dataEndYmd;

    /**
     * 실제로 <b>요청 기간으로 쓰는</b> 시작일 = {@code max(dataBgngYmd, dataEndYmd - 365일)}.
     *
     * <p>🔴 보유 구간을 그대로 요청하면 상한이 없다 — {@code max} 는 수집 배치가 매일 밀어 올려
     * 구간이 하루씩 자라고, 366일({@code DateRanges.MAX_RANGE_DAYS})을 넘는 순간
     * {@link #fullPeriod()} 를 쓰는 전 케이스가 한꺼번에 400 을 받는다. 시작일만 자른다 —
     * 보유 구간이 1년 이내면 {@code dataBgngYmd} 와 같은 값이라 동작 변화가 없다.
     */
    private String queryBgngYmd;

    /**
     * 🔴 이 테스트가 만든 보고서·실행 아이디. {@link #deleteOnlyWhatThisTestCreated()} 가 <b>이것만</b> 지운다.
     *
     * <p>JUnit 은 테스트 메서드마다 인스턴스를 새로 만들므로 케이스 간에 섞이지 않는다.
     */
    private final Set<Long> createdRptpIds = new LinkedHashSet<>();
    private final Set<Long> createdExcnIds = new LinkedHashSet<>();

    /** 예상 밖 성공 응답에서 id 만 건져 내는 용도. 단언에는 쓰지 않는다. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 조회 기간을 <b>DB 에서</b> 읽어 둔다 (상수 박기 금지 — 클래스 주석 "데이터 독립성" 참고).
     *
     * <p>적재분이 비어 있으면 모든 케이스가 400 으로 죽어 원인이 가려지므로 여기서 먼저 명확한 메시지로
     * 실패시킨다.
     */
    /**
     * 🔴 보고서 생성·스냅샷 조회가 <b>로그인 세션을 요구</b>하게 됐다.
     * 생성은 {@code creatr_id} 에 실사용자를 적어야 하고, 스냅샷은 소유자 가드를 거친다.
     * test 체인은 permitAll 이지만 {@code CurrentUserResolver} 가 principal 이 null 이면 <b>401</b> 이라,
     * 익명 호출로는 생성도 조회도 닿지 않는다. 그래서 {@code mock-login} 세션을 얻어 쓴다
     * ({@code ReportIntegrationTest} 와 같은 헬퍼). 익명이 401 인 것은 전용 케이스가 따로 지킨다.
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
                .as("원천(TB_CVLCPT·TB_CRDN_DSCTN)이 비어 있다 — 배치 적재분을 먼저 확인하라")
                .matches("\\d{8}");
        assertThat(dataEndYmd).matches("\\d{8}");
        queryBgngYmd = clampToMaxRange(dataBgngYmd, dataEndYmd);
    }

    /**
     * 🔴 <b>이 테스트가 만든 행만</b> 지운다 (클래스 주석 "정리는 내가 만든 id 만" 참고).
     *
     * <p>순서는 FK 방향 그대로 <b>결과행 → 보고서 → 실행</b> 이다. 뒤집으면
     * {@code fk_tb_anls_rslt_excn_id} / {@code fk_tb_rptp_excn_id} 위반으로 헬퍼 자체가 터진다.
     */
    @AfterEach
    void deleteOnlyWhatThisTestCreated() {
        // 한 문장이 터져도 나머지를 마저 지운다 — 중간에 멈추면 남은 행이 다음 실행의 단언을 흔들고,
        // 범위 DELETE 로 쓸어 담을 수도 없다(그러면 수동 검증이 남긴 보고서까지 날아간다).
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
        // 조용히 넘기지 않는다 — 정리 실패는 다음 실행이 알 수 없는 이유로 깨지는 원인이 된다.
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Object id) {
        try {
            jdbcTemplate.update(sql, id);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    // ────────────────────────────────────── 1. 정상 생성 (3표 각각 1 / 1 / 1 · zones 는 1칸 이상)

    @Test
    @DisplayName("1. 정상 생성 — 201 · 실행 1행 · 결과 1행(zones 1칸 이상) · 보고서 1행이 한 번에 늘고 저장값이 계약을 지킨다")
    void createReportInsertsOneExecutionOneResultRowAndOneReport() {
        long[] before = tableCounts();

        HotspotReportVO body = createOk(request(fullPeriod(), "PDF"));

        assertThat(body.getRptpId()).isNotNull();
        assertThat(body.getExcnId()).isNotNull();
        assertThat(body.getFileFmt()).isEqualTo("PDF");
        assertThat(body.getRptpNm()).isNotBlank();
        assertThat(body.getRegionNm()).isEqualTo("전체 지역");
        assertThat(body.getAnlsPrdExpln()).isNotBlank();
        assertThat(body.getCrtYmd()).isNotNull();

        List<HotspotZoneVO> zones = body.getZones();
        // 개수를 리터럴로 단언하지 않는다(limit 미지정 = 전량 · 주의 40점 이상만).
        assertThat(zones)
                .as("보유 구간 전체에서 주의(40점) 이상이 한 칸도 없다 — 적재분·월기준값을 확인하라")
                .isNotEmpty();
        assertZoneContract(zones, body.getWeights());

        // 🔴 세 표가 정확히 한 벌씩 늘었다 (실행 1 · 결과 1 · 보고서 1) — 순위 15행이 결과 1행이 됐다
        long[] after = tableCounts();
        assertThat(after[0]).as("TB_ANLS_EXCN 은 1행 는다").isEqualTo(before[0] + 1);
        assertThat(after[1]).as("TB_ANLS_RSLT 는 순위 개수와 무관하게 1행만 는다")
                .isEqualTo(before[1] + 1);
        assertThat(after[2]).as("TB_RPTP 는 1행 는다").isEqualTo(before[2] + 1);

        // 저장된 모양 — 목록 화면의 '직접 생성' 탭·파일 미생성 계약
        Map<String, Object> saved = jdbcTemplate.queryForMap(
                "SELECT crt_type, stts_cd, file_fmt, file_path, stng_id, excn_id, creatr_id"
                        + "  FROM parking.tb_rptp WHERE rptp_id = ?", body.getRptpId());
        assertThat(saved.get("crt_type")).as("MANUAL 이어야 직접 생성 탭에 뜬다").isEqualTo("MANUAL");
        assertThat(saved.get("stts_cd")).isEqualTo("DONE");
        assertThat(saved.get("file_fmt")).isEqualTo("PDF");
        assertThat(saved.get("file_path")).as("파일을 서버에 굽지 않는다").isNull();
        assertThat(saved.get("stng_id")).as("자동 생성 설정과 무관하다").isNull();
        assertThat(saved.get("excn_id")).isEqualTo(body.getExcnId());
        // 🔴 생성자는 'system'/'관리자' 하드코딩이 아니라 세션 사용자다
        assertThat(saved.get("creatr_id")).as("생성자는 로그인 사용자다")
                .isEqualTo(IntegrationLoginSupport.MOCK_ID);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT anls_type_cd FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, body.getExcnId()))
                .as("이 값이 목록의 출처 칸을 '집중구역분석' 으로 만든다").isEqualTo("HOTSPOT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_rslt WHERE excn_id = ?",
                Long.class, body.getExcnId()))
                .as("excn_id 에 UNIQUE 가 걸려 있어 실행 1건 = 결과 1행이다")
                .isEqualTo(1L);

        // 🔴 저장 모양 — object 로 감싼 {"zones":[…]} 이고 문자열 이중 인코딩이 아니다
        Map<String, Object> jsonShape = jdbcTemplate.queryForMap(
                "SELECT jsonb_typeof(rslt_json) AS t, jsonb_exists(rslt_json, 'zones') AS has_zones,"
                        + "       jsonb_array_length(rslt_json->'zones') AS len"
                        + "  FROM parking.tb_anls_rslt WHERE excn_id = ?", body.getExcnId());
        assertThat(jsonShape.get("t")).as("배열이 아니라 객체로 감싼다").isEqualTo("object");
        assertThat(jsonShape.get("has_zones")).as("zones 키가 있어야 조회가 성립한다").isEqualTo(Boolean.TRUE);
        // 길이를 리터럴로 박는 대신 '응답으로 돌려준 칸 수와 같다' 를 본다.
        //    저장분과 응답이 갈리는 것이야말로 이 단언이 잡아야 할 사고다(0칸 저장도 함께 걸린다).
        assertThat(((Number) jsonShape.get("len")).intValue())
                .as("저장된 zones 배열 길이가 응답 칸 수(%d)와 같아야 한다 (행이 아니라 배열 길이로 센다)",
                        zones.size())
                .isPositive()
                .isEqualTo(zones.size());

        // 🔴 저장 키는 7칸뿐이다 — emdNm·zoneNmSrc·grade 를 담으면 이관된 기존 보고서와 계약이 갈린다
        List<String> savedKeys = jdbcTemplate.queryForList(
                "SELECT jsonb_object_keys(rslt_json->'zones'->0) FROM parking.tb_anls_rslt WHERE excn_id = ?",
                String.class, body.getExcnId());
        assertThat(savedKeys).as("7칸 + 저장 등급 grade 만 담는다")
                .containsExactlyInAnyOrder("rnk", "zoneNm", "cvlcptCnt", "crdnCnt",
                        "snthsScr", "grade", "mrkLat", "mrkLot");
    }

    // ────────────────────────────────────────────────── 2. 같은 조건 2회 (중복 방지 없음 = 정상)

    @Test
    @DisplayName("2. 같은 조건으로 PDF·DOCX 두 번 — 보고서 2건 · 실행 2건 (중복 방지를 하지 않는다)")
    void sameConditionTwiceCreatesTwoIndependentReports() {
        long[] before = tableCounts();

        HotspotReportVO pdf = createOk(request(fullPeriod(), "PDF"));
        HotspotReportVO docx = createOk(request(fullPeriod(), "DOCX"));

        assertThat(pdf.getRptpId()).as("두 번째 호출이 첫 보고서를 덮어쓰지 않는다").isNotEqualTo(docx.getRptpId());
        assertThat(pdf.getExcnId()).as("실행 이력도 각각 남는다").isNotEqualTo(docx.getExcnId());
        assertThat(pdf.getFileFmt()).isEqualTo("PDF");
        assertThat(docx.getFileFmt()).isEqualTo("DOCX");

        // 같은 조건이므로 보고서명·지역명·기간표기는 같다 — 다른 것은 파일형식과 아이디뿐이다
        assertThat(docx.getRptpNm()).isEqualTo(pdf.getRptpNm());
        assertThat(docx.getRegionNm()).isEqualTo(pdf.getRegionNm());
        assertThat(docx.getAnlsPrdExpln()).isEqualTo(pdf.getAnlsPrdExpln());

        long[] after = tableCounts();
        assertThat(after[0]).as("실행 2행").isEqualTo(before[0] + 2);
        assertThat(after[2]).as("보고서 2행").isEqualTo(before[2] + 2);
        assertThat(after[1]).as("결과도 실행마다 한 행씩 — 두 벌")
                .isEqualTo(before[1] + 2);
    }

    // ────────────────────────────────────────────────── 3. 결과 0건 = 400 + 전 표 롤백

    @Test
    @DisplayName("3. 결과 0건 기간 — 400 이고 세 표 어디에도 행이 늘지 않는다 (트랜잭션 롤백)")
    void emptyResultReturns400AndWritesNothing() {
        // 🔴 0건 구간도 상수로 박지 않는다 — 보유 구간 종료일 + 1년부터 한 달을 계산해 쓴다
        String bgng = LocalDate.parse(dataEndYmd, YMD).plusYears(1).format(YMD);
        String end = LocalDate.parse(bgng, YMD).plusMonths(1).format(YMD);
        assertThat(sourceRowCount(bgng, end))
                .as("계산한 구간 %s~%s 이 실제로 0건이어야 이 케이스가 성립한다", bgng, end)
                .isZero();

        long[] before = tableCounts();

        assertThat(postStatus(request(period(bgng, end), "PDF")))
                .as("조회는 0건도 200 이지만 보고서는 400 이다 — 열 수 없는 빈 보고서를 남기지 않는다")
                .isEqualTo(400);

        assertThat(tableCounts())
                .as("실행 이력만 남는 반쪽 상태를 만들지 않는다 (세 표가 함께 롤백된다)")
                .containsExactly(before[0], before[1], before[2]);
    }

    // ────────────────────────────────────────────────── 4. fileFmt 화이트리스트

    @Test
    @DisplayName("4. fileFmt 가 PDF|DOCX|XLSX 밖 — 400 (대소문자·빈값·누락 모두) · 아무것도 저장하지 않는다")
    void fileFmtOutsideWhitelistReturns400() {
        long[] before = tableCounts();

        assertThat(postStatus(request(fullPeriod(), "HWP"))).as("미지원 형식 → @Pattern").isEqualTo(400);
        assertThat(postStatus(request(fullPeriod(), "XLS"))).as("미지원 형식 → @Pattern (XLSX 는 허용)").isEqualTo(400);
        assertThat(postStatus(request(fullPeriod(), "pdf"))).as("소문자 → @Pattern (^(PDF|DOCX|XLSX)$)").isEqualTo(400);
        assertThat(postStatus(request(fullPeriod(), ""))).as("빈 값 → @NotBlank").isEqualTo(400);
        assertThat(postStatus(request(fullPeriod(), null)))
                .as("키 자체를 생략 → @NotBlank (조용히 PDF 로 떨어뜨리지 않는다)").isEqualTo(400);

        assertThat(tableCounts()).as("검증 실패는 아무것도 저장하지 않는다")
                .containsExactly(before[0], before[1], before[2]);
    }

    // ────────────────────────────────────────────────── 5. 스냅샷 왕복

    @Test
    @DisplayName("5. 스냅샷 왕복 — GET 이 저장·복원되는 칸(rnk·zoneNm·건수·점수·등급)을 POST 와 똑같이 준다")
    void snapshotRoundTripKeepsPersistedColumns() {
        HotspotReportVO created = createOk(request(fullPeriod(), "DOCX"));

        ResponseEntity<HotspotReportVO> response =
                restTemplate.exchange(SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session),
                        HotspotReportVO.class, created.getRptpId());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        HotspotReportVO snapshot = response.getBody();
        assertThat(snapshot).isNotNull();

        // 헤더 — 두 경로(POST 는 HotspotReportNaming, GET 은 selectReportById 의 CASE)가 같은 문자열을 내야 한다
        assertThat(snapshot.getRptpId()).isEqualTo(created.getRptpId());
        assertThat(snapshot.getExcnId()).isEqualTo(created.getExcnId());
        assertThat(snapshot.getRptpNm()).isEqualTo(created.getRptpNm());
        assertThat(snapshot.getRegionNm()).isEqualTo(created.getRegionNm());
        assertThat(snapshot.getAnlsPrdExpln()).isEqualTo(created.getAnlsPrdExpln());
        assertThat(snapshot.getFileFmt()).isEqualTo("DOCX");
        // crt_ymd 는 timestamp(마이크로초)라 JVM 이 준 나노초와 자릿수가 다르다 — 초 단위로 맞춘다
        assertThat(snapshot.getCrtYmd()).isNotNull();
        assertThat(snapshot.getCrtYmd().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(created.getCrtYmd().truncatedTo(ChronoUnit.SECONDS));

        List<HotspotZoneVO> before = created.getZones();
        List<HotspotZoneVO> after = snapshot.getZones();
        assertThat(after).hasSameSizeAs(before);

        // 🔴 객체 전체 비교(isEqualTo)는 실패한다 — 좌표는 NUMERIC(12,10) 반올림이고
        //    emdNm·zoneNmSrc 는 결과 테이블에 컬럼이 없다. 저장·복원되는 칸만 본다.
        for (int i = 0; i < before.size(); i++) {
            HotspotZoneVO src = before.get(i);
            HotspotZoneVO dst = after.get(i);
            assertThat(dst.getRnk()).as("rnk (%d번째)", i).isEqualTo(src.getRnk());
            assertThat(dst.getZoneNm()).as("zoneNm (rnk %d)", src.getRnk()).isEqualTo(src.getZoneNm());
            assertThat(dst.getCvlcptCnt()).as("cvlcptCnt (rnk %d)", src.getRnk()).isEqualTo(src.getCvlcptCnt());
            assertThat(dst.getCrdnCnt()).as("crdnCnt (rnk %d)", src.getRnk()).isEqualTo(src.getCrdnCnt());
            assertThat(dst.getSnthsScr()).as("snthsScr (rnk %d)", src.getRnk())
                    .isEqualByComparingTo(src.getSnthsScr());
            assertThat(dst.getGrade()).as("grade (rnk %d) — 저장하지 않고 점수로 다시 판정한다", src.getRnk())
                    .isEqualTo(src.getGrade());
            assertThat(dst.getGradeNm()).isEqualTo(src.getGradeNm());

            // 좌표는 값이 실려 오되 자릿수는 다를 수 있다 (지도 마커가 사라지지 않는지만 본다)
            assertThat(dst.getMrkLat()).as("좌표는 반올림되어도 실려 온다").isNotNull();
            assertThat(dst.getMrkLot()).isNotNull();

            // 의도된 비대칭 — 두 칸이 값을 갖는다고 가정하는 코드를 프론트가 만들지 않게 못박는다
            assertThat(dst.getEmdNm()).as("결과 테이블에 emd_nm 컬럼이 없다").isNull();
            assertThat(dst.getZoneNmSrc()).as("결과 테이블에 zone_nm_src 컬럼이 없다").isNull();
        }

        // 복원된 목록도 순위·점수·등급 계약을 그대로 지킨다
        assertZoneContract(after, snapshot.getWeights());
    }

    // ────────────────────────────────────────────────── 5-1. 가중치 왕복 (조용한 실패 감시)

    @Test
    @DisplayName("5-1. 가중치 왕복 — GET 스냅샷의 weights 가 POST 응답과 13필드 전부 같다 (빈 목록 삼킴 감시)")
    void snapshotRestoresAllNineWeightFields() {
        HotspotReportVO created = createOk(request(fullPeriod(), "PDF"));
        HotspotReportVO snapshot = snapshotOk(created.getRptpId());

        List<AnalysisWeightVO> before = created.getWeights();
        List<AnalysisWeightVO> after = snapshot.getWeights();

        // 🔴 이 단언이 핵심이다 — 스냅샷 조회는 anls_param_json 을 Jackson 으로 되살리는데,
        //    역직렬화 실패를 '빈 목록 + WARN' 으로 삼킨다(500 을 내지 않는다). 그래서 AnalysisWeightVO 의
        //    필드 구성이 바뀌면 GET 만 조용히 빈 가중치를 주고 문서 부제(민원 60% / 단속 40%)가 사라진다.
        assertThat(after)
                .as("가중치가 비어 있다 — anls_param_json 역직렬화가 조용히 실패했을 수 있다 (WARN 로그 확인)")
                .isNotEmpty();
        assertThat(before).isNotEmpty();
        assertThat(after).hasSameSizeAs(before);

        for (int i = 0; i < before.size(); i++) {
            AnalysisWeightVO src = before.get(i);
            AnalysisWeightVO dst = after.get(i);
            String where = "weights[" + i + "] (" + src.getVrblCd() + ")";
            assertThat(dst.getAnlsTypeCd()).as("%s anlsTypeCd", where).isEqualTo(src.getAnlsTypeCd());
            assertThat(dst.getVrblCd()).as("%s vrblCd", where).isEqualTo(src.getVrblCd());
            assertThat(dst.getVrblNm()).as("%s vrblNm", where).isEqualTo(src.getVrblNm());
            assertThat(dst.getVrblExpln()).as("%s vrblExpln", where).isEqualTo(src.getVrblExpln());
            assertBigDecimalEquals(dst.getStngVl(), src.getStngVl(), where + " stngVl");
            assertBigDecimalEquals(dst.getBscStngVl(), src.getBscStngVl(), where + " bscStngVl");
            // 신규 4필드도 같은 경로로 왕복한다 — 하나라도 빠지면 화면이 범위·단위를 잃는다
            assertThat(dst.getVrblSeCd()).as("%s vrblSeCd", where).isEqualTo(src.getVrblSeCd());
            assertBigDecimalEquals(dst.getMinVl(), src.getMinVl(), where + " minVl");
            assertBigDecimalEquals(dst.getMaxVl(), src.getMaxVl(), where + " maxVl");
            assertThat(dst.getUnitNm()).as("%s unitNm", where).isEqualTo(src.getUnitNm());
            assertThat(dst.getUseYn()).as("%s useYn", where).isEqualTo(src.getUseYn());
            assertThat(dst.getSortSeq()).as("%s sortSeq", where).isEqualTo(src.getSortSeq());
            assertThat(dst.getMdfcnDt()).as("%s mdfcnDt", where).isEqualTo(src.getMdfcnDt());
        }

        // 보고서는 '생성 시점의 기록' 이므로 출처는 anls_param_json 뿐이다 — 마스터를 조인하지 않는다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, created.getExcnId()))
                .as("재현 메타 JSON 에 eps·minPoints·limit·weights·gradeThresholds 가 담긴다")
                .contains("\"weights\"").contains("\"eps\"").contains("\"minPoints\"")
                .contains("\"limit\"").contains("\"gradeCriteria\"");
    }

    // ────────────────────────────────────────────────── 6. 지역 3형태

    @Test
    @DisplayName("6. 지역 3형태 — regionNm 이 '전체 지역' / '제주시 전체' / '제주시 연동' 으로 저장·복원된다")
    void regionNameHasThreeShapes() {
        // ① 전체 지역 — 지역 키를 아예 넣지 않는다
        HotspotReportVO all = createOk(request(fullPeriod(), "PDF"));
        assertThat(all.getRegionNm()).isEqualTo("전체 지역");
        assertThat(all.getRptpNm()).endsWith("전체 지역");

        // ② 시군구 전체
        Map<String, Object> sggReq = request(fullPeriod(), "PDF");
        sggReq.put("sggNm", SGG_JEJU);
        HotspotReportVO sgg = createOk(sggReq);
        assertThat(sgg.getRegionNm()).isEqualTo(SGG_JEJU + " 전체");
        assertThat(sgg.getRptpNm()).endsWith(SGG_JEJU + " 전체");

        // ③ 시군구 + 읍면동 — emdCd 만 왔어도 서버가 TB_ADMDST 에서 시군구를 채워 넣는다
        Map<String, Object> emdReq = request(fullPeriod(), "PDF");
        emdReq.put("emdCd", EMD_YEONDONG);
        HotspotReportVO emd = createOk(emdReq);
        String expected = SGG_JEJU + " " + emdNmOf(EMD_YEONDONG);
        assertThat(emd.getRegionNm())
                .as("emdCd 만 온 요청에도 시군구가 채워져야 한다 (아니면 지역명이 통째로 NULL 이 된다)")
                .isEqualTo(expected);
        assertThat(emd.getRptpNm()).endsWith(expected);

        // 🔴 저장 경로(POST 조립)와 조회 경로(selectReportById 의 3단 CASE)가 같은 문자열을 내야 한다
        assertThat(snapshotOk(all.getRptpId()).getRegionNm()).isEqualTo(all.getRegionNm());
        assertThat(snapshotOk(sgg.getRptpId()).getRegionNm()).isEqualTo(sgg.getRegionNm());
        assertThat(snapshotOk(emd.getRptpId()).getRegionNm()).isEqualTo(emd.getRegionNm());

        // 지역 조건은 실행 이력에도 그대로 남는다 (재현 가능성) — 전 지역은 두 칸 모두 null 이다
        Map<String, Object> allExcn = jdbcTemplate.queryForMap(
                "SELECT sgg_nm, emd_cd FROM parking.tb_anls_excn WHERE excn_id = ?", all.getExcnId());
        assertThat(allExcn.get("sgg_nm")).as("전 지역은 시군구가 null 이다").isNull();
        assertThat(allExcn.get("emd_cd")).as("전 지역은 읍면동이 null 이다").isNull();

        Map<String, Object> emdExcn = jdbcTemplate.queryForMap(
                "SELECT sgg_nm, emd_cd FROM parking.tb_anls_excn WHERE excn_id = ?", emd.getExcnId());
        assertThat(emdExcn.get("sgg_nm"))
                .as("emdCd 만 온 요청이어도 실행 이력엔 시군구가 채워진다").isEqualTo(SGG_JEJU);
        assertThat(emdExcn.get("emd_cd")).isEqualTo(EMD_YEONDONG);
    }

    // ────────────────────────────────────────────────── 7 · 8. 스냅샷 404 3종

    @Test
    @DisplayName("7. 다른 유형(CVLCPT) 보고서의 집중구역 스냅샷 조회 — 404")
    void snapshotOfNonHotspotReportReturns404() {
        long rptpId = insertReportWithoutResult("CVLCPT", FIXTURE_NM_CVLCPT);

        // 전제 확인 — 픽스처가 CVLCPT 유형이어야 이 케이스의 의미가 성립한다
        assertThat(anlsTypeCdOfReport(rptpId))
                .as("픽스처 보고서 %d 는 CVLCPT 유형이어야 이 케이스가 성립한다", rptpId)
                .isEqualTo("CVLCPT");

        assertThat(snapshotStatus(rptpId))
                .as("이 경로로 열 수 있는 것은 집중구역뿐이다 (404-②)")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("8. 스냅샷 0행 보고서(유형은 HOTSPOT 이지만 결과행 없음)의 조회 — 404 (빈 문서를 200 으로 주지 않는다)")
    void snapshotOfHotspotReportWithoutResultRowsReturns404() {
        long rptpId = insertReportWithoutResult("HOTSPOT", FIXTURE_NM_HOTSPOT_EMPTY);

        assertThat(anlsTypeCdOfReport(rptpId))
                .as("픽스처 보고서 %d 는 HOTSPOT 유형이어야 이 케이스가 성립한다", rptpId)
                .isEqualTo("HOTSPOT");
        assertThat(snapshotRowCountOfReport(rptpId))
                .as("픽스처 보고서 %d 는 결과 0행이어야 이 케이스가 성립한다", rptpId)
                .isZero();

        assertThat(snapshotStatus(rptpId))
                .as("유형은 맞지만 스냅샷이 비었다 — 200 으로 주면 화면이 빈 문서를 그린다 (404-③)")
                .isEqualTo(404);
    }

    /**
     * 실행 이력 1행 + 그것을 참조하는 보고서 1행을 심고 {@code rptp_id} 를 돌려준다.
     * <b>결과행({@code TB_ANLS_RSLT})은 넣지 않는다</b> — 케이스 8 의 "스냅샷 0행" 이 그대로 성립한다.
     *
     * <p>🔴 <b>시드를 빌리지 않는다</b>. 종전에는 시드 보고서 {@code rptp_id=1}(CVLCPT)·
     * {@code 14}(HOTSPOT·결과 0행)를 상수로 박아 썼는데, 시드
     * ({@code database/migrations/sql/seeding/08-TB_RPTP.sql})가 보고서 목데이터를 전부 걷어
     * <b>그 번호가 더 이상 존재하지 않는다</b> — 두 케이스가 {@code EmptyResultDataAccessException} 으로
     * 깨졌다. 각 케이스가 필요한 것은 "그 유형이 아닌 보고서 1건" 뿐이므로 여기서 직접 만든다.
     *
     * <p>🔴 PK 를 하드코딩하지 않는다 — identity 가 채번한 값을 {@code RETURNING} 으로 받아
     * 즉시 추적 집합에 넣으므로, 정리는 클래스 주석의 규칙 그대로 <b>이 테스트가 만든 id 만</b>
     * 지운다({@link #deleteOnlyWhatThisTestCreated()}). 범위 DELETE 를 새로 들이지 않는다.
     *
     * <p>⚠️ {@code file_fmt} 는 <b>NULL</b> 로 넣는다 — NOT NULL 이 풀렸다(마이그레이션 72).
     * 여기서 NOT NULL 위반이 나면 그 마이그레이션이 아직 안 돌았다는 뜻이다.
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
                        + "         ?, '테스트', now()) RETURNING rptp_id",   // 🔴 소유자 가드를 통과해야 404 사유가 '유형' 으로 남는다
                Long.class, excnId, rptpNm, IntegrationLoginSupport.MOCK_ID);
        assertThat(rptpId).isNotNull();
        createdRptpIds.add(rptpId);
        return rptpId;
    }

    @Test
    @DisplayName("8-3. 🔴 익명 HTTP 는 생성·스냅샷 모두 401 이고 세 표가 한 행도 늘지 않는다")
    void anonymousIs401AndInsertsNothing() {
        long[] before = tableCounts();

        // 바디는 유효하다 — 같은 바디를 세션으로 보내면 201 이다(= 401 의 원인이 바디가 아니다)
        assertThat(restTemplate.postForEntity(CREATE_URL, request(fullPeriod(), "PDF"), String.class)
                .getStatusCode().value()).as("익명 생성").isEqualTo(401);
        Long anyId = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(rptp_id), 0) FROM parking.tb_rptp", Long.class);
        assertThat(restTemplate.getForEntity(SNAPSHOT_URL, String.class, anyId)
                .getStatusCode().value()).as("익명 스냅샷").isEqualTo(401);

        assertThat(tableCounts()).as("401 이면 아무것도 저장하지 않는다").containsExactly(before[0], before[1], before[2]);
    }

    @Test
    @DisplayName("8-2. 존재하지 않는 보고서아이디 — 404 (404-①)")
    void snapshotOfMissingReportReturns404() {
        Long missing = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(rptp_id), 0) + 100000 FROM parking.tb_rptp", Long.class);
        assertThat(snapshotStatus(missing)).isEqualTo(404);
    }

    // ──────────────────────────── 9. 옛 키 스냅샷 재개봉 (@JsonAlias 회귀 장치)

    @Test
    @DisplayName("9. 🔴 개명 이전에 굳은 옛 키(idctCd·wgvlRt) 스냅샷 — 부제 '민원빈도 60% / 단속건수 40%' 가 복원된다")
    void legacyKeySnapshotIsStillRestoredByJsonAlias() {
        // 🔴 이 행은 '만들 수 없는 과거' 다 — 지금 코드는 새 키로만 굳히므로 테스트가 직접 심는다.
        //    (마이그레이션 67 이전에 생성된 보고서가 운영 DB 에 그대로 남아 있는 상황을 재현한다)
        long rptpId = insertLegacyHotspotReport();

        HotspotReportVO snapshot = snapshotOk(rptpId);

        List<AnalysisWeightVO> weights = snapshot.getWeights();
        // 🔴 폴백이 없으면 여기서 '빈 목록' 이 온다 — ObjectMapper 가 FAIL_ON_UNKNOWN_PROPERTIES 를
        //    꺼 둬서 예외도 로그도 없이 전 필드가 null 인 VO 2건이 된다(조용한 실패).
        assertThat(weights).as("@JsonAlias 폴백이 빠지면 값이 통째로 비어 부제가 사라진다").hasSize(2);

        // 옛 키 5종(idctCd·idctNm·idctExpln·wgvlRt·bscWgvlRt)이 새 필드로 들어왔는지 하나씩 본다
        AnalysisWeightVO complaint = weights.get(0);
        AnalysisWeightVO enforce = weights.get(1);
        assertThat(complaint.getVrblCd()).as("idctCd → vrblCd").isEqualTo(VRBL_COMPLAINT);
        assertThat(enforce.getVrblCd()).isEqualTo(VRBL_ENFORCE);
        assertThat(complaint.getVrblNm()).as("idctNm → vrblNm").isEqualTo("민원빈도");
        assertThat(enforce.getVrblNm()).isEqualTo("단속건수");
        assertThat(complaint.getVrblExpln()).as("idctExpln → vrblExpln").isEqualTo("수집플랫폼 표준 민원 데이터");
        assertBigDecimalEquals(complaint.getStngVl(), new BigDecimal("60"), "wgvlRt → stngVl (민원)");
        assertBigDecimalEquals(enforce.getStngVl(), new BigDecimal("40"), "wgvlRt → stngVl (단속)");
        assertBigDecimalEquals(complaint.getBscStngVl(), new BigDecimal("60"), "bscWgvlRt → bscStngVl (민원)");
        assertBigDecimalEquals(enforce.getBscStngVl(), new BigDecimal("40"), "bscWgvlRt → bscStngVl (단속)");

        // 화면 부제가 바로 이 두 값으로 조립된다 — 문장으로 한 번 더 못박는다
        assertThat(complaint.getVrblNm() + " " + complaint.getStngVl().stripTrailingZeros().toPlainString()
                + "% / " + enforce.getVrblNm() + " " + enforce.getStngVl().stripTrailingZeros().toPlainString() + "%")
                .isEqualTo("민원빈도 60% / 단속건수 40%");

        // 🆕 신규 4필드는 옛 스냅샷에 애초에 없다 — null 이 정상이다(폴백 대상이 아니다)
        assertThat(complaint.getVrblSeCd()).as("옛 스냅샷엔 vrbl_se_cd 가 없었다").isNull();
        assertThat(complaint.getMinVl()).isNull();
        assertThat(complaint.getMaxVl()).isNull();
        assertThat(complaint.getUnitNm()).isNull();

        // 🔴 키가 없는 구 스냅샷은 'N' 이 아니라 null 이다(조정 여부를 모른다)
        assertThat(snapshot.getWgvlAjmtYn()).as("VO 초기값·폴백이 생기면 여기서 N 이 된다").isNull();
        assertThat(snapshot.getAnlsMthd()).as("VO 초기값·폴백이 생기면 구 보고서가 새 문구로 읽힌다").isNull();
        assertThat(snapshot.getGradeCriteria()).as("구 보고서").isNull();
        assertThat(snapshot.getVrblAjmt()).as("🔴 키가 없는 구 보고서는 null 이다").isNull();

        // 본문(zones)도 정상 복원돼 '가중치만 비는' 상황과 구분된다
        assertThat(snapshot.getZones()).hasSize(1);
        assertThat(snapshot.getZones().get(0).getZoneNm()).isEqualTo("옛키 스냅샷 구역");
        // 🔴 grade 키가 없는 구 스냅샷은 저장된 점수로 재판정한다
        assertThat(snapshot.getZones().get(0).getGrade())
                .isEqualTo(HotspotGrade.of(snapshot.getZones().get(0).getSnthsScr()).name());
    }

    /**
     * 개명(마이그레이션 67) <b>이전 형식</b>의 {@code anls_param_json} 을 가진 보고서 한 벌을 심는다.
     * 실행 → 결과 → 보고서 순(FK 방향)이며, 심은 id 는 즉시 추적 집합에 넣어
     * {@link #deleteOnlyWhatThisTestCreated()} 가 지우게 한다.
     *
     * <p>🔴 옛 키 5종({@code idctCd}·{@code idctNm}·{@code idctExpln}·{@code wgvlRt}·{@code bscWgvlRt})을
     * 쓴다 — 이 문자열이 곧 회귀 장치다. 새 키로 바꾸면 {@code @JsonAlias} 를 지워도 테스트가 통과한다.
     */
    private long insertLegacyHotspotReport() {
        String legacyParamJson = "{\"eps\":50,\"minPoints\":3,\"limit\":15,\"weights\":["
                + "{\"anlsTypeCd\":\"HOTSPOT\",\"idctCd\":\"COMPLAINT\",\"idctNm\":\"민원빈도\","
                + "\"idctExpln\":\"수집플랫폼 표준 민원 데이터\",\"wgvlRt\":60.00,\"bscWgvlRt\":60.00,"
                + "\"useYn\":\"Y\",\"sortSeq\":1},"
                + "{\"anlsTypeCd\":\"HOTSPOT\",\"idctCd\":\"ENFORCE\",\"idctNm\":\"단속건수\","
                + "\"idctExpln\":\"제주도 주정차 단속 데이터\",\"wgvlRt\":40.00,\"bscWgvlRt\":40.00,"
                + "\"useYn\":\"Y\",\"sortSeq\":2}],"
                + "\"gradeThresholds\":{\"SEVERE\":80,\"WARN\":60,\"CAUTION\":40}}";
        String rsltJson = "{\"zones\":[{\"rnk\":1,\"zoneNm\":\"옛키 스냅샷 구역\",\"cvlcptCnt\":10,"
                + "\"crdnCnt\":5,\"snthsScr\":8.00,\"mrkLat\":33.4996,\"mrkLot\":126.5312}]}";

        Long excnId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_anls_excn"
                        + " (anls_type_cd, excutr_id, excn_stts_cd, excn_dt, anls_param_json)"
                        + " VALUES ('HOTSPOT', 'system', 'DONE', now(), ?::jsonb) RETURNING excn_id",
                Long.class, legacyParamJson);
        assertThat(excnId).isNotNull();
        createdExcnIds.add(excnId);

        jdbcTemplate.update(
                "INSERT INTO parking.tb_anls_rslt (excn_id, rslt_json) VALUES (?, ?::jsonb)",
                excnId, rsltJson);

        Long rptpId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_rptp"
                        + " (crt_type, excn_id, rptp_nm, anls_prd_expln, stts_cd, file_fmt,"
                        + "  creatr_id, creatr_nm, crt_ymd)"
                        + " VALUES ('MANUAL', ?, '옛키 스냅샷 보고서', '테스트 기간', 'DONE', 'PDF',"
                        + "         ?, '관리자', now()) RETURNING rptp_id",   // 🔴 소유자 가드 통과
                Long.class, excnId, IntegrationLoginSupport.MOCK_ID);
        assertThat(rptpId).isNotNull();
        createdRptpIds.add(rptpId);
        return rptpId;
    }

    // ────────────────────────────────────────────────── 사용자 입력 가중치

    @Test
    @DisplayName("입력 80/20 — anls_param_json.weights 가 80·20 이고 저장 순위가 같은 값 조회 순위와 같다")
    void userWeightsReachParamJsonAndSavedRanking() {
        // 🔴 toAnalysisRequest 에서 필드가 빠지면 보고서만 설정값으로 분석된다 — 이 케이스가 잡는다
        HotspotReportVO created = createOk(withWeights(request(fullPeriod(), "PDF"), "80", "20"));

        Map<String, BigDecimal> saved = weightsInParamJson(created.getExcnId());
        assertThat(saved).containsOnlyKeys(VRBL_COMPLAINT, VRBL_ENFORCE);
        assertThat(saved.get(VRBL_COMPLAINT)).isEqualByComparingTo("80");
        assertThat(saved.get(VRBL_ENFORCE)).isEqualByComparingTo("20");

        ResponseEntity<HotspotAnalysisResultVO> queried = restTemplate.exchange(
                "/api/analysis/hotspot?bgngYmd={bgngYmd}&endYmd={endYmd}&cvlcptWgvl={cvlcptWgvl}&crdnWgvl={crdnWgvl}",
                HttpMethod.GET, new HttpEntity<>(session), HotspotAnalysisResultVO.class,
                queryBgngYmd, dataEndYmd, "80", "20");
        assertThat(queried.getStatusCode().value()).isEqualTo(200);
        assertThat(queried.getBody()).isNotNull();

        // 응답이 아니라 저장본을 다시 읽어 대조한다
        List<HotspotZoneVO> snapshot = snapshotOk(created.getRptpId()).getZones();
        assertThat(snapshot).isNotEmpty();
        assertThat(rankingOf(snapshot)).isEqualTo(rankingOf(queried.getBody().getZones()));
    }

    @Test
    @DisplayName("입력 생략 — anls_param_json.weights 가 설정값이다")
    void omittedUserWeightsKeepSettingsInParamJson() {
        HotspotReportVO created = createOk(request(fullPeriod(), "PDF"));

        Map<String, BigDecimal> saved = weightsInParamJson(created.getExcnId());
        List<Map<String, Object>> settings = jdbcTemplate.queryForList(
                "SELECT vrbl_cd, stng_vl FROM parking.tb_anls_vrbl_stng"
                        + " WHERE anls_type_cd = 'HOTSPOT' AND vrbl_se_cd = 'WGVL' AND use_yn = 'Y'");
        assertThat(settings).isNotEmpty();
        assertThat(saved).hasSameSizeAs(settings);
        for (Map<String, Object> row : settings) {
            assertBigDecimalEquals(saved.get((String) row.get("vrbl_cd")), (BigDecimal) row.get("stng_vl"),
                    "weights " + row.get("vrbl_cd"));
        }
    }

    @Test
    @DisplayName("입력 합계 99 — 400 이고 세 표 어디에도 행이 늘지 않는다")
    void invalidUserWeightsReturn400AndWriteNothing() {
        long[] before = tableCounts();

        assertThat(postStatus(withWeights(request(fullPeriod(), "PDF"), "60", "39"))).isEqualTo(400);

        assertThat(tableCounts()).containsExactly(before[0], before[1], before[2]);
    }

    @Test
    @DisplayName("wgvlAjmtYn — 입력 80/20 은 생성 응답·anls_param_json·스냅샷이 모두 Y, 입력 생략은 모두 N")
    void adjustmentFlagReachesResponseParamJsonAndSnapshot() {
        // 🔴 80/20 이 현재 설정과 같으면 Y 전제가 성립하지 않는다 — 설정이 그렇다면 테스트 전제부터 드러낸다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = 'HOTSPOT'"
                        + " AND vrbl_cd = 'COMPLAINT' AND stng_vl = 80", Integer.class))
                .as("전제 — 민원 설정이 80 이 아니어야 80/20 이 조정이다").isZero();

        HotspotReportVO adjusted = createOk(withWeights(request(fullPeriod(), "PDF"), "80", "20"));
        assertThat(adjusted.getWgvlAjmtYn()).as("생성 응답").isEqualTo("Y");
        assertThat(wgvlAjmtYnInParamJson(adjusted.getExcnId())).as("anls_param_json").isEqualTo("Y");
        assertThat(snapshotOk(adjusted.getRptpId()).getWgvlAjmtYn()).as("스냅샷").isEqualTo("Y");

        HotspotReportVO plain = createOk(request(fullPeriod(), "PDF"));
        assertThat(plain.getWgvlAjmtYn()).as("생성 응답").isEqualTo("N");
        assertThat(wgvlAjmtYnInParamJson(plain.getExcnId())).as("anls_param_json").isEqualTo("N");
        assertThat(snapshotOk(plain.getRptpId()).getWgvlAjmtYn()).as("스냅샷").isEqualTo("N");

        // 🔴 새 보고서는 산정 방식이 생성 응답·스냅샷에 모두 실린다
        assertThat(plain.getAnlsMthd()).as("생성 응답").isEqualTo("LIST_MAX");
        assertThat(snapshotOk(plain.getRptpId()).getAnlsMthd()).as("스냅샷").isEqualTo("LIST_MAX");
        assertThat(snapshotOk(plain.getRptpId()).getGradeCriteria()).as("생성 당시 등급 비율").hasSize(3);
    }

    // ────────────────────────────────────────────────────────────── 등급 비율 일시 입력

    @Test
    @DisplayName("vrblVls — 적용값이 POST·anls_param_json·rslt_json·스냅샷에 남고 같은 값 조회와 순위가 같으며 설정은 그대로다")
    void gradeBoundaryInputReachesReport() {
        String settingsSql = "SELECT * FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd";
        List<Map<String, Object>> settingsBefore = jdbcTemplate.queryForList(settingsSql);

        int[] cur = currentBoundaries();
        int caution = cur[2] + 10;
        assertThat(caution).as("전제 — 현재 주의 비율 + 10 이 100 이하").isLessThanOrEqualTo(100);

        Map<String, Object> body = request(fullPeriod(), "PDF");
        body.put("vrblVls", Map.of("GRD_BNDRY1", cur[0], "GRD_BNDRY2", cur[1], "GRD_BNDRY3", caution));
        HotspotReportVO created = createOk(body);

        // 🔴 gradeCriteria 스냅샷은 설정값이 아니라 적용값이어야 한다
        assertThat(created.getGradeCriteria()).extracting(GradeCriterionVO::getMaxRankPct)
                .containsExactly(cur[0], cur[1], caution);
        assertThat(jdbcTemplate.queryForList(
                "SELECT (c->>'maxRankPct')::int FROM parking.tb_anls_excn e,"
                        + " jsonb_array_elements(e.anls_param_json->'gradeCriteria') c WHERE e.excn_id = ?",
                Integer.class, created.getExcnId()))
                .as("anls_param_json.gradeCriteria").containsExactly(cur[0], cur[1], caution);

        assertThat(created.getVrblAjmt()).containsEntry("ajmtYn", "Y");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT anls_param_json->'vrblAjmt'->>'ajmtYn' FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, created.getExcnId())).as("anls_param_json.vrblAjmt").isEqualTo("Y");
        assertThat(new BigDecimal(jdbcTemplate.queryForObject(
                "SELECT v->>'aplcnVl' FROM parking.tb_anls_excn e,"
                        + " jsonb_array_elements(e.anls_param_json->'vrblAjmt'->'vrbls') v"
                        + " WHERE e.excn_id = ? AND v->>'vrblCd' = 'GRD_BNDRY3'",
                String.class, created.getExcnId()))).isEqualByComparingTo(BigDecimal.valueOf(caution));

        HotspotReportVO snapshot = snapshotOk(created.getRptpId());
        assertThat(snapshot.getVrblAjmt()).as("POST·GET 이 같은 값이다").isEqualTo(created.getVrblAjmt());
        assertThat(snapshot.getGradeCriteria()).extracting(GradeCriterionVO::getMaxRankPct)
                .containsExactly(cur[0], cur[1], caution);

        ResponseEntity<HotspotAnalysisResultVO> queried = restTemplate.exchange(
                "/api/analysis/hotspot?bgngYmd={bgngYmd}&endYmd={endYmd}&vrblVls={vrblVls}",
                HttpMethod.GET, new HttpEntity<>(session), HotspotAnalysisResultVO.class,
                queryBgngYmd, dataEndYmd,
                "{\"GRD_BNDRY1\":" + cur[0] + ",\"GRD_BNDRY2\":" + cur[1] + ",\"GRD_BNDRY3\":" + caution + "}");
        assertThat(queried.getStatusCode().value()).isEqualTo(200);
        assertThat(queried.getBody()).isNotNull();
        assertThat(rankingOf(snapshot.getZones())).isEqualTo(rankingOf(queried.getBody().getZones()));
        assertThat(snapshot.getZones()).extracting(HotspotZoneVO::getGrade)
                .as("저장 등급").isEqualTo(queried.getBody().getZones().stream().map(HotspotZoneVO::getGrade).toList());

        assertThat(jdbcTemplate.queryForList(settingsSql)).as("설정 표는 그대로다").isEqualTo(settingsBefore);
    }

    @Test
    @DisplayName("vrblVls 생략은 ajmtYn N · 설정값 gradeCriteria, 가중치만 조정은 vrblAjmt N + wgvlAjmtYn Y")
    void omittedGradeBoundaryKeepsSettings() {
        int[] cur = currentBoundaries();

        HotspotReportVO plain = createOk(request(fullPeriod(), "PDF"));
        assertThat(plain.getVrblAjmt()).containsEntry("ajmtYn", "N");
        assertThat(plain.getGradeCriteria()).extracting(GradeCriterionVO::getMaxRankPct)
                .containsExactly(cur[0], cur[1], cur[2]);
        assertThat(snapshotOk(plain.getRptpId()).getVrblAjmt()).isEqualTo(plain.getVrblAjmt());

        HotspotReportVO weightOnly = createOk(withWeights(request(fullPeriod(), "PDF"), "80", "20"));
        assertThat(weightOnly.getVrblAjmt()).containsEntry("ajmtYn", "N");
        assertThat(weightOnly.getWgvlAjmtYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("vrblVls 위반(역전 · COMPLAINT · null) — 400 이고 세 표 어디에도 행이 늘지 않는다")
    void invalidGradeBoundaryReturns400AndWritesNothing() {
        long[] before = tableCounts();

        Map<String, Object> reversed = request(fullPeriod(), "PDF");
        reversed.put("vrblVls", Map.of("GRD_BNDRY1", 20, "GRD_BNDRY2", 10, "GRD_BNDRY3", 30));
        assertThat(postStatus(reversed)).as("역전").isEqualTo(400);

        Map<String, Object> weightCode = request(fullPeriod(), "PDF");
        weightCode.put("vrblVls", Map.of("COMPLAINT", 50));
        assertThat(postStatus(weightCode)).as("가중치 코드는 조정 대상이 아니다").isEqualTo(400);

        Map<String, Object> nullValue = request(fullPeriod(), "PDF");
        Map<String, Object> nullEntry = new LinkedHashMap<>();
        nullEntry.put("GRD_BNDRY1", null);
        nullValue.put("vrblVls", nullEntry);
        assertThat(postStatus(nullValue)).as("null 값").isEqualTo(400);

        assertThat(tableCounts()).containsExactly(before[0], before[1], before[2]);
    }

    /** 현재 설정의 {@code GRD_BNDRY1~3}. 기대값은 상수가 아니라 이 값으로 계산한다. */
    private int[] currentBoundaries() {
        return jdbcTemplate.queryForList(
                "SELECT stng_vl FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = 'HOTSPOT'"
                        + " AND vrbl_cd IN ('GRD_BNDRY1', 'GRD_BNDRY2', 'GRD_BNDRY3') ORDER BY vrbl_cd",
                BigDecimal.class).stream().mapToInt(BigDecimal::intValueExact).toArray();
    }

    private String wgvlAjmtYnInParamJson(Long excnId) {
        return jdbcTemplate.queryForObject(
                "SELECT anls_param_json->>'wgvlAjmtYn' FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, excnId);
    }

    /** 요청 바디에 입력 가중치를 얹는다. */
    private static Map<String, Object> withWeights(Map<String, Object> body, String cvlcptWgvl, String crdnWgvl) {
        body.put("cvlcptWgvl", new BigDecimal(cvlcptWgvl));
        body.put("crdnWgvl", new BigDecimal(crdnWgvl));
        return body;
    }

    /** {@code anls_param_json.weights} 를 변수코드 → 값으로 읽는다. */
    private Map<String, BigDecimal> weightsInParamJson(Long excnId) {
        String json = jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?", String.class, excnId);
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        try {
            for (JsonNode node : objectMapper.readTree(json).path("weights")) {
                weights.put(node.get("vrblCd").asText(), new BigDecimal(node.get("stngVl").asText()));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("anls_param_json 을 읽지 못했다: " + json, e);
        }
        return weights;
    }

    /** 순위 비교용 — 저장·복원되는 칸만, 점수는 스케일 차이로 다르게 보이지 않게 표기를 맞춘다. */
    private static List<String> rankingOf(List<HotspotZoneVO> zones) {
        return zones.stream()
                .map(zone -> zone.getRnk() + "|" + zone.getZoneNm() + "|" + zone.getCvlcptCnt() + "|"
                        + zone.getCrdnCnt() + "|" + zone.getSnthsScr().stripTrailingZeros().toPlainString())
                .toList();
    }

    // ────────────────────────────────────────────────────────────── 헬퍼 — 호출

    /**
     * 생성 성공을 기대하는 호출. 🔴 <b>응답을 받자마자 id 를 등록</b>한다 — 뒤 단언이 실패해도
     * {@link #deleteOnlyWhatThisTestCreated()} 가 그 행을 지울 수 있어야 한다.
     */
    private HotspotReportVO createOk(Map<String, Object> body) {
        ResponseEntity<HotspotReportVO> response = restTemplate.exchange(
                CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, body), HotspotReportVO.class);
        HotspotReportVO vo = response.getBody();
        track(vo);
        assertThat(response.getStatusCode().value()).as("생성 응답은 201 이다 (요청: %s)", body).isEqualTo(201);
        assertThat(vo).isNotNull();
        return vo;
    }

    /**
     * 실패가 예상되는 생성 호출 — 에러 바디를 VO 로 역직렬화하려다 터지는 것을 막으려 String 으로 받는다.
     *
     * <p>🔴 <b>200번대면 그 응답의 id 도 추적한다.</b> 여기 오는 호출은 "400 이어야 한다" 는 단언용인데,
     * 구현이 깨져 실제로 생성되면 <b>단언은 실패하고 행은 남는다</b> — 그 행은 어느 정리에도 걸리지 않아
     * 다음 실행까지 오염된다. 실패하는 순간에도 뒷정리가 되게 여기서 id 를 건져 둔다.
     */
    private int postStatus(Map<String, Object> body) {
        ResponseEntity<String> response = restTemplate.exchange(
                CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, body), String.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            trackFromJson(response.getBody());
        }
        return response.getStatusCode().value();
    }

    /** 예상 밖 성공 응답에서 {@code rptpId}·{@code excnId} 만 건져 정리 대상에 넣는다. */
    private void trackFromJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            HotspotReportVO vo = new HotspotReportVO();
            if (node.hasNonNull("rptpId")) {
                vo.setRptpId(node.get("rptpId").asLong());
            }
            if (node.hasNonNull("excnId")) {
                vo.setExcnId(node.get("excnId").asLong());
            }
            track(vo);
        } catch (JsonProcessingException e) {   // 4.3 — readTree 가 던지는 파싱 예외만
            // 바디가 보고서 JSON 이 아니면 지울 것도 없다.
            org.slf4j.LoggerFactory.getLogger(HotspotReportIntegrationTest.class).debug("응답 JSON 파싱 생략 — {}", e.getClass().getSimpleName());   // 4.2
        }
    }

    private HotspotReportVO snapshotOk(Long rptpId) {
        ResponseEntity<HotspotReportVO> response = restTemplate.exchange(
                SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session), HotspotReportVO.class, rptpId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private int snapshotStatus(Object rptpId) {
        return restTemplate.exchange(SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session),
                String.class, rptpId).getStatusCode().value();
    }

    private void track(HotspotReportVO vo) {
        if (vo == null) {
            return;
        }
        if (vo.getRptpId() != null) {
            createdRptpIds.add(vo.getRptpId());
        }
        if (vo.getExcnId() != null) {
            createdExcnIds.add(vo.getExcnId());
        }
    }

    // ────────────────────────────────────────────────────────────── 헬퍼 — 요청 조립

    /** 필수 두 칸만 담은 가변 맵. 호출부가 지역을 덧붙인다. */
    private static Map<String, Object> period(String bgngYmd, String endYmd) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bgngYmd", bgngYmd);
        params.put("endYmd", endYmd);
        return params;
    }

    /** 원천 보유 구간 전체 — 결과를 보는 케이스는 전부 이것을 쓴다 (상수 박기 금지). */
    private Map<String, Object> fullPeriod() {
        return period(queryBgngYmd, dataEndYmd);
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

    /** {@code fileFmt} 가 {@code null} 이면 <b>키 자체를 넣지 않는다</b> (누락 = @NotBlank 검증 대상). */
    private static Map<String, Object> request(Map<String, Object> period, String fileFmt) {
        if (fileFmt != null) {
            period.put("fileFmt", fileFmt);
        }
        return period;
    }

    // ────────────────────────────────────────────────────────────── 헬퍼 — 단언

    /**
     * 목록이 지켜야 할 계약을 한 번에 확인한다 — 데이터가 어떻게 바뀌어도 성립하는 것만 담는다.
     * 계수는 응답의 {@code weights} 에서 뽑으므로 설정이 60/40 이 아니게 돼도 그대로 성립한다.
     */
    private static void assertZoneContract(List<HotspotZoneVO> zones, List<AnalysisWeightVO> weights) {
        BigDecimal cvlcptCoef = coefficientOf(weights, VRBL_COMPLAINT);
        BigDecimal crdnCoef = coefficientOf(weights, VRBL_ENFORCE);
        for (int i = 0; i < zones.size(); i++) {
            HotspotZoneVO zone = zones.get(i);
            assertThat(zone.getRnk()).as("rnk 는 1 부터 빈틈없이 오름차순").isEqualTo(i + 1);
            assertThat(zone.getZoneNm()).as("구역명은 폴백까지 거쳐 최종적으로 비지 않는다").isNotBlank();
            assertThat(zone.getSnthsScr()).isNotNull();
            assertScoreSatisfiesNormalizedFormula(zone, cvlcptCoef, crdnCoef);
            assertGradeMatchesScore(zone);
            if (i > 0) {
                assertThat(zone.getSnthsScr())
                        .as("종합점수는 내림차순이어야 한다 (rnk %d)", zone.getRnk())
                        .isLessThanOrEqualTo(zones.get(i - 1).getSnthsScr());
            }
        }
    }

    /** 가중치 비율(%)을 산식 계수로 옮긴다 — 지표가 꺼져 목록에 없으면 계수 0 이다. */
    private static BigDecimal coefficientOf(List<AnalysisWeightVO> weights, String vrblCd) {
        if (weights != null) {
            for (AnalysisWeightVO weight : weights) {
                if (vrblCd.equals(weight.getVrblCd()) && weight.getStngVl() != null) {
                    return weight.getStngVl().divide(new BigDecimal("100"));
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 종합점수의 <b>성질</b>을 확인한다 — 데이터·조건이 어떻게 바뀌어도 성립하는 것만 본다.
     * <ol>
     *   <li>조회 결과 1위 = 100 이라 점수는 <b>항상 0~100</b> 이다.</li>
     *   <li>계수가 살아 있는 지표의 건수가 모두 0 이면 점수도 0 이다(기여할 것이 없다).</li>
     *   <li>등급은 표시 3종이다.</li>
     * </ol>
     *
     * <p>🔴 {@code HotspotAnalysisIntegrationTest} 의 같은 이름 헬퍼와 <b>같은 방식</b>으로 유지한다 —
     * 산식의 <b>구체적인</b> 검증은 그쪽 케이스 11~14 가 전담한다.
     */
    private static void assertScoreSatisfiesNormalizedFormula(HotspotZoneVO zone,
                                                             BigDecimal cvlcptCoef, BigDecimal crdnCoef) {
        BigDecimal score = zone.getSnthsScr();
        assertThat(score)
                .as("구역 %s 의 점수는 0~100 이어야 한다", zone.getZoneNm())
                .isBetween(BigDecimal.ZERO, SCORE_MAX);

        boolean scoredCountIsZero = (isZeroCoefficient(cvlcptCoef) || zone.getCvlcptCnt() == 0)
                && (isZeroCoefficient(crdnCoef) || zone.getCrdnCnt() == 0);
        if (scoredCountIsZero) {
            assertThat(score)
                    .as("계수가 살아 있는 지표의 건수가 0 이면 점수도 0 이다 (구역 %s)", zone.getZoneNm())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        assertGradeMatchesScore(zone);
    }

    /** 계수가 0 인지 — 0 배인 지표는 점수에 기여하지 않는다(산식에 없는 것과 같다). */
    private static boolean isZeroCoefficient(BigDecimal coefficient) {
        return coefficient == null || coefficient.signum() == 0;
    }

    /** 표시 등급 3종이고 코드와 이름이 맞는지 — 순위 비율 판정은 {@code HotspotGradeTest} 가 본다. */
    private static void assertGradeMatchesScore(HotspotZoneVO zone) {
        String expectedNm = switch (zone.getGrade()) {
            case "SEVERE" -> "심각";
            case "WARN" -> "경고";
            case "CAUTION" -> "주의";
            default -> throw new AssertionError("표시 대상이 아닌 등급: " + zone.getGrade());
        };
        assertThat(zone.getGradeNm()).isEqualTo(expectedNm);
    }

    /** {@code NUMERIC} 스케일 차이에 걸리지 않는 비교. 둘 다 {@code null} 이면 통과다. */
    private static void assertBigDecimalEquals(BigDecimal actual, BigDecimal expected, String where) {
        if (expected == null) {
            assertThat(actual).as("%s", where).isNull();
            return;
        }
        assertThat(actual).as("%s", where).isNotNull();
        assertThat(actual).as("%s", where).isEqualByComparingTo(expected);
    }

    // ────────────────────────────────────────────────────────────── 헬퍼 — DB 조회

    /** {@code TB_ANLS_EXCN} · {@code TB_ANLS_RSLT} · {@code TB_RPTP} 행 수 (증가분 단언용). */
    private long[] tableCounts() {
        return new long[] {
                countOf("parking.tb_anls_excn"),
                countOf("parking.tb_anls_rslt"),
                countOf("parking.tb_rptp") };
    }

    private long countOf(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private String anlsTypeCdOfReport(long rptpId) {
        return jdbcTemplate.queryForObject(
                "SELECT e.anls_type_cd FROM parking.tb_rptp r"
                        + "  LEFT JOIN parking.tb_anls_excn e ON e.excn_id = r.excn_id"
                        + " WHERE r.rptp_id = ?", String.class, rptpId);
    }

    /**
     * 보고서에 묶인 스냅샷 <b>구역 수</b> — 행수가 아니라 {@code jsonb_array_length(rslt_json->'zones')} 다
     * 결과 행 자체가 없으면 {@code COALESCE} 로 0 — 매퍼 {@code selectHotspotExcnMeta} 와 같은 식이다.
     */
    private long snapshotRowCountOfReport(long rptpId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COALESCE(jsonb_array_length(h.rslt_json->'zones'), 0) FROM parking.tb_rptp r"
                        + "  LEFT JOIN parking.tb_anls_rslt h ON h.excn_id = r.excn_id"
                        + " WHERE r.rptp_id = ?", Long.class, rptpId);
        return count == null ? 0L : count;
    }

    private String emdNmOf(String emdCd) {
        return jdbcTemplate.queryForObject(
                "SELECT emd_nm FROM parking.tb_admdst WHERE emd_cd = ?", String.class, emdCd);
    }

    /**
     * 매퍼 {@code selectDataRange} 와 <b>같은 조건</b>으로 보유 구간 경계를 읽는다 (상수 박기 회피 —
     * {@code HotspotAnalysisIntegrationTest} 와 같은 SQL 이다). 조건이 갈리면 이유 없이 깨지므로
     * 매퍼 XML 과 함께 고친다.
     */
    private static String dataRangeSql() {
        return "SELECT to_char(LEAST(   (SELECT min(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                + "                     (SELECT min(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD'),"
                + "       to_char(GREATEST((SELECT max(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                + "                     (SELECT max(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD')";
    }

    /** 구간 안의 원천 행 수(민원 + 단속). 케이스 3 의 "정말 0건인가" 를 SQL 로 보장한다. */
    private long sourceRowCount(String bgngYmd, String endYmd) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT (SELECT count(*) FROM parking.tb_cvlcpt"
                        + "  WHERE cvlcpt_type_cd <> '99'"
                        + "    AND ocrn_dt >= to_date(?, 'YYYYMMDD') AND ocrn_dt < to_date(?, 'YYYYMMDD') + 1)"
                        + "     + (SELECT count(*) FROM parking.tb_crdn_dsctn"
                        + "  WHERE crdn_dt >= to_date(?, 'YYYYMMDD') AND crdn_dt < to_date(?, 'YYYYMMDD') + 1)",
                Long.class, bgngYmd, endYmd, bgngYmd, endYmd);
        return count == null ? 0L : count;
    }
}
