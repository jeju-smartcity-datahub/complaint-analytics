package jeju.datahub.batch.pblpklt.api;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import jeju.datahub.batch.cvlcpt.api.DhubDatasetCount;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetQuery;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.cvlcpt.api.DhubQueryClient;
import jeju.datahub.batch.cvlcpt.api.KeysetOrder;
import jeju.datahub.batch.cvlcpt.api.DhubApiException;

/**
 * DHubManager 공영주차장(pblpklt) 조회 — {@code POST /api/v1/analytics/query} + {@code pklt_id} keyset 페이징.
 *
 * <h2>🔁 2026-09-17 — 테이블 덤프에서 전환</h2>
 * 종전에는 {@code GET /api/v1/datasets/{id}/table?format=json} 에 body 로 SQL 을 실어 보냈지만 서버는 그 SQL 을
 * <b>무시</b>했다(2026-09-11 실측) — 필터를 붙일 방법이 없었고, 행 수를 정하는 수단은 쿼리스트링 {@code limit}
 * 하나뿐이었다. 이제 민원·단속과 같은 경로로 페이지 단위로 받는다.
 *
 * <pre>{@code
 * 수집:   SELECT <SOURCE_COLUMNS> FROM <pblpklt-source-table>
 *           [WHERE pklt_id > '커서'] ORDER BY pklt_id LIMIT n        (max_rows = n)
 * 총건수: SELECT count(*) AS cnt FROM <pblpklt-source-table>          (같은 WHERE — 필터 없음)
 * }</pre>
 *
 * <h2>키 정렬은 원천 컬럼 타입에 맞춘다</h2>
 * {@code dhub.pblpklt-keyset-order} 기본값은 기존 String 원천용 {@link KeysetOrder#LEXICOGRAPHIC} 이다.
 * int64 원천(예: innopam_parking)은 {@link KeysetOrder#NUMERIC} 을 설정한다. SQL 정렬·커서 리터럴과
 * Java 미전진 검사가 같은 설정을 사용하며, SQL 에 별도 캐스팅을 섞지 않는다.
 * 원천 ID 는 비유일인 마지막 관측 식별자 {@code origin_pk} 로 저장하며 내부 PK 와 구분한다.
 *
 * <h2>SELECT 목록 = {@link PblpkltOriginRow#SOURCE_COLUMNS}</h2>
 * {@code SELECT *} 를 쓰지 않고 매핑이 읽는 33필드(키 {@code pklt_id} 포함)를 명시한다 — 원천 컬럼명이 바뀌면
 * 서버가 422 로 거절해 조용히 NULL 적재되지 않는다. {@code extsh_dt} 는 매핑하지 않으므로 받지 않는다.
 * 목록과 매핑의 일치는 {@code PblpkltOriginRowTest} 가 고정한다(빠지면 조용히 null 적재).
 *
 * <h2>전송</h2>
 * SQL 조립·페이지 판정은 공용 {@link DhubKeysetQuery}, 전송은 공용 {@link DhubQueryClient}
 * ({@code max_rows} 동기화·{@code truncated=true} 예외·401 재시도)가 진다. 이 클래스는 전송 계층을 따로 두지 않는다.
 * 필터는 현재 없다. 붙일 때는 🔴 {@link DhubKeysetQuery} 생성자의 {@code filter} 로만 넣는다 —
 * 수집·총건수 SQL 이 같은 WHERE 를 써야 교차검증이 성립하고, 소멸 마킹이 필터 밖 행을 지우지 않는다.
 *
 * <h2>🔒 로깅</h2>
 * 토큰·비밀번호는 어떤 레벨로도 남기지 않는다. 데이터 값(이름·주소)도 남기지 않는다.
 */
@Component
public class DhubPblpkltApiClient {

    private static final Logger log = LoggerFactory.getLogger(DhubPblpkltApiClient.class);

    /** keyset 키 — 원천에서 유일한 {@code pklt_id}. 타입은 데이터셋별 설정으로 구분한다. */
    static final String KEYSET_COLUMN = "pklt_id";

    /** 기존 String 원천의 기본 정렬. 실제 조회는 DhubProperties 의 데이터셋별 설정을 사용한다. */
    static final KeysetOrder KEYSET_ORDER = KeysetOrder.LEXICOGRAPHIC;

    /** 수집 SELECT 목록 — 키 + 매핑이 읽는 원천 필드. 매핑 쪽 목록이 정본이다. */
    static final List<String> SELECT_COLUMNS = buildSelectColumns();

    private final DhubQueryClient queryClient;
    private final DhubProperties properties;

    public DhubPblpkltApiClient(DhubQueryClient queryClient, DhubProperties properties) {
        this.queryClient = queryClient;
        this.properties = properties;
    }

