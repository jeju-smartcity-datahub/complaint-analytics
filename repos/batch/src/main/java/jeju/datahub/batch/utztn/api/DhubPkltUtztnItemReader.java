package jeju.datahub.batch.utztn.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;

/**
 * 주차장 이용패턴 Reader — <b>{@code msmt_ym} 파티션 순회</b>.
 *
 * <p>{@code read()} 는 버퍼에서 한 행씩 흘리고, 버퍼가 비면 다음 달을 한 왕복으로 받아 채운다.
 * 월 목록이 끝나면 <b>총건수 교차검증</b> 후 {@code null} 을 돌려 Step 을 끝낸다.
 *
 * <pre>
 * 월 목록 15개 → 월별 1왕복씩 → 총건수 1왕복 = 17회 (2026-09-29 실측: 4,308행 · 주차장 369개)
 * </pre>
 *
 * <h2>🔴 총건수 교차검증이 누락을 잡는다</h2>
 * 월별 수신 합계 ≠ 원천 {@code count(*)} 면 <b>예외로 끊는다</b>(Job FAILED). 부분 수집분을
 * {@code COMPLETED} 로 끝내면 불완전한 결과가 완전한 것처럼 보인다 — upsert 라 다시 돌리면 그만이다.
 * ⚠️ 기대값을 {@code 369 × 15} 로 계산하지 않는다 — 원천에 결측이 있다.
 *
 * <h2>🔴 싱글턴 빈이라 {@link ItemStream} 으로 상태를 리셋한다</h2>
 * {@code @StepScope} 없는 싱글턴이므로 <b>트리거마다 같은 인스턴스가 재사용된다.</b> {@code open()} 에서
 * 초기화하고 {@code close()} 에서 {@code opened} 를 풀지 않으면 <b>두 번째 실행이 read=0 으로 아무 일도
 * 하지 않으면서 {@code COMPLETED} 로 끝난다</b>(2026-09-30 E2E 실측 — 재실행이 조용한 no-op 이었다).
 * 형제 수집 Reader({@code DhubCrackdownItemReader}·{@code DhubPblpkltItemReader})가 같은 함정을 이미
 * 기록해 뒀다(그쪽은 {@code buffer=null} 인 채 NPE 로 터졌다 — 2026-09-14 실행 415).
 */
public class DhubPkltUtztnItemReader implements ItemReader<PkltUtztnOriginRow>, ItemStream {

    private static final Logger log = LoggerFactory.getLogger(DhubPkltUtztnItemReader.class);

    private final DhubPkltUtztnApiClient apiClient;

    private boolean opened;
    private Deque<String> pendingMonths;
    private Deque<PkltUtztnOriginRow> buffer = new ArrayDeque<>();
    private long received;
    private boolean crossChecked;

    public DhubPkltUtztnItemReader(DhubPkltUtztnApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /** Spring Batch 가 Step 시작 시 호출한다({@link ItemStream}). */
    @Override
    public void open(ExecutionContext executionContext) {
        if (opened) {
            return;
        }
        opened = true;
        pendingMonths = null;
        buffer = new ArrayDeque<>();
        received = 0;
        crossChecked = false;
    }

    @Override
    public void close() {
        // 🔴 opened 를 풀지 않으면 다음 실행의 open() 이 건너뛰어, 소진된 상태 그대로
        //    read() 가 즉시 null 을 돌려준다 = read 0 · write 0 인 채 COMPLETED 다(조용한 no-op).
        opened = false;
    }

    @Override
    public PkltUtztnOriginRow read() {
        if (pendingMonths == null) {
            pendingMonths = new ArrayDeque<>(apiClient.fetchMonths());
            if (pendingMonths.isEmpty()) {
                throw new IllegalStateException(
                        "원천 월 목록이 비어 있습니다 — 데이터셋(dhub.utztn-table-id)과 FROM 절"
                                + "(dhub.utztn-source-table)의 짝을 확인하세요. 0행을 성공으로 끝내지 않습니다.");
            }
        }

        while (buffer.isEmpty() && !pendingMonths.isEmpty()) {
            String month = pendingMonths.poll();
            List<PkltUtztnOriginRow> rows = apiClient.fetchMonth(month);
            log.debug("이용패턴 {} — {}행", month, rows.size());
            buffer.addAll(rows);
            received += rows.size();
        }

        if (!buffer.isEmpty()) {
            return buffer.poll();
        }

        crossCheckOnce();
        return null;
    }

    /** 월 목록을 모두 소진한 뒤 한 번만 부른다 — {@code read()} 가 여러 번 null 을 돌려도 왕복은 1회다. */
    private void crossCheckOnce() {
        if (crossChecked) {
            return;
        }
        crossChecked = true;

        long total = apiClient.countAll();
        if (received != total) {
            throw new IllegalStateException(
                    "이용패턴 수신 건수가 원천 총건수와 다릅니다 — 수신 " + received + " / 원천 " + total
                            + ". 부분 수집분을 COMPLETED 로 끝내지 않습니다(upsert 라 재실행하면 이어집니다).");
        }
        log.info("이용패턴 수집 완료 — 수신 {}행 = 원천 총건수 {}행 (교차검증 통과)", received, total);
    }

    /** 실행 이력·로그용. 교차검증 전이면 그 시점까지의 수신 건수다. */
    public long receivedCount() {
        return received;
    }
}
