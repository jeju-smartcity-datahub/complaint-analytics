package jeju.datahub.batch.utztn.api;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetQuery;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.cvlcpt.api.DhubQueryClient;
import jeju.datahub.batch.cvlcpt.api.DhubSql;
import jeju.datahub.batch.cvlcpt.api.KeysetOrder;

/**
 * 주차장 이용패턴 원천 조회 — {@code POST /api/v1/analytics/query} (TSK-4189 Step 6).
 *
 * <h2>🔴 keyset 페이징을 쓸 수 없다 — 유일 키가 없다</h2>
 * 형제 잡 셋(민원·주차장·단속)은 모두 {@code DhubKeysetQuery} + 단일 유일 컬럼으로 페이징한다.
 * 이 원천에는 <b>유일한 단일 컬럼이 없다</b> — 행을 가리는 것은 {@code (pklt_nm, msmt_ym)} 복합이고
 * {@code DhubKeysetQuery} 는 단일 키만 받는다. 그래서 <b>월 단위로 쪼개</b> 읽는다.
 *
 * <pre>
 * ① 월 목록:  SELECT DISTINCT msmt_ym FROM t ORDER BY msmt_ym            → 15행
 * ② 월별 수집: SELECT 6필드 FROM t WHERE msmt_ym = '2025-06' ORDER BY pklt_nm LIMIT n
 * ③ 총건수:    SELECT count(*) AS cnt FROM t                              → 4,308 과 대조
 * </pre>
 * 월 최대 369행(주차장 수)이라 {@code max_rows} 1000 안에 <b>한 왕복으로</b> 들어간다 —
 * 총 왕복은 {@code 1 + 15 + 1 = 17}회다(2026-09-29 실측).
 *
 * <h2>🔴 세 질의 모두 3-arg 오버로드에 utztn 데이터셋을 넘긴다</h2>
 * {@link DhubQueryClient#query(String)} · {@code query(String,int)} · {@link DhubQueryClient#count(String)} 는
 * 전부 {@code properties.getTableId()}(= <b>민원</b> 데이터셋)를 쓴다. 그것을 쓰면 {@code FROM} 절과 짝이
 * 어긋나 <b>422</b> 다. 그래서 {@link DhubQueryClient#query(String, String, int)} 만 쓴다.
 *
 * <h2>🔴 월별 수집(②)은 {@code index} keyset 페이징한다 (🔁 2026-09-30)</h2>
 * 원천이 <b>행 번호 {@code index}</b> 를 붙여 준 뒤 형제 잡 셋과 같은 커서 방식으로 바꿨다 —
 * 커버리지가 넓어져 한 달이 1000행을 넘어도 그대로 돈다.
 * 🔴 커서 컬럼은 <b>유일해야</b> 한다 — 전제 검증은 {@link #KEYSET_COLUMN} javadoc 참조.
 *
 * <p><b>주차장 목록(①-1)도 페이징한다</b> — {@code GROUP BY … HAVING min(`index`) > 커서} 다
 * (공용 부품은 {@code GROUP BY} 를 만들지 못해 그 SQL 만 직접 조립한다).
 * 반면 <b>월 목록(①)</b> 만 페이징하지 않는다 — 월 수(15)만큼이라 1000 에 닿으려면 83년이 걸린다.
 * 닿았다면 전제가 무너진 것이므로 <b>예외로 끊는다</b>(조용한 부분 적재보다 시끄러운 실패가 낫다).
 * 판정 기준을 {@code 1000} 리터럴로 박지 않는 이유는, 그 값을 낮췄을 때 게이트가 발화하지 않기 때문이다.
 *
 * <p>⚠️ 이름·주소 <b>2단 매칭은 그대로다</b> — {@code index} 는 행 번호라 그게 어느 주차장인지 알려주지
 * 않는다. 그것까지 없애려면 원천이 <b>자기 주차장 번호</b>를 줘야 한다(우리 {@code origin_pk} 와 같은
 * 계열이면 조인으로 끝난다 — 요청 대기).
 */
@Component
public class DhubPkltUtztnApiClient {

    private static final Logger log = LoggerFactory.getLogger(DhubPkltUtztnApiClient.class);

    /** 원천 6필드 — SELECT 목록이자 {@link PkltUtztnOriginRow} 의 입력이다. */
    private static final List<String> COLUMNS =
            List.of("pklt_nm", "pklt_addr", "msmt_ym", "use_cnt", "pklt_cpcty", "use_rate");

