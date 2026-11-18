package jeju.datahub.batch.sftychck.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import jeju.datahub.batch.cvlcpt.api.DhubDatasetCount;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetQuery;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.cvlcpt.api.DhubQueryClient;
import jeju.datahub.batch.cvlcpt.api.KeysetOrder;
import jeju.datahub.batch.cvlcpt.api.DhubApiException;

/**
 * DHubManager 주차장 안전점검 조회 — {@code POST /api/v1/analytics/query} + {@code sfty_chck_id} keyset 페이징 (TSK-4254).
 * {@code DhubCrackdownApiClient} 를 본떴다.
 *
 * <pre>{@code
 * 감지:   SELECT * FROM <sftychck-source-table> LIMIT 1                         (max_rows = 1, Step 시작 시 1회)
 * 수집:   SELECT sfty_chck_id, [pklt_id,] <나머지 REQUIRED_COLUMNS> FROM <sftychck-source-table>
 *           [WHERE sfty_chck_id > 커서] ORDER BY sfty_chck_id LIMIT n          (max_rows = n)
 * 총건수: SELECT count(*) AS cnt FROM <sftychck-source-table>                    (같은 WHERE — 필터 없음)
 * }</pre>
 *
 * <ul>
 *   <li><b>키 {@code sfty_chck_id} — 원천 int64, 정렬 {@link KeysetOrder#NUMERIC}</b>(Step 1 실측 유일 · NULL 0).
 *       문자열 비교면 {@code 999 → 1000} 에서 미전진 오탐이다.</li>
 *   <li><b>전송 계층을 따로 두지 않는다</b> — SQL 조립·페이지 판정은 공용 {@link DhubKeysetQuery}, 전송은 주입받은
 *       공용 {@link DhubQueryClient}({@code max_rows}·{@code truncated}·401 재시도)다.</li>
 *   <li>🔴 <b>컬럼 감지(D2)</b> — 원천은 같은 구성이지만 {@code pklt_id} 가 빠질 수 있다. 없는 컬럼을 SELECT 하면
 *       422 로 Step FAILED 이므로 {@link #detectPkltIdColumn()} 결과로 SELECT 목록을 만든다
 *       ({@link #keysetQuery(boolean)}). 컬럼명은 공용 클라이언트가 돌려주는 {@code rows} 의 <b>첫 행 필드명</b>에서
 *       읽는다 — 응답 봉투에 별도 스키마 필드가 없고(공용 클라이언트는 {@code rows} 만 돌려준다), NULL 값도 키가
 *       실려 온다(Step 1 실측).</li>
 *   <li>필터는 없다. 붙일 때는 🔴 {@link DhubKeysetQuery} 생성자의 {@code filter} 로만 넣는다 — 수집·총건수가 같은
 *       WHERE 를 써야 교차검증이 성립한다.</li>
 *   <li>{@code source_id}({@code dhub.sftychck-table-id})가 비면 공용 클라이언트가 호출 전에 예외로 끊는다
 *       (형제 단속과 같은 검증 경로). 🔴 {@code FROM} 테이블과 같은 데이터셋의 짝이어야 한다 — 어긋나면 422 다.</li>
 * </ul>
 *
 * <h2>🔒 로깅</h2>
 * 토큰·비밀번호는 어떤 레벨로도 남기지 않는다. 데이터 값도 남기지 않는다(필드명까지만).
 */
@Component
public class DhubPkltSftyChckApiClient {

    private static final Logger log = LoggerFactory.getLogger(DhubPkltSftyChckApiClient.class);

    /** keyset 키 — 원천 {@code sfty_chck_id}(int64, 유일). */
    static final String KEYSET_COLUMN = PkltSftyChckOriginRow.KEY_COLUMN;

    /** 🔴 키의 정렬 방식 — 원천 컬럼 타입(숫자)과 맞춘다. */
    static final KeysetOrder KEYSET_ORDER = KeysetOrder.NUMERIC;

    private final DhubQueryClient queryClient;
    private final DhubProperties properties;

    public DhubPkltSftyChckApiClient(DhubQueryClient queryClient, DhubProperties properties) {
        this.queryClient = queryClient;
        this.properties = properties;
    }

