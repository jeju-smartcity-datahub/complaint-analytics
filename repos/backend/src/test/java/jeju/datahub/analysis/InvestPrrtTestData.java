package jeju.datahub.analysis;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.analysis.service.impl.InvestPrrtQueryParam;

/**
 * 투자우선순위 테스트가 쓰는 <b>데이터 독립 기대값 도구</b>(TSK-4036 — {@code ZoneRcmdtnTestData} 형식).
 *
 * <h2>🔴 날짜·건수·주차장 이름을 박지 않는다 — 로컬 데이터가 날짜째 옮겨진다</h2>
 * 조회 창은 DB 의 {@code max(ocrn_dt)} 에서 3개월을 거슬러 잡고({@link #recentWin()}), 기대값은
 * 매퍼와 <b>다른 경로의 SQL</b>로 다시 센다.
 *
 * <h2>🔴 매퍼 XML 을 복사하지 않는다</h2>
 * 같은 SQL 을 복사하면 같은 버그를 복사한다. 여기 SQL 은 일부러 다른 모양이다.
 * <ul>
 *   <li>최근접 배정 — 매퍼는 "주차장 → 반경 안 사건(GiST) → {@code DISTINCT ON}" 이고, 여기는
 *       <b>사건마다</b> 대상과의 쌍을 만들어 <b>거리 최솟값</b>({@code min}) 을 구한 뒤 그 거리의 주차장 중
 *       {@code pklt_id} 가 가장 작은 곳을 고른다. 생성컬럼 {@code geom}·공간 인덱스·{@code <->} 를 쓰지
 *       않고 좌표를 {@code lat}/{@code lot} 에서 직접 만든다.</li>
 *   <li>안전 — 매퍼는 {@code DISTINCT ON … ORDER BY}, 여기는 {@code max(chck_ymd)} 를 구해 조인한 뒤
 *       등급의 최댓값을 뽑는다.</li>
 *   <li>대상 — 매퍼는 {@code NOT IN}, 여기는 {@code NOT (= ANY)}.</li>
 * </ul>
 * 단 <b>거리 정의는 매퍼와 같아야</b> 값이 맞는다 — 반경 판정도 최근접도 <b>구면 거리</b>다
 * (매퍼의 {@code ST_DWithin(…, false)} · {@code geography <->} ↔ 여기의 {@code ST_Distance(…, false)}).
 *
 * <p>⚠️ 쌍을 줄이는 좌표 차 조건({@link #LOOSE_M_PER_DEG})은 매퍼의 사전 필터(m ÷ 80000 도)보다
 * <b>훨씬 느슨한 별개의 값</b>이다 — 매퍼의 사전 필터가 반경 안 사건을 놓치면 여기 값과 어긋난다.
 * 전 쌍(사건 수만 × 대상 수백)에 측지 거리를 계산하면 테스트가 분 단위가 되어 둔 장치다.
 *
 * <p>Spring 빈이 아니다 — 테스트가 {@code new InvestPrrtTestData(jdbcTemplate)} 로 만든다. 전부 {@code SELECT} 다.
 */
final class InvestPrrtTestData {

    static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 쌍을 줄이는 좌표 차 상한의 환산값 — 1도를 <b>50km</b> 로 친다(실제는 한국에서 경도 약 88~92km ·
     * 위도 약 111km 라 반경의 1.8배 이상을 덮는다). 실측값이 아니라 "반경 안 쌍을 절대 놓치지 않는" 여유다.
     */
    private static final double LOOSE_M_PER_DEG = 50_000d;

    /** 대상 주차장 — 소멸하지 않았고 노상·공한지가 아니다(TSK-4347 D1). 매퍼는 NOT IN, 여기는 NOT (= ANY) */
    private static final String TGT = "SELECT p.pklt_id, p.lat::float8 AS lat, p.lot::float8 AS lot"
            + " FROM parking.tb_pblpklt p WHERE p.extsh_dt IS NULL"
            + " AND NOT (p.pklt_type_cd = ANY (ARRAY['ONSTR', 'VACLOT']))";

    /** 같은 DB 를 보는 한 실행 안에서 독립 배정을 다시 계산하지 않는다(쌍 생성이 초 단위다) */
    private static final Map<String, Map<Long, Long>> ASGN_MEMO = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;

    InvestPrrtTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =====================================================================================
    // 조회 창
    // =====================================================================================

    /** 양끝 <b>포함</b> 날짜 창. 서비스가 할 파라미터 파생(반열림 상한 · 이용률 집계 달)을 입력에서 다시 계산한다 */
    record Win(LocalDate bgng, LocalDate end) {

        LocalDateTime from() {
            return bgng.atStartOfDay();
        }

        /** 반열림 상한 = 종료일 다음 날 00:00 */
        LocalDateTime toExcl() {
            return end.plusDays(1).atStartOfDay();
        }

        /**
         * 이용률 집계 시작 달 (TSK-4352 D1 = A) — 시작일이 1일이면 그 달, 아니면 다음 달. 다음 달이 끝 달을 넘으면
         * (한 달 안 창) 끝 달로 clamp 한다. 🔴 impl 의 {@code utztnBgngYm} 을 부르지 않고 같은 규칙을 <b>독립 구현</b>한다
         * (기대값 독립성) — 매퍼 입력({@link #queryParam})과 독립 재계산({@link #utzByLot})이 같은 범위를 보게 한다.
         */
        String bgngYm() {
            YearMonth endYm = YearMonth.from(end);
            YearMonth start = bgng.getDayOfMonth() == 1 ? YearMonth.from(bgng) : YearMonth.from(bgng).plusMonths(1);
            return (start.compareTo(endYm) > 0 ? endYm : start).format(YM);
        }

        String endYm() {
            return end.format(YM);
        }

        String endYmd() {
            return end.format(YMD);
        }
    }

    /**
     * 주차 민원(유형 01~05 · 좌표 있음)의 가장 최근 발생일에서 3개월을 거슬러 잡은 창.
     * 그런 민원이 없으면 {@code null}.
     * <p>⚠️ 기본 기간(TSK-4352 — 12개월)과 별개다 — 매퍼·응답 동등성 검증용 창이라 길이를 3개월로 유지한다(실행 시간).
     */
    Win recentWin() {
        Timestamp max = jdbc.queryForObject("SELECT max(ocrn_dt) FROM parking.tb_cvlcpt"
                + " WHERE lat IS NOT NULL AND cvlcpt_type_cd BETWEEN '01' AND '05'", Timestamp.class);
        if (max == null) {
            return null;
        }
        LocalDate end = max.toLocalDateTime().toLocalDate();
        return new Win(end.minusMonths(3), end);
    }

    static InvestPrrtQueryParam queryParam(Win w, BigDecimal rdsM) {
        InvestPrrtQueryParam p = new InvestPrrtQueryParam();
        p.setBgngDt(w.from());
        p.setEndDtExcl(w.toExcl());
        p.setBgngYm(w.bgngYm());
        p.setEndYm(w.endYm());
        p.setRdsM(rdsM);
        return p;
    }

    // =====================================================================================
    // 독립 집계
    // =====================================================================================

    /** 사용 중인 INVEST 설정 행 — 일시 입력의 기대값은 상수가 아니라 이 값으로 계산한다(TSK-4325) */
    List<Map<String, Object>> investRows() {
        return jdbc.queryForList("SELECT vrbl_cd, vrbl_se_cd, stng_vl, min_vl, max_vl FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = 'INVEST' AND use_yn = 'Y' ORDER BY sort_seq");
    }

    /** INVEST 설정 표 전체의 지문 — 일시 입력이 설정을 바꾸지 않았는지 비교한다 */
    List<Map<String, Object>> investStngPrint() {
        return jdbc.queryForList("SELECT vrbl_cd, stng_vl, mdfr_id, mdfcn_dt FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = 'INVEST' ORDER BY vrbl_cd");
    }

