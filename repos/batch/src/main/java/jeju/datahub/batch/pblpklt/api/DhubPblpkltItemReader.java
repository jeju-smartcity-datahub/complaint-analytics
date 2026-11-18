package jeju.datahub.batch.pblpklt.api;

import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.NonTransientResourceException;
import org.springframework.batch.item.UnexpectedInputException;

import com.fasterxml.jackson.databind.JsonNode;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;
import jeju.datahub.batch.cvlcpt.api.DhubDatasetCount;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;

/**
 * pblpklt API → origin 행 스트림 — {@code pklt_id} keyset 페이징으로 <b>전량을 먼저 받고</b> 한 행씩 흘린다.
 *
 * <h2>🔁 2026-09-17 TSK-4161 Phase 2 Step 12 — 테이블 덤프 1회에서 keyset 페이지 루프로</h2>
 * {@link #doOpen()} 이 {@link DhubPblpkltApiClient#fetchPage} 를 {@code hasMore}({@code rows.size() == pageSize})인
 * 동안 반복해 <b>전 페이지를 버퍼에 모은 뒤</b> 총건수를 대조하고, 그다음에야 {@code read()} 가 행을 내보낸다.
 * 커서는 마지막 {@code pklt_id} 이며 원천 타입에 맞춘 {@code dhub.pblpklt-keyset-order} 를 따른다
 * ({@link DhubPblpkltApiClient} 참고).
 *
 * <h2>🔴 왜 단속처럼 페이지 지연 로딩을 하지 않나 — 이 잡은 부재로 행을 지운다</h2>
 * 단속 Reader 는 버퍼가 빌 때마다 다음 페이지를 받고 마지막 페이지 뒤에 총건수를 대조한다(앞 청크는 이미 커밋).
 * 주차장은 같은 Job 의 다음 Step({@code PblpkltSoftDeleteMarkingTasklet})이 seen set 에 없는 행을 소멸 처리하므로,
 * <b>전량 보장 판정을 어떤 쓰기보다도 먼저</b> 끝내는 편을 택했다.
 * <ul>
 *   <li>페이지 중간 예외(HTTP·{@code truncated}·커서 미전진·키 NULL) → {@code open()} 이 던진다 → 수집 Step
 *       <b>FAILED</b>(청크 0건 커밋) → Job 이 거기서 멈춰 소멸 마킹 Step 이 <b>시작조차 안 한다</b>
 *       ({@code .start(fetch).next(softDelete)}). 마킹 Tasklet 의 {@code upstreamHealthy} 가드가 두 번째 겹이다.</li>
 *   <li>총건수 가드 — 마지막 페이지 직후, 역시 {@code open()} 안에서 판정한다. 즉 <b>소멸 마킹 Step 이전</b>이며
 *       청크 쓰기보다도 이전이다.</li>
 *   <li>메모리: 원천 2,272행 × 33필드 ≈ 1.5MB(스파이크 페이지 1000행 ≈ 660KB). 원천이 수십만 행으로 커지면
 *       단속처럼 지연 로딩으로 바꾸되 총건수 판정 시점이 마킹 Step 이전인지 다시 확인한다.</li>
 * </ul>
 * 종전 가드 A({@code limit} 도달 → 예외)는 {@code limit} 쿼리스트링과 함께 사라졌다
 * (Step 13 가드 표 선반영). 잘림은 공용 부품이 {@code truncated=true}·커서 미전진으로 끊는다.
 *
 * <h2>🔁 2026-08-12 추가 — soft delete 를 위한 seen set (가이드 3-2)</h2>
 * <p>{@link #read()} 가 내보낸 원천 ID 와 주소 원문을 각각 수집한다.
 * 소멸 마킹 Tasklet 은 주소 집합을 사용하며, Processor 가 제외한 행의 주소도 포함한다.
 * 두 집합은 close() 후에도 유지하고 다음 open() 에서 새로 만든다.
 *
 * <h2>🔒 로깅</h2>
 * 응답 값을 로그에 남기지 않는다 — 이름·좌표가 그대로 흘러나가는 걸 막기 위함.
 */
public class DhubPblpkltItemReader implements ItemReader<PblpkltOriginRow>, ItemStream {

    private static final Logger log = LoggerFactory.getLogger(DhubPblpkltItemReader.class);

    /**
     * 페이지 루프 상한(시큐어코딩 3.2 — TSK-4336). 페이지 1,000행 기준 1,000만 행이라 실측(수백 행)으로는 닿지 않는다.
     * 닿으면 원천이 hasMore 를 끝없이 주는 상황이라 예외로 끊는다({@code DhubPkltUtztnApiClient.MAX_PAGES} 와 같은 값).
     */
    static final int MAX_PAGES = 10_000;

    private final DhubPblpkltApiClient client;
    private final DhubProperties properties;

