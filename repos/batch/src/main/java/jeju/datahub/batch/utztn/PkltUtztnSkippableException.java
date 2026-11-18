package jeju.datahub.batch.utztn;

/**
 * 주차장 해석 확정 실패 — <b>그 행만</b> 스킵한다 (TSK-4189 Step 7).
 *
 * <p>사유는 화이트리스트 3종이다 — {@code ADDR_NM_BOTH_MISS}(1·2단 모두 실패) ·
 * {@code NM_AMBIGUOUS}(2단 후보 여럿) · {@code DUP_TARGET}(다른 원천 주차장이 이미 그 {@code pklt_id} 점유).
 * 🔴 예외 메시지 원문을 감사 표에 넣지 않는다 — 사유 코드만 넣는다.
 *
 * <p>🔴 이 예외만 {@code skip()} 목록에 넣는다. API 오류·파싱 실패는 넣지 않아 Job 이 FAILED 로 끊기게 둔다.
 */
public class PkltUtztnSkippableException extends RuntimeException {

    private final String failRsn;

    public PkltUtztnSkippableException(String failRsn, String detail) {
        super(failRsn + " — " + detail);
        this.failRsn = failRsn;
    }

    public String failRsn() {
        return failRsn;
    }
}
