package jeju.datahub.batch.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.ConcurrentModificationException;

/**
 * 실행 중 단계별 진행 수치 저장소 (TSK-4337 추가 — 운영 화면 "API 수집 → 지오코딩 → DB 적재" 표시).
 *
 * <p>Spring Batch 의 {@code batch_step_execution} 카운트는 <b>청크 커밋마다</b>만 갱신되고, 지오코딩 내역(캐시·API·실패)은
 * 잡이 끝날 때 {@code tb_btch_excn_hstry} 에 한 번만 남는다. 그 사이의 값은 잡 컴포넌트의 인메모리 카운터에만 있으므로,
 * 잡 리스너가 {@code beforeJob} 에서 그 카운터를 읽는 {@link Supplier} 를 등록하고 {@code afterJob} 에서 해제한다.
 *
 * <ul>
 *   <li>🔴 <b>값을 복사해 두지 않는다</b> — 조회 때마다 공급자를 부른다(폴링 2초마다 최신값).</li>
 *   <li>🔴 <b>공급자 예외가 조회를 죽이지 않는다</b> — WARN 없이 빈 결과(화면은 기존 한 줄 진행 표시로 돌아간다).</li>
 *   <li>⚠️ 이 프로세스 안의 값이다 — 배치 인스턴스 1개 전제(좀비 판정과 같다). 재기동하면 사라진다.</li>
 *   <li>공급자를 등록하지 않는 잡은 조회가 404 {@code LIVE_NOT_AVAILABLE} 이다 — 정상이다.</li>
 * </ul>
 */
@Component
public class JobLiveMetrics {

    private static final Logger log = LoggerFactory.getLogger(JobLiveMetrics.class);

    private final Map<Long, Supplier<Map<String, Object>>> suppliers = new ConcurrentHashMap<>();

    public void register(long jobExecutionId, Supplier<Map<String, Object>> supplier) {
        suppliers.put(jobExecutionId, supplier);
    }

    public void unregister(long jobExecutionId) {
        suppliers.remove(jobExecutionId);
    }

    /** 등록된 공급자가 없거나 공급자가 실패하면 empty. */
    public Optional<Map<String, Object>> snapshot(long jobExecutionId) {
        Supplier<Map<String, Object>> supplier = suppliers.get(jobExecutionId);
        if (supplier == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> values = supplier.get();
            return values == null ? Optional.empty() : Optional.of(new LinkedHashMap<>(values));
        } catch (ConcurrentModificationException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — 실행 중 카운터를 다른 스레드에서 읽는 동안의 동시 수정·상태 예외.
            log.debug("[ops] live metrics 공급 실패 jobExecutionId={} cause={}", jobExecutionId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
