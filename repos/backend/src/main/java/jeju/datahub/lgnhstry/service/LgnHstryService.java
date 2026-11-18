package jeju.datahub.lgnhstry.service;

/**
 * 로그인 이력 기록 서비스 ({@code parking.TB_LGN_HSTRY}).
 *
 * <p>🔴 구현체는 세 메서드 모두 <b>별도 트랜잭션</b>({@code REQUIRES_NEW})으로 기록한다 —
 * 호출부(로그인/로그아웃) 트랜잭션이 롤백돼도 시도 이력은 남아야 한다.
 *
 * <p>세션 해시는 반드시 {@link jeju.datahub.common.SesnIdHasher#shortHash(String)} 로 만든 값을 넘긴다.
 */
public interface LgnHstryService {

    /**
     * 로그인 성공 기록. 반환값은 쓰지 않는다(호출부가 결과에 의존하지 않게).
     *
     * <p>🔴 {@code sesnIdHash} 가 null·공백·{@code "-"} 면 <b>그 값을 버리고 {@code sesn_id = NULL} 로
     * 기록한 뒤 WARN</b> — 행 자체를 버리지 않는다(성공 로그인이 이력에서 통째로 사라지면 안 된다).
     * 그런 값을 그대로 저장하면 여러 행이 한 값으로 뭉쳐 {@link #closeSession(String)} 이 남의 행을 닫는다.
     * {@code sesn_id} 가 NULL 인 행은 CHECK 가 허용하고, {@code closeSession} 대상에서만 빠진다.
     */
    void recordSuccess(String userId, String ipAddr, String sesnIdHash);

    /** 로그인 실패 기록. userId 는 신원 확인 전이면 null (sesn_id 는 남기지 않는다). */
    void recordFailure(String userId, String ipAddr, String failRsn);

    /**
     * 접속 종료 시각 보정 — 해당 세션의 가장 최근 성공 행을 채운다.
     *
     * <p>🔴 {@code sesnIdHash} 가 null·공백·{@code "-"} 면 <b>no-op(WARN)</b> — 조회 키로 쓰지 않는다.
     */
    void closeSession(String sesnIdHash);
}
