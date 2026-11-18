package jeju.datahub.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

import jeju.datahub.dashboard.service.AnalysisVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 대시보드 패널 "최근 분석 실행" 조회 API 통합 테스트 (~3).
 *
 * <p><b>무엇을 지키는 테스트인가</b> — {@code GET /api/dashboard/analyses} 에 새로 붙은
 * {@code anlsTypeCd} 필터의 계약 3가지다.
 * <ol>
 *   <li>미전송이면 종전대로 분석 화면용 5종 전체({@code addl_atrb1 IN ('ANLS','BOTH')})</li>
 *   <li>값을 주면 그 유형 1종으로만 좁힌다 — 0건이어도 오류가 아니다</li>
 *   <li><b>빈 문자열은 미전송과 같다</b> — 그대로 SQL 로 내려가면 {@code = ''} 로 걸려 늘 0건이 된다.
 *       서비스가 blank→null 로 정규화하는지를 이 케이스가 지킨다</li>
 * </ol>
 *
 * <p>🔴 <b>기대 건수를 상수로 박지 않는다.</b> {@code TB_ANLS_EXCN} 은 시드와 보고서 저장 테스트가 함께
 * 소유하므로 숫자를 굳히면 남의 변경에 깨진다 — 필터를 건 응답을 <b>필터 없는 응답과 직접 비교</b>하고,
 * 유형 필터는 "모든 행이 그 유형인가" 로만 검사한다.
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도 스레드·별도
 * 커넥션으로 처리해 테스트 트랜잭션이 서버 쪽을 감싸지 못한다({@code ComplaintDashboardIntegrationTest} ·
 * {@code ReportIntegrationTest} 와 같은 전략).
 *
 * <p>🔴 <b>실행 이력 2행을 직접 심는다</b> — 종전에는 시드가 깔아 둔 {@code TB_ANLS_EXCN} 을
 * 빌려 썼으나, 보고서 목데이터를 걷으면서 그 표가 <b>0행</b>이 됐다. 0행이면 케이스 1 의
 * "보고서 전용 유형이 섞이지 않는다" 가 <b>아무것도 검증하지 못한다</b>. 그래서 대조에 필요한 최소
 * 2행({@code HOTSPOT} = 나와야 하는 것 · {@code CVLCPT} = 걸러져야 하는 것)을 이 테스트가 만들고
 * {@code @AfterEach} 가 <b>자기가 만든 id 만</b> 지운다.
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("대시보드 최근 분석 실행 (GET /api/dashboard/analyses)")
class DashboardPanelIntegrationTest {

    private static final String ANALYSES_URL = "/api/dashboard/analyses";

    /** 분석 실행 유형 코드(정책시뮬레이션). 건수가 아니라 <b>필터 입력값</b>이라 상수로 둔다. */
    private static final String TYPE_POLICY_SIM = "POLICY_SIM";

    /** 대시보드 카드 기본값(5)보다 넉넉히 잡아 필터 유무 비교가 상위 5건에 가려지지 않게 한다. */
    private static final int LIMIT = 50;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 이 테스트가 심은 실행 이력 id. 🔴 범위({@code > base})가 아니라 <b>id 목록</b>으로 지운다 */
    private final List<Long> createdExcnIds = new ArrayList<>();

    /**
     * 🔴 이 API 는 이제 로그인 사용자가 실행한 것만 낸다(익명은 401). mock-login 세션으로 부르고,
     * 픽스처의 {@code excutr_id} 도 같은 {@link IntegrationLoginSupport#MOCK_ID} 로 심는다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    /**
     * 대조에 필요한 최소 2행을 심는다 — 나와야 하는 것({@code HOTSPOT}: {@code addl_atrb1='BOTH'})과
     * 걸러져야 하는 것({@code CVLCPT}: {@code 'RPTP'}).
     *
     * <p>🔴 건수를 늘리는 것이 목적이 아니다 — 케이스 1 이 "보고서 전용 유형이 섞이지 않는다" 를
     * 실제로 확인할 수 있게 <b>양쪽 표본</b>을 만드는 것이다.
     */
    @BeforeEach
    void installExecutionFixture() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();   // 🔴 빠지면 cleanUp() 이 로그인 이력 전량을 지운다
        session = auth.mockSession();

