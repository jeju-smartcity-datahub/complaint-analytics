package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import jeju.datahub.analysis.InvestPrrtTestData.Admdst;
import jeju.datahub.analysis.InvestPrrtTestData.Sfty;
import jeju.datahub.analysis.InvestPrrtTestData.Src;
import jeju.datahub.analysis.InvestPrrtTestData.Utz;
import jeju.datahub.analysis.InvestPrrtTestData.Win;
import jeju.datahub.analysis.service.InvestPrrtLotBaseVO;
import jeju.datahub.analysis.service.impl.InvestPrrtMapper;
import jeju.datahub.analysis.service.impl.InvestPrrtQueryParam;

/**
 * 투자우선순위 지표 원값 매퍼 테스트 (TSK-4036 Step 4 검증).
 *
 * <p>로컬 PostgreSQL 을 그대로 쓴다. 사고 케이스(10)만 사고이력 행을 INSERT 하며, {@code @Transactional}
 * 롤백으로 DB 를 바꾸지 않는다.
 *
 * <h2>🔴 날짜·건수·주차장 이름을 박지 않는다</h2>
 * 조회 창은 DB 의 {@code max(ocrn_dt)} 에서 3개월을 거슬러 잡고(검증용 창 — 기본 기간과 별개), 기대값은 {@link InvestPrrtTestData} 의
 * <b>매퍼와 다른 경로의 SQL</b>로 다시 센다. 원천이 비면 실패가 아니라 <b>사유와 함께 건너뛴다</b>.
 *
 * <p>⚠️ 아래 숫자 리터럴({@code 100/300/500})은 실측값이 아니라 매퍼에 넣는 <b>입력</b>(배정 반경)이다.
 *
 * <h2>⚠️ 알려진 검증 공백</h2>
 * 독립 SQL 로 다시 세는 방식은 데이터가 그 조건으로 결과를 가를 때만 조건을 검증한다. 소멸 주차장
 * ({@code extsh_dt})·좌표 없는 사건·유형 99 민원(좌표가 없다)이 지금 데이터에서 결과를 가르지 못하면
 * 그 조건을 매퍼에서 지워도 초록불이다({@code ZoneRcmdtnMapperTest} 머리 주석과 같은 사정).
 */
@SpringBootTest
@Transactional
@DisplayName("TSK-4036 Step 4 — 투자우선순위 지표 원값 매퍼")
class InvestPrrtMapperTest {

    private static final Logger log = LoggerFactory.getLogger(InvestPrrtMapperTest.class);

    // --- 매퍼 입력 (실측값이 아니다) ---
    private static final BigDecimal RDS_100 = new BigDecimal("100");
    /** 기본 배정 반경(m) */
    private static final BigDecimal RDS_300 = new BigDecimal("300");
    private static final BigDecimal RDS_500 = new BigDecimal("500");

    /** 평균(numeric 나눗셈) 비교 허용 오차 — 스케일은 DB 가 정한다 */
    private static final BigDecimal EPS = new BigDecimal("0.000001");

    @Autowired
    private InvestPrrtMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private InvestPrrtTestData data;
    /** 최근 3개월 창(매퍼 동등성 검증용 — 기본 기간 12개월과 별개 · 이용률 집계 달은 {@link Win#bgngYm()} 규칙) — 주차 민원이 없으면 {@code null} */
    private Win win;
    /** 대상 주차장 ID(독립 SQL) */
    private List<Long> targetIds;

    @BeforeEach
    void resolveWindow() {
        data = new InvestPrrtTestData(jdbcTemplate);
        targetIds = data.targetIds();
        win = data.recentWin();
        assumeTrue(!targetIds.isEmpty(), "노상·공한지가 아닌 미소멸 주차장이 없다");
        assumeTrue(win != null, "좌표 있는 주차 민원(parking.tb_cvlcpt · 유형 01~05)이 없어 창을 잡을 수 없다");
    }

    private List<InvestPrrtLotBaseVO> lotBase(BigDecimal rdsM) {
        return mapper.selectLotBase(InvestPrrtTestData.queryParam(win, rdsM));
    }

