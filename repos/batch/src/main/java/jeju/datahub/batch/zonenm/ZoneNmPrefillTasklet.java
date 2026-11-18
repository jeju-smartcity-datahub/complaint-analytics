package jeju.datahub.batch.zonenm;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.web.client.ResourceAccessException;

/**
 * 격자 크기별로 백엔드 미리 채우기 API 를 {@code remainingCnt == 0} 까지 반복 호출한다.
 *
 * <h2>1 {@code execute()} = 백엔드 호출 1회 ({@link RepeatStatus#CONTINUABLE})</h2>
 * 루프를 한 {@code execute()} 안에서 돌리지 않는다 — TaskletStep 은 {@code execute()} 마다 트랜잭션을
 * 열므로, 수십 분짜리 루프를 한 번에 돌리면 그동안 DB 커넥션을 붙잡는다. 호출마다 끊으면 커밋(=
 * StepExecution·ExecutionContext 저장)도 호출마다 일어나 <b>중간에 죽어도 어디까지 했는지</b>가 남는다.
 * 그래서 진행 상태를 필드에 들고 있다 — 🔴 반드시 {@code @StepScope} 빈으로 만든다(실행마다 새 인스턴스).
 *
 * <h2>🔴 실패 3갈래</h2>
 * <table>
 *   <tr><th>상황</th><th>처리</th></tr>
 *   <tr><td>200 · {@code remainingCnt == 0}</td><td>그 크기 완료 → 다음 크기</td></tr>
 *   <tr><td>200 · {@code remainingCnt} 가 <b>직전 호출보다 줄지 않았고</b> {@code > 0}</td>
 *       <td>그 크기 중단 + <b>ERROR 1줄</b> → 다음 크기 · Job COMPLETED(VWorld 장애·키 없음 — 일시 실패 갈래)</td></tr>
 *   <tr><td>5xx(503 제외) · 연결 실패 · 타임아웃</td><td>그 크기 중단 + ERROR → 다음 크기(다음 실행이 이어서)</td></tr>
 *   <tr><td>400</td><td>{@link IllegalStateException} → <b>Job FAILED</b>(우리가 잘못 보냈다 — 파라미터 버그)</td></tr>
 *   <tr><td>401 · 403 · 503 · 예상 밖 상태 · 200 인데 본문 계약 불일치</td>
 *       <td>{@link IllegalStateException} → <b>Job FAILED</b>(키 오류·키 미설정 — 다시 돌려도 같다. rptp 와 같은 정책)</td></tr>
 * </table>
 * 🔴 진척 판정은 {@code resolvedCnt == 0} 이 <b>아니다</b> — {@code resolvedCnt} 는 세션의 <b>저장 수</b>라
 * 화면 조회와 경합해 캐시 히트로 끝난 셀을 세지 않는다. {@code resolvedCnt} 로 판정하면 경합만 있던
 * 호출에서 멀쩡히 끊긴다. 남은 수가 엄격히 줄어야 계속하므로 루프는 반드시 끝난다.
 *
 * <h2>지표</h2>
 * 크기마다 호출 수·resolved 합·transient 합·최종 remaining·tripped 횟수·결과를 Step
 * {@link ExecutionContext} 에 {@code zoneNm.{크기}.{지표}} 로 남긴다(호출마다 커밋 → 메타 테이블에 저장).
 * 이력 리스너({@link ZoneNmPrefillHistoryListener})가 그걸 읽어 요약 로그·실행 이력을 만든다.
 * {@link StepContribution} 에도 <b>read = 시도한 셀(resolved+transient) · write = resolved ·
 * filter = transient</b> 로 올려 {@code batch.tb_btch_excn_hstry} 의 {@code read_cnt}/{@code write_cnt} 와
 * 트리거 응답({@code JobExecutionSummary})에 그대로 나타나게 한다.
 */