    /**
     * 현재 설정값과 다른 일시 입력 한 벌 — 가중치 전부(합계 유지, 두 지표를 ±{@code delta}) + 계수 2개(🔁 TSK-4346 — 숨김 INDCT_PKLT_CNT 제외).
     *
     * @return 만들 수 없는 설정이면 {@code null}(호출부가 {@code assumeTrue} 한다)
     */
    Map<String, BigDecimal> adjustedVrblVls(BigDecimal delta) {
        Map<String, BigDecimal> vals = new LinkedHashMap<>();
        String up = null;
        String down = null;
        for (Map<String, Object> row : investRows()) {
            if (!"WGVL".equals(row.get("vrbl_se_cd"))) {
                continue;
            }
            String cd = (String) row.get("vrbl_cd");
            BigDecimal vl = (BigDecimal) row.get("stng_vl");
            vals.put(cd, vl);
            if (up == null && vl.add(delta).compareTo((BigDecimal) row.get("max_vl")) <= 0) {
                up = cd;
            } else if (down == null && vl.subtract(delta).compareTo((BigDecimal) row.get("min_vl")) >= 0) {
                down = cd;
            }
        }
        if (up == null || down == null) {
            return null;
        }
        vals.put(up, vals.get(up).add(delta));
        vals.put(down, vals.get(down).subtract(delta));

        for (Map<String, Object> row : investRows()) {
            BigDecimal vl = (BigDecimal) row.get("stng_vl");
            BigDecimal min = (BigDecimal) row.get("min_vl");
            BigDecimal max = (BigDecimal) row.get("max_vl");
            switch ((String) row.get("vrbl_cd")) {
                case "ALTMNT_RNG_VL" -> vals.put("ALTMNT_RNG_VL", moved(vl, BigDecimal.valueOf(50), min, max));
                // 🔁 TSK-4346 — INDCT_PKLT_CNT 는 숨김(expsr_yn='N')이라 일시 조정이 400 이다 — 넣지 않는다
                // 중간은 내리고 상위는 그대로 둔다 — 역전되지 않는다
                case "MD_GRD_CRTR_SCR" -> vals.put("MD_GRD_CRTR_SCR", vl.subtract(BigDecimal.ONE).max(min));
                default -> { }
            }
        }
        return vals;
    }

    private static BigDecimal moved(BigDecimal vl, BigDecimal step, BigDecimal min, BigDecimal max) {
        return vl.add(step).compareTo(max) <= 0 ? vl.add(step) : vl.subtract(step).max(min);
    }

    /** 대상 주차장 ID(오름차순) */
    List<Long> targetIds() {
        return jdbc.queryForList("SELECT t.pklt_id FROM (" + TGT + ") t ORDER BY t.pklt_id", Long.class);
    }

    /**
     * 이용패턴(월 집계)에 한 행도 없는 대상 주차장 ID(오름차순) — TSK-4347 D3 이후 대상에 남아 이용 0점으로 계산된다.
     * 조회 창과 무관하다(전 기간에 행이 없다).
     */
    List<Long> targetIdsWithoutUtztn() {
        return jdbc.queryForList("SELECT t.pklt_id FROM (" + TGT + ") t"
                + " LEFT JOIN (SELECT DISTINCT u.pklt_id FROM parking.tb_pklt_utztn_pttn u) x ON x.pklt_id = t.pklt_id"
                + " WHERE x.pklt_id IS NULL ORDER BY t.pklt_id", Long.class);
    }

    /** 행정구역 마스터의 이름 한 쌍. 주차장의 {@code emd_cd} 가 비었으면 둘 다 {@code null} */
    record Admdst(String emdCd, String sggNm, String emdNm) {
    }

    /** 대상 주차장의 읍면동 코드와 <b>행정구역 마스터</b>의 이름(스칼라 서브쿼리 — 매퍼의 JOIN 과 다른 경로) */
    Map<Long, Admdst> admdstByLot() {
        Map<Long, Admdst> out = new LinkedHashMap<>();
        jdbc.query("SELECT p.pklt_id, p.emd_cd,"
                + " (SELECT a.sgg_nm FROM parking.tb_admdst a WHERE a.emd_cd = p.emd_cd),"
                + " (SELECT a.emd_nm FROM parking.tb_admdst a WHERE a.emd_cd = p.emd_cd)"
                + " FROM parking.tb_pblpklt p WHERE p.pklt_id IN (SELECT t.pklt_id FROM (" + TGT + ") t)", rs -> {
            out.put(rs.getLong(1), new Admdst(rs.getString(2), rs.getString(3), rs.getString(4)));
        });
        return out;
    }

