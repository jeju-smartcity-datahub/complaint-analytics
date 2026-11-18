package jeju.datahub.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.report.service.ReportAutoCrtVO;
import jeju.datahub.support.IntegrationLoginSupport;


/**
 * 보고서 <b>자동 생성</b> 내부 API 통합 테스트 (TSK-4027 Step 6 — 케이스 1~11 · 14 · 15 ·
 * TSK-4257 Step 5 — 케이스 16 · 17 생성자 = 설정 등록자).
 *
 * <p>계획서 케이스 표 16건 중 <b>14건</b>이 여기 있다. 남은 2건(12 · 13)은
 * {@code ReportAutoCrtApiKeyRequiredTest} · {@code ReportAutoCrtApiKeyMissingTest} 가 진다 —
 * {@code @SpringBootTest(properties=…)} 가 <b>클래스 단위</b>라 한 클래스에 담을 수 없다.
 *
 * <h2>🔴 이 테스트는 실제로 커밋한다</h2>
 *
 * <p>{@code RANDOM_PORT} 는 요청을 별도 스레드·별도 커넥션으로 처리하므로 테스트 트랜잭션이 서버 쪽
 * 쓰기를 감싸지 못한다 — 롤백이 걸리지 않는다. 그래서 <b>"실제 커밋 + 수동 정리"</b> 다
 * ({@code ReportIntegrationTest} · {@code HotspotReportIntegrationTest} 와 같은 전략).
 *
 * <p>🔴 <b>착수 상태를 깨뜨리지 않는다</b> — 이 테스트가 도는 시점의 {@code tb_rptp} 는 0행이고
 * {@code tb_rptp_auto_crt_stng} 에는 운영이 만든 설정({@code stng_id=100}) 한 행이 있다.
 * <ul>
 *   <li>픽스처 설정은 <b>{@code stngBase + n}</b> 으로 새로 만들고 {@link #cleanUp()} 이
 *       {@code > stngBase} 만 지운다 — {@code stng_id=100} 은 손대지 않는다.</li>
 *   <li>🔴 {@code last_crt_dt} 를 읽지도 쓰지도 않는다. 자동 생성 API 는 그 칸을 갱신하지 않으며
 *       (D9 — 배치가 한다) 테스트도 마찬가지다.</li>
 *   <li>🔴 {@link #BASE_UNSET}(-1) 초기화가 안전장치다. {@code long} 기본값 0 으로 두면
 *       {@code @BeforeEach} 가 터졌을 때 JUnit 이 그래도 실행하는 {@code @AfterEach} 가
 *       {@code DELETE ... WHERE stng_id > 0} 을 돌려 <b>설정 100 을 지운다.</b></li>
 * </ul>
 *
 * <h2>데이터 독립성 — 날짜를 리터럴로 박지 않는다</h2>
 *
 * <p>대상기간은 전부 <b>적재 구간에서 계산</b>한다({@link #resolveFixtures()}). 시드가 날짜만 옮겨
 * 재적재돼도 깨지지 않게 하기 위해서다. 0건 구간도 마찬가지로 <b>보유 구간 + 1년</b>을 계산하고,
 * 정말 0건인지 SQL 로 먼저 확인한다.
 *
 * <h2>인증</h2>
 *
 * <p>로그인하지 않는다 — {@code POST /api/internal/reports/auto-crt} 에는 {@code @PreAuthorize} 가
 * 없고({@code InternalApiKeyFilter} 가 인증을 진다), test 프로파일은
 * {@code app.auth.security-enabled=false} 라 {@code SecurityConfig} 체인 자체가 빠져 그 필터도 함께
 * 사라진다. 수동 생성({@code POST /api/complaints/reports})도 {@code @PreAuthorize} 가 없다.
 * 그래서 이 클래스는 {@code tb_lgn_hstry} 행을 한 줄도 남기지 않는다.
 *
 * <p>응답 바디의 {@code message} 는 단언하지 않는다 — devtools 유무로 갈려 환경에 따라 깨진다.
 * 실패가 예상되는 호출은 응답 타입을 {@code String.class} 로 받는다(에러 바디를 VO 로 역직렬화하려다
 * 터지는 것을 막는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportAutoCrtIntegrationTest {

    private static final String AUTO_CRT_URL = "/api/internal/reports/auto-crt";
    private static final String MANUAL_CVLCPT_URL = "/api/complaints/reports";
    private static final String CVLCPT_SNAPSHOT_URL = "/api/reports/{rptpId}/complaint";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 픽스처가 쓰는 읍면동 — {@code TB_ADMDST} 의 고정 코드(제주시 연동) */
    private static final String EMD_YEONDONG = "50110137";
    private static final String SGG_JEJU = "제주시";

    /** 설정 +1~+5 의 등록자 — {@link #installSettings()} 의 리터럴과 짝이다 (TSK-4257 D10) */
    private static final String STNG_CREATR_ID = "test";
    private static final String STNG_CREATR_NM = "테스트";

    /** 설정 +6·+7 의 등록자 — 이름이 비어 있는 설정(TSK-4202 이전 모양 · D11). {@code varchar(20)} 안이다 */
    private static final String NAMELESS_CREATR_ID = "t4257-noname";

    /**
     * 픽스처 PK 를 기존 최댓값에서 얼마나 띄울지. identity 시퀀스를 {@code base + 이 값} 으로 밀어
     * API 가 만드는 행이 픽스처 구간과 겹치지 않게 한다(명시 INSERT 는 identity 를 올리지 않는다).
     */
    private static final int SEQUENCE_GAP = 50;

    /** 🔴 {@code base} 미설정 표식. 0 으로 두면 {@code @AfterEach} 가 실 데이터를 전부 지운다. */
    private static final long BASE_UNSET = -1L;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private long rptpBase = BASE_UNSET;
    private long stngBase = BASE_UNSET;
    private long excnBase = BASE_UNSET;

    /** 원천 보유 구간 (민원 + 단속) — 케이스의 대상기간을 여기서 계산한다. */
    private LocalDate dataBgng;
    private LocalDate dataEnd;

    /** 데이터가 있는 <b>완전한 한 달</b> (케이스 1·2·3·4·4-1·10·14) */
    private YearMonth filledMonth;

    /** 원천이 <b>0건</b> 인 한 달 — 보유 구간 + 1년 (케이스 6·7) */
    private YearMonth emptyMonth;

    /** 데이터가 있는 <b>연도</b> (케이스 5 — 지역 전체·연간) */
    private int filledYear;

    // ────────────────────────────────────────────────────────── 픽스처

    /**
     * 🔴 TSK-4202 Step 5 — <b>수동</b> 경로(화면 API)와 스냅샷 조회가 로그인 세션을 요구하게 됐다
     * (Step 3·4). 자동 생성 내부 API({@code AUTO_CRT_URL})는 그대로 무인증 + {@code X-API-Key} 라
     * 세션을 싣지 않는다 — 그 둘이 다른 창구임을 테스트가 그대로 드러낸다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void loginAsMockUser() {
        auth = IntegrationLoginSupport.forTestChain(rest, jdbc);
        auth.markStart();
        session = auth.mockSession();
    }

    @AfterEach
    void logoutMockUser() {
        auth.cleanUp();
    }

    @BeforeEach
    void resolveFixtures() {
        seedDepts();

        String[] range = jdbc.queryForObject(dataRangeSql(),
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        assertThat(range[0])
                .as("원천(TB_CVLCPT·TB_CRDN_DSCTN)이 비어 있다 — 배치 적재분을 먼저 확인하라")
                .matches("\\d{8}");
        dataBgng = LocalDate.parse(range[0], YMD);
        dataEnd = LocalDate.parse(range[1], YMD);

        // 보유 구간 안에 '끝까지 다 들어오는' 마지막 달. 종료일이 그 달의 말일이 아니면 한 달 물린다.
        YearMonth candidate = YearMonth.from(dataEnd);
        filledMonth = candidate.atEndOfMonth().isAfter(dataEnd) ? candidate.minusMonths(1) : candidate;
        assertThat(sourceRowCount(filledMonth.atDay(1), filledMonth.atEndOfMonth()))
                .as("계산한 구간 %s 에 원천이 있어야 케이스 1 이 성립한다", filledMonth)
                .isPositive();

        emptyMonth = YearMonth.from(dataEnd.plusYears(1));
        assertThat(sourceRowCount(emptyMonth.atDay(1), emptyMonth.atEndOfMonth()))
                .as("계산한 구간 %s 이 실제로 0건이어야 케이스 6·7 이 성립한다", emptyMonth)
                .isZero();

        filledYear = dataEnd.getYear();

        rptpBase = maxId("parking.tb_rptp", "rptp_id");
        stngBase = maxId("parking.tb_rptp_auto_crt_stng", "stng_id");
        excnBase = maxId("parking.tb_anls_excn", "excn_id");

        installSettings();
    }

    /**
     * 🔴 부서 픽스처 7행 ({@code ReportIntegrationTest.seedDepts()} 와 <b>같은 7행</b>).
     *
     * <p>{@code TB_DEPT} 는 IdP {@code groups} 의 투영이라 빈 표로 시작할 수 있다. 🔴
     * <b>정리(DELETE)하지 않는다</b> — {@code ReportIntegrationTest}·{@code RiskNotiIntegrationTest}
     * 가 같은 7코드를 쓰므로 한쪽이 지우면 다른 쪽이 깨진다 (TSK-4183 D9).
     */
    private void seedDepts() {
        jdbc.update("""
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
     * 이 테스트만의 자동 생성 설정 7행.
     *
     * <p>🔴 <b>운영 설정({@code stng_id=100})을 쓰지 않는다</b> — 케이스마다 유형·주기·지역·삭제여부가
     * 달라야 하는데, 그것을 운영 행에 걸면 착수 상태를 건드리게 된다.
     *
     * <pre>
     *  +1  CVLCPT   MONTH  제주시 연동   케이스 1·2·3·4·4-1·7·10·14
     *  +2  HOTSPOT  YEAR   전체 지역     케이스 5
     *  +3  HOTSPOT  MONTH  전체 지역     케이스 6 (0건 구간)
     *  +4  POLICY_SIM MONTH 전체 지역    케이스 11 (지원하지 않는 유형)
     *  +5  CVLCPT   MONTH  제주시        케이스 9 (del_dt 가 차 있다)
     *  +6  CVLCPT   MONTH  제주시 연동   케이스 17 (creatr_nm NULL — TSK-4257 D11)
     *  +7  CVLCPT   MONTH  제주시 연동   케이스 17 (creatr_nm 공백 — TSK-4257 D11)
     * </pre>
     *
     * <p>🔴 케이스 11 이 설정을 따로 두는 이유 — 서비스는 <b>"설정과 불일치"</b> 를 먼저 보므로
     * ({@code rptpTypeCd} 대조가 앞이다) 설정이 {@code CVLCPT} 인 채로는 "미지원 유형" 분기에
     * 닿지 못한다. 설정 자체를 {@code POLICY_SIM} 으로 만들어야 그 분기를 덮는다.
     */
    private void installSettings() {
        jdbc.update("""
                INSERT INTO parking.tb_rptp_auto_crt_stng
                  (stng_id, rptp_type_cd, crltn_dept_cd, crltn_cycl_cd, crltn_mm, crltn_dd,
                   actvtn_yn, creatr_id, creatr_nm, crt_dt, last_crt_dt, sgg_nm, emd_cd, del_dt) VALUES
                  (%1$d+1,'CVLCPT',    'TRFC','MONTH', NULL,1, TRUE,'test','테스트', now(), NULL, '%2$s','%3$s', NULL),
                  (%1$d+2,'HOTSPOT',   'ALL', 'YEAR',     1,1, TRUE,'test','테스트', now(), NULL, NULL,  NULL,   NULL),
                  (%1$d+3,'HOTSPOT',   'ALL', 'MONTH', NULL,1, TRUE,'test','테스트', now(), NULL, NULL,  NULL,   NULL),
                  (%1$d+4,'POLICY_SIM','ALL', 'MONTH', NULL,1, TRUE,'test','테스트', now(), NULL, NULL,  NULL,   NULL),
                  (%1$d+5,'CVLCPT',    'TRFC','MONTH', NULL,1, TRUE,'test','테스트', now(), NULL, '%2$s', NULL,  now()),
                  (%1$d+6,'CVLCPT',    'TRFC','MONTH', NULL,1, TRUE,'%4$s', NULL,  now(), NULL, '%2$s','%3$s', NULL),
                  (%1$d+7,'CVLCPT',    'TRFC','MONTH', NULL,1, TRUE,'%4$s','   ',  now(), NULL, '%2$s','%3$s', NULL)
                """.formatted(stngBase, SGG_JEJU, EMD_YEONDONG, NAMELESS_CREATR_ID));

        advanceIdentity("parking.tb_rptp_auto_crt_stng", "stng_id", stngBase);
        advanceIdentity("parking.tb_rptp", "rptp_id", rptpBase);
        advanceIdentity("parking.tb_anls_excn", "excn_id", excnBase);
    }

    /**
     * 이 테스트가 만든 것만 지운다 — 픽스처 설정 5행 + API 가 만든 보고서·결과·실행.
     *
     * <p>순서는 FK 방향 그대로 <b>보고서 → 결과 → 실행 → 설정</b> 이다. 보고서가 설정·실행을 모두
     * 참조하므로 뒤집으면 헬퍼 자체가 FK 위반으로 터진다.
     *
     * <p>🔴 {@code base} 가 안 잡혔으면 <b>아무것도 지우지 않는다</b> — 0 으로 두면 전량 삭제다.
     * 🔴 {@code seedDepts()} 7행은 지우지 않는다(공유 픽스처).
     */
    @AfterEach
    void cleanUp() {
        if (rptpBase == BASE_UNSET || stngBase == BASE_UNSET || excnBase == BASE_UNSET) {
            return;
        }
        jdbc.update("DELETE FROM parking.tb_rptp WHERE rptp_id > ?", rptpBase);
        jdbc.update("DELETE FROM parking.tb_anls_rslt WHERE excn_id > ?", excnBase);
        jdbc.update("DELETE FROM parking.tb_anls_excn WHERE excn_id > ?", excnBase);
        jdbc.update("DELETE FROM parking.tb_rptp_auto_crt_stng WHERE stng_id > ?", stngBase);
    }

    // ────────────────────────────────────────────────────────── 1 · 2. 정상 생성과 멱등

    @Test
    @DisplayName("1. 정상 생성 (CVLCPT · MONTH) — 201 · created=true · 세 표가 각각 1행 는다")
    void case1_createsOneRowInEachTable() {
        long[] before = tableCounts();

        ResponseEntity<ReportAutoCrtVO> res = autoCrt(cvlcptMonthRequest());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ReportAutoCrtVO body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isCreated()).as("새로 만들었으므로 created=true").isTrue();
        assertThat(body.getRptpId()).isNotNull();
        assertThat(body.getExcnId()).isNotNull();
        assertThat(body.getTrgtPrdCd()).isEqualTo(monthPrdCd(filledMonth));
        assertThat(body.getRptpNm())
                .as("보고서명 규칙은 ReportAutoCrtNaming 이 정본이다 (D4·D6)")
                .isEqualTo("%d년 %d월 월간 민원 보고서 - %s 연동"
                        .formatted(filledMonth.getYear(), filledMonth.getMonthValue(), SGG_JEJU));

        assertThat(tableCounts())
                .as("실행 1 · 결과 1 · 보고서 1 이 한 번에 는다")
                .containsExactly(before[0] + 1, before[1] + 1, before[2] + 1);
    }

    @Test
    @DisplayName("2. 같은 요청 재호출 — 200 · created=false · rptpId 동일 · 행수 불변 (D5 멱등)")
    void case2_secondCallIsIdempotent() {
        ReportAutoCrtVO first = autoCrt(cvlcptMonthRequest()).getBody();
        assertThat(first).isNotNull();
        long[] after1 = tableCounts();

        ResponseEntity<ReportAutoCrtVO> res = autoCrt(cvlcptMonthRequest());

        assertThat(res.getStatusCode())
                .as("409 가 아니라 200 이다 — 배치가 재시도해도 같은 주기가 두 번 쌓이지 않는다")
                .isEqualTo(HttpStatus.OK);
        ReportAutoCrtVO second = res.getBody();
        assertThat(second).isNotNull();
        assertThat(second.isCreated()).isFalse();
        assertThat(second.getRptpId()).isEqualTo(first.getRptpId());
        assertThat(second.getExcnId()).isEqualTo(first.getExcnId());
        assertThat(second.getRptpNm()).isEqualTo(first.getRptpNm());
        assertThat(second.getTrgtPrdCd()).isEqualTo(first.getTrgtPrdCd());

        assertThat(tableCounts())
                .as("두 번째 호출은 생성 경로를 아예 타지 않는다 — 세 표 모두 그대로")
                .containsExactly(after1[0], after1[1], after1[2]);
    }

    // ────────────────────────────────────────────────────────── 3. AUTO 표식 5칸

    @Test
    @DisplayName("3. AUTO 표식 5칸 — crt_type='AUTO' + stng_id·trgt_prd_cd·crltn_dept_cd·crltn_cycl_cd 가 전부 채워진다")
    void case3_autoMarksAreWritten() {
        ReportAutoCrtVO body = autoCrt(cvlcptMonthRequest()).getBody();
        assertThat(body).isNotNull();

        Map<String, Object> row = rptpRow(body.getRptpId());

        assertThat(row.get("crt_type"))
                .as("기존 생성 서비스는 MANUAL 을 하드코딩한다 — 같은 트랜잭션에서 덮어썼는지 본다 (D4)")
                .isEqualTo("AUTO");
        assertThat(row.get("stng_id")).isEqualTo(settingId(1));
        assertThat(row.get("trgt_prd_cd")).isEqualTo(monthPrdCd(filledMonth));
        assertThat(row.get("crltn_dept_cd")).isEqualTo("TRFC");
        assertThat(row.get("crltn_cycl_cd")).isEqualTo("MONTH");
        assertThat(row.get("rptp_nm")).isEqualTo(body.getRptpNm());
    }

    // ────────────────────────────────────────────────────────── 4 · 4-1. file_path · file_fmt

    @Test
    @DisplayName("4. file_path 는 NULL (D3) — 자동 생성은 파일을 만들지 않는다")
    void case4_filePathIsNull() {
        ReportAutoCrtVO body = autoCrt(cvlcptMonthRequest()).getBody();
        assertThat(body).isNotNull();

        assertThat(rptpRow(body.getRptpId()).get("file_path")).isNull();
    }

    @Test
    @DisplayName("4-1. file_fmt 는 NULL (D14) — 수동 생성분은 여전히 값이 들어간다")
    void case4_1_fileFmtIsNullForAutoButKeptForManual() {
        ReportAutoCrtVO auto = autoCrt(cvlcptMonthRequest()).getBody();
        assertThat(auto).isNotNull();

        assertThat(rptpRow(auto.getRptpId()).get("file_fmt"))
                .as("마이그레이션 72 가 NOT NULL 을 풀었다 — 배치는 보낼 파일형식이 없다 (D14). "
                        + "🔴 여기가 400 으로 깨지면 생성 서비스에 @Validated 가 붙은 것이다")
                .isNull();

        // 🔴 같은 회귀의 반대쪽 — 수동 경로는 종전 그대로 값이 들어간다.
        Long manualRptpId = createManualComplaintReport("PDF");
        assertThat(rptpRow(manualRptpId).get("file_fmt")).isEqualTo("PDF");
    }

    // ────────────────────────────────────────────────────────── 5 · 6. HOTSPOT

    @Test
    @DisplayName("5. HOTSPOT 정상 생성 (지역 전체 · 연간) — 201")
    void case5_hotspotCreates() {
        long[] before = tableCounts();

        Map<String, Object> req = request(settingId(2), "HOTSPOT", String.valueOf(filledYear),
                LocalDate.of(filledYear, 1, 1), LocalDate.of(filledYear, 12, 31),
                null, null, "ALL", "YEAR");
        ResponseEntity<ReportAutoCrtVO> res = autoCrt(req);

        assertThat(res.getStatusCode())
                .as("집중구역도 자동 생성 대상이다 — 결과가 1건 이상이면 201")
                .isEqualTo(HttpStatus.CREATED);
        ReportAutoCrtVO body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isCreated()).isTrue();
        assertThat(body.getRptpNm())
                .isEqualTo("%d년 전체 집중구역 보고서 - 전체 지역".formatted(filledYear));

        Map<String, Object> row = rptpRow(body.getRptpId());
        assertThat(row.get("crt_type")).isEqualTo("AUTO");
        assertThat(row.get("crltn_cycl_cd")).isEqualTo("YEAR");
        assertThat(row.get("file_fmt")).isNull();
        assertThat(row.get("file_path")).isNull();

        assertThat(tableCounts()).containsExactly(before[0] + 1, before[1] + 1, before[2] + 1);
    }

    @Test
    @DisplayName("6. 🔴 HOTSPOT 0건 구간 — 400 이고 세 표 어디에도 행이 늘지 않는다 (D7)")
    void case6_hotspotEmptyPeriodIs400AndWritesNothing() {
        long[] before = tableCounts();

        Map<String, Object> req = request(settingId(3), "HOTSPOT", monthPrdCd(emptyMonth),
                emptyMonth.atDay(1), emptyMonth.atEndOfMonth(), null, null, "ALL", "MONTH");

        assertThat(postStatus(req))
                .as("열 수 없는 빈 보고서를 목록에 남기지 않는다 — 그 400 을 자동 생성이 삼키지도 않는다")
                .isEqualTo(400);

        assertThat(tableCounts())
                .as("실행 이력만 남는 반쪽 상태를 만들지 않는다 (세 표가 함께 롤백된다)")
                .containsExactly(before[0], before[1], before[2]);
    }

    // ────────────────────────────────────────────────────────── 7. CVLCPT 0건

    @Test
    @DisplayName("7. 🔴 CVLCPT 0건 구간 — 201 (D7 — 주기마다 빠짐없이 1건이 쌓여야 목록에 구멍이 안 난다)")
    void case7_complaintEmptyPeriodStillCreates() {
        long[] before = tableCounts();

        Map<String, Object> req = request(settingId(1), "CVLCPT", monthPrdCd(emptyMonth),
                emptyMonth.atDay(1), emptyMonth.atEndOfMonth(), SGG_JEJU, EMD_YEONDONG, "TRFC", "MONTH");
        ResponseEntity<ReportAutoCrtVO> res = autoCrt(req);

        assertThat(res.getStatusCode())
                .as("집중구역(0건이면 400)과 정책이 다르다 — '이 기간에 민원이 0건이었다' 도 보고할 값이다")
                .isEqualTo(HttpStatus.CREATED);
        ReportAutoCrtVO body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isCreated()).isTrue();

        assertThat(tableCounts()).containsExactly(before[0] + 1, before[1] + 1, before[2] + 1);
    }

    // ────────────────────────────────────────────────────────── 8 ~ 11. 400 분기 4종

    @Test
    @DisplayName("8. 없는 stngId — 400 · 아무것도 남지 않는다")
    void case8_unknownSettingIs400() {
        long[] before = tableCounts();

        Map<String, Object> req = cvlcptMonthRequest();
        req.put("stngId", stngBase + 9_999);

        assertThat(postStatus(req)).isEqualTo(400);
        assertThat(tableCounts()).containsExactly(before[0], before[1], before[2]);
    }

    @Test
    @DisplayName("9. 삭제된 설정 (del_dt 가 차 있다) — 400 · 아무것도 남지 않는다")
    void case9_deletedSettingIs400() {
        long[] before = tableCounts();

        Map<String, Object> req = request(settingId(5), "CVLCPT", monthPrdCd(filledMonth),
                filledMonth.atDay(1), filledMonth.atEndOfMonth(), SGG_JEJU, null, "TRFC", "MONTH");

        assertThat(postStatus(req))
                .as("지운 설정은 되살아나지 않는다 — '없음' 과 '이미 삭제됨' 을 서비스가 가른다")
                .isEqualTo(400);
        assertThat(tableCounts()).containsExactly(before[0], before[1], before[2]);
    }

    @Test
    @DisplayName("10. 요청의 rptpTypeCd 가 설정과 불일치 — 400 (배치가 낡은 값을 들고 있다)")
    void case10_typeMismatchIs400() {
        long[] before = tableCounts();

        Map<String, Object> req = cvlcptMonthRequest();
        req.put("rptpTypeCd", "HOTSPOT");   // 설정 +1 은 CVLCPT 다

        assertThat(postStatus(req)).isEqualTo(400);
        assertThat(tableCounts()).containsExactly(before[0], before[1], before[2]);
    }

    @Test
    @DisplayName("11. 지원하지 않는 유형 (POLICY_SIM) — 400 · 설정도 POLICY_SIM 이라 불일치 가드를 통과해 이 분기에 닿는다")
    void case11_unsupportedTypeIs400() {
        long[] before = tableCounts();

        Map<String, Object> req = request(settingId(4), "POLICY_SIM", monthPrdCd(filledMonth),
                filledMonth.atDay(1), filledMonth.atEndOfMonth(), null, null, "ALL", "MONTH");

        assertThat(postStatus(req))
                .as("자동 생성 대상은 CVLCPT·HOTSPOT 2종뿐이다")
                .isEqualTo(400);
        assertThat(tableCounts()).containsExactly(before[0], before[1], before[2]);
    }

    // ────────────────────────────────────────────────────────── 14. 스냅샷 재열람

    @Test
    @DisplayName("14. 스냅샷 재열람 — 자동 생성한 rptpId 로 GET /api/reports/{id}/complaint → 200 (보고서 관리 메뉴 보유자)")
    void case14_snapshotIsReadable() {
        ReportAutoCrtVO body = autoCrt(cvlcptMonthRequest()).getBody();
        assertThat(body).isNotNull();

        // 🔴 TSK-4202 — 스냅샷은 소유자 가드를 거친다. 🔁 TSK-4257 — AUTO 는 배포 부서·ALL·관리자만 본다.
        //    요청 부서가 TRFC 이고 mock-user 는 부서 NULL 이라, 보고서 관리 메뉴(M0402)를 줘 관리자(D1)로 연다.
        //    재로그인 불필요(매 요청 조회) · 회수는 auth.cleanUp() 이 한다
        auth.grantMenu("M0402");
        ResponseEntity<String> res = rest.exchange(CVLCPT_SNAPSHOT_URL, HttpMethod.GET,
                new HttpEntity<>(session), String.class, body.getRptpId());

        assertThat(res.getStatusCode())
                .as("걷어낸 목데이터 14건은 여기서 404 였다 — 자동 생성분은 실 스냅샷을 물고 있어야 한다")
                .isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"rptpId\":" + body.getRptpId());
    }

    // ────────────────────────────────────────────────────────── 16 · 17. 생성자 = 설정 등록자 (TSK-4257)

    @Test
    @DisplayName("16. 🔴 생성자 = 설정 등록자 — creatr_id·creatr_nm 이 설정의 두 칸이다 ('system'/'관리자' 가 아니다 · TSK-4257 D10·D14)")
    void case16_creatorIsSettingRegistrant() {
        ReportAutoCrtVO body = autoCrt(cvlcptMonthRequest()).getBody();
        assertThat(body).isNotNull();
        assertThat(body.isCreated()).isTrue();

        Map<String, Object> row = creatorRow(body.getRptpId());
        assertThat(row.get("creatr_id")).as("보고서 생성자 = 설정 creatr_id (D10)").isEqualTo(STNG_CREATR_ID);
        assertThat(row.get("creatr_nm")).as("보고서 생성자명 = 설정 creatr_nm (D10)").isEqualTo(STNG_CREATR_NM);
        assertThat(row.get("excutr_id")).as("실행자도 설정 등록자다 — 허용 부수효과 (D14)").isEqualTo(STNG_CREATR_ID);
    }

    @Test
    @DisplayName("17. 🔴 설정 creatr_nm 이 NULL·공백이면 생성자명은 creatr_id 로 채운다 (TSK-4257 D11 — '생성자' 칸이 비지 않는다)")
    void case17_blankCreatorNameFallsBackToId() {
        for (int n : new int[] {6, 7}) {
            Map<String, Object> req = request(settingId(n), "CVLCPT", monthPrdCd(filledMonth),
                    filledMonth.atDay(1), filledMonth.atEndOfMonth(), SGG_JEJU, EMD_YEONDONG, "TRFC", "MONTH");
            ReportAutoCrtVO body = autoCrt(req).getBody();
            assertThat(body).as("설정 +%d", n).isNotNull();
            assertThat(body.isCreated()).as("설정 +%d — 새로 만든다", n).isTrue();

            Map<String, Object> row = creatorRow(body.getRptpId());
            assertThat(row.get("creatr_id")).as("설정 +%d 생성자", n).isEqualTo(NAMELESS_CREATR_ID);
            assertThat(row.get("creatr_nm"))
                    .as("설정 +%d — 이름이 %s 라 creatr_id 로 채운다 (D11)", n, n == 6 ? "NULL" : "공백")
                    .isEqualTo(NAMELESS_CREATR_ID);
        }
    }

    // ────────────────────────────────────────────────────────── 15. 직접 생성 경로 무손상

    @Test
    @DisplayName("15. 직접 생성 경로 무손상 — 수동 API 는 여전히 crt_type='MANUAL' · stng_id NULL 로 저장된다")
    void case15_manualPathIsUntouched() {
        Long manualRptpId = createManualComplaintReport("DOCX");

        Map<String, Object> row = rptpRow(manualRptpId);
        assertThat(row.get("crt_type"))
                .as("자동 생성이 수동 경로를 오염시키지 않았다 (기존 서비스 시그니처를 바꾸지 않은 근거)")
                .isEqualTo("MANUAL");
        assertThat(row.get("stng_id")).isNull();
        assertThat(row.get("trgt_prd_cd")).isNull();
        assertThat(row.get("crltn_dept_cd")).isNull();
        assertThat(row.get("crltn_cycl_cd")).isNull();
        assertThat(row.get("file_fmt")).isEqualTo("DOCX");
    }

    // ────────────────────────────────────────────────────────── 헬퍼

    /** 케이스 1·2·3·4·4-1·10·14 의 공통 요청 (설정 +1 · 데이터가 있는 한 달). */
    private Map<String, Object> cvlcptMonthRequest() {
        return request(settingId(1), "CVLCPT", monthPrdCd(filledMonth),
                filledMonth.atDay(1), filledMonth.atEndOfMonth(), SGG_JEJU, EMD_YEONDONG, "TRFC", "MONTH");
    }

    private Map<String, Object> request(Long stngId, String rptpTypeCd, String trgtPrdCd,
                                        LocalDate bgng, LocalDate end, String sggNm, String emdCd,
                                        String crltnDeptCd, String crltnCyclCd) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stngId", stngId);
        body.put("rptpTypeCd", rptpTypeCd);
        body.put("trgtPrdCd", trgtPrdCd);
        body.put("bgngYmd", bgng.format(YMD));
        body.put("endYmd", end.format(YMD));
        body.put("sggNm", sggNm);
        body.put("emdCd", emdCd);
        body.put("crltnDeptCd", crltnDeptCd);
        body.put("crltnCyclCd", crltnCyclCd);
        return body;
    }

    private ResponseEntity<ReportAutoCrtVO> autoCrt(Map<String, Object> body) {
        return rest.postForEntity(AUTO_CRT_URL, jsonEntity(body), ReportAutoCrtVO.class);
    }

    /** 실패가 예상되는 호출 — 에러 바디를 VO 로 역직렬화하려다 터지지 않게 {@code String} 으로 받는다. */
    private int postStatus(Map<String, Object> body) {
        return rest.postForEntity(AUTO_CRT_URL, jsonEntity(body), String.class)
                .getStatusCode().value();
    }

    /** 수동 생성(화면 경로) 1건 — 케이스 4-1 · 15 가 대조군으로 쓴다. */
    private Long createManualComplaintReport(String fileFmt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", filledMonth.atDay(1).format(YMD));
        body.put("endYmd", filledMonth.atEndOfMonth().format(YMD));
        body.put("sggCd", null);
        body.put("emdCd", EMD_YEONDONG);
        body.put("fileFmt", fileFmt);

        ResponseEntity<Map> res = rest.exchange(MANUAL_CVLCPT_URL, HttpMethod.POST,
                auth.jsonEntity(session, body), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        return ((Number) res.getBody().get("rptpId")).longValue();
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Long settingId(int n) {
        return stngBase + n;
    }

    private static String monthPrdCd(YearMonth ym) {
        return "%04d-%02d".formatted(ym.getYear(), ym.getMonthValue());
    }

    private Map<String, Object> rptpRow(Long rptpId) {
        return jdbc.queryForMap("""
                SELECT crt_type, stng_id, excn_id, rptp_nm, stts_cd, file_fmt, file_path,
                       trgt_prd_cd, crltn_dept_cd, crltn_cycl_cd
                  FROM parking.tb_rptp WHERE rptp_id = ?""", rptpId);
    }

    /** 생성자 칸 — 보고서 {@code creatr_id}·{@code creatr_nm} + 실행 {@code excutr_id} (TSK-4257 D10·D11·D14). */
    private Map<String, Object> creatorRow(Long rptpId) {
        return jdbc.queryForMap(
                "SELECT r.creatr_id, r.creatr_nm, e.excutr_id"
                        + "  FROM parking.tb_rptp r JOIN parking.tb_anls_excn e ON e.excn_id = r.excn_id"
                        + " WHERE r.rptp_id = ?", rptpId);
    }

    /** {@code [tb_anls_excn, tb_anls_rslt, tb_rptp]} 행수 — 단언 순서를 한곳에 고정한다. */
    private long[] tableCounts() {
        return new long[] {
                count("parking.tb_anls_excn"),
                count("parking.tb_anls_rslt"),
                count("parking.tb_rptp") };
    }

    private long count(String table) {
        Long c = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return c == null ? 0L : c;
    }

    private long maxId(String table, String pkColumn) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(max(" + pkColumn + "), 0) FROM " + table, Long.class);
        return max == null ? 0L : max;
    }

    private void advanceIdentity(String table, String pkColumn, long base) {
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence(?::text, ?::text), ?, false)",
                Long.class, table, pkColumn, base + SEQUENCE_GAP);
    }

    /** 보유 구간(민원 + 단속) — {@code HotspotReportIntegrationTest} 와 같은 조건이다. */
    private static String dataRangeSql() {
        return "SELECT to_char(LEAST(   (SELECT min(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                + "                     (SELECT min(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD'),"
                + "       to_char(GREATEST((SELECT max(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                + "                     (SELECT max(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD')";
    }

    /** 구간 안의 원천 행 수(민원 + 단속). "정말 0건인가" 를 SQL 로 보장한다. */
    private long sourceRowCount(LocalDate bgng, LocalDate end) {
        String b = bgng.format(YMD);
        String e = end.format(YMD);
        Long count = jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM parking.tb_cvlcpt"
                        + "  WHERE cvlcpt_type_cd <> '99'"
                        + "    AND ocrn_dt >= to_date(?, 'YYYYMMDD') AND ocrn_dt < to_date(?, 'YYYYMMDD') + 1)"
                        + "     + (SELECT count(*) FROM parking.tb_crdn_dsctn"
                        + "  WHERE crdn_dt >= to_date(?, 'YYYYMMDD') AND crdn_dt < to_date(?, 'YYYYMMDD') + 1)",
                Long.class, b, e, b, e);
        return count == null ? 0L : count;
    }
}
