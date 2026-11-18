package jeju.datahub.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.ReportSettingVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 보고서 목록 · 자동 생성 설정 API 통합 테스트.
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도 스레드·별도
 * 커넥션으로 처리하므로 테스트 트랜잭션이 서버 쪽 쓰기를 감싸지 못한다(롤백이 걸리지 않는다). 더구나 롤백이
 * 걸리는 {@code MockMvc + @Transactional} 조합을 택하면 <b>테스트 4(트랜잭션 밖 재조회)</b>가 성립하지 않아
 * 정작 잡으려던 MyBatis 1차 캐시 / JPA flush 누락 버그를 못 잡는다. 그래서
 * "실제 커밋 + 수동 정리({@link #cleanUpCreated()})"를 쓴다. ({@code MenuAuthrtIntegrationTest} 와 같은 전략)
 *
 * <p>🔴 <b>이 테스트는 더 이상 시드를 전제하지 않는다.</b>
 * 종전에는 {@code @AfterEach restoreSeed()} 가 "시드 상태로 되돌린다"는 이름으로
 * <b>보고서 14행 · 설정 4행 · 실행이력 14행</b>을 다시 채워 넣었는데,
 * 시드({@code database/migrations/sql/seeding/08-TB_RPTP.sql})는 이미 그 목데이터를 전부 걷은 뒤였다 —
 * <b>없는 시드를 테스트가 만들어 내고 있었다.</b> 그대로 두면 이 테스트가 한 번 도는 것만으로
 * {@code tb_rptp} 가 0행에서 14행이 되어, 보고서 자동 생성 배치의 실측 기준이 깨진다.
 *
 * <p>대신 <b>이 테스트가 자기 픽스처를 만들고 자기가 치운다</b>:
 * <ul>
 *   <li>{@link #installFixture()} 가 {@code @BeforeEach} 에서 실행이력 14 · 설정 4 · AUTO 보고서 14행을
 *       넣는다. PK 는 하드코딩이 아니라 <b>기존 최댓값 + n</b>({@link #rptpBase} 등)이라 실 데이터와 겹치지 않는다.</li>
 *   <li>{@link #cleanUpCreated()} 가 {@code @AfterEach} 에서 <b>그 최댓값보다 큰 행만</b> 지운다 —
 *       사용자·다른 테스트가 만든 행은 건드리지 않는다.</li>
 *   <li>건수 단언은 <b>델타 기반</b>이다 — {@code @BeforeEach} 가 읽어 둔 기준 건수
 *       ({@link #baselineAutoCnt})에 픽스처 수를 더해 비교한다. 시드가 0행이든 실 데이터가 쌓였든 통과한다.</li>
 * </ul>
 * 🔴 <b>{@code seedDepts()} 7행만은 정리하지 않는다</b> — {@code RiskNotiIntegrationTest} 와 공유하는
 * 픽스처라 한쪽이 지우면 다른 쪽이 깨진다.
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다 — 모든 프로파일이 PostgreSQL 이고 H2 는 없다.
 *
 * <p>응답 바디의 {@code message} 문자열은 단언하지 않는다 — 로컬에서는 devtools 가
 * {@code server.error.include-message=always} 로 덮어써 보이지만 jar 배포에는 devtools 가 없어 사라진다.
 * 환경에 따라 깨지는 단언이 되므로 <b>상태코드만</b> 단언한다. 같은 이유로 실패가 예상되는 호출은
 * 응답 타입을 {@code String.class} 로 받는다(에러 바디를 VO 로 역직렬화하려다 터지는 것을 막는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportIntegrationTest {

    private static final String REPORTS_URL = "/api/reports";
    private static final String SETTINGS_URL = "/api/report-settings";

    /** 픽스처가 만드는 AUTO 보고서 수 (= 실행이력 수). 기본 페이지 크기 10 을 넘겨 페이징을 태운다 */
    private static final int FIXTURE_RPTP_CNT = 14;

    /** 픽스처가 만드는 자동 생성 설정 수 (월간·분기·반기·연간 4종) */
    private static final int FIXTURE_STNG_CNT = 4;

    /**
     * 픽스처 PK 를 기존 최댓값에서 얼마나 띄울지. identity 시퀀스를 {@code base + 이 값} 으로 밀어
     * API 가 만드는 행(≥ base+50)이 픽스처 구간(base+1 ~ base+15)과 겹치지 않게 한다.
     */
    private static final int SEQUENCE_GAP = 50;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 🔴 {@code POST/PUT/DELETE /api/report-settings/**} 가 {@code M0402}
     * (보고서 관리) 매핑을 요구하게 됐다({@code @PreAuthorize}). 메서드 보안은 test 체인의
     * {@code permitAll} 과 무관하게 익명 호출을 403 으로 막으므로 설정 쓰기 8곳이 세션을 타야 한다.
     * 조회는 종전대로 익명이다 — 쓰기 API 만 잠갔다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    /**
     * 픽스처를 넣기 <b>전</b> 의 PK 최댓값. 정리 범위({@code > base})이자 픽스처 PK 의 기준점이다.
     *
     * <p>🔴 초기값이 {@link #BASE_UNSET}(-1) 인 것이 안전장치다. {@code long} 기본값 0 으로 두면,
     * {@code @BeforeEach} 가 이 값을 대입하기 <b>전</b>(로그인·{@code seedDepts()} 실패)에 터졌을 때
     * JUnit 이 그래도 실행하는 {@code @AfterEach} 가 {@code DELETE ... WHERE rptp_id > 0} 을 돌려
     * <b>실 데이터를 전부 지운다</b>(로컬의 자동 생성 설정 {@code stng_id=100} 포함).
     */
    private static final long BASE_UNSET = -1L;

    private long rptpBase = BASE_UNSET;
    private long stngBase = BASE_UNSET;
    private long excnBase = BASE_UNSET;

    /** 픽스처를 넣기 <b>전</b> 의 건수. 모든 총건수 단언은 이 값 + 픽스처 수로 한다 (델타 기반) */
    private long baselineAutoCnt;

    @BeforeEach
    void loginWithMenuGrant() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        // ① mock-login(ROLE_USER) → ② ROLE_USER 에 M0402 부여. 재로그인 없음(매 요청 조회).
        session = auth.mockSessionWithMenus("M0402");
        // 🔴 부서 픽스처 — TB_DEPT 가 빈 표로 시작하므로 설정 등록 전에 먼저 넣는다
        seedDepts();

        rptpBase = maxId("parking.tb_rptp", "rptp_id");
        stngBase = maxId("parking.tb_rptp_auto_crt_stng", "stng_id");
        excnBase = maxId("parking.tb_anls_excn", "excn_id");
        baselineAutoCnt = countReports("AUTO");

        installFixture();
    }

    /**
     * 🔴 부서 픽스처 7행.
     *
     * <p>공통코드 {@code DEPT_CD} 가 삭제돼 {@code parking.TB_DEPT} 가 부서의 정본이 됐고, 그 표는
     * IdP {@code groups} 의 투영이라 <b>빈 표로 시작</b>한다. 이 7행이 없으면 아래 케이스들의
     * 수신자·설정 등록이 <b>전부 400</b> 이 되어 테스트가 통째로 깨진다.
     *
     * <p>🔴 <b>{@code @BeforeEach} 에 둔다</b> — 정리 단계에만 두면 빈 표로 시작하는 첫 실행의
     * <b>첫 케이스</b>가 400 으로 깨진다({@code dept_cd} 에는 FK 가 없어 INSERT 순서 제약도 없다).
     *
     * <p>🔴 <b>정리(DELETE)하지 않는다</b> — {@code RiskNotiIntegrationTest} 와
     * {@code ReportIntegrationTest} 가 같은 7코드를 쓰므로 한쪽이 지우면 다른 쪽이 깨진다.
     * {@code ON CONFLICT DO NOTHING} 이라 반복 실행도 안전하고, 사후 보정한 {@code dept_nm} 도 덮지 않는다.
     * ⚠️ 그래서 이 테스트를 한 번 돌리면 {@code /api/depts} 가 이 7행을 계속 돌려준다.
     *
     * <p>🔴 값·순서는 삭제된 공통코드 {@code DEPT_CD} 7행
     * ({@code database/migrations/sql/36-report-codes.sql:18~24})과 <b>동일</b>하다. 리터럴은 한 자도 고치지 않는다.
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

    // ---------------------------------------------------------------- 픽스처

    /**
     * 이 테스트가 쓰는 보고서 픽스처를 만든다 — 실행이력 14 · 설정 4 · AUTO 보고서 14행.
     *
     * <p>🔴 <b>시드 복원이 아니다.</b> 시드는 이 목데이터를 걷었고, 여기 넣는 값은
     * <b>이 테스트만의 픽스처</b>다 — {@link #cleanUpCreated()} 가 매 케이스 끝에 도로 지운다.
     *
     * <p>🔴 PK 는 하드코딩하지 않는다 — {@code base + n} 이라 실 데이터·사용자 생성분과 겹치지 않고,
     * 정리도 {@code > base} 한 줄로 끝난다. 넣은 뒤 identity 시퀀스를 {@code base + SEQUENCE_GAP} 으로
     * 밀어 API 가 만드는 행이 픽스처 구간을 재사용하지 않게 한다(명시 INSERT 는 identity 가 세지 않는다).
     *
     * <p>지역은 3형태를 모두 덮는다 — 읍면동 지정(제주시 연동) 8건 · 시군구 전체(제주시 3 · 서귀포시 2) ·
     * 전체 지역 1건. 주기는 MONTH·QUARTER·HALF·YEAR 4종이다.
     *
     * <p>⚠️ 보고서의 {@code file_fmt} 는 <b>NULL</b> 로 넣는다 — 자동 생성분은 형식을 고를 창구가 없다
     * (마이그레이션 72). 여기서 NOT NULL 위반이 나면 그 마이그레이션이 아직 안 돌았다는 뜻이다.
     */
    private void installFixture() {
        String yeondongEmdCd = jdbcTemplate.queryForObject("""
                SELECT emd_cd FROM parking.tb_admdst
                 WHERE sgg_nm = '제주시' AND emd_nm = '연동' ORDER BY emd_cd LIMIT 1""", String.class);
        assertThat(yeondongEmdCd).as("행정동 시드(제주시 연동)가 있어야 한다").isNotNull();

        // ① 실행 이력 14행 — 1~8 제주시 연동 / 9~11 제주시 / 12~13 서귀포시 / 14 전체(HOTSPOT)
        jdbcTemplate.update("""
                INSERT INTO parking.tb_anls_excn
                  (excn_id, anls_type_cd, excutr_id, sgg_nm, emd_cd, anls_bgng_ymd, anls_end_ymd, anls_param_json, excn_stts_cd, excn_dt) VALUES
                  (%1$d+1, 'CVLCPT', 'test', '제주시',  '%2$s','20260601','20260630', NULL,'DONE', TIMESTAMP '2026-07-01 09:00'),
                  (%1$d+2, 'CVLCPT', 'test', '제주시',  '%2$s','20260501','20260531', NULL,'DONE', TIMESTAMP '2026-06-01 09:00'),
                  (%1$d+3, 'CVLCPT', 'test', '제주시',  '%2$s','20260401','20260430', NULL,'DONE', TIMESTAMP '2026-05-01 09:00'),
                  (%1$d+4, 'CVLCPT', 'test', '제주시',  '%2$s','20260301','20260331', NULL,'DONE', TIMESTAMP '2026-04-01 09:00'),
                  (%1$d+5, 'CVLCPT', 'test', '제주시',  '%2$s','20260201','20260228', NULL,'DONE', TIMESTAMP '2026-03-01 09:00'),
                  (%1$d+6, 'CVLCPT', 'test', '제주시',  '%2$s','20260101','20260131', NULL,'DONE', TIMESTAMP '2026-02-01 09:00'),
                  (%1$d+7, 'CVLCPT', 'test', '제주시',  '%2$s','20251201','20251231', NULL,'DONE', TIMESTAMP '2026-01-01 09:00'),
                  (%1$d+8, 'CVLCPT', 'test', '제주시',  '%2$s','20251101','20251130', NULL,'DONE', TIMESTAMP '2025-12-01 09:00'),
                  (%1$d+9, 'CVLCPT', 'test', '제주시',   NULL, '20260401','20260630', NULL,'DONE', TIMESTAMP '2026-07-01 09:00'),
                  (%1$d+10,'CVLCPT', 'test', '제주시',   NULL, '20260101','20260331', NULL,'DONE', TIMESTAMP '2026-04-01 09:00'),
                  (%1$d+11,'CVLCPT', 'test', '제주시',   NULL, '20251001','20251231', NULL,'DONE', TIMESTAMP '2026-01-01 09:00'),
                  (%1$d+12,'CVLCPT', 'test', '서귀포시', NULL, '20260101','20260630', NULL,'DONE', TIMESTAMP '2026-07-01 09:00'),
                  (%1$d+13,'CVLCPT', 'test', '서귀포시', NULL, '20250701','20251231', NULL,'DONE', TIMESTAMP '2026-01-01 09:00'),
                  (%1$d+14,'HOTSPOT','test', NULL,       NULL, '20250101','20251231', NULL,'DONE', TIMESTAMP '2026-01-01 09:00')
                """.formatted(excnBase, yeondongEmdCd));

        // ② 자동 생성 설정 4행 — 주기 4종 · 지역 3형태
        jdbcTemplate.update("""
                INSERT INTO parking.tb_rptp_auto_crt_stng
                  (stng_id, rptp_type_cd, crltn_dept_cd, crltn_cycl_cd, crltn_mm, crltn_dd,
                   actvtn_yn, creatr_id, creatr_nm, crt_dt, last_crt_dt, sgg_nm, emd_cd) VALUES
                  (%1$d+1,'CVLCPT', 'TRFC','MONTH',   NULL, 1, TRUE, 'test','테스트', TIMESTAMP '2026-05-01 14:21', TIMESTAMP '2026-05-01 14:21', '제주시',   '%2$s'),
                  (%1$d+2,'CVLCPT', 'PARK','QUARTER',    4, 1, TRUE, 'test','테스트', TIMESTAMP '2026-04-01 14:21', TIMESTAMP '2026-04-01 14:21', '제주시',   NULL),
                  (%1$d+3,'CVLCPT', 'PARK','HALF',       7, 1, TRUE, 'test','테스트', TIMESTAMP '2026-07-01 14:21', TIMESTAMP '2026-07-01 14:21', '서귀포시', NULL),
                  (%1$d+4,'HOTSPOT','ALL', 'YEAR',       1, 1, TRUE, 'test','테스트', TIMESTAMP '2026-07-31 14:21', TIMESTAMP '2026-07-31 14:21', NULL,      NULL)
                """.formatted(stngBase, yeondongEmdCd));

        // ③ AUTO 보고서 14행 — 설정·실행을 짝지어 참조하고 주기·부서 스냅샷 2칸을 함께 담는다
        jdbcTemplate.update("""
                INSERT INTO parking.tb_rptp
                  (rptp_id, crt_type, stng_id, excn_id, rptp_nm, anls_prd_expln, stts_cd, file_fmt, file_path,
                   creatr_id, creatr_nm, crt_ymd, trgt_prd_cd, crltn_dept_cd, crltn_cycl_cd) VALUES
                  (%1$d+1, 'AUTO',%2$d+1,%3$d+1, '2026년 6월 월간 민원 보고서',  NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-07-01 09:00', '2026-06', 'TRFC','MONTH'),
                  (%1$d+2, 'AUTO',%2$d+1,%3$d+2, '2026년 5월 월간 민원 보고서',  NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-06-01 09:00', '2026-05', 'TRFC','MONTH'),
                  (%1$d+3, 'AUTO',%2$d+1,%3$d+3, '2026년 4월 월간 민원 보고서',  NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-05-01 09:00', '2026-04', 'TRFC','MONTH'),
                  (%1$d+4, 'AUTO',%2$d+1,%3$d+4, '2026년 3월 월간 민원 보고서',  NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-04-01 09:00', '2026-03', 'TRFC','MONTH'),
                  (%1$d+5, 'AUTO',%2$d+1,%3$d+5, '2026년 2월 월간 민원 보고서',  NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-03-01 09:00', '2026-02', 'TRFC','MONTH'),
                  (%1$d+6, 'AUTO',%2$d+1,%3$d+6, '2026년 1월 월간 민원 보고서',  NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-02-01 09:00', '2026-01', 'TRFC','MONTH'),
                  (%1$d+7, 'AUTO',%2$d+1,%3$d+7, '2025년 12월 월간 민원 보고서', NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-01-01 09:00', '2025-12', 'TRFC','MONTH'),
                  (%1$d+8, 'AUTO',%2$d+1,%3$d+8, '2025년 11월 월간 민원 보고서', NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2025-12-01 09:00', '2025-11', 'TRFC','MONTH'),
                  (%1$d+9, 'AUTO',%2$d+2,%3$d+9, '2026년 2/4분기 민원 보고서',   NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-07-01 09:00', '2026-Q2', 'PARK','QUARTER'),
                  (%1$d+10,'AUTO',%2$d+2,%3$d+10,'2026년 1/4분기 민원 보고서',   NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-04-01 09:00', '2026-Q1', 'PARK','QUARTER'),
                  (%1$d+11,'AUTO',%2$d+2,%3$d+11,'2025년 4/4분기 민원 보고서',   NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-01-01 09:00', '2025-Q4', 'PARK','QUARTER'),
                  (%1$d+12,'AUTO',%2$d+3,%3$d+12,'2026년 상반기 민원 보고서',    NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-07-01 09:00', '2026-H1', 'PARK','HALF'),
                  (%1$d+13,'AUTO',%2$d+3,%3$d+13,'2025년 하반기 민원 보고서',    NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-01-01 09:00', '2025-H2', 'PARK','HALF'),
                  (%1$d+14,'AUTO',%2$d+4,%3$d+14,'2025년 전체 민원 보고서',      NULL,'DONE',NULL,NULL,'test','테스트', TIMESTAMP '2026-01-01 09:00', '2025',    'ALL', 'YEAR')
                """.formatted(rptpBase, stngBase, excnBase));

        // ④ identity 를 픽스처 구간 뒤로 민다 — 명시 INSERT 는 시퀀스를 올리지 않으므로(BY DEFAULT)
        //    이걸 빼면 API 가 만드는 다음 행이 픽스처 PK 를 재사용해 PK 충돌(500)이 난다.
        advanceIdentity("parking.tb_anls_excn", "excn_id", excnBase);
        advanceIdentity("parking.tb_rptp", "rptp_id", rptpBase);
        advanceIdentity("parking.tb_rptp_auto_crt_stng", "stng_id", stngBase);
    }

    /**
     * 이 테스트가 만든 것만 지운다 — 픽스처 · API 로 만든 보고서/설정/실행이력.
     *
     * <p>🔴 <b>복원(INSERT)하지 않는다</b>. 종전 {@code restoreSeed()} 는 시드가 이미
     * 걷은 목데이터를 다시 채워 넣어, 이 테스트가 한 번 도는 것만으로 {@code tb_rptp} 가 14행이 됐다.
     * 이제는 {@code base} 보다 큰 PK 만 지우므로 <b>테스트 전/후의 DB 상태가 같다.</b>
     *
     * <p>순서 고정: <b>보고서 → 설정 → 실행이력</b>. 보고서가 설정·실행을 FK 로 참조하므로
     * 역순으로 지우면 헬퍼 자체가 FK 위반으로 터진다.
     *
     * <p>⚠️ identity 시퀀스는 되돌리지 않는다 — 앞으로만 밀면 충돌이 없고, 되돌리면 이미 쓰인 값을
     * 재사용할 위험이 생긴다. 🔴 {@code seedDepts()} 7행도 지우지 않는다.
     */
    @AfterEach
    void cleanUpCreated() {
        // 헬퍼가 넣은 매핑·mock 계정·로그인 이력을 함께 되돌린다
        if (auth != null) {
            auth.cleanUp();
        }
        // 🔴 base 가 안 잡혔으면 아무것도 지우지 않는다 — 0 으로 두면 전량 삭제가 된다 (BASE_UNSET 참고).
        if (rptpBase == BASE_UNSET || stngBase == BASE_UNSET || excnBase == BASE_UNSET) {
            return;
        }
        jdbcTemplate.update("DELETE FROM parking.tb_rptp WHERE rptp_id > ?", rptpBase);
        jdbcTemplate.update("DELETE FROM parking.tb_rptp_auto_crt_stng WHERE stng_id > ?", stngBase);
        jdbcTemplate.update("DELETE FROM parking.tb_anls_excn WHERE excn_id > ?", excnBase);
    }

    private long maxId(String table, String pkColumn) {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(" + pkColumn + "), 0) FROM " + table, Long.class);
        return max == null ? 0L : max;
    }

    private void advanceIdentity(String table, String pkColumn, long base) {
        jdbcTemplate.queryForObject(
                "SELECT setval(pg_get_serial_sequence(?::text, ?::text), ?, false)",
                Long.class, table, pkColumn, base + SEQUENCE_GAP);
    }

    /** 픽스처 보고서의 PK ({@code n} 은 1부터) */
    private long fixtureRptpId(int n) {
        return rptpBase + n;
    }

    /** 픽스처 설정의 PK ({@code n} 은 1부터) */
    private Long fixtureStngId(int n) {
        return stngBase + n;
    }

    private List<Long> fixtureRptpIds() {
        return LongStream.rangeClosed(1, FIXTURE_RPTP_CNT).map(n -> rptpBase + n).boxed().toList();
    }

    private long countReports(String crtType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp WHERE crt_type = ?", Long.class, crtType);
        return count == null ? 0L : count;
    }

    // ---------------------------------------------------------------- 1~3. 보고서 목록

    @Test
    @DisplayName("1. GET /api/reports?crtType=AUTO — 기준 + 픽스처 14건, 기본 페이지 크기 10, 자동 생성 칸이 채워진다")
    void reportsAutoFirstPage() {
        long expectedTotal = baselineAutoCnt + FIXTURE_RPTP_CNT;

        ReportPage page = getReports("?crtType=AUTO");

        assertThat(page.totalElements).isEqualTo(expectedTotal);
        assertThat(page.page).isZero();
        assertThat(page.size).isEqualTo(10);
        assertThat(page.totalPages).isEqualTo((int) ((expectedTotal + 9) / 10));
        assertThat(page.content).hasSize((int) Math.min(10, expectedTotal));

        // 최신순 정렬 (crt_ymd DESC)
        assertThat(page.content).isSortedAccordingTo(
                Comparator.<ReportListVO, LocalDateTime>comparing(ReportListVO::getCrtYmd).reversed());

        // AUTO 는 생성 시점 스냅샷(tb_rptp)으로 주기·부서가 채워진다.
        // 개정으로 AUTO 도 excn_id 를 가져 출처·지역까지 나온다 (화면은 출처 칸을 그리지 않을 뿐)
        // 🔴 기준 데이터가 아니라 **이 테스트가 만든 14행**으로 단언한다
        List<ReportListVO> fixtures = fixtureRows(getReports("?crtType=AUTO&size=100"));
        assertThat(fixtures).hasSize(FIXTURE_RPTP_CNT);
        assertThat(fixtures).allSatisfy(report -> {
            assertThat(report.getCrtType()).isEqualTo("AUTO");
            assertThat(report.getCyclNm()).isNotNull();
            assertThat(report.getDeptNm()).isNotNull();
            assertThat(report.getExcnId()).isNotNull();
            assertThat(report.getSrcNm()).isNotNull();
            assertThat(report.getRegionNm()).isNotNull();
        });
    }

    @Test
    @DisplayName("1-2. regionNm — 생성 시점 지역이 3형태(읍면동 / 시군구 전체 / 전체 지역)로 조립된다")
    void autoReportsCarryRegionSnapshot() {
        Map<Long, String> regionById = new LinkedHashMap<>();
        fixtureRows(getReports("?crtType=AUTO&size=100"))
                .forEach(report -> regionById.put(report.getRptpId(), report.getRegionNm()));

        assertThat(regionById).hasSize(FIXTURE_RPTP_CNT);
        // 픽스처 1~8(제주시 연동) · 9~11(제주시 전체) · 12~13(서귀포시 전체) · 14(전체 지역)
        assertThat(regionById.get(fixtureRptpId(1))).isEqualTo("제주시 연동");
        assertThat(regionById.get(fixtureRptpId(9))).isEqualTo("제주시 전체");
        assertThat(regionById.get(fixtureRptpId(12))).isEqualTo("서귀포시 전체");
        assertThat(regionById.get(fixtureRptpId(14))).isEqualTo("전체 지역");
        assertThat(new LinkedHashSet<>(regionById.values()))
                .containsExactlyInAnyOrder("제주시 연동", "제주시 전체", "서귀포시 전체", "전체 지역");
    }

    @Test
    @DisplayName("2. GET /api/reports?crtType=MANUAL — 픽스처는 MANUAL 을 만들지 않고, 직접 넣은 1건의 출처·기간·지역이 채워진다")
    void reportsManualAllHaveSrcNm() {
        // 🔴 시드도 픽스처도 MANUAL 을 만들지 않는다.
        //    총건수를 API 로 단언하지 않는다: 그 값은 실 API 로 만들어진 보고서까지 세는데 목록에 id
        //    필터가 없어(파라미터가 crtType·page·size 뿐이다) 좁힐 수 없다. 이 테스트 구간만 JDBC 로 센다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp WHERE crt_type = 'MANUAL' AND rptp_id > ?",
                Long.class, rptpBase))
                .as("픽스처는 직접 생성 보고서를 만들지 않는다").isZero();

        // MANUAL 조인 경로(srcNm·anlsPrdExpln·regionNm)를 잃지 않게 1행을 직접 넣는다.
        // 픽스처 실행 1(CVLCPT · 제주시 연동)을 참조하고, @AfterEach 의 rptp_id > rptpBase DELETE 가 정리한다.
        // crt_ymd 를 now() 로 둬 최신순(crt_ymd DESC) 첫 페이지에 반드시 들어오게 한다.
        long manualProbeId = fixtureRptpId(FIXTURE_RPTP_CNT + 1);
        jdbcTemplate.update("""
                INSERT INTO parking.tb_rptp
                  (rptp_id, crt_type, stng_id, excn_id, rptp_nm, anls_prd_expln, stts_cd, file_fmt, file_path,
                   creatr_id, creatr_nm, crt_ymd, trgt_prd_cd, crltn_dept_cd, crltn_cycl_cd)
                VALUES (?, 'MANUAL', NULL, ?, '직접 생성 보고서(테스트)', '2026.06.01 ~ 2026.06.30',
                        'DONE', 'PDF', NULL, ?, '테스트', now(), NULL, NULL, NULL)""",
                manualProbeId, excnBase + 1, IntegrationLoginSupport.MOCK_ID);

        ReportPage page = getReports("?crtType=MANUAL&size=100");

        // 🔴 그 rptpId 로만 찾아 단언한다 — 다른 검증이 만든 MANUAL 보고서가 섞여 있어도 흔들리지 않는다
        ReportListVO report = page.content.stream()
                .filter(row -> Long.valueOf(manualProbeId).equals(row.getRptpId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "직접 넣은 MANUAL 보고서를 목록에서 찾지 못했습니다: " + manualProbeId));

        // MANUAL 은 excn_id 를 갖고, 출처명 = 실행유형 코드명 단독
        assertThat(report.getCrtType()).isEqualTo("MANUAL");
        assertThat(report.getExcnId()).isEqualTo(excnBase + 1);
        assertThat(report.getSrcNm()).isNotNull();
        assertThat(report.getAnlsPrdExpln()).isEqualTo("2026.06.01 ~ 2026.06.30");
        assertThat(report.getRegionNm()).isEqualTo("제주시 연동");   // 실행이 지역을 갖는다
        // 직접 생성은 설정으로 만든 보고서가 아니라 주기·부서 스냅샷이 비어 있다
        assertThat(report.getCyclNm()).isNull();
        assertThat(report.getDeptNm()).isNull();
    }

    @Test
    @DisplayName("3. 페이지 경계 — 전 페이지를 이어 붙이면 중복도 누락도 없다 (tie-breaker 회귀 방지)")
    void reportPagesDoNotOverlap() {
        long total = baselineAutoCnt + FIXTURE_RPTP_CNT;
        int totalPages = (int) ((total + 9) / 10);

        int collected = 0;
        Set<Long> union = new LinkedHashSet<>();
        for (int p = 0; p < totalPages; p++) {
            ReportPage page = getReports("?crtType=AUTO&page=" + p + "&size=10");
            assertThat(page.totalElements).isEqualTo(total);
            collected += page.content.size();
            union.addAll(rptpIds(page));
        }

        // crt_ymd 가 같은 행이 여럿이라 tie-breaker(rptp_id) 가 빠지면 여기서 중복/누락이 잡힌다
        assertThat(collected).as("읽어 온 행 수 = 총건수 (중복이 있으면 union 이 작아진다)").isEqualTo((int) total);
        assertThat(union).hasSize((int) total);
        assertThat(union).containsAll(fixtureRptpIds());
    }

    // ---------------------------------------------------------------- 4. 등록 → 수정 → 트랜잭션 밖 재조회

    @Test
    @DisplayName("4. POST → PUT 응답이 요청값과 같고, 새 요청으로 재조회해도 같다 (MyBatis 1차 캐시 / flush 회귀 방지)")
    void updateReflectedInResponseAndOnRefetch() {
        ReportSettingVO created = createSetting(settingBody("CVLCPT", "TRFC", "MONTH", null, 5, "제주시", null));

        // (0) 등록 응답 자체가 요청값과 일치해야 한다 (save → flush → 재조회 경로)
        assertThat(created.getStngId()).isNotNull().isGreaterThan(stngBase);
        assertThat(created.getRptpTypeCd()).isEqualTo("CVLCPT");
        assertThat(created.getCrltnCyclCd()).isEqualTo("MONTH");
        assertThat(created.getCrltnDd()).isEqualTo(5);
        assertThat(created.getCrltnMm()).isNull();                 // MONTH 는 기준월을 저장하지 않는다
        assertThat(created.getRegionNm()).isEqualTo("제주시 전체");

        Long stngId = created.getStngId();
        Map<String, Object> modified = settingBody("HOTSPOT", "SAFE", "QUARTER", 4, 20, null, null);

        ResponseEntity<ReportSettingVO> updated =
                exchangeSetting(SETTINGS_URL + "/" + stngId, HttpMethod.PUT, modified, ReportSettingVO.class);

        // (1) ★ 수정 응답이 요청값이어야 한다. selectSettingById 의 flushCache="true" 가 빠지면
        //     같은 트랜잭션의 첫 호출(존재 확인) 결과가 캐시 히트로 돌아와 여기서 FAIL 한다.
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody()).isNotNull();
        assertSettingMatches(updated.getBody(), "HOTSPOT", "SAFE", "QUARTER", 4, 20, "전체 지역");

        // (2) 트랜잭션 밖 새 HTTP 요청으로 재조회해도 같아야 한다 (JPA flush 누락 회귀 방지)
        ReportSettingVO refetched = findSetting(getSettings(), stngId);
        assertSettingMatches(refetched, "HOTSPOT", "SAFE", "QUARTER", 4, 20, "전체 지역");

        // (3) DB 에도 실제로 커밋됐는지 확인
        Map<String, Object> row = settingRow(stngId);
        assertThat(row.get("rptp_type_cd")).isEqualTo("HOTSPOT");
        assertThat(row.get("crltn_dept_cd")).isEqualTo("SAFE");
        assertThat(row.get("crltn_cycl_cd")).isEqualTo("QUARTER");
        assertThat(((Number) row.get("crltn_mm")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("crltn_dd")).intValue()).isEqualTo(20);
        assertThat(row.get("sgg_nm")).isNull();
        assertThat(row.get("emd_cd")).isNull();
        // 수정은 생성자·생성일시·활성여부를 건드리지 않는다
        // 🔴 등록이 적은 생성자는 'system' 이 아니라 세션 사용자다
        assertThat(row.get("creatr_id")).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(row.get("actvtn_yn")).isEqualTo(Boolean.TRUE);
    }

    // ---------------------------------------------------------------- 5~6. 소프트 삭제

    @Test
    @DisplayName("5. DELETE — 204 · 행은 남고 del_dt 이 채워지며 목록에서 빠진다 · 재삭제/수정은 404")
    void deleteSettingIsSoftDelete() {
        Long stngId = createSetting(settingBody("CVLCPT", "TRFC", "MONTH", null, 5, null, null)).getStngId();

        ResponseEntity<String> deleted = deleteSetting(SETTINGS_URL + "/" + stngId);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);

        // (1) 행은 남아 있고 del_dt 만 채워진다 (하드 삭제면 여기서 0행이라 실패한다)
        Map<String, Object> row = settingRow(stngId);
        assertThat(row).isNotNull();
        assertThat(row.get("del_dt")).isNotNull();

        // (2) 목록에서는 빠진다
        assertThat(getSettings()).extracting(ReportSettingVO::getStngId).doesNotContain(stngId);

        // (3) 이미 삭제된 설정은 없는 것과 같이 취급한다 — 재삭제·수정 모두 404 (복원 경로를 만들지 않는다)
        assertThat(deleteSetting(SETTINGS_URL + "/" + stngId)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(exchangeSetting(SETTINGS_URL + "/" + stngId, HttpMethod.PUT,
                settingBody("CVLCPT", "TRFC", "MONTH", null, 5, null, null), String.class)
                .getStatusCode().value()).isEqualTo(404);

        // (4) 없는 설정도 404
        assertThat(deleteSetting(SETTINGS_URL + "/999999")
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("6. 참조 중인 설정을 삭제해도 204 이고, 그 설정으로 만든 보고서의 주기·부서·지역은 그대로 남는다")
    void reportKeepsSettingColumnsAfterSettingIsDeleted() {
        List<Long> referencing = referencingReportIds();

        ReportPage before = getReports("?crtType=AUTO&size=100");
        Map<Long, String> cyclBefore = valueById(before, referencing, ReportListVO::getCyclNm);
        Map<Long, String> deptBefore = valueById(before, referencing, ReportListVO::getDeptNm);
        Map<Long, String> regionBefore = valueById(before, referencing, ReportListVO::getRegionNm);
        assertThat(cyclBefore).hasSameSizeAs(referencing).doesNotContainValue(null);
        assertThat(deptBefore).hasSameSizeAs(referencing).doesNotContainValue(null);
        assertThat(regionBefore).hasSameSizeAs(referencing).doesNotContainValue(null);

        // 보고서가 참조 중이어도 소프트 삭제라 204 다 (409 는 개정 전 정책)
        assertThat(deleteSetting(SETTINGS_URL + "/" + fixtureStngId(1))
                .getStatusCode().value()).isEqualTo(204);

        // 표시 값을 설정에서 읽으면(삭제분 제외 조건까지 붙으면) 여기서 세 칸이 통째로 null 이 되어 실패한다
        ReportPage after = getReports("?crtType=AUTO&size=100");
        assertThat(after.totalElements).isEqualTo(before.totalElements);
        assertThat(valueById(after, referencing, ReportListVO::getCyclNm)).isEqualTo(cyclBefore);
        assertThat(valueById(after, referencing, ReportListVO::getDeptNm)).isEqualTo(deptBefore);
        assertThat(valueById(after, referencing, ReportListVO::getRegionNm)).isEqualTo(regionBefore);

        // 설정 행도 지워지지 않고 남는다 (소프트 삭제)
        Map<String, Object> row = settingRow(fixtureStngId(1));
        assertThat(row.get("del_dt")).isNotNull();
        assertThat(row.get("sgg_nm")).isEqualTo("제주시");
        assertThat(row.get("emd_cd")).isNotNull();
    }

    @Test
    @DisplayName("6-2. 🔴 설정을 수정해도 이미 만들어진 보고서의 부서·주기·지역 표기가 바뀌지 않는다 (스냅샷)")
    void reportSnapshotSurvivesSettingUpdate() {
        List<Long> referencing = referencingReportIds();

        ReportPage before = getReports("?crtType=AUTO&size=100");
        Map<Long, String> cyclBefore = valueById(before, referencing, ReportListVO::getCyclNm);
        Map<Long, String> deptBefore = valueById(before, referencing, ReportListVO::getDeptNm);
        Map<Long, String> regionBefore = valueById(before, referencing, ReportListVO::getRegionNm);

        // 픽스처 설정 1 = TRFC(부서) · MONTH(주기) · 제주시 연동(지역)
        assertThat(cyclBefore).hasSameSizeAs(referencing).doesNotContainValue(null);
        assertThat(deptBefore).hasSameSizeAs(referencing).doesNotContainValue(null);
        assertThat(regionBefore.values()).isNotEmpty().allMatch("제주시 연동"::equals);

        // 부서·주기·지역을 전부 다른 값으로 갈아엎는다 (삭제가 아니라 '수정' 이 회귀 지점이다)
        ResponseEntity<ReportSettingVO> updated = exchangeSetting(SETTINGS_URL + "/" + fixtureStngId(1),
                HttpMethod.PUT, settingBody("HOTSPOT", "SAFE", "YEAR", 1, 1, "서귀포시", null), ReportSettingVO.class);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody()).isNotNull();
        assertSettingMatches(updated.getBody(), "HOTSPOT", "SAFE", "YEAR", 1, 1, "서귀포시 전체");

        // ★ 목록 SQL 이 설정(stng_id)을 JOIN 해 표시값을 읽으면, 과거 보고서 8건이 전부
        //   'ALL 부서 · 연간 · 서귀포시 전체' 로 소급 변경되어 여기서 실패한다
        ReportPage after = getReports("?crtType=AUTO&size=100");
        assertThat(after.totalElements).isEqualTo(before.totalElements);
        assertThat(valueById(after, referencing, ReportListVO::getCyclNm)).isEqualTo(cyclBefore);
        assertThat(valueById(after, referencing, ReportListVO::getDeptNm)).isEqualTo(deptBefore);
        assertThat(valueById(after, referencing, ReportListVO::getRegionNm)).isEqualTo(regionBefore);

        // 단건 조회도 같은 SELECT 를 써야 한다 — 두 쿼리가 갈리면 여기서만 값이 달라진다
        Long rptpId = referencing.get(0);
        ResponseEntity<ReportListVO> single = restTemplate.exchange(REPORTS_URL + "/" + rptpId,
                HttpMethod.GET, new HttpEntity<>(session), ReportListVO.class);
        assertThat(single.getStatusCode().value()).isEqualTo(200);
        assertThat(single.getBody()).isNotNull();
        assertThat(single.getBody().getCyclNm()).isEqualTo(cyclBefore.get(rptpId));
        assertThat(single.getBody().getDeptNm()).isEqualTo(deptBefore.get(rptpId));
        assertThat(single.getBody().getRegionNm()).isEqualTo(regionBefore.get(rptpId));
    }

    /** 픽스처 설정 1(월간·제주시 연동)로 만들어진 보고서 8건 */
    private List<Long> referencingReportIds() {
        List<Long> referencing = jdbcTemplate.queryForList(
                "SELECT rptp_id FROM parking.tb_rptp WHERE stng_id = ?", Long.class, fixtureStngId(1));
        assertThat(referencing).isNotEmpty();
        return referencing;
    }

    // ---------------------------------------------------------------- 7. 다음 배포 예정일

    @Test
    @DisplayName("7. crltnYmd — 주기 4종 모두 오늘 이후의 가장 이른 날짜이고 기준월·기준일과 맞는다")
    void nextCrltnYmdIsFirstFutureDateForEachCycle() {
        LocalDate today = LocalDate.now();

        assertNextYmd(createSetting(settingBody("CVLCPT", "TRFC", "MONTH", null, 5, null, null)),
                1, null, 5, today);
        assertNextYmd(createSetting(settingBody("CVLCPT", "TRFC", "QUARTER", 4, 10, null, null)),
                3, 4, 10, today);
        assertNextYmd(createSetting(settingBody("CVLCPT", "TRFC", "HALF", 7, 15, null, null)),
                6, 7, 15, today);
        assertNextYmd(createSetting(settingBody("CVLCPT", "TRFC", "YEAR", 3, 20, null, null)),
                12, 3, 20, today);
    }

    // ---------------------------------------------------------------- 8. 지역 정규화

    @Test
    @DisplayName("8. 지역 3형태 — 전체 지역 / 시군구 전체 / 시군구 읍면동, emdCd 가 있으면 sggNm 을 서버가 교정한다")
    void regionIsNormalizedByServer() {
        // (1) 둘 다 없음 → 전체 지역
        ReportSettingVO all = createSetting(settingBody("CVLCPT", "TRFC", "MONTH", null, 5, null, null));
        assertThat(all.getRegionNm()).isEqualTo("전체 지역");
        assertThat(all.getSggNm()).isNull();
        assertThat(all.getEmdCd()).isNull();

        // (2) 시군구만 → {시군구} 전체
        ReportSettingVO sgg = createSetting(settingBody("CVLCPT", "TRFC", "MONTH", null, 5, "서귀포시", null));
        assertThat(sgg.getRegionNm()).isEqualTo("서귀포시 전체");
        assertThat(sgg.getSggNm()).isEqualTo("서귀포시");
        assertThat(sgg.getEmdCd()).isNull();

        // (3) 읍면동 지정 → {시군구} {읍면동}. 클라이언트가 보낸 엉뚱한 sggNm 은 버리고 서버가 다시 채운다
        String emdCd = jdbcTemplate.queryForObject(
                "SELECT emd_cd FROM parking.tb_admdst WHERE sgg_nm = '제주시' AND emd_nm = '연동' ORDER BY emd_cd LIMIT 1",
                String.class);
        ReportSettingVO emd = createSetting(settingBody("CVLCPT", "TRFC", "MONTH", null, 5, "서귀포시", emdCd));
        assertThat(emd.getSggNm()).isEqualTo("제주시");          // 보낸 '서귀포시' 가 아니라 서버 교정값
        assertThat(emd.getEmdCd()).isEqualTo(emdCd);
        assertThat(emd.getEmdNm()).isEqualTo("연동");
        assertThat(emd.getRegionNm()).isEqualTo("제주시 연동");

        // (4) 빈 문자열은 '지정 안 함' 으로 접힌다 (폼이 비운 select 를 "" 로 보내는 경우)
        ReportSettingVO blank = createSetting(settingBody("CVLCPT", "TRFC", "MONTH", null, 5, "  ", ""));
        assertThat(blank.getRegionNm()).isEqualTo("전체 지역");

        // (5) 없는 읍면동코드는 400 (FK 위반을 500 으로 흘리지 않는다)
        long before = countSettings();
        assertThat(postSetting(settingBody("CVLCPT", "TRFC", "MONTH", null, 5, null, "99999999"), String.class)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(countSettings()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- 9. 잘못된 입력

    @Test
    @DisplayName("9-1. 없는 코드 · 분석 전용 코드(POLICY_SIM) · 규칙 없는 주기(WEEK) → 400, 행이 생기지 않는다")
    void invalidCodesReturn400() {
        long before = countSettings();

        // POLICY_SIM 은 ANLS_TYPE_CD 에 실재하지만 addlAtrb1='ANLS' 라 보고서 종류로는 쓸 수 없다
        assertThat(postSetting(settingBody("POLICY_SIM", "TRFC", "MONTH", null, 5, null, null), String.class)
                .getStatusCode().value()).isEqualTo(400);
        // WEEK 는 CRLTN_CYCL_CD 에 실재하지만 예정일 계산 규칙이 없어 400 이다
        assertThat(postSetting(settingBody("CVLCPT", "TRFC", "WEEK", 1, 5, null, null), String.class)
                .getStatusCode().value()).isEqualTo(400);
        // 아예 없는 코드값
        assertThat(postSetting(settingBody("CVLCPT", "NOT_EXISTS", "MONTH", null, 5, null, null), String.class)
                .getStatusCode().value()).isEqualTo(400);
        // 시군구명이 실재하지 않는 경우
        assertThat(postSetting(settingBody("CVLCPT", "TRFC", "MONTH", null, 5, "없는시", null), String.class)
                .getStatusCode().value()).isEqualTo(400);

        assertThat(countSettings()).isEqualTo(before);
    }

    @Test
    @DisplayName("9-2. 주기별 필수값 누락(QUARTER + crltnMm 없음) · 범위 밖(crltnDd=29) · 빈 바디 → 400")
    void invalidRangesReturn400() {
        long before = countSettings();

        assertThat(postSetting(settingBody("CVLCPT", "TRFC", "QUARTER", null, 5, null, null), String.class)
                .getStatusCode().value()).isEqualTo(400);
        // crltn_dd 는 말일 편차를 피해 1~28 로 제한한다 (@Max(28) + DB CHECK)
        assertThat(postSetting(settingBody("CVLCPT", "TRFC", "MONTH", null, 29, null, null), String.class)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(postSetting(settingBody("CVLCPT", "TRFC", "MONTH", 13, 5, null, null), String.class)
                .getStatusCode().value()).isEqualTo(400);
        // @Valid 가 빠지면 NPE(500)로 흐르는 경로
        assertThat(postSetting(new LinkedHashMap<>(), String.class).getStatusCode().value()).isEqualTo(400);

        assertThat(countSettings()).isEqualTo(before);
    }

    @Test
    @DisplayName("9-3. 목록 파라미터 — crtType 누락·화이트리스트 밖·size 범위 밖·page 음수 → 400")
    void invalidListParamsReturn400() {
        assertThat(getReportsStatus("")).isEqualTo(400);                         // crtType 누락
        assertThat(getReportsStatus("?crtType=FOO")).isEqualTo(400);
        assertThat(getReportsStatus("?crtType=AUTO&size=101")).isEqualTo(400);   // 상한 100
        assertThat(getReportsStatus("?crtType=AUTO&size=0")).isEqualTo(400);
        assertThat(getReportsStatus("?crtType=AUTO&page=-1")).isEqualTo(400);
    }

    @Test
    @DisplayName("11. 🔴 익명 HTTP 는 목록·단건 모두 401 이다 — 폴백으로 전체 공개하지 않는다")
    void anonymousListAndDetailAre401() {
        long rptpId = fixtureRptpId(1);
        for (String crtType : new String[] {"AUTO", "MANUAL", "AI"}) {
            assertThat(restTemplate.getForEntity(REPORTS_URL + "?crtType=" + crtType, String.class)
                    .getStatusCode().value()).as("익명 목록 crtType=%s", crtType).isEqualTo(401);
        }
        assertThat(restTemplate.getForEntity(REPORTS_URL + "/" + rptpId, String.class)
                .getStatusCode().value()).as("익명 단건 — AUTO 보고서라도 세션이 없으면 401").isEqualTo(401);
    }

    @Test
    @DisplayName("9-4. GET /api/reports/{rptpId} — 픽스처 1건은 200, 없는 ID 는 404")
    void reportByIdReturns200And404() {
        long rptpId = fixtureRptpId(1);
        ResponseEntity<ReportListVO> found = restTemplate.exchange(
                REPORTS_URL + "/" + rptpId, HttpMethod.GET, new HttpEntity<>(session), ReportListVO.class);
        assertThat(found.getStatusCode().value()).isEqualTo(200);
        assertThat(found.getBody()).isNotNull();
        assertThat(found.getBody().getRptpId()).isEqualTo(rptpId);

        assertThat(restTemplate.exchange(REPORTS_URL + "/999999", HttpMethod.GET,
                new HttpEntity<>(session), String.class).getStatusCode().value()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- 10. 엔티티 ↔ 테이블 매핑

    @Test
    @DisplayName("10. JPA 저장이 parking 스키마의 실제 컬럼에 들어간다 (컬럼명 매핑 · 서버 고정값 4종)")
    void createdSettingIsMappedToTableColumns() {
        String emdCd = jdbcTemplate.queryForObject(
                "SELECT emd_cd FROM parking.tb_admdst WHERE sgg_nm = '제주시' AND emd_nm = '연동' ORDER BY emd_cd LIMIT 1",
                String.class);
        LocalDateTime before = LocalDateTime.now().minusMinutes(1);

        // ⚠️ 보고서 종류는 ANLS_TYPE_CD 중 addl_atrb1 IN ('RPTP','BOTH') 2종(CVLCPT · HOTSPOT)뿐이다.
        //    마이그레이션 37-report-codes-fix.sql(2026-08-18, 개정)이 DASHBOARD 의 addlAtrb1 을 NULL 로
        //    되돌려 선택지에서 뺐으므로 여기서 DASHBOARD 를 쓰면 400 이다.
        ReportSettingVO created = createSetting(settingBody("CVLCPT", "CIVIL", "HALF", 7, 12, null, emdCd));
        Long stngId = created.getStngId();

        // identity 가 픽스처 구간 뒤에서 매겨진다
        assertThat(stngId).isGreaterThan(stngBase + FIXTURE_STNG_CNT);

        Map<String, Object> row = settingRow(stngId);
        assertThat(row.get("rptp_type_cd")).isEqualTo("CVLCPT");
        assertThat(row.get("crltn_dept_cd")).isEqualTo("CIVIL");
        assertThat(row.get("crltn_cycl_cd")).isEqualTo("HALF");
        assertThat(((Number) row.get("crltn_mm")).intValue()).isEqualTo(7);
        assertThat(((Number) row.get("crltn_dd")).intValue()).isEqualTo(12);
        assertThat(row.get("sgg_nm")).isEqualTo("제주시");
        assertThat(row.get("emd_cd")).isEqualTo(emdCd);

        // DB NOT NULL 3종은 서버 고정값.
        // 🔁 2026-08-24 — crltn_ymd 는 컬럼이 사라졌다. 저장을 확인할 수 없으니 대신
        //    "응답에는 계산돼 실려 온다" 를 확인한다(assertNextYmd 가 성질까지 검증한다).
        assertThat(created.getCrltnYmd()).as("배포 예정일은 저장하지 않고 응답에서 계산해 채운다").isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT crt_dt FROM parking.tb_rptp_auto_crt_stng WHERE stng_id = ?", LocalDateTime.class, stngId))
                .isAfter(before);
        assertThat(row.get("actvtn_yn")).isEqualTo(Boolean.TRUE);
        // 🔴 'system' 하드코딩이 아니라 세션 사용자이고, 이름 칸도 함께 채운다
        assertThat(row.get("creatr_id")).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(row.get("creatr_nm")).as("설정 목록의 '생성자' 칸이 비어 있던 것을 구현이 채웠다").isNotNull();

        // 등록이 건드리지 않는 컬럼
        assertThat(row.get("del_dt")).isNull();
        assertThat(row.get("last_crt_dt")).isNull();
    }

    // ---------------------------------------------------------------- 12. 목록 필터

    /** 다른 소유자 — T9 에서 "남의 MANUAL 보고서" 를 만든다. creatr_id 에는 FK 가 없다 */
    private static final String OTHER_OWNER_ID = "other-user-4266";

    /**
     * 필터 픽스처 — MANUAL 3행을 <b>한 문장</b>으로 넣는다(같은 {@code now()} → 같은 {@code crt_ymd}).
     *
     * <ul>
     *   <li>{@link #filterMine1Id()} — 내 것 · CVLCPT(실행 1) · "{토큰} 민원 보고서"</li>
     *   <li>{@link #filterMine2Id()} — 내 것 · HOTSPOT(실행 14) · "{토큰} 핫스팟 보고서"</li>
     *   <li>{@link #filterOtherId()} — <b>남의 것</b> · HOTSPOT · 이름·출처·날짜가 모든 필터 조건에 맞는다</li>
     * </ul>
     * 🔴 {@code @BeforeEach} 에 두지 않는다 — 케이스 2 가 "픽스처는 MANUAL 을 만들지 않는다" 를 단언한다.
     * PK 는 {@code base + 15 ~ 17}(identity 는 {@code base + 50} 부터라 겹치지 않는다)이고,
     * {@link #cleanUpCreated()} 의 {@code rptp_id > rptpBase} DELETE 가 정리한다.
     * 토큰에 {@code rptpBase} 를 넣어 실 데이터·이전 실행 잔여분과 이름이 겹치지 않게 한다.
     *
     * @return 그 행들의 생성일 — 🔴 날짜를 박지 않고 DB 가 매긴 값을 읽는다
     */
    private LocalDate installFilterFixture() {
        jdbcTemplate.update("""
                INSERT INTO parking.tb_rptp
                  (rptp_id, crt_type, stng_id, excn_id, rptp_nm, anls_prd_expln, stts_cd, file_fmt, file_path,
                   creatr_id, creatr_nm, crt_ymd, trgt_prd_cd, crltn_dept_cd, crltn_cycl_cd) VALUES
                  (?, 'MANUAL', NULL, ?, ?, NULL, 'DONE', 'PDF', NULL, ?, '테스트', now(), NULL, NULL, NULL),
                  (?, 'MANUAL', NULL, ?, ?, NULL, 'DONE', 'PDF', NULL, ?, '테스트', now(), NULL, NULL, NULL),
                  (?, 'MANUAL', NULL, ?, ?, NULL, 'DONE', 'PDF', NULL, ?, '남',     now(), NULL, NULL, NULL)""",
                filterMine1Id(), excnBase + 1, filterToken() + " 민원 보고서", IntegrationLoginSupport.MOCK_ID,
                filterMine2Id(), excnBase + 14, filterToken() + " 핫스팟 보고서", IntegrationLoginSupport.MOCK_ID,
                filterOtherId(), excnBase + 14, filterToken() + " 핫스팟 보고서", OTHER_OWNER_ID);
        return jdbcTemplate.queryForObject(
                "SELECT crt_ymd::date FROM parking.tb_rptp WHERE rptp_id = ?", LocalDate.class, filterMine1Id());
    }

    private String filterToken() {
        return "필터4266-" + rptpBase;
    }

    private long filterMine1Id() {
        return fixtureRptpId(FIXTURE_RPTP_CNT + 1);
    }

    private long filterMine2Id() {
        return fixtureRptpId(FIXTURE_RPTP_CNT + 2);
    }

    private long filterOtherId() {
        return fixtureRptpId(FIXTURE_RPTP_CNT + 3);
    }

    private long countMyManualReports() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp WHERE crt_type = 'MANUAL' AND creatr_id = ?",
                Long.class, IntegrationLoginSupport.MOCK_ID);
        return count == null ? 0L : count;
    }

    @Test
    @DisplayName("12-1. (T1) 필터 없음 — 종전과 같다: 내 MANUAL 전건이 총건수이고, 빈 파라미터도 미적용이다")
    void listFilterAbsentIsBackwardCompatible() {
        installFilterFixture();
        long myManual = countMyManualReports();

        ReportPage plain = getReports("?crtType=MANUAL&size=100");
        assertThat(plain.totalElements).as("필터 없음 = 내 MANUAL 전건").isEqualTo(myManual);
        assertThat(rptpIds(plain)).contains(filterMine1Id(), filterMine2Id()).doesNotContain(filterOtherId());

        // 빈 값 4종은 "보내지 않음" 과 같다
        ReportPage blank = getReports("?crtType=MANUAL&size=100&keyword=&anlsTypeCd=&fromYmd=&toYmd=");
        assertThat(blank.totalElements).isEqualTo(plain.totalElements);
        assertThat(rptpIds(blank)).isEqualTo(rptpIds(plain));

        // AUTO 도 필터 없이 종전 총건수 그대로 (케이스 1 과 같은 기준)
        assertThat(getReports("?crtType=AUTO").totalElements).isEqualTo(baselineAutoCnt + FIXTURE_RPTP_CNT);
    }

    @Test
    @DisplayName("12-2. (T2) keyword — 보고서명 부분 일치, 총건수 = content 건수")
    void listFilterKeywordPartialMatch() {
        installFilterFixture();

        ReportPage both = getReports("?crtType=MANUAL&size=100&keyword={kw}", filterToken());
        assertThat(rptpIds(both)).containsExactlyInAnyOrder(filterMine1Id(), filterMine2Id());
        assertThat(both.totalElements).isEqualTo(both.content.size());

        // 이름 중간의 일부만으로도 걸린다 — 대소문자 구분 부분 일치(position)
        ReportPage one = getReports("?crtType=MANUAL&size=100&keyword={kw}", filterToken() + " 민원");
        assertThat(rptpIds(one)).containsExactly(filterMine1Id());
        assertThat(one.totalElements).isEqualTo(1);
    }

    @Test
    @DisplayName("12-3. (T3) keyword — 앞뒤 공백은 trim, 빈 문자열·공백만은 미적용")
    void listFilterKeywordTrimAndBlank() {
        installFilterFixture();

        ReportPage padded = getReports("?crtType=MANUAL&size=100&keyword={kw}", "  " + filterToken() + "  ");
        assertThat(rptpIds(padded)).containsExactlyInAnyOrder(filterMine1Id(), filterMine2Id());
        assertThat(padded.totalElements).isEqualTo(2);

        long all = getReports("?crtType=MANUAL&size=100").totalElements;
        assertThat(getReports("?crtType=MANUAL&size=100&keyword=").totalElements).isEqualTo(all);
        assertThat(getReports("?crtType=MANUAL&size=100&keyword={kw}", "   ").totalElements).isEqualTo(all);
    }

    @Test
    @DisplayName("12-4. (T4) keyword 의 % · _ 는 와일드카드가 아니라 리터럴이다 — 이름에 없으면 0건")
    void listFilterKeywordWildcardIsLiteral() {
        installFilterFixture();

        // LIKE 였다면 '%' 는 임의 문자열, '_' 는 임의 한 글자라 픽스처 2행이 걸렸을 것이다
        ReportPage percent = getReports("?crtType=MANUAL&size=100&keyword={kw}", "필터4266%");
        assertThat(percent.totalElements).isZero();
        assertThat(percent.content).isEmpty();

        ReportPage underscore = getReports("?crtType=MANUAL&size=100&keyword={kw}", "필터4266_" + rptpBase);
        assertThat(underscore.totalElements).isZero();
    }

    @Test
    @DisplayName("12-5. (T5) anlsTypeCd=HOTSPOT — 전 행이 HOTSPOT 이고 다른 출처(CVLCPT)는 빠진다 · trim 적용")
    void listFilterAnlsTypeCd() {
        installFilterFixture();

        ReportPage hotspot = getReports("?crtType=MANUAL&size=100&anlsTypeCd=HOTSPOT");
        assertThat(hotspot.content).isNotEmpty();
        assertThat(hotspot.content).allSatisfy(row -> assertThat(row.getAnlsTypeCd()).isEqualTo("HOTSPOT"));
        assertThat(rptpIds(hotspot)).contains(filterMine2Id()).doesNotContain(filterMine1Id(), filterOtherId());
        assertThat(hotspot.totalElements).isEqualTo(hotspot.content.size());

        // 앞뒤 공백은 trim 된다
        ReportPage padded = getReports("?crtType=MANUAL&size=100&anlsTypeCd={cd}", " HOTSPOT ");
        assertThat(rptpIds(padded)).isEqualTo(rptpIds(hotspot));

        // 키워드와 겹치면 AND — 픽스처 HOTSPOT 1건만
        ReportPage narrowed = getReports("?crtType=MANUAL&size=100&anlsTypeCd=HOTSPOT&keyword={kw}", filterToken());
        assertThat(rptpIds(narrowed)).containsExactly(filterMine2Id());

        // AUTO 에 걸어도 동작한다(총건수 쿼리의 tb_anls_excn JOIN 확인). 픽스처 AUTO 중 HOTSPOT 은 14번 하나
        ReportPage auto = getReports("?crtType=AUTO&size=100&anlsTypeCd=HOTSPOT");
        assertThat(auto.content).allSatisfy(row -> assertThat(row.getAnlsTypeCd()).isEqualTo("HOTSPOT"));
        assertThat(fixtureRows(auto)).extracting(ReportListVO::getRptpId).containsExactly(fixtureRptpId(14));
        assertThat(auto.totalElements).isEqualTo(auto.content.size());
    }

    @Test
    @DisplayName("12-6. (T6) anlsTypeCd=NOPE — 없는 출처 코드는 400")
    void listFilterUnknownAnlsTypeCdReturns400() {
        assertThat(getReportsStatus("?crtType=MANUAL&anlsTypeCd=NOPE")).isEqualTo(400);
    }

    @Test
    @DisplayName("12-7. (T7) fromYmd = toYmd = 생성일 — 그날 생성분이 포함된다(종료일 당일 포함) · 앞뒤 날짜는 제외")
    void listFilterSameDayRangeIncludesThatDay() {
        LocalDate day = installFilterFixture();
        String q = "?crtType=MANUAL&size=100&keyword={kw}&fromYmd={from}&toYmd={to}";

        ReportPage sameDay = getReports(q, filterToken(), day, day);
        assertThat(rptpIds(sameDay)).containsExactlyInAnyOrder(filterMine1Id(), filterMine2Id());
        assertThat(sameDay.totalElements).isEqualTo(2);

        // 한쪽만 걸어도 동작한다
        assertThat(rptpIds(getReports("?crtType=MANUAL&size=100&keyword={kw}&fromYmd={from}", filterToken(), day)))
                .containsExactlyInAnyOrder(filterMine1Id(), filterMine2Id());
        assertThat(rptpIds(getReports("?crtType=MANUAL&size=100&keyword={kw}&toYmd={to}", filterToken(), day)))
                .containsExactlyInAnyOrder(filterMine1Id(), filterMine2Id());

        // 생성일 다음 날부터 / 전날까지 — 빠진다
        assertThat(getReports(q, filterToken(), day.plusDays(1), day.plusDays(1)).totalElements).isZero();
        assertThat(getReports(q, filterToken(), day.minusDays(1), day.minusDays(1)).totalElements).isZero();
    }

    @Test
    @DisplayName("12-8. (T8) fromYmd > toYmd · 날짜 형식 오류 · keyword 101자 → 400 (100자는 200)")
    void listFilterInvalidParamsReturn400() {
        LocalDate today = LocalDate.now();
        assertThat(getReportsStatus("?crtType=MANUAL&fromYmd=" + today.plusDays(1) + "&toYmd=" + today))
                .as("기간 역전").isEqualTo(400);
        assertThat(getReportsStatus("?crtType=MANUAL&fromYmd=abc")).as("fromYmd 형식 오류").isEqualTo(400);
        assertThat(getReportsStatus("?crtType=MANUAL&toYmd=abc")).as("toYmd 형식 오류").isEqualTo(400);
        assertThat(getReportsStatus("?crtType=MANUAL&keyword=" + "a".repeat(101))).as("keyword 101자").isEqualTo(400);
        assertThat(getReportsStatus("?crtType=MANUAL&keyword=" + "a".repeat(100))).as("keyword 100자").isEqualTo(200);
    }

    @Test
    @DisplayName("12-9. 🔴 (T9) 필터를 걸어도 남의 MANUAL 보고서가 섞이지 않는다 — 소유권 조건을 넓히지 않는다")
    void listFilterNeverLeaksOthersManualReports() {
        LocalDate day = installFilterFixture();

        // 남의 행은 이름·출처·날짜 조건에 전부 맞는다 — 그래도 내 것만 나온다
        ReportPage all4 = getReports(
                "?crtType=MANUAL&size=100&keyword={kw}&anlsTypeCd=HOTSPOT&fromYmd={from}&toYmd={to}",
                filterToken(), day, day);
        assertThat(rptpIds(all4)).containsExactly(filterMine2Id());
        assertThat(all4.totalElements).as("총건수에도 남의 것이 세지지 않는다").isEqualTo(1);

        for (String q : new String[] {
                "?crtType=MANUAL&size=100",
                "?crtType=MANUAL&size=100&anlsTypeCd=HOTSPOT",
                "?crtType=MANUAL&size=100&keyword=" + "{kw}"}) {
            assertThat(rptpIds(getReports(q, filterToken()))).as(q).doesNotContain(filterOtherId());
        }
    }

    @Test
    @DisplayName("12-10. (T10) 필터 + size=1 — 전 페이지를 이어 붙이면 중복·누락이 없고 합 = totalElements")
    void listFilterPagingIsConsistent() {
        installFilterFixture();
        String q = "?crtType=MANUAL&keyword={kw}&size=1&page={p}";

        ReportPage first = getReports(q, filterToken(), 0);
        long total = first.totalElements;
        assertThat(total).isEqualTo(2);
        assertThat(first.totalPages).isEqualTo((int) total);

        List<Long> collected = new java.util.ArrayList<>();
        for (int p = 0; p < first.totalPages; p++) {
            ReportPage page = getReports(q, filterToken(), p);
            assertThat(page.totalElements).as("페이지마다 같은 총건수").isEqualTo(total);
            assertThat(page.content).hasSize(1);
            collected.addAll(page.content.stream().map(ReportListVO::getRptpId).toList());
        }
        // 같은 crt_ymd 두 행 — tie-breaker 가 빠지면 중복·누락이 여기서 잡힌다
        assertThat(collected).hasSize((int) total).doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(filterMine1Id(), filterMine2Id());
        assertThat(getReports(q, filterToken(), first.totalPages).content).as("마지막 다음 페이지는 빈다").isEmpty();
    }

    @Test
    @DisplayName("13. DELETE /api/reports/{id} — 내 직접 생성은 204 하드 삭제(실행은 남는다) · 재삭제·남의 것 404 · AUTO 400")
    void deleteManualReport() {
        installFilterFixture();
        long mine = filterMine1Id();

        assertThat(deleteReport(mine).getStatusCode().value()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp WHERE rptp_id = ?", Long.class, mine))
                .as("보고서 행은 지워진다").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_excn WHERE excn_id = ?", Long.class, excnBase + 1))
                .as("묶인 분석 실행은 남는다").isEqualTo(1);
        assertThat(deleteReport(mine).getStatusCode().value()).as("재삭제").isEqualTo(404);

        // 🔴 남의 보고서는 403 이 아니라 404 이고, 지워지지 않는다
        assertThat(deleteReport(filterOtherId()).getStatusCode().value()).isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp WHERE rptp_id = ?", Long.class, filterOtherId()))
                .isEqualTo(1);

        // 볼 수 있는 AUTO(ALL 배포)는 400 — 지워지지 않는다
        assertThat(deleteReport(fixtureRptpId(14)).getStatusCode().value()).isEqualTo(400);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp WHERE rptp_id = ?", Long.class, fixtureRptpId(14)))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- 헬퍼

    /**
     * {@link jeju.datahub.common.PageResponse} 를 그대로 역직렬화할 수 없어(필드가 final 이고 기본 생성자가
     * 없다) 테스트 전용 미러를 둔다. 필드 5개는 응답 계약과 1:1 이라 계약이 바뀌면 여기서 먼저 깨진다.
     */
    static class ReportPage {
        public List<ReportListVO> content;
        public int page;
        public int size;
        public long totalElements;
        public int totalPages;
    }

    /**
     * 🔴 목록 조회가 세션을 요구한다. 익명은 404 가 아니라 <b>401</b>
     * ({@code CurrentUserResolver}). 소유자는 {@code mock-user} 이므로 {@code MANUAL}·{@code AI} 는
     * 그 사용자가 만든 것만 보인다. 익명 401 은 케이스 11 이 따로 지킨다.
     */
    private ReportPage getReports(String query) {
        ResponseEntity<ReportPage> response = restTemplate.exchange(
                REPORTS_URL + query, HttpMethod.GET, new HttpEntity<>(session), ReportPage.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content).isNotNull();
        return response.getBody();
    }

    /**
     * URI 템플릿 변수판 — 한글·공백·{@code %}·{@code _} 가 든 keyword 를 그대로 보내려고
     * 값을 변수로 넘긴다(인코딩은 RestTemplate 이 한다). 문자열에 직접 붙이면 {@code %} 가 깨진다.
     */
    private ReportPage getReports(String queryTemplate, Object... uriVariables) {
        ResponseEntity<ReportPage> response = restTemplate.exchange(
                REPORTS_URL + queryTemplate, HttpMethod.GET, new HttpEntity<>(session), ReportPage.class,
                uriVariables);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content).isNotNull();
        return response.getBody();
    }

    /** 응답에서 <b>이 테스트가 만든 픽스처 14행</b>만 골라낸다 — 실 데이터가 섞여 있어도 흔들리지 않는다 */
    private List<ReportListVO> fixtureRows(ReportPage page) {
        Set<Long> ids = new LinkedHashSet<>(fixtureRptpIds());
        return page.content.stream().filter(row -> ids.contains(row.getRptpId())).toList();
    }

    /** 실패가 예상되는 목록 호출 — 에러 바디를 VO 로 읽으려다 터지지 않게 String 으로 받는다 */
    private int getReportsStatus(String query) {
        return restTemplate.exchange(REPORTS_URL + query, HttpMethod.GET, new HttpEntity<>(session),
                String.class).getStatusCode().value();
    }

    private List<ReportSettingVO> getSettings() {
        ResponseEntity<List<ReportSettingVO>> response = restTemplate.exchange(
                SETTINGS_URL, HttpMethod.GET, null, new ParameterizedTypeReference<List<ReportSettingVO>>() {});
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /**
     * 등록·수정 요청 바디. {@code Map.of} 는 null 값을 허용하지 않아 {@link LinkedHashMap} 을 쓴다 —
     * "보내지 않음"을 null 로 표현해야 조건부 필수(crltnMm)·선택 입력(지역) 검증을 태울 수 있다.
     */
    private Map<String, Object> settingBody(String rptpTypeCd, String crltnDeptCd, String crltnCyclCd,
                                            Integer crltnMm, Integer crltnDd, String sggNm, String emdCd) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rptpTypeCd", rptpTypeCd);
        body.put("crltnDeptCd", crltnDeptCd);
        body.put("crltnCyclCd", crltnCyclCd);
        body.put("crltnMm", crltnMm);
        body.put("crltnDd", crltnDd);
        body.put("sggNm", sggNm);
        body.put("emdCd", emdCd);
        return body;
    }

    private <T> ResponseEntity<T> postSetting(Map<String, Object> body, Class<T> responseType) {
        return exchangeSetting(SETTINGS_URL, HttpMethod.POST, body, responseType);
    }

    /** 🔴 쓰기는 반드시 세션을 실어 보낸다 (@PreAuthorize, M0402). */
    private <T> ResponseEntity<T> exchangeSetting(String url, HttpMethod method,
                                                  Map<String, Object> body, Class<T> responseType) {
        return restTemplate.exchange(url, method, auth.jsonEntity(session, body), responseType);
    }

    private ResponseEntity<String> deleteReport(long rptpId) {
        return restTemplate.exchange(REPORTS_URL + "/" + rptpId, HttpMethod.DELETE,
                new HttpEntity<>(session), String.class);
    }

    /** DELETE 는 바디가 없지만 세션은 필요하다 — 종전 {@code null} 엔티티를 대체한다. */
    private ResponseEntity<String> deleteSetting(String url) {
        return restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(session), String.class);
    }

    /** 등록 성공(201)을 전제로 응답 VO 를 돌려준다 */
    private ReportSettingVO createSetting(Map<String, Object> body) {
        ResponseEntity<ReportSettingVO> response = postSetting(body, ReportSettingVO.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ReportSettingVO findSetting(List<ReportSettingVO> settings, Long stngId) {
        return settings.stream()
                .filter(setting -> stngId.equals(setting.getStngId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("설정을 목록에서 찾지 못했습니다: " + stngId));
    }

    /** 소프트 삭제분까지 포함해 행을 읽는다 (del_dt 확인용) */
    private Map<String, Object> settingRow(Long stngId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM parking.tb_rptp_auto_crt_stng WHERE stng_id = ?", stngId);
        assertThat(rows).as("설정 행이 DB 에 남아 있어야 한다: " + stngId).hasSize(1);
        return rows.get(0);
    }

    private long countSettings() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp_auto_crt_stng", Long.class);
        return count == null ? 0L : count;
    }

    private void assertSettingMatches(ReportSettingVO setting, String rptpTypeCd, String crltnDeptCd,
                                      String crltnCyclCd, Integer crltnMm, Integer crltnDd, String regionNm) {
        assertThat(setting.getRptpTypeCd()).isEqualTo(rptpTypeCd);
        assertThat(setting.getCrltnDeptCd()).isEqualTo(crltnDeptCd);
        assertThat(setting.getCrltnCyclCd()).isEqualTo(crltnCyclCd);
        assertThat(setting.getCrltnMm()).isEqualTo(crltnMm);
        assertThat(setting.getCrltnDd()).isEqualTo(crltnDd);
        assertThat(setting.getRegionNm()).isEqualTo(regionNm);
        // 코드명은 공통코드 JOIN 으로 채워진다 — 코드만 바뀌고 이름이 안 따라오면 여기서 잡힌다
        assertThat(setting.getRptpTypeNm()).isNotNull();
        assertThat(setting.getCrltnDeptNm()).isNotNull();
        assertThat(setting.getCrltnCyclNm()).isNotNull();
    }

    /**
     * 다음 배포 예정일 단언. 계산식을 그대로 옮기지 않고 <b>성질</b>로 검증한다 —
     * 오늘 이후이고, 기준일이 맞고, 주기만큼 앞선 날짜는 이미 지났다(= 오늘 이후 가장 이른 날짜),
     * 그리고 기준월과 같은 주기 잔여류에 있다.
     */
    private void assertNextYmd(ReportSettingVO setting, int intervalMonths, Integer crltnMm,
                               int crltnDd, LocalDate today) {
        LocalDate ymd = setting.getCrltnYmd();
        assertThat(ymd).as("다음 배포 예정일은 오늘 이후여야 한다").isNotNull();
        assertThat(ymd).isAfterOrEqualTo(today);
        assertThat(ymd.getDayOfMonth()).as("기준일이 요청값과 같아야 한다").isEqualTo(crltnDd);
        assertThat(ymd.minusMonths(intervalMonths))
                .as("한 주기 앞선 날짜는 이미 지났어야 한다(가장 이른 예정일)").isBefore(today);
        if (crltnMm != null) {
            // QUARTER=3 · HALF=6 · YEAR=12 — 기준월에서 주기 배수만큼 떨어진 달이어야 한다(YEAR 면 곧 동일 월)
            assertThat(Math.floorMod(ymd.getMonthValue() - crltnMm, intervalMonths))
                    .as("기준월과 같은 주기 잔여류여야 한다").isZero();
            assertThat(setting.getCrltnMm()).isEqualTo(crltnMm);
        } else {
            assertThat(setting.getCrltnMm()).as("MONTH 는 기준월을 저장하지 않는다").isNull();
        }
    }

    private Set<Long> rptpIds(ReportPage page) {
        return page.content.stream().map(ReportListVO::getRptpId).collect(Collectors.toSet());
    }

    /**
     * 지정한 보고서들의 칸 하나를 {@code rptpId → 값} 으로 모은다.
     *
     * <p>{@code Collectors.toMap} 을 쓰지 않는다 — 값이 null 이면 NPE 로 터져 "칸이 비었다"는
     * 정작 확인하려던 사실이 단언 실패가 아니라 예외로 나온다.
     */
    private Map<Long, String> valueById(ReportPage page, List<Long> rptpIds,
                                        java.util.function.Function<ReportListVO, String> extractor) {
        Map<Long, String> result = new LinkedHashMap<>();
        page.content.stream()
                .filter(report -> rptpIds.contains(report.getRptpId()))
                .forEach(report -> result.put(report.getRptpId(), extractor.apply(report)));
        return result;
    }
}