    /**
     * keyset 커서 — 원천이 붙여 주는 <b>행 번호</b>다 (🔁 2026-09-30 원천측 추가).
     *
     * <p>🔴 <b>유일해야 한다</b> — 겹치면 {@code WHERE index > 커서} 가 같은 값의 다른 행을 함께 건너뛰어
     * <b>행이 조용히 누락된다.</b> 실제로 처음 받은 데이터셋은 4,308행 중 고유 4,068(중복 240)이라 쓸 수
     * 없었고, 원천이 고쳐 준 뒤 4,308/4,308 이 됐다(2026-09-30 실측). 🔴 원천을 갈아끼울 때 이 전제를
     * 다시 재야 한다 — {@code count(*) = count(DISTINCT `index`)}.
     *
     * <p>🔴 {@link KeysetOrder#NUMERIC} 이다 — 커서가 따옴표 없는 숫자로 나가야 한다. {@code LEXICOGRAPHIC}
     * 이면 {@code '10'} 이 {@code '9'} 보다 작아 페이지가 엉키고, 게다가 공용 부품
     * {@link DhubSql#stringLiteral(String)} 은 한글 커서를 애초에 거부한다(그래서 {@code pklt_nm} 은 못 썼다).
     *
     * <p>⚠️ 컬럼명이 {@code index}(SQL 예약어처럼 보이는 이름)지만 백틱 없이도 동작한다(2026-09-30 실측).
     * 값은 <b>페이지 경계 표시에만</b> 쓰고 적재하지 않는다 — 원천이 스냅샷을 다시 만들어 번호가 바뀌어도
     * 무관하다(매 실행 커서를 처음부터 잡는다).
     */
    private static final String KEYSET_COLUMN = "index";
    private static final KeysetOrder KEYSET_ORDER = KeysetOrder.NUMERIC;

    /** 한 조회의 페이지 상한(시큐어코딩 3.2 — TSK-4336) — {@link #requireUnderPageLimit} 참조. */
    static final int MAX_PAGES = 10_000;

    private final DhubQueryClient queryClient;
    private final DhubProperties properties;

    public DhubPkltUtztnApiClient(DhubQueryClient queryClient, DhubProperties properties) {
        this.queryClient = queryClient;
        this.properties = properties;
    }

