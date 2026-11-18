package jeju.datahub.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.analysis.service.PrdMonths;
import jeju.datahub.analysis.service.ZoneRcmdtnLotVO;
import jeju.datahub.analysis.service.impl.ZoneRcmdtnQueryParam;

/**
 * 구역추천(요금제) 테스트 3종({@code ZoneRcmdtnMapperTest}·{@code ZoneRcmdtnCalculatorTest}·
 * {@code ZoneRcmdtnApiTest})이 함께 쓰는 <b>데이터 독립 기대값 도구</b>.
 *
 * <h2>🔴 날짜·읍면동·건수를 박지 않는다 — 로컬 데이터가 날짜째 옮겨진다</h2>
 * 민원({@code tb_cvlcpt.ocrn_dt})·단속({@code tb_crdn_dsctn.crdn_dt}) 적재분은 개발 중 날짜가 통째로
 * 옮겨지고 행이 바뀐다(2026-10-13 하루에만 단속 날짜가 옮겨지고 건수가 달라져 "실측값" 단언 5건이
 * 코드 결함 없이 깨졌다). 그래서 세 테스트는 이 클래스로
 * <ol>
 *   <li><b>조회 구간</b>을 DB 의 {@code min/max} 에서 읽고({@link #cvRange()}·{@link #crdnRange()}),</li>
 *   <li><b>대상 읍면동</b>을 조건으로 골라 쓰며({@link #busyEmd} — 후보 좌표가 충분한 동 중 민원 최다),</li>
 *   <li><b>기대값</b>을 매퍼와 <b>다른 경로의 SQL</b>로 다시 세거나({@link #asgnByLoc} 등) 산식을
 *       입력값에서 다시 계산한다({@link #incrsRt}).</li>
 * </ol>
 *
 * <h2>🔴 매퍼 XML 을 복사하지 않는다</h2>
 * 같은 SQL 을 복사하면 같은 버그를 복사한다. 여기 SQL 은 일부러 다른 모양이다 —
 * 최근접 배정은 {@code CROSS JOIN LATERAL … LIMIT 1} 이 아니라 <b>전 쌍을 만든 뒤
 * {@code DISTINCT ON}</b> 으로 고르고, 좌표는 생성컬럼 {@code geom} 이 아니라 {@code lat}/{@code lot}
 * 에서 직접 만든다. 단 <b>거리 정의는 매퍼와 같아야</b> 값이 맞는다 — 최근접은 구면 거리
 * ({@code geography <->} = {@code ST_Distance(…, false)}), 반경 판정은 {@code ST_DWithin}(회전타원체)다.
 *
 * <p>⚠️ 주차장 좌표가 <b>완전히 같은</b> 후보가 실재한다(같은 필지의 두 행). 그 둘 중 어느 쪽에
 * 배정되는지는 매퍼에서도 정해져 있지 않으므로, 주차장별 대조는 <b>좌표 단위로 묶어</b> 한다
 * ({@link #locKey}).
 *
 * <p>🔴 <b>독립 SQL 이 맞아도 검증되지 않는 조건이 있다</b> — 지금 데이터가 그 조건으로 결과를 가르지
 * 못하는 것들이다(민영·소멸·유형 99·좌표 없음). 목록과 근거는 {@code ZoneRcmdtnMapperTest} 클래스
 * 머리 주석 "🔴 알려진 검증 공백", 데이터가 생겼는지 찾는 탐침은 {@link #nearCandidates} 이하다.
 *
 * <p>Spring 빈이 아니다 — 각 테스트가 {@code new ZoneRcmdtnTestData(jdbcTemplate)} 로 만든다
 * (테스트 컨텍스트 구성을 건드리지 않는다). 전부 {@code SELECT} 다.
 */
final class ZoneRcmdtnTestData {

    static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 조회 기간 상한(개월) — 컨트롤러의 {@code MAX_PRD_MONTHS} 와 같은 수다(API 가 400 으로 끊는다) */
    static final int MAX_PRD_MONTHS = 36;

