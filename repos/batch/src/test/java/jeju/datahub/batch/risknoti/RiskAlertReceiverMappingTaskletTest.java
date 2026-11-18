package jeju.datahub.batch.risknoti;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link RiskAlertReceiverMappingTasklet} 통합 검증 — <b>알림 × 수신자 매핑 적재</b>
 * (TSK-4202 Step 7 · 🔁 TSK-4250 Step 8 — 다지역 확장).
 *
 * <h2>🔁 TSK-4250 — 지역은 {@code TB_NOTI_RCVR_RGN} 행이다</h2>
 * 수신자의 단일 지역 칸({@code sgg_nm}·{@code emd_cd})은 DEPRECATED 라 이 테스트는 <b>채우지 않는다</b>.
 * 지역 의도는 케이스마다 {@code TB_NOTI_RCVR_RGN} 행으로 심는다 — 읍면동 지정 {@code (sgg, emd)} ·
 * 시군구 전체 {@code (sgg, NULL)} · 전체 지역 {@code (NULL, NULL)}(R3). 지역 행이 0개면
 * <b>아무 알림도 받지 않는다</b>(fail-closed — 케이스 10).
 * {@code TB_NOTI_RCVR.user_id} 가 NOT NULL + 활성 행 부분 유니크(R9)라 수신자마다 다른 합성
 * {@code user_id}({@value #USER_ID_PREFIX}{@code seq})를 넣는다 — 🔴 백엔드 테스트의
 * {@code 't4250-%'} 고아 정리에 걸리지 않게 접두를 달리한다(같은 DB 동시 실행 시 서로 지운다).
 *
 * <h2>🔴 왜 {@code @SpringBootTest} + {@code JdbcTemplate} 인가 (목이 아니다)</h2>
 * 배치 레포에 두 관례가 있지만({@code PblpkltSoftDeleteMarkingTaskletTest} 는 Mockito 목),
 * 아래 케이스는 <b>지역 3단 OR 분기 · {@code ON CONFLICT DO NOTHING} · 반열림 경계</b> 가 전부
 * {@link RiskAlertReceiverMappingTasklet#INSERT_HEAD} <b>SQL 안에</b> 있어 목으로는 아무것도
 * 검증되지 않는다. 하네스는 선례 {@link RiskAlertJudgeJobTest} 를 그대로 본뜬다.
 *
 * <p>Tasklet 을 직접 부르지 않고 <b>{@code riskAlertJudgeJob} 을 실행</b>한다 —
 * {@link RiskAlertReceiverMappingTasklet.PeriodMode#DAILY} 의 기간 해석
 * ({@code [targetDate, targetDate+1일)})까지 운영 경로 그대로 지나가야 케이스 7(반열림 경계)이
 * 의미를 갖기 때문이다. 판정 Step 은 <b>빈 판정일</b>(민원 0건)이라 0행을 읽고 끝나므로
 * 매핑 Step 이 보는 알림은 이 테스트가 심은 합성 알림뿐이다.
 *
 * <h2>🔴 단언은 전역 count 가 아니라 "합성 알림 × 합성 수신자" 로 스코프한다</h2>
 * 시드·화면 등록 수신자 중 <b>전체 지역(NULL 행) 수신자</b>가 있으면 SQL 의 <b>③ 전체 지역 분기</b>에
 * 걸려 <b>합성 알림 전부에 자동 매칭</b>된다. 게다가 시더·판정 배치가 매핑을 이미 깔아 둔다 —
 * {@code SELECT count(*) FROM parking.tb_noti_hstry_rcvr} 로 단언하면
 * <b>케이스 4·5(음성 케이스)가 원리적으로 성립하지 않는다.</b>
 * 그래서 모든 단언이 {@code WHERE noti_id IN (합성) AND rcvr_id IN (합성)} 이다.
 *
 * <h2>🔴 픽스처와 정리</h2>
 * <ul>
 *   <li>합성 알림의 {@code emd_cd} 는 <b>{@code TB_ADMDST} 에 실재하는 값</b>이다 — SQL 이 그 표를
 *       INNER JOIN 하고 FK {@code fk_tb_risk_stp_noti_hstry_emd_cd} 도 있다.</li>
 *   <li>합성 수신자는 <b>테스트 전용 이메일 접두</b>({@value #EML_PREFIX})와 <b>{@code user_id} 접두</b>
 *       ({@value #USER_ID_PREFIX})로 만든다 — 시드
 *       {@code rcvr_id} 1~12 를 재사용하지 않는다({@code RESTART WITH 100} 이라 신규는 100 이상).</li>
 *   <li>🔴 정리는 {@code @AfterEach} 가 정본이며 <b>매핑 → 지역 → 단계 → 수신자 → 알림 참조 역순</b>이다 —
 *       {@code fk_tb_noti_hstry_rcvr_rcvr} 에는 <b>CASCADE 가 없다</b>
 *       (알림 쪽 {@code fk_tb_noti_hstry_rcvr_noti} 만 CASCADE). 지역·단계는 CASCADE 지만 순서를
 *       눈에 보이게 하려고 명시 삭제한다.</li>
 *   <li>🔴 <b>시더가 심는 알림 · 수신자 · 매핑을 지우지 않는다</b> —
 *       조건 없는 {@code DELETE FROM parking.tb_noti_hstry_rcvr} 가 백필을 통째로 날린 전례가 있다.</li>
 * </ul>
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — Job 은 자기 트랜잭션에서 커밋한다(선례와 같은 근거).
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "complaint.sync.scheduled=false",
        "pblpklt.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class RiskAlertReceiverMappingTaskletTest {

    /** 빈 판정일 탐색 시작점 — 실데이터({@code 2025-11~2026-08})가 닿지 않는 과거 구간. */
    private static final LocalDate EMPTY_DAY_SEARCH_FROM = LocalDate.of(2000, 1, 2);

    private static final int EMPTY_DAY_SEARCH_DAYS = 366;

    /** 🔴 테스트 전용 수신자 식별 접두. {@code @AfterEach} 의 삭제 스코프가 이것 하나다. */
    private static final String EML_PREFIX = "aaa-rcvrtest-";

    /**
     * 🔴 합성 수신자 {@code user_id} 접두(TSK-4250 R9 — 사람당 활성 1행이라 수신자마다 다르게).
     * 백엔드 테스트의 {@code 't4250-%'} 와 겹치지 않게 {@code b} 를 붙인다. {@code VARCHAR(20)} 이내.
     */
    private static final String USER_ID_PREFIX = "t4250b-";

    /** 시군구명 — {@code TB_ADMDST.sgg_nm} 과 같은 표기. */
    private static final String SGG_JEJU = "제주시";

    private static final String SGG_SEOGWIPO = "서귀포시";

    /** {@code TB_ADMDST} 실재 코드 — 제주시 일도일동. */
    private static final String EMD_JEJU_A = "50110101";

    /** {@code TB_ADMDST} 실재 코드 — 제주시 일도이동(같은 시군구, 다른 읍면동). */
    private static final String EMD_JEJU_B = "50110102";

    /** {@code TB_ADMDST} 실재 코드 — 서귀포시 서귀동(다른 시군구). */
    private static final String EMD_SEOGWIPO = "50130101";

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final String INSERT_ALERT_SQL = """
            INSERT INTO parking.TB_RISK_STP_NOTI_HSTRY
                   (risk_lvl_cd, emd_cd, cvlcpt_cnt, ocrn_dt, prcs_stts_cd, incrs_rt, noti_ctt)
            VALUES ('CRIT', ?, 10, ?, 'OPEN', 200.00, '매핑 테스트 합성 알림')
            RETURNING noti_id""";

    private static final String INSERT_RCVR_SQL = """
            INSERT INTO parking.TB_NOTI_RCVR (rcvr_nm, eml, user_id, use_yn, del_yn)
            VALUES (?, ?, ?, ?, ?)
            RETURNING rcvr_id""";

    private static final String INSERT_RGN_SQL =
            "INSERT INTO parking.TB_NOTI_RCVR_RGN (rcvr_id, sgg_nm, emd_cd) VALUES (?, ?, ?)";

    /** 수신자 지역 1행 — {@code (sgg, emd)} 읍면동 지정 · {@code (sgg, null)} 시군구 전체 · {@code (null, null)} 전체(R3). */
    private record Rgn(String sggNm, String emdCd) {
        static Rgn emd(String sggNm, String emdCd) { return new Rgn(sggNm, emdCd); }
        static Rgn sgg(String sggNm) { return new Rgn(sggNm, null); }
        static final Rgn ALL = new Rgn(null, null);
    }

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job riskAlertJudgeJob;

    @Autowired
    private JdbcTemplate jdbc;

    private NamedParameterJdbcTemplate named;

    /** 판정일. {@code @BeforeEach} 가 DB 로 빈 날을 골라 정한다(상수로 박지 않는다). */
    private LocalDate judgeDate;

    /** 정리 하한(포함) = 판정일 전일 00:00 — 케이스 7 의 '전날 23:59' 픽스처까지 덮는다. */
    private LocalDateTime cleanupFrom;

    /** 정리 상한(미포함) = 판정일 +2일 00:00 — 케이스 7 의 '익일 00:00' 픽스처까지 덮는다. */
    private LocalDateTime cleanupTo;

    /** 이 테스트가 만든 알림 PK. 모든 단언과 정리의 스코프다. */
    private final List<Long> notiIds = new ArrayList<>();

    /** 이 테스트가 만든 수신자 PK. 모든 단언과 정리의 스코프다. */
    private final List<Long> rcvrIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        named = new NamedParameterJdbcTemplate(jdbc);
        notiIds.clear();
        rcvrIds.clear();
        // 앞선 실행이 비정상 종료해 남긴 합성 수신자를 먼저 걷는다 — 남아 있으면 ③ 전체 지역
        // 분기에 섞여 다음 케이스의 스코프 밖 매핑을 만든다.
        deleteOrphanReceivers();
        pickEmptyJudgeDate();
        // 고른 구간에 합성 알림이 남아 있으면 uq_risk_hstry_emd_ocrn 위반으로 INSERT 부터 죽는다.
        jdbc.update("DELETE FROM parking.TB_RISK_STP_NOTI_HSTRY WHERE ocrn_dt >= ? AND ocrn_dt < ?",
                cleanupFrom, cleanupTo);
    }

    /**
     * 🔴 <b>매핑 → 지역 → 단계 → 수신자 → 알림 참조 역순.</b> {@code fk_tb_noti_hstry_rcvr_rcvr} 에 CASCADE 가 없어
     * 매핑을 먼저 지우지 않으면 수신자 DELETE 가 FK 위반으로 죽는다.
     *
     * <p>🔴 모든 DELETE 가 <b>합성 행으로 스코프</b>된다 — 시드 알림 · 수신자 · 매핑은 건드리지 않는다.
     */
    @AfterEach
    void tearDown() {
        if (!notiIds.isEmpty() || !rcvrIds.isEmpty()) {
            Map<String, Object> params = new HashMap<>();
            params.put("notiIds", notiIds.isEmpty() ? List.of(-1L) : notiIds);
            params.put("rcvrIds", rcvrIds.isEmpty() ? List.of(-1L) : rcvrIds);
            // ① 매핑 — 합성 알림에 붙은 것(시드 수신자 3명분 포함)과 합성 수신자에 붙은 것 모두.
            named.update("DELETE FROM parking.TB_NOTI_HSTRY_RCVR"
                    + " WHERE noti_id IN (:notiIds) OR rcvr_id IN (:rcvrIds)", params);
        }
        // ②~④ 지역 → 단계 → 수신자 — 이메일·user_id 접두로만 좁힌다.
        deleteOrphanReceivers();
        // ⑤ 알림 — 고른 판정일 구간(전일~익일)으로만 좁힌다.
        jdbc.update("DELETE FROM parking.TB_RISK_STP_NOTI_HSTRY WHERE ocrn_dt >= ? AND ocrn_dt < ?",
                cleanupFrom, cleanupTo);
    }

    // ------------------------------------------------------------------ 케이스 1
    @Test
    @DisplayName("1. 읍면동 지정 수신자 + 그 읍면동 알림 → 매핑 1행 (① 분기)")
    void mapsEmdScopedReceiver() throws Exception {
        long noti = insertAlert(EMD_JEJU_A, judgeDate.atTime(9, 0));
        long rcvr = insertReceiver("Y", "N", Rgn.emd(SGG_JEJU, EMD_JEJU_A));

        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(mappingCount(noti, rcvr)).isOne();
    }

    // ------------------------------------------------------------------ 케이스 2
    @Test
    @DisplayName("2. 시군구 전체 수신자(지역 행 emd_cd NULL) + 같은 시군구 알림 → 매핑 1행 (② 분기)")
    void mapsSggScopedReceiver() throws Exception {
        // 🔴 수신자가 고른 읍면동과 다른 읍면동의 알림이라야 ②가 ①에 묻히지 않는다.
        long noti = insertAlert(EMD_JEJU_B, judgeDate.atTime(9, 0));
        long rcvr = insertReceiver("Y", "N", Rgn.sgg(SGG_JEJU));

        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(mappingCount(noti, rcvr)).isOne();
    }

    // ------------------------------------------------------------------ 케이스 3
    @Test
    @DisplayName("3. 전체 지역 수신자(지역 행 둘 다 NULL) + 아무 알림 → 매핑 1행 (③ 분기)")
    void mapsGlobalReceiver() throws Exception {
        long jeju = insertAlert(EMD_JEJU_A, judgeDate.atTime(9, 0));
        long seogwipo = insertAlert(EMD_SEOGWIPO, judgeDate.atTime(9, 0));
        long rcvr = insertReceiver("Y", "N", Rgn.ALL);

        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 시군구를 가리지 않는다 — 두 알림 모두 각각 1행.
        assertThat(mappingCount(jeju, rcvr)).isOne();
        assertThat(mappingCount(seogwipo, rcvr)).isOne();
    }

    // ------------------------------------------------------------------ 케이스 4
    @Test
    @DisplayName("4. 🔴 다른 시군구 알림 → 매핑 0행 (지역이 실제로 걸러진다)")
    void doesNotMapOtherSgg() throws Exception {
        long noti = insertAlert(EMD_JEJU_A, judgeDate.atTime(9, 0));          // 제주시 알림
        long otherSgg = insertReceiver("Y", "N", Rgn.sgg(SGG_SEOGWIPO));               // ② 가 안 맞는다
        long otherEmd = insertReceiver("Y", "N", Rgn.emd(SGG_JEJU, EMD_JEJU_B));       // ① 이 안 맞는다

        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 🔴 전역 count 로는 성립하지 않는 단언이다 — 시드의 전체 지역 수신자가 ③으로 붙는다.
        assertThat(mappingCount(noti, otherSgg)).isZero();
        assertThat(mappingCount(noti, otherEmd)).isZero();
    }

    // ------------------------------------------------------------------ 케이스 5
    @Test
    @DisplayName("5. 🔴 use_yn='N' · del_yn='Y' 수신자 → 매핑 0행 (활성 조건)")
    void doesNotMapInactiveReceivers() throws Exception {
        long noti = insertAlert(EMD_JEJU_A, judgeDate.atTime(9, 0));
        long unused = insertReceiver("N", "N", Rgn.emd(SGG_JEJU, EMD_JEJU_A));    // 미사용
        long deleted = insertReceiver("Y", "Y", Rgn.emd(SGG_JEJU, EMD_JEJU_A));   // 삭제됨
        long global = insertReceiver("N", "N", Rgn.ALL);                          // ③ 분기여도 비활성

        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(mappingCount(noti, unused)).isZero();
        assertThat(mappingCount(noti, deleted)).isZero();
        assertThat(mappingCount(noti, global)).isZero();
    }

    // ------------------------------------------------------------------ 케이스 6
    @Test
    @DisplayName("6. 🔴 같은 파라미터로 2회 실행해도 행 수가 같다 — ON CONFLICT DO NOTHING 멱등")
    void isIdempotentAcrossReruns() throws Exception {
        insertAlert(EMD_JEJU_A, judgeDate.atTime(9, 0));
        insertAlert(EMD_SEOGWIPO, judgeDate.atTime(9, 0));
        insertReceiver("Y", "N", Rgn.emd(SGG_JEJU, EMD_JEJU_A));
        insertReceiver("Y", "N", Rgn.sgg(SGG_SEOGWIPO));
        insertReceiver("Y", "N", Rgn.ALL);
        // 🔁 TSK-4250 — 다지역 수신자도 재실행에 행이 늘지 않아야 한다.
        insertReceiver("Y", "N", Rgn.emd(SGG_JEJU, EMD_JEJU_A), Rgn.sgg(SGG_SEOGWIPO));

        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long afterFirst = scopedMappingCount();
        assertThat(afterFirst).isPositive();          // 아무것도 안 들어갔으면 멱등 단언이 공허하다

        // 멱등성이 없으면 여기서 PK(noti_id, rcvr_id) 위반으로 Job 이 FAILED 다.
        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(scopedMappingCount()).isEqualTo(afterFirst);
    }

    // ------------------------------------------------------------------ 케이스 7
    @Test
    @DisplayName("7. 🔴 판정일 밖(전날 23:59 / 익일 00:00) 알림 → 매핑 0행 — 반열림 [D, D+1)")
    void respectsHalfOpenDayBoundary() throws Exception {
        long before = insertAlert(EMD_JEJU_A, judgeDate.minusDays(1).atTime(23, 59));
        long after = insertAlert(EMD_JEJU_B, judgeDate.plusDays(1).atStartOfDay());
        // 경계 바로 안쪽 — '아무것도 안 돌았다' 와 '경계가 걸렀다' 를 가른다.
        long inside = insertAlert(EMD_SEOGWIPO, judgeDate.atStartOfDay());
        long rcvr = insertReceiver("Y", "N", Rgn.ALL);                    // ③ 전체 지역

        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(mappingCount(inside, rcvr)).isOne();
        assertThat(mappingCount(before, rcvr)).isZero();
        assertThat(mappingCount(after, rcvr)).isZero();
    }

    // ------------------------------------------------------------------ 케이스 8 (TSK-4250)
    @Test
    @DisplayName("8. 🔴 한 수신자가 두 지역 담당 → 두 지역 알림 모두 매핑, 담당 밖은 0행, 알림당 1행")
    void mapsMultiRegionReceiverOncePerAlert() throws Exception {
        long jejuA = insertAlert(EMD_JEJU_A, judgeDate.atTime(9, 0));        // ① 읍면동 지정에 걸린다
        long seogwipo = insertAlert(EMD_SEOGWIPO, judgeDate.atTime(9, 0));   // ② 시군구 전체에 걸린다
        long jejuB = insertAlert(EMD_JEJU_B, judgeDate.atTime(9, 0));        // 어느 지역 행에도 안 걸린다
        long rcvr = insertReceiver("Y", "N",
                Rgn.emd(SGG_JEJU, EMD_JEJU_A), Rgn.sgg(SGG_SEOGWIPO));

        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 🔴 지역 행이 2개여도 매핑은 알림당 1행이다(EXISTS 라 지역 행 수만큼 곱해지지 않는다).
        assertThat(mappingCount(jejuA, rcvr)).isOne();
        assertThat(mappingCount(seogwipo, rcvr)).isOne();
        assertThat(mappingCount(jejuB, rcvr)).isZero();
    }

    // ------------------------------------------------------------------ 케이스 9 (TSK-4250)
    @Test
    @DisplayName("9. 🔴 겹치는 지역 행(시군구 전체 + 그 안의 읍면동) → 같은 알림에 매핑 1행")
    void overlappingRegionsDoNotDuplicateMapping() throws Exception {
        long jejuA = insertAlert(EMD_JEJU_A, judgeDate.atTime(9, 0));
        long jejuB = insertAlert(EMD_JEJU_B, judgeDate.atTime(9, 0));
        long seogwipo = insertAlert(EMD_SEOGWIPO, judgeDate.atTime(9, 0));
        // ①·② 두 분기가 jejuA 에 동시에 성립한다 — EXISTS 라 수신자당 1행으로 판정된다(JOIN 이었어도 ON CONFLICT DO NOTHING 이 같은 문장 안 중복을 흡수해 결과는 같다 — 이 케이스는 "알림당 1행" 결과를 고정한다).
        long rcvr = insertReceiver("Y", "N",
                Rgn.sgg(SGG_JEJU), Rgn.emd(SGG_JEJU, EMD_JEJU_A));

        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(mappingCount(jejuA, rcvr)).isOne();
        assertThat(mappingCount(jejuB, rcvr)).isOne();
        assertThat(mappingCount(seogwipo, rcvr)).isZero();
        assertThat(receiverMappingCount(rcvr)).isEqualTo(2L);
    }

    // ------------------------------------------------------------------ 케이스 10 (TSK-4250)
    @Test
    @DisplayName("10. 🔴 지역 행 0개 수신자 → 알림 0건 (R3 fail-closed 회귀)")
    void receiverWithoutRegionsGetsNothing() throws Exception {
        long jejuA = insertAlert(EMD_JEJU_A, judgeDate.atTime(9, 0));
        long seogwipo = insertAlert(EMD_SEOGWIPO, judgeDate.atTime(9, 0));
        // 계획서대로 지역 행을 심었다가 직접 DELETE 해 "저장 누락" 상태를 만든다.
        long noRegion = insertReceiver("Y", "N", Rgn.ALL);
        jdbc.update("DELETE FROM parking.TB_NOTI_RCVR_RGN WHERE rcvr_id = ?", noRegion);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking.TB_NOTI_RCVR_RGN WHERE rcvr_id = ?", Long.class, noRegion))
                .as("하네스 — 지역 행이 실제로 0개여야 fail-closed 단언이 의미를 갖는다")
                .isZero();
        // 대조군 — '아무것도 안 돌았다' 와 'fail-closed 가 걸렀다' 를 가른다.
        long control = insertReceiver("Y", "N", Rgn.ALL);

        assertThat(runJudgeJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(mappingCount(jejuA, control)).isOne();
        assertThat(mappingCount(seogwipo, control)).isOne();
        // 🔴 지역 행이 없으면 "전체" 가 아니라 "아무것도 아님" 이다.
        assertThat(mappingCount(jejuA, noRegion)).isZero();
        assertThat(mappingCount(seogwipo, noRegion)).isZero();
    }

    // ------------------------------------------------------------------ 헬퍼

    /**
     * 운영 경로 그대로 {@code riskAlertJudgeJob} 을 돌린다 — 판정 Step(빈 날이라 0행) 다음에
     * 매핑 Step 이 {@code targetDate} 로 기간을 잡는다.
     */
    private JobExecution runJudgeJob() throws Exception {
        return jobLauncher.run(riskAlertJudgeJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime())              // 매 실행 새 JobInstance
                .addString("targetDate", judgeDate.toString())
                .toJobParameters());
    }

    private long insertAlert(String emdCd, LocalDateTime ocrnDt) {
        Long notiId = jdbc.queryForObject(INSERT_ALERT_SQL, Long.class, emdCd, Timestamp.valueOf(ocrnDt));
        notiIds.add(notiId);
        return notiId;
    }

    /**
     * 합성 수신자 1명 + 지역 행. 🔴 {@code user_id} 는 수신자마다 다르다(R9 — 사람당 활성 1행).
     * 단일 지역 칸({@code sgg_nm}·{@code emd_cd})은 DEPRECATED 라 채우지 않는다(R4).
     */
    private long insertReceiver(String useYn, String delYn, Rgn... regions) {
        int seq = SEQ.incrementAndGet();
        Long rcvrId = jdbc.queryForObject(INSERT_RCVR_SQL, Long.class,
                "매핑테스트" + seq, EML_PREFIX + seq + "@test.invalid", USER_ID_PREFIX + seq, useYn, delYn);
        rcvrIds.add(rcvrId);
        for (Rgn rgn : regions) {
            jdbc.update(INSERT_RGN_SQL, rcvrId, rgn.sggNm(), rgn.emdCd());
        }
        return rcvrId;
    }

    /** 합성 수신자 1명의 매핑 건수 — 합성 알림으로 스코프한다(시드 알림 매핑이 섞이지 않게). */
    private long receiverMappingCount(long rcvrId) {
        Map<String, Object> params = new HashMap<>();
        params.put("notiIds", notiIds);
        params.put("rcvrId", rcvrId);
        return named.queryForObject("SELECT count(*) FROM parking.TB_NOTI_HSTRY_RCVR"
                + " WHERE noti_id IN (:notiIds) AND rcvr_id = :rcvrId", params, Long.class);
    }

    /** 🔴 합성 알림 1건 × 합성 수신자 1명으로 좁힌 매핑 건수. 전역 count 를 쓰지 않는 이유는 클래스 주석. */
    private long mappingCount(long notiId, long rcvrId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM parking.TB_NOTI_HSTRY_RCVR WHERE noti_id = ? AND rcvr_id = ?",
                Long.class, notiId, rcvrId);
    }

    /** 합성 알림 × 합성 수신자 전체로 좁힌 매핑 건수(케이스 6). */
    private long scopedMappingCount() {
        Map<String, Object> params = new HashMap<>();
        params.put("notiIds", notiIds);
        params.put("rcvrIds", rcvrIds);
        return named.queryForObject("SELECT count(*) FROM parking.TB_NOTI_HSTRY_RCVR"
                + " WHERE noti_id IN (:notiIds) AND rcvr_id IN (:rcvrIds)", params, Long.class);
    }

    /**
     * 이메일 접두 + {@code user_id} 접두로만 좁힌 수신자 정리 — <b>매핑 → 지역 → 단계 → 수신자</b>.
     * 매핑은 CASCADE 가 없어 반드시 먼저, 지역·단계는 CASCADE 지만 명시 삭제한다.
     * 🔴 {@code user_id} 접두도 보는 것은 고아 행이 {@code uq_tb_noti_rcvr_user} 를 점유하지 않게 하려는 것이다.
     */
    private void deleteOrphanReceivers() {
        String scope = "SELECT r.rcvr_id FROM parking.TB_NOTI_RCVR r WHERE r.eml LIKE ? OR r.user_id LIKE ?";
        Object[] args = {EML_PREFIX + "%", USER_ID_PREFIX + "%"};
        jdbc.update("DELETE FROM parking.TB_NOTI_HSTRY_RCVR WHERE rcvr_id IN (" + scope + ")", args);
        jdbc.update("DELETE FROM parking.TB_NOTI_RCVR_RGN WHERE rcvr_id IN (" + scope + ")", args);
        jdbc.update("DELETE FROM parking.TB_NOTI_RCVR_LVL WHERE rcvr_id IN (" + scope + ")", args);
        jdbc.update("DELETE FROM parking.TB_NOTI_RCVR WHERE eml LIKE ? OR user_id LIKE ?", args);
    }

    /**
     * 전일~익일에 <b>민원·알림이 한 건도 없는</b> 첫 날을 판정일로 고른다(선례 {@link RiskAlertJudgeJobTest} 와 같은 장치).
     * 민원이 없어야 판정 Step 이 알림을 만들지 않고, 알림이 없어야
     * {@code uq_risk_hstry_emd_ocrn} 과 정리 범위가 합성 행만 건드린다.
     * 🔴 익일까지 보는 것은 케이스 7 이 <b>{@code D+1 00:00}</b> 에도 알림을 심기 때문이다.
     */
    private void pickEmptyJudgeDate() {
        List<LocalDate> found = jdbc.queryForList("""
                SELECT d::date
                  FROM generate_series(CAST(? AS date), CAST(? AS date), INTERVAL '1 day') d
                 WHERE NOT EXISTS (SELECT 1 FROM parking.tb_cvlcpt c
                                    WHERE c.ocrn_dt >= d - INTERVAL '1 day' AND c.ocrn_dt < d + INTERVAL '2 day')
                   AND NOT EXISTS (SELECT 1 FROM parking.tb_risk_stp_noti_hstry h
                                    WHERE h.ocrn_dt >= d - INTERVAL '1 day' AND h.ocrn_dt < d + INTERVAL '2 day')
                 ORDER BY d
                 LIMIT 1""",
                LocalDate.class,
                java.sql.Date.valueOf(EMPTY_DAY_SEARCH_FROM),
                java.sql.Date.valueOf(EMPTY_DAY_SEARCH_FROM.plusDays(EMPTY_DAY_SEARCH_DAYS - 1)));
        assertThat(found)
                .as("판정일 후보 %s 부터 %d일 안에 전일~익일이 모두 빈 날이 없다 — 로컬 DB 데이터 구간을 확인할 것",
                        EMPTY_DAY_SEARCH_FROM, EMPTY_DAY_SEARCH_DAYS)
                .isNotEmpty();
        judgeDate = found.get(0);
        cleanupFrom = judgeDate.minusDays(1).atStartOfDay();
        cleanupTo = judgeDate.plusDays(2).atStartOfDay();
    }
}
