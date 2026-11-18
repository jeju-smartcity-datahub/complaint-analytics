package jeju.datahub.report.service;

/**
 * AI 보고서 등록·스냅샷 서비스.
 *
 * <p>🔴 <b>멱등은 두 메서드로 나눠 컨트롤러가 조립한다</b> — {@link #findRegistered} 선조회 →
 * 없으면 {@link #register} → UNIQUE 위반({@code DataIntegrityViolationException})이면 <b>새 트랜잭션</b>의
 * {@link #findRegistered} 재조회. catch 를 {@code @Transactional} 메서드 안에 두면 트랜잭션이
 * rollback-only(PostgreSQL 은 aborted)가 되어 재조회가 실패하므로, 각 메서드가 자기 트랜잭션을 갖고
 * catch 는 트랜잭션 바깥(호출 계층)에 둔다.
 *
 * <p>서비스는 인증 기술을 모르고 {@code userId}·{@code userNm} 문자열만 받는다.
 */
public interface AiReportService {

    /**
     * 같은 {@code (userId, sesnId, msgId, rptSeq)} 로 이미 등록된 보고서. 없으면 {@code null}.
     * 결과의 {@code created} 는 {@code false} 다.
     */
    AiReportCrtVO findRegistered(String userId, String sesnId, String msgId, int rptSeq);

    /**
     * {@code TB_RPTP}(AI) + {@code TB_AI_RPTP_CN} 을 한 트랜잭션에 넣는다. 본문은 INSERT 직전에 새니타이즈한다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 멱등 키 UNIQUE 위반(동시 등록)
     */
    AiReportCrtVO register(String userId, String userNm, String sesnId, String msgId, int rptSeq,
                           String title, String html);

    /**
     * 저장된 본문 스냅샷. 본문이 없으면 404. 🔴 소유 판정은 하지 않는다 — 컨트롤러가 호출 직전에
     * {@link ReportOwnershipGuard#check} 를 부른다.
     */
    AiReportSnapshotVO selectReportSnapshot(Long rptpId);
}