    /**
     * ① 집계연월 목록을 오름차순으로 읽는다.
     *
     * <p>🔴 <b>월 목록을 상수로 박지 않는다</b> — 원천이 다음 달분을 추가하면(2026-09-29 현재 최대 {@code 2026-08})
     * 박아 둔 목록이 그 달을 영구히 누락시킨다. 🔴 {@code max_rows} 에 ③의 {@code 1} 을 복사하지 않는다 —
     * 첫 달만 읽고 나머지가 조용히 빠진다.
     */
    public List<String> fetchMonths() {
        if (!properties.getUtztnCsvPath().isBlank()) {
            return jeju.datahub.batch.collect.ParkingCsvSource.read(properties.getUtztnCsvPath()).stream()
                    .map(row -> row.path("msmt_ym").asText("")).distinct().sorted().toList();
        }
        int maxRows = properties.requireUtztnMaxRows();
        String table = properties.requireUtztnSourceTable();
        String sql = "SELECT DISTINCT msmt_ym FROM " + table + " ORDER BY msmt_ym"
                + " LIMIT " + DhubSql.limit(maxRows);

        ArrayNode rows = queryClient.query(properties.getUtztnTableId(), sql, maxRows);
        requireNotTruncated(rows.size(), maxRows, "월 목록");

        List<String> months = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            JsonNode v = row.get("msmt_ym");
            if (v == null) throw new IllegalStateException("월 목록에 msmt_ym 컬럼이 없습니다.");
            months.add(v.isNull() ? "" : v.asText().trim());
        }
        log.info("이용패턴 월 목록 {}개 — {} ~ {}", months.size(),
                months.isEmpty() ? "-" : months.get(0),
                months.isEmpty() ? "-" : months.get(months.size() - 1));
        return months;
    }

    /**
     * ①-1 원천 <b>주차장 목록</b>(월 무관) — 착수 시 매칭을 일괄 배정하려고 읽는다 (TSK-4189 Step 7).
     *
     * <p>🔴 <b>스트리밍 중에 배정하면 순서에 좌우된다.</b> 같은 정규화 주소에 원천 2건이 걸리면
     * 먼저 흘러온 쪽이 {@code MIN(pklt_id)} 를 차지해 <b>이름이 맞는 쪽을 밀어낸다</b>
     * (2026-09-30 E2E 실측: `일도일동 1476-33` 에서 그 사고가 났다). 그래서 전량을 미리 받아
     * 이름 일치를 우선 배정한 뒤 나머지를 채운다 — 순서 의존을 없앤다.
     *
     * <h2>🔴 {@code GROUP BY} + {@code HAVING min(`index`)} 로 페이징한다</h2>
     * 공용 부품 {@link DhubKeysetQuery} 는 {@code GROUP BY} 질의를 만들지 못해 이 SQL 만 직접 조립한다.
     * 커서는 <b>그룹의 최소 행 번호</b>이고, 그룹마다 유일하므로 커서가 반드시 전진한다
     * (2026-09-30 실측으로 동작 확인). 커버리지가 2,263개까지 늘어도 그대로 돈다.
     * 🔴 커서를 숫자로 직접 넣는다 — {@link DhubSql#stringLiteral(String)} 은 문자열 전용이고,
     * 여기 값은 우리가 파싱한 {@code long} 이라 주입 여지가 없다.
     *
     * @return {@code (pklt_nm, pklt_addr)} 고유 조합 — 2026-09-29 실측 369건
     */
    public List<String[]> fetchLots() {
        if (!properties.getUtztnCsvPath().isBlank()) {
            return jeju.datahub.batch.collect.ParkingCsvSource.read(properties.getUtztnCsvPath()).stream()
                    .map(row -> new String[] {row.path("pklt_nm").asText("").isBlank() ? null : row.path("pklt_nm").asText(), row.path("pklt_addr").asText("")})
                    .toList();
        }
        int pageSize = properties.requireUtztnMaxRows();
        String table = properties.requireUtztnSourceTable();

        List<String[]> lots = new ArrayList<>();
        long cursor = 0;
        int pages = 0;
        boolean hasMore = true;
        while (hasMore && pages < MAX_PAGES) {   // 3.2(TSK-4336) — 상한을 반복 조건에 둔다
            String sql = "SELECT pklt_nm, pklt_addr, min(" + DhubSql.column(KEYSET_COLUMN) + ") AS mi"
                    + " FROM " + table
                    + " GROUP BY pklt_nm, pklt_addr"
                    + " HAVING min(" + DhubSql.column(KEYSET_COLUMN) + ") > " + cursor
                    + " ORDER BY mi"
                    + " LIMIT " + DhubSql.limit(pageSize);

            ArrayNode rows = queryClient.query(properties.getUtztnTableId(), sql, pageSize);
            pages++;
            long lastMi = cursor;
            for (JsonNode row : rows) {
                JsonNode nm = row.get("pklt_nm");
                JsonNode addr = row.get("pklt_addr");
                JsonNode mi = row.get("mi");
                if (nm == null || nm.isNull() || addr == null || addr.isNull() || mi == null || mi.isNull()) {
                    throw new IllegalStateException("주차장 목록 행에 pklt_nm/pklt_addr/mi 가 없습니다: " + row);
                }
                lots.add(new String[] { nm.asText().trim(), addr.asText().trim() });
                lastMi = Math.max(lastMi, mi.asLong());
            }
            hasMore = rows.size() >= pageSize;
            // 🔴 커서 미전진 가드 — 무한 루프를 막는다(공용 부품이 없는 경로라 직접 둔다).
            if (hasMore && lastMi <= cursor) {
                throw new IllegalStateException(
                        "주차장 목록 커서가 전진하지 않았습니다(커서=" + cursor + ") — min(`index`) 가 유일하지 않을 수 있습니다.");
            }
            cursor = lastMi;
        }
        if (hasMore) {
            requireUnderPageLimit(pages, "주차장 목록");   // 상한에 닿아 끝났으면 예외 — 덜 받은 채 성공하지 않는다
        }
        log.info("원천 주차장 목록 {}건 ({}페이지)", lots.size(), pages);
        return lots;
    }

    /**
     * ② 한 달분을 읽는다 — <b>{@code index} keyset 페이징</b> (🔁 2026-09-30).
     *
     * <p>종전에는 "월 최대 369행이라 {@code max_rows} 1000 안에 들어온다" 를 전제로 한 번만 요청하고
     * 행수가 상한에 닿으면 FAILED 로 끊었다. 🔴 <b>커버리지가 넓어지면 그 전제가 깨진다</b> —
     * 지금 369개는 제주시 339 · 서귀포 30 이고 전체는 2,263개(제주시 1,728 · 서귀포 535)라,
     * 전 주차장이 들어오면 한 달이 2,263행이 되어 <b>매 실행 FAILED</b> 가 된다.
     * 그래서 형제 잡 셋(민원·주차장·단속)과 <b>같은 keyset 페이징</b>으로 바꿨다.
     *
     * <p>커서 전진·잘림({@code truncated})·키 NULL·커서 미전진 가드는 공용 부품
     * ({@link DhubKeysetQuery}·{@link DhubQueryClient})이 진다 — 직접 만들지 않는다.
     */
    public List<PkltUtztnOriginRow> fetchMonth(String msmtYm) {
        if (!properties.getUtztnCsvPath().isBlank()) {
            return jeju.datahub.batch.collect.ParkingCsvSource.read(properties.getUtztnCsvPath()).stream()
                    .filter(row -> row.path("msmt_ym").asText("").equals(msmtYm))
                    .map(PkltUtztnOriginRow::from).toList();
        }
        int pageSize = properties.requireUtztnMaxRows();
        DhubKeysetQuery query = new DhubKeysetQuery(
                properties.requireUtztnSourceTable(), selectColumns(),
                msmtYm.isBlank() ? "(msmt_ym IS NULL OR msmt_ym = '')" : "msmt_ym = " + DhubSql.stringLiteral(msmtYm),
                KEYSET_COLUMN, KEYSET_ORDER);

        List<PkltUtztnOriginRow> parsed = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        boolean hasMore = true;
        while (hasMore && pages < MAX_PAGES) {   // 3.2(TSK-4336) — 상한을 반복 조건에 둔다
            DhubKeysetPage page = query.fetchPage(queryClient, properties.getUtztnTableId(), cursor, pageSize);
            pages++;
            for (JsonNode row : page.rows()) {
                parsed.add(PkltUtztnOriginRow.from(row));
            }
            hasMore = page.hasMore();
            cursor = page.nextCursor();
        }
        if (hasMore) {
            requireUnderPageLimit(pages, "이용패턴 " + msmtYm);   // 상한에 닿아 끝났으면 예외
        }
        if (pages > 1) {
            log.info("이용패턴 {} — {}행 ({}페이지)", msmtYm, parsed.size(), pages);
        }
        return parsed;
    }

    /** SELECT 목록 — 커서 컬럼을 앞에 붙인다(공용 부품이 커서를 읽으려면 응답에 있어야 한다). */
    private static List<String> selectColumns() {
        List<String> columns = new ArrayList<>();
        columns.add(KEYSET_COLUMN);
        columns.addAll(COLUMNS);
        return List.copyOf(columns);
    }

    /**
     * ③ 원천 전체 건수 — 월별 수신 합계와 교차검증한다.
     *
     * <p>⚠️ 기대값을 곱셈으로 계산하지 않는다 — 원천에 결측이 있어 {@code 369 × 15 = 5,535} 가 아니라
     * <b>4,308</b> 이 정답이다(2026-09-29 실측).
     * 🔴 {@link DhubQueryClient#count(String)} 를 쓰지 않는다 — 그것은 민원 데이터셋으로 질의한다.
     */
    public long countAll() {
        if (!properties.getUtztnCsvPath().isBlank()) return jeju.datahub.batch.collect.ParkingCsvSource.read(properties.getUtztnCsvPath()).size();
        String table = properties.requireUtztnSourceTable();
        String sql = "SELECT count(*) AS cnt FROM " + table;

        ArrayNode rows = queryClient.query(properties.getUtztnTableId(), sql, 1);
        if (rows.isEmpty()) {
            throw new IllegalStateException("총건수 질의가 빈 결과를 돌려줬습니다: " + sql);
        }
        JsonNode cnt = rows.get(0).get("cnt");
        if (cnt == null || cnt.isNull() || !cnt.canConvertToLong()) {
            throw new IllegalStateException("총건수 질의 응답에 cnt 가 없습니다: " + rows.get(0));
        }
        return cnt.asLong();
    }

    /**
     * 페이지 상한 가드(시큐어코딩 3.2 — TSK-4336). 커서 미전진 가드가 1차 방어선이고, 이것은 그것마저 뚫렸을 때
     * 루프가 끝나지 않는 것을 막는 상한이다 — 원천 전체가 2,263개 · 월 2,263행이라 정상 실행은 수 페이지다.
     */
    static void requireUnderPageLimit(int pages, String what) {
        if (pages >= MAX_PAGES) {
            throw new IllegalStateException(
                    what + " 조회가 페이지 상한(" + MAX_PAGES + ") 에 닿았습니다 — 커서가 전진하지 않는지 원천을 확인하세요.");
        }
    }

    private static void requireNotTruncated(int received, int maxRows, String what) {
        if (received >= maxRows) {
            throw new IllegalStateException(
                    what + " 조회가 max_rows(" + maxRows + ") 에 닿았습니다 — 결과가 잘렸을 수 있어 중단합니다. "
                            + "dhub.utztn-max-rows 를 올리거나(상한 1000 — 서버 max_rows 상한) 원천 규모를 확인하세요.");
        }
    }
}
