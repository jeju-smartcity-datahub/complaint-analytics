package jeju.datahub.risknoti;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.risknoti.service.NotiReceiverCandidateVO;
import jeju.datahub.risknoti.service.NotiReceiverRegionVO;
import jeju.datahub.risknoti.service.NotiReceiverVO;
import jeju.datahub.risknoti.service.RiskAlertDailyVO;
import jeju.datahub.risknoti.service.RiskAlertSummaryVO;
import jeju.datahub.risknoti.service.RiskAlertVO;
import jeju.datahub.risknoti.service.RiskCriteriaVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 위험단계 알림 이력 조회 · 수신자 CRUD · 기준 저장 API 통합 테스트 (TSK-4028 Step 7).
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도 스레드·별도
 * 커넥션으로 처리하므로 테스트 트랜잭션이 서버 쪽 쓰기를 감싸지 못한다(롤백이 걸리지 않는다). 더구나 롤백이
 * 걸리는 {@code MockMvc + @Transactional} 조합을 택하면 <b>케이스 5(트랜잭션 밖 재조회)</b>가 성립하지 않아
 * 정작 잡으려던 MyBatis 1차 캐시 / JPA flush 누락 버그를 못 잡는다. 그래서
 * "실제 커밋 + {@link #restoreSeed()} 수동 복원"을 쓴다.
 * ({@code MenuAuthrtIntegrationTest} · {@code ReportIntegrationTest} 와 같은 전략)
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다 — 모든 프로파일이 PostgreSQL 이고 H2 는 없다.
 * 시드({@code database/migrations/sql/seeding/10-TB_NOTI_ALERT.sql})가 적용된 상태를 전제로 한다
 * (기준 3행 · <b>수신자 0행 · 단계매핑 0행</b> · 알림 이력 18행 · 발송기록 0행).
 * 🔁 <b>2026-10-15 — 시드 수신자 12행이 걷혔다</b>(사용자 결정). 전부 {@code @jeju.go.kr} 더미라
 * {@code TB_USER}({@code @innopam.com})와 <b>이메일 매칭이 0</b> 이어서 아무도 자기 알림을 볼 수 없었다.
 * 그래서 이 테스트는 필요한 수신자를 <b>스스로 심거나 API 로 만든다</b>
 * ({@link #plantSessionReceiver()} · {@link #createReceiver}).
 * 🔁 <b>TSK-4250 — 수신자는 {@code TB_USER} 에서 고른다(R8) · 한 사용자 = 활성 수신자 1행(R9) ·
 * 매칭 키 {@code user_id}(Q3) · 지역은 {@code TB_NOTI_RCVR_RGN} 다중(R1~R3).</b> API 로 만드는 수신자는
 * {@link #plantSyntheticUsers()} 가 {@code @BeforeEach} 에서 심는 <b>합성 {@code TB_USER}</b>({@code t4250-} 접두)를
 * 고른다. 🔴 {@code mock-user} 로 {@code createReceiver} 를 부르지 않는다 — 세션 수신자가 그 자리를 차지해 409 다.
 * ⚠️ 시드 SQL 은 <b>루트 레포</b>에 있어 백엔드 서브모듈 테스트가 파일로 읽을 수 없다 —
 * {@link #restoreSeed()} 가 같은 내용을 {@link JdbcTemplate} 인라인 SQL 로 복사해 갖고 있다.
 *
 * <p><b>{@code noti_id} 값을 단언하지 않는다.</b> 이력은 identity 채번이라 복원할 때마다 값이 밀린다
 * (시드도 명시 INSERT 를 하지 않는다). 대신 "18건" · "교집합 0" 처럼 값에 의존하지 않는 불변식을 본다.
 * {@code rcvr_id} 도 더는 시드가 정하지 않는다 — {@link #plantSessionReceiver()} 가
 * {@value #SESSION_RCVR_ID} 를 명시 INSERT 하고 identity 를 100 으로 되돌리므로
 * API 가 만드는 첫 수신자는 항상 {@value #FIRST_GENERATED_RCVR_ID} 다.
 *
 * <p>응답 바디의 {@code message} 문자열은 단언하지 않는다 — 로컬에서는 devtools 가
 * {@code server.error.include-message=always} 로 덮어써 보이지만 jar 배포에는 devtools 가 없어 사라진다.
 * 환경에 따라 깨지는 단언이 되므로 <b>상태코드만</b> 단언한다. 같은 이유로 실패가 예상되는 호출은
 * 응답 타입을 {@code String.class} 로 받는다(에러 바디를 VO 로 역직렬화하려다 터지는 것을 막는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskNotiIntegrationTest {

    private static final String ALERTS_URL = "/api/risk-alerts";
    private static final String RECEIVERS_URL = "/api/noti-receivers";
    private static final String CRITERIA_URL = "/api/risk-criteria";

    /** 시드 알림 이력 건수 */
    private static final int SEED_ALERT_COUNT = 18;
    /**
     * 시드 수신자 건수 — 🔁 <b>2026-10-15 부터 0</b> (12행을 걷어냈다).
     *
     * <p>🔴 상수를 지우지 않고 <b>0 으로 남긴다</b> — "시드가 깔아 주는 수신자" 와 "이 테스트가 만드는
     * 수신자"({@value #OWN_RECEIVER_COUNT})를 계속 구분해야 건수 단언이 무엇을 세는지 읽힌다.
     * 시드가 다시 수신자를 깔면 여기만 고치면 된다.
     */
    private static final int SEED_RECEIVER_COUNT = 0;

    /**
     * 이 테스트가 {@code @BeforeEach} 에 <b>스스로 심는</b> 수신자 수 — 세션 사용자({@code mock-user})를
     * 수신자로 만드는 {@value #SESSION_RCVR_ID} 번 1행이다({@link #plantSessionReceiver()}).
     * 수신자 목록 건수 단언은 전부 이 값을 기준선으로 쓴다.
     */
    private static final int OWN_RECEIVER_COUNT = 1;

    /**
     * 백필 매핑({@code TB_NOTI_HSTRY_RCVR}) 건수 (TSK-4202 Step 1·6).
     *
     * <p>🔁 <b>2026-10-15 — 66 에서 {@value #SEED_ALERT_COUNT} 로 줄었다.</b> 종전에는 시드 수신자 8명
     * (활성)의 지역 3단 매칭 합이 66 이었는데, 그 12명이 사라져 매칭 대상이
     * {@link #plantSessionReceiver() 세션 수신자} 1명<b>뿐</b>이다. 그 1명은 <b>전체 지역</b>이라
     * 알림 18행 전부에 붙는다 — 그래서 값이 알림 건수와 같다.
     * 🔴 값은 {@link #restoreBackfillMapping()} 의 SQL 이 만들며, 여기서는 <b>그 결과를 고정</b>한다.
     */
    private static final int OWN_MAPPING_COUNT = SEED_ALERT_COUNT;

    /**
     * 🔴 세션 사용자({@code mock-user})를 수신자로 만드는 <b>이 테스트의 합성 행</b> ID
     * (시드가 아니다 — {@link #plantSessionReceiver()} 가 심는다).
     *
     * <p>지역은 <b>둘 다 NULL(전체 지역) · 활성</b>이라 백필이 알림 18행 전부에 붙인다 —
     * 종전 "조회는 전건" 단언들이 그대로 성립한다.
     * <p>🔴 값이 100 <b>미만</b>이어야 한다 — identity 가 {@value #FIRST_GENERATED_RCVR_ID} 부터
     * 채번하므로, 100 이상을 쓰면 API 가 만드는 첫 수신자와 PK 가 충돌한다.
     */
    private static final long SESSION_RCVR_ID = 90L;

    /**
     * 시드 최소 민원수 하한 · 기본값 (TSK-4092 — {@code min_cvlcpt_cnt} = {@code bsc_min_cvlcpt_cnt} = 10).
     *
     * <p>🔴 <b>{@link #restoreSeed()} 의 INSERT 와 단언이 이 상수 하나를 함께 본다.</b> 값을 리터럴로
     * 흩어 두면 복원 SQL 만 고치고 단언이 옛 값을 계속 통과시키는 조용한 어긋남이 생긴다.
     * 원천은 {@code database/migrations/sql/seeding/10-TB_NOTI_ALERT.sql} · 마이그레이션 57 이다.
     */
    private static final int SEED_MIN_CVLCPT_CNT = 10;
    // 🔴 SEED_CVLCPT_SUM(868 — 시드 이력의 sum(cvlcpt_cnt)) 상수는 없앴다 (TSK-4069 Step 9).
    //    민원 수 카드가 이력 합이 아니라 parking.tb_cvlcpt 총합으로 바뀌어 상수의 의미가 사라졌고,
    //    민원 테이블은 시드가 아니라 수집 배치가 채우는 실데이터라 고정값으로 박을 수 없다.
    //    → 기대값은 {@link #countCvlcpt(String)} 로 그때그때 DB 에서 읽는다.
    //    이력 합이 필요한 곳(목록 대조)은 {@link #sumCvlcpt(String)} 가 그대로 남아 있다.

    /** identity 를 100 부터 다시 매기므로(D11) API 가 만든 첫 수신자는 항상 이 값이다 */
    private static final long FIRST_GENERATED_RCVR_ID = 100L;

    /** 케이스 8 픽스처 전용 ID — 시드(1~12)와 identity 채번(100~) 어디와도 겹치지 않는 값 */
    private static final long ORPHAN_RCVR_ID = 900L;

    // ---------------------------------------------------------------- 합성 사용자 (TSK-4250 Step 7)

    /**
     * 🔴 합성 {@code TB_USER}·직접 INSERT 수신자의 {@code user_id} 접두 — 고아 정리 스코프({@code LIKE 't4250-%'})다.
     * 배치 테스트는 {@code t4250b-} 를 써 같은 DB 에서 서로 지우지 않는다. {@code user_id} 는 20자 이내.
     */
    private static final String SYN_PREFIX = "t4250-";

    /** 합성 활성 사용자 4명 — 부서 {@code PARK}(주차관리, sort_seq 2). 이름 순서 = 상수 순서 */
    private static final String USER_1 = "t4250-u-01";
    private static final String USER_2 = "t4250-u-02";
    private static final String USER_3 = "t4250-u-03";
    private static final String USER_4 = "t4250-u-04";

    /** 합성 무소속 사용자 — {@code dept_cd} NULL (Q2 — 후보 목록 맨 뒤) */
    private static final String USER_NODEPT = "t4250-u-nodept";

    /** 합성 비활성 사용자 — {@code use_yn='N'} (후보에 없고 등록은 400) */
    private static final String USER_OFF = "t4250-u-off";

    /** 케이스 8 의 직접 INSERT 수신자 {@code user_id} — {@code TB_USER} 행은 없다(FK 없음 · 행 단위 폴백) */
    private static final String ORPHAN_USER_ID = "t4250-orphan";

    /** 세션 수신자의 {@code eml} — 🔴 로그인 이메일과 일부러 다르다. 매칭은 {@code user_id}(Q3)라 무관하다 */
    private static final String SESSION_RCVR_EML = "t4250-session-copy@example.com";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 🔴 TSK-4115 Step 7 — {@code POST/PATCH/DELETE /api/noti-receivers/**} 와
     * {@code PUT /api/risk-criteria} 가 {@code M0404}(위험단계 알림 관리) 매핑을 요구하게 됐다
     * ({@code @PreAuthorize}). 메서드 보안은 test 체인의 {@code permitAll} 과 무관하게 익명 호출을
     * 403 으로 막으므로 이 클래스의 쓰기 14곳이 세션을 타야 한다. 조회는 종전대로 익명이다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void loginWithMenuGrant() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        // ① mock-login(ROLE_USER) → ② ROLE_USER 에 M0404 부여. 재로그인 없음(매 요청 조회).
        session = auth.mockSessionWithMenus("M0404");
        // 🔴 부서 픽스처 — TB_DEPT 가 빈 표로 시작하므로 수신자 등록 전에 먼저 넣는다 (TSK-4183 D9)
        seedDepts();
        // 🔴 TSK-4250 — API 등록 대상 합성 사용자. fk_tb_user_dept 때문에 seedDepts() **뒤**다
        plantSyntheticUsers();
        // 🔴 TSK-4202 Step 8·9 — 벨·읽음이 "내게 온 알림" 으로 좁혀졌다(🔁 R13 — 조회 화면은 수신자 무관). 세션 사용자를 수신자로 만든다.
        //    🔴 순서 — 수신자를 **먼저** 심어야 백필이 그 사람에게 붙는다 (반대면 매핑이 0행이다).
        plantSessionReceiver();
        restoreBackfillMapping();
    }

    /**
     * 🔴 세션 사용자({@code mock-user})를 수신자로 만든다 — <b>행을 직접 심는다</b>
     * (TSK-4202 Step 9 · 🔁 2026-10-15 · 🔁 TSK-4250 개정).
     *
     * <p>Step 8 이후 {@code GET /api/risk-alerts} 는 요청자의 활성 수신자({@code TB_NOTI_RCVR.rcvr_id})에
     * 매핑된 알림만 내린다. 🔁 <b>TSK-4250 Q3 — 해석 키는 이메일이 아니라 {@code user_id} 다</b>
     * ({@code TB_USER.user_id = TB_NOTI_RCVR.user_id}). 그래서 {@code user_id} = {@code mock-user} 로 심는다.
     * 종전의 "로그인 계정 이메일을 읽어 같은 {@code eml} 로 심기"·{@code IllegalStateException} 은 필요 없어 걷었다 —
     * {@code eml} 칸은 등록 시점 복사본일 뿐이라 일부러 로그인 이메일과 다른 값({@value #SESSION_RCVR_EML})을 둔다.
     *
     * <p>🔴 <b>지역 행 {@code TB_NOTI_RCVR_RGN (rcvr_id, NULL, NULL)} 1행이 필수다</b>(R3 — 전체 지역은 NULL 명시 행).
     * 없으면 지역 0행 = 수신 없음(fail-closed)이라 {@link #restoreBackfillMapping()} 이 0행을 만들어
     * {@value #OWN_MAPPING_COUNT} 단언이 전부 깨진다.
     *
     * <p>🔴 <b>ID 를 {@value #SESSION_RCVR_ID} 로 못박는 이유</b> — identity 에 맡기면 이 행이
     * {@value #FIRST_GENERATED_RCVR_ID} 를 가져가 케이스 5·11 의 "API 가 만든 첫 수신자는 100" 단언이
     * 깨진다. 100 미만의 고정 값을 쓰면 identity 와 영역이 겹치지 않는다.
     *
     * <p>🔴 <b>앞서 남은 테스트 생성분({@code rcvr_id >= 100})을 먼저 걷고 identity 를 되돌린다</b> —
     * 비정상 종료나 앞선 실행이 남긴 행이 있으면 케이스 5 의 첫 채번이 101·102 로 밀린다.
     * 🔁 TSK-4250 — {@code user_id = mock-user} 인 고아 행도 걷는다(다른 테스트 클래스가 남겼을 수 있다) —
     * 남으면 {@code uq_tb_noti_rcvr_user}(사용자당 활성 1행)에 걸려 아래 INSERT 가 깨진다.
     * 🔴 <b>조건 없는 {@code DELETE FROM parking.tb_noti_hstry_rcvr} 는 쓰지 않는다</b>
     * (백필을 통째로 날린 전례가 있다).
     * ⚠️ 그래도 {@link #restoreSeed()} 가 수신자 표를 전량 비우므로, <b>이 테스트를 한 번 돌리면
     * 로컬에서 화면으로 만들어 둔 수신자는 사라진다.</b>
     */
    private void plantSessionReceiver() {
        // 매핑 → 지역 → 단계 → 수신자 (매핑 FK 에는 CASCADE 가 없다)
        jdbcTemplate.update("DELETE FROM parking.tb_noti_hstry_rcvr WHERE rcvr_id >= 100");
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr_rgn WHERE rcvr_id >= 100");
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr_lvl WHERE rcvr_id >= 100");
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr WHERE rcvr_id >= 100");
        jdbcTemplate.execute("ALTER TABLE parking.TB_NOTI_RCVR ALTER COLUMN rcvr_id RESTART WITH 100");

        String mine = "SELECT r.rcvr_id FROM parking.tb_noti_rcvr r WHERE r.rcvr_id = ? OR r.user_id = ?";
        jdbcTemplate.update("DELETE FROM parking.tb_noti_hstry_rcvr WHERE rcvr_id IN (" + mine + ")",
                SESSION_RCVR_ID, IntegrationLoginSupport.MOCK_ID);
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr_rgn WHERE rcvr_id IN (" + mine + ")",
                SESSION_RCVR_ID, IntegrationLoginSupport.MOCK_ID);
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr_lvl WHERE rcvr_id IN (" + mine + ")",
                SESSION_RCVR_ID, IntegrationLoginSupport.MOCK_ID);
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr WHERE rcvr_id = ? OR user_id = ?",
                SESSION_RCVR_ID, IntegrationLoginSupport.MOCK_ID);

        // 단계 매핑(TB_NOTI_RCVR_LVL)은 일부러 넣지 않는다 — 조회 필터와 무관하고,
        // 넣으면 케이스 16 의 "단계 매핑 총건수" 기준선이 흐려진다.
        jdbcTemplate.update("""
                INSERT INTO parking.tb_noti_rcvr
                       (rcvr_id, user_id, dept_cd, rcvr_nm, eml, use_yn, del_yn, ntfctn_ch_cd)
                VALUES (?, ?, NULL, '세션수신자', ?, 'Y', 'N', 'EMAIL')""",
                SESSION_RCVR_ID, IntegrationLoginSupport.MOCK_ID, SESSION_RCVR_EML);
        // 🔴 지역 둘 다 NULL 행 = 전체 지역(R3) → 백필이 알림 18행 전부에 붙는다 (OWN_MAPPING_COUNT)
        jdbcTemplate.update(
                "INSERT INTO parking.tb_noti_rcvr_rgn (rcvr_id, sgg_nm, emd_cd) VALUES (?, NULL, NULL)",
                SESSION_RCVR_ID);
    }

    /**
     * 🔴 합성 {@code TB_USER} 6행 (TSK-4250 Step 7 — 픽스처 전략 표의 "API 로 만드는 수신자" 행).
     *
     * <p>R8 — 등록 API 는 {@code userId} 로 {@code TB_USER} 를 읽어 이름·이메일·부서를 <b>복사</b>한다(없음·비활성 400).
     * 그래서 API 로 수신자를 만드는 케이스는 실재 사용자가 필요하다. 로컬 실사용자를 쓰면 환경마다 갈리므로
     * {@value #SYN_PREFIX} 접두의 합성 사용자를 쓴다.
     *
     * <p>🔴 NOT NULL 7칸({@code user_id}·{@code cert_id}·{@code user_nm}·{@code user_authrt_cd}·{@code use_yn}·
     * {@code rgtr_id}·{@code reg_dt})을 모두 채운다. {@code cert_id} 는 UNIQUE 라 값이 갈린다.
     * 🔴 {@code fk_tb_user_dept} 때문에 {@link #seedDepts()} <b>뒤</b>에 부른다.
     * 🔴 직전 실행이 비정상 종료해 남긴 행이 PK·{@code cert_id} UNIQUE 를 막지 않게 <b>INSERT 전에</b> 지운다 —
     * 그 사용자를 가리키는 수신자 고아(매핑 → 지역 → 단계 → 수신자)를 먼저 걷는다.
     */
    private void plantSyntheticUsers() {
        deleteSyntheticReceiversAndUsers();
        jdbcTemplate.update("""
                INSERT INTO parking.tb_user
                       (user_id, cert_id, user_nm, user_eml_addr, user_authrt_cd, dept_cd, use_yn, rgtr_id, reg_dt)
                VALUES (?, 't4250-cert-01',  '합성사용자1', 't4250-u-01@test.local',  'ROLE_USER', 'PARK', 'Y', 'test', now()),
                       (?, 't4250-cert-02',  '합성사용자2', 't4250-u-02@test.local',  'ROLE_USER', 'PARK', 'Y', 'test', now()),
                       (?, 't4250-cert-03',  '합성사용자3', 't4250-u-03@test.local',  'ROLE_USER', 'PARK', 'Y', 'test', now()),
                       (?, 't4250-cert-04',  '합성사용자4', 't4250-u-04@test.local',  'ROLE_USER', 'PARK', 'Y', 'test', now()),
                       (?, 't4250-cert-nd',  '합성무소속',  't4250-u-nd@test.local',  'ROLE_USER', NULL,   'Y', 'test', now()),
                       (?, 't4250-cert-off', '합성정지',    't4250-u-off@test.local', 'ROLE_USER', 'PARK', 'N', 'test', now())""",
                USER_1, USER_2, USER_3, USER_4, USER_NODEPT, USER_OFF);
    }

    /**
     * 합성 접두({@value #SYN_PREFIX}) 수신자와 합성 사용자를 걷는다 — 고아 정리라 {@code rcvr_id} 가 아니라
     * <b>{@code user_id} 스코프</b>다. 순서: 매핑(CASCADE 없음) → 지역 → 단계 → 수신자 → 합성 {@code TB_USER}.
     */
    private void deleteSyntheticReceiversAndUsers() {
        String scope = "SELECT r.rcvr_id FROM parking.tb_noti_rcvr r WHERE r.user_id LIKE '" + SYN_PREFIX + "%'";
        jdbcTemplate.update("DELETE FROM parking.tb_noti_hstry_rcvr WHERE rcvr_id IN (" + scope + ")");
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr_rgn WHERE rcvr_id IN (" + scope + ")");
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr_lvl WHERE rcvr_id IN (" + scope + ")");
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr WHERE user_id LIKE '" + SYN_PREFIX + "%'");
        jdbcTemplate.update("DELETE FROM parking.tb_user WHERE user_id LIKE '" + SYN_PREFIX + "%'");
    }

    /**
     * 🔴 부서 픽스처 7행 (TSK-4183 Step 3 항목 4 · D9).
     *
     * <p>공통코드 {@code DEPT_CD} 가 삭제돼 {@code parking.TB_DEPT} 가 부서의 정본이 됐고, 그 표는
     * IdP {@code groups} 의 투영이라 <b>빈 표로 시작</b>한다(D8). 이 7행이 없으면 아래 케이스들의
     * 수신자·설정 등록이 <b>전부 400</b> 이 되어 테스트가 통째로 깨진다.
     *
     * <p>🔴 <b>{@code @BeforeEach} 에 둔다</b> — 시드 복원({@code @AfterEach restoreSeed()})에만 두면
     * 빈 표로 시작하는 첫 실행의 <b>첫 케이스</b>가 400 으로 깨진다({@code dept_cd} 에는 FK 가 없어
     * INSERT 순서 제약도 없다).
     *
     * <p>🔴 <b>정리(DELETE)하지 않는다</b> — {@code RiskNotiIntegrationTest} 와
     * {@code ReportIntegrationTest} 가 같은 7코드를 쓰므로 한쪽이 지우면 다른 쪽이 깨진다.
     * {@code ON CONFLICT DO NOTHING} 이라 반복 실행도 안전하고, 사후 보정한 {@code dept_nm} 도 덮지 않는다.
     * ⚠️ 그래서 이 테스트를 한 번 돌리면 {@code /api/depts} 가 이 7행을 계속 돌려준다.
     *
     * <p>🔴 값·순서는 삭제된 공통코드 {@code DEPT_CD} 7행
     * ({@code database/migrations/sql/36-report-codes.sql:18~24})과 <b>동일</b>하다 — 테스트가 실제로 쓰는
     * 것은 6종({@code TRFC}·{@code PARK}·{@code SAFE}·{@code CIVIL}·{@code INFO}·{@code ALL}: 보고서 14행의
     * {@code crltn_dept_cd='ALL'} 이 {@code deptNm} 단언에 걸린다)이지만, 미사용 {@code VHCL} 까지 넣어
     * <b>같은 누락을 다시 만들지 않는다.</b> 리터럴 64곳은 한 자도 고치지 않는다(D9).
     *
     * <p>🔴 "없는 부서 → 400" 검증은 별도 리터럴 {@code NOPE} 가 담당하므로 <b>여기 넣지 않는다.</b>
     */
    private void seedDepts() {
        jdbcTemplate.update("""
                INSERT INTO parking.tb_dept (dept_cd, dept_nm, sort_seq, use_yn, rgtr_id, reg_dt) VALUES
                  ('TRFC', '교통정책과', 1, 'Y', 'test', now()),
                  ('PARK', '주차관리',   2, 'Y', 'test', now()),
                  ('SAFE', '안전정책과', 3, 'Y', 'test', now()),
                  ('CIVIL','종합민원실', 4, 'Y', 'test', now()),
                  ('VHCL', '차량관리과', 5, 'Y', 'test', now()),
                  ('INFO', '정보화',     6, 'Y', 'test', now()),
                  ('ALL',  '전체',       7, 'Y', 'test', now())
                ON CONFLICT (dept_cd) DO NOTHING""");
    }

    /**
     * 알림 5테이블을 시드 상태(기준 3 · <b>수신자 0 · 단계매핑 0</b> · 이력 18 · 발송기록 0)로 되돌린다.
     * 🔁 2026-10-15 — 시드가 수신자를 깔지 않게 되어 여기서도 12행·14행 INSERT 를 걷었다.
     *
     * <p>순서 고정: <b>참조 역순 DELETE 선행</b>({@code TB_NOTI_HSTRY_RCVR} → {@code TB_NOTI_RCVR_LVL} →
     * {@code TB_NOTI_RCVR} → {@code HSTRY} → {@code CRTR} — 시드 SQL 의 DELETE 순서와 같다).
     * 순서를 뒤집으면 FK 위반으로 헬퍼 자체가 터진다.
     *
     * <p>INSERT 값·순서는 시드({@code database/migrations/sql/seeding/10-TB_NOTI_ALERT.sql})와 동일하다 —
     * 기준(CRTR)을 이력보다 <b>먼저</b> 넣어야 이력의 {@code incrs_rt_thrsh} 스냅샷(D7)이 조인으로 채워진다.
     *
     * <p>🔴 마지막의 {@code ALTER TABLE ... RESTART WITH 100} 을 빼면 안 된다 —
     * 빼면 케이스 5·11 의 신규 {@code rcvrId} 가 실행마다 밀려 단언이 깨진다.
     */
    @AfterEach
    void restoreSeed() {
        // TSK-4115 Step 7-1 — 헬퍼가 넣은 매핑·mock 계정·로그인 이력을 함께 되돌린다
        auth.cleanUp();

        // --- 참조 역순 DELETE ---
        // 🔴 TSK-4202 Step 9 — 종전 첫 줄이던 **조건 없는** `DELETE FROM parking.tb_noti_hstry_rcvr` 를
        //    걷어냈다. 그 한 줄이 Step 1 백필 66행을 통째로 날려(Step 5 에서 66 → 0 실측) 알림 조회가
        //    전원 0건이 됐다 — 종전에는 그 표가 "0행이 정상" 이라 눈에 띄지 않았다.
        //    🔴 지울 범위는 **알림 DELETE 가 FK CASCADE 로 정확히 알아서** 만든다
        //       (fk_tb_noti_hstry_rcvr_noti 는 ON DELETE CASCADE). 그래서 알림을 **먼저** 지우면
        //       매핑이 함께 사라져, 뒤따르는 수신자 DELETE 가 FK 위반으로 죽지 않는다
        //       (fk_tb_noti_hstry_rcvr_rcvr 에는 CASCADE 가 없다 — 순서가 바로 그 대비책이다).
        //    🔴 그리고 아래 6) 의 restoreBackfillMapping() 이 66행을 **다시 만든다** —
        //       지우기만 하고 끝내면 다음 테스트·다음 사람이 빈 매핑 표를 받는다.
        jdbcTemplate.update("DELETE FROM parking.tb_risk_stp_noti_hstry");   // → 매핑 CASCADE
        // 🔁 TSK-4250 — 매핑 → **지역** → 단계 → 수신자 (지역은 CASCADE 로도 지워지지만 참조 역순을 명시한다)
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr_rgn");
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr_lvl");
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr");
        // 🔁 TSK-4250 — 수신자 **뒤에** 합성 TB_USER(t4250-%)를 지운다 (수신자 행이 고아로 남지 않게)
        jdbcTemplate.update("DELETE FROM parking.tb_user WHERE user_id LIKE '" + SYN_PREFIX + "%'");
        jdbcTemplate.update("DELETE FROM parking.tb_risk_stp_noti_crtr");

        // --- 🔴 이력 PK 를 1 부터 다시 채번한다 — 시드 SQL 과 같은 처리다 (재현성) ---
        //     DELETE 는 identity 를 되돌리지 않아, 이걸 빼면 테스트가 한 번 돌 때마다 noti_id 가 18씩 밀린다.
        //     아래 5-1) 의 읽음·확인이 `noti_id % 3` 이라 배정이 매번 달라져(건수는 6/6/6 고정) 재현이 안 된다.
        jdbcTemplate.execute("ALTER TABLE parking.TB_RISK_STP_NOTI_HSTRY ALTER COLUMN noti_id RESTART WITH 1");

        // --- 1) 알림 기준 3행 (D7 — thrsh_id 는 'THRSH_' + 단계코드 고정) ---
        //     🔴 min_cvlcpt_cnt · bsc_min_cvlcpt_cnt 를 반드시 명시한다 (TSK-4092 D2·D11) —
        //        빼면 DDL 의 DEFAULT 0 이 들어가 "하한 없음 · 기본값 0" 으로 복원된다(시드와 다른 상태).
        jdbcTemplate.update("""
                INSERT INTO parking.tb_risk_stp_noti_crtr
                       (thrsh_id, risk_lvl_cd, thrsh_cnt, incrs_rt,
                        min_cvlcpt_cnt, bsc_min_cvlcpt_cnt, mdfr_id, mdfcn_dt) VALUES
                  ('THRSH_CRIT','CRIT', NULL, 30.00, ?, ?, 'system', now()),
                  ('THRSH_WARN','WARN', NULL, 20.00, ?, ?, 'system', now()),
                  ('THRSH_CAUT','CAUT', NULL, 10.00, ?, ?, 'system', now())""",
                SEED_MIN_CVLCPT_CNT, SEED_MIN_CVLCPT_CNT,
                SEED_MIN_CVLCPT_CNT, SEED_MIN_CVLCPT_CNT,
                SEED_MIN_CVLCPT_CNT, SEED_MIN_CVLCPT_CNT);

        // --- 2)·3) 수신자 · 단계 매핑 — 🔴 넣지 않는다 (🔁 2026-10-15) ---
        //     시드가 12행·14행을 걷어냈다(이메일 매칭 0 — 아무도 자기 알림을 볼 수 없었다).
        //     이 헬퍼는 시드 SQL 의 사본이므로 함께 비운다. 필요한 수신자는 테스트가 스스로 만든다:
        //       · 세션 사용자 = plantSessionReceiver() (@BeforeEach, rcvr_id=90)
        //       · 픽스처       = createReceiver(...)    (API POST, rcvr_id=100~)
        //     🔴 위 DELETE 로 **수신자 표가 비는 것이 정상**이다 — verifyRestored() 가 0 을 단언한다.

        // --- 4) 알림 이력 18행 (D12 — 전부 읍면동 단위. noti_id 는 identity 에 맡긴다) ---
        //     noti_ctt 의 rtrim 은 D6 형식('26.5%' 는 소수부 보존 · '22%' 는 마침표 없음)을 만든다.
        jdbcTemplate.update("""
                WITH evt(sgg_nm, emd_nm, risk_lvl_cd, cvlcpt_cnt, incrs_rt, ocrn_dt) AS (VALUES
                  ('제주시',  '연동',     'CRIT', 89, 45.00, TIMESTAMP '2026-05-01 14:21'),
                  ('제주시',  '연동',     'WARN', 29, 22.00, TIMESTAMP '2026-05-28 11:47'),
                  ('제주시',  '노형동',   'WARN', 34, 26.50, TIMESTAMP '2026-05-28 11:47'),
                  ('제주시',  '이도이동', 'WARN', 22, 21.00, TIMESTAMP '2026-05-28 11:47'),
                  ('제주시',  '삼도일동', 'CAUT', 22, 10.00, TIMESTAMP '2026-05-24 10:12'),
                  ('서귀포시','중문동',   'WARN', 47, 22.00, TIMESTAMP '2026-05-19 15:38'),
                  ('제주시',  '이도일동', 'CRIT', 84, 46.00, TIMESTAMP '2026-05-14 09:52'),
                  ('제주시',  '아라일동', 'CAUT', 26, 11.00, TIMESTAMP '2026-05-09 17:03'),
                  ('제주시',  '노형동',   'WARN', 62, 28.00, TIMESTAMP '2026-05-05 13:05'),
                  ('제주시',  '이도일동', 'CAUT', 34, 12.00, TIMESTAMP '2026-04-18 09:30'),
                  ('제주시',  '연동',     'WARN', 53, 24.00, TIMESTAMP '2026-04-11 16:44'),
                  ('서귀포시','하효동',   'CAUT', 19, 11.00, TIMESTAMP '2026-04-03 10:55'),
                  ('제주시',  '노형동',   'CRIT', 95, 52.00, TIMESTAMP '2026-03-22 16:10'),
                  ('제주시',  '애월읍',   'CAUT', 28, 12.00, TIMESTAMP '2026-03-15 14:07'),
                  ('제주시',  '삼도일동', 'WARN', 44, 21.00, TIMESTAMP '2026-03-06 09:18'),
                  ('제주시',  '연동',     'WARN', 58, 27.00, TIMESTAMP '2026-02-14 11:00'),
                  ('제주시',  '애월읍',   'CAUT', 31, 13.00, TIMESTAMP '2026-01-09 10:20'),
                  ('서귀포시','성산읍',   'CRIT', 91, 48.00, TIMESTAMP '2025-12-28 15:45')
                )
                INSERT INTO parking.tb_risk_stp_noti_hstry
                       (risk_lvl_cd, emd_cd, cvlcpt_cnt, incrs_rt, noti_ctt, incrs_rt_thrsh, ocrn_dt, prcs_stts_cd)
                SELECT e.risk_lvl_cd, a.emd_cd, e.cvlcpt_cnt, e.incrs_rt,
                       a.emd_nm || ' 민원 ' || rtrim(to_char(e.incrs_rt,'FM990.99'),'.') || '% 증가',
                       c.incrs_rt,
                       e.ocrn_dt, 'OPEN'
                  FROM evt e
                  JOIN parking.tb_admdst a             ON a.sgg_nm = e.sgg_nm AND a.emd_nm = e.emd_nm
                  JOIN parking.tb_risk_stp_noti_crtr c ON c.risk_lvl_cd = e.risk_lvl_cd
                 ORDER BY e.ocrn_dt, a.emd_cd""");

        // --- 5) 🔁 TSK-4202 — 종전 "발송기록은 채우지 않는다(0행이 정상)" 는 폐기됐다.
        //        그 표가 이제 **조회 대상을 정하는 표**다(D6·D9). 아래 6) 에서 백필을 재현한다. ---

        // --- 5-1) 읽음·확인 3단계를 섞는다 (D17) ---
        //     🔴 위 INSERT 의 RESTART WITH 1 + ORDER BY 가 세트로 있어야 배정까지 재현된다.
        //        RESTART 만으로는 범위(1~18)만 잡히고, 조인 결과 순서를 PostgreSQL 이 보장하지 않아
        //        어떤 행이 읽음/확인인지가 실행마다 달라진다(건수만 6/6/6 고정).
        jdbcTemplate.update("""
                UPDATE parking.tb_risk_stp_noti_hstry
                   SET prsl_yn='Y', prsl_dt = ocrn_dt + interval '2 hour'
                 WHERE noti_id % 3 <> 0""");
        jdbcTemplate.update("""
                UPDATE parking.tb_risk_stp_noti_hstry
                   SET idnty_yn='Y', idnty_dt = prsl_dt + interval '1 hour'
                 WHERE prsl_yn = 'Y' AND noti_id % 3 = 1""");

        // --- 🔴 identity 시작점을 시드 최대값 뒤로 민다 (D11) ---
        jdbcTemplate.execute("ALTER TABLE parking.TB_NOTI_RCVR ALTER COLUMN rcvr_id RESTART WITH 100");

        // --- 6) 🔴 백필 매핑 재현 (TSK-4202 Step 1·6) ---
        //     ⚠️ 수신자 표가 비었으므로 여기서는 **0행**이 만들어진다 — 다음 테스트의 @BeforeEach 가
        //        세션 수신자를 심은 뒤 다시 불러 OWN_MAPPING_COUNT 행을 만든다.
        restoreBackfillMapping();

        verifyRestored();
    }

    /**
     * 알림 × 수신자 매핑({@code TB_NOTI_HSTRY_RCVR})을 <b>백필과 같은 규칙</b>으로 다시 만든다
     * (TSK-4202 Step 9).
     *
     * <p>🔴 이 SQL 은 배치 {@code RiskAlertReceiverMappingTasklet.INSERT_HEAD} 의 <b>지역 3단 규칙을
     * 그대로 옮긴 것</b>이다 — ① 읍면동 지정 ② 시군구 전체 ③ 전체 지역. 레포가 달라 코드를 공유할 수
     * 없어 규칙을 복제한다(보고서 주기 규칙이 두 레포에 중복된 것과 같은 사정). 규칙을 고치면
     * <b>두 곳을 함께</b> 고친다(TSK-4250 R7).
     *
     * <p>🔴 지역 판정의 정본은 {@code parking.TB_NOTI_RCVR_RGN} 이다(TSK-4250) — {@code sgg_nm}·{@code emd_cd}
     * 둘 다 NULL 인 행 = 전체 지역, 지역 행 0개 = 수신 없음(R3 — fail-closed). 지역 행 수만큼 쌍이
     * 불어나지 않게 JOIN 이 아니라 {@code EXISTS} 로 본다.
     *
     * <p>🔴 <b>왜 여기서 다시 만드는가</b> — {@link #restoreSeed()} 가 알림을 전량 재생성하므로
     * (알림은 {@code noti_id} 를 1부터 다시 채번한다) 기존 매핑은 FK CASCADE 로 함께 사라진다.
     * ⚠️ 🔁 2026-10-15 — 수신자 시드가 사라져 {@code @AfterEach} 시점의 결과는 <b>0행</b>이다.
     * 실제로 행을 만드는 것은 {@code @BeforeEach}(세션 수신자를 심은 직후) 호출이며,
     * 그때 {@value #OWN_MAPPING_COUNT} 행이 된다.
     *
     * <p>{@code ON CONFLICT DO NOTHING} 이라 {@code @BeforeEach} 에서 다시 불러도 안전하다
     * (배치의 재실행 정책과 같다).
     */
    private void restoreBackfillMapping() {
        jdbcTemplate.update("""
                INSERT INTO parking.TB_NOTI_HSTRY_RCVR (noti_id, rcvr_id)
                SELECT h.noti_id, r.rcvr_id
                  FROM parking.TB_RISK_STP_NOTI_HSTRY h
                  JOIN parking.TB_ADMDST a ON a.emd_cd = h.emd_cd
                  JOIN parking.TB_NOTI_RCVR r
                    ON r.use_yn = 'Y' AND r.del_yn = 'N'
                   AND (EXISTS (SELECT 1 FROM parking.TB_NOTI_RCVR_RGN g
                                 WHERE g.rcvr_id = r.rcvr_id
                                   AND (g.emd_cd = h.emd_cd                                   -- ① 읍면동 지정
                                     OR g.emd_cd IS NULL AND g.sgg_nm = a.sgg_nm              -- ② 시군구 전체
                                     OR g.sgg_nm IS NULL AND g.emd_cd IS NULL)))              -- ③ 전체 지역(NULL 행, R3)
                ON CONFLICT (noti_id, rcvr_id) DO NOTHING""");
    }

    /**
     * 복원 결과를 즉시 검증한다 — 시드 SQL 의 {@code DO $$ ... RAISE EXCEPTION} 가드에 대응한다.
     * 조용히 어긋난 복원이 다음 테스트를 엉뚱하게 실패시키는 것을 막는다.
     */
    private void verifyRestored() {
        int receivers = count("SELECT count(*) FROM parking.tb_noti_rcvr");
        int levels = count("SELECT count(*) FROM parking.tb_noti_rcvr_lvl");
        int regions = count("SELECT count(*) FROM parking.tb_noti_rcvr_rgn");
        int synUsers = count("SELECT count(*) FROM parking.tb_user WHERE user_id LIKE '" + SYN_PREFIX + "%'");
        int alerts = count("SELECT count(*) FROM parking.tb_risk_stp_noti_hstry");
        int criteria = count("SELECT count(*) FROM parking.tb_risk_stp_noti_crtr");
        // 🔴 건수만 보면 하한이 0 으로 복원돼도 통과한다 (TSK-4092) — 값까지 센다.
        Integer minRestoredValue = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_risk_stp_noti_crtr "
                        + "WHERE min_cvlcpt_cnt = ? AND bsc_min_cvlcpt_cnt = ?",
                Integer.class, SEED_MIN_CVLCPT_CNT, SEED_MIN_CVLCPT_CNT);
        int minRestored = minRestoredValue == null ? 0 : minRestoredValue;
        int dispatched = count("SELECT count(*) FROM parking.tb_noti_hstry_rcvr");

        // 🔁 2026-10-15 — 수신자 시드가 사라져 기대값이 바뀌었다.
        //    수신자 0 · 단계매핑 0 · 알림수신대상 0 (매핑은 다음 @BeforeEach 가 세션 수신자로 다시 만든다).
        //    🔴 종전의 "emd_cd 누락 가드" 는 검사 대상(시드 수신자 7행)이 없어져 함께 걷었다.
        if (receivers != SEED_RECEIVER_COUNT || levels != 0 || regions != 0 || synUsers != 0
                || alerts != SEED_ALERT_COUNT
                || criteria != 3 || dispatched != 0 || minRestored != 3) {
            throw new IllegalStateException("시드 복원 실패 — 수신자=" + receivers + "("
                    + SEED_RECEIVER_COUNT + ") 단계매핑=" + levels + "(0) 지역매핑=" + regions
                    + "(0) 합성사용자=" + synUsers + "(0) 이력=" + alerts + "(18) 기준="
                    + criteria + "(3) 알림수신대상=" + dispatched + "(0) 최소민원수복원="
                    + minRestored + "(3)");
        }
    }

    // ================================================================= 알림 이력 조회 (Step 5)

    @Test
    @DisplayName("1. GET /api/risk-alerts 무필터 — totalElements=18, summary 합이 18")
    void case01_alertsWithoutFilter() {
        AlertPage page = getAlerts("");

        assertThat(page.totalElements).isEqualTo(SEED_ALERT_COUNT);
        assertThat(page.page).isZero();
        assertThat(page.size).isEqualTo(10);
        assertThat(page.totalPages).isEqualTo(2);
        assertThat(page.content).hasSize(10);   // 기본 size=10

        RiskAlertSummaryVO summary = page.summary;
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalCnt()).isEqualTo(SEED_ALERT_COUNT);
        // 단계별 합이 전체와 같아야 한다 — 어긋나면 FILTER 절이나 조인이 행을 늘린 것이다
        assertThat(summary.getCritCnt() + summary.getWarnCnt() + summary.getCautCnt())
                .isEqualTo(SEED_ALERT_COUNT);
        assertThat(summary.getCritCnt()).isEqualTo(4);
        assertThat(summary.getWarnCnt()).isEqualTo(8);
        assertThat(summary.getCautCnt()).isEqualTo(6);
        // 🔴 민원 수 카드는 이력 합(868)이 아니라 **민원 테이블 총합**이다 (TSK-4069 Step 9).
        //    옛 SQL(sum(h.cvlcpt_cnt))로 되돌리면 여기서 깨진다 — 이력은 알림이 난 읍면동만 담기 때문이다.
        assertThat(summary.getCvlcptSumCnt()).isEqualTo(countCvlcpt(null));

        // 최신순(ocrn_dt DESC) — 첫 행이 마지막 행보다 늦다
        assertThat(page.content.get(0).getOcrnDt())
                .isAfter(page.content.get(page.content.size() - 1).getOcrnDt());
    }

    @Test
    @DisplayName("2. 단계·지역 필터 — summary 와 content 가 같은 조건으로 움직인다")
    void case02_levelAndRegionFilters() {
        // (1) 단계 필터
        AlertPage warn = getAlerts("?riskLvlCd=WARN&size=100");
        assertThat(warn.totalElements).isEqualTo(8);
        assertThat(warn.content).hasSize(8);
        assertThat(warn.content).extracting(RiskAlertVO::getRiskLvlCd).containsOnly("WARN");
        assertThat(warn.summary.getTotalCnt()).isEqualTo(8);
        assertThat(warn.summary.getWarnCnt()).isEqualTo(8);
        assertThat(warn.summary.getCritCnt()).isZero();
        assertThat(warn.summary.getCautCnt()).isZero();

        // (2) 시군구 필터 — 이력에 sgg_nm 이 없어 TB_ADMDST 조인으로 건다 (D12)
        // 한글 파라미터는 그대로 넘긴다 — RestTemplate 의 UriBuilderFactory 가 인코딩한다
        // (미리 percent-encoding 하면 '%' 가 다시 '%25' 로 인코딩돼 0건이 된다)
        AlertPage seogwipo = getAlerts("?sggNm=서귀포시&size=100");
        assertThat(seogwipo.totalElements).isEqualTo(3);
        assertThat(seogwipo.content).extracting(RiskAlertVO::getSggNm).containsOnly("서귀포시");
        assertThat(seogwipo.summary.getTotalCnt()).isEqualTo(3);
        // 지역 필터는 민원 수 카드에도 계속 반영된다 — 대조 대상만 민원 테이블로 바뀌었다 (TSK-4069 Step 9)
        assertThat(seogwipo.summary.getCvlcptSumCnt())
                .isEqualTo(countCvlcpt("a.sgg_nm = '서귀포시'"));

        // (3) 읍면동 필터
        String yeondong = emdCd("제주시", "연동");
        AlertPage emd = getAlerts("?emdCd=" + yeondong + "&size=100");
        assertThat(emd.totalElements).isEqualTo(4);
        assertThat(emd.content).extracting(RiskAlertVO::getEmdCd).containsOnly(yeondong);
        assertThat(emd.summary.getTotalCnt()).isEqualTo(4);
        // 🔴 읍면동 필터도 민원 수 카드에 반영된다 — sggNm 과 대칭이다 (TSK-4069 Step 9).
        //    이 단언이 없으면 카드 SQL 의 `AND c.emd_cd = #{emdCd}` 한 줄이 사라져도 아무도 못 잡는다:
        //    목록은 연동 4건인데 카드만 제주도 전체를 세는 상태가 조용히 성립한다.
        assertThat(emd.summary.getCvlcptSumCnt())
                .isEqualTo(countCvlcpt("c.emd_cd = '" + yeondong + "'"));

        // (4) 단계 + 지역 동시 — 두 조건이 함께 걸린다
        AlertPage both = getAlerts("?riskLvlCd=WARN&emdCd=" + yeondong + "&size=100");
        assertThat(both.totalElements).isEqualTo(3);
        assertThat(both.summary.getTotalCnt()).isEqualTo(3);
        assertThat(both.content).allSatisfy(row -> {
            assertThat(row.getRiskLvlCd()).isEqualTo("WARN");
            assertThat(row.getEmdCd()).isEqualTo(yeondong);
        });

        // (5) '전체' 값(all)·빈 문자열은 필터 없음으로 접힌다
        assertThat(getAlerts("?riskLvlCd=all&sggNm=&emdCd=").totalElements).isEqualTo(SEED_ALERT_COUNT);
    }

    /**
     * 3. 기간 경계 — {@code from}/{@code to} 가 그날을 00:00:00~23:59:59 로 펼치는지 본다.
     *
     * <p>🔴 <b>성격이 바뀌었다 (TSK-4197)</b> — 종전에는 시드 실측일({@code 2026-05-28}·{@code 2026-05-14})을
     * 리터럴로 박고 그날의 건수(3·1)까지 상수로 단언했다. 지금은 <b>발생일을 DB 에서 고르고 기대 건수도
     * 독립 집계로 만든다</b>. 그래서 단언의 의미가 "시드 실측값 고정" 에서
     * <b>"조회 API 의 기간 해석이 DB 직접 집계와 일치한다"</b> 로 바뀌었다.
     *
     * <p>기대값은 {@link #alertDateCounts()} 의 <b>원시 SQL 집계</b>로 만든다 — 조회 매퍼·서비스·API 를
     * 한 줄도 타지 않으므로 "자기 자신과 비교" 가 아니다.
     */
    @Test
    @DisplayName("3. 기간 경계 — from=to=발생일 에 그날 알림이 전부 들어온다 (00:00:00~23:59:59)")
    void case03_dateBoundary() {
        List<DayCount> byDate = alertDateCounts();
        // 🔴 0건이면 아래가 전부 '0 == 0' 으로 조용히 통과한다
        assertThat(byDate).as("자정이 아닌 시각의 알림 이력이 한 건도 없다 — 경계를 검증할 수 없다").isNotEmpty();

        // (1) 종료일 경계 — 그날 알림이 가장 많은 날을 고른다.
        //     그 알림들의 시각은 전부 00:00:00 보다 뒤이므로, to 를 00:00:00 으로 비교하면 통째로 빠진다.
        DayCount busiest = byDate.get(0);
        assertThat(busiest.cnt).isPositive();

        AlertPage oneDay = getAlerts("?from=" + busiest.day + "&to=" + busiest.day + "&size=100");
        assertThat(oneDay.totalElements).isEqualTo(busiest.cnt);
        assertThat(oneDay.summary.getTotalCnt()).isEqualTo(busiest.cnt);
        assertThat(oneDay.content).hasSize((int) busiest.cnt);
        assertThat(oneDay.content).allSatisfy(row ->
                assertThat(row.getOcrnDt().toLocalDate()).isEqualTo(busiest.day));

        // (2) 시작일 경계도 같이 본다 — from 은 00:00:00 이라 그날 오전 알림이 들어와야 한다.
        //     건수가 가장 적은 날(목록의 끝)을 골라 (1) 과 다른 날짜가 걸리게 한다.
        DayCount quietest = byDate.get(byDate.size() - 1);
        assertThat(quietest.cnt).isPositive();

        AlertPage fromDay = getAlerts("?from=" + quietest.day + "&to=" + quietest.day + "&size=100");
        assertThat(fromDay.totalElements).isEqualTo(quietest.cnt);
        assertThat(fromDay.content).hasSize((int) quietest.cnt);
        assertThat(fromDay.content).allSatisfy(row ->
                assertThat(row.getOcrnDt().toLocalDate()).isEqualTo(quietest.day));
    }

    @Test
    @DisplayName("4. 페이지 경계 — page 0·1 의 notiId 교집합이 0 이고 합집합이 18")
    void case04_pageBoundary() {
        AlertPage first = getAlerts("?page=0&size=10");
        AlertPage second = getAlerts("?page=1&size=10");

        assertThat(first.content).hasSize(10);
        assertThat(second.content).hasSize(8);
        assertThat(second.page).isEqualTo(1);

        Set<Long> firstIds = ids(first);
        Set<Long> secondIds = ids(second);
        // 같은 ocrn_dt 를 3건이 공유하므로 tie-breaker(noti_id) 가 없으면 여기서 중복이 난다
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        assertThat(firstIds).hasSize(10);
        assertThat(secondIds).hasSize(8);

        Set<Long> union = new java.util.LinkedHashSet<>(firstIds);
        union.addAll(secondIds);
        assertThat(union).hasSize(SEED_ALERT_COUNT);

        // 요약은 페이지가 아니라 필터 전체 기준이라 페이지를 넘겨도 같다 (D8)
        assertThat(second.summary.getTotalCnt()).isEqualTo(first.summary.getTotalCnt());
        assertThat(second.summary.getCvlcptSumCnt()).isEqualTo(first.summary.getCvlcptSumCnt());
    }

    // ================================================================= 수신자 CRUD (Step 6)

    /**
     * ⚠️ <b>변이 테스트 실측(2026-08-21)</b> — {@code selectReceiverById} 의 {@code flushCache="true"} 를
     * 지워도 이 테스트는 <b>통과한다.</b> {@code RiskNotiServiceImpl} 은 그 statement 를 한 트랜잭션에서
     * <b>한 번만</b> 부르기 때문이다({@code createReceiver} 1회 · {@code updateReceiverActivation} 1회이고
     * 존재 확인은 JPA {@code findById} 가 한다) — 1차 캐시에 적재된 "직전 값"이 없어 stale read 가 성립하지 않는다.
     * 선례인 {@code MenuAuthrtServiceImpl.saveMenuAuthorities} 는 검증(쓰기 전)·재조회(쓰기 후)로 <b>두 번</b>
     * 부르므로 거기서는 실제 버그였다. 즉 여기의 {@code flushCache} 는 "두 번 부르는 경로가 생겨도 안전하게"
     * 라는 <b>예방 설정</b>이고, 블랙박스 테스트로는 검증 대상이 아니다.
     * 반면 {@code flush()} 누락(JPA)은 이 테스트가 실제로 잡는다 — 단계 배열이 빈 채로 응답된다.
     */
    @Test
    @DisplayName("5. 🔴 수신자 POST(중복 단계) → 트랜잭션 밖 GET — 중복 제거 + 최신값 (캐시/flush 회귀)")
    void case05_createReceiverThenRefetch() {
        // 🔁 TSK-4250 — 새 계약(userId + regions + riskLvlCds). 사람 정보는 합성 TB_USER 에서 복사된다(R8)
        String yeondong = emdCd("제주시", "연동");
        Map<String, Object> body = receiverBody(USER_1, List.of(region(null, yeondong)),
                List.of("CRIT", "WARN", "CRIT"));

        ResponseEntity<NotiReceiverVO> created = restTemplate.exchange(RECEIVERS_URL, HttpMethod.POST,
                auth.jsonEntity(session, body), NotiReceiverVO.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).isNotNull();
        Long rcvrId = created.getBody().getRcvrId();
        assertThat(rcvrId).isEqualTo(FIRST_GENERATED_RCVR_ID);   // identity RESTART WITH 100 (D11)

        // 🔴 핵심 — POST 응답이 아니라 **트랜잭션 밖 별도 GET** 으로 확인한다.
        //    (POST 응답만 보면 MyBatis 1차 캐시·JPA flush 회귀가 가려질 수 있다)
        NotiReceiverVO refetched = findReceiver(getReceivers("?page=0&size=100"), rcvrId);
        assertThat(refetched.getRiskLvlCds())
                .doesNotContainNull()
                .containsExactlyInAnyOrder("CRIT", "WARN");      // 중복 CRIT 이 제거됐다
        // 사람 정보는 합성 사용자에서 복사·조회된다 (R8 · Q4 최신값)
        assertThat(refetched.getRcvrNm()).isEqualTo("합성사용자1");
        assertThat(refetched.getEml()).isEqualTo("t4250-u-01@test.local");
        assertThat(refetched.getDeptCd()).isEqualTo("PARK");
        assertThat(refetched.getDeptNm()).isEqualTo("주차관리");
        // 지역은 regions 배열 (R6) — emdCd 로 서버가 sggNm 을 채운다 (D13 원소 단위)
        assertThat(refetched.getRegions()).hasSize(1);
        NotiReceiverRegionVO region = refetched.getRegions().get(0);
        assertThat(region.getSggNm()).isEqualTo("제주시");
        assertThat(region.getEmdCd()).isEqualTo(yeondong);
        assertThat(region.getEmdNm()).isEqualTo("연동");
        assertThat(region.getRegionNm()).isEqualTo("제주시 연동");
        assertThat(refetched.getRegionNm()).isEqualTo("제주시 연동");
        assertThat(refetched.getUseYn()).isEqualTo("Y");

        // POST 응답도 같은 값이어야 한다 (flush 누락이면 단계 배열이 비어 내려간다)
        assertThat(created.getBody().getRiskLvlCds())
                .doesNotContainNull()
                .containsExactlyInAnyOrder("CRIT", "WARN");
        assertThat(created.getBody().getRegions()).hasSize(1);
        assertThat(created.getBody().getRegionNm()).isEqualTo("제주시 연동");

        // 목록 총건수도 함께 늘어난다 (시드 0 + 세션 수신자 1 + 방금 만든 1)
        assertThat(getReceivers("?page=0&size=100").totalElements)
                .isEqualTo(SEED_RECEIVER_COUNT + OWN_RECEIVER_COUNT + 1);

        // DB 에 실제로 커밋됐는지 (매핑은 중복 제거돼 2행)
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr_lvl WHERE rcvr_id = " + rcvrId))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr_rgn WHERE rcvr_id = " + rcvrId))
                .isEqualTo(1);
        // 🔴 user_id 가 채워진다 (R8 — 벨 매칭 키 Q3)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM parking.tb_noti_rcvr WHERE rcvr_id = ?", String.class, rcvrId))
                .isEqualTo(USER_1);
    }

    @Test
    @DisplayName("6. 수신자 PATCH activation — 값이 응답·재조회·DB 에 모두 반영되고 응답에 regions 가 유지된다")
    void case06_patchActivation() {
        // 🔁 2026-10-15 — 시드 수신자(종전 1번 김지수)가 사라져 토글 대상을 API 로 직접 만든다.
        //    등록 직후는 use_yn='Y' 라 종전과 같은 출발점이다. (🔁 TSK-4250 — 합성 사용자로 등록)
        long rcvrId = createReceiver(USER_1, List.of(region(null, emdCd("제주시", "연동"))),
                List.of("CRIT", "WARN"));

        ResponseEntity<NotiReceiverVO> patched = restTemplate.exchange(
                RECEIVERS_URL + "/" + rcvrId + "/activation", HttpMethod.PATCH,
                auth.jsonEntity(session, Map.of("useYn", "N")), NotiReceiverVO.class);

        assertThat(patched.getStatusCode().value()).isEqualTo(200);
        assertThat(patched.getBody()).isNotNull();
        // 🔴 flushCache/flush 회귀면 여기서 직전 값 "Y" 가 그대로 나간다
        assertThat(patched.getBody().getUseYn()).isEqualTo("N");
        assertThat(patched.getBody().getRcvrId()).isEqualTo(rcvrId);
        assertThat(patched.getBody().getRiskLvlCds()).containsExactlyInAnyOrder("CRIT", "WARN");
        // 🔴 TSK-4250 Step 5 — 활성화 응답도 등록·수정과 같은 헬퍼로 지역을 붙인다.
        //    빠지면 화면 replaceRow(updated)가 토글 직후 그 행의 지역 칸을 비운다
        assertThat(patched.getBody().getRegions()).extracting(NotiReceiverRegionVO::getRegionNm)
                .containsExactly("제주시 연동");
        assertThat(patched.getBody().getRegionNm()).isEqualTo("제주시 연동");

        // 트랜잭션 밖 재조회
        assertThat(findReceiver(getReceivers("?page=0&size=100"), rcvrId).getUseYn()).isEqualTo("N");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT use_yn FROM parking.tb_noti_rcvr WHERE rcvr_id = ?", String.class, rcvrId).trim())
                .isEqualTo("N");

        // 다시 Y 로 되돌려도 같은 경로가 동작한다
        ResponseEntity<NotiReceiverVO> back = restTemplate.exchange(
                RECEIVERS_URL + "/" + rcvrId + "/activation", HttpMethod.PATCH,
                auth.jsonEntity(session, Map.of("useYn", "Y")), NotiReceiverVO.class);
        assertThat(back.getBody()).isNotNull();
        assertThat(back.getBody().getUseYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("7. 수신자 DELETE — 목록에서만 사라지고 행·단계 매핑은 남는다 (소프트 삭제, D5)")
    void case07_deleteReceiverIsSoft() {
        // 🔁 2026-10-15 — 시드 수신자(종전 2번)가 사라져 삭제 대상을 API 로 직접 만든다 (단계 1개).
        long rcvrId = createReceiver(USER_1, List.of(region(null, emdCd("제주시", "노형동"))),
                List.of("CAUT"));

        ResponseEntity<String> deleted = restTemplate.exchange(
                RECEIVERS_URL + "/" + rcvrId, HttpMethod.DELETE, new HttpEntity<>(session), String.class);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);

        // ① 목록·총건수에서 사라진다 — 남는 것은 세션 수신자뿐이다
        ReceiverPage page = getReceivers("?page=0&size=100");
        assertThat(page.totalElements).isEqualTo(SEED_RECEIVER_COUNT + OWN_RECEIVER_COUNT);
        assertThat(page.content).extracting(NotiReceiverVO::getRcvrId).doesNotContain(rcvrId);

        // ② 행은 del_yn='Y' 로 남고 단계 매핑도 유지된다 (하드 삭제면 둘 다 0행이 된다)
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr WHERE rcvr_id = " + rcvrId
                + " AND del_yn = 'Y'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr_lvl WHERE rcvr_id = " + rcvrId))
                .isEqualTo(1);
        // 🔁 TSK-4250 — 지역 매핑도 남는다 (소프트 삭제는 매핑을 건드리지 않는다)
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr_rgn WHERE rcvr_id = " + rcvrId))
                .isEqualTo(1);

        // ③ 이미 삭제된 수신자를 다시 건드리면 404 (되살리는 경로는 없다)
        assertThat(restTemplate.exchange(RECEIVERS_URL + "/" + rcvrId, HttpMethod.DELETE,
                new HttpEntity<>(session), String.class)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(restTemplate.exchange(RECEIVERS_URL + "/" + rcvrId + "/activation", HttpMethod.PATCH,
                auth.jsonEntity(session, Map.of("useYn", "N")), String.class)
                .getStatusCode().value()).isEqualTo(404);
    }

    /**
     * ⚠️ <b>변이 테스트 실측(2026-08-21)</b> — {@code receiverMap} 의 {@code notNullColumn="risk_lvl_cd"} 를
     * 지워도 이 테스트는 <b>통과한다.</b> 현재 버전(mybatis-spring-boot-starter 3.0.4)의
     * {@code DefaultResultSetHandler} 는 단순타입 {@code <collection>} 에서 컬럼이 NULL 이면 행 값 자체가
     * {@code null} 이 돼 컬렉션에 <b>링크되지 않는다</b> — {@code [null]} 이 만들어지지 않는다.
     * ({@code MenuAuthrtMapper} 의 {@code authrtCds} 로도 같은 변이를 걸어 봤고 결과가 같다.)
     * 그래도 이 테스트는 <b>매핑 0건 수신자가 목록에 정상적으로 나오고 {@code riskLvlCds} 가 빈 배열</b>이라는
     * 계약 자체를 지킨다 — 컬렉션 매핑을 VO 로 바꾸거나 컬럼을 더하는 변경에서 실제로 깨진다.
     */
    @Test
    @DisplayName("8. 🔴 매핑 0건 수신자 — riskLvlCds 가 [] 이고 [null] 이 아니다 (notNullColumn)")
    void case08_receiverWithoutLevelMapping() {
        // 🔴 이 픽스처는 API 로 만들 수 없다 — POST 가 빈 riskLvlCds 를 400 으로 막는다.
        //    JdbcTemplate 으로 TB_NOTI_RCVR 에 매핑 행 없이 1행만 직접 INSERT 한다.
        // 🔁 TSK-4250 — user_id 는 NOT NULL(Step 1)이라 합성 값을 넣는다(TB_USER 행 없음 → 행 단위 폴백 Q4).
        //    지역은 TB_NOTI_RCVR_RGN (900, NULL, NULL) 1행을 함께 심어 "전체 지역" 단언을 유지한다
        //    (지역 0행이면 "지역 없음" — 그 갈래는 case39 가 본다).
        jdbcTemplate.update("""
                INSERT INTO parking.tb_noti_rcvr
                       (rcvr_id, user_id, dept_cd, rcvr_nm, eml, use_yn, del_yn, ntfctn_ch_cd)
                VALUES (?, ?, 'PARK', '매핑없음', 'nolevel@jeju.go.kr', 'Y', 'N', 'EMAIL')""",
                ORPHAN_RCVR_ID, ORPHAN_USER_ID);
        jdbcTemplate.update(
                "INSERT INTO parking.tb_noti_rcvr_rgn (rcvr_id, sgg_nm, emd_cd) VALUES (?, NULL, NULL)",
                ORPHAN_RCVR_ID);
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr_lvl WHERE rcvr_id = " + ORPHAN_RCVR_ID))
                .isZero();

        NotiReceiverVO orphan = findReceiver(getReceivers("?page=0&size=100"), ORPHAN_RCVR_ID);

        // notNullColumn 이 없으면 LEFT JOIN 미매칭이 null 원소로 들어와 [null] 이 된다
        assertThat(orphan.getRiskLvlCds()).isNotNull().doesNotContainNull().isEmpty();
        assertThat(orphan.getRegionNm()).isEqualTo("전체 지역");   // NULL 지역 행 = 전체 지역 (R3)
        // 사용자 행이 없으면 수신자 행 복사본으로 행 단위 폴백한다 (Q4)
        assertThat(orphan.getRcvrNm()).isEqualTo("매핑없음");
        assertThat(orphan.getEml()).isEqualTo("nolevel@jeju.go.kr");
        assertThat(orphan.getDeptCd()).isEqualTo("PARK");

        // 원시 JSON 에서도 [null] 이 아니어야 한다 (프론트 .includes() 오작동 방지)
        String raw = restTemplate.getForObject(RECEIVERS_URL + "?page=0&size=100", String.class);
        assertThat(raw).doesNotContain("[null]");
    }

    // ================================================================= 기준 (Step 6)

    @Test
    @DisplayName("9. 기준 PUT → GET — 3건 값이 일치하고 순서는 CRIT → WARN → CAUT")
    void case09_putCriteriaThenGet() {
        List<RiskCriteriaVO> saved = putCriteria(List.of(
                criteriaItem("WARN", "25.50"),
                criteriaItem("CAUT", "15.00"),
                criteriaItem("CRIT", "35.00")));   // 순서를 섞어 보내도 응답은 sort_seq 순이다

        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(RiskCriteriaVO::getRiskLvlCd).containsExactly("CRIT", "WARN", "CAUT");
        assertThat(saved).extracting(RiskCriteriaVO::getThrshId)
                .containsExactly("THRSH_CRIT", "THRSH_WARN", "THRSH_CAUT");
        assertThat(saved).extracting(vo -> vo.getIncrsRt().stripTrailingZeros())
                .containsExactly(new BigDecimal("35"), new BigDecimal("25.5"), new BigDecimal("15"));

        // 트랜잭션 밖 GET 이 같은 값·같은 순서를 돌려준다 (merge 후 flush 누락이면 옛 값이 나온다)
        List<RiskCriteriaVO> fetched = getCriteria();
        assertThat(fetched).extracting(RiskCriteriaVO::getRiskLvlCd).containsExactly("CRIT", "WARN", "CAUT");
        assertThat(fetched).extracting(vo -> vo.getIncrsRt().stripTrailingZeros())
                .containsExactly(new BigDecimal("35"), new BigDecimal("25.5"), new BigDecimal("15"));

        // DB 도 3행 그대로 (UPSERT 라 행이 늘지 않는다)
        assertThat(count("SELECT count(*) FROM parking.tb_risk_stp_noti_crtr")).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT incrs_rt FROM parking.tb_risk_stp_noti_crtr WHERE thrsh_id = 'THRSH_CRIT'",
                BigDecimal.class)).isEqualByComparingTo("35.00");
    }

    // ================================================================= 입력 검증 · 매핑

    @Test
    @DisplayName("10. 잘못된 입력 — 400 / 404")
    void case10_invalidInputs() {
        // --- 조회 필터 (400) ---
        assertThat(alertStatus("?riskLvlCd=NOPE")).isEqualTo(400);          // 화이트리스트 밖
        assertThat(alertStatus("?from=2026-05-10&to=2026-05-01")).isEqualTo(400);   // from > to
        assertThat(alertStatus("?page=-1")).isEqualTo(400);
        assertThat(alertStatus("?size=0")).isEqualTo(400);
        assertThat(alertStatus("?size=101")).isEqualTo(400);                // 상한 100 (D10)

        // --- 수신자 목록 페이징 (400) ---
        assertThat(restTemplate.getForEntity(RECEIVERS_URL + "?size=101", String.class)
                .getStatusCode().value()).isEqualTo(400);

        // --- 수신자 등록 (400) — 🔁 TSK-4250 R8·R11 갈래 ---
        //     🔴 종전 `NOPE` 부서·`not-an-email` 단언은 새 계약에서 userId 누락 400 으로 **헛통과**하므로 걷었다.
        //        아래 각 줄은 **그 갈래 하나만** 틀리게 하고 나머지는 유효하게 둔다.
        String yeondong = emdCd("제주시", "연동");
        List<Map<String, Object>> okRegions = List.of(region(null, yeondong));
        assertThat(postReceiverStatus(receiverBody(USER_1, okRegions, List.of())))
                .isEqualTo(400);                                             // 빈 riskLvlCds
        assertThat(postReceiverStatus(receiverBody(USER_1, okRegions, List.of("NOPE"))))
                .isEqualTo(400);                                             // 없는 riskLvlCd
        assertThat(postReceiverStatus(receiverBody(null, okRegions, List.of("CRIT"))))
                .isEqualTo(400);                                             // userId 누락
        assertThat(postReceiverStatus(receiverBody("t4250-nobody", okRegions, List.of("CRIT"))))
                .isEqualTo(400);                                             // 없는 userId (R8)
        assertThat(postReceiverStatus(receiverBody(USER_OFF, okRegions, List.of("CRIT"))))
                .isEqualTo(400);                                             // 비활성 userId (R8)
        assertThat(postReceiverStatus(receiverBody(USER_1, List.of(), List.of("CRIT"))))
                .isEqualTo(400);                                             // regions 빈 배열 (R3)
        assertThat(postReceiverStatus(receiverBody(USER_1, null, List.of("CRIT"))))
                .isEqualTo(400);                                             // regions 누락 (R3)
        assertThat(postReceiverStatus(receiverBody(USER_1, List.of(region(null, "99999999")),
                List.of("CRIT")))).isEqualTo(400);                           // 없는 emdCd
        assertThat(postReceiverStatus(receiverBody(USER_1, List.of(region("제주 시", null)),
                List.of("CRIT")))).isEqualTo(400);                           // 없는 sggNm (오타 저장 방지)
        assertThat(postReceiverStatus(receiverBody(USER_1, List.of(regionAll(), region(null, yeondong)),
                List.of("CRIT")))).isEqualTo(400);                           // 전체 지역 + 다른 지역 (R11)
        assertThat(postReceiverStatus(receiverBody(USER_1, List.of(region("제주시", null), region(null, yeondong)),
                List.of("CRIT")))).isEqualTo(400);                           // 시군구 전체 + 그 읍면동 (R11)

        // 실패한 등록이 행을 남기지 않는다 (세션 수신자 1행만 있는 상태 그대로 · 지역 행도 그대로)
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr"))
                .isEqualTo(SEED_RECEIVER_COUNT + OWN_RECEIVER_COUNT);
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr_rgn WHERE rcvr_id <> " + SESSION_RCVR_ID))
                .isZero();

        // --- 활성화 토글 --- (🔁 대상은 실재하는 세션 수신자다 — 시드 1번이 사라졌다)
        assertThat(restTemplate.exchange(RECEIVERS_URL + "/" + SESSION_RCVR_ID + "/activation",
                HttpMethod.PATCH, auth.jsonEntity(session, Map.of("useYn", "X")), String.class)
                .getStatusCode().value()).isEqualTo(400);                    // Y/N 외 값
        assertThat(restTemplate.exchange(RECEIVERS_URL + "/999999/activation", HttpMethod.PATCH,
                auth.jsonEntity(session, Map.of("useYn", "N")), String.class)
                .getStatusCode().value()).isEqualTo(404);                    // 없는 수신자

        // --- 삭제 ---
        assertThat(restTemplate.exchange(RECEIVERS_URL + "/999999", HttpMethod.DELETE,
                new HttpEntity<>(session), String.class)
                .getStatusCode().value()).isEqualTo(404);

        // --- 기준 저장 (400) — 부분 저장 금지 (D7) ---
        assertThat(putCriteriaStatus(List.of(criteriaItem("CRIT", "30.00"), criteriaItem("WARN", "20.00"))))
                .isEqualTo(400);                                             // CAUT 누락
        assertThat(putCriteriaStatus(List.of(criteriaItem("CRIT", "30.00"), criteriaItem("CRIT", "31.00"),
                criteriaItem("WARN", "20.00")))).isEqualTo(400);             // 중복
        assertThat(putCriteriaStatus(List.of(criteriaItem("CRIT", "0"), criteriaItem("WARN", "20.00"),
                criteriaItem("CAUT", "10.00")))).isEqualTo(400);             // 0 이하
        assertThat(putCriteriaStatus(List.of(criteriaItem("CRIT", "100.01"), criteriaItem("WARN", "20.00"),
                criteriaItem("CAUT", "10.00")))).isEqualTo(400);             // 100 초과
        assertThat(putCriteriaStatus(List.of(criteriaItem("CRIT", "30.005"), criteriaItem("WARN", "20.00"),
                criteriaItem("CAUT", "10.00")))).isEqualTo(400);             // 소수 3자리

        // 실패한 저장이 기준을 바꾸지 않는다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT incrs_rt FROM parking.tb_risk_stp_noti_crtr WHERE thrsh_id = 'THRSH_CRIT'",
                BigDecimal.class)).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("11. 엔티티↔테이블 매핑 — JPA 저장이 parking 스키마 실제 컬럼·복합 PK 에 들어간다")
    void case11_entityMapsToRealColumns() {
        String seongsan = emdCd("서귀포시", "성산읍");
        // 🔁 TSK-4250 — 원소의 sggNm('제주시')은 무시되고 서버가 TB_ADMDST 에서 다시 채운다 (D13 원소 단위)
        Map<String, Object> body = receiverBody(USER_1, List.of(region("제주시", seongsan)),
                List.of("WARN", "CAUT"));

        ResponseEntity<NotiReceiverVO> created = restTemplate.exchange(RECEIVERS_URL, HttpMethod.POST,
                auth.jsonEntity(session, body), NotiReceiverVO.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).isNotNull();
        Long rcvrId = created.getBody().getRcvrId();

        // (1) TB_NOTI_RCVR — 스키마 parking 의 실제 컬럼에 값이 들어갔다
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM parking.tb_noti_rcvr WHERE rcvr_id = ?", rcvrId);
        //     🔁 TSK-4250 R8 — 사람 칸은 합성 사용자(t4250-u-01)에서 **복사**된 값이다
        assertThat(row.get("user_id")).isEqualTo(USER_1);
        assertThat(row.get("dept_cd")).isEqualTo("PARK");
        assertThat(row.get("rcvr_nm")).isEqualTo("합성사용자1");
        assertThat(row.get("eml")).isEqualTo("t4250-u-01@test.local");
        //     🔴 DEPRECATED 지역 칸(R4)에는 더 이상 쓰지 않는다 — 지역은 TB_NOTI_RCVR_RGN 이 정본
        assertThat(row.get("emd_cd")).isNull();
        assertThat(row.get("sgg_nm")).isNull();
        assertThat(row.get("ntfctn_ch_cd")).isEqualTo("EMAIL");        // 서버 고정값
        assertThat(((String) row.get("use_yn")).trim()).isEqualTo("Y");  // 엔티티 초기값 (NOT NULL)
        assertThat(((String) row.get("del_yn")).trim()).isEqualTo("N");  // 엔티티 초기값 (NOT NULL)
        // deprecated 컬럼은 엔티티가 매핑하지 않으므로 NULL 로 남는다
        assertThat(row.get("risk_lvl_cd")).isNull();

        // (2) TB_NOTI_RCVR_LVL — 복합 PK (rcvr_id, risk_lvl_cd) 로 2행
        List<String> levels = jdbcTemplate.queryForList(
                "SELECT risk_lvl_cd FROM parking.tb_noti_rcvr_lvl WHERE rcvr_id = ? ORDER BY risk_lvl_cd",
                String.class, rcvrId);
        assertThat(levels).containsExactly("CAUT", "WARN");
        // 같은 복합 PK 를 다시 넣으면 DB 가 막는다 (중복 제거가 필요한 이유)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO parking.tb_noti_rcvr_lvl (rcvr_id, risk_lvl_cd) VALUES (?, 'WARN')", rcvrId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // (2-1) 🔁 TSK-4250 — TB_NOTI_RCVR_RGN 1행. 요청의 '제주시' 를 버리고 '서귀포시' 로 재설정 (D13)
        List<Map<String, Object>> rgnRows = jdbcTemplate.queryForList(
                "SELECT sgg_nm, emd_cd FROM parking.tb_noti_rcvr_rgn WHERE rcvr_id = ?", rcvrId);
        assertThat(rgnRows).hasSize(1);
        assertThat(rgnRows.get(0).get("sgg_nm")).isEqualTo("서귀포시");
        assertThat(rgnRows.get(0).get("emd_cd")).isEqualTo(seongsan);

        // (3) TB_RISK_STP_NOTI_CRTR — 할당형 PK(thrsh_id) UPSERT 가 실제 컬럼에 반영된다
        putCriteria(List.of(criteriaItem("CRIT", "33.00"), criteriaItem("WARN", "22.00"),
                criteriaItem("CAUT", "11.00")));
        Map<String, Object> crtr = jdbcTemplate.queryForMap(
                "SELECT * FROM parking.tb_risk_stp_noti_crtr WHERE thrsh_id = 'THRSH_CRIT'");
        assertThat(crtr.get("risk_lvl_cd")).isEqualTo("CRIT");
        assertThat((BigDecimal) crtr.get("incrs_rt")).isEqualByComparingTo("33.00");
        assertThat(crtr.get("mdfr_id")).isEqualTo("system");
        assertThat(crtr.get("mdfcn_dt")).isNotNull();
    }

    // ================================================================= 설계 결정 불변식

    @Test
    @DisplayName("12. 알림 단위 = 읍면동 (D12) — regionNm 에 시군구명이 없고 emd_cd NULL 은 DB 가 막는다")
    void case12_alertUnitIsAlwaysEmd() {
        AlertPage all = getAlerts("?size=100");

        // regionNm 은 읍면동명 고정이다 — '제주시'·'서귀포시' 같은 시군구명이 하나도 없어야 한다
        assertThat(all.content).extracting(RiskAlertVO::getRegionNm)
                .doesNotContain("제주시", "서귀포시");
        assertThat(all.content).allSatisfy(row -> {
            assertThat(row.getRegionNm()).isEqualTo(row.getEmdNm());
            assertThat(row.getEmdCd()).isNotNull();
            assertThat(row.getSggNm()).isIn("제주시", "서귀포시");   // 시군구는 조인으로 따로 내려간다
        });

        // emd_cd NULL 이력은 DB 제약(NOT NULL)이 막는다 — '지역=전체' 이력 행이 존재할 수 없다
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO parking.tb_risk_stp_noti_hstry
                       (risk_lvl_cd, emd_cd, cvlcpt_cnt, incrs_rt, noti_ctt, incrs_rt_thrsh,
                        ocrn_dt, prcs_stts_cd)
                VALUES ('WARN', NULL, 10, 25.00, '테스트 민원 25% 증가', 20.00, now(), 'OPEN')"""))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("SELECT count(*) FROM parking.tb_risk_stp_noti_hstry"))
                .isEqualTo(SEED_ALERT_COUNT);
    }

    @Test
    @DisplayName("13. notiCtt 저장값 (D6) — 저장 문구를 그대로 내리고 소수부를 보존한다")
    void case13_notiCttIsStoredValue() {
        AlertPage all = getAlerts("?size=100");

        // (1) 응답 문구 == DB 에 저장된 문구 (조회에서 재조립하지 않는다)
        Map<Long, String> stored = jdbcTemplate.queryForList(
                        "SELECT noti_id, noti_ctt FROM parking.tb_risk_stp_noti_hstry").stream()
                .collect(Collectors.toMap(r -> ((Number) r.get("noti_id")).longValue(),
                        r -> (String) r.get("noti_ctt")));
        assertThat(all.content).allSatisfy(row ->
                assertThat(row.getNotiCtt()).isEqualTo(stored.get(row.getNotiId())));

        // (2) '{읍면동} 민원 {증가율}% 증가' 꼴이고 마침표가 없다
        assertThat(all.content).allSatisfy(row -> {
            assertThat(row.getNotiCtt()).matches("^\\S+ 민원 \\d+(\\.\\d+)?% 증가$");
            assertThat(row.getNotiCtt()).doesNotContain(".%");   // rtrim 누락이면 '22.% 증가' 가 된다
        });

        // (3) 45.00 → '45%' (정수는 소수부 없음)
        RiskAlertVO crit = all.content.stream()
                .filter(row -> "연동".equals(row.getEmdNm()) && "CRIT".equals(row.getRiskLvlCd()))
                .findFirst().orElseThrow();
        assertThat(crit.getNotiCtt()).isEqualTo("연동 민원 45% 증가");

        // (4) 26.50 → '26.5%' (소수부는 보존)
        RiskAlertVO fraction = all.content.stream()
                .filter(row -> row.getIncrsRt() != null && row.getIncrsRt() == 26.5)
                .findFirst().orElseThrow();
        assertThat(fraction.getNotiCtt()).isEqualTo("노형동 민원 26.5% 증가");
    }

    @Test
    @DisplayName("14. 기준 스냅샷 불변 (D7) — 기준을 바꿔도 과거 이력의 incrsRtThrsh 는 그대로")
    void case14_criteriaSnapshotIsImmutable() {
        AlertPage before = getAlerts("?size=100");
        // 모든 행이 판정 조건(증가율 >= 판정 당시 기준)을 만족한다
        assertThat(before.content).allSatisfy(row -> {
            assertThat(row.getIncrsRtThrsh()).isNotNull();
            assertThat(row.getIncrsRt()).isGreaterThanOrEqualTo(row.getIncrsRtThrsh());
        });
        assertThat(before.content).filteredOn(row -> "CRIT".equals(row.getRiskLvlCd()))
                .extracting(RiskAlertVO::getIncrsRtThrsh).containsOnly(30.0);

        // CRIT 기준을 30 → 35 로 바꾼다
        putCriteria(List.of(criteriaItem("CRIT", "35.00"), criteriaItem("WARN", "20.00"),
                criteriaItem("CAUT", "10.00")));
        assertThat(getCriteria().get(0).getIncrsRt()).isEqualByComparingTo("35.00");

        // 🔴 과거 이력의 스냅샷은 30 그대로다 (소급 수정하면 판정 근거가 사라진다)
        AlertPage after = getAlerts("?size=100");
        assertThat(after.content).filteredOn(row -> "CRIT".equals(row.getRiskLvlCd()))
                .extracting(RiskAlertVO::getIncrsRtThrsh).containsOnly(30.0);
        assertThat(after.content).allSatisfy(row ->
                assertThat(row.getIncrsRt()).isGreaterThanOrEqualTo(row.getIncrsRtThrsh()));

        assertThat(count("SELECT count(*) FROM parking.tb_risk_stp_noti_hstry WHERE incrs_rt_thrsh = 30.00"))
                .isEqualTo(4);
    }

    /**
     * 🔁 <b>TSK-4202 Step 8 이 TSK-4028 D13("조회는 수신자와 무관") 을 폐기했다가,
     * TSK-4250 R13 이 조회 화면에 한해 되돌렸다</b> — 이 케이스의 ③ 이 <b>다시 뒤집혔다.</b>
     * Step 8 시절에는 "내 수신자 행이 사라지면 0건" 이었고, 이제는 다시 <b>"수신자를 전원 삭제해도 18건"</b> 이다.
     * 수신자 기준은 벨·읽음에만 남는다.
     *
     * <p>🔴 반면 ①②(알림 1건 = 1행 · 응답에 수신자 필드 없음)는 <b>그대로 유효하다</b> — 조건을
     * {@code JOIN} 이 아니라 {@code EXISTS} 로 건 이유가 바로 그것이다(종전 D11 — 한 사람의 수신자 행이
     * 여러 개면 조인은 같은 알림을 여러 번 돌려준다. 🔁 TSK-4250 R9 로 활성 행은 사람당 1개가 됐지만
     * {@code EXISTS} 는 방어선으로 남긴다).
     */
    @Test
    @DisplayName("15. 🔁 R13 — notiId 는 유일하고, 수신자를 전원 삭제해도 조회 화면은 18건 그대로다")
    void case15_alertQueryIgnoresReceivers() {
        // ① notiId 가 전부 유일하다 — 조인이 끼면 행이 복제된다
        AlertPage all = getAlerts("?size=100");
        assertThat(all.content).hasSize(SEED_ALERT_COUNT);
        assertThat(ids(all)).hasSize(SEED_ALERT_COUNT);

        // ② 응답에 수신자 필드가 없다 — 거르기만 할 뿐 수신자를 내려 주지는 않는다
        String raw = rawAlerts("?size=100");
        assertThat(raw).doesNotContain("rcvrId").doesNotContain("rcvrNm").doesNotContain("rcvr_id");

        // ③ 🔁 R13 — 수신자를 전원 삭제(del_yn='Y')해 내 수신자 해석이 빈 목록이 돼도 조회 화면은 그대로다
        jdbcTemplate.update("UPDATE parking.tb_noti_rcvr SET del_yn = 'Y'");
        assertThat(getReceivers("?page=0&size=100").totalElements).isZero();

        AlertPage afterWipe = getAlerts("?size=100");
        assertThat(afterWipe.totalElements).as("🔴 조회 화면은 수신자 무관 — 전 알림").isEqualTo(SEED_ALERT_COUNT);
        assertThat(ids(afterWipe)).containsExactlyInAnyOrderElementsOf(ids(all));
        assertThat(afterWipe.summary.getTotalCnt()).isEqualTo(SEED_ALERT_COUNT);
        assertThat(afterWipe.summary.getCvlcptSumCnt()).isEqualTo(countCvlcpt(null));
        // 반대로 벨은 수신자 기준이라 비수신자에게 0건이다(TSK-4202 D10 이 남은 자리)
        assertThat(getDaily().getTotalCnt()).isZero();
    }

    @Test
    @DisplayName("16. 소프트 삭제 (D5) — 목록에서 사라져도 tb_noti_rcvr 행이 del_yn='Y' 로 남는다")
    void case16_softDeleteKeepsRow() {
        // 🔁 2026-10-15 — 시드 수신자(종전 3번)가 사라져 대상을 API 로 직접 만든다 (단계 1개).
        long rcvrId = createReceiver(USER_1, List.of(region(null, emdCd("제주시", "삼도일동"))),
                List.of("WARN"));

        restTemplate.exchange(RECEIVERS_URL + "/" + rcvrId, HttpMethod.DELETE,
                new HttpEntity<>(session), String.class);

        // ① 목록에서 사라진다
        assertThat(getReceivers("?page=0&size=100").content)
                .extracting(NotiReceiverVO::getRcvrId).doesNotContain(rcvrId);

        // ② 🔴 행은 남는다 — 하드 삭제로 되돌리면 이 카운트가 0 이 돼 FAIL 한다
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr WHERE rcvr_id = " + rcvrId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT del_yn FROM parking.tb_noti_rcvr WHERE rcvr_id = ?", String.class, rcvrId).trim())
                .isEqualTo("Y");
        // 전체 행수는 세션 수신자 + 방금 것 그대로 (숨겼을 뿐 지우지 않았다)
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr"))
                .isEqualTo(SEED_RECEIVER_COUNT + OWN_RECEIVER_COUNT + 1);
        // 단계 매핑도 CASCADE 로 날아가지 않았다
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr_lvl WHERE rcvr_id = " + rcvrId))
                .isEqualTo(1);
        // 🔴 전체 단계 매핑은 이 1행뿐이다 — 세션 수신자는 단계 매핑을 갖지 않는다(plantSessionReceiver)
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr_lvl")).isEqualTo(1);
    }

    /**
     * 🔴 <b>알림 건수 4종과 민원 수 카드는 원천이 다르다</b> (TSK-4069 Step 9).
     * {@code totalCnt}·{@code critCnt}·{@code warnCnt}·{@code cautCnt} 는 <b>이력(사건) 기준</b>이고,
     * {@code cvlcptSumCnt} 는 <b>{@code parking.tb_cvlcpt} 기준</b>이다 — 이력 합은 판정 배치가
     * 기준을 넘은 읍면동만 남기므로 "알림이 난 지역의 민원"만 세어 카드 부제(선택 기간 합계)와 어긋난다.
     * 그래서 <b>단계 필터는 민원 수 카드를 바꾸지 않는다</b>(민원 raw 에 위험단계가 없다).
     */
    @Test
    @DisplayName("17. 요약 원천 (D13 + TSK-4069) — 건수 4종은 이력 기준, cvlcptSumCnt 는 민원 테이블 기준")
    void case17_summaryIsEventBased() {
        long dbCount = count("SELECT count(*) FROM parking.tb_risk_stp_noti_hstry");

        AlertPage all = getAlerts("?size=100");
        assertThat(all.summary.getTotalCnt()).isEqualTo(all.totalElements).isEqualTo(dbCount);
        // 🔴 민원 수 카드 = 민원 테이블 총합. 옛 SQL(이력 합)로 되돌리면 여기서 깨진다
        assertThat(all.summary.getCvlcptSumCnt()).isEqualTo(countCvlcpt(null));

        // 필터를 걸어도 **건수 3종**은 같은 집합 위에서 움직인다
        AlertPage warn = getAlerts("?riskLvlCd=WARN&size=100");
        long warnCount = count("SELECT count(*) FROM parking.tb_risk_stp_noti_hstry WHERE risk_lvl_cd='WARN'");
        assertThat(warn.summary.getTotalCnt()).isEqualTo(warn.totalElements).isEqualTo(warnCount);

        // 🔴 뒤집힌 단언 — 단계 필터는 민원 수 카드에 **반영되지 않는다**(의도된 동작).
        //    민원 raw 에는 위험단계가 없어 서브쿼리에 riskLvlCd 를 걸지 않기 때문이다.
        assertThat(warn.summary.getCvlcptSumCnt())
                .isEqualTo(all.summary.getCvlcptSumCnt())
                .isEqualTo(countCvlcpt(null));

        // 🔴 분리된 단언 — "목록 합 == 카드" 는 더 이상 성립하지 않는다(목록은 알림만, 카드는 민원 전체).
        //    목록 쪽 불변식(1행 = 사건 1건, cvlcptCnt 는 이력 값 그대로)만 따로 본다.
        assertThat(warn.content.stream().mapToLong(RiskAlertVO::getCvlcptCnt).sum())
                .isEqualTo(sumCvlcpt("h.risk_lvl_cd = 'WARN'"));   // 8건 전부 한 페이지에 담긴 경우

        // 요약은 페이지가 아니라 필터 기준이라 페이지를 넘겨도 변하지 않는다
        AlertPage warnPage1 = getAlerts("?riskLvlCd=WARN&page=1&size=5");
        assertThat(warnPage1.summary.getTotalCnt()).isEqualTo(warn.summary.getTotalCnt());
        assertThat(warnPage1.summary.getCvlcptSumCnt()).isEqualTo(warn.summary.getCvlcptSumCnt());
        assertThat(warnPage1.totalElements).isEqualTo(warnCount);
        assertThat(warnPage1.content).hasSize(3);

        // 0건 필터에서도 카드가 0 으로 내려온다 (요약 쿼리는 집계라 항상 1행이다).
        // ⚠️ 2020-01-01~02 는 **민원 테이블에도 행이 없는** 구간이라야 한다(대조까지 함께 건다).
        //    🔴 이 날짜는 **데이터 의존이 아니다** — 시드가 날짜째 재적재돼도(2026-10-02 까지 두 번
        //    일어났다) 2020년에 행이 생길 일은 없어서 일부러 멀리 잡은 탐침이다.
        //    🔴 그래서 여기엔 구체적인 실데이터 범위를 적지 않는다 — 적으면 곧 낡는다
        //    (종전 주석 `실데이터는 2025-04-25~2025-12-31` 이 그렇게 낡았다).
        //    지금 범위가 궁금하면 `./local.sh baseline` 을 돌린다.
        AlertPage empty = getAlerts("?from=2020-01-01&to=2020-01-02");
        assertThat(empty.totalElements).isZero();
        assertThat(empty.content).isEmpty();
        assertThat(empty.summary.getTotalCnt()).isZero();
        assertThat(countCvlcpt("c.ocrn_dt >= TIMESTAMP '2020-01-01 00:00:00' "
                + "AND c.ocrn_dt <= TIMESTAMP '2020-01-02 23:59:59'")).isZero();
        assertThat(empty.summary.getCvlcptSumCnt()).isZero();
    }

    /**
     * 🔁 <b>종전 "발송기록 미사용 — 0행이 정상"(D13) 은 폐기됐다</b>(TSK-4202 Step 8).
     * 그 표는 이제 백필 {@value #OWN_MAPPING_COUNT} 행을 담은 <b>조회 대상 표</b>다.
     *
     * <p>🔁 <b>TSK-4250 R9 — 종전 단언("내 수신자 행이 2개여도 알림은 1건", D11)은 성립하지 않는다.</b>
     * 같은 {@code user_id} 의 두 번째 <b>활성</b> 행은 {@code uq_tb_noti_rcvr_user} 가 막는다. 그래서 둘로 바꿨다:
     * <ol>
     *   <li>같은 {@code user_id} 두 번째 활성 INSERT → {@link DuplicateKeyException}({@code uq_tb_noti_rcvr_user})</li>
     *   <li>{@code del_yn='Y'} 인 <b>내 옛 행</b>(부분 인덱스 밖이라 공존 가능)의 매핑은 읽음·벨에서
     *       안 보인다 — 수신자 해석이 {@code del_yn='N'} 만 보기 때문이다. 🔁 R13 — 조회 화면은 수신자 무관이라
     *       그 알림도 그대로 보인다</li>
     * </ol>
     * 조회 SQL 의 {@code EXISTS}(행 복제 방지)는 방어선으로 남긴다 — 걷어내지 않는다.
     */
    @Test
    @DisplayName("18. 🔁 R9 — 같은 user_id 두 번째 활성 행은 DB 가 막고, 삭제된 내 옛 행의 매핑은 읽음·벨에 안 보인다 (R13 조회 화면은 그대로)")
    void case18_oneActiveRowPerUserAndDeletedRowIsInvisible() {
        assertThat(count("SELECT count(*) FROM parking.tb_noti_hstry_rcvr"))
                .as("백필 매핑이 깔려 있어야 조회가 성립한다 (0 이면 @BeforeEach 가 되살리지 못한 것이다)")
                .isEqualTo(OWN_MAPPING_COUNT);

        AlertPage all = getAlerts("?size=100");
        assertThat(all.totalElements).isEqualTo(SEED_ALERT_COUNT);
        assertThat(ids(all)).hasSize(SEED_ALERT_COUNT);

        // ① 🔴 같은 사람(mock-user)의 두 번째 **활성** 행 → 부분 유니크 인덱스 위반
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO parking.tb_noti_rcvr (user_id, rcvr_nm, eml, use_yn, del_yn, ntfctn_ch_cd)
                VALUES (?, '두번째활성행', 'dup@example.com', 'Y', 'N', 'EMAIL')""",
                IntegrationLoginSupport.MOCK_ID))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_tb_noti_rcvr_user");

        // ② 삭제된(del_yn='Y') 내 옛 행 — 부분 인덱스(WHERE del_yn='N') 밖이라 공존한다
        Long oldRcvrId = jdbcTemplate.queryForObject("""
                INSERT INTO parking.tb_noti_rcvr (user_id, rcvr_nm, eml, use_yn, del_yn, ntfctn_ch_cd)
                VALUES (?, '삭제된옛행', 'old@example.com', 'Y', 'Y', 'EMAIL')
                RETURNING rcvr_id""", Long.class, IntegrationLoginSupport.MOCK_ID);
        jdbcTemplate.update(
                "INSERT INTO parking.tb_noti_rcvr_rgn (rcvr_id, sgg_nm, emd_cd) VALUES (?, NULL, NULL)", oldRcvrId);

        // 알림 1건을 **옛 행에만** 매핑한다 — 활성 세션 수신자 매핑은 걷는다
        Long notiId = jdbcTemplate.queryForObject(
                "SELECT noti_id FROM parking.tb_risk_stp_noti_hstry ORDER BY noti_id LIMIT 1", Long.class);
        jdbcTemplate.update("DELETE FROM parking.tb_noti_hstry_rcvr WHERE noti_id = ? AND rcvr_id = ?",
                notiId, SESSION_RCVR_ID);
        jdbcTemplate.update("INSERT INTO parking.tb_noti_hstry_rcvr (noti_id, rcvr_id) VALUES (?, ?)",
                notiId, oldRcvrId);

        // 조회 — 🔁 R13: 조회 화면은 수신자 무관이라 그 알림도 그대로 보인다(18건 · 중복 없음)
        AlertPage after = getAlerts("?size=100");
        assertThat(after.totalElements).isEqualTo(SEED_ALERT_COUNT);
        assertThat(ids(after)).contains(notiId).doesNotHaveDuplicates();

        // 읽음 — 내 활성 매핑이 아니므로 404 (남의 알림과 같은 문구·같은 상태)
        assertThat(restTemplate.exchange(ALERTS_URL + "/" + notiId + "/read", HttpMethod.PATCH,
                new HttpEntity<>(session), String.class).getStatusCode().value()).isEqualTo(404);

        // 벨 — 어제 알림 1건을 옛 행에만 매핑하면 벨에 없고, 활성 행에 매핑하면 뜬다(대조군)
        //   🔴 날짜를 하드코딩하지 않는다 — 벨 기준일은 Asia/Seoul 전일이다. @AfterEach restoreSeed() 가
        //      이력 표를 전량 비우므로 이 합성 알림은 따로 지우지 않아도 된다.
        LocalDateTime yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).atTime(10, 0);
        Long bellNotiId = jdbcTemplate.queryForObject("""
                INSERT INTO parking.tb_risk_stp_noti_hstry
                       (risk_lvl_cd, emd_cd, cvlcpt_cnt, incrs_rt, noti_ctt, ocrn_dt, prcs_stts_cd)
                VALUES ('WARN', ?, 30, 25.00, 'TSK-4250 case18 합성 알림', ?, 'OPEN')
                RETURNING noti_id""", Long.class, emdCd("제주시", "연동"), yesterday);
        jdbcTemplate.update("INSERT INTO parking.tb_noti_hstry_rcvr (noti_id, rcvr_id) VALUES (?, ?)",
                bellNotiId, oldRcvrId);
        assertThat(getDaily().getItems()).extracting(RiskAlertVO::getNotiId).doesNotContain(bellNotiId);

        jdbcTemplate.update("INSERT INTO parking.tb_noti_hstry_rcvr (noti_id, rcvr_id) VALUES (?, ?)",
                bellNotiId, SESSION_RCVR_ID);
        assertThat(getDaily().getItems()).extracting(RiskAlertVO::getNotiId).containsOnlyOnce(bellNotiId);
    }

    @Test
    @DisplayName("19. 읽음·확인 상태 (D17) — 3단계가 모두 있고, 확인은 읽음을 전제한다 (CHECK 제약)")
    void case19_readAndConfirmStates() {
        AlertPage all = getAlerts("?size=100");

        // 응답에 4필드가 있다
        String raw = rawAlerts("?size=100");
        assertThat(raw).contains("\"prslYn\"").contains("\"prslDt\"")
                .contains("\"idntyYn\"").contains("\"idntyDt\"");

        // 3단계(미읽음 6 · 읽음 6 · 확인 6)가 모두 존재한다
        long unread = all.content.stream().filter(r -> "N".equals(r.getPrslYn())).count();
        long readOnly = all.content.stream()
                .filter(r -> "Y".equals(r.getPrslYn()) && "N".equals(r.getIdntyYn())).count();
        long confirmed = all.content.stream().filter(r -> "Y".equals(r.getIdntyYn())).count();
        assertThat(unread).isEqualTo(6);
        assertThat(readOnly).isEqualTo(6);
        assertThat(confirmed).isEqualTo(6);
        assertThat(unread + readOnly + confirmed).isEqualTo(SEED_ALERT_COUNT);

        // idnty_yn='Y' 인 행은 전부 prsl_yn='Y' 이고 일시가 채워져 있다
        assertThat(all.content).filteredOn(r -> "Y".equals(r.getIdntyYn())).allSatisfy(r -> {
            assertThat(r.getPrslYn()).isEqualTo("Y");
            assertThat(r.getPrslDt()).isNotNull();
            assertThat(r.getIdntyDt()).isNotNull().isAfter(r.getPrslDt());
        });
        // 미읽음 행은 일시가 비어 있다
        assertThat(all.content).filteredOn(r -> "N".equals(r.getPrslYn())).allSatisfy(r -> {
            assertThat(r.getPrslDt()).isNull();
            assertThat(r.getIdntyYn()).isEqualTo("N");
            assertThat(r.getIdntyDt()).isNull();
        });

        // 🔴 확인만 켜고 읽음을 끄면 DB CHECK 제약(ck_risk_hstry_read_cnfm)이 막는다
        Long unreadId = jdbcTemplate.queryForObject(
                "SELECT noti_id FROM parking.tb_risk_stp_noti_hstry WHERE prsl_yn = 'N' "
                        + "ORDER BY noti_id LIMIT 1", Long.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE parking.tb_risk_stp_noti_hstry SET idnty_yn = 'Y', prsl_yn = 'N' WHERE noti_id = ?",
                unreadId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================ 최소 민원수 하한 (TSK-4092 Step 5)

    @Test
    @DisplayName("20. GET /api/risk-criteria — 3건 전부 minCvlcptCnt 가 null 이 아니다")
    void case20_criteriaExposeMinCvlcptCnt() {
        List<RiskCriteriaVO> criteria = getCriteria();

        assertThat(criteria).hasSize(3);
        assertThat(criteria).extracting(RiskCriteriaVO::getMinCvlcptCnt).doesNotContainNull();
    }

    @Test
    @DisplayName("21. minCvlcptCnt 가 시드값과 일치한다 (리터럴이 아니라 시드 상수로 대조)")
    void case21_minCvlcptCntMatchesSeed() {
        assertThat(getCriteria()).extracting(RiskCriteriaVO::getMinCvlcptCnt)
                .containsOnly(SEED_MIN_CVLCPT_CNT);
        // API 값과 DB 값이 같은지도 본다 — 매퍼가 컬럼을 빠뜨리면 여기서 갈린다
        assertThat(getCriteria()).extracting(RiskCriteriaVO::getMinCvlcptCnt)
                .containsExactlyElementsOf(jdbcTemplate.queryForList(
                        "SELECT c.min_cvlcpt_cnt FROM parking.tb_risk_stp_noti_crtr c "
                                + "LEFT JOIN parking.tb_com_cd c2 "
                                + "  ON c2.group_cd='RISK_LVL_CD' AND c2.cd_vl = c.risk_lvl_cd "
                                + "ORDER BY c2.sort_seq", Integer.class));
    }

    @Test
    @DisplayName("22. PUT 왕복 — 3건을 5로 저장하면 응답·재조회·DB 가 모두 5")
    void case22_putMinCvlcptCntRoundTrip() {
        int target = SEED_MIN_CVLCPT_CNT / 2;   // 시드(10)와 다른 값이면 충분하다

        List<RiskCriteriaVO> saved = putCriteria(criteriaAll(target));
        assertThat(saved).extracting(RiskCriteriaVO::getMinCvlcptCnt).containsOnly(target);

        // 트랜잭션 밖 재조회 (merge 후 flush 누락이면 옛 값이 나온다)
        assertThat(getCriteria()).extracting(RiskCriteriaVO::getMinCvlcptCnt).containsOnly(target);
        assertThat(jdbcTemplate.queryForList(
                "SELECT min_cvlcpt_cnt FROM parking.tb_risk_stp_noti_crtr", Integer.class))
                .containsOnly(target);
        // UPSERT 라 행이 늘지 않는다
        assertThat(count("SELECT count(*) FROM parking.tb_risk_stp_noti_crtr")).isEqualTo(3);
    }

    @Test
    @DisplayName("23. 하한 저장이 incrsRt 를 덮지 않는다 — 한 필드 저장이 다른 필드를 밀어내지 않는다")
    void case23_putMinCvlcptCntKeepsIncrsRt() {
        List<BigDecimal> before = getCriteria().stream()
                .map(vo -> vo.getIncrsRt().stripTrailingZeros())
                .collect(Collectors.toList());

        putCriteria(criteriaAll(SEED_MIN_CVLCPT_CNT / 2));

        assertThat(getCriteria()).extracting(vo -> vo.getIncrsRt().stripTrailingZeros())
                .containsExactlyElementsOf(before);
    }

    @Test
    @DisplayName("24. minCvlcptCnt = -1 → 400 (음수는 하한이 될 수 없다)")
    void case24_negativeMinCvlcptCntRejected() {
        assertThat(putCriteriaStatus(criteriaAll(-1))).isEqualTo(400);
        // 실패한 저장이 값을 바꾸지 않는다
        assertThat(getCriteria()).extracting(RiskCriteriaVO::getMinCvlcptCnt)
                .containsOnly(SEED_MIN_CVLCPT_CNT);
    }

    @Test
    @DisplayName("25. minCvlcptCnt = 1001 → 400 (상한 1000 — 오타로 알림이 영구 침묵하는 것을 막는다)")
    void case25_tooLargeMinCvlcptCntRejected() {
        assertThat(putCriteriaStatus(criteriaAll(1001))).isEqualTo(400);
        assertThat(putCriteriaStatus(criteriaAll(1000))).isEqualTo(200);   // 경계값은 통과한다
    }

    @Test
    @DisplayName("26. 🔴 minCvlcptCnt 키 누락 → 400 (NOT NULL 위반 500 이 아니다)")
    void case26_missingMinCvlcptCntIsBadRequest() {
        int status = putCriteriaStatus(List.of(
                criteriaItemWithoutMin("CRIT", "30.00"),
                criteriaItemWithoutMin("WARN", "20.00"),
                criteriaItemWithoutMin("CAUT", "10.00")));

        assertThat(status).isEqualTo(400);   // @NotNull 이 없으면 여기서 500 이 난다
        assertThat(getCriteria()).extracting(RiskCriteriaVO::getMinCvlcptCnt)
                .containsOnly(SEED_MIN_CVLCPT_CNT);
    }

    @Test
    @DisplayName("27. 단계 하나 누락 → 400 (하한 항목이 늘어도 부분 저장 금지가 유지된다)")
    void case27_partialCriteriaStillRejected() {
        assertThat(putCriteriaStatus(List.of(
                criteriaItem("CRIT", "30.00", SEED_MIN_CVLCPT_CNT),
                criteriaItem("WARN", "20.00", SEED_MIN_CVLCPT_CNT)))).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM parking.tb_risk_stp_noti_crtr")).isEqualTo(3);
    }

    @Test
    @DisplayName("28. UPSERT 후에도 mdfr_id·mdfcn_dt 가 NULL 이 아니다 (merge 가 기존 값을 덮지 않았다)")
    void case28_upsertKeepsAuditColumns() {
        putCriteria(criteriaAll(SEED_MIN_CVLCPT_CNT / 2));

        assertThat(count("SELECT count(*) FROM parking.tb_risk_stp_noti_crtr "
                + "WHERE mdfr_id IS NULL OR mdfcn_dt IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM parking.tb_risk_stp_noti_crtr "
                + "WHERE mdfr_id = 'system'")).isEqualTo(3);
    }

    @Test
    @DisplayName("29. 회귀 — 하한 컬럼이 늘어도 GET /api/risk-alerts 응답 형태는 그대로다")
    void case29_alertResponseShapeUnchanged() {
        AlertPage page = getAlerts("");

        assertThat(page.totalElements).isEqualTo(SEED_ALERT_COUNT);
        assertThat(page.page).isZero();
        assertThat(page.size).isEqualTo(10);
        assertThat(page.totalPages).isEqualTo(2);
        assertThat(page.content).hasSize(10);
        assertThat(page.summary.getTotalCnt()).isEqualTo(SEED_ALERT_COUNT);
        assertThat(page.content).allSatisfy(row -> {
            assertThat(row.getNotiId()).isNotNull();
            assertThat(row.getRiskLvlCd()).isNotNull();
            assertThat(row.getIncrsRtThrsh()).isNotNull();
        });
    }

    @Test
    @DisplayName("30. 🔴 GET 응답에 bscMinCvlcptCnt 가 있고 시드 직후에는 minCvlcptCnt 와 같다")
    void case30_bscMinCvlcptCntPresentAndEqualAtSeed() {
        List<RiskCriteriaVO> criteria = getCriteria();

        assertThat(criteria).extracting(RiskCriteriaVO::getBscMinCvlcptCnt).doesNotContainNull();
        assertThat(criteria).allSatisfy(vo ->
                assertThat(vo.getBscMinCvlcptCnt()).isEqualTo(vo.getMinCvlcptCnt()));
        assertThat(criteria).extracting(RiskCriteriaVO::getBscMinCvlcptCnt)
                .containsOnly(SEED_MIN_CVLCPT_CNT);
    }

    @Test
    @DisplayName("31. 🔴 minCvlcptCnt 를 바꿔도 bscMinCvlcptCnt 는 불변 — 응답·재조회·DB 세 곳 모두 기본값")
    void case31_bscMinCvlcptCntIsNotUpdatable() {
        int target = SEED_MIN_CVLCPT_CNT / 2;

        // ① 응답
        List<RiskCriteriaVO> saved = putCriteria(criteriaAll(target));
        assertThat(saved).extracting(RiskCriteriaVO::getMinCvlcptCnt).containsOnly(target);
        assertThat(saved).extracting(RiskCriteriaVO::getBscMinCvlcptCnt)
                .containsOnly(SEED_MIN_CVLCPT_CNT);

        // ② 재조회
        assertThat(getCriteria()).extracting(RiskCriteriaVO::getBscMinCvlcptCnt)
                .containsOnly(SEED_MIN_CVLCPT_CNT);

        // ③ DB 직접 조회 — updatable=false 가 실제로 UPDATE 문에서 컬럼을 빼는지 보는 유일한 경로
        assertThat(bscMinCvlcptCntsFromDb()).containsOnly(SEED_MIN_CVLCPT_CNT);
    }

    @Test
    @DisplayName("32. PUT 을 연속 3회 해도 bsc_min_cvlcpt_cnt 가 NULL 이 되지 않는다 (merge 의 null 이 새지 않는다)")
    void case32_repeatedPutKeepsBscMinCvlcptCnt() {
        putCriteria(criteriaAll(SEED_MIN_CVLCPT_CNT / 2));
        putCriteria(criteriaAll(SEED_MIN_CVLCPT_CNT + 1));
        putCriteria(criteriaAll(0));   // 0 = 하한 없음도 유효한 값이다

        assertThat(count("SELECT count(*) FROM parking.tb_risk_stp_noti_crtr "
                + "WHERE bsc_min_cvlcpt_cnt IS NULL")).isZero();
        assertThat(bscMinCvlcptCntsFromDb()).containsOnly(SEED_MIN_CVLCPT_CNT);
        assertThat(getCriteria()).extracting(RiskCriteriaVO::getMinCvlcptCnt).containsOnly(0);
    }

    @Test
    @DisplayName("33. 요청에 bscMinCvlcptCnt 를 끼워 보내도 무시된다 (200 · 기본값 불변)")
    void case33_bscMinCvlcptCntInRequestIsIgnored() {
        int target = SEED_MIN_CVLCPT_CNT / 2;
        List<Map<String, Object>> items = criteriaAll(target).stream()
                .map(item -> {
                    Map<String, Object> copy = new LinkedHashMap<>(item);
                    copy.put("bscMinCvlcptCnt", 999);   // 요청 DTO 에 없는 필드
                    return copy;
                })
                .collect(Collectors.toList());

        List<RiskCriteriaVO> saved = putCriteria(items);   // 헬퍼가 200 을 단언한다

        assertThat(saved).extracting(RiskCriteriaVO::getBscMinCvlcptCnt)
                .containsOnly(SEED_MIN_CVLCPT_CNT);
        assertThat(saved).extracting(RiskCriteriaVO::getMinCvlcptCnt).containsOnly(target);
        assertThat(bscMinCvlcptCntsFromDb()).containsOnly(SEED_MIN_CVLCPT_CNT);
    }

    // ============================================ 수신자 다지역 · 사용자 선택 · 수정 (TSK-4250 Step 7)

    /**
     * 후보 API (R12) — {@code registered}(R9) · 무소속 맨 뒤(Q2) · 비활성 제외.
     *
     * <p>🔴 단언은 <b>합성 {@code t4250-*} 사용자 스코프</b>다 — 로컬 실사용자·{@code mock-user} 가 섞여 전역 건수가 흔들린다.
     * 🔴 후보의 "활성" 기준({@code use_yn='Y'})은 등록 시 비활성 판정(400)과 같은 칸·같은 값이어야 한다 — 둘을 함께 본다.
     */
    @Test
    @DisplayName("34. 후보 API — registered 플래그 · 무소속 맨 뒤 · 비활성 사용자 제외(등록도 400)")
    void case34_candidates() {
        createReceiver(USER_2, List.of(regionAll()), List.of("CRIT"));

        List<NotiReceiverCandidateVO> all = getCandidates();
        List<NotiReceiverCandidateVO> mine = all.stream()
                .filter(c -> c.getUserId() != null && c.getUserId().startsWith(SYN_PREFIX))
                .toList();

        // 정렬: 부서 sort_seq(PARK=2) → 부서명 → 사용자명, 무소속(dept_cd NULL)은 맨 뒤 (Q2). 비활성은 없다
        assertThat(mine).extracting(NotiReceiverCandidateVO::getUserId)
                .containsExactly(USER_1, USER_2, USER_3, USER_4, USER_NODEPT)
                .doesNotContain(USER_OFF);
        // registered — 방금 등록한 USER_2 만 true (R9)
        assertThat(mine).filteredOn(NotiReceiverCandidateVO::isRegistered)
                .extracting(NotiReceiverCandidateVO::getUserId).containsExactly(USER_2);

        NotiReceiverCandidateVO u1 = mine.get(0);
        assertThat(u1.getUserNm()).isEqualTo("합성사용자1");
        assertThat(u1.getDeptCd()).isEqualTo("PARK");
        assertThat(u1.getDeptNm()).isEqualTo("주차관리");
        assertThat(u1.getEml()).isEqualTo("t4250-u-01@test.local");

        NotiReceiverCandidateVO noDept = mine.get(mine.size() - 1);
        assertThat(noDept.getDeptCd()).isNull();
        assertThat(noDept.getDeptNm()).isNull();

        // 세션 사용자(mock-user)는 plantSessionReceiver() 의 활성 행이 있어 registered=true — user_id 기준(Q3)
        assertThat(all).filteredOn(c -> IntegrationLoginSupport.MOCK_ID.equals(c.getUserId()))
                .singleElement().extracting(NotiReceiverCandidateVO::isRegistered).isEqualTo(true);

        // 🔴 후보에 없는 비활성 사용자는 등록도 400 이다 (같은 칸·같은 값)
        assertThat(postReceiverStatus(receiverBody(USER_OFF, List.of(regionAll()), List.of("CRIT"))))
                .isEqualTo(400);
    }

    /**
     * 비권한 403 (Q1) — 후보 API 는 <b>첫 인가 GET</b> 이다. 수정 {@code PUT} 도 {@code M0404} 다.
     *
     * <p>{@code @PreAuthorize} 는 메서드 보안이라 test 체인의 {@code permitAll} 과 무관하게 동작한다.
     * {@link IntegrationLoginSupport#revokeMenu} 로 {@code ROLE_USER} 의 {@code M0404} 를 회수한다(시드에는 그 매핑이 없다).
     * 대조군으로 같은 세션의 목록 {@code GET} 은 200 이다(다른 조회는 아직 인가 대상이 아니다).
     */
    @Test
    @DisplayName("35. 🔴 M0404 없는 세션 — 후보 GET·수정 PUT·등록 POST 는 403, 목록 GET 은 200 (Q1)")
    void case35_forbiddenWithoutMenuGrant() {
        auth.revokeMenu("M0404");

        assertThat(restTemplate.exchange(RECEIVERS_URL + "/candidates", HttpMethod.GET,
                new HttpEntity<>(session), String.class).getStatusCode().value()).isEqualTo(403);
        assertThat(putReceiverRaw(SESSION_RCVR_ID, List.of(regionAll()), List.of("CRIT"))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(postReceiverStatus(receiverBody(USER_1, List.of(regionAll()), List.of("CRIT"))))
                .isEqualTo(403);

        // 대조군 — 목록 조회는 인가 대상이 아니다 (TSK-4115: 후보 API 만 예외)
        assertThat(restTemplate.exchange(RECEIVERS_URL + "?page=0&size=10", HttpMethod.GET,
                new HttpEntity<>(session), String.class).getStatusCode().value()).isEqualTo(200);
        // 거부된 쓰기가 아무것도 바꾸지 않았다
        assertThat(regionRowsOf(SESSION_RCVR_ID)).containsExactly("-|-");
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr"))
                .isEqualTo(SEED_RECEIVER_COUNT + OWN_RECEIVER_COUNT);
    }

    @Test
    @DisplayName("36. 등록 — 지역 N건이 TB_NOTI_RCVR_RGN 에 저장되고 같은 지역 중복은 제거된다")
    void case36_createWithMultipleRegions() {
        String seongsan = emdCd("서귀포시", "성산읍");
        String jungmun = emdCd("서귀포시", "중문동");
        long rcvrId = createReceiver(USER_1, List.of(
                region("제주시", null),          // 시군구 전체
                region(null, seongsan),
                region(null, jungmun),
                region(null, seongsan)),         // 중복 — 서버가 제거한다
                List.of("CRIT"));

        assertThat(regionRowsOf(rcvrId)).containsExactlyInAnyOrder(
                "제주시|-", "서귀포시|" + seongsan, "서귀포시|" + jungmun);

        NotiReceiverVO row = findReceiver(getReceivers("?page=0&size=100"), rcvrId);
        assertThat(row.getRegions()).extracting(NotiReceiverRegionVO::getRegionNm)
                .containsExactlyInAnyOrder("제주시 전체", "서귀포시 성산읍", "서귀포시 중문동");
        // 합친 regionNm 은 원소 regionNm 을 같은 순서로 ", " 로 이은 것이다 (R6 — 프론트가 다시 조립하지 않는다)
        assertThat(row.getRegionNm()).isEqualTo(String.join(", ",
                row.getRegions().stream().map(NotiReceiverRegionVO::getRegionNm).toList()));
    }

    @Test
    @DisplayName("37. 등록 — regions:[{}] 는 전체 지역 = sgg_nm·emd_cd 둘 다 NULL 인 명시 행 1개 (R3)")
    void case37_createWithAllRegion() {
        long rcvrId = createReceiver(USER_1, List.of(regionAll()), List.of("WARN"));

        assertThat(regionRowsOf(rcvrId)).as("🔴 행 0개가 아니라 NULL 행 1개다 (fail-closed)")
                .containsExactly("-|-");

        NotiReceiverVO row = findReceiver(getReceivers("?page=0&size=100"), rcvrId);
        assertThat(row.getRegions()).hasSize(1);
        assertThat(row.getRegions().get(0).getSggNm()).isNull();
        assertThat(row.getRegions().get(0).getEmdCd()).isNull();
        assertThat(row.getRegions().get(0).getRegionNm()).isEqualTo("전체 지역");
        assertThat(row.getRegionNm()).isEqualTo("전체 지역");
    }

    @Test
    @DisplayName("38. 🔴 같은 사용자 2회 등록 → 409 RCVR_DUPLICATED · 삭제 후 재등록은 201 (R9)")
    void case38_duplicateUserIs409AndReRegisterAfterDelete() {
        long first = createReceiver(USER_1, List.of(regionAll()), List.of("CRIT"));

        ResponseEntity<String> dup = restTemplate.exchange(RECEIVERS_URL, HttpMethod.POST,
                auth.jsonEntity(session, receiverBody(USER_1, List.of(region("제주시", null)), List.of("WARN"))),
                String.class);
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(dup.getBody()).contains("\"code\"").contains("RCVR_DUPLICATED");
        // 409 가 아무것도 남기지 않았다 — 활성 행 1개 그대로
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr WHERE user_id = '" + USER_1 + "'"))
                .isEqualTo(1);

        // 삭제(del_yn='Y') 하면 부분 인덱스 밖이 되어 다시 등록할 수 있다
        assertThat(restTemplate.exchange(RECEIVERS_URL + "/" + first, HttpMethod.DELETE,
                new HttpEntity<>(session), String.class).getStatusCode().value()).isEqualTo(204);
        assertThat(getCandidates()).filteredOn(c -> USER_1.equals(c.getUserId()))
                .singleElement().extracting(NotiReceiverCandidateVO::isRegistered).isEqualTo(false);

        long second = createReceiver(USER_1, List.of(region("제주시", null)), List.of("WARN"));
        assertThat(second).isNotEqualTo(first);
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr WHERE user_id = '" + USER_1 + "'"))
                .as("옛 행(del_yn='Y') + 새 행").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM parking.tb_noti_rcvr WHERE user_id = '" + USER_1
                + "' AND del_yn = 'N'")).isEqualTo(1);
        assertThat(getCandidates()).filteredOn(c -> USER_1.equals(c.getUserId()))
                .singleElement().extracting(NotiReceiverCandidateVO::isRegistered).isEqualTo(true);
    }

    /**
     * 조회 (Step 5) — 지역은 별도 쿼리로 붙인다. 🔴 {@code <collection>} LEFT JOIN 으로 붙이면 행이
     * <b>단계 수 × 지역 수</b>로 곱해진다. 단계 2 × 지역 3 수신자가 1행·단계 2·지역 3 인지,
     * 페이지 경계에서 쪼개지지 않는지, 지역 0행은 {@code 지역 없음} 으로 드러나는지 본다.
     */
    @Test
    @DisplayName("39. 조회 — 단계 2 × 지역 3 수신자가 1행(riskLvlCds 2·regions 3) · 페이지 경계 · 지역 0행은 '지역 없음'")
    void case39_listDoesNotMultiplyRows() {
        List<Map<String, Object>> threeRegions = List.of(
                region(null, emdCd("제주시", "연동")),
                region(null, emdCd("제주시", "노형동")),
                region("서귀포시", null));
        long r1 = createReceiver(USER_1, threeRegions, List.of("CRIT", "WARN"));
        long r2 = createReceiver(USER_2, threeRegions, List.of("CRIT", "WARN"));
        long r3 = createReceiver(USER_3, threeRegions, List.of("CRIT", "WARN"));

        ReceiverPage page = getReceivers("?page=0&size=100");
        assertThat(page.totalElements).isEqualTo(SEED_RECEIVER_COUNT + OWN_RECEIVER_COUNT + 3);
        assertThat(page.content).extracting(NotiReceiverVO::getRcvrId).doesNotHaveDuplicates();

        NotiReceiverVO row = findReceiver(page, r1);
        assertThat(row.getRiskLvlCds()).as("🔴 곱해지면 6 이 된다").hasSize(2)
                .containsExactlyInAnyOrder("CRIT", "WARN");
        assertThat(row.getRegions()).as("🔴 곱해지면 6 이 된다").hasSize(3);
        assertThat(row.getRegions()).extracting(NotiReceiverRegionVO::getRegionNm)
                .containsExactlyInAnyOrder("제주시 연동", "제주시 노형동", "서귀포시 전체");
        assertThat(row.getRegionNm().split(", ")).hasSize(3);
        assertThat(row.getRegionNm()).isEqualTo(String.join(", ",
                row.getRegions().stream().map(NotiReceiverRegionVO::getRegionNm).toList()));

        // 페이지 경계 — size=2 두 페이지가 겹치지 않고 합집합이 전체다. 다중 단계·지역 수신자가 반쪽으로 쪼개지지 않는다
        ReceiverPage p0 = getReceivers("?page=0&size=2");
        ReceiverPage p1 = getReceivers("?page=1&size=2");
        assertThat(p0.content).hasSize(2);
        assertThat(p1.content).hasSize(2);
        Set<Long> union = new java.util.LinkedHashSet<>();
        p0.content.forEach(r -> union.add(r.getRcvrId()));
        p1.content.forEach(r -> union.add(r.getRcvrId()));
        assertThat(union).containsExactlyInAnyOrder(SESSION_RCVR_ID, r1, r2, r3);
        assertThat(java.util.stream.Stream.concat(p0.content.stream(), p1.content.stream()))
                .filteredOn(r -> r.getRcvrId() >= FIRST_GENERATED_RCVR_ID)
                .allSatisfy(r -> {
                    assertThat(r.getRiskLvlCds()).hasSize(2);
                    assertThat(r.getRegions()).hasSize(3);
                });

        // 지역 0행 (정상 경로로는 생기지 않는다 — 직접 지워 만든다) → '지역 없음' (전체 지역으로 보이면 fail-closed 를 가린다)
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr_rgn WHERE rcvr_id = ?", r3);
        NotiReceiverVO empty = findReceiver(getReceivers("?page=0&size=100"), r3);
        assertThat(empty.getRegions()).isNotNull().isEmpty();
        assertThat(empty.getRegionNm()).isEqualTo("지역 없음");
    }

    @Test
    @DisplayName("40. 수정 PUT — 지역·단계 통째 교체(이전 행 잔존 0) · 사람 필드 불변 · 404 · 400 은 아무것도 지우지 않는다 (R10)")
    void case40_updateReplacesMappings() {
        long rcvrId = createReceiver(USER_1, List.of(
                region(null, emdCd("제주시", "연동")),
                region(null, emdCd("제주시", "노형동"))), List.of("CRIT", "WARN"));
        Map<String, Object> personBefore = jdbcTemplate.queryForMap(
                "SELECT user_id, rcvr_nm, eml, dept_cd FROM parking.tb_noti_rcvr WHERE rcvr_id = ?", rcvrId);

        // 사람 필드를 끼워 보내도 받지 않는다 (DTO 에 필드가 없다)
        Map<String, Object> body = updateBody(List.of(region("서귀포시", null)), List.of("CAUT"));
        body.put("userId", USER_2);
        body.put("rcvrNm", "바꾸려는이름");
        body.put("eml", "changed@example.com");
        body.put("deptCd", "SAFE");
        ResponseEntity<NotiReceiverVO> updated = restTemplate.exchange(RECEIVERS_URL + "/" + rcvrId,
                HttpMethod.PUT, auth.jsonEntity(session, body), NotiReceiverVO.class);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().getRcvrId()).isEqualTo(rcvrId);
        // 🔴 PUT 응답 riskLvlCds 가 [] 이면 saveAll 뒤 flush 누락이다 (Step 4)
        assertThat(updated.getBody().getRiskLvlCds()).containsExactly("CAUT");
        assertThat(updated.getBody().getRegions()).extracting(NotiReceiverRegionVO::getRegionNm)
                .containsExactly("서귀포시 전체");
        assertThat(updated.getBody().getRegionNm()).isEqualTo("서귀포시 전체");

        // DB — 요청과 정확히 같다 (이전 행 잔존 0)
        assertThat(regionRowsOf(rcvrId)).containsExactly("서귀포시|-");
        assertThat(levelRowsOf(rcvrId)).containsExactly("CAUT");
        // 사람 칸 불변 (R10)
        assertThat(jdbcTemplate.queryForMap(
                "SELECT user_id, rcvr_nm, eml, dept_cd FROM parking.tb_noti_rcvr WHERE rcvr_id = ?", rcvrId))
                .isEqualTo(personBefore);

        // 트랜잭션 밖 재조회도 같다
        NotiReceiverVO refetched = findReceiver(getReceivers("?page=0&size=100"), rcvrId);
        assertThat(refetched.getRiskLvlCds()).containsExactly("CAUT");
        assertThat(refetched.getRegionNm()).isEqualTo("서귀포시 전체");
        assertThat(refetched.getRcvrNm()).isEqualTo("합성사용자1");

        // 400 — 검증 실패는 아무것도 지우지 않는다
        assertThat(putReceiverRaw(rcvrId, List.of(), List.of("CRIT")).getStatusCode().value()).isEqualTo(400);
        assertThat(putReceiverRaw(rcvrId, List.of(regionAll(), region("제주시", null)), List.of("CRIT"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(putReceiverRaw(rcvrId, List.of(regionAll()), List.of("NOPE"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(regionRowsOf(rcvrId)).containsExactly("서귀포시|-");
        assertThat(levelRowsOf(rcvrId)).containsExactly("CAUT");

        // 404 — 없는 수신자 · 삭제된 수신자
        assertThat(putReceiverRaw(999_999L, List.of(regionAll()), List.of("CRIT"))
                .getStatusCode().value()).isEqualTo(404);
        restTemplate.exchange(RECEIVERS_URL + "/" + rcvrId, HttpMethod.DELETE, new HttpEntity<>(session), String.class);
        assertThat(putReceiverRaw(rcvrId, List.of(regionAll()), List.of("CRIT"))
                .getStatusCode().value()).isEqualTo(404);
    }

    /**
     * 🔴 일부 유지 PUT — 기존 지역·단계 중 하나를 그대로 두고 하나를 바꾼다(같은 키 delete → insert 경로).
     *
     * <p>삭제를 {@code em.remove}(파생 {@code deleteByRcvrId})로 미루면 Hibernate 가 flush 때 INSERT 를 DELETE 보다
     * <b>먼저</b> 내 유지되는 지역이 {@code uq_tb_noti_rcvr_rgn}, 유지되는 단계가 PK 위반으로 500 이 된다(Step 4).
     */
    @Test
    @DisplayName("41. 🔴 일부 유지 PUT — 연동·CRIT 는 유지, 나머지만 교체해도 200 (500 이 아니다)")
    void case41_partialKeepUpdate() {
        String yeondong = emdCd("제주시", "연동");
        String nohyeong = emdCd("제주시", "노형동");
        String ido2 = emdCd("제주시", "이도이동");
        long rcvrId = createReceiver(USER_1, List.of(region(null, yeondong), region(null, nohyeong)),
                List.of("CRIT", "WARN"));

        NotiReceiverVO updated = putReceiver(rcvrId, List.of(region(null, yeondong), region(null, ido2)),
                List.of("CRIT", "CAUT"));

        assertThat(updated.getRiskLvlCds()).containsExactlyInAnyOrder("CRIT", "CAUT");
        assertThat(updated.getRegions()).extracting(NotiReceiverRegionVO::getRegionNm)
                .containsExactlyInAnyOrder("제주시 연동", "제주시 이도이동");
        assertThat(regionRowsOf(rcvrId)).containsExactlyInAnyOrder("제주시|" + yeondong, "제주시|" + ido2);
        assertThat(levelRowsOf(rcvrId)).containsExactlyInAnyOrder("CRIT", "CAUT");
    }

    // ---------------------------------------------------------------- 헬퍼 (응답 미러)

    /**
     * {@code RiskAlertPageVO} 를 그대로 역직렬화할 수 없어(부모 {@code PageResponse} 의 필드가 final 이고
     * 기본 생성자가 없다) 테스트 전용 미러를 둔다. 필드는 응답 계약과 1:1 이라 계약이 바뀌면 여기서 먼저 깨진다.
     */
    static class AlertPage {
        public List<RiskAlertVO> content;
        public int page;
        public int size;
        public long totalElements;
        public int totalPages;
        public RiskAlertSummaryVO summary;
    }

    /** 수신자 목록({@code PageResponse<NotiReceiverVO>})용 미러 */
    static class ReceiverPage {
        public List<NotiReceiverVO> content;
        public int page;
        public int size;
        public long totalElements;
        public int totalPages;
    }

    // ---------------------------------------------------------------- 헬퍼 (호출)

    /**
     * 🔴 <b>세션을 반드시 싣는다</b> (TSK-4202 Step 8) — 익명이면 수신자 해석 이전에 401 이고,
     * 세션 사용자는 {@link #plantSessionReceiver()} 가 전체 지역 수신자로 만들어 둬서
     * 알림 18행이 전부 보인다.
     */
    private AlertPage getAlerts(String query) {
        ResponseEntity<AlertPage> response = restTemplate.exchange(ALERTS_URL + query, HttpMethod.GET,
                new HttpEntity<>(session), AlertPage.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content).isNotNull();
        assertThat(response.getBody().summary).isNotNull();
        return response.getBody();
    }

    /** 실패가 예상되는 호출 — 에러 바디를 VO 로 읽으려다 터지지 않게 String 으로 받는다 */
    private int alertStatus(String query) {
        return restTemplate.exchange(ALERTS_URL + query, HttpMethod.GET, new HttpEntity<>(session),
                String.class).getStatusCode().value();
    }

    /** 원시 JSON (필드 존재 단언용) — 세션 필수. */
    private String rawAlerts(String query) {
        return restTemplate.exchange(ALERTS_URL + query, HttpMethod.GET, new HttpEntity<>(session),
                String.class).getBody();
    }

    private ReceiverPage getReceivers(String query) {
        ResponseEntity<ReceiverPage> response =
                restTemplate.getForEntity(RECEIVERS_URL + query, ReceiverPage.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content).isNotNull();
        return response.getBody();
    }

    private List<RiskCriteriaVO> getCriteria() {
        ResponseEntity<List<RiskCriteriaVO>> response = restTemplate.exchange(
                CRITERIA_URL, HttpMethod.GET, null, new ParameterizedTypeReference<List<RiskCriteriaVO>>() {});
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<RiskCriteriaVO> putCriteria(List<Map<String, Object>> items) {
        ResponseEntity<List<RiskCriteriaVO>> response = restTemplate.exchange(
                CRITERIA_URL, HttpMethod.PUT, auth.jsonEntity(session, Map.of("criteria", items)),
                new ParameterizedTypeReference<List<RiskCriteriaVO>>() {});
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private int putCriteriaStatus(List<Map<String, Object>> items) {
        return restTemplate.exchange(CRITERIA_URL, HttpMethod.PUT,
                auth.jsonEntity(session, Map.of("criteria", items)), String.class).getStatusCode().value();
    }

    /**
     * 수신자 픽스처를 <b>API 로</b> 만든다 (🔁 2026-10-15 — 시드 수신자 12행이 사라져 생긴 헬퍼).
     *
     * <p>🔴 {@link JdbcTemplate} 직접 INSERT 가 아니라 {@code POST /api/noti-receivers} 를 쓴다 —
     * 단계·지역 매핑({@code TB_NOTI_RCVR_LVL}·{@code TB_NOTI_RCVR_RGN})까지 서비스가 채워 줘야
     * "삭제해도 매핑이 남는다"(D5) 같은 단언이 의미를 갖기 때문이다.
     * 🔁 TSK-4250 — 새 계약({@code userId} + {@code regions} + {@code riskLvlCds}). {@code userId} 는
     * {@link #plantSyntheticUsers()} 의 합성 사용자다 — 🔴 {@code mock-user} 를 넘기지 않는다(세션 수신자가 있어 409).
     *
     * @return 생성된 {@code rcvr_id} (identity 라 {@value #FIRST_GENERATED_RCVR_ID} 부터다)
     */
    private long createReceiver(String userId, List<Map<String, Object>> regions, List<String> riskLvlCds) {
        ResponseEntity<NotiReceiverVO> created = restTemplate.exchange(RECEIVERS_URL, HttpMethod.POST,
                auth.jsonEntity(session, receiverBody(userId, regions, riskLvlCds)),
                NotiReceiverVO.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().getRcvrId()).isNotNull();
        return created.getBody().getRcvrId();
    }

    private int postReceiverStatus(Map<String, Object> body) {
        return restTemplate.exchange(RECEIVERS_URL, HttpMethod.POST,
                auth.jsonEntity(session, body), String.class).getStatusCode().value();
    }

    /** 수정 {@code PUT /api/noti-receivers/{rcvrId}} — 실패가 예상되는 호출도 있어 String 으로 받는다 */
    private ResponseEntity<String> putReceiverRaw(long rcvrId, List<Map<String, Object>> regions,
                                                  List<String> riskLvlCds) {
        return restTemplate.exchange(RECEIVERS_URL + "/" + rcvrId, HttpMethod.PUT,
                auth.jsonEntity(session, updateBody(regions, riskLvlCds)), String.class);
    }

    /** 수정 성공 경로 — 200 을 단언하고 갱신된 1건을 돌려준다 */
    private NotiReceiverVO putReceiver(long rcvrId, List<Map<String, Object>> regions, List<String> riskLvlCds) {
        ResponseEntity<NotiReceiverVO> res = restTemplate.exchange(RECEIVERS_URL + "/" + rcvrId, HttpMethod.PUT,
                auth.jsonEntity(session, updateBody(regions, riskLvlCds)), NotiReceiverVO.class);
        assertThat(res.getStatusCode().value()).as("🔴 500 이면 delete→insert 순서 함정(Step 4) 회귀다")
                .isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    /** 수신자 후보 {@code GET /api/noti-receivers/candidates} — 🔴 M0404 인가(Q1)라 세션을 싣는다 */
    private List<NotiReceiverCandidateVO> getCandidates() {
        ResponseEntity<List<NotiReceiverCandidateVO>> res = restTemplate.exchange(RECEIVERS_URL + "/candidates",
                HttpMethod.GET, new HttpEntity<>(session),
                new ParameterizedTypeReference<List<NotiReceiverCandidateVO>>() {});
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    /** 벨 {@code GET /api/risk-alerts/daily} — 세션 사용자 기준 */
    private RiskAlertDailyVO getDaily() {
        ResponseEntity<RiskAlertDailyVO> res = restTemplate.exchange(ALERTS_URL + "/daily", HttpMethod.GET,
                new HttpEntity<>(session), RiskAlertDailyVO.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getItems()).isNotNull();
        return res.getBody();
    }

    // ---------------------------------------------------------------- 헬퍼 (요청 바디 · 조회)

    /**
     * 등록 요청 바디 (🔁 TSK-4250 R6 — {@code userId} + {@code regions} + {@code riskLvlCds}).
     * {@code Map.of} 는 null 값을 허용하지 않아 {@link LinkedHashMap} 을 쓴다 — "보내지 않음"을 null 로
     * 표현해야 누락 400 분기를 태울 수 있다.
     */
    private Map<String, Object> receiverBody(String userId, List<Map<String, Object>> regions,
                                             List<String> riskLvlCds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("regions", regions);
        body.put("riskLvlCds", riskLvlCds);
        return body;
    }

    /** 수정 요청 바디 — 사람 필드가 없다(R10: 받지 않는다) */
    private Map<String, Object> updateBody(List<Map<String, Object>> regions, List<String> riskLvlCds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("regions", regions);
        body.put("riskLvlCds", riskLvlCds);
        return body;
    }

    /**
     * 지역 원소 1개. {@code emdCd} 가 있으면 읍면동(sggNm 은 서버가 다시 채운다), {@code sggNm} 만 있으면 시군구 전체.
     * null 값을 담아야 해서 {@link LinkedHashMap} 이다.
     */
    private static Map<String, Object> region(String sggNm, String emdCd) {
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("sggNm", sggNm);
        region.put("emdCd", emdCd);
        return region;
    }

    /** 🔴 전체 지역 원소 {@code {}} (R3 — 빈 배열이 아니다). 직렬화하면 빈 객체다 */
    private static Map<String, Object> regionAll() {
        return new LinkedHashMap<>();
    }

    /** 수신자 1명의 지역 매핑을 DB 에서 직접 읽는다 — "시군구|읍면동" 문자열(NULL 은 "-") */
    private List<String> regionRowsOf(long rcvrId) {
        return jdbcTemplate.queryForList("""
                SELECT coalesce(sgg_nm, '-') || '|' || coalesce(emd_cd, '-')
                  FROM parking.tb_noti_rcvr_rgn WHERE rcvr_id = ?""", String.class, rcvrId);
    }

    /** 수신자 1명의 단계 매핑을 DB 에서 직접 읽는다 */
    private List<String> levelRowsOf(long rcvrId) {
        return jdbcTemplate.queryForList(
                "SELECT risk_lvl_cd FROM parking.tb_noti_rcvr_lvl WHERE rcvr_id = ?", String.class, rcvrId);
    }

    /**
     * 기준 1건 요청 바디 — {@code minCvlcptCnt} 는 시드값을 채운다.
     *
     * <p>🔴 {@code minCvlcptCnt} 가 {@code @NotNull} 이라(TSK-4092 Step 4) 빠뜨리면 400 이다.
     * 하한을 신경 쓰지 않는 기존 케이스(9·10·14)가 하한 때문에 깨지지 않도록 기본값을 넣는다.
     */
    private Map<String, Object> criteriaItem(String riskLvlCd, String incrsRt) {
        return criteriaItem(riskLvlCd, incrsRt, SEED_MIN_CVLCPT_CNT);
    }

    /** 기준 1건 요청 바디 — 최소 민원수 하한까지 지정 (TSK-4092) */
    private Map<String, Object> criteriaItem(String riskLvlCd, String incrsRt, Integer minCvlcptCnt) {
        Map<String, Object> item = criteriaItemWithoutMin(riskLvlCd, incrsRt);
        item.put("minCvlcptCnt", minCvlcptCnt);
        return item;
    }

    /**
     * {@code minCvlcptCnt} <b>키 자체가 없는</b> 요청 바디 — 케이스 26 전용.
     * {@code Map.of} 는 null 을 못 담아 {@link LinkedHashMap} 을 쓴다(다른 헬퍼와 같은 이유).
     */
    private Map<String, Object> criteriaItemWithoutMin(String riskLvlCd, String incrsRt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("riskLvlCd", riskLvlCd);
        item.put("incrsRt", new BigDecimal(incrsRt));
        return item;
    }

    /** 기준 3건(CRIT/WARN/CAUT)을 시드 증가율 그대로, 하한만 바꿔 만든다 */
    private List<Map<String, Object>> criteriaAll(int minCvlcptCnt) {
        return List.of(criteriaItem("CRIT", "30.00", minCvlcptCnt),
                criteriaItem("WARN", "20.00", minCvlcptCnt),
                criteriaItem("CAUT", "10.00", minCvlcptCnt));
    }

    /** {@code bsc_min_cvlcpt_cnt} 3행을 DB 에서 직접 읽는다 (API 를 거치지 않는 확인 경로) */
    private List<Integer> bscMinCvlcptCntsFromDb() {
        return jdbcTemplate.queryForList(
                "SELECT bsc_min_cvlcpt_cnt FROM parking.tb_risk_stp_noti_crtr", Integer.class);
    }

    private NotiReceiverVO findReceiver(ReceiverPage page, Long rcvrId) {
        return page.content.stream()
                .filter(row -> rcvrId.equals(row.getRcvrId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("수신자를 목록에서 찾지 못했습니다: " + rcvrId));
    }

    private Set<Long> ids(AlertPage page) {
        return page.content.stream().map(RiskAlertVO::getNotiId).collect(Collectors.toCollection(
                java.util.LinkedHashSet::new));
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    /** 시드 읍면동명 → emd_cd. 코드값을 소스에 박지 않는다(시드가 TB_ADMDST 조인으로 채우기 때문) */
    private String emdCd(String sggNm, String emdNm) {
        return jdbcTemplate.queryForObject(
                "SELECT emd_cd FROM parking.tb_admdst WHERE sgg_nm = ? AND emd_nm = ?",
                String.class, sggNm, emdNm);
    }

    /**
     * {@code sum(cvlcpt_cnt)} — <b>알림 이력</b> 기준 합계. 목록({@code content})의 {@code cvlcptCnt} 합과
     * 대조하는 용도로 남겨 둔다. 🔴 <b>요약 카드({@code cvlcptSumCnt})의 기대값이 아니다</b> —
     * 카드는 {@link #countCvlcpt(String)} 다 (TSK-4069 Step 9).
     */
    private long sumCvlcpt(String where) {
        String sql = "SELECT COALESCE(sum(h.cvlcpt_cnt), 0) FROM parking.tb_risk_stp_noti_hstry h "
                + "JOIN parking.tb_admdst a ON a.emd_cd = h.emd_cd"
                + (where == null ? "" : " WHERE " + where);
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    /**
     * {@code cvlcptSumCnt} 카드의 기대값 — <b>민원 테이블</b>({@code parking.tb_cvlcpt})을 직접 센다.
     * {@code selectAlertSummary} 의 서브쿼리와 <b>같은 FROM·같은 {@code emd_cd IS NOT NULL} 기준</b>이라
     * 매퍼가 옛 정의(이력 합)로 되돌아가면 그 즉시 어긋난다.
     *
     * <p>기대값을 상수로 박지 않는 이유: 민원 테이블은 시드가 아니라 수집 배치가 채우는 실데이터라
     * 환경마다 건수가 다르다. {@code where} 는 별칭 {@code c}(민원)·{@code a}(행정구역)를 쓴다.
     */
    private long countCvlcpt(String where) {
        String sql = "SELECT count(*) FROM parking.tb_cvlcpt c "
                + "LEFT JOIN parking.tb_admdst a ON a.emd_cd = c.emd_cd "
                + "WHERE c.emd_cd IS NOT NULL"
                + (where == null ? "" : " AND " + where);
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    /** {@link #alertDateCounts()} 의 한 행 — 발생일과 그날 건수. */
    private record DayCount(LocalDate day, long cnt) { }

    /**
     * 알림 이력의 <b>발생일별 건수</b> — 기간 경계 케이스(3)의 기대값을 날짜 리터럴 대신 DB 에서 만든다
     * (TSK-4197).
     *
     * <p>🔴 <b>{@code ocrn_dt::time > '00:00:00'} 으로 자정 행을 뺀다.</b> 자정 행만 걸리면
     * {@code to} 를 00:00:00 으로 비교하는 버그가 있어도 통과해 경계 검증이 성립하지 않는다.
     *
     * <p>🔴 조회 매퍼·서비스·API 를 타지 않는 <b>독립 SQL</b> 이다 — 검증 대상과 같은 쿼리를 재사용하면
     * "자기 자신과 비교" 가 되어 둘이 함께 틀려도 통과한다.
     *
     * <p>정렬은 건수 내림차순 → 날짜 내림차순이라, 첫 행이 "그날이 가장 붐빈 날"(종료일 경계용),
     * 마지막 행이 "가장 한산한 날"(시작일 경계용)이 된다. 동점이면 날짜로 갈려 실행마다 흔들리지 않는다.
     */
    private List<DayCount> alertDateCounts() {
        return jdbcTemplate.query("""
                SELECT ocrn_dt::date AS ocrn_ymd, count(*) AS alert_cnt
                  FROM parking.tb_risk_stp_noti_hstry
                 WHERE ocrn_dt::time > TIME '00:00:00'
                 GROUP BY ocrn_dt::date
                 ORDER BY count(*) DESC, ocrn_dt::date DESC""",
                (rs, rowNum) -> new DayCount(rs.getObject("ocrn_ymd", LocalDate.class),
                        rs.getLong("alert_cnt")));
    }
}