    /** 증가율 블록 최소 일수 — 계산기의 {@code MIN_TREND_DAYS}(패키지 비공개 상수)를 다시 적은 것 */
    static final int MIN_TREND_DAYS = 7;

    static final String WARN_TREND_WINDOW_SHORT = "TREND_WINDOW_SHORT";
    static final String WARN_TREND_BFR_FALLBACK = "TREND_BFR_FALLBACK";

    /** 후보 유형 — 정책 정의다(원천 문자열이고 코드그룹이 없다). 데이터 실측값이 아니다 */
    private static final String NARROW_TYPES = "'공영','노외(공영)','복층화'";
    private static final String WIDE_TYPES = NARROW_TYPES + ",'노상','노상(23년 조사)','공한지'";

    private final JdbcTemplate jdbc;

    ZoneRcmdtnTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =====================================================================================
    // 조회 창
    // =====================================================================================

    /**
     * 양끝 <b>포함</b> 날짜 창. 서비스의 파라미터 파생(반열림 상한 · 개월수 · 추세 블록)을
     * <b>입력에서 다시 계산</b>한다 — 서비스 코드를 부르지 않는다.
     */
    record Win(LocalDate bgng, LocalDate end) {

        LocalDateTime from() {
            return bgng.atStartOfDay();
        }

        /** 반열림 상한 = 종료일 다음 날 00:00 */
        LocalDateTime toExcl() {
            return end.plusDays(1).atStartOfDay();
        }

        String bgngYmd() {
            return bgng.format(YMD);
        }

        String endYmd() {
            return end.format(YMD);
        }

        /** 월평균 분모 — 달별 조회 비율 합(TSK-4284 · 서비스와 같은 {@link PrdMonths#of}) */
        BigDecimal months() {
            return PrdMonths.of(bgng, end);
        }

        /** 걸친 달 수(월초 기준 차 + 1) — 🔴 기간 상한(36개월) 판정 전용이다. 월평균 분모가 아니다(D5) */
        int spanMonths() {
            return (int) ChronoUnit.MONTHS.between(bgng.withDayOfMonth(1), end.withDayOfMonth(1)) + 1;
        }

        /** 총 일수 D(양끝 포함) */
        int days() {
            return (int) ChronoUnit.DAYS.between(bgng, end) + 1;
        }

        /** 반 분할 블록 일수 = D ÷ 2 (홀수면 가운데 1일이 버려진다) */
        int half() {
            return days() / 2;
        }

        /** 블록이 7일 이상인가(= D ≥ 14) — 아니면 증가율을 내지 않는다 */
        boolean trendOk() {
            return half() >= MIN_TREND_DAYS;
        }

        /** 1차 '이전' 블록 = 직전 같은 길이 */
        Win bfr() {
            return new Win(bgng.minusDays(days()), bgng.minusDays(1));
        }

        /** 폴백 '이전' 블록 = 처음 D/2 일 */
        Win splitBfr() {
            return new Win(bgng, bgng.plusDays(half() - 1L));
        }

        /** 폴백 '최근' 블록 = 마지막 D/2 일 */
        Win splitRcnt() {
            return new Win(end.minusDays(half() - 1L), end);
        }

        /** 이 창이 걸친 연월({@code YYYY-MM}) 오름차순 — 양 끝은 부분 달이어도 한 칸이다 */
        List<String> yms() {
            List<String> out = new ArrayList<>();
            for (YearMonth ym = YearMonth.from(bgng); !ym.isAfter(YearMonth.from(end)); ym = ym.plusMonths(1)) {
                out.add(ym.toString());
            }
            return out;
        }

