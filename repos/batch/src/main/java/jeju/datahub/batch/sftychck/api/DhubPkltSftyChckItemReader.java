package jeju.datahub.batch.sftychck.api;

import java.util.ArrayDeque;
import java.util.Deque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;
import jeju.datahub.batch.cvlcpt.api.DhubDatasetCount;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetQuery;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;

/**
 * 주차장 안전점검 API → 원천 행 스트림 — {@code sfty_chck_id} keyset <b>페이지 지연 로딩</b>.
 * {@code DhubCrackdownItemReader} 를 본떴다 — 메모리에는 한 페이지({@code dhub.sftychck-page-size})만 있다.
 *
 * <h2>Step 시작({@code open()}) 순서</h2>
 * <ol>
 *   <li>설정 검증 — 자격증명·원천 테이블·<b>페이지 크기 1..1000</b>(HTTP 호출 전).</li>
 *   <li>🔴 <b>컬럼 감지 1회</b>({@link DhubPkltSftyChckApiClient#detectPkltIdColumn()}) → 그 결과로
 *       {@link DhubKeysetQuery} 를 만든다(D2 — {@code pklt_id} 가 없을 수 있다). 필수 컬럼 누락은 여기서 FAILED.</li>
 *   <li>총건수 1회(best-effort).</li>
 * </ol>
 *
 * <h2>전량 수집 보장</h2>
 * <ol>
 *   <li><b>커서 루프</b> — {@code hasMore} 인 동안 마지막 {@code sfty_chck_id} 로 전진한다. 커서 미전진·키 NULL·
 *       {@code truncated=true} 는 공용 부품이 예외로 끊는다.</li>
 *   <li><b>총건수 교차검증</b> — 마지막 페이지를 받은 뒤 누적 수신 행수가 총건수보다 적으면 {@link DhubApiException}
 *       으로 Step 을 FAILED 시킨다. ⚠️ 판정이 끝에 오므로 앞선 청크는 이미 커밋돼 있다 — Writer 가 자연키
 *       upsert 라 재실행이 멱등이다.</li>
 * </ol>
 *
 * <p>파싱 실패·필드 누락은 스킵 대상이 아니다({@link PkltSftyChckOriginRow#from} 예외 → FAILED).
 *
 * <p>JobConfig 에서 {@code @Bean} 으로 만든다(형제 단속과 같다 — {@code @StepScope} 없는 싱글턴). 그래서
 * {@link #close()} 가 상태를 리셋해야 다음 실행이 처음부터 다시 연다.
 *
 * <h2>🔒 로깅</h2>
 * 응답 값을 로그에 남기지 않는다.
 */
public class DhubPkltSftyChckItemReader implements ItemReader<PkltSftyChckOriginRow>, ItemStream {

    private static final Logger log = LoggerFactory.getLogger(DhubPkltSftyChckItemReader.class);

    private final DhubPkltSftyChckApiClient client;
    private final DhubProperties properties;

    /** 현재 페이지를 한 행씩 풀어보내는 버퍼. 비면 다음 페이지를 받는다. */
    private Deque<JsonNode> buffer;

    /** open() 이 실제로 호출됐는지 — Spring Batch 가 read() 만 먼저 부를 수도 있어 가드로 둠. */
    private boolean opened;

    /** Step 시작 시 감지한 {@code pklt_id} 컬럼 유무. */
    private boolean hasPkltId;

    /** 감지 결과로 만든 수집·총건수 질의 — Step 동안 재사용한다. */
    private DhubKeysetQuery query;

    /** open() 에서 검증한 페이지 크기. */
    private int pageSize;

    /** 원천 총건수(교차검증) — 확인 실패면 {@link DhubDatasetCount#UNAVAILABLE}. */
    private long total;

    /** 다음 페이지 요청에 쓸 커서 — 첫 페이지면 {@code null}. */
    private String cursor;

    /** 다음 페이지가 있을 수 있는지. 마지막 페이지를 받으면 false. */
    private boolean hasMore;

    /** 누적 수신 행수 · 받은 페이지 수. */
    private long received;
    private int pages;
    /** 페이지 루프 상한(시큐어코딩 3.2). 반복 조건에 직접 넣어 무한 루프를 막는다(페이지 1,000행 기준 1,000만 행). */
    static final int MAX_PAGES = 10_000;