    /**
     * 원천 컬럼 구성을 감지한다 — {@code SELECT * … LIMIT 1} 응답 첫 행의 필드명 집합(D2).
     *
     * <p>필수 컬럼이 하나라도 없으면 예외(원천 스키마 변경 → Step FAILED). 원천이 0행이면 컬럼을 알 수 없으므로
     * {@code pklt_id} 없음으로 보고 진행한다(읽을 행도 없다). 🔴 감지 실패(HTTP·422)는 삼키지 않는다 —
     * 컬럼을 모른 채 수집하면 다음 요청이 같은 이유로 실패한다.
     *
     * @return {@code pklt_id} 컬럼이 있으면 {@code true}
     * @throws jeju.datahub.batch.cvlcpt.api.DhubApiException 필수 컬럼 누락·HTTP 실패·응답 형태 불일치
     */
    public boolean detectPkltIdColumn() {
        if (!properties.getSftychckCsvPath().isBlank()) {
            var rows = csvRows();
            if (rows.isEmpty()) throw new IllegalStateException("안전점검 CSV가 비어 있습니다.");
            java.util.List<String> fields = new java.util.ArrayList<>();
            rows.get(0).fieldNames().forEachRemaining(fields::add);
            PkltSftyChckOriginRow.requireSourceColumns(fields);
            return fields.contains("pklt_id");
        }
        String sql = "SELECT * FROM " + properties.requireSftychckSourceTable() + " LIMIT 1";
        ArrayNode rows = queryClient.query(properties.getSftychckTableId(), sql, 1);
        if (rows.isEmpty()) {
            log.info("주차장 안전점검 원천 0행 — 컬럼을 감지할 수 없어 {} 없음으로 진행합니다.",
                    PkltSftyChckOriginRow.OPTIONAL_KEY_COLUMN);
            return false;
        }
        Set<String> columns = new TreeSet<>();
        rows.get(0).fieldNames().forEachRemaining(columns::add);
        PkltSftyChckOriginRow.requireSourceColumns(columns);
        boolean hasPkltId = columns.contains(PkltSftyChckOriginRow.OPTIONAL_KEY_COLUMN);
        log.info("주차장 안전점검 원천 컬럼 감지 — {} {}, 원천 컬럼 {}",
                PkltSftyChckOriginRow.OPTIONAL_KEY_COLUMN, hasPkltId ? "있음(키 경로 사용)" : "없음(이름·주소 경로만)",
                columns);
        return hasPkltId;
    }

    /**
     * 수집·총건수 SQL 을 한 곳에서 만든다 — 설정(원천 테이블)을 읽어 검증한다.
     *
     * @param hasPkltId {@link #detectPkltIdColumn()} 결과. {@code true} 면 SELECT 목록에 {@code pklt_id} 를 넣는다
     */
    public DhubKeysetQuery keysetQuery(boolean hasPkltId) {
        return new DhubKeysetQuery(properties.requireSftychckSourceTable(), selectColumns(hasPkltId), null,
                KEYSET_COLUMN, KEYSET_ORDER);
    }

    /**
     * 한 페이지를 받는다.
     *
     * @param query    {@link #keysetQuery(boolean)} 로 만든 질의 — Step 시작 시 1회 만들어 재사용한다
     * @param cursor   직전 페이지의 {@link DhubKeysetPage#nextCursor()}. 첫 페이지면 {@code null}
     * @param pageSize {@code 1..1000} — 호출부(Reader)가 {@link DhubProperties#requireSftychckPageSize()} 로 검증한 값
     * @throws jeju.datahub.batch.cvlcpt.api.DhubApiException HTTP 실패·응답 형태 불일치·{@code truncated=true}
     * @throws IllegalStateException 커서 미전진·키 NULL·{@code source_id} 미설정
     */
    public DhubKeysetPage fetchPage(DhubKeysetQuery query, String cursor, int pageSize) {
        if (!properties.getSftychckCsvPath().isBlank()) {
            var rows = csvRows();
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (JsonNode row : rows) {
                long id = Long.parseLong(row.path("sfty_chck_id").asText());
                if (!ids.add(id)) throw new IllegalStateException("안전점검 CSV 행 ID 가 중복되었습니다.");
            }
            long after = cursor == null ? Long.MIN_VALUE : Long.parseLong(cursor);
            var selected = rows.stream().filter(row -> Long.parseLong(row.path("sfty_chck_id").asText()) > after)
                    .sorted(java.util.Comparator.comparingLong(row -> Long.parseLong(row.path("sfty_chck_id").asText())))
                    .limit(pageSize).toList();
            var array = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
            selected.forEach(array::add);
            return new DhubKeysetPage(array, selected.size() == pageSize,
                    selected.isEmpty() ? null : selected.get(selected.size()-1).path("sfty_chck_id").asText());
        }
        DhubKeysetPage page = query.fetchPage(queryClient, properties.getSftychckTableId(), cursor, pageSize);
        log.debug("DHub 주차장 안전점검 페이지 수신 — tableId={}, rows={}, hasMore={}",
                properties.getSftychckTableId(), page.rows().size(), page.hasMore());
        return page;
    }