        /** 이 창과 한 달의 교집합(없으면 {@code null}) — 월별 추이 칸의 독립 재계산용 */
        Win within(String ym) {
            YearMonth m = YearMonth.parse(ym);
            LocalDate b = bgng.isAfter(m.atDay(1)) ? bgng : m.atDay(1);
            LocalDate e = end.isBefore(m.atEndOfMonth()) ? end : m.atEndOfMonth();
            return e.isBefore(b) ? null : new Win(b, e);
        }
    }

    /** 자료 시작일에서 출발해 상한(36개월) 안으로 자른 창 — 🔴 시작이 자료 시작일이라 1차 '이전' 이 반드시 0건이다 */
    static Win head(Win range) {
        LocalDate cap = range.bgng().withDayOfMonth(1).plusMonths(MAX_PRD_MONTHS).minusDays(1);
        return new Win(range.bgng(), range.end().isBefore(cap) ? range.end() : cap);
    }

    /**
     * 자료 종료일에서 거꾸로 {@code days} 일인 창 — 1차 '이전' 블록이 보유 구간 <b>안</b>에 들게 하려면
     * {@code days ≤ 보유 일수 ÷ 2} 로 부른다.
     */
    static Win tail(Win range, int days) {
        return new Win(range.end().minusDays(days - 1L), range.end());
    }

    /** 읍면동 코드 전부(행정동 마스터) */
    List<String> emdCds() {
        return jdbc.queryForList("SELECT emd_cd FROM parking.tb_admdst ORDER BY emd_cd", String.class);
    }

