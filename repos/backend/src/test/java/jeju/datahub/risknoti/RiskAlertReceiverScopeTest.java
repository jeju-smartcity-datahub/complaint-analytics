package jeju.datahub.risknoti;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import jeju.datahub.auth.service.AuthLookupService;
import jeju.datahub.risknoti.service.RiskAlertDailyVO;
import jeju.datahub.risknoti.service.RiskAlertPageVO;
import jeju.datahub.risknoti.service.RiskAlertSearchVO;
import jeju.datahub.risknoti.service.RiskAlertService;
import jeju.datahub.risknoti.service.RiskAlertSummaryVO;
import jeju.datahub.risknoti.service.RiskAlertVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 위험단계 알림 <b>수신자 범위 필터 · 수신자별 읽음</b> 통합 테스트 (TSK-4202 Step 9 — 요청 ⑤ ·
 * 🔁 TSK-4250 Step 7 개정: 한 사용자 = 활성 수신자 1행(R9) · 매칭 키 {@code user_id}(Q3)).
 *
 * <h2>무엇을 고정하는가</h2>
 * <ul>
 *   <li>🔁 <b>조회 화면은 수신자 무관 · 벨·읽음만 수신자 기준</b>(TSK-4250 R13 — 종전 D10 "조회 3경로가 같은 집합" 을
 *       조회 화면에 한해 되돌렸다). 조회 화면은 수신자·비수신자 모두 <b>전 알림</b>이고(케이스 1·2·8·11·14),
 *       벨·읽음은 {@code TB_NOTI_HSTRY_RCVR} 의 내 행으로 걸러진다.</li>
 *   <li><b>읽음은 사람마다 따로</b>(D9) — A 가 읽어도 B 의 {@code unreadCnt} 는 그대로다.</li>
 *   <li>🔁 <b>한 사람 = 활성 수신자 1행</b>(TSK-4250 R9 — 종전 D11 "한 사람의 행이 여러 개" 는
 *       {@code uq_tb_noti_rcvr_user} 로 성립하지 않는다). D11 전용 케이스(종전 3·9·10·12)는 삭제하거나
 *       "내 행 1개" 기준으로 다시 썼다. 조회 SQL 의 {@code EXISTS}·알림 단위 집계·{@code markRead} 전부 갱신은
 *       <b>방어선으로 남아 있다</b> — 걷어내지 않는다.</li>
 *   <li>🔁 <b>매칭 키는 {@code user_id}</b>(TSK-4250 Q3) — 이메일이 비거나 달라도 {@code user_id} 가 같으면
 *       수신자다(케이스 7).</li>
 *   <li><b>미인증(401)과 비수신자(200 + 0건)는 다른 상태</b> — 뭉개지 않는다.</li>
 * </ul>
 *
 * <h2>🔴 매핑 행을 직접 심는다 — 백엔드는 지역을 보지 않는다</h2>
 * Step 8 의 필터는
 * {@code EXISTS (tb_noti_hstry_rcvr WHERE noti_id = h.noti_id AND rcvr_id IN (:rcvrIds))} <b>하나뿐</b>이고
 * {@code sgg_nm}·{@code emd_cd} 를 보지 않는다 — 지역 3단 규칙은 <b>배치 Tasklet 전용</b>이다(Step 6).
 * 그래서 {@link #setUp()} 은 <b>합성 알림 · 합성 수신자 · 합성 매핑</b> 셋을 모두 심는다.
 * 매핑을 빼면 벨·읽음 케이스가 전부 0건이 되어 "필터가 잘 걸렸다" 로 오독된다.
 * 🔁 R13 — 조회 화면 기대 건수는 <b>DB 에서 그때그때 센다</b>({@link #countAlerts}) — 시드·배치 생성분이
 * 환경마다 달라 고정 숫자로 박지 않는다.
 * 🔴 <b>시드 {@code rcvr_id}(1~12)를 재활용하지 않는다</b> — 백필 매핑 66행이 이미 붙어 있어
 * 기대 건수가 어긋난다. 이 테스트의 수신자는 전부 새로 만든 행이다.
 *
 * <h2>🔴 세션 사용자를 수신자로 만드는 법</h2>
 * 🔁 TSK-4250 Q3 — {@code mock-login} 계정의 <b>{@code user_id}</b>({@code mock-user})로 수신자 행을 심는다.
 * {@code findActiveReceiverIds} 가 {@code TB_USER.user_id = TB_NOTI_RCVR.user_id} 로 잇기 때문이다
 * (종전의 이메일 매칭은 폐기됐다 — {@code eml} 칸은 등록 시점 복사본일 뿐이다).
 * 남의 수신자는 합성 {@code user_id}({@value #OTHER_USER_ID})로 심는다 — {@code TB_USER} 행은 필요 없다
 * ({@code user_id → TB_USER} FK 가 없다).
 * 🔴 세션이 필요 없는 단언은 {@code rcvrIds} 를 인자로 넘겨 <b>서비스를 직접</b> 부른다 — 특히
 * "비수신자"(빈 목록)와 "남"(R3)은 세션을 만들 수 없는 쪽이라 직접 호출이 유일한 경로다.
 *
 * <h2>🔴 정리는 매핑 → 수신자 → 알림 참조 역순</h2>
 * {@code fk_tb_noti_hstry_rcvr_rcvr} 에 {@code ON DELETE CASCADE} 가 <b>없다</b>
 * (알림 쪽 {@code fk_tb_noti_hstry_rcvr_noti} 만 CASCADE). 그리고 세 DELETE 모두
 * <b>이 테스트가 만든 PK 로 스코프</b>한다 — 조건 없는 {@code DELETE FROM parking.tb_noti_hstry_rcvr}
 * 가 백필 66행을 통째로 날린 전례가 있다({@code RiskNotiIntegrationTest} L173).
 *
 * <p>{@code @Transactional} 롤백을 쓰지 않는다 — {@code RANDOM_PORT} 는 요청을 별도 커넥션으로
 * 처리해 롤백이 걸리지 않는다(이 패키지의 다른 두 테스트와 같은 근거).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TSK-4202 Step 9 · TSK-4250 — 알림 수신자 필터(벨·읽음) · 조회 화면 전 알림(R13) · 수신자별 읽음")
class RiskAlertReceiverScopeTest {

    private static final String ALERTS_URL = "/api/risk-alerts";
    private static final String DAILY_URL = ALERTS_URL + "/daily";

    /**
     * {@code MockAuthController} 가 리터럴 상수로 못박은 mock 계정 이메일.
     * 🔁 TSK-4250 Q3 — 매칭 키가 {@code user_id} 로 바뀌어 이 값은 수신자 행 {@code eml} 칸(등록 시점 복사본)을
     * 채우는 용도뿐이다. 매칭에 쓰이지 않는다(케이스 7 이 이메일과 무관함을 고정한다).
     */
    private static final String MOCK_EML = "mock-user@example.com";

    /** 🔴 세션을 만들 수 없는 <b>남</b>. 정리 스코프를 구분하려고 접두를 붙인다. */
    private static final String OTHER_EML = "aaa-alertscope-other@example.com";

    /**
     * 남의 합성 {@code user_id} (TSK-4250 — {@code user_id} NOT NULL · 20자 이내).
     * 🔴 접두 {@code t4250-} 는 고아 정리 스코프다 — 배치 테스트는 {@code t4250b-} 를 써 서로 지우지 않는다.
     */
    private static final String OTHER_USER_ID = "t4250-scope-other";

    /** {@code TB_ADMDST} 실재 코드 — 제주시 일도일동 ({@code RiskAlertReceiverMappingTaskletTest} 와 같은 값) */
    private static final String EMD_A = "50110101";

    /** {@code TB_ADMDST} 실재 코드 — 제주시 일도이동 */
    private static final String EMD_B = "50110102";

    /** {@code TB_ADMDST} 실재 코드 — 서귀포시 서귀동 */
    private static final String EMD_C = "50130101";

    /**
     * 합성 알림의 발생일시 — 어제 09:00.
     *
     * <p>🔴 날짜 리터럴을 쓰지 않는다. 벨({@code /daily})의 기준일이 {@code Asia/Seoul} 전일이라
     * 케이스 3·4·7·8·10 이 그 구간에 있어야 한다.
     * ⚠️ 09:00 은 {@code RiskAlertDailyIntegrationTest} 의 픽스처(00:00)와 겹치지 않게 고른 값이다
     * ({@code uq_risk_hstry_emd_ocrn} 이 {@code (emd_cd, ocrn_dt)} UNIQUE 다).
     */
    private static final LocalDateTime OCRN_DT = LocalDate.now(ZoneId.of("Asia/Seoul"))
            .minusDays(1).atTime(9, 0);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RiskAlertService riskAlertService;

    @Autowired
    private AuthLookupService authLookupService;

    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    /**
     * 내 수신자 행 — 세션 사용자와 같은 {@code user_id} 다(Q3).
     * 🔁 TSK-4250 R9 — 종전 {@code rMine2}(같은 사람의 두 번째 활성 행)는 {@code uq_tb_noti_rcvr_user} 에
     * 걸려 없앴다. 알림 A1·A2 가 모두 이 한 행에 붙는다.
     */
    private long rMine1;

    /** 남의 수신자 행 — 세션을 만들 수 없다 */
    private long rOther;

    /** 알림 A1 — 내 행에 붙는다 */
    private long a1;

    /** 알림 A2 — 내 행에 붙는다 */
    private long a2;

    /** 알림 A3 — 남의 것. 내게는 보이지도, 읽히지도 않아야 한다 */
    private long a3;

    private final List<Long> notiIds = new ArrayList<>();
    private final List<Long> rcvrIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();

        notiIds.clear();
        rcvrIds.clear();
        // 앞선 실행이 비정상 종료해 남긴 합성 행을 먼저 걷는다(매핑 → 수신자 순).
        deleteOrphans();

        // ① 합성 알림 3건 — 같은 구간에 이미 있으면 uq_risk_hstry_emd_ocrn 위반이라 먼저 비운다
        jdbcTemplate.update("DELETE FROM parking.tb_risk_stp_noti_hstry"
                + " WHERE ocrn_dt = ? AND emd_cd IN (?, ?, ?)", OCRN_DT, EMD_A, EMD_B, EMD_C);
        a1 = insertAlert(EMD_A, "CRIT");
        a2 = insertAlert(EMD_B, "WARN");
        a3 = insertAlert(EMD_C, "CAUT");

        // ② 합성 수신자 2행 (내 것 1 + 남 1) — 🔁 TSK-4250 R9: 한 사람 = 활성 1행
        rMine1 = insertReceiver(IntegrationLoginSupport.MOCK_ID, MOCK_EML);
        rOther = insertReceiver(OTHER_USER_ID, OTHER_EML);

        // ③ 🔴 합성 매핑 — 이것이 없으면 전 케이스가 0건이다 (백엔드는 지역을 보지 않는다)
        //    🔴 종전 (a1,rMine1)·(a1,rMine2)를 그대로 "옮기면" (a1,rMine1) 이 중복 PK 다 — 알림당 1행씩 둔다
        insertMapping(a1, rMine1);
        insertMapping(a2, rMine1);
        insertMapping(a3, rOther);

        // 세션 사용자가 실제로 내 수신자 행 1개로 해석되는지 — 하네스 자체의 전제 확인
        assertThat(authLookupService.findActiveReceiverIds(IntegrationLoginSupport.MOCK_ID))
                .as("mock 세션 사용자의 활성 수신자 (user_id 로 이어진다 — Q3)")
                .containsExactly(rMine1);
    }

    /** 🔴 매핑 → 수신자 → 알림 참조 역순. 세 DELETE 모두 이 테스트가 만든 PK 로 스코프한다. */
    @AfterEach
    void tearDown() {
        for (Long notiId : notiIds) {
            jdbcTemplate.update("DELETE FROM parking.tb_noti_hstry_rcvr WHERE noti_id = ?", notiId);
        }
        for (Long rcvrId : rcvrIds) {
            jdbcTemplate.update("DELETE FROM parking.tb_noti_hstry_rcvr WHERE rcvr_id = ?", rcvrId);
            jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr WHERE rcvr_id = ?", rcvrId);
        }
        for (Long notiId : notiIds) {
            jdbcTemplate.update("DELETE FROM parking.tb_risk_stp_noti_hstry WHERE noti_id = ?", notiId);
        }
        notiIds.clear();
        rcvrIds.clear();
        deleteOrphans();
        auth.cleanUp();
    }

    // ───────────────────────────── 1 · 2. 수신자 / 비수신자

    /**
     * 🔁 <b>TSK-4250 R13 — 단언이 뒤집혔다.</b> 종전에는 "내게 매핑된 A1·A2 만, A3(남의 것)은 안 보인다" 였다.
     * 이제 조회 화면은 수신자 무관이라 <b>A3 도 보이고, 건수는 그날 알림 전체</b>다.
     */
    @Test
    @DisplayName("1. 🔁 R13 — 수신자라도 조회 화면은 전 알림이다 (남의 A3 포함 · 건수 = 그날 전체)")
    void inquiryShowsAllAlertsRegardlessOfMapping() {
        RiskAlertPageVO page = alertsOnFixtureDay();

        assertThat(ids(page))
                .as("🔴 A3(남의 것)도 보인다 — 조회 화면은 매핑을 보지 않는다")
                .contains(a1, a2, a3);
        assertThat(page.getTotalElements()).as("그날 알림 전체").isEqualTo(countAlertsOnFixtureDay());
        assertThat(page.getSummary().getTotalCnt()).as("요약도 같은 집합을 센다").isEqualTo(page.getTotalElements());
    }

    /**
     * 🔁 <b>TSK-4250 R13 — 조회 화면 단언이 뒤집혔다.</b> 종전에는 "비수신자는 조회 화면도 0건" 이었다.
     * 이제 비수신자 세션도 조회 화면은 전 알림이고(200), <b>벨만</b> 0건이다.
     */
    @Test
    @DisplayName("2. 🔁 R13 — 비수신자 세션: 조회 화면은 전 알림(200) · 벨은 0건이고 예외가 아니다 (500 도 401 도 아니다)")
    void nonReceiverSeesAllInquiryButEmptyBell() {
        // 세션 사용자를 비수신자로 만든다 — 내 행을 소프트 삭제하면 활성 수신자 해석이 빈 목록이다
        jdbcTemplate.update("UPDATE parking.tb_noti_rcvr SET del_yn = 'Y' WHERE rcvr_id = ?", rMine1);
        assertThat(authLookupService.findActiveReceiverIds(IntegrationLoginSupport.MOCK_ID)).isEmpty();

        JsonNode body = getAlertsWithSession(fixtureDayQuery());
        assertThat(body.get("totalElements").asLong())
                .as("🔴 수신자가 아니어도 조회 화면은 그날 알림 전체다")
                .isEqualTo(countAlertsOnFixtureDay());
        assertThat(notiIds(body)).contains(a1, a2, a3);

        // 벨은 0건 — 서비스가 통째로 조기 반환한다
        assertThat(getDailyWithSession().getTotalCnt()).as("HTTP 벨도 0건(200)").isZero();
        RiskAlertDailyVO daily = riskAlertService.selectDailyAlerts(OCRN_DT.toLocalDate(), List.of());
        assertThat(daily.getTotalCnt()).isZero();
        assertThat(daily.getUnreadCnt()).isZero();
        assertThat(daily.getItems()).isNotNull().isEmpty();
    }

    // ───────────────────────────── 3. 내 행 1개 — 서비스 직접 호출과 HTTP 세션이 같은 집합

    /**
     * 🔁 TSK-4250 R9 — 종전 "수신자 행 2개(D11) → 중복 없음" 은 같은 사람의 두 번째 활성 행이
     * {@code uq_tb_noti_rcvr_user} 에 걸려 픽스처로 만들 수 없다. "내 행 1개" 기준으로 다시 썼다 —
     * 세션 사용자의 수신자 해석({@code user_id})이 그 1행이고, 서비스 직접 호출과 HTTP 가 같은 집합이다.
     */
    @Test
    @DisplayName("3. 🔁 내 행 1개가 두 알림에 붙으면 벨은 2건 — 서비스 직접 호출과 HTTP 세션(user_id 해석)이 같다")
    void singleReceiverRowSeesBothAlertsOverHttp() {
        RiskAlertDailyVO direct = daily(List.of(rMine1));
        assertThat(direct.getItems()).extracting(RiskAlertVO::getNotiId)
                .containsExactlyInAnyOrder(a1, a2).doesNotHaveDuplicates();

        // 🔴 HTTP 로도 같다 — 세션 사용자의 수신자 해석이 같은 한 행이다(Q3)
        RiskAlertDailyVO daily = getDailyWithSession();
        assertThat(daily.getItems()).extracting(RiskAlertVO::getNotiId)
                .containsExactlyInAnyOrder(a1, a2).doesNotHaveDuplicates();
        assertThat(daily.getTotalCnt()).isEqualTo(2);
    }

    // ───────────────────────────── 4 · 5. 읽음 분리 · 멱등

    @Test
    @DisplayName("4. 🔴 A 가 읽어도 B 의 unreadCnt 는 그대로다 (D9 — 읽음이 사람마다 따로다)")
    void readByOneDoesNotAffectAnother() {
        RiskAlertDailyVO beforeOther = daily(List.of(rOther));
        assertThat(beforeOther.getUnreadCnt()).as("남(B)에게는 A3 한 건이 미읽음이다").isEqualTo(1);

        riskAlertService.markRead(a1, List.of(rMine1));

        assertThat(daily(List.of(rOther)).getUnreadCnt())
                .as("🔴 B 의 배지는 줄지 않는다 — 이력 한 칸에 읽음을 쓰면 여기서 깨진다")
                .isEqualTo(beforeOther.getUnreadCnt());
        assertThat(daily(List.of(rMine1)).getUnreadCnt())
                .as("반대로 내 배지는 2 → 1 로 준다").isEqualTo(1);
    }

    @Test
    @DisplayName("5. 멱등 — PATCH 2회 모두 204 이고 prsl_dt 가 바뀌지 않는다 (HTTP 끝까지)")
    void markReadIsIdempotentOverHttp() {
        assertThat(patchRead(a1).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        LocalDateTime first = prslDt(a1, rMine1);
        assertThat(first).as("첫 호출이 읽은일시를 남긴다").isNotNull();

        assertThat(patchRead(a1).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(prslDt(a1, rMine1))
                .as("🔴 '처음 읽은 시각' 이 매 클릭마다 밀리면 이력의 뜻이 달라진다")
                .isEqualTo(first);
    }

    // ───────────────────────────── 6 · 7. 404 · 이메일과 무관한 매칭(Q3)

    @Test
    @DisplayName("6. 매핑 없는 notiId 읽음 — 404 (남의 알림은 존재 여부도 알려주지 않는다)")
    void markReadOnUnmappedAlertIs404() {
        assertThat(patchRead(a1).getStatusCode()).as("대조군 — 내 알림은 열린다").isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(patchRead(a3).getStatusCode()).as("남(rOther)의 알림").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patchRead(999_999_999L).getStatusCode()).as("아예 없는 알림").isEqualTo(HttpStatus.NOT_FOUND);

        // 실패한 호출이 남의 행을 건드리지 않았다
        assertThat(prslYn(a3, rOther)).isEqualTo("N");
    }

    /**
     * 🔁 <b>TSK-4250 Q3 회귀 — 단언이 뒤집혔다.</b> 종전에는 "이메일이 매칭의 유일한 키라 {@code user_eml_addr}
     * 가 NULL 이면 0건(알려진 제약)" 이었다. 이제 매칭 키는 {@code user_id} 라 <b>사용자 이메일이 비어도,
     * 수신자 행의 {@code eml} 이 사용자 이메일과 달라도</b> 수신자다(벨 매칭 케이스와 합쳤다).
     */
    @Test
    @DisplayName("7. 🔁 Q3 — user_eml_addr 가 NULL 이고 수신자 eml 이 달라도 user_id 가 같으면 수신자다 (벨에 뜬다)")
    void receiverMatchesByUserIdNotEmail() {
        // ① 수신자 행의 eml 을 사용자 이메일과 **다르게** 바꾼다 (등록 시점 복사본이 낡은 상황)
        jdbcTemplate.update("UPDATE parking.tb_noti_rcvr SET eml = ? WHERE rcvr_id = ?",
                "t4250-stale-copy@example.com", rMine1);
        // ② 사용자 이메일 자체를 비운다
        jdbcTemplate.update("UPDATE parking.tb_user SET user_eml_addr = NULL WHERE user_id = ?",
                IntegrationLoginSupport.MOCK_ID);

        // 🔴 이메일로 잇는 옛 SQL 이면 여기서 빈 목록이다
        assertThat(authLookupService.findActiveReceiverIds(IntegrationLoginSupport.MOCK_ID))
                .as("user_id 매칭(Q3) — 이메일과 무관하다")
                .containsExactly(rMine1);

        RiskAlertDailyVO daily = getDailyWithSession();
        assertThat(daily.getTotalCnt()).as("🔔 벨에 내 알림 2건이 뜬다").isEqualTo(2);
        assertThat(daily.getItems()).extracting(RiskAlertVO::getNotiId).containsExactlyInAnyOrder(a1, a2);
    }

    // ───────────────────────────── 8. 조회 화면 ↔ 벨 — 🔁 R13 으로 기준이 갈렸다

    /**
     * 🔁 <b>TSK-4250 R13 — 종전 D10("조회 화면과 벨이 같은 집합") 이 뒤집혔다.</b>
     * 같은 하루라도 조회 화면은 <b>전 알림</b>, 벨은 <b>내 매핑만</b>이다 — 조회 ⊇ 벨.
     */
    @Test
    @DisplayName("8. 🔁 R13 — 같은 하루라도 조회 화면은 전 알림(A3 포함), 벨은 내 매핑(A1·A2)만이다")
    void inquiryIsWiderThanBell() {
        List<Long> inquiry = ids(alertsOnFixtureDay());
        List<Long> bell = daily(List.of(rMine1)).getItems().stream().map(RiskAlertVO::getNotiId).toList();

        assertThat(bell).as("벨은 여전히 내 매핑만").containsExactlyInAnyOrder(a1, a2);
        assertThat(inquiry).as("🔴 조회 화면은 벨을 포함하고 남의 A3 까지 보인다")
                .containsAll(bell)
                .contains(a3);
    }

    // ───────────────────────────── 10. 벨 읽음은 매핑 행 · 조회 화면은 이력값
    // 🔁 TSK-4250 R9 — 종전 케이스 9("내 행 2개가 같은 알림에 붙어도 unreadCnt 1")는 같은 사람의 두 번째
    //    활성 행을 만들 수 없어 삭제했다. 알림 단위 집계 SQL 자체는 방어선으로 남아 있다(걷어내지 않는다).

    /**
     * 🔁 TSK-4250 R9 — 종전 "내 행 하나만 'Y' 면 벨은 'N'(min 집계, D11)" 은 내 행이 1개라 성립하지 않는다.
     * "내 행 1개" 기준으로 다시 썼다 — 벨의 {@code prslYn} 은 <b>내 매핑 행</b> 값이고, 조회 화면은 이력값 그대로다.
     */
    @Test
    @DisplayName("10. 🔁 벨의 prslYn 은 내 매핑 행 값이다 — 조회 화면의 prslYn 은 이력값 그대로")
    void bellReadFlagComesFromMyMappingRow() {
        jdbcTemplate.update("UPDATE parking.tb_noti_hstry_rcvr SET prsl_yn = 'Y', prsl_dt = now()"
                + " WHERE noti_id = ? AND rcvr_id = ?", a1, rMine1);

        RiskAlertDailyVO bell = daily(List.of(rMine1));
        RiskAlertVO bellA1 = bell.getItems().stream()
                .filter(r -> r.getNotiId() == a1).findFirst().orElseThrow();
        RiskAlertVO bellA2 = bell.getItems().stream()
                .filter(r -> r.getNotiId() == a2).findFirst().orElseThrow();

        assertThat(bellA1.getPrslYn()).as("내 매핑 행이 'Y' 면 벨에서도 읽음이다").isEqualTo("Y");
        assertThat(bellA2.getPrslYn()).isEqualTo("N");
        assertThat(bell.getUnreadCnt()).isEqualTo(1);

        // ⚠️ 조회 화면(selectAlertList)은 손대지 않았다 — 그 prslYn 은 이력 컬럼 그대로다.
        RiskAlertVO inquiryRow = alertsOnFixtureDay().getContent().stream()
                .filter(r -> r.getNotiId() == a1).findFirst().orElseThrow();
        assertThat(inquiryRow.getPrslYn()).isEqualTo(historyPrslYn(a1));
    }

    // ───────────────────────────── 11. 요약 — 수신 여부와 무관

    /**
     * 🔁 <b>TSK-4250 R13 — 단언이 뒤집혔다.</b> 종전에는 "비수신자 요약은 단계별 알림이 전부 0" 이었다.
     * 이제 요약은 수신 여부와 무관해 <b>단계별 건수가 DB 전체</b>와 같다. 민원 합계(전역)는 원래부터 그랬다.
     */
    @Test
    @DisplayName("11. 🔁 R13 — 요약은 수신 여부와 무관: 단계별 건수 = DB 전체 · cvlcptSumCnt = 전역 민원 합계")
    void summaryIgnoresReceiverScope() {
        RiskAlertSummaryVO summary = alerts().getSummary();

        assertThat(summary).as("집계 함수라 항상 1행이 온다").isNotNull();
        assertThat(summary.getTotalCnt()).isEqualTo(countAlerts(null));
        assertThat(summary.getCritCnt()).isEqualTo(countAlerts("h.risk_lvl_cd = 'CRIT'"));
        assertThat(summary.getWarnCnt()).isEqualTo(countAlerts("h.risk_lvl_cd = 'WARN'"));
        assertThat(summary.getCautCnt()).isEqualTo(countAlerts("h.risk_lvl_cd = 'CAUT'"));

        // 🔴 민원 합계는 "선택 기간 제주 전역" 이라 수신 여부와 무관하다
        long globalCvlcpt = countCvlcpt();
        assertThat(globalCvlcpt).as("원천이 비면 아래 단언이 '0 == 0' 으로 조용히 통과한다").isPositive();
        assertThat(summary.getCvlcptSumCnt())
                .as("🔴 여기에 수신자 필터를 걸면 카드 뜻이 바뀐다 (TSK-4069 Step 9)")
                .isEqualTo(globalCvlcpt);
    }

    // ───────────────────────────── 12. markRead (서비스 직접) — 내 행만, 2회차 불변

    /**
     * 🔁 TSK-4250 R9 — 종전 "markRead 1회 → 그 알림의 내 매핑 행이 <b>전부</b> 'Y'(D11)" 는 내 행이 1개라
     * "전부" 를 시험할 수 없다. "내 행 1개" 기준으로 다시 썼다. {@code markRead} 의 전부 갱신 SQL 은 방어선으로 남는다.
     */
    @Test
    @DisplayName("12. 🔁 markRead 1회 → 내 매핑 행 'Y' · 2회차는 prsl_dt 불변 · 남의 행은 그대로")
    void markReadUpdatesMyRowOnly() {
        riskAlertService.markRead(a1, List.of(rMine1));

        assertThat(prslYn(a1, rMine1)).isEqualTo("Y");
        assertThat(countMappingRows(a1, "prsl_yn = 'N'")).isZero();

        LocalDateTime first = prslDt(a1, rMine1);
        assertThat(first).isNotNull();

        riskAlertService.markRead(a1, List.of(rMine1));

        assertThat(prslDt(a1, rMine1)).isEqualTo(first);
        // 같은 사람의 다른 알림·남의 행은 건드리지 않는다
        assertThat(prslYn(a2, rMine1)).isEqualTo("N");
        assertThat(prslYn(a3, rOther)).isEqualTo("N");
    }

    // ───────────────────────────── 13. 익명 HTTP

    @Test
    @DisplayName("13. 🔴 익명 HTTP GET /api/risk-alerts/daily — 401 ('수신자가 아니다'(200 + 0건)와 다른 상태다)")
    void anonymousDailyIs401() {
        assertThat(restTemplate.getForEntity(DAILY_URL, String.class).getStatusCode())
                .as("세션이 없으면 수신자 해석 이전에 끊긴다")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity(ALERTS_URL + "?size=100", String.class).getStatusCode())
                .as("조회 화면도 같다").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange(ALERTS_URL + "/" + a1 + "/read", HttpMethod.PATCH, null,
                String.class).getStatusCode())
                .as("읽음도 같다 — 404(매핑 없음)가 아니라 401 이다").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ───────────────────────────── 14. 필터가 하나도 없는 조회

    @Test
    @DisplayName("14. 🔴 필터 전무(단계·지역·기간 null) — 500 이 아니라 정상 조회 · 🔁 R13 건수 = DB 전체 (빈 <where> 경로)")
    void emptyFilterStillRendersValidSql() {
        // 🔴 alertWhere 의 <where> 가 아무것도 렌더하지 않는 유일한 경로다.
        //    🔁 R13 으로 receiverExists 는 빠졌지만, 되살릴 때 <where> **밖**에 붙이면 100% 500 이라 경로를 계속 지킨다.
        RiskAlertPageVO page = alerts();

        long all = countAlerts(null);
        assertThat(page.getTotalElements()).as("🔁 R13 — 필터 없으면 DB 의 알림 전체").isEqualTo(all);
        assertThat(page.getSummary().getTotalCnt()).isEqualTo(all);

        // 같은 경로를 HTTP(화면 첫 진입 = 파라미터 없음)로도 태운다 — 컨트롤러 정규화가 전부 null 로 접는다
        assertThat(restTemplate.exchange(ALERTS_URL + "?size=100", HttpMethod.GET,
                new HttpEntity<>(session), String.class).getStatusCode())
                .as("필터 없는 첫 진입이 500 이면 화면이 아예 안 뜬다").isEqualTo(HttpStatus.OK);
    }

    // ───────────────────────────── 헬퍼 — 호출

    /**
     * 필터를 하나도 채우지 않은 조회 (케이스 14 의 경로). {@code size} 만 넉넉히 준다.
     * 🔁 R13 — 수신자 인자가 없다(조회 화면은 수신자 무관 · {@code RiskAlertSearchVO.rcvrIds} 는 걷혔다).
     */
    private RiskAlertPageVO alerts() {
        return riskAlertService.selectAlerts(search(), 0);
    }

    /**
     * 픽스처 발생일(어제 · Asia/Seoul) 하루로 좁힌 조회 — A1~A3 이 반드시 한 페이지(100) 안에 들도록.
     * 🔴 날짜 리터럴이 아니라 {@link #OCRN_DT} 에서 파생한다.
     */
    private RiskAlertPageVO alertsOnFixtureDay() {
        RiskAlertSearchVO search = search();
        search.setFromDt(OCRN_DT.toLocalDate().atStartOfDay());
        search.setToDt(OCRN_DT.toLocalDate().atTime(23, 59, 59));
        return riskAlertService.selectAlerts(search, 0);
    }

    private RiskAlertSearchVO search() {
        RiskAlertSearchVO search = new RiskAlertSearchVO();
        search.setSize(100);
        search.setOffset(0);
        return search;
    }

    /** {@link #alertsOnFixtureDay()} 와 같은 하루를 HTTP 쿼리로 */
    private String fixtureDayQuery() {
        String day = OCRN_DT.toLocalDate().toString();
        return "?from=" + day + "&to=" + day + "&size=100";
    }

    /** 세션(mock-user)으로 조회 화면을 부른다. {@code RiskAlertPageVO} 는 역직렬화가 안 돼 트리로 받는다. */
    private JsonNode getAlertsWithSession(String query) {
        var response = restTemplate.exchange(ALERTS_URL + query, HttpMethod.GET, new HttpEntity<>(session),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static List<Long> notiIds(JsonNode page) {
        List<Long> result = new ArrayList<>();
        page.get("content").forEach(row -> result.add(row.get("notiId").asLong()));
        return result;
    }

    private RiskAlertDailyVO daily(List<Long> rcvrIdList) {
        return riskAlertService.selectDailyAlerts(OCRN_DT.toLocalDate(), rcvrIdList);
    }

    /** 세션(mock-user)으로 벨을 부른다 — 수신자 해석까지 컨트롤러가 한다. */
    private RiskAlertDailyVO getDailyWithSession() {
        var response = restTemplate.exchange(DAILY_URL, HttpMethod.GET, new HttpEntity<>(session),
                RiskAlertDailyVO.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getItems()).isNotNull();
        return response.getBody();
    }

    /** 실패가 예상되는 호출도 있으므로 응답 타입은 항상 String 이다(에러 바디 역직렬화 회피). */
    private org.springframework.http.ResponseEntity<String> patchRead(long notiId) {
        return restTemplate.exchange(ALERTS_URL + "/" + notiId + "/read", HttpMethod.PATCH,
                new HttpEntity<>(session), String.class);
    }

    private static List<Long> ids(RiskAlertPageVO page) {
        return page.getContent().stream().map(RiskAlertVO::getNotiId).toList();
    }

    // ───────────────────────────── 헬퍼 — 픽스처

    private long insertAlert(String emdCd, String riskLvlCd) {
        Long notiId = jdbcTemplate.queryForObject("""
                INSERT INTO parking.tb_risk_stp_noti_hstry
                       (risk_lvl_cd, emd_cd, cvlcpt_cnt, incrs_rt, noti_ctt, ocrn_dt, prcs_stts_cd)
                VALUES (?, ?, 30, 25.00, '수신자 범위 테스트 합성 알림', ?, 'OPEN')
                RETURNING noti_id""", Long.class, riskLvlCd, emdCd, OCRN_DT);
        assertThat(notiId).isNotNull();
        notiIds.add(notiId);
        return notiId;
    }

    /**
     * 🔁 TSK-4250 — {@code user_id} 는 NOT NULL 이고 매칭 키다(Q3). 지역 행({@code TB_NOTI_RCVR_RGN})은
     * 넣지 않는다 — 백엔드 필터는 매핑만 보고, DEPRECATED 지역 칸({@code sgg_nm}·{@code emd_cd})도 쓰지 않는다.
     */
    private long insertReceiver(String userId, String eml) {
        Long rcvrId = jdbcTemplate.queryForObject("""
                INSERT INTO parking.tb_noti_rcvr
                       (user_id, rcvr_nm, eml, use_yn, del_yn, ntfctn_ch_cd)
                VALUES (?, '범위테스트수신자', ?, 'Y', 'N', 'EMAIL')
                RETURNING rcvr_id""", Long.class, userId, eml);
        assertThat(rcvrId).isNotNull();
        rcvrIds.add(rcvrId);
        return rcvrId;
    }

    /** 🔴 백엔드는 지역을 보지 않는다 — 매핑을 심지 않으면 전 케이스가 0건이다. */
    private void insertMapping(long notiId, long rcvrId) {
        jdbcTemplate.update("INSERT INTO parking.tb_noti_hstry_rcvr (noti_id, rcvr_id) VALUES (?, ?)",
                notiId, rcvrId);
    }

    /**
     * 비정상 종료가 남긴 합성 수신자를 걷는다 — 매핑 먼저(CASCADE 없음), 그다음 수신자.
     *
     * <p>🔁 TSK-4250 — 스코프는 <b>{@code user_id}</b>({@code mock-user} · 합성 {@code t4250-%})다(종전 이메일 스코프).
     * 고아 정리라 직전 실행의 {@code rcvr_id} 를 모른다 — 남은 행이 {@code uq_tb_noti_rcvr_user} 를 점유하면
     * 다음 실행의 INSERT 가 깨진다. 백필 매핑은 이 두 사용자 밖이라 건드리지 않는다.
     * 단계·지역 행은 수신자 FK CASCADE 로 함께 지워진다.
     */
    private void deleteOrphans() {
        jdbcTemplate.update("""
                DELETE FROM parking.tb_noti_hstry_rcvr m
                 WHERE EXISTS (SELECT 1 FROM parking.tb_noti_rcvr r
                                WHERE r.rcvr_id = m.rcvr_id
                                  AND (r.user_id = ? OR r.user_id LIKE 't4250-%'))""",
                IntegrationLoginSupport.MOCK_ID);
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr WHERE user_id = ? OR user_id LIKE 't4250-%'",
                IntegrationLoginSupport.MOCK_ID);
    }

    // ───────────────────────────── 헬퍼 — 독립 SQL

    private String prslYn(long notiId, long rcvrId) {
        String value = jdbcTemplate.queryForObject(
                "SELECT prsl_yn FROM parking.tb_noti_hstry_rcvr WHERE noti_id = ? AND rcvr_id = ?",
                String.class, notiId, rcvrId);
        return value == null ? null : value.trim();
    }

    private LocalDateTime prslDt(long notiId, long rcvrId) {
        return jdbcTemplate.queryForObject(
                "SELECT prsl_dt FROM parking.tb_noti_hstry_rcvr WHERE noti_id = ? AND rcvr_id = ?",
                LocalDateTime.class, notiId, rcvrId);
    }

    /** 이력 컬럼의 읽음값 — 케이스 10 이 "조회 화면은 이력값 그대로" 를 보일 때 쓴다. */
    private String historyPrslYn(long notiId) {
        String value = jdbcTemplate.queryForObject(
                "SELECT prsl_yn FROM parking.tb_risk_stp_noti_hstry WHERE noti_id = ?",
                String.class, notiId);
        return value == null ? null : value.trim();
    }

    private int countMappingRows(long notiId, String where) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_noti_hstry_rcvr WHERE noti_id = ? AND " + where,
                Integer.class, notiId);
        return value == null ? 0 : value;
    }

    /**
     * 🔁 R13 — 조회 화면의 기대 건수. 매퍼 {@code alertFrom} 과 같은 FROM(이력 ⨝ 행정구역)이고
     * 수신자 조건이 없다. {@code where} 는 이 클래스가 넘기는 리터럴뿐이다(외부 입력 아님).
     */
    private long countAlerts(String where) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_risk_stp_noti_hstry h"
                        + " JOIN parking.tb_admdst a ON a.emd_cd = h.emd_cd"
                        + (where == null ? "" : " WHERE " + where), Long.class);
        return value == null ? 0L : value;
    }

    /** 픽스처 발생일 하루의 알림 전체 건수 — {@link #alertsOnFixtureDay()} 의 기대값 */
    private long countAlertsOnFixtureDay() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_risk_stp_noti_hstry h"
                        + " JOIN parking.tb_admdst a ON a.emd_cd = h.emd_cd"
                        + " WHERE h.ocrn_dt >= ? AND h.ocrn_dt <= ?", Long.class,
                OCRN_DT.toLocalDate().atStartOfDay(), OCRN_DT.toLocalDate().atTime(23, 59, 59));
        return value == null ? 0L : value;
    }

    /** 요약 카드의 민원 합계와 같은 조건 — 필터가 없으므로 {@code emd_cd IS NOT NULL} 하나다. */
    private long countCvlcpt() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt WHERE emd_cd IS NOT NULL", Long.class);
        return value == null ? 0L : value;
    }
}
