package jeju.datahub.complaint;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.analysis.service.HotspotRankingResultVO;
import jeju.datahub.analysis.service.HotspotRankingZoneVO;
import jeju.datahub.complaint.service.ComplaintReportVO;
import jeju.datahub.complaint.service.CrdnCompareVO;
import jeju.datahub.complaint.service.ShortageCompareVO;
import jeju.datahub.support.IntegrationLoginSupport;


/**
 * 민원현황 보고서 §4·§5 (단속 집중 지역 · 주차 수요 부족 지역) <b>생성 → 스냅샷 왕복</b> 통합 테스트.
 * ({@code POST /api/complaints/reports} · {@code GET /api/reports/&#123;rptpId&#125;/complaint})
 *
 * <p>같은 엔드포인트의 기존 계약(키 3종·404 분기·0건 정책)은 {@link ComplaintReportIntegrationTest} 가
 * 이미 덮는다. 여기는 <b>새로 넣은 두 배열</b>({@code crdnCompare}·{@code shortageCompare})
 * 만 본다 — 생성 응답 · jsonb 저장 모양 · 스냅샷 왕복 · 구 스냅샷 하위호환.
 *
 * <h2>🔴 왜 {@code @Transactional} 롤백을 쓰지 않는가</h2>
 * {@code RANDOM_PORT} 는 요청을 별도 스레드·별도 커넥션으로 처리해 테스트 트랜잭션이 서버 쪽 쓰기를
 * 감싸지 못한다. 더구나 이 테스트가 보려는 것이 <b>트랜잭션 밖에서 다시 읽은 스냅샷</b>이라 롤백이
 * 걸리는 조합을 택하면 검증 대상 자체가 사라진다. 그래서 {@link #deleteRowsThisTestCreated()} 가
 * 기준선(최대 id) 뒤에 생긴 행만 <b>FK 역순</b>으로 지운다.
 *
 * <h2>🔴 기준선 이하는 건드리지 않는다</h2>
 * 보고서 1건은 세 표({@code tb_anls_excn} → {@code tb_anls_rslt} → {@code tb_rptp})에 쓴다.
 * {@code @BeforeEach} 가 {@code coalesce(max(...),0)} 으로 잡은 기준선 <b>초과분만</b> 지우므로
 * 로컬에 이미 있던 보고서(수동 검증분·구 스냅샷)는 그대로 남는다.
 *
 * <h2>🔴 조회 날짜를 코드에 박지 않는다</h2>
 * 민원·단속 적재분은 배치가 소유한다 — 재적재 한 번이면 구간이 통째로 옮겨간다. 그래서
 * <ul>
 *   <li>현재 구간은 {@code LEAST(max(ocrn_dt), max(crdn_dt))} 가 든 달을 SQL 로 읽어 쓴다
 *       (두 원천이 겹치는 끝 달이라야 두 배열이 모두 채워진다).</li>
 *   <li>"민원 0건" 구간도 리터럴이 아니라 <b>민원 0 · 단속 &gt; 0 인 달</b>을 SQL 로 찾는다.</li>
 *   <li>기대값은 매퍼·API 를 다시 부르지 않고 <b>독립 SQL</b> 로 만든다. 예외는 케이스 4 하나이며,
 *       그것은 "두 엔드포인트가 같은 표를 보는가" 를 <b>의도적으로</b> 교차 대조하는 케이스다.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComplaintReportRankIntegrationTest {

    private static final String CREATE_URL = "/api/complaints/reports";
    private static final String SNAPSHOT_URL = "/api/reports/{rptpId}/complaint";
    private static final String RANKING_URL = "/api/hotspots/ranking";

    /** 보고서 생성 요청의 날짜 형식 (YYYYMMDD). */
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 분석레이어 순위 API 의 날짜 형식 (yyyy-MM-dd). */
    private static final DateTimeFormatter DASHED = DateTimeFormatter.ISO_LOCAL_DATE;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 🔴 기준선 — 이 값 <b>초과</b> id 만 {@code @AfterEach} 가 지운다. */
    private long startRptpId;
    private long startExcnId;
    private long startLgnSn;

    /** 민원·단속이 모두 있는 현재 구간 (SQL 로 읽는다 — 상수 박기 금지). */
    private String bgngYmd;
    private String endYmd;

    /** 민원 0건 · 단속 &gt; 0 인 구간 (케이스 6 — SQL 로 찾는다). */
    private String emptyBgngYmd;
    private String emptyEndYmd;

    // ──────────────────────────────────────────────────────── 기준선 · 구간 해석

    /**
     * 🔴 생성·스냅샷이 로그인 세션을 요구한다. {@code mock-login} 세션을 쓰고,
     * 익명이 401 인 것은 전용 케이스(7)가 지킨다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void resolveBaselineAndPeriods() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
        startRptpId = scalarLong("SELECT coalesce(max(rptp_id),0) FROM parking.tb_rptp");
        startExcnId = scalarLong("SELECT coalesce(max(excn_id),0) FROM parking.tb_anls_excn");
        startLgnSn = scalarLong("SELECT coalesce(max(lgn_sn),0) FROM parking.tb_lgn_hstry");

        // 민원·단속 둘 다 있는 마지막 달 — 두 배열이 모두 채워지는 구간이어야 케이스가 성립한다.
        String[] period = jdbcTemplate.queryForObject("""
                SELECT to_char(date_trunc('month', d), 'YYYYMMDD'),
                       to_char(d, 'YYYYMMDD')
                  FROM (SELECT LEAST(
                                 (SELECT max(ocrn_dt) FROM parking.tb_cvlcpt
                                   WHERE cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL),
                                 (SELECT max(crdn_dt) FROM parking.tb_crdn_dsctn
                                   WHERE emd_cd IS NOT NULL))::date AS d) t""",
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(period).isNotNull();
        bgngYmd = period[0];
        endYmd = period[1];
        assertThat(bgngYmd)
                .as("원천(TB_CVLCPT / TB_CRDN_DSCTN)이 비어 있다 — 배치 적재분·시드를 먼저 확인하라")
                .matches("\\d{8}");
        assertThat(endYmd).matches("\\d{8}");

        // 민원 0건 · 단속 > 0 인 달 — 케이스 6 이 "crdnCompare 는 [] 가 아니다" 를 보려면 둘 다 필요하다.
        String[] empty = jdbcTemplate.query("""
                SELECT to_char(m, 'YYYYMMDD'),
                       to_char(m + interval '1 month' - interval '1 day', 'YYYYMMDD')
                  FROM generate_series(
                         date_trunc('month', (SELECT min(crdn_dt) FROM parking.tb_crdn_dsctn)),
                         date_trunc('month', (SELECT max(crdn_dt) FROM parking.tb_crdn_dsctn)),
                         interval '1 month') m
                 WHERE (SELECT count(*) FROM parking.tb_crdn_dsctn c
                         WHERE c.crdn_dt >= m AND c.crdn_dt < m + interval '1 month'
                           AND c.emd_cd IS NOT NULL) > 0
                   AND (SELECT count(*) FROM parking.tb_cvlcpt v
                         WHERE v.ocrn_dt >= m AND v.ocrn_dt < m + interval '1 month'
                           AND v.cvlcpt_type_cd <> '99' AND v.emd_cd IS NOT NULL) = 0
                 ORDER BY m
                 LIMIT 1""",
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) })
                .stream().findFirst().orElse(null);
        assertThat(empty)
                .as("민원 0건 · 단속 > 0 인 달이 없다 — 케이스 6 이 성립하지 않는 적재 상태다")
                .isNotNull();
        emptyBgngYmd = empty[0];
        emptyEndYmd = empty[1];
    }

    /**
     * 🔴 기준선 <b>초과</b> 행만 FK 역순으로 지운다 — {@code tb_rptp}(참조) → {@code tb_anls_rslt} →
     * {@code tb_anls_excn}(피참조). 뒤집으면 {@code fk_tb_rptp_excn_id} 위반으로 헬퍼가 터진다.
     * ⚠️ 로그인 이력은 {@code lgn_sn > startSn} 만 지운다 — {@code OR user_id IS NULL} 로 넓히면
     * 테스트가 만들지 않은 실 실패 이력까지 지운다.
     */
    @AfterEach
    void deleteRowsThisTestCreated() {
        jdbcTemplate.update("DELETE FROM parking.tb_rptp      WHERE rptp_id > ?", startRptpId);
        jdbcTemplate.update("DELETE FROM parking.tb_anls_rslt WHERE excn_id > ?", startExcnId);
        jdbcTemplate.update("DELETE FROM parking.tb_anls_excn WHERE excn_id > ?", startExcnId);
        auth.cleanUp();
        jdbcTemplate.update("DELETE FROM parking.tb_lgn_hstry WHERE lgn_sn  > ?", startLgnSn);
    }

    // ──────────────────────────────────────── 1. 생성 201 — 두 배열이 비어 있지 않다

    @Test
    @DisplayName("1. 생성 201 — crdnCompare·shortageCompare 가 비어 있지 않고 독립 SQL 모집단과 같다")
    void createFillsBothRankArrays() {
        ComplaintReportVO body = createOk(request(bgngYmd, endYmd));

        List<CrdnCompareVO> crdn = body.getCrdnCompare();
        List<ShortageCompareVO> shortage = body.getShortageCompare();
        assertThat(crdn).as("단속 집중 지역이 비었다 — 구간에 단속이 있는데 §4 가 비면 재집계가 깨진 것이다")
                .isNotEmpty();
        assertThat(shortage).as("주차 수요 부족 지역이 비었다 — §5 재집계가 깨진 것이다").isNotEmpty();

        // 독립 SQL — 매퍼·API 를 다시 부르지 않는다.
        assertThat(crdn).hasSize((int) crdnEmdCount(bgngYmd, endYmd));
        assertThat(shortage).hasSize((int) shortageZoneCount(bgngYmd, endYmd));

        // 1위 행 — 단속 최다 동 · 건수 · 증감률(직전 동일 길이 구간)을 SQL 로 직접 만든다.
        Map<String, Object> top = topCrdnEmd(bgngYmd, endYmd);
        CrdnCompareVO first = crdn.get(0);
        assertThat(first.rank()).isEqualTo(1L);
        assertThat(first.emdCd()).isEqualTo(top.get("emd_cd"));
        assertThat(first.crdnCnt()).isEqualTo(((Number) top.get("cnt")).longValue());
        assertThat(first.crdnCnt()).as("1위 단속 건수가 0 이면 이 케이스가 아무것도 보지 않는다").isPositive();
        assertThat(first.emdNm()).as("zoneNm → emdNm 으로 옮겨 담는다").isNotBlank();
        assertThat(first.deltaPct()).isEqualTo(expectedDeltaPct(first.crdnCnt(),
                prevCrdnCnt((String) top.get("emd_cd"), bgngYmd, endYmd)));

        // 수요 부족 1위 = PSDR 최소 동 (독립 SQL — fn_jenks_shortage 직접 호출)
        assertThat(shortage.get(0).rank()).isEqualTo(1L);
        assertThat(shortage.get(0).emdCd()).isEqualTo(topShortageEmd(bgngYmd, endYmd));
    }

    // ──────────────────────────────────────── 2. 경계 polygon 을 담지 않는다

    @Test
    @DisplayName("2. 두 배열 원소에 bndryGeoJson 키가 없다 — 저장 jsonb·응답 본문 양쪽")
    void rankArraysCarryNoBoundaryPolygon() {
        ComplaintReportVO body = createOk(request(bgngYmd, endYmd));

        for (String key : List.of("crdnCompare", "shortageCompare")) {
            assertThat(jsonbElementCount(body.getExcnId(), key))
                    .as("%s 가 비어 있으면 '경계가 없다' 단언이 아무것도 보지 않는다", key)
                    .isPositive();
            assertThat(jsonbElementsWithKey(body.getExcnId(), key, "bndryGeoJson"))
                    .as("%s 원소에 bndryGeoJson 이 들어갔다 — 보고서 1건이 수 MB 가 된다", key)
                    .isZero();
        }

        String raw = restTemplate.exchange(SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session),
                String.class, body.getRptpId()).getBody();
        assertThat(raw).isNotNull();
        assertThat(raw).as("스냅샷 응답 본문에도 경계 polygon 이 없어야 한다").doesNotContain("bndryGeoJson");
    }

    // ──────────────────────────────────────── 3. 스냅샷 왕복 — 길이·1위 동 + 새 필드 값

    @Test
    @DisplayName("3. 스냅샷 왕복 — 같은 길이·같은 1위 동이고 증감 필드 값까지 생성 응답과 같다")
    void snapshotRoundTripKeepsDeltaFields() {
        ComplaintReportVO created = createOk(request(bgngYmd, endYmd));
        ComplaintReportVO snapshot = snapshotOk(created.getRptpId());

        assertThat(created.getCrdnCompare()).isNotEmpty();
        assertThat(created.getShortageCompare()).isNotEmpty();
        assertThat(snapshot.getCrdnCompare()).hasSameSizeAs(created.getCrdnCompare());
        assertThat(snapshot.getShortageCompare()).hasSameSizeAs(created.getShortageCompare());

        CrdnCompareVO crdnBefore = created.getCrdnCompare().get(0);
        CrdnCompareVO crdnAfter = snapshot.getCrdnCompare().get(0);
        assertThat(crdnAfter.emdCd()).isEqualTo(crdnBefore.emdCd());
        assertThat(crdnAfter.rank()).isEqualTo(crdnBefore.rank());
        assertThat(crdnAfter.crdnCnt()).isEqualTo(crdnBefore.crdnCnt());
        // 🔴 길이만 보면 Integer·boolean 직렬화 왕복이 깨져도 통과한다 — 값까지 본다.
        assertThat(crdnAfter.deltaPct()).isEqualTo(crdnBefore.deltaPct());
        assertThat(crdnAfter.deltaPctInferred()).isEqualTo(crdnBefore.deltaPctInferred());

        ShortageCompareVO shBefore = created.getShortageCompare().get(0);
        ShortageCompareVO shAfter = snapshot.getShortageCompare().get(0);
        assertThat(shAfter.emdCd()).isEqualTo(shBefore.emdCd());
        assertThat(shAfter.rank()).isEqualTo(shBefore.rank());
        assertThat(shAfter.deltaPctInferred()).isEqualTo(shBefore.deltaPctInferred());
        // 🔴 BigDecimal 은 scale 이 깎여 돌아온다 — equals 가 아니라 수치 비교다 (케이스 3-1).
        assertNumericallyEqual(shAfter.shortageDeltaPp(), shBefore.shortageDeltaPp(), "shortageDeltaPp");

        // 0건이면 조용히 통과하는 단언이 되지 않게 — 증감이 실제로 산출된 행이 있어야 한다.
        assertThat(created.getCrdnCompare()).as("deltaPct 가 전부 null 이면 왕복 단언이 빈 껍데기다")
                .anyMatch(z -> z.deltaPct() != null);
        assertThat(created.getShortageCompare())
                .as("shortageDeltaPp 가 전부 null 이면 왕복 단언이 빈 껍데기다")
                .anyMatch(z -> z.shortageDeltaPp() != null);
    }

    // ──────────────────────────────────────── 3-1. BigDecimal 왕복은 수치 비교다

    @Test
    @DisplayName("3-1. BigDecimal 왕복 — scale 은 깎일 수 있어도 값은 같다 (isEqualByComparingTo)")
    void bigDecimalRoundTripIsNumericallyEqual() {
        ComplaintReportVO created = createOk(request(bgngYmd, endYmd));
        ComplaintReportVO snapshot = snapshotOk(created.getRptpId());

        List<ShortageCompareVO> before = created.getShortageCompare();
        List<ShortageCompareVO> after = snapshot.getShortageCompare();
        assertThat(before).isNotEmpty();
        assertThat(after).hasSameSizeAs(before);

        // 🔴 readTree 가 소수를 DoubleNode 로 읽어 convertValue 가 BigDecimal.valueOf(double) 로
        //    복원한다 — 저장 jsonb 의 scale 이 보존되지 않는다(값은 수치적으로 같다). 공용 스냅샷
        //    파싱 경로를 바꾸는 것은 이번 범위 밖이라, 단언을 수치 비교로 맞춘다.
        int compared = 0;
        for (int i = 0; i < before.size(); i++) {
            ShortageCompareVO b = before.get(i);
            ShortageCompareVO a = after.get(i);
            assertThat(a.emdCd()).as("행 순서가 왕복에서 바뀌었다 (i=%d)", i).isEqualTo(b.emdCd());
            assertNumericallyEqual(a.shortageRatio(), b.shortageRatio(), "shortageRatio");
            assertNumericallyEqual(a.pkltCpctySum(), b.pkltCpctySum(), "pkltCpctySum");
            assertNumericallyEqual(a.demandIndex(), b.demandIndex(), "demandIndex");
            assertNumericallyEqual(a.shortageDeltaPp(), b.shortageDeltaPp(), "shortageDeltaPp");
            if (b.shortageRatio() != null) {
                compared++;
            }
        }
        assertThat(compared).as("비교한 BigDecimal 이 0건이면 이 케이스가 아무것도 보지 않는다").isPositive();
    }

    // ──────────────────────────────────────── 전기 단속 · 전기 부족률

    @Test
    @DisplayName(". 전기 단속 건수는 같은 동·직전 구간의 직접 집계와 같다")
    void crdnPrevCntMatchesDirectCount() {
        ComplaintReportVO body = createOk(request(bgngYmd, endYmd));
        CrdnCompareVO first = body.getCrdnCompare().get(0);

        assertThat(first.prevCrdnCnt()).isEqualTo(prevCrdnCnt(first.emdCd(), bgngYmd, endYmd));
    }

    @Test
    @DisplayName(". 전기 부족률은 직전 구간 함수 호출의 민원 ÷ (공급 + 민원) 과 같다")
    void shortagePrevRatioMatchesDirectCall() {
        ComplaintReportVO body = createOk(request(bgngYmd, endYmd));
        ShortageCompareVO first = body.getShortageCompare().get(0);

        LocalDate curStart = LocalDate.parse(bgngYmd, YMD);
        long len = LocalDate.parse(endYmd, YMD).toEpochDay() - curStart.toEpochDay() + 1;
        BigDecimal expected = jdbcTemplate.queryForObject("""
                SELECT ROUND(out_cvlcpt_sum::numeric / NULLIF(out_pklt_sum + out_cvlcpt_sum, 0), 4)
                  FROM parking.fn_jenks_shortage(
                         to_date(?, 'YYYYMMDD'), to_date(?, 'YYYYMMDD'),
                         NULL::text, NULL::text, NULL::text, NULL::text)
                 WHERE out_emd_cd = ?""", BigDecimal.class,
                curStart.minusDays(len).format(YMD), curStart.minusDays(1).format(YMD), first.emdCd());

        assertThat(expected).as("1위 동의 전기 부족률이 없으면 이 케이스가 아무것도 보지 않는다").isNotNull();
        assertThat(first.prevShortageRatio()).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName(". 전기 값은 ranking API 응답에 나가지 않는다")
    void prevValuesStayOutOfRankingResponse() {
        for (String view : List.of("enforcement", "shortage")) {
            String raw = restTemplate.getForObject(
                    RANKING_URL + "?view={view}&startDt={startDt}&endDt={endDt}", String.class,
                    view, LocalDate.parse(bgngYmd, YMD), LocalDate.parse(endYmd, YMD));
            assertThat(raw).as("view=%s", view).contains("zones")
                    .doesNotContain("prevCrdnCnt").doesNotContain("prevShortageRatio");
        }
    }

    @Test
    @DisplayName(". 🔴 구 스냅샷 — 전기 키가 없어도 200 이고 값은 0 이 아니라 null 이다")
    void oldSnapshotWithoutPrevRankKeysReadsAsNull() {
        ComplaintReportVO created = createOk(request(bgngYmd, endYmd));
        jdbcTemplate.update("""
                UPDATE parking.tb_anls_rslt
                   SET rslt_json = jsonb_set(jsonb_set(rslt_json,
                         '{crdnCompare}', (SELECT jsonb_agg(e - 'prevCrdnCnt')
                                             FROM jsonb_array_elements(rslt_json->'crdnCompare') e)),
                         '{shortageCompare}', (SELECT jsonb_agg(e - 'prevShortageRatio')
                                                 FROM jsonb_array_elements(rslt_json->'shortageCompare') e))
                 WHERE excn_id = ?""", created.getExcnId());

        ComplaintReportVO snapshot = snapshotOk(created.getRptpId());

        assertThat(snapshot.getCrdnCompare()).isNotEmpty()
                .allSatisfy(row -> assertThat(row.prevCrdnCnt()).isNull());
        assertThat(snapshot.getShortageCompare()).isNotEmpty()
                .allSatisfy(row -> assertThat(row.prevShortageRatio()).isNull());
    }

    // ──────────────────────────────────────── 4. 같은 조건 ranking API 와 교차 대조

    @Test
    @DisplayName("4. crdnCompare 의 순위·건수가 같은 조건 GET /api/hotspots/ranking?view=enforcement 와 같다")
    void crdnCompareMatchesEnforcementRanking() {
        ComplaintReportVO body = createOk(request(bgngYmd, endYmd));
        List<CrdnCompareVO> crdn = body.getCrdnCompare();
        assertThat(crdn).isNotEmpty();

        // 🔴 여기만 "두 엔드포인트 교차 대조" 가 목적이라 API 를 다시 부른다(self-referential 예외).
        List<HotspotRankingZoneVO> zones = enforcementRanking(bgngYmd, endYmd);
        assertThat(zones).hasSameSizeAs(crdn);

        for (int i = 0; i < crdn.size(); i++) {
            CrdnCompareVO c = crdn.get(i);
            HotspotRankingZoneVO z = zones.get(i);
            assertThat(c.emdCd()).as("i=%d 동이 다르다", i).isEqualTo(z.getEmdCd());
            assertThat(c.rank()).as("i=%d 순위가 다르다", i).isEqualTo(z.getRank());
            assertThat(c.crdnCnt()).as("i=%d 단속 건수가 다르다", i).isEqualTo(z.getCrdnCnt());
            assertThat(c.cvlcptCnt()).as("i=%d 민원 건수가 다르다", i).isEqualTo(z.getCvlcptCnt());
            assertThat(c.emdNm()).as("i=%d zoneNm → emdNm", i).isEqualTo(z.getZoneNm());
            assertThat(c.deltaPct()).as("i=%d 증감률이 다르다", i).isEqualTo(z.getDeltaPct());
            assertThat(c.topCrdnMthd()).as("i=%d 최다 단속유형이 다르다", i).isEqualTo(z.getTopCrdnMthd());
            assertThat(c.topCrdnMthdRatio()).as("i=%d 최다 단속유형 비율이 다르다", i)
                    .isEqualTo(z.getTopCrdnMthdRatio());
        }
    }

    @Test
    @DisplayName("최다 단속유형 키가 없는 구 스냅샷 원소는 null 로 읽힌다 (0.0 금지)")
    void oldSnapshotElementReadsTopMthdAsNull() throws Exception {
        CrdnCompareVO old = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"rank\":1,\"emdCd\":\"50110137\",\"emdNm\":\"연동\",\"crdnCnt\":10,"
                        + "\"cvlcptCnt\":3,\"deltaPct\":5,\"deltaPctInferred\":false}",
                CrdnCompareVO.class);
        assertThat(old.crdnCnt()).isEqualTo(10L);
        assertThat(old.topCrdnMthd()).isNull();
        assertThat(old.topCrdnMthdRatio()).isNull();
    }

    // ──────────────────────────────────────── 5. 구 스냅샷 하위호환 (키 2개 부재)

    @Test
    @DisplayName("5. 두 키가 빠진 rslt_json 도 200 + 빈 배열 — 구 스냅샷 하위호환")
    void snapshotWithoutNewKeysStillReturnsEmptyArrays() {
        // 🔴 기존 보고서를 고치지 않는다 — 이 테스트가 만든 것에서 키를 지운다(@AfterEach 가 정리한다).
        ComplaintReportVO created = createOk(request(bgngYmd, endYmd));
        assertThat(created.getCrdnCompare()).as("지울 키가 실제로 채워져 있어야 이 케이스가 성립한다")
                .isNotEmpty();

        int updated = jdbcTemplate.update(
                "UPDATE parking.tb_anls_rslt"
                        + "    SET rslt_json = rslt_json - 'crdnCompare' - 'shortageCompare'"
                        + "  WHERE excn_id = ?", created.getExcnId());
        assertThat(updated).isEqualTo(1);
        assertThat(jsonbHasKey(created.getExcnId(), "crdnCompare")).isFalse();
        assertThat(jsonbHasKey(created.getExcnId(), "shortageCompare")).isFalse();
        assertThat(jsonbHasKey(created.getExcnId(), "kpi")).as("나머지 키는 그대로여야 한다").isTrue();

        ResponseEntity<ComplaintReportVO> res =
                restTemplate.exchange(SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session),
                        ComplaintReportVO.class, created.getRptpId());
        assertThat(res.getStatusCode().value())
                .as("키가 없다고 500 이 되면 구 보고서가 전부 열리지 않는다").isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getCrdnCompare()).as("키 부재는 빈 배열이다 (null 아님)").isEmpty();
        assertThat(res.getBody().getShortageCompare()).isEmpty();
        assertThat(res.getBody().getKpi()).as("나머지 본문은 그대로 복원된다").isNotNull();
    }

    // ──────────────────────────────────────── 6. 민원 0건 구간 — crdnCompare 는 [] 가 아니다

    @Test
    @DisplayName("6. 민원 0건 구간 — 201 이고 민원 파생 3배열만 [] · crdnCompare 는 단속이 있으면 채워진다")
    void zeroComplaintPeriodStillCarriesEnforcement() {
        assertThat(complaintRowCount(emptyBgngYmd, emptyEndYmd))
                .as("고른 구간 %s~%s 이 실제로 민원 0건이어야 한다", emptyBgngYmd, emptyEndYmd)
                .isZero();
        long crdnEmds = crdnEmdCount(emptyBgngYmd, emptyEndYmd);
        assertThat(crdnEmds).as("고른 구간에 단속이 있어야 '단속은 민원과 무관' 을 볼 수 있다").isPositive();

        ComplaintReportVO body = createOk(request(emptyBgngYmd, emptyEndYmd));

        assertThat(body.getEmdCompare()).as("민원 0건이면 민원 상위 지역은 없다").isEmpty();
        assertThat(body.getNegEmdCompare()).isEmpty();
        // 🔴 모집단이 out_cvlcpt_sum > 0 이라 민원 0건이면 수요 부족도 0건이다.
        assertThat(body.getShortageCompare()).isEmpty();
        // 🔴 crdnCompare 는 [] 가 아니다 — selectEnforcement 는 tb_crdn_dsctn 단독 집계다.
        assertThat(body.getCrdnCompare())
                .as("단속 집계는 민원 유무와 무관하다 — 여기서 비면 §4 가 민원에 묶인 것이다")
                .isNotEmpty();
        assertThat(body.getCrdnCompare()).hasSize((int) crdnEmds);

        // 저장도 같은 모양이어야 한다 — 키 4종이 모두 있고(빈 배열도 키는 있다) 3개만 비어 있다.
        for (String key : List.of("crdnCompare", "shortageCompare", "emdCompare", "negEmdCompare")) {
            assertThat(jsonbHasKey(body.getExcnId(), key)).as("키 %s 가 없다 (빈 배열과 키 없음은 다르다)", key)
                    .isTrue();
        }
        assertThat(jsonbElementCount(body.getExcnId(), "shortageCompare")).isZero();
        assertThat(jsonbElementCount(body.getExcnId(), "crdnCompare")).isEqualTo(crdnEmds);
    }

    // ──────────────────────────────────────── 7. 익명은 401

    @Test
    @DisplayName("7. 🔴 익명 HTTP 는 생성·스냅샷 모두 401 이고 보고서가 한 행도 늘지 않는다")
    void anonymousIs401AndInsertsNothing() {
        // 바디는 유효하다 — 같은 바디를 세션으로 보내면 201 이다(= 401 의 원인이 바디가 아니다)
        ComplaintReportVO ok = createOk(request(bgngYmd, endYmd));
        long before = scalarLong("SELECT count(*) FROM parking.tb_rptp");

        assertThat(restTemplate.postForEntity(CREATE_URL, request(bgngYmd, endYmd), String.class)
                .getStatusCode().value()).as("익명 생성").isEqualTo(401);
        assertThat(restTemplate.getForEntity(SNAPSHOT_URL, String.class, ok.getRptpId())
                .getStatusCode().value()).as("익명 스냅샷 — 내 보고서라도 세션이 없으면 401").isEqualTo(401);

        assertThat(scalarLong("SELECT count(*) FROM parking.tb_rptp"))
                .as("401 이면 아무것도 저장하지 않는다").isEqualTo(before);
    }

    // ──────────────────────────────────────────────────────── 헬퍼 — 호출

    private ComplaintReportVO createOk(Map<String, Object> body) {
        ResponseEntity<ComplaintReportVO> response =
                restTemplate.exchange(CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, body),
                        ComplaintReportVO.class);
        assertThat(response.getStatusCode().value())
                .as("생성 응답은 201 이다 (요청: %s)", body).isEqualTo(201);
        ComplaintReportVO vo = response.getBody();
        assertThat(vo).isNotNull();
        assertThat(vo.getRptpId()).isNotNull();
        assertThat(vo.getExcnId()).isNotNull();
        return vo;
    }

    private ComplaintReportVO snapshotOk(Long rptpId) {
        ResponseEntity<ComplaintReportVO> response =
                restTemplate.exchange(SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session),
                        ComplaintReportVO.class, rptpId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /** 케이스 4 전용 — 같은 조건의 분석레이어 순위(enforcement). */
    private List<HotspotRankingZoneVO> enforcementRanking(String bgng, String end) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("view", "enforcement");
        params.put("startDt", dashed(bgng));
        params.put("endDt", dashed(end));
        ResponseEntity<HotspotRankingResultVO> response = restTemplate.exchange(
                RANKING_URL + "?view={view}&startDt={startDt}&endDt={endDt}",
                HttpMethod.GET, null, HotspotRankingResultVO.class, params);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getZones()).isNotNull();
        return response.getBody().getZones();
    }

    private static Map<String, Object> request(String bgng, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", bgng);
        body.put("endYmd", end);
        body.put("fileFmt", "PDF");
        return body;
    }

    private static String dashed(String ymd) {
        return LocalDate.parse(ymd, YMD).format(DASHED);
    }

    // ──────────────────────────────────────────────────────── 헬퍼 — 단언

    /**
     * 🔴 {@code BigDecimal} 은 {@code equals} 로 보지 않는다 — scale 이 다르면 값이 같아도 false 다.
     * 저장 jsonb 와 스냅샷 응답의 scale 이 다른 것이 정상이므로 수치 비교({@code compareTo == 0})를 쓴다.
     */
    private static void assertNumericallyEqual(BigDecimal actual, BigDecimal expected, String field) {
        if (expected == null) {
            assertThat(actual).as("%s 는 null 로 왕복해야 한다", field).isNull();
            return;
        }
        assertThat(actual).as("%s 가 null 로 사라졌다", field).isNotNull();
        assertThat(actual).as("%s 값이 왕복에서 달라졌다", field).isEqualByComparingTo(expected);
    }

    // ──────────────────────────────────────────────────────── 헬퍼 — 독립 SQL (기대값)

    /** 구간 안에 단속이 있는 읍면동 수 = {@code selectEnforcement} 의 모집단(HAVING 현재 구간 &gt; 0). */
    private long crdnEmdCount(String bgng, String end) {
        return scalarLong("""
                SELECT count(*) FROM (
                    SELECT emd_cd FROM parking.tb_crdn_dsctn
                     WHERE crdn_dt::date BETWEEN to_date(?, 'YYYYMMDD') AND to_date(?, 'YYYYMMDD')
                       AND emd_cd IS NOT NULL
                     GROUP BY emd_cd) t""", bgng, end);
    }

    /** 단속 최다 읍면동(동률이면 코드 오름차순) 과 그 건수. */
    private Map<String, Object> topCrdnEmd(String bgng, String end) {
        return jdbcTemplate.queryForMap("""
                SELECT emd_cd, count(*) AS cnt FROM parking.tb_crdn_dsctn
                 WHERE crdn_dt::date BETWEEN to_date(?, 'YYYYMMDD') AND to_date(?, 'YYYYMMDD')
                   AND emd_cd IS NOT NULL
                 GROUP BY emd_cd
                 ORDER BY count(*) DESC, emd_cd ASC
                 LIMIT 1""", bgng, end);
    }

    /**
     * 직전 <b>동일 길이</b> 구간의 단속 건수. 🔴 달력 월이 아니라 길이 기준이다 —
     * {@code prevStart = bgng - 길이}, {@code prevEnd = bgng - 1} (서비스와 같은 정의를 테스트가 다시 쓴다).
     */
    private long prevCrdnCnt(String emdCd, String bgng, String end) {
        LocalDate curStart = LocalDate.parse(bgng, YMD);
        LocalDate curEnd = LocalDate.parse(end, YMD);
        long len = curEnd.toEpochDay() - curStart.toEpochDay() + 1;
        return scalarLong("""
                SELECT count(*) FROM parking.tb_crdn_dsctn
                 WHERE emd_cd = ?
                   AND crdn_dt::date BETWEEN to_date(?, 'YYYYMMDD') AND to_date(?, 'YYYYMMDD')""",
                emdCd,
                curStart.minusDays(len).format(YMD),
                curStart.minusDays(1).format(YMD));
    }

    /** 증감률 규칙 — 직전 0건이면 분모 1 치환(그리고 inferred=true). */
    private static Integer expectedDeltaPct(long cur, long prev) {
        long diff = cur - prev;
        return prev == 0
                ? (int) Math.round(diff * 100 / 1.0)
                : (int) Math.round((double) diff / prev * 100);
    }

    /** 수요 부족 모집단 = {@code out_cvlcpt_sum > 0} 인 zone 수 (등급 필터 없음). */
    private long shortageZoneCount(String bgng, String end) {
        return scalarLong("""
                SELECT count(*) FROM parking.fn_jenks_shortage(
                         to_date(?, 'YYYYMMDD'), to_date(?, 'YYYYMMDD'),
                         NULL::text, NULL::text, NULL::text, NULL::text)
                 WHERE out_cvlcpt_sum > 0""", bgng, end);
    }

    /** PSDR 이 가장 낮은(= 가장 부족한) 읍면동. */
    private String topShortageEmd(String bgng, String end) {
        return jdbcTemplate.queryForObject("""
                SELECT out_emd_cd FROM parking.fn_jenks_shortage(
                         to_date(?, 'YYYYMMDD'), to_date(?, 'YYYYMMDD'),
                         NULL::text, NULL::text, NULL::text, NULL::text)
                 WHERE out_cvlcpt_sum > 0
                 ORDER BY out_psdr ASC, out_emd_cd ASC
                 LIMIT 1""", String.class, bgng, end);
    }

    /** 구간 안의 민원 행 수 ({@code ComplaintMapper} 와 같은 조건). */
    private long complaintRowCount(String bgng, String end) {
        return scalarLong("""
                SELECT count(*) FROM parking.tb_cvlcpt
                 WHERE ocrn_dt::date BETWEEN to_date(?, 'YYYYMMDD') AND to_date(?, 'YYYYMMDD')
                   AND cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL""", bgng, end);
    }

    // ──────────────────────────────────────────────────────── 헬퍼 — 저장된 jsonb

    private boolean jsonbHasKey(Long excnId, String key) {
        Boolean has = jdbcTemplate.queryForObject(
                "SELECT jsonb_exists(rslt_json, ?) FROM parking.tb_anls_rslt WHERE excn_id = ?",
                Boolean.class, key, excnId);
        return Boolean.TRUE.equals(has);
    }

    private long jsonbElementCount(Long excnId, String key) {
        return scalarLong(
                "SELECT COALESCE(jsonb_array_length(rslt_json->?), -1)"
                        + "  FROM parking.tb_anls_rslt WHERE excn_id = ?", key, excnId);
    }

    /** 배열 원소 중 특정 키를 가진 것의 수 — 0 이어야 경계 polygon 을 담지 않은 것이다. */
    private long jsonbElementsWithKey(Long excnId, String arrayKey, String elementKey) {
        return scalarLong(
                "SELECT count(*) FROM parking.tb_anls_rslt r,"
                        + "       jsonb_array_elements(r.rslt_json->?) e"
                        + " WHERE r.excn_id = ? AND jsonb_exists(e, ?)", arrayKey, excnId, elementKey);
    }

    private long scalarLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