    /** 주차장 목록을 좌표 단위로 묶은 건수(0 인 좌표는 뺀다) — {@link #asgnByLoc} 와 같은 모양이다 */
    static Map<String, Long> byLoc(List<ZoneRcmdtnLotVO> lots,
                                   java.util.function.Function<ZoneRcmdtnLotVO, Long> cnt) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (ZoneRcmdtnLotVO l : lots) {
            Long v = cnt.apply(l);
            if (v != null && v != 0L) {
                out.merge(locKey(l), v, Long::sum);
            }
        }
        return out;
    }

    /**
     * 매퍼 정렬 계약 — 배정 민원 내림차순 → 면수 내림차순 → {@code pklt_id} 오름차순
     * ({@code selectLotRanking} 의 {@code ORDER BY} · D9 — 결정적 입력 순서). 2·3차 키가 없으면 동순위 구간의
     * 순서가 호출마다 바뀐다.
     *
     * <p>🔁 TSK-4282 — <b>응답 순위의 계약이 아니다.</b> 응답은 계산기가 추세 반영 검토지수 내림차순으로 다시
     * 정렬하고, 이 순서는 그 동점 구간의 2차 키로만 남는다.
     */
    static boolean inRankOrder(ZoneRcmdtnLotVO a, ZoneRcmdtnLotVO b) {
        int c = Long.compare(b.getAsgnCvlcptCnt(), a.getAsgnCvlcptCnt());
        if (c == 0) {
            c = Integer.compare(b.getPkltCpcty(), a.getPkltCpcty());
        }
        if (c == 0) {
            c = Long.compare(a.getPkltId(), b.getPkltId());
        }
        return c < 0;
    }

    /** 민원 보유 구간 — 매퍼와 같은 대상(유형 99 제외 · {@code emd_cd} 있음). 비어 있으면 {@code null} */
    Win cvRange() {
        return range("SELECT min(ocrn_dt), max(ocrn_dt) FROM parking.tb_cvlcpt"
                + " WHERE cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL");
    }

    /** 단속 보유 구간({@code emd_cd} 있음). 비어 있으면 {@code null} */
    Win crdnRange() {
        return range("SELECT min(crdn_dt), max(crdn_dt) FROM parking.tb_crdn_dsctn WHERE emd_cd IS NOT NULL");
    }

    private Win range(String sql) {
        return jdbc.queryForObject(sql, (rs, n) -> {
            Timestamp min = rs.getTimestamp(1);
            Timestamp max = rs.getTimestamp(2);
            return (min == null || max == null) ? null
                    : new Win(min.toLocalDateTime().toLocalDate(), max.toLocalDateTime().toLocalDate());
        });
    }

    // =====================================================================================
    // 독립 집계
    // =====================================================================================

    int admdstCount() {
        return jdbc.queryForObject("SELECT count(*) FROM parking.tb_admdst", Integer.class);
    }

    Map<String, Object> admdst(String emdCd) {
        return jdbc.queryForMap("SELECT emd_nm, sgg_nm FROM parking.tb_admdst WHERE emd_cd = ?", emdCd);
    }

    /** 읍면동별 민원 건수(유형 99 제외) */
    Map<String, Long> cvCountByEmd(Win w) {
        return countByEmd("SELECT emd_cd, count(*) FROM parking.tb_cvlcpt"
                + " WHERE cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL AND ocrn_dt >= ? AND ocrn_dt < ?"
                + " GROUP BY emd_cd", w);
    }

    /** 읍면동별 단속 건수 */
    Map<String, Long> crdnCountByEmd(Win w) {
        return countByEmd("SELECT emd_cd, count(*) FROM parking.tb_crdn_dsctn"
                + " WHERE emd_cd IS NOT NULL AND crdn_dt >= ? AND crdn_dt < ? GROUP BY emd_cd", w);
    }

    private Map<String, Long> countByEmd(String sql, Win w) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        }, Timestamp.valueOf(w.from()), Timestamp.valueOf(w.toExcl()));
        return out;
    }

    /** 좌표 보유 민원 수 — 최근접 배정 합의 상한이다 */
    long cvCoordCount(String emdCd, Win w) {
        return jdbc.queryForObject("SELECT count(*) FROM parking.tb_cvlcpt"
                + " WHERE emd_cd = ? AND cvlcpt_type_cd <> '99' AND lat IS NOT NULL"
                + " AND ocrn_dt >= ? AND ocrn_dt < ?", Long.class,
                emdCd, Timestamp.valueOf(w.from()), Timestamp.valueOf(w.toExcl()));
    }

    /** 후보 주차장(유료화 전환 대상) 조건 — 무료 · 미소멸 · 민영 아님 · 면수 하한 · 유형 범위 */
    private static String cand(boolean wide) {
        return "(prk_crg_yn IS NULL OR prk_crg_yn = 'N') AND extsh_dt IS NULL"
                + " AND (ownshp_type_nm IS NULL OR ownshp_type_nm <> '민영')"
                + " AND pklt_cpcty >= ? AND pklt_type_nm IN (" + (wide ? WIDE_TYPES : NARROW_TYPES) + ")";
    }

    /** 후보 집계 1건 — 개소 수와 면수 합 */
    record Cand(long cnt, long cpcty) {
        static final Cand NONE = new Cand(0, 0);
    }

    /** 읍면동별 후보 개소·면수(후보가 없는 동은 키가 없다) */
    Map<String, Cand> candByEmd(BigDecimal minCpcty, boolean wide) {
        Map<String, Cand> out = new LinkedHashMap<>();
        jdbc.query("SELECT emd_cd, count(*), sum(pklt_cpcty) FROM parking.tb_pblpklt"
                + " WHERE emd_cd IS NOT NULL AND " + cand(wide) + " GROUP BY emd_cd", rs -> {
            out.put(rs.getString(1), new Cand(rs.getLong(2), rs.getLong(3)));
        }, minCpcty);
        return out;
    }

    Cand cand(String emdCd, BigDecimal minCpcty, boolean wide) {
        return candByEmd(minCpcty, wide).getOrDefault(emdCd, Cand.NONE);
    }

    /** 읍면동별 기존 유료 면수(미소멸) */
    Map<String, Long> paidCpctyByEmd() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT emd_cd, sum(pklt_cpcty) FROM parking.tb_pblpklt"
                + " WHERE emd_cd IS NOT NULL AND prk_crg_yn = 'Y' AND extsh_dt IS NULL GROUP BY emd_cd", rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }

    /** 전 주차장 중 최대 면수 — "어떤 주차장도 후보가 못 되는 하한" 을 만들 때 쓴다 */
    long maxPkltCpcty() {
        Long v = jdbc.queryForObject("SELECT coalesce(max(pklt_cpcty), 0) FROM parking.tb_pblpklt", Long.class);
        return v == null ? 0 : v;
    }

    // =====================================================================================
    // 검증 공백 탐침 — "그 조건이 결과를 가르는 데이터" 가 생겼는지 찾는다
    // (공백 목록과 근거는 ZoneRcmdtnMapperTest 클래스 머리 주석 "🔴 알려진 검증 공백")
    // =====================================================================================

    /** 후보 조건 가운데 <b>하나만</b> 어긴 주차장 — 그 조건이 없으면 후보가 됐을 주차장이다 */
    enum Near {
        /** 민영 — 무료 · 미소멸 · 후보 유형인데 소유가 민영 */
        PRIVATE("extsh_dt IS NULL AND ownshp_type_nm = '민영'"),
        /** 소멸 — 무료 · 민영 아님 · 후보 유형인데 소멸일시가 있다 */
        EXTINCT("extsh_dt IS NOT NULL AND (ownshp_type_nm IS NULL OR ownshp_type_nm <> '민영')");

        private final String cond;

        Near(String cond) {
            this.cond = cond;
        }
    }

    /**
     * {@link Near} 조건 하나만 어긴 주차장(읍면동 → {@code pklt_id} 목록). 유형은 넓은 범위(WIDE),
     * 면수 하한은 0 으로 본다 — 호출부도 매퍼를 {@code WIDE}·하한 0 으로 불러야 "그 조건만" 이 남는다.
     */
    Map<String, List<Long>> nearCandidates(Near which) {
        Map<String, List<Long>> out = new LinkedHashMap<>();
        jdbc.query("SELECT emd_cd, pklt_id FROM parking.tb_pblpklt"
                + " WHERE emd_cd IS NOT NULL AND (prk_crg_yn IS NULL OR prk_crg_yn = 'N')"
                + " AND pklt_cpcty >= 0 AND pklt_type_nm IN (" + WIDE_TYPES + ") AND " + which.cond
                + " ORDER BY emd_cd, pklt_id", rs -> {
            out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getLong(2));
        });
        return out;
    }

    /** 창 안에 {@code emd_cd} 를 단 <b>유형 99</b> 민원이 가장 많은 읍면동. 없으면 {@code null} */
    String emdWithType99(Win w) {
        return firstEmd("SELECT emd_cd FROM parking.tb_cvlcpt"
                + " WHERE cvlcpt_type_cd = '99' AND emd_cd IS NOT NULL AND ocrn_dt >= ? AND ocrn_dt < ?"
                + " GROUP BY emd_cd ORDER BY count(*) DESC, emd_cd LIMIT 1", w);
    }

    /** 창 안에 <b>좌표 없는</b> 주차 민원(유형 99 제외)이 가장 많은 읍면동. 없으면 {@code null} */
    String emdWithCvNoCoord(Win w) {
        return firstEmd("SELECT emd_cd FROM parking.tb_cvlcpt"
                + " WHERE cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL AND lat IS NULL"
                + " AND ocrn_dt >= ? AND ocrn_dt < ? GROUP BY emd_cd ORDER BY count(*) DESC, emd_cd LIMIT 1", w);
    }

    /** 창 안에 <b>좌표 없는</b> 단속이 가장 많은 읍면동. 없으면 {@code null} */
    String emdWithCrdnNoCoord(Win w) {
        return firstEmd("SELECT emd_cd FROM parking.tb_crdn_dsctn"
                + " WHERE emd_cd IS NOT NULL AND lat IS NULL AND crdn_dt >= ? AND crdn_dt < ?"
                + " GROUP BY emd_cd ORDER BY count(*) DESC, emd_cd LIMIT 1", w);
    }

    private String firstEmd(String sql, Win w) {
        List<String> emds = jdbc.queryForList(sql, String.class,
                Timestamp.valueOf(w.from()), Timestamp.valueOf(w.toExcl()));
        return emds.isEmpty() ? null : emds.get(0);
    }

    /** 한 읍면동의 창 안 민원 수 — 🔴 <b>유형 99 포함</b>(매퍼가 세면 안 되는 수와의 대비용) */
    long cvCountAllTypes(String emdCd, Win w) {
        return jdbc.queryForObject("SELECT count(*) FROM parking.tb_cvlcpt"
                + " WHERE emd_cd = ? AND ocrn_dt >= ? AND ocrn_dt < ?", Long.class,
                emdCd, Timestamp.valueOf(w.from()), Timestamp.valueOf(w.toExcl()));
    }

    /** 한 읍면동의 좌표 보유 단속 수 — 단속 최근접 배정 합의 상한이다 */
    long crdnCoordCount(String emdCd, Win w) {
        return jdbc.queryForObject("SELECT count(*) FROM parking.tb_crdn_dsctn"
                + " WHERE emd_cd = ? AND lat IS NOT NULL AND crdn_dt >= ? AND crdn_dt < ?", Long.class,
                emdCd, Timestamp.valueOf(w.from()), Timestamp.valueOf(w.toExcl()));
    }

    /** 최근접 배정의 대상 — 민원 또는 단속 */
    enum Src {
        CV("SELECT cvlcpt_id::text AS id, lat, lot FROM parking.tb_cvlcpt"
                + " WHERE emd_cd = ? AND cvlcpt_type_cd <> '99' AND lat IS NOT NULL"
                + " AND ocrn_dt >= ? AND ocrn_dt < ?"),
        CRDN("SELECT crdn_id::text AS id, lat, lot FROM parking.tb_crdn_dsctn"
                + " WHERE emd_cd = ? AND lat IS NOT NULL AND crdn_dt >= ? AND crdn_dt < ?");

        private final String sql;

        Src(String sql) {
            this.sql = sql;
        }
    }

    /**
     * 🔴 최근접 배정의 독립 재계산 — <b>후보 좌표별</b> 배정 건수({@link #locKey} → 건수).
     *
     * <p>건마다 전 후보 좌표와의 쌍을 만들고, 구면 거리가 가장 짧은 한 곳을 {@code DISTINCT ON} 으로
     * 고른 뒤, 그곳이 반경 안일 때만 센다. 배정이 0건인 좌표는 키가 없다.
     */
    Map<String, Long> asgnByLoc(Src src, String emdCd, Win w,
                                BigDecimal minCpcty, boolean wide, BigDecimal rng) {
        String sql = "WITH loc AS (SELECT DISTINCT lat, lot FROM parking.tb_pblpklt"
                + "                 WHERE emd_cd = ? AND " + cand(wide) + "),"
                + " ev AS (" + src.sql + "),"
                + " pair AS (SELECT ev.id, loc.lat, loc.lot,"
                + "   ST_Distance(ST_SetSRID(ST_MakePoint(ev.lot::float8, ev.lat::float8), 4326)::geography,"
                + "               ST_SetSRID(ST_MakePoint(loc.lot::float8, loc.lat::float8), 4326)::geography,"
                + "               false) AS sph,"
                + "   ST_DWithin(ST_SetSRID(ST_MakePoint(ev.lot::float8, ev.lat::float8), 4326)::geography,"
                + "              ST_SetSRID(ST_MakePoint(loc.lot::float8, loc.lat::float8), 4326)::geography,"
                + "              ?::float8) AS near"
                + "   FROM ev CROSS JOIN loc),"
                + " nearest AS (SELECT DISTINCT ON (id) id, lat, lot, near FROM pair ORDER BY id, sph)"
                + " SELECT lat, lot, count(*) FROM nearest WHERE near GROUP BY lat, lot";
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            out.put(locKey(rs.getBigDecimal(1), rs.getBigDecimal(2)), rs.getLong(3));
        }, emdCd, minCpcty, emdCd, Timestamp.valueOf(w.from()), Timestamp.valueOf(w.toExcl()), rng);
        return out;
    }

    /** 위 배정의 합 — "이 동의 후보 반경 안에 든 건수" */
    long asgnTotal(Src src, String emdCd, Win w, BigDecimal minCpcty, boolean wide, BigDecimal rng) {
        return sum(asgnByLoc(src, emdCd, w, minCpcty, wide, rng));
    }

    static long sum(Map<String, Long> m) {
        return m.values().stream().mapToLong(Long::longValue).sum();
    }

    /** 좌표 키 — 꼬리 0 을 걷어 스케일 차이를 없앤다 */
    static String locKey(BigDecimal lat, BigDecimal lot) {
        return lat.stripTrailingZeros().toPlainString() + "|" + lot.stripTrailingZeros().toPlainString();
    }

    static String locKey(ZoneRcmdtnLotVO l) {
        return locKey(l.getLat(), l.getLot());
    }

    /**
     * 순위 검증 대상이 갖추길 바라는 후보 좌표 수. 🔴 실측값이 아니라 <b>검증이 성립하기 위한 하한</b>이다 —
     * 순위·동순위(2·3차 키)·누적 면수·필요 면수 도달 경계·읍면동 경계를 보려면 이웃한 쌍이 여럿 있어야
     * 한다(5곳 = 이웃 쌍 4개). 2곳이면 쌍이 하나뿐이라 "정렬돼 있다" 가 한 번의 비교로 끝나고,
     * 옆 동 주차장으로 배정이 새는 결함도 거의 드러나지 않는다.
     */
    static final int RANK_LOCS = 5;

    /**
     * 조건으로 고른 <b>순위 검증 대상</b> 읍면동. 없으면 {@code null}(호출부가 사유와 함께 건너뛴다).
     *
     * <h3>선택 규칙</h3>
     * 후보 좌표가 {@code minLocs} 곳 이상이고 창 안에 좌표 보유 민원이 있는 동 가운데
     * <ol>
     *   <li>후보 좌표가 {@link #RANK_LOCS} 곳 이상인 동을 먼저 — 그런 동이 여럿이면 <b>민원이 가장 많은 동</b>
     *       (배정 건수가 0 뿐이면 순위·배정 대조가 0 = 0 으로 끝난다),</li>
     *   <li>그런 동이 없으면 <b>후보 좌표가 가장 많은 동</b>(있는 것 중 최다),</li>
     *   <li>동률은 민원 많은 순 → 좌표 많은 순 → 코드 오름차순(결정적이어야 실행마다 대상이 같다).</li>
     * </ol>
     * 한 줄로는 {@code ORDER BY LEAST(좌표 수, RANK_LOCS) DESC, 민원 DESC, 좌표 수 DESC, emd_cd} 다.
     *
     * <h3>🔴 왜 "민원 최다" 만으로 고르지 않는가</h3>
     * 종전 규칙(좌표 2곳 이상인 동 중 민원 최다)은 <b>후보가 2개소뿐인 동</b>을 골랐다 — 민원이 많은 도심
     * 동일수록 무료 공영 후보가 적다. 그 결과 NARROW 실경로의 순위·누적 면수·도달 여부·읍면동 경계
     * 검증이 2행 위에서만 돌았고, 변이 실험에서 "주차장 쪽 읍면동 경계 조건 제거" 를 거의 잡지 못했다.
     * 민원 수는 2차 키로 내렸다.
     */
    String busyEmd(Win w, BigDecimal minCpcty, boolean wide, int minLocs) {
        List<String> emds = jdbc.queryForList(
                "SELECT p.emd_cd FROM (SELECT emd_cd, count(DISTINCT (lat, lot)) AS locs FROM parking.tb_pblpklt"
                + "                     WHERE emd_cd IS NOT NULL AND " + cand(wide) + " GROUP BY emd_cd) p"
                + " JOIN (SELECT emd_cd, count(*) AS cv FROM parking.tb_cvlcpt"
                + "        WHERE cvlcpt_type_cd <> '99' AND lat IS NOT NULL AND ocrn_dt >= ? AND ocrn_dt < ?"
                + "        GROUP BY emd_cd) c ON c.emd_cd = p.emd_cd"
                + " WHERE p.locs >= ?"
                + " ORDER BY LEAST(p.locs, ?) DESC, c.cv DESC, p.locs DESC, p.emd_cd LIMIT 1",
                String.class, minCpcty, Timestamp.valueOf(w.from()), Timestamp.valueOf(w.toExcl()), minLocs,
                RANK_LOCS);
        return emds.isEmpty() ? null : emds.get(0);
    }

    /** 분석변수의 <b>적용값</b> — {@code use_yn='Y'} 면 {@code stng_vl}, 아니면 {@code bsc_stng_vl} */
    BigDecimal appliedVl(String anlsTypeCd, String vrblCd) {
        List<BigDecimal> v = jdbc.queryForList(
                "SELECT CASE WHEN use_yn = 'Y' THEN stng_vl ELSE bsc_stng_vl END"
                + " FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND vrbl_cd = ?",
                BigDecimal.class, anlsTypeCd, vrblCd);
        return v.isEmpty() ? null : v.get(0);
    }

    // =====================================================================================
    // 산식 재계산 (double — 계산기의 BigDecimal 경로와 다른 산술이다)
    // =====================================================================================

    /** 증가율(%) 재계산 — 이전 블록이 하한 미만이면 {@code null}, 아니면 소수 1자리 */
    static BigDecimal incrsRt(long rcnt, long bfr, int minTrendBase) {
        if (bfr < minTrendBase) {
            return null;
        }
        return BigDecimal.valueOf((rcnt - bfr) * 100.0 / bfr).setScale(1, RoundingMode.HALF_UP);
    }

    // =====================================================================================
    // 매퍼 파라미터
    // =====================================================================================

    /**
     * 매퍼 파라미터 조립 — 서비스가 민원 창에서 파생하는 추세 8칸을 {@link Win} 으로 <b>다시 계산</b>해
     * 넣는다(블록이 7일 미만이면 서비스와 같이 8칸을 비우고 일수 2칸을 0 으로 둔다).
     */
    static ZoneRcmdtnQueryParam queryParam(String emdCd, Win cv, Win crdn,
                                           BigDecimal minCpcty, String trgtTypeSe, BigDecimal rng) {
        ZoneRcmdtnQueryParam p = new ZoneRcmdtnQueryParam();
        p.setEmdCd(emdCd);
        p.setAltmntRng(rng);
        p.setMonths(cv.months());
        p.setCvFrom(cv.from());
        p.setCvToExcl(cv.toExcl());
        p.setCrdnMonths(crdn.months());
        p.setCrdnFrom(crdn.from());
        p.setCrdnTo(crdn.toExcl());
        p.setMinCpcty(minCpcty);
        p.setTrgtTypeSe(trgtTypeSe);
        if (cv.trendOk()) {
            p.setTrendDayCnt(cv.days());
            p.setSplitDayCnt(cv.half());
            p.setTrendFrom(cv.from());
            p.setTrendToExcl(cv.toExcl());
            p.setBfrFrom(cv.bfr().from());
            p.setBfrToExcl(cv.bfr().toExcl());
            p.setSplitTrendFrom(cv.splitRcnt().from());
            p.setSplitTrendToExcl(cv.splitRcnt().toExcl());
            p.setSplitBfrFrom(cv.splitBfr().from());
            p.setSplitBfrToExcl(cv.splitBfr().toExcl());
        }
        return p;
    }
}