        createdExcnIds.add(insertExcn("HOTSPOT"));
        createdExcnIds.add(insertExcn("CVLCPT"));
    }

    /** 🔴 자기가 만든 id 만 지운다 — 착수 상태({@code tb_anls_excn} 0행)를 그대로 되돌린다. */
    @AfterEach
    void cleanUpCreated() {
        try {
            for (Long excnId : createdExcnIds) {
                // 결과 행(FK)이 먼저 — 케이스 4 가 심은 것만 걸린다(다른 케이스의 excn 에는 결과 행이 없다)
                jdbcTemplate.update("DELETE FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?", excnId);
                jdbcTemplate.update("DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
            }
            createdExcnIds.clear();
        } finally {
            // 🔴 픽스처 정리가 실패해도 mock-user·로그인 이력은 되돌린다
            auth.cleanUp();
        }
    }

    /** 실행 이력 1행. PK 는 identity 채번값을 {@code RETURNING} 으로 받아 추적한다(하드코딩 금지). */
    private long insertExcn(String anlsTypeCd) {
        Long excnId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_anls_excn"
                        + " (anls_type_cd, excutr_id, excn_stts_cd, excn_dt)"
                        + " VALUES (?, ?, 'DONE', ?)"
                        + " RETURNING excn_id",
                Long.class, anlsTypeCd, IntegrationLoginSupport.MOCK_ID, LocalDateTime.now());
        return excnId;
    }

    // ================================================================= 케이스 1~3

    @Test
    @DisplayName("1. anlsTypeCd 미전송 — 200 · 분석 화면용 유형만 온다(종전 동작 불변)")
    void case01_withoutTypeFilterReturnsAllAnalysisTypes() {
        List<AnalysisVO> rows = analyses("");

        // 🔴 @BeforeEach 가 HOTSPOT 1행을 심었으므로 최소 1건은 나와야 한다.
        //    비어 있다면 필터가 분석 유형까지 걸러 낸 것이다(시드 유무와 무관한 회귀다).
        assertThat(rows)
                .as("픽스처로 심은 HOTSPOT 실행이 조회되지 않았다 — 유형 필터가 과도하게 좁혔다")
                .isNotEmpty();
        // 필터가 없어도 보고서 전용 유형(CVLCPT·DASHBOARD)은 섞이지 않는다
        assertThat(rows).extracting(AnalysisVO::getAnlsTypeCd)
                .doesNotContain("CVLCPT", "DASHBOARD");
    }

    @Test
    @DisplayName("2. anlsTypeCd=POLICY_SIM — 200 · 모든 행이 그 유형이다(0건이면 통과)")
    void case02_typeFilterNarrowsToSingleType() {
        List<AnalysisVO> all = analyses("");
        List<AnalysisVO> filtered = analyses("&anlsTypeCd=" + TYPE_POLICY_SIM);

        // 🔴 건수가 아니라 "모든 행이 그 유형인가" — 시드가 바뀌어도 성립하는 것만 본다(0건도 정상)
        assertThat(filtered).extracting(AnalysisVO::getAnlsTypeCd)
                .allMatch(TYPE_POLICY_SIM::equals);
        // 좁히기만 하므로 무필터보다 많아질 수 없다
        assertThat(filtered.size()).isLessThanOrEqualTo(all.size());
    }

    @Test
    @DisplayName("3. 🔴 anlsTypeCd= (빈 문자열)은 미전송과 같다 — `= ''` 로 걸려 0건이 되면 안 된다")
    void case03_blankTypeFilterBehavesAsUnfiltered() {
        List<AnalysisVO> unfiltered = analyses("");
        List<AnalysisVO> blank = analyses("&anlsTypeCd=");

        assertThat(blank).hasSameSizeAs(unfiltered);
        // 같은 정렬(excn_dt DESC)의 같은 결과여야 한다 — 건수만 보면 우연히 맞을 수 있다
        assertThat(blank).extracting(AnalysisVO::getExcnId)
                .isEqualTo(unfiltered.stream().map(AnalysisVO::getExcnId).toList());
    }

    @Test
    @DisplayName("4. 정책 시뮬레이션 제목 재료 — 결과 행이 2개여도 실행당 1행 · 숫자 아닌 값도 문자열로 통과 · 다른 유형은 null")
    void case04_policySimTitleFieldsAreSafeAndUnique() {
        // 🔴 실데이터 값을 단언하지 않는다 — 이 케이스가 심은 행만 본다.
        //    addPkltSlot 을 일부러 숫자가 아닌 값으로 넣어 SQL 캐스팅이 없음을 지킨다.
        Long excnId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_anls_excn"
                        + " (anls_type_cd, excutr_id, excn_stts_cd, excn_dt, anls_param_json)"
                        + " VALUES ('POLICY_SIM', ?, 'DONE', ?, ?::jsonb)"
                        + " RETURNING excn_id",
                Long.class, IntegrationLoginSupport.MOCK_ID, LocalDateTime.now(),
                "{\"plcyType\":\"EXPAND\",\"expandMethod\":\"VACLOT\",\"addPkltSlot\":\"not-a-number\"}");
        createdExcnIds.add(excnId);
        // excn_id 에 UNIQUE 가 없다 — 중복 결과 행이 생겨도 목록 행이 늘면 안 된다(LATERAL LIMIT 1)
        List<Long> rsltIds = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            rsltIds.add(jdbcTemplate.queryForObject(
                    "INSERT INTO parking.tb_plcy_smlt_rslt"
                            + " (excn_id, plcy_type_cd, predc_mm, cst_lvl_cd, rcmdtn_yn, anls_rng_cn)"
                            + " VALUES (?, 'EXPAND', 6, 'LOW', false, '테스트 지역')"
                            + " RETURNING rslt_id",
                    Long.class, excnId));
        }

        List<AnalysisVO> rows = analyses("&anlsTypeCd=" + TYPE_POLICY_SIM);
        List<AnalysisVO> mine = rows.stream().filter(r -> excnId.equals(r.getExcnId())).toList();

        assertThat(mine).as("결과 행 2개가 실행 행을 복제했다").hasSize(1);
        AnalysisVO row = mine.get(0);
        assertThat(row.getAnlsRngCn()).isEqualTo("테스트 지역");
        // 카드 행 → 시나리오 선택 키 — LATERAL 이 고른 첫 결과 행(rslt_id 최소)
        assertThat(row.getRsltId()).isEqualTo(rsltIds.get(0));
        assertThat(row.getPlcyType()).isEqualTo("EXPAND");
        assertThat(row.getExpandMethod()).isEqualTo("VACLOT");
        assertThat(row.getAddPkltSlot()).isEqualTo("not-a-number");
        assertThat(row.getRoadMtncType()).isNull();
        assertThat(row.getFeeAplcnMthd()).isNull();

        // 다른 유형(픽스처 HOTSPOT — anls_param_json·결과 행 없음)은 새 칸이 전부 null 이다
        List<AnalysisVO> hotspot = analyses("").stream()
                .filter(r -> createdExcnIds.get(0).equals(r.getExcnId())).toList();
        assertThat(hotspot).hasSize(1);
        assertThat(hotspot.get(0).getAnlsRngCn()).isNull();
        assertThat(hotspot.get(0).getRsltId()).isNull();
        assertThat(hotspot.get(0).getPlcyType()).isNull();
        assertThat(hotspot.get(0).getAddPkltSlot()).isNull();
    }

    // ---------------------------------------------------------------- 헬퍼

    private List<AnalysisVO> analyses(String extraQuery) {
        String url = ANALYSES_URL + "?limit=" + LIMIT + extraQuery;
        ResponseEntity<AnalysisVO[]> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(session), AnalysisVO[].class);

        assertThat(response.getStatusCode().value()).as("GET %s", url).isEqualTo(200);
        assertThat(response.getBody()).as("빈 결과도 배열이어야 한다(null 금지)").isNotNull();
        return Arrays.asList(response.getBody());
    }
}