    /** 전 페이지 수신분을 한 행씩 풀어보내는 버퍼. open() 에서 채워지고 read() 에서 줄어든다. */
    private Deque<JsonNode> buffer;

    /** open() 이 실제로 호출됐는지 — Spring Batch 가 read() 만 먼저 부를 수도 있어 가드로 둠. */
    private boolean opened;

    /** 이번 실행에서 내보낸 원천 ID. 조회 진단용이며 소멸 판정에는 주소 집합을 쓴다. */
    private Set<String> seenPkltIds = Collections.emptySet();

    /** 소멸 판정은 원천 ID 가 아니라 수신한 주소 원문의 집합으로 한다. */
    private Set<String> seenAddresses = Collections.emptySet();
    private boolean hasUnaddressedRows;

    public DhubPblpkltItemReader(DhubPblpkltApiClient client, DhubProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * Step 시작 시점에 호출된다. <b>부작용</b>(실제 HTTP 요청)이 여기서 일어난다 — Spring Batch 의
     * lifecycle 메서드는 idempotent 해야 하므로 두 번 이상 호출돼도 안전하게 no-op 으로 만든다.
     */
    public void open() {
        doOpen();
    }

    /**
     * 🔁 2026-08-12 — {@link ItemStream} 구현. Spring Batch 가 Step 시작 시 {@code open()} 을
     * 명시적으로 호출한다. 이게 없으면 두 번째 실행부터 read() 가 불리지 않아
     * seen set 이 비어 soft delete 가 안 되는 버그가 있었다(첫 실행은 read() 안의 가드가
     * 우연히 open() 을 트리거해서 동작했음).
     */
    @Override
    public void open(ExecutionContext executionContext) {
        doOpen();
    }

    /**
     * 실제 준비 작업 — 설정 검증 → 총건수 교차검증 → 전 페이지 수신 → 총건수 대조 → 버퍼·seen set 준비.
     *
     * <h2>🔴 왜 한 메서드인가 (🔁 2026-09-17 TSK-4161)</h2>
     * 종전에는 {@link #open()} 과 {@link #open(ExecutionContext)} 에 <b>본문이 통째로 복제</b>돼
     * 있었다. 그 상태에서 가드를 한쪽에만 넣으면 다른 경로로 들어올 때 <b>조용히 통과</b>한다 —
     * 이 잡의 사고가 정확히 그 실패 양식이었으므로(경고만 있고 막는 것은 없었다) 먼저 합쳤다.
     */
    private void doOpen() {
        if (opened) {
            return;
        }
        opened = true;
        // 이전 실행의 seen set 을 HTTP 전에 먼저 버린다 — 이번 수집이 실패하면 빈 집합이 남아
        // 마킹 Tasklet 이 (설령 돌더라도) "비어 있음" 으로 물러난다.
        seenPkltIds = new HashSet<>();
        seenAddresses = new HashSet<>();
        hasUnaddressedRows = false;
        buffer = new ArrayDeque<>();
        // 시큐어코딩 4.3(TSK-4336) — catch (RuntimeException) + 재던지기를 성공 플래그 + finally 로 바꿨다.
        // 어떤 예외든 그대로 전파되고 정리도 그대로 일어난다(종전과 같은 동작, 넓은 catch 없음).
        boolean fetched = false;
        try {
            fetchAllPages();
            fetched = true;
        } finally {
            if (!fetched) {
                // 🔴 실패한 준비 상태를 "열림" 으로 남기지 않는다 — 남기면 다음 read() 가 빈 버퍼를 보고
                //    null(= 정상 종료)을 돌려줘, 부분 수집이 조용히 COMPLETED 로 보일 수 있다.
                buffer = new ArrayDeque<>();
                opened = false;
            }
        }
    }

    /** 설정 검증 → 총건수 → 전 페이지 → 총건수 대조. 예외는 {@link #doOpen()} 이 상태를 되돌린 뒤 그대로 던진다. */
    private void fetchAllPages() {
        // Job 시작 시점 검증 — 기동은 통과시키되 Job 실행이 HTTP 호출 전에 명확히 실패하도록 (rule_batch-project.md).
        properties.requireCredentials();
        properties.requirePblpkltSourceTable();
        int pageSize = properties.requirePblpkltPageSize();

        // best-effort 교차검증 — 확인 불가면 UNAVAILABLE 이고, 그때는 커서 루프 가드(truncated·미전진)에만 기댄다.
        long total = client.count();

        String cursor = null;
        boolean hasMore = true;
        int pages = 0;
        while (hasMore && pages < MAX_PAGES) {
            // 🔴 여기서 나는 예외(HTTP·truncated·커서 미전진·키 NULL)는 삼키지 않는다 — open() 이 던져야
            //    수집 Step 이 FAILED 가 되고 소멸 마킹 Step 이 돌지 않는다.
            DhubKeysetPage page = client.fetchPage(cursor, pageSize);
            pages++;
            page.rows().forEach(buffer::add);
            hasMore = page.hasMore();
            if (page.nextCursor() != null) {
                cursor = page.nextCursor();
            }
        }
        if (hasMore) {
            // 🔴 삼키지 않는다 — doOpen() 이 상태를 되돌리고 수집 Step 이 FAILED 가 되어야 소멸 마킹이 돌지 않는다.
            throw new DhubApiException("pblpklt 페이지 상한(" + MAX_PAGES
                    + ")에 닿았습니다 — 커서가 전진하지 않는지 원천을 확인하세요.");
        }
        int received = buffer.size();

        // 🔴 페이징 누락(커서가 건너뛰고도 hasMore=false 로 정상 종료)을 잡는 유일한 사후 장치.
        //   이 잡 뒤에는 부재(不在)로 행을 지우는 소멸 마킹 Tasklet 이 붙어 있다 — 배치 전체에서
        //   유일하다. 전량이 아닌 수신분으로 그것이 돌면 멀쩡한 주차장이 화면에서 사라진다.
        //   ⚠️ 비교 대상은 **원천에서 읽은 행수** 다. 적재 행수(parking.tb_pblpklt)와
        //   비교하면 지오코딩 스킵분(2026-09-17 기준 102행)만큼 매 실행 오탐이 된다.
        if (total != DhubDatasetCount.UNAVAILABLE && total > received) {
            throw new DhubApiException(
                    ("pblpklt 전량 수집 실패 — 원천 %d행 중 %d행만 수신했습니다(페이지 %d회, 페이지 크기 %d). "
                            + "keyset 커서가 행을 건너뛰었거나(키 pklt_id 는 사전순 — 숫자 캐스팅 여부 확인) "
                            + "원천이 수집 중 줄었는지 확인하세요.").formatted(total, received, pages, pageSize));
        }

        log.info("pblpklt Reader 준비 완료 — {}행을 버퍼에 적재(페이지 {}회, 페이지 크기 {}, 원천 총건수={})",
                received, pages, pageSize, total == DhubDatasetCount.UNAVAILABLE ? "확인불가" : total);
    }

    @Override
    public void update(ExecutionContext executionContext) {
        // restart 후에도 seen set 이 유지되도록 ExecutionContext 에 넣어둔다.
        // 단 seen 은 메모리 set 인데 String[] 로 직렬화 가능한 형태로 저장.
        if (seenPkltIds != null && !seenPkltIds.isEmpty()) {
            executionContext.put(SEEN_KEY, seenPkltIds.toArray(new String[0]));
        }
        executionContext.put("pblpklt.seenAddresses", seenAddresses.toArray(new String[0]));
    }

    @Override
    public void close() {
        // 자원 해제 — 실제로는 client 가 HTTP 풀이라 close() 안 함. 명시적 표기.
        buffer = null;
        // 이 인스턴스가 빈으로 재사용될 수 있다(spring.batch.job.enabled=false 환경에서도
        // 트리거마다 같은 빈이 반환되는 케이스). 다음 실행의 open() 가 다시 동작하려면
        // opened 플래그를 리셋해야 한다. 안 그러면 open() 의 첫 줄 `if (opened) return;` 이
        // 통과해 buffer=null 인 채로 read() 가 NPE 로 터진다(코멘트 1).
        opened = false;
        // 주소 집합은 비우지 않는다. 뒤따르는 소멸 마킹 Step 이 읽은 뒤 다음 open() 에서 교체한다.
    }

    /** ExecutionContext 에 seen set 을 저장할 때 쓰는 키. */
    private static final String SEEN_KEY = "pblpklt.seenPkltIds";

    /** 이번 실행에서 내보낸 원천 ID 의 읽기 전용 집합. */
    public Set<String> getSeenPkltIds() {
        return Collections.unmodifiableSet(seenPkltIds);
    }

    public Set<String> getSeenAddresses() {
        return Collections.unmodifiableSet(seenAddresses);
    }

    public boolean hasUnaddressedRows() {
        return hasUnaddressedRows;
    }

    @Override
    public PblpkltOriginRow read()
            throws UnexpectedInputException, ParseException, NonTransientResourceException {
        if (!opened) {
            open();
        }
        JsonNode node = buffer.poll();
        if (node == null) {
            return null;
        }
        PblpkltOriginRow row = PblpkltOriginRow.fromApi(node);
        // emit 직전에 seen set 에 누적 — Processor 에서 null 로 떨어져도 "봤다"는 사실은 유지.
        if (row != null && row.pkltId() != null) {
            seenPkltIds.add(row.pkltId());
        }
        // Processor 에서 제외돼도 원천에 존재한 주소는 남긴다. close() 후 후행 Step 이 참조한다.
        if (row != null) {
            if (row.pkltAddr() == null || row.pkltAddr().isBlank()) {
                hasUnaddressedRows = true;
            } else {
                seenAddresses.add(row.pkltAddr());
            }
        }
        return row;
    }
}
