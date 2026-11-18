package jeju.datahub.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
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

import jeju.datahub.dashboard.service.AnalysisVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 대시보드 <b>"최근 분석 실행" 카드</b>의 실행자 범위 통합 테스트.
 * ({@code GET /api/dashboard/analyses})
 *
 * <p>카드는 <b>로그인 사용자가 실행한 것만</b>({@code excutr_id = 나}) 낸다 — 유형과 무관하다.
 * 정책 시뮬레이션 화면의 저장 목록과 같은 집합이어야 하므로, {@code POLICY_SIM} 은 여전히
 * <b>결과 행이 있는 실행만</b> 나온다(보고서 내보내기용 실행 행이 카드를 채우면 안 된다).
 *
 * <h2>🔴 두 사용자 · 기대 건수를 박지 않는다</h2>
 * u1 은 {@code mock-login} 세션({@link IntegrationLoginSupport#MOCK_ID}), u2 는 세션을 만들 수 없는 남이라
 * 행만 심는다({@code excutr_id} 는 FK 없는 {@code VARCHAR(20)} 이라 {@code TB_USER} 행이 필요 없다).
 * 단언은 <b>이 테스트가 심은 {@code excnId}</b> 와 <b>독립 SQL</b> 로만 한다 — 조회 날짜도 박지 않는다.
 *
 * <p>🔴 {@code RANDOM_PORT} 라 롤백이 안 먹는다 — {@link #cleanUp()} 이 심은 id 만 지운다(결과 행 먼저).
 * 선례: {@link DashboardReportOwnerScopeTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("대시보드 최근 분석 실행 카드의 실행자 범위")
class DashboardAnalysisOwnerScopeTest {

    private static final String CARD_URL = "/api/dashboard/analyses";

    private static final String POLICY_SIM = "POLICY_SIM";

    /** 남의 {@code user_id} — {@code varchar(20)} 안에 들어간다. */
    private static final String U2 = "owner-u2";

    /** 실행자를 세션에서 정하기 전의 과거 행 실행자. */
    private static final String LEGACY = "system";

    /**
     * 🔴 카드 기본값 5 로 부르면 심은 행이 상위 5건에 가려 아무것도 검증하지 못한다 — 넉넉히 잡는다.
     * 심은 행은 {@code excn_dt = now()} 라 내림차순 상위에 온다.
     */
    private static final int LIMIT = 100;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private IntegrationLoginSupport auth;
    private HttpHeaders session;
    private String u1;

    private final List<Long> createdExcnIds = new ArrayList<>();

    private long u1Sim1;
    private long u1Sim2;
    private long u2Sim;
    private long legacySim;
    /** 결과 행 없는 u1 POLICY_SIM — 보고서 내보내기용 실행 */
    private long u1SimNoRslt;

    @BeforeEach
    void setUp() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();   // 🔴 빠지면 cleanUp() 이 로그인 이력 전량을 지운다
        session = auth.mockSession();
        u1 = IntegrationLoginSupport.MOCK_ID;

        u1Sim1 = insertPolicySim(u1, true);
        u1Sim2 = insertPolicySim(u1, true);
        u2Sim = insertPolicySim(U2, true);
        legacySim = insertPolicySim(LEGACY, true);
        u1SimNoRslt = insertPolicySim(u1, false);
    }

    @AfterEach
    void cleanUp() {
        List<RuntimeException> failures = new ArrayList<>();
        // FK(tb_plcy_smlt_rslt.excn_id → tb_anls_excn.excn_id) 때문에 결과 행을 먼저 지운다
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?", excnId);
        }
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdExcnIds.clear();
        auth.cleanUp();
        assertThat(failures).as("테스트가 심은 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    // ───────────────────────────── 1. 유형 지정 — 내 것만

    @Test
    @DisplayName("1. anlsTypeCd=POLICY_SIM — 내가 실행한 2건은 보이고 남(u2)의 것은 없다 · 전 행 실행자 = 나")
    void policySimShowsOnlyMine() {
        Set<Long> ids = cardIds("&anlsTypeCd=" + POLICY_SIM);

        assertThat(ids).as("내가 실행한 POLICY_SIM 2건은 카드에 뜬다").contains(u1Sim1, u1Sim2);
        assertThat(ids).as("🔴 남이 실행한 것이 내 카드에 뜨면 안 된다").doesNotContain(u2Sim);
        assertThat(executorsOf(ids)).as("POLICY_SIM 카드의 실행자 집합").containsOnly(u1);
    }

    // ───────────────────────────── 2. 유형 미지정 — 내 것만

    @Test
    @DisplayName("2. anlsTypeCd 미지정 — 유형과 무관하게 내 것만")
    void withoutTypeShowsOnlyMine() {
        Set<Long> ids = cardIds("");

        assertThat(ids).as("내 POLICY_SIM 은 유형 미지정에서도 보인다").contains(u1Sim1, u1Sim2);
        assertThat(ids).as("남의 것·과거 system 행은 없다").doesNotContain(u2Sim, legacySim);
        assertThat(executorsOf(ids)).as("유형 미지정 카드의 실행자 집합 — 다른 유형도 전부 나").containsOnly(u1);
    }

    // ───────────────────────────── 3. 과거 system 행

    @Test
    @DisplayName("3. excutr_id='system' 과거 행 — 누구에게도 보이지 않는다")
    void legacySystemRowIsInvisible() {
        assertThat(cardIds("&anlsTypeCd=" + POLICY_SIM)).as("유형 지정").doesNotContain(legacySim);
        assertThat(cardIds("")).as("유형 미지정").doesNotContain(legacySim);
    }

    // ───────────────────────────── 4. 결과 행 없는 내 POLICY_SIM (회귀)

    @Test
    @DisplayName("4. 결과 행 없는 내 POLICY_SIM(보고서용 실행) — 내 것이어도 보이지 않는다 (회귀 방지)")
    void policySimWithoutResultIsHidden() {
        Set<Long> typed = cardIds("&anlsTypeCd=" + POLICY_SIM);
        assertThat(typed).as("대조군 — 같은 사용자의 결과 행 있는 실행은 보인다").contains(u1Sim1);
        assertThat(typed).as("🔴 실행자 조건을 붙이며 조건을 잃으면 보고서 내보내기가 카드를 채운다")
                .doesNotContain(u1SimNoRslt);
        assertThat(cardIds("")).as("유형 미지정").doesNotContain(u1SimNoRslt);
    }

    // ───────────────────────────── 5. 미인증

    @Test
    @DisplayName("5. 🔴 익명 카드 호출은 401 이다 — 폴백으로 전체 공개하지 않는다")
    void anonymousCardIs401() {
        assertThat(restTemplate.getForEntity(CARD_URL + "?limit=" + LIMIT, String.class).getStatusCode())
                .as("유형 미지정").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity(CARD_URL + "?limit=" + LIMIT + "&anlsTypeCd=" + POLICY_SIM,
                String.class).getStatusCode()).as("anlsTypeCd=POLICY_SIM").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ───────────────────────────── 헬퍼

    /** 세션(u1)으로 카드를 부르고 {@code excnId} 집합을 돌려준다. */
    private Set<Long> cardIds(String extraQuery) {
        String url = CARD_URL + "?limit=" + LIMIT + extraQuery;
        ResponseEntity<AnalysisVO[]> res = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(session), AnalysisVO[].class);
        assertThat(res.getStatusCode().value()).as("GET %s", url).isEqualTo(200);
        assertThat(res.getBody()).as("빈 결과도 배열이어야 한다(null 금지)").isNotNull();
        return new LinkedHashSet<>(Arrays.stream(res.getBody()).map(AnalysisVO::getExcnId).toList());
    }

    /** 🔴 응답 VO 에 실행자 칸이 없으므로 독립 SQL 로 전 행의 실행자를 확인한다. */
    private List<String> executorsOf(Set<Long> excnIds) {
        assertThat(excnIds).as("카드가 비어 있으면 '전부 내 것' 이 아무것도 검증하지 못한다").isNotEmpty();
        return excnIds.stream().map(id -> jdbcTemplate.queryForObject(
                "SELECT excutr_id FROM parking.tb_anls_excn WHERE excn_id = ?", String.class, id)).toList();
    }

    /** POLICY_SIM 실행 1행 (+ 결과 행 1행). 픽스처 모양은 {@link DashboardPanelIntegrationTest} 케이스 4 와 같다. */
    private long insertPolicySim(String excutrId, boolean withResult) {
        Long excnId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_anls_excn"
                        + " (anls_type_cd, excutr_id, excn_stts_cd, excn_dt, anls_param_json)"
                        + " VALUES ('POLICY_SIM', ?, 'DONE', now(), ?::jsonb)"
                        + " RETURNING excn_id",
                Long.class, excutrId, "{\"plcyType\":\"EXPAND\",\"expandMethod\":\"VACLOT\",\"addPkltSlot\":\"10\"}");
        assertThat(excnId).isNotNull();
        createdExcnIds.add(excnId);
        if (withResult) {
            jdbcTemplate.update(
                    "INSERT INTO parking.tb_plcy_smlt_rslt"
                            + " (excn_id, plcy_type_cd, predc_mm, cst_lvl_cd, rcmdtn_yn, anls_rng_cn)"
                            + " VALUES (?, 'EXPAND', 6, 'LOW', false, '실행자 판정용 지역')",
                    excnId);
        }
        return excnId;
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Object arg) {
        try {
            jdbcTemplate.update(sql, arg);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