    /** 최근접 배정의 대상 — 민원 또는 단속(창 안 · 좌표 있음) */
    enum Src {
        CV("SELECT cvlcpt_id::text AS id, lat::float8 AS lat, lot::float8 AS lot FROM parking.tb_cvlcpt"
                + " WHERE lat IS NOT NULL AND lot IS NOT NULL AND cvlcpt_type_cd BETWEEN '01' AND '05'"
                + " AND ocrn_dt >= ? AND ocrn_dt < ?"),
        CRDN("SELECT crdn_id::text AS id, lat::float8 AS lat, lot::float8 AS lot FROM parking.tb_crdn_dsctn"
                + " WHERE lat IS NOT NULL AND lot IS NOT NULL AND crdn_dt >= ? AND crdn_dt < ?");

        private final String sql;

        Src(String sql) {
            this.sql = sql;
        }
    }

    /** 창 안의 좌표 보유 사건 수 — 배정 합의 상한이다 */
    long eventCount(Src src, Win w) {
        return jdbc.queryForObject("SELECT count(*) FROM (" + src.sql + ") e", Long.class,
                Timestamp.valueOf(w.from()), Timestamp.valueOf(w.toExcl()));
    }

    /**
     * 🔴 최근접 배정의 독립 재계산 — 주차장({@code pklt_id})별 배정 건수. 배정이 0건인 주차장은 키가 없다.
     *
     * <p>사건마다 대상과의 쌍을 만들고(좌표 차로만 줄인다), 반경 안 쌍 중 구면 거리 최솟값을 구한 뒤
     * 그 거리의 주차장 가운데 {@code pklt_id} 가 가장 작은 곳에 센다.
     */
    Map<Long, Long> asgnByLot(Src src, Win w, BigDecimal rdsM) {
        return ASGN_MEMO.computeIfAbsent(src + "|" + w + "|" + rdsM.toPlainString(), k -> {
            String evPt = "ST_SetSRID(ST_MakePoint(ev.lot, ev.lat), 4326)::geography";
            String tPt = "ST_SetSRID(ST_MakePoint(t.lot, t.lat), 4326)::geography";
            String sql = "WITH tgt AS (" + TGT + "),"
                    + " ev AS (" + src.sql + "),"
                    + " pair AS (SELECT ev.id, t.pklt_id,"
                    + "   ST_Distance(" + evPt + ", " + tPt + ", false) AS sph,"
                    + "   ST_Distance(" + evPt + ", " + tPt + ", false) <= ?::float8 AS near"
                    + "   FROM ev JOIN tgt t ON abs(ev.lat - t.lat) <= ?::float8 AND abs(ev.lot - t.lot) <= ?::float8),"
                    + " best AS (SELECT id, min(sph) AS d FROM pair WHERE near GROUP BY id),"
                    + " pick AS (SELECT p.id, min(p.pklt_id) AS pklt_id FROM pair p"
                    + "            JOIN best b ON b.id = p.id AND p.sph = b.d WHERE p.near GROUP BY p.id)"
                    + " SELECT pklt_id, count(*) FROM pick GROUP BY pklt_id";
            double deg = rdsM.doubleValue() / LOOSE_M_PER_DEG;
            Map<Long, Long> out = new LinkedHashMap<>();
            jdbc.query(sql, rs -> {
                out.put(rs.getLong(1), rs.getLong(2));
            }, Timestamp.valueOf(w.from()), Timestamp.valueOf(w.toExcl()), rdsM, deg, deg);
            return out;
        });
    }

    static long sum(Map<Long, Long> m) {
        return m.values().stream().mapToLong(Long::longValue).sum();
    }

    /** 안전점검 결과 한 건 — 결과 코드와 점검일자 */
    record Sfty(String rsltCd, String chckYmd) {
    }