    /**
     * 원천 전체 건수 — 수집 SQL 과 <b>같은 WHERE</b> 로 조립한 {@link DhubKeysetQuery#countSql()} 을 실행하는
     * <b>best-effort 교차검증</b>이다. 어떤 실패도 예외로 던지지 않고 {@link DhubDatasetCount#UNAVAILABLE} 로 강등한다
     * (형제 단속과 같은 정책 — 확인되면 Reader 가 정확 대조, 안 되면 WARN).
     */
    public long count(DhubKeysetQuery query) {
        if (!properties.getSftychckCsvPath().isBlank()) return csvRows().size();
        try {
            JsonNode rows = queryClient.query(properties.getSftychckTableId(), query.countSql(), 1);
            long parsed = DhubDatasetCount.parse(rows);
            if (parsed == DhubDatasetCount.UNAVAILABLE) {
                log.warn("DHub 주차장 안전점검 총건수 확인 불가 — analytics/query rows 가 예상 형태([{{\"cnt\": N}}])가 "
                        + "아닙니다. 페이징 누락을 사후에 잡을 장치가 꺼진 채 수집합니다.");
            }
            return parsed;
        } catch (DhubApiException | IllegalStateException | IllegalArgumentException e) {
            // 시큐어코딩 4.3(TSK-4336) — 조회 실패(DhubApiException·401 하위 포함) · 설정 누락(IllegalState) · SQL 조립(IllegalArgument).
            // 교차검증 실패가 수집을 막으면 안 된다 — 원인만 WARN 으로 드러낸다.
            log.warn("DHub 주차장 안전점검 총건수 교차검증 실패({}) — 페이징 누락을 사후에 잡을 장치가 꺼진 채 수집합니다.",
                    e.getClass().getSimpleName());
            // 시큐어코딩 4.1(TSK-4336) — 예외 원문은 DEBUG 로만 남긴다.
            log.debug("DHub 주차장 안전점검 총건수 교차검증 실패 상세", e);
            return DhubDatasetCount.UNAVAILABLE;
        }
    }

    private java.util.List<JsonNode> csvRows() {
        var rows = jeju.datahub.batch.collect.ParkingCsvSource.read(properties.getSftychckCsvPath());
        var ids = properties.getSftychckCsvIncludeIds();
        return ids.isEmpty() ? rows : rows.stream()
                .filter(row -> ids.contains(Long.parseLong(row.path("sfty_chck_id").asText()))).toList();
    }

    /** SELECT 목록 — 키 → ({@code pklt_id}) → 나머지 필수 컬럼. {@code rgtr_id}·{@code reg_dt} 는 넣지 않는다(D8). */
    static List<String> selectColumns(boolean hasPkltId) {
        List<String> columns = new ArrayList<>();
        columns.add(KEYSET_COLUMN);
        if (hasPkltId) {
            columns.add(PkltSftyChckOriginRow.OPTIONAL_KEY_COLUMN);
        }
        PkltSftyChckOriginRow.REQUIRED_COLUMNS.stream()
                .filter(c -> !KEYSET_COLUMN.equals(c))
                .forEach(columns::add);
        return List.copyOf(columns);
    }
}