    public DhubPkltSftyChckItemReader(DhubPkltSftyChckApiClient client, DhubProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** Spring Batch 가 Step 시작 시 호출한다({@link ItemStream}). */
    @Override
    public void open(ExecutionContext executionContext) {
        doOpen();
    }

    private void doOpen() {
        if (opened) {
            return;
        }
        opened = true;
        buffer = new ArrayDeque<>();
        cursor = null;
        hasMore = true;
        received = 0;
        pages = 0;

        // Job 시작 시점 검증 — 기동은 통과시키되 Job 실행이 명확히 실패하도록.
        if (properties.getSftychckCsvPath().isBlank()) properties.requireCredentials();
        properties.requireSftychckSourceTable();
        pageSize = properties.requireSftychckPageSize();

        // 🔴 컬럼 감지는 Step 마다 1회 — 결과로 SELECT 목록이 정해진다(D2).
        hasPkltId = client.detectPkltIdColumn();
        query = client.keysetQuery(hasPkltId);

        // 총건수는 페이징 전에 한 번 받는다 — 판정은 마지막 페이지 뒤(fillBuffer)에서 한다.
        total = client.count(query);
        log.info("주차장 안전점검 Reader 시작 — 원천 총건수 {}, 페이지 크기 {}, pklt_id 컬럼 {}",
                total == DhubDatasetCount.UNAVAILABLE ? "(확인 불가)" : total, pageSize, hasPkltId ? "있음" : "없음");
    }

    /** 버퍼가 빌 때마다 다음 페이지를 받는다. 마지막 페이지를 받고 나면 총건수를 대조한다. */
    private void fillBuffer() {
        while (buffer.isEmpty() && hasMore && pages < MAX_PAGES) {
            DhubKeysetPage page = client.fetchPage(query, cursor, pageSize);
            ArrayNode rows = page.rows();
            pages++;
            rows.forEach(buffer::add);
            received += rows.size();
            hasMore = page.hasMore();
            if (page.nextCursor() != null) {
                cursor = page.nextCursor();
            }
            if (!hasMore) {
                requireAllReceived();
            }
        }
        if (buffer.isEmpty() && hasMore) {
            throw new DhubApiException("안전점검 페이지 상한(" + MAX_PAGES + ")에 닿았습니다 — 커서가 전진하는지 원천을 확인하세요.");
        }
    }

    /** 총건수 교차검증 — 확인된 총건수보다 적게 받았으면 잘림(페이징 누락) 확정. */
    private void requireAllReceived() {
        if (total != DhubDatasetCount.UNAVAILABLE && total > received) {
            throw new DhubApiException(
                    ("주차장 안전점검 전량 수집 실패 — 원천 %d행 중 %d행만 수신했습니다(페이지 %d회, 페이지 크기 %d). "
                            + "keyset 커서가 행을 건너뛰었거나(키 sfty_chck_id NULL 행 포함) 원천이 수집 중 줄었는지 확인하세요.")
                            .formatted(total, received, pages, pageSize));
        }
        log.info("주차장 안전점검 원천 수신 완료 — {}행 / 페이지 {}회 (총건수 {})", received, pages,
                total == DhubDatasetCount.UNAVAILABLE ? "확인 불가" : total);
    }

    @Override
    public void update(ExecutionContext executionContext) {
        // 저장할 재시작 상태 없음 — 재실행은 커서 없이 처음부터 다시다(자연키 upsert 멱등).
    }

    @Override
    public void close() {
        buffer = null;
        query = null;
        // 싱글턴 빈이라 트리거마다 같은 인스턴스가 재사용된다 — opened 를 리셋해야 다음 실행이
        // 컬럼 감지·커서 초기화부터 다시 한다(형제 단속 Reader 의 2026-09-14 NPE 전례).
        opened = false;
    }

    @Override
    public PkltSftyChckOriginRow read() {
        if (!opened) {
            doOpen();
        }
        fillBuffer();
        JsonNode node = buffer.poll();
        if (node == null) {
            return null;
        }
        return PkltSftyChckOriginRow.from(node, hasPkltId);
    }
}
