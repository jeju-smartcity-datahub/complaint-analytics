package jeju.datahub.risknoti;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
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

import jeju.datahub.risknoti.service.RiskAlertDailyVO;
import jeju.datahub.risknoti.service.RiskAlertSummaryVO;
import jeju.datahub.risknoti.service.RiskAlertVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 상단 알림 벨 팝오버 API 통합 테스트.
 *
 * <p>대상은 두 엔드포인트다 — {@code GET /api/risk-alerts/daily}(판정일 하루치) ·
 * {@code PATCH /api/risk-alerts/{notiId}/read}(읽음 처리).
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도 스레드·별도
 * 커넥션으로 처리하므로 테스트 트랜잭션이 서버 쪽 쓰기를 감싸지 못한다(롤백이 걸리지 않는다).
 * 그래서 이 도메인은 "실제 커밋 + {@code @AfterEach} 수동 복원"을 쓴다
 * ({@code RiskNotiIntegrationTest} · {@code MenuAuthrtIntegrationTest} · {@code ReportIntegrationTest} 와 같은 전략).
 *
 * <p><b>🔴 날짜를 하드코딩하지 않는다.</b> 시드가 날짜만 옮겨 재적재되면 하드코딩한 단언이 한꺼번에 깨진다
 * (과거 실측 사례). 기준일은 항상 {@code LocalDate.now(Asia/Seoul).minusDays(1)} 로 계산한다 —
 * 컨트롤러가 {@code baseDe} 를 확정하는 규칙 자체를 검증하는 셈이다.
 *
 * <h2>·9 — 세 경로가 전부 "내게 온 알림" 으로 좁혀졌다</h2>
 * <ul>
 *   <li>🔴 <b>익명 호출은 이제 401 이다</b>({@code @AuthenticationPrincipal} 이 null 이면
 *       {@code CurrentUserResolver} 가 끊는다). 그래서 이 클래스의 10케이스가 모두
 *       {@link IntegrationLoginSupport} 로 얻은 <b>실제 {@code mock-login} 세션</b>을 싣는다.
 *       익명 401 자체는 {@code RiskAlertReceiverScopeTest} 케이스 13 이 전담한다.</li>
 *   <li>🔴 <b>세션 사용자가 수신자여야 알림이 보인다.</b> 매칭 키는 이메일이 아니라
 *       <b>{@code user_id}</b> 다. {@code user_id} = {@code mock-user} 로 수신자 1행을 심고
 *       ({@link #insertDailyFixture()}), <b>픽스처 알림 3건을 그 수신자에게 매핑</b>한다.
 *       수신자의 {@code eml}({@value #FIXTURE_EML})은 일부러 로그인 계정 이메일과 <b>다르게</b> 둔다 —
 *       이메일로 잇는 옛 매칭으로 되돌아가면 전 케이스가 0건이 되어 여기서 깨진다.
 *       🔴 매핑({@code TB_NOTI_HSTRY_RCVR})이 없으면 지역이 맞아도 0건이다 —
 *       백엔드 필터는 매핑 {@code EXISTS} 하나뿐이고 지역을 보지 않는다.</li>
 *   <li>🔴 <b>읽음은 이력이 아니라 매핑 행에 남는다</b>. {@code prsl_yn}·{@code prsl_dt} 단언이
 *       {@code TB_RISK_STP_NOTI_HSTRY} 가 아니라 {@code TB_NOTI_HSTRY_RCVR} 를 본다.</li>
 *   <li>🔴 <b>시드 수신자(1~12)를 재활용하지 않는다</b> — 백필 매핑 66행이 붙어 있어 건수가 어긋난다.
 *       대신 이 테스트 전용 수신자 1행을 만들고 {@link #removeDailyFixture()} 가 지운다.</li>
 * </ul>
 *
 * <p><b>픽스처는 이 테스트가 스스로 만들고 스스로 지운다.</b> 시드(18행)의 최신 이력이 전일이 아니고,
 * {@code RiskNotiIntegrationTest.restoreSeed()} 가 테이블을 통째로 비웠다 다시 채우기 때문에
 * 전일 알림이 남아 있다고 가정할 수 없다. {@link #insertDailyFixture()} 가 어제 구간을 선삭제한 뒤
 * <b>무조건 3행</b>을 넣고({@link #clearYesterday()}), {@link #removeDailyFixture()} 가 되돌린다.
 * ⚠️ 알림을 지우면 {@code fk_tb_noti_hstry_rcvr_noti} 가 CASCADE 라 매핑도 함께 사라진다 —
 * 반면 수신자 쪽 FK 는 CASCADE 가 아니라 <b>매핑 → 수신자</b> 순서를 지켜야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskAlertDailyIntegrationTest {

    private static final String ALERTS_URL = "/api/risk-alerts";
    private static final String DAILY_URL = ALERTS_URL + "/daily";

    /** 이 테스트가 어제 구간에 넣는 픽스처 행 수 */
    private static final int FIXTURE_COUNT = 3;

    /**
     * 픽스처 수신자의 {@code eml} — 매칭은 {@code user_id}({@code mock-user})로 하므로
     * 이 값은 등록 시점 복사본일 뿐이다. 🔴 일부러 mock 계정 이메일({@code mock-user@example.com})과
     * <b>다르게</b> 둔다 — 이메일 매칭으로 회귀하면 전 케이스가 0건이 된다.
     */
    private static final String FIXTURE_EML = "t4250-bell-fixture@example.com";

    /** 어제 00:00:00 — 컨트롤러가 계산하는 baseDe 와 같은 규칙. 날짜 리터럴을 쓰지 않는다 */
    private static final LocalDateTime YESTERDAY = LocalDate.now(ZoneId.of("Asia/Seoul"))
            .minusDays(1).atStartOfDay();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    /** 이 테스트 전용 수신자 — 세션 사용자와 같은 {@code user_id}다. 픽스처 3건이 여기 매핑된다 */
    private long rcvrId;

    @BeforeEach
    void insertDailyFixture() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();

        // 앞선 실행이 비정상 종료해 남긴 전용 수신자를 먼저 걷는다 (매핑 → 수신자 순)
        deleteFixtureReceiver();

        // 🔴 uq_risk_hstry_emd_ocrn (emd_cd, ocrn_dt) UNIQUE — emd_cd 를 서로 다르게 고른다.
        //    NOT NULL 은 risk_lvl_cd · emd_cd · ocrn_dt · prcs_stts_cd 4개다
        //    (prsl_yn·idnty_yn 은 DEFAULT 'N').
        // 🔴 어제 구간을 먼저 비우고 **무조건 3행**을 넣는다.
        //    NOT EXISTS 로 건너뛰면 로컬에 배치 생성분(riskAlertJudgeJob)이 있을 때 3행 미만이 들어가
        //    항목 2·3·5·9·10 의 건수 단언이 실행 환경에 따라 갈린다. 이 테스트는 **어제 구간을 통째로 소유**한다.
        clearYesterday();
        int inserted = jdbcTemplate.update("""
                INSERT INTO parking.tb_risk_stp_noti_hstry
                       (risk_lvl_cd, emd_cd, cvlcpt_cnt, incrs_rt, noti_ctt, ocrn_dt, prcs_stts_cd)
                SELECT v.lvl, a.emd_cd, v.cnt, v.rt, a.emd_nm || ' 민원 테스트 증가', ?, 'OPEN'
                  FROM (VALUES ('CRIT','연동',89,45.00),
                               ('WARN','노형동',34,26.50),
                               ('CAUT','이도이동',22,10.00)) AS v(lvl, nm, cnt, rt)
                  JOIN parking.tb_admdst a ON a.emd_nm = v.nm AND a.sgg_nm = '제주시'""",
                YESTERDAY);
        assertThat(inserted).isEqualTo(FIXTURE_COUNT);   // tb_admdst 에 3행 모두 존재 (2026-08-27 실측)

        // 🔴 수신자 1행 + 픽스처 3건 매핑 — 이게 없으면 전 케이스가 0건이다.
        //    user_id 는 NOT NULL 이고 매칭 키다. eml 은 로그인 이메일과 다르게 둔다.
        //    지역 행(TB_NOTI_RCVR_RGN)은 넣지 않는다 — 백엔드 필터는 매핑만 보고 지역을 보지 않는다.
        Long newRcvrId = jdbcTemplate.queryForObject("""
                INSERT INTO parking.tb_noti_rcvr
                       (user_id, rcvr_nm, eml, use_yn, del_yn, ntfctn_ch_cd)
                VALUES (?, '벨테스트수신자', ?, 'Y', 'N', 'EMAIL')
                RETURNING rcvr_id""", Long.class, IntegrationLoginSupport.MOCK_ID, FIXTURE_EML);
        assertThat(newRcvrId).isNotNull();
        rcvrId = newRcvrId;
        int mapped = jdbcTemplate.update("""
                INSERT INTO parking.tb_noti_hstry_rcvr (noti_id, rcvr_id)
                SELECT noti_id, ? FROM parking.tb_risk_stp_noti_hstry
                 WHERE ocrn_dt >= ? AND ocrn_dt < ?""", rcvrId, YESTERDAY, YESTERDAY.plusDays(1));
        assertThat(mapped).as("픽스처 3건이 모두 세션 사용자에게 매핑돼야 한다").isEqualTo(FIXTURE_COUNT);
    }

    @AfterEach
    void removeDailyFixture() {
        clearYesterday();          // 알림 삭제 → fk_tb_noti_hstry_rcvr_noti CASCADE 로 매핑도 사라진다
        deleteFixtureReceiver();   // 🔴 수신자 쪽 FK 는 CASCADE 가 아니라 매핑을 먼저 지운다
        auth.cleanUp();
    }

    /** 어제 구간을 통째로 비운다. @BeforeEach·@AfterEach·항목 4(0건 경로)가 공유한다.
     *  ⚠️ 로컬에 배치 생성분이 있으면 함께 지워진다 — 그래서 배치를 다시 돌린다(그 절에 명시됨).
     *     테이블 전체를 비우지는 않는다(시드 18행은 그대로 남는다). */
    private void clearYesterday() {
        jdbcTemplate.update(
                "DELETE FROM parking.tb_risk_stp_noti_hstry WHERE ocrn_dt >= ? AND ocrn_dt < ?",
                YESTERDAY, YESTERDAY.plusDays(1));
    }

    /**
     * 이 테스트 전용 수신자와 그 매핑만 지운다.
     *
     * <p>🔴 스코프는 <b>{@code user_id} = {@code mock-user} 하나</b>다(종전 이메일 스코프).
     * {@code @BeforeEach} 에서도 부르는 <b>고아 정리</b>라 직전 실행의 {@code rcvr_id} 를 모른다 — 남은 행이
     * {@code uq_tb_noti_rcvr_user}(사용자당 활성 1행)를 점유하면 다음 INSERT 가 깨진다.
     * 조건 없는 {@code DELETE FROM parking.tb_noti_hstry_rcvr} 가 백필을 통째로 날린 전례가 있다.
     * 지역 행은 수신자 FK {@code ON DELETE CASCADE} 로 함께 지워진다.
     */
    private void deleteFixtureReceiver() {
        jdbcTemplate.update("""
                DELETE FROM parking.tb_noti_hstry_rcvr m
                 WHERE EXISTS (SELECT 1 FROM parking.tb_noti_rcvr r
                                WHERE r.rcvr_id = m.rcvr_id AND r.user_id = ?)""",
                IntegrationLoginSupport.MOCK_ID);
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr_lvl l WHERE EXISTS (SELECT 1 FROM parking.tb_noti_rcvr r"
                + " WHERE r.rcvr_id = l.rcvr_id AND r.user_id = ?)", IntegrationLoginSupport.MOCK_ID);
        jdbcTemplate.update("DELETE FROM parking.tb_noti_rcvr WHERE user_id = ?", IntegrationLoginSupport.MOCK_ID);
    }

    // ================================================================= GET /api/risk-alerts/daily

    @Test
    @DisplayName("1. GET /daily — 200 이고 baseDe 가 Asia/Seoul 기준 전일이다")
    void case01_baseDeIsYesterdayInSeoul() {
        RiskAlertDailyVO daily = getDaily();

        // 🔴 날짜 리터럴이 아니라 '기준일 규칙' 자체를 검증한다
        assertThat(daily.getBaseDe())
                .isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
        assertThat(daily.getItems()).isNotNull();
        // 픽스처 3행이 그대로 보인다 — 어제 구간은 이 테스트가 소유한다
        assertThat(daily.getItems()).hasSize(FIXTURE_COUNT);
        assertThat(daily.getItems()).allSatisfy(row ->
                assertThat(row.getOcrnDt().toLocalDate()).isEqualTo(daily.getBaseDe()));
    }

    @Test
    @DisplayName("2. totalCnt == items.size() — 상한이 없으므로 항상 같다")
    void case02_totalCntEqualsItemsSize() {
        RiskAlertDailyVO daily = getDaily();

        assertThat(daily.getTotalCnt()).isEqualTo(daily.getItems().size());
        assertThat(daily.getTotalCnt()).isEqualTo(FIXTURE_COUNT);
        // DB 의 어제 구간 건수와도 같다 (LIMIT 이 끼면 여기서 어긋난다)
        assertThat(daily.getTotalCnt()).isEqualTo(countYesterday(null));
    }

    @Test
    @DisplayName("3. unreadCnt == items 중 prslYn='N' 개수 — 원천은 내 매핑 행이다")
    void case03_unreadCntMatchesItems() {
        RiskAlertDailyVO daily = getDaily();

        long unreadInItems = daily.getItems().stream()
                .filter(row -> "N".equals(row.getPrslYn())).count();
        assertThat(daily.getUnreadCnt()).isEqualTo((int) unreadInItems);
        // 픽스처 매핑은 prsl_yn DEFAULT 'N' 이라 3건 모두 미읽음이다
        assertThat(daily.getUnreadCnt()).isEqualTo(FIXTURE_COUNT);
        // 🔴 대조는 이력이 아니라 매핑 테이블이다 — 읽음이 거기로 옮겨갔다
        assertThat(daily.getUnreadCnt()).isEqualTo(countMyMapping("prsl_yn = 'N'"));
    }

    @Test
    @DisplayName("4. 전일 알림 0건 — 200 · items 빈 배열 (404/500 이 아니다)")
    void case04_emptyDayReturnsEmptyList() {
        // 🔴 @BeforeEach 가 무조건 3행을 넣으므로, 지우지 않으면 0건 상태에 절대 도달하지 못한다
        clearYesterday();
        assertThat(countYesterday(null)).isZero();

        RiskAlertDailyVO daily = getDaily();

        assertThat(daily.getBaseDe())
                .isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
        assertThat(daily.getItems()).isNotNull().isEmpty();
        assertThat(daily.getTotalCnt()).isZero();
        assertThat(daily.getUnreadCnt()).isZero();
    }

    @Test
    @DisplayName("5. 정렬 — items 가 notiId 내림차순 (하루치라 ocrnDt 는 동률)")
    void case05_itemsSortedByNotiIdDesc() {
        List<RiskAlertVO> items = getDaily().getItems();

        assertThat(items).hasSize(FIXTURE_COUNT);
        assertThat(items).extracting(RiskAlertVO::getNotiId)
                .isSortedAccordingTo(Comparator.<Long>reverseOrder());
        // ocrn_dt 가 전부 같으므로 tie-breaker(noti_id DESC) 가 없으면 순서가 실행마다 흔들린다
        assertThat(items).extracting(RiskAlertVO::getOcrnDt).containsOnly(YESTERDAY);
    }

    // ================================================================= PATCH /{notiId}/read

    @Test
    @DisplayName("6. PATCH /{notiId}/read — 204 이고 내 매핑 행이 prsl_yn='Y' · prsl_dt not null")
    void case06_markReadUpdatesRow() {
        Long notiId = firstYesterdayId();

        ResponseEntity<String> response = markRead(notiId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        // 🔴 읽음은 이력(TB_RISK_STP_NOTI_HSTRY)이 아니라 내 매핑 행에 남는다
        assertThat(prslYn(notiId)).isEqualTo("Y");
        assertThat(prslDt(notiId)).isNotNull();

        // 조회 응답에도 그대로 반영된다 (트랜잭션 밖 재조회)
        RiskAlertVO item = getDaily().getItems().stream()
                .filter(r -> notiId.equals(r.getNotiId())).findFirst().orElseThrow();
        assertThat(item.getPrslYn()).isEqualTo("Y");
        assertThat(item.getPrslDt()).isNotNull();
    }

    @Test
    @DisplayName("7. 멱등 — 같은 요청 2회 모두 204 이고 prsl_dt 가 바뀌지 않는다")
    void case07_markReadIsIdempotent() {
        Long notiId = firstYesterdayId();

        assertThat(markRead(notiId).getStatusCode().value()).isEqualTo(204);
        LocalDateTime firstPrslDt = prslDt(notiId);
        assertThat(firstPrslDt).isNotNull();

        assertThat(markRead(notiId).getStatusCode().value()).isEqualTo(204);
        // 🔴 두 번째 호출이 prsl_dt 를 다시 찍으면 "언제 읽었나" 가 매 클릭마다 밀린다
        assertThat(prslDt(notiId)).isEqualTo(firstPrslDt);

        // 읽음 건수도 늘지 않는다 (UPDATE 가 조건 없이 돌면 여기서 어긋난다)
        assertThat(countMyMapping("prsl_yn = 'Y'")).isEqualTo(1);
    }

    @Test
    @DisplayName("8. 없는 notiId → 404")
    void case08_markReadUnknownIdIs404() {
        assertThat(markRead(999_999_999L).getStatusCode().value()).isEqualTo(404);

        // 실패한 호출이 다른 행을 건드리지 않는다
        assertThat(countMyMapping("prsl_yn = 'Y'")).isZero();
    }

    @Test
    @DisplayName("9. 🔴 읽음 처리 후 unreadCnt 만 1 감소하고 totalCnt 는 불변")
    void case09_readDecreasesUnreadOnly() {
        RiskAlertDailyVO before = getDaily();
        assertThat(before.getTotalCnt()).isEqualTo(FIXTURE_COUNT);
        assertThat(before.getUnreadCnt()).isEqualTo(FIXTURE_COUNT);

        Long notiId = before.getItems().get(0).getNotiId();
        assertThat(markRead(notiId).getStatusCode().value()).isEqualTo(204);

        RiskAlertDailyVO after = getDaily();
        assertThat(after.getUnreadCnt()).isEqualTo(before.getUnreadCnt() - 1);
        // 🔴 totalCnt 는 '그날 생성된 전체 건수' 라 읽어도 줄지 않는다 — 헤더 문구와 배지의 원천이 다르다
        assertThat(after.getTotalCnt()).isEqualTo(before.getTotalCnt());
        assertThat(after.getItems()).hasSize(before.getItems().size());
        // 응답 안에서도 두 값의 관계가 유지된다
        assertThat(after.getUnreadCnt()).isEqualTo(
                (int) after.getItems().stream().filter(r -> "N".equals(r.getPrslYn())).count());
    }

    // ================================================================= 회귀

    @Test
    @DisplayName("10. 회귀 — 기존 GET /api/risk-alerts 응답 형태 유지 · 수신자 무관 전 알림")
    void case10_existingAlertListUnaffected() {
        AlertPage withFixture = getAlerts("?size=100");

        // 응답 형태가 그대로다 (필드 구성이 바뀌면 미러 역직렬화·아래 단언에서 먼저 깨진다)
        assertThat(withFixture.content).isNotEmpty();
        assertThat(withFixture.page).isZero();
        assertThat(withFixture.size).isEqualTo(100);
        assertThat(withFixture.totalPages).isPositive();
        RiskAlertSummaryVO summary = withFixture.summary;
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalCnt()).isEqualTo(withFixture.totalElements);
        assertThat(summary.getCritCnt() + summary.getWarnCnt() + summary.getCautCnt())
                .isEqualTo(summary.getTotalCnt());
        assertThat(withFixture.content).allSatisfy(row -> {
            assertThat(row.getNotiId()).isNotNull();
            assertThat(row.getEmdCd()).isNotNull();
            assertThat(row.getRegionNm()).isEqualTo(row.getEmdNm());
            assertThat(row.getSggNm()).isIn("제주시", "서귀포시");
        });

        // 조회 화면은 수신자 무관이라 건수는 DB 의 알림 전체다(매핑 3건이 아니다).
        assertThat(withFixture.totalElements).isEqualTo(countAllAlerts());

        // ⚠️ 건수를 고정 숫자로 단언하지 않는다 — 환경마다 이력 건수가 다르다.
        //    픽스처를 지웠을 때의 차이가 **정확히 3**(= 픽스처 행 수)인지만 본다.
        clearYesterday();
        AlertPage withoutFixture = getAlerts("?size=100");
        assertThat(withFixture.totalElements - withoutFixture.totalElements)
                .isEqualTo(FIXTURE_COUNT);
        assertThat(withoutFixture.summary.getTotalCnt()).isEqualTo(withoutFixture.totalElements);
        assertThat(withoutFixture.totalElements).isEqualTo(countAllAlerts());
    }

    /** 조회 화면의 기대 건수(매퍼 alertFrom 과 같은 FROM · 수신자 조건 없음) */
    private long countAllAlerts() {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_risk_stp_noti_hstry h"
                + " JOIN parking.tb_admdst a ON a.emd_cd = h.emd_cd", Long.class);
        return value == null ? 0L : value;
    }

    // ---------------------------------------------------------------- 헬퍼 (응답 미러)

    /**
     * {@code RiskAlertPageVO} 를 그대로 역직렬화할 수 없어(부모 {@code PageResponse} 의 필드가 final 이고
     * 기본 생성자가 없다) 테스트 전용 미러를 둔다 — {@code RiskNotiIntegrationTest} 와 같은 방식이다.
     */
    static class AlertPage {
        public List<RiskAlertVO> content;
        public int page;
        public int size;
        public long totalElements;
        public int totalPages;
        public RiskAlertSummaryVO summary;
    }

    // ---------------------------------------------------------------- 헬퍼 (호출)

    /** 🔴 세션을 반드시 싣는다 — 익명이면 수신자 해석 이전에 401 이다. */
    private RiskAlertDailyVO getDaily() {
        ResponseEntity<RiskAlertDailyVO> response = restTemplate.exchange(DAILY_URL, HttpMethod.GET,
                new HttpEntity<>(session), RiskAlertDailyVO.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getItems()).isNotNull();
        return response.getBody();
    }

    /** 실패가 예상되는 호출도 있으므로 응답 타입은 항상 String 으로 받는다 (에러 바디 역직렬화 회피) */
    private ResponseEntity<String> markRead(Long notiId) {
        return restTemplate.exchange(ALERTS_URL + "/" + notiId + "/read",
                HttpMethod.PATCH, new HttpEntity<>(session), String.class);
    }

    private AlertPage getAlerts(String query) {
        ResponseEntity<AlertPage> response = restTemplate.exchange(ALERTS_URL + query, HttpMethod.GET,
                new HttpEntity<>(session), AlertPage.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content).isNotNull();
        return response.getBody();
    }

    // ---------------------------------------------------------------- 헬퍼 (DB 조회)

    /** 어제 구간의 건수. {@code where} 는 이력 테이블의 컬럼을 그대로 쓴다 */
    private int countYesterday(String where) {
        String sql = "SELECT count(*) FROM parking.tb_risk_stp_noti_hstry "
                + "WHERE ocrn_dt >= ? AND ocrn_dt < ?"
                + (where == null ? "" : " AND " + where);
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, YESTERDAY, YESTERDAY.plusDays(1));
        return value == null ? 0 : value;
    }

    /**
     * 어제 구간에서 <b>내 매핑 행</b>의 건수 (읽음이 여기로 옮겨갔다).
     *
     * @param where {@code TB_NOTI_HSTRY_RCVR} 의 컬럼 조건
     */
    private int countMyMapping(String where) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM parking.tb_noti_hstry_rcvr m
                  JOIN parking.tb_risk_stp_noti_hstry h ON h.noti_id = m.noti_id
                 WHERE m.rcvr_id = ? AND h.ocrn_dt >= ? AND h.ocrn_dt < ? AND m.""" + where,
                Integer.class, rcvrId, YESTERDAY, YESTERDAY.plusDays(1));
        return value == null ? 0 : value;
    }

    /** 어제 구간의 첫 행 — noti_id 값을 소스에 박지 않는다(identity 채번이라 실행마다 밀린다) */
    private Long firstYesterdayId() {
        Long notiId = jdbcTemplate.queryForObject(
                "SELECT noti_id FROM parking.tb_risk_stp_noti_hstry "
                        + "WHERE ocrn_dt >= ? AND ocrn_dt < ? ORDER BY noti_id LIMIT 1",
                Long.class, YESTERDAY, YESTERDAY.plusDays(1));
        assertThat(notiId).isNotNull();
        return notiId;
    }

    /** 🔴 내 매핑 행의 읽음 여부 — 이력 컬럼이 아니다 */
    private String prslYn(Long notiId) {
        String value = jdbcTemplate.queryForObject(
                "SELECT prsl_yn FROM parking.tb_noti_hstry_rcvr WHERE noti_id = ? AND rcvr_id = ?",
                String.class, notiId, rcvrId);
        return value == null ? null : value.trim();
    }

    private LocalDateTime prslDt(Long notiId) {
        return jdbcTemplate.queryForObject(
                "SELECT prsl_dt FROM parking.tb_noti_hstry_rcvr WHERE noti_id = ? AND rcvr_id = ?",
                LocalDateTime.class, notiId, rcvrId);
    }
}