    /** 주차장별 건수(0 은 뺀다) — 독립 SQL 의 결과와 같은 모양이다 */
    private static Map<Long, Long> byLot(List<InvestPrrtLotBaseVO> rows, Function<InvestPrrtLotBaseVO, Long> cnt) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (InvestPrrtLotBaseVO r : rows) {
            Long v = cnt.apply(r);
            if (v != null && v != 0L) {
                out.put(r.getPkltId(), v);
            }
        }
        return out;
    }

    private static long total(List<InvestPrrtLotBaseVO> rows, Function<InvestPrrtLotBaseVO, Long> cnt) {
        return rows.stream().mapToLong(cnt::apply).sum();
    }

    @Test
    @DisplayName("1. 대상 = 독립 SQL — 노상·공한지가 아닌 미소멸 주차장마다 한 행 · pklt_id 오름차순 · 반경과 무관")
    void targetsMatchIndependentSql() {
        List<InvestPrrtLotBaseVO> rows = lotBase(RDS_300);

        assertThat(rows).extracting(InvestPrrtLotBaseVO::getPkltId)
                .as("대상 주차장 — 한 곳 한 행, pklt_id 오름차순").containsExactlyElementsOf(targetIds);
        // 대상은 창·반경과 무관하다 — 배정이 0건이어도 행이 남는다
        assertThat(lotBase(RDS_100)).extracting(InvestPrrtLotBaseVO::getPkltId)
                .containsExactlyElementsOf(targetIds);
    }

    @Test
    @DisplayName("2. 필수 필드 NOT NULL · 🔴 sggNm·emdNm 은 tb_admdst 값이다")
    void requiredFieldsAndAdmdstNames() {
        List<InvestPrrtLotBaseVO> rows = lotBase(RDS_300);
        Map<Long, Admdst> admdst = data.admdstByLot();

        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getPkltId()).isNotNull();
            assertThat(r.getPkltNm()).as("%s 주차장명", r.getPkltId()).isNotBlank();
            assertThat(r.getLat()).as("tb_pblpklt.lat 은 NOT NULL 이라 비지 않는다").isNotNull();
            assertThat(r.getLot()).isNotNull();
            // COALESCE 로 0 을 채우므로 null 이 없다 — 계산기가 null 체크 없이 백분위를 낼 수 있는 근거
            assertThat(r.getCvlcptCnt()).as("%s 배정 민원", r.getPkltId()).isNotNull().isNotNegative();
            assertThat(r.getCrdnCnt()).as("%s 배정 단속", r.getPkltId()).isNotNull().isNotNegative();
            assertThat(r.getAcdntCnt()).as("%s 사고(TSK-4296 — 값 없음 = 0)", r.getPkltId()).isNotNull().isNotNegative();
            assertThat(r.getUtztnMmCnt()).as("%s 이용 달 수", r.getPkltId()).isNotNull().isNotNegative();

            Admdst a = admdst.get(r.getPkltId());
            assertThat(a).as("%s — 독립 SQL 의 행정구역 행", r.getPkltId()).isNotNull();
            assertThat(r.getEmdCd()).isEqualTo(a.emdCd());
            assertThat(r.getSggNm()).as("%s sggNm — tb_admdst.sgg_nm", r.getPkltId()).isEqualTo(a.sggNm());
            assertThat(r.getEmdNm()).as("%s emdNm — 🔴 tb_pblpklt.emd_nm(원천 표기)이 아니라 tb_admdst.emd_nm",
                    r.getPkltId()).isEqualTo(a.emdNm());
            if (r.getEmdCd() != null) {
                assertThat(r.getSggNm()).as("%s — emd_cd 가 있으면 시군구명이 있다", r.getPkltId()).isNotBlank();
                assertThat(r.getEmdNm()).isNotBlank();
            }
        });
    }

    @Test
    @DisplayName("3. 민원 배정 = 독립 SQL — 주차장별 건수 · 합 · 🔴 합 ≤ 창 안 좌표 보유 주차 민원 수(중복 없음)")
    void complaintAssignmentMatchesIndependentSql() {
        Map<Long, Long> exp = data.asgnByLot(Src.CV, win, RDS_300);
        assumeTrue(InvestPrrtTestData.sum(exp) > 0, "창 안에 대상 주차장 반경 안 민원이 한 건도 없다");
        List<InvestPrrtLotBaseVO> rows = lotBase(RDS_300);

        assertThat(byLot(rows, InvestPrrtLotBaseVO::getCvlcptCnt)).as("주차장별 배정 민원").isEqualTo(exp);
        assertThat(total(rows, InvestPrrtLotBaseVO::getCvlcptCnt)).as("배정 민원 합")
                .isEqualTo(InvestPrrtTestData.sum(exp))
                .as("🔴 최근접 배정이면 사건 수를 넘을 수 없다(반경 집계면 넘는다)")
                .isLessThanOrEqualTo(data.eventCount(Src.CV, win));
    }

    @Test
    @DisplayName("4. 단속 배정 = 독립 SQL — 주차장별 건수 · 합 · 합 ≤ 창 안 좌표 보유 단속 수")
    void crackdownAssignmentMatchesIndependentSql() {
        Map<Long, Long> exp = data.asgnByLot(Src.CRDN, win, RDS_300);
        assumeTrue(InvestPrrtTestData.sum(exp) > 0, "창 안에 대상 주차장 반경 안 단속이 한 건도 없다");
        List<InvestPrrtLotBaseVO> rows = lotBase(RDS_300);

        assertThat(byLot(rows, InvestPrrtLotBaseVO::getCrdnCnt)).as("주차장별 배정 단속").isEqualTo(exp);
        assertThat(total(rows, InvestPrrtLotBaseVO::getCrdnCnt)).as("배정 단속 합")
                .isEqualTo(InvestPrrtTestData.sum(exp))
                .isLessThanOrEqualTo(data.eventCount(Src.CRDN, win));
    }

    @Test
    @DisplayName("5. 반경 100/300/500 — 배정 합 = 반경별 독립 재계산 · 반경이 커지면 줄지 않는다 (미터 단위 · 사전 필터가 놓치지 않는다)")
    void radiusChangesAssignedTotal() {
        long prevCv = -1;
        long prevCrdn = -1;
        for (BigDecimal rds : List.of(RDS_100, RDS_300, RDS_500)) {
            List<InvestPrrtLotBaseVO> rows = lotBase(rds);
            long cv = total(rows, InvestPrrtLotBaseVO::getCvlcptCnt);
            long crdn = total(rows, InvestPrrtLotBaseVO::getCrdnCnt);
            // 🔴 독립 SQL 의 반경도 미터다 — 단위가 어긋나거나 도 단위 사전 필터가 좁으면 여기서 갈린다
            assertThat(byLot(rows, InvestPrrtLotBaseVO::getCvlcptCnt)).as("반경 %sm 주차장별 배정 민원", rds)
                    .isEqualTo(data.asgnByLot(Src.CV, win, rds));
            assertThat(byLot(rows, InvestPrrtLotBaseVO::getCrdnCnt)).as("반경 %sm 주차장별 배정 단속", rds)
                    .isEqualTo(data.asgnByLot(Src.CRDN, win, rds));
            assertThat(cv).as("반경 %sm — 민원 배정 합은 줄지 않는다", rds).isGreaterThanOrEqualTo(prevCv);
            assertThat(crdn).as("반경 %sm — 단속 배정 합은 줄지 않는다", rds).isGreaterThanOrEqualTo(prevCrdn);
            prevCv = cv;
            prevCrdn = crdn;
        }
    }

    @Test
    @DisplayName("6. 안전 = 독립 SQL — 종료일 이전 가장 최근 점검일의 가장 나쁜 결과 · 점검이 없으면 NULL")
    void safetyMatchesIndependentSql() {
        Map<Long, Sfty> exp = data.sftyByLot(win);
        assumeTrue(!exp.isEmpty(), "창 종료일 이전 안전점검(parking.tb_pklt_sfty_chck)이 한 건도 없다");

        assertThat(lotBase(RDS_300)).allSatisfy(r -> assertSafety(r, exp));
    }

    private static void assertSafety(InvestPrrtLotBaseVO r, Map<Long, Sfty> exp) {
        Sfty s = exp.get(r.getPkltId());
        assertThat(r.getSftyChckRsltCd()).as("%s 안전점검 결과", r.getPkltId()).isEqualTo(s == null ? null : s.rsltCd());
        assertThat(r.getSftyChckYmd()).as("%s 안전점검 일자", r.getPkltId()).isEqualTo(s == null ? null : s.chckYmd());
    }

    @Test
    @DisplayName("7. 이용 = 독립 SQL — 이용률 집계 달 중 값이 있는 달의 평균과 그 달 수 · 없으면 NULL·0")
    void usageMatchesIndependentSql() {
        Map<Long, Utz> exp = data.utzByLot(win);
        assumeTrue(!exp.isEmpty(), "이용률 집계 달(" + win.bgngYm() + "~" + win.endYm() + ")의 회전율 자료가 없다");

        assertThat(lotBase(RDS_300)).allSatisfy(r -> assertUsage(r, exp));
    }

    private static void assertUsage(InvestPrrtLotBaseVO r, Map<Long, Utz> exp) {
        Utz u = exp.getOrDefault(r.getPkltId(), Utz.NONE);
        assertThat(r.getUtztnMmCnt()).as("%s 이용 달 수", r.getPkltId()).isEqualTo(u.mmCnt());
        if (u.avg() == null) {
            assertThat(r.getDayAvgTrnvrRt()).as("%s — 자료가 없으면 0 이 아니라 null", r.getPkltId()).isNull();
        } else {
            assertThat(r.getDayAvgTrnvrRt()).as("%s 일평균 회전율", r.getPkltId()).isCloseTo(u.avg(), within(EPS));
        }
    }

    @Test
    @DisplayName("11. 이용패턴 없는 대상(TSK-4347 D1·D3) — 1곳 이상 대상에 남고 utztnMmCnt = 0 · dayAvgTrnvrRt = null")
    void targetsWithoutUsageStayWithZeroUsage() {
        List<Long> noUtz = data.targetIdsWithoutUtztn();
        assertThat(noUtz).as("이용패턴이 한 행도 없는 대상 — 유형 기준 대상이라 존재해야 한다(노외(공영) 등)").isNotEmpty();

        Map<Long, InvestPrrtLotBaseVO> byId = new LinkedHashMap<>();
        lotBase(RDS_300).forEach(r -> byId.put(r.getPkltId(), r));
        assertThat(byId.keySet()).as("이용패턴이 없어도 대상에서 빠지지 않는다").containsAll(noUtz);
        assertThat(noUtz).allSatisfy(id -> {
            InvestPrrtLotBaseVO r = byId.get(id);
            assertThat(r.getUtztnMmCnt()).as("%s 이용 달 수", id).isZero();
            assertThat(r.getDayAvgTrnvrRt()).as("%s 일평균 회전율 — 0 이 아니라 null", id).isNull();
        });
    }

    /**
     * 주차장 5곳의 4지표 원값을 한 행씩 맞춘다. 고르는 기준은 이름·ID 가 아니라 <b>값의 위치</b>다 —
     * 지표마다 값이 가장 큰 곳(민원 · 단속 · 회전율)과 안전 결과가 가장 나쁜 곳을 넣고, 나머지는
     * {@code pklt_id} 순 목록에서 고르게 띄워 채운다(0 만 대조하고 끝나지 않게 한다).
     */
    @Test
    @DisplayName("8. 주차장 5곳의 4지표 원값(안전 · 민원 · 이용 · 단속) = 독립 SQL")
    void fourIndicatorsOfFiveLots() {
        List<InvestPrrtLotBaseVO> rows = lotBase(RDS_300);
        Map<Long, Long> cv = data.asgnByLot(Src.CV, win, RDS_300);
        Map<Long, Long> crdn = data.asgnByLot(Src.CRDN, win, RDS_300);
        Map<Long, Sfty> sfty = data.sftyByLot(win);
        Map<Long, Utz> utz = data.utzByLot(win);

        Set<InvestPrrtLotBaseVO> picked = new LinkedHashSet<>();
        rows.stream().max(Comparator.comparing(InvestPrrtLotBaseVO::getCvlcptCnt)).ifPresent(picked::add);
        rows.stream().max(Comparator.comparing(InvestPrrtLotBaseVO::getCrdnCnt)).ifPresent(picked::add);
        rows.stream().filter(r -> r.getDayAvgTrnvrRt() != null)
                .max(Comparator.comparing(InvestPrrtLotBaseVO::getDayAvgTrnvrRt)).ifPresent(picked::add);
        rows.stream().filter(r -> r.getSftyChckRsltCd() != null)
                .max(Comparator.comparing(InvestPrrtLotBaseVO::getSftyChckRsltCd)).ifPresent(picked::add);
        int want = Math.min(5, rows.size());
        for (int k = 0; k < want && picked.size() < want; k++) {
            picked.add(rows.get(k * rows.size() / want));   // 고르게 띄운 자리
        }
        for (int i = 0; picked.size() < want; i++) {
            picked.add(rows.get(i));                        // 겹쳤으면 앞에서부터 채운다
        }

        assertThat(picked).hasSize(want);
        for (InvestPrrtLotBaseVO r : picked) {
            assertThat(r.getCvlcptCnt()).as("%s 배정 민원", r.getPkltId())
                    .isEqualTo(cv.getOrDefault(r.getPkltId(), 0L));
            assertThat(r.getCrdnCnt()).as("%s 배정 단속", r.getPkltId())
                    .isEqualTo(crdn.getOrDefault(r.getPkltId(), 0L));
            assertSafety(r, sfty);
            assertUsage(r, utz);
        }
    }

    /**
     * 사고 건수(TSK-4296) — 그 주차장에 <b>기록된</b> 창 안 사고 수다(공간 배정 없음).
     *
     * <p>🔴 로컬 DB 에는 사고 임의 자료(D4 시드 {@code SEED-%})가 있다 — 기대값을 0 이나 리터럴로 박지 않고
     * <b>삽입 전 그 주차장의 창 안 건수 + 삽입한 창 안 건수</b>로 잡는다. "사고 없는 주차장" 도 ID 로 박지
     * 않고 독립 SQL 로 창 안 사고가 0 인 대상을 고른다.
     *
     * <p>🔴 매퍼는 INSERT <b>뒤에 한 번만</b> 부른다 — {@code @Transactional} 안에서는 MyBatis 세션이 하나라,
     * 같은 파라미터로 먼저 불러 두면 1차 캐시가 INSERT(JdbcTemplate — MyBatis 가 모르는 쓰기) 전 결과를 돌려준다.
     */
    @Test
    @DisplayName("10. 사고 = 독립 SQL — 삽입 행이 그 주차장에 집계 · 🔴 창 밖(반열림 경계 포함) 제외 · 사고 없는 주차장 0")
    void accidentCountPerLot() {
        Map<Long, Long> before = acdntByLot(win);
        Long zeroLot = targetIds.stream().filter(id -> !before.containsKey(id)).findFirst().orElse(null);
        assumeTrue(zeroLot != null, "창 안 사고가 0 인 대상 주차장이 없다");
        // 삽입 대상 — 이미 사고가 있는 곳이 있으면 그곳(누적이 덮어쓰기가 아님을 본다), 없으면 zeroLot 이 아닌 첫 곳
        Long hitLot = targetIds.stream().filter(before::containsKey).findFirst()
                .orElse(targetIds.stream().filter(id -> !id.equals(zeroLot)).findFirst().orElse(null));
        assumeTrue(hitLot != null, "사고를 넣을 두 번째 대상 주차장이 없다");

        // 창 안 2건(시작 00:00 · 종료일 23:59:59) + 창 밖 2건(시작 1초 전 · 🔴 반열림 상한 정각)
        insertAcdnt("T4296-IN-1", hitLot, win.from());
        insertAcdnt("T4296-IN-2", hitLot, win.toExcl().minusSeconds(1));
        insertAcdnt("T4296-OUT-1", hitLot, win.from().minusSeconds(1));
        insertAcdnt("T4296-OUT-2", hitLot, win.toExcl());

        List<InvestPrrtLotBaseVO> rows = lotBase(RDS_300);   // 🔴 INSERT 뒤 한 번만
        Map<Long, InvestPrrtLotBaseVO> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.getPkltId(), r));

        assertThat(byId.get(hitLot).getAcdntCnt()).as("%s — 삽입 전 %s건 + 창 안 2건", hitLot, before.getOrDefault(hitLot, 0L))
                .isEqualTo(before.getOrDefault(hitLot, 0L) + 2);
        assertThat(byId.get(zeroLot).getAcdntCnt()).as("%s — 사고 없는 주차장은 null 이 아니라 0", zeroLot).isZero();
        // 삽입 뒤 전 대상이 독립 SQL 과 같다(다른 주차장으로 새지 않는다)
        assertThat(byLot(rows, InvestPrrtLotBaseVO::getAcdntCnt)).as("주차장별 사고").isEqualTo(acdntByLot(win));
    }

    /** 창 안 사고 건수 — 대상 주차장만, 0 은 뺀다(독립 SQL · 매퍼 XML 을 복사하지 않는다) */
    private Map<Long, Long> acdntByLot(Win w) {
        Set<Long> targets = new LinkedHashSet<>(targetIds);
        Map<Long, Long> out = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT pklt_id, count(*) AS cnt FROM parking.tb_pklt_acdnt_hstry"
                        + " WHERE acdnt_dt >= ? AND acdnt_dt < ? GROUP BY pklt_id ORDER BY pklt_id",
                rs -> {
                    long id = rs.getLong("pklt_id");
                    if (targets.contains(id)) {
                        out.put(id, rs.getLong("cnt"));
                    }
                },
                Timestamp.valueOf(w.from()), Timestamp.valueOf(w.toExcl()));
        return out;
    }

    private void insertAcdnt(String acdntId, long pkltId, LocalDateTime acdntDt) {
        jdbcTemplate.update("INSERT INTO parking.tb_pklt_acdnt_hstry (acdnt_id, pklt_id, acdnt_dt, svrt_cd, acdnt_type)"
                + " VALUES (?, ?, ?, 'MINOR', '테스트')", acdntId, pkltId, Timestamp.valueOf(acdntDt));
    }

    /**
     * 실행 시간 — 환경 의존이라 <b>단언하지 않고 로그로만 남긴다</b>(계획서 기준은 3초 이내).
     *
     * <p>🔴 측정 전에 한 번 호출해 둔다(JIT·커넥션 워밍). 넘으면 사건 쪽 {@code geom} GiST 를
     * 타는지부터 본다 — 못 타면 사건 전량을 대상 주차장 수만큼 훑는다.
     */
    @Test
    @DisplayName("9. 소요 시간 — selectLotBase 1회 호출(ms)을 로그로 남긴다 (단언 없음)")
    void logsElapsedTime() {
        InvestPrrtQueryParam p = InvestPrrtTestData.queryParam(win, RDS_300);
        // 🔴 워밍은 다른 반경으로 한다 — 같은 파라미터로 부르면 MyBatis 1차 캐시(@Transactional 안에서는
        //    세션이 하나다)가 DB 를 거치지 않고 답해 0ms 가 찍힌다
        mapper.selectLotBase(InvestPrrtTestData.queryParam(win, RDS_100));

        long nano = System.nanoTime();
        List<InvestPrrtLotBaseVO> rows = mapper.selectLotBase(p);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - nano);

        log.info("[InvestPrrtMapperTest] selectLotBase 1회 = {}ms (창 {} ~ {} · 반경 {}m · 대상 {}곳 · 창 안 민원 {}건 · 단속 {}건)",
                elapsed.toMillis(), win.bgng(), win.end(), RDS_300, rows.size(),
                data.eventCount(Src.CV, win), data.eventCount(Src.CRDN, win));
        assertThat(rows).isNotEmpty();
    }
}