    /**
     * 한 페이지를 받는다.
     *
     * @param cursor   직전 페이지의 {@link DhubKeysetPage#nextCursor()}. 첫 페이지면 {@code null}
     * @param pageSize {@code 1..1000} — 호출부(Reader)가 {@link DhubProperties#requirePblpkltPageSize()} 로 검증한 값
     * @throws jeju.datahub.batch.cvlcpt.api.DhubApiException HTTP 실패·응답 형태 불일치·{@code truncated=true}
     * @throws IllegalStateException 커서 미전진·키 NULL
     */
    public DhubKeysetPage fetchPage(String cursor, int pageSize) {
        DhubKeysetPage page = keysetQuery().fetchPage(queryClient, properties.getPblpkltTableId(), cursor, pageSize);
        log.debug("DHub pblpklt 페이지 수신 — tableId={}, rows={}, hasMore={}",
                properties.getPblpkltTableId(), page.rows().size(), page.hasMore());
        return page;
    }

    /**
     * 원천 전체 건수 — 수집 SQL 과 <b>같은 WHERE</b> 로 조립한 {@link DhubKeysetQuery#countSql()} 을 실행하는
     * <b>best-effort 교차검증</b>(🔁 2026-09-17).
     *
     * <h2>왜 필요한가</h2>
     * 페이징은 "중간까지만 받는 상태" 가 정상적으로 존재한다. 커서가 잘못 전진해 페이지를 건너뛰고도
     * {@code hasMore=false} 로 끝나면, 같은 Job 의 소멸 마킹 Tasklet 이 못 받은 행을 소멸 처리한다 — 이 잡은
     * 배치 전체에서 <b>부재(不在)로 데이터를 지우는 유일한 잡</b>이다. 그 누락을 사후에 잡는 장치가 이 총건수다.
     *
     * <h2>🔴 어떤 실패도 예외로 던지지 않는다</h2>
     * HTTP 실패·형태 불일치·파싱 실패 모두 {@link DhubDatasetCount#UNAVAILABLE} 로 강등한다 — 교차검증이 수집을
     * 막으면 가드가 오히려 가용성을 떨어뜨린다. 판정은 Reader 가 한다.
     *
     * <p>⚠️ {@code dhub.pblpklt-source-table} 은 이제 <b>수집 SQL 의 FROM 절</b>이기도 하다 — 틀리면 교차검증뿐
     * 아니라 수집이 첫 페이지부터 422 로 실패한다(허용 이름은 422 응답이 알려준다).
     */
    public long count() {
        try {
            JsonNode rows = queryClient.query(properties.getPblpkltTableId(), keysetQuery().countSql(), 1);
            long parsed = DhubDatasetCount.parse(rows);
            if (parsed == DhubDatasetCount.UNAVAILABLE) {
                // 🔒 응답 값을 싣지 않는다 — 형태(노드 타입)까지만 남긴다.
                log.warn("DHub pblpklt 총건수 확인 불가 — analytics/query rows 가 예상 형태([{{\"cnt\": N}}])가 "
                        + "아닙니다(nodeType={}). 페이징 누락을 사후에 잡을 장치가 꺼진 채 수집합니다.",
                        rows == null ? "null" : rows.getNodeType());
            }
            return parsed;
        } catch (DhubApiException | IllegalStateException | IllegalArgumentException e) {
            // 시큐어코딩 4.3 — 조회 실패(DhubApiException·401 하위 포함) · 설정 누락(IllegalState) · SQL 조립(IllegalArgument).
            // 교차검증 실패가 수집을 막으면 안 된다 — 원인만 WARN 으로 드러낸다.
            log.warn("DHub pblpklt 총건수 교차검증 실패({}) — 페이징 누락을 사후에 잡을 장치가 꺼진 채 수집합니다.",
                    e.getClass().getSimpleName());
            // 시큐어코딩 4.1 — 예외 원문은 DEBUG 로만 남긴다.
            log.debug("DHub pblpklt 총건수 교차검증 실패 상세", e);
            return DhubDatasetCount.UNAVAILABLE;
        }
    }

    /** 수집·총건수 SQL 을 한 곳에서 만든다 — 설정(원천 테이블)을 매번 읽어 검증한다. */
    DhubKeysetQuery keysetQuery() {
        // 🔴 keyColumn 은 컬럼 이름 그대로 — toUInt32(pklt_id) 같은 식을 넣지 않는다(DhubSql.column 이 거부한다).
        return new DhubKeysetQuery(properties.requirePblpkltSourceTable(), SELECT_COLUMNS, null,
                KEYSET_COLUMN, properties.getPblpkltKeysetOrder());
    }

    private static List<String> buildSelectColumns() {
        List<String> columns = new ArrayList<>();
        columns.add(KEYSET_COLUMN);
        PblpkltOriginRow.SOURCE_COLUMNS.stream()
                .filter(c -> !KEYSET_COLUMN.equals(c))   // 키는 매핑 목록에도 있다 — 중복 SELECT 방지
                .forEach(columns::add);
        return List.copyOf(columns);
    }
}