public class ZoneNmPrefillTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ZoneNmPrefillTasklet.class);

    /** 지원 격자 크기 — 기본 실행 순서(큰 격자부터 — 셀이 적고 많이 보인다). 백엔드 허용 목록과 같다. */
    public static final List<Integer> ALL_GRID_SIZES = List.of(1000, 500, 250, 100);

    /** 백엔드 {@code limit} 허용 범위. */
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 200;

    /** ExecutionContext 키 접두. */
    static final String CTX_PREFIX = "zoneNm.";

    static final String OUTCOME_DONE = "DONE";
    static final String OUTCOME_NO_PROGRESS = "NO_PROGRESS";
    static final String OUTCOME_TRANSIENT = "TRANSIENT_ERROR";

    /** 호출 사이 대기 — 테스트에서 0 으로 갈아끼운다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final BackendZoneNmClient client;
    private final List<Integer> gridSizes;
    private final int limit;
    private final long callIntervalMs;
    private final Sleeper sleeper;

    // ── 진행 상태 (StepScope 인스턴스 전용) ──
    private int sizeIdx;
    private Integer prevRemaining;
    private long totalCalls;
    private SizeStats current;

    public ZoneNmPrefillTasklet(BackendZoneNmClient client, Long gridSz, int limit, long callIntervalMs) {
        this(client, gridSz, limit, callIntervalMs, Thread::sleep);
    }

    ZoneNmPrefillTasklet(BackendZoneNmClient client, Long gridSz, int limit, long callIntervalMs,
                         Sleeper sleeper) {
        this.client = client;
        this.gridSizes = resolveGridSizes(gridSz);
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            // 🔴 400 으로 돌아올 값을 보내지 않는다 — Job 시작 시점에 끊는다.
            throw new IllegalArgumentException(
                    "batch.zone-nm.limit 은 " + MIN_LIMIT + "~" + MAX_LIMIT + " 이어야 합니다: " + limit);
        }
        this.limit = limit;
        this.callIntervalMs = Math.max(0, callIntervalMs);
        this.sleeper = sleeper;
    }

    /**
     * Job 파라미터 {@code gridSz} → 실행할 크기 목록. 없으면 전 크기(1000→500→250→100).
     *
     * @throws IllegalArgumentException 지원하지 않는 크기(트리거가 400 으로 먼저 막지만 방어선)
     */
    static List<Integer> resolveGridSizes(Long gridSz) {
        if (gridSz == null) {
            return ALL_GRID_SIZES;
        }
        if (!isSupported(gridSz)) {
            throw new IllegalArgumentException(
                    "지원하지 않는 gridSz 입니다(1000·500·250·100 중 하나): " + gridSz);
        }
        return List.of(gridSz.intValue());
    }

    /** 트리거 컨트롤러와 공유하는 크기 검증. */
    public static boolean isSupported(long gridSz) {
        return ALL_GRID_SIZES.contains((int) gridSz) && gridSz == (int) gridSz;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        if (sizeIdx >= gridSizes.size()) {
            return RepeatStatus.FINISHED;
        }
        ExecutionContext ctx = chunkContext.getStepContext().getStepExecution().getExecutionContext();
        int gridSz = gridSizes.get(sizeIdx);
        if (current == null) {
            current = new SizeStats(gridSz);
        }

        // 호출 사이 대기 — VWorld 를 몰아치지 않는다(첫 호출 앞에는 두지 않는다).
        if (totalCalls > 0 && callIntervalMs > 0) {
            try {
                sleeper.sleep(callIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("구역명 미리 채우기 대기 중 인터럽트 — gridSz=" + gridSz, e);
            }
        }

        BackendZoneNmClient.Result result;
        try {
            totalCalls++;
            current.calls++;
            result = client.prefill(gridSz, limit);
        } catch (ResourceAccessException e) {
            // 일시 실패 갈래 — 굳히지 않고 그 크기만 접는다. 다음 실행이 이어서 한다.
            log.error("[zoneNmPrefill] 백엔드 응답 없음(연결 실패·타임아웃) — gridSz={} 중단, 다음 크기로 넘어갑니다."
                    + " 호출={} error={}", gridSz, current.calls, e.getClass().getSimpleName());
            return finishSize(ctx, OUTCOME_TRANSIENT);
        }

        int status = result.status();
        if (status == 200 && result.body() != null) {
            return onSuccess(contribution, ctx, gridSz, result.body());
        }
        if (status == 400) {
            throw new IllegalStateException(
                    "구역명 미리 채우기 400 — 배치가 잘못된 파라미터를 보냈습니다(gridSz=" + gridSz
                            + " limit=" + limit + "). 배치 코드·설정을 확인하세요.");
        }
        if (status == 401 || status == 403 || status == 503) {
            // 503 은 5xx 지만 "내부 API 키 미설정" 이라 다시 돌려도 같다(rptp 와 같은 정책).
            throw new IllegalStateException(
                    "구역명 미리 채우기 " + status + " — 내부 API 인증 실패 또는 백엔드 키 미설정"
                            + " (X-API-Key / INTERNAL_API_KEY 를 확인하세요). gridSz=" + gridSz);
        }
        if (status >= 500) {
            log.error("[zoneNmPrefill] 백엔드 {} — gridSz={} 중단, 다음 크기로 넘어갑니다(일시 실패 — 다음 실행이 이어서)."
                    + " 호출={}", status, gridSz, current.calls);
            return finishSize(ctx, OUTCOME_TRANSIENT);
        }
        throw new IllegalStateException(
                "구역명 미리 채우기 예상 밖 응답 — status=" + status
                        + (status == 200 ? "(본문 계약 불일치)" : "") + " gridSz=" + gridSz);
    }

    private RepeatStatus onSuccess(StepContribution contribution, ExecutionContext ctx, int gridSz,
                                   BackendZoneNmClient.PrefillResponse body) {
        current.resolved += body.resolvedCnt();
        current.transientCnt += body.transientCnt();
        current.remaining = body.remainingCnt();
        if (body.tripped()) {
            current.tripped++;
        }
        long attempted = (long) body.resolvedCnt() + body.transientCnt();
        for (long i = 0; i < attempted; i++) {
            contribution.incrementReadCount();
        }
        contribution.incrementWriteCount(body.resolvedCnt());
        contribution.incrementFilterCount(body.transientCnt());

        int remaining = body.remainingCnt();
        if (remaining <= 0) {
            log.info("[zoneNmPrefill] gridSz={} 완료 — 호출={} 저장={} 일시실패={} 차단={}",
                    gridSz, current.calls, current.resolved, current.transientCnt, current.tripped);
            return finishSize(ctx, OUTCOME_DONE);
        }
        // 🔴 진척 판정은 remainingCnt 감소 여부다 — resolvedCnt 아님(클래스 javadoc).
        if (prevRemaining != null && remaining >= prevRemaining) {
            log.error("[zoneNmPrefill] gridSz={} 진척 없음 — remaining {} → {} (이번 호출 저장={} 일시실패={} 차단={})."
                    + " VWorld 장애·키 없음 의심. 이 크기를 중단합니다(다음 실행이 이어서).",
                    gridSz, prevRemaining, remaining, body.resolvedCnt(), body.transientCnt(), body.tripped());
            return finishSize(ctx, OUTCOME_NO_PROGRESS);
        }
        prevRemaining = remaining;
        saveStats(ctx);
        return RepeatStatus.CONTINUABLE;
    }

    /** 현재 크기를 닫고 다음 크기로 넘어간다. 남은 크기가 없으면 FINISHED. */
    private RepeatStatus finishSize(ExecutionContext ctx, String outcome) {
        current.outcome = outcome;
        saveStats(ctx);
        sizeIdx++;
        prevRemaining = null;
        current = null;
        return sizeIdx >= gridSizes.size() ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
    }

    private void saveStats(ExecutionContext ctx) {
        String p = CTX_PREFIX + current.gridSz + ".";
        ctx.putLong(p + "calls", current.calls);
        ctx.putLong(p + "resolved", current.resolved);
        ctx.putLong(p + "transient", current.transientCnt);
        ctx.putLong(p + "remaining", current.remaining);
        ctx.putLong(p + "tripped", current.tripped);
        if (current.outcome != null) {
            ctx.putString(p + "outcome", current.outcome);
        }
    }

    /** 크기 하나의 누계. {@code remaining = -1} 은 "200 응답을 한 번도 못 받았다" 는 뜻이다. */
    private static final class SizeStats {
        final int gridSz;
        long calls;
        long resolved;
        long transientCnt;
        long remaining = -1;
        long tripped;
        String outcome;

        SizeStats(int gridSz) {
            this.gridSz = gridSz;
        }
    }
}