    /**
     * 주차장별 안전점검 결과의 독립 재계산 — 창 종료일(당일 포함) 이전 가장 최근 점검일의 결과 중
     * 가장 나쁜 것(C > B > A). 그날까지 점검이 없는 주차장은 키가 없다.
     */
    Map<Long, Sfty> sftyByLot(Win w) {
        Map<Long, Sfty> out = new LinkedHashMap<>();
        jdbc.query("SELECT s.pklt_id, s.chck_ymd,"
                + " max(CASE s.sfty_chck_rslt_cd WHEN 'A' THEN 1 WHEN 'B' THEN 2 WHEN 'C' THEN 3 END)"
                + " FROM parking.tb_pklt_sfty_chck s"
                + " JOIN (SELECT pklt_id, max(chck_ymd) AS ymd FROM parking.tb_pklt_sfty_chck"
                + "        WHERE chck_ymd <= ? GROUP BY pklt_id) m ON m.pklt_id = s.pklt_id AND m.ymd = s.chck_ymd"
                + " GROUP BY s.pklt_id, s.chck_ymd", rs -> {
            int worst = rs.getInt(3);
            String cd = rs.wasNull() ? null : String.valueOf("ABC".charAt(worst - 1));
            out.put(rs.getLong(1), new Sfty(cd, rs.getString(2)));
        }, w.endYmd());
        return out;
    }

    /** 이용 지표 한 건 — 값이 있는 달의 평균과 그 달 수 */
    record Utz(BigDecimal avg, int mmCnt) {
        static final Utz NONE = new Utz(null, 0);
    }

    /** 주차장별 일평균 회전율의 독립 재계산 — 이용률 집계 달(시작일이 1일이 아니면 시작 달 제외 — D1) 중 값이 있는 달만. 그런 달이 없으면 키가 없다 */
    Map<Long, Utz> utzByLot(Win w) {
        Map<Long, Utz> out = new LinkedHashMap<>();
        jdbc.query("SELECT pklt_id, sum(day_avg_trnvr_rt) / count(*), count(*)"
                + " FROM parking.tb_pklt_utztn_pttn"
                + " WHERE day_avg_trnvr_rt IS NOT NULL AND tot_ym >= ? AND tot_ym <= ? GROUP BY pklt_id", rs -> {
            out.put(rs.getLong(1), new Utz(rs.getBigDecimal(2), rs.getInt(3)));
        }, w.bgngYm(), w.endYm());
        return out;
    }

    /** 지표별 최근 자료일 4칸(TSK-4352 D2) — 응답 {@code cvlcptLastYmd·utztnLastYm·sftyChckLastYmd·acdntLastYmd} 와 같은 순서 */
    record DataLast(String cvlcptLastYmd, String utztnLastYm, String sftyChckLastYmd, String acdntLastYmd) {
    }

    /**
     * 지표별 최근 자료일의 독립 재계산 — <b>창 시작과 무관하게</b> 종료일(종료 달) 이하 전체의 최댓값. 자료가 없으면 {@code null}.
     * 이용률·안전점검·사고는 대상 주차장({@link #TGT})의 자료만, 민원은 사건 조건(좌표 · 유형 01~05)만 본다(반경 배정 없음).
     * 매퍼와 다른 모양이다 — 대상은 {@code IN (TGT)}, 안전점검 상한은 {@code <= 종료일}(매퍼는 {@code < 종료일+1}).
     */
    DataLast dataLast(Win w) {
        String tgtIds = "SELECT t.pklt_id FROM (" + TGT + ") t";
        return jdbc.queryForObject("SELECT"
                + " (SELECT to_char(max(e.ocrn_dt), 'YYYYMMDD') FROM parking.tb_cvlcpt e"
                + "   WHERE e.ocrn_dt < ? AND e.lat IS NOT NULL AND e.cvlcpt_type_cd BETWEEN '01' AND '05'),"
                + " (SELECT max(u.tot_ym) FROM parking.tb_pklt_utztn_pttn u"
                + "   WHERE u.tot_ym <= ? AND u.day_avg_trnvr_rt IS NOT NULL AND u.pklt_id IN (" + tgtIds + ")),"
                + " (SELECT max(s.chck_ymd) FROM parking.tb_pklt_sfty_chck s"
                + "   WHERE s.chck_ymd <= ? AND s.pklt_id IN (" + tgtIds + ")),"
                + " (SELECT to_char(max(h.acdnt_dt), 'YYYYMMDD') FROM parking.tb_pklt_acdnt_hstry h"
                + "   WHERE h.acdnt_dt < ? AND h.pklt_id IN (" + tgtIds + "))",
                (rs, i) -> new DataLast(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                Timestamp.valueOf(w.toExcl()), w.endYm(), w.endYmd(), Timestamp.valueOf(w.toExcl()));
    }
}
