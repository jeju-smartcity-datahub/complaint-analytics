package jeju.datahub.lgnhstry.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jeju.datahub.lgnhstry.service.LgnHstryService;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 이력 기록 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속. (TSK-4104 Step 5)
 *
 * <h2>🔴 D1 — 별도 트랜잭션</h2>
 * 세 메서드 <b>모두</b> {@code @Transactional(propagation = REQUIRES_NEW)} 다. 하나라도 빠지면
 * 호출부(로그인/로그아웃) 트랜잭션에 합류해 <b>롤백 시 이력이 함께 사라진다</b> — 정작 기록이
 * 필요한 것은 실패한 시도다. (배치 실행 이력이 별도 트랜잭션으로 커밋하는 것과 같은 취지)
 *
 * <h2>🔴 실패 사유는 마스킹한다</h2>
 * 예외 메시지를 그대로 넣으면 토큰·URL·claim 조각이 이력 표로 샌다
 * ({@code rule_batch-project.md} — "실패 메시지는 마스킹 + 길이 제한"). {@link #mask(String)} 가
 * <b>사유 코드 8종</b>(TSK-4134 가 {@code PASSWORD_MISMATCH}·{@code ATTEMPT_BLOCKED} 를 더했다)으로
 * 접고 200자로 자른다.
 */
@Service
@RequiredArgsConstructor
public class LgnHstryServiceImpl extends EgovAbstractServiceImpl implements LgnHstryService {

    private static final Logger log = LoggerFactory.getLogger(LgnHstryServiceImpl.class);

    /** {@code lgn_fail_rsn} 최대 길이 — 컬럼이 VARCHAR(200) 이다. */
    private static final int FAIL_RSN_MAX_LEN = 200;

    /**
     * 허용 사유 코드. 이 목록에 없는 문자열은 전부 {@code UNKNOWN} 으로 접는다 —
     * <b>화이트리스트</b>라야 새 예외 메시지가 원문 그대로 새지 않는다.
     * (순서 있는 List 인 이유: 부분 일치 스캔을 결정적으로 만들기 위함)
     */
    private static final List<String> FAIL_CODES = List.of(
            "STATE_MISMATCH", "SUB_MISMATCH", "USERINFO_EMPTY",
            "USER_DISABLED", "TOKEN_EXCHANGE_FAILED",
            // 🔴 TSK-4134 — 이메일/비밀번호 경로의 사유 2종. 여기 없으면 전부 UNKNOWN 으로 접혀
            //    "비밀번호 오류"와 "그 밖의 장애"가 이력에서 구분되지 않는다.
            //    철자는 DhubPasswordAuthClient.PasswordLoginException.Reason 과 같아야 한다.
            "PASSWORD_MISMATCH", "ATTEMPT_BLOCKED",
            "UNKNOWN");

    /** 세션 해시가 아닌 값(MDC 표시용 기본값). 조회 키로 쓰면 여러 행이 한 값으로 뭉친다. */
    private static final String SESN_SENTINEL = "-";

    private final LgnHstryRepository lgnHstryRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String userId, String ipAddr, String sesnIdHash) {
        String sesnId = normalizeSesnIdHash(sesnIdHash);
        if (sesnId == null) {
            // 🔴 행을 버리지 않는다 — 성공 로그인이 이력에서 통째로 사라지면 안 된다(D1).
            //    sesn_id 만 NULL 로 두면 closeSession 대상에서만 빠진다.
            log.warn("[lgn-hstry] 세션 해시가 유효하지 않아 sesn_id 를 NULL 로 기록합니다 (userId={}, 입력='{}')",
                    userId, sesnIdHash);
        }
        lgnHstryRepository.save(LgnHstry.success(userId, ipAddr, sesnId));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String userId, String ipAddr, String failRsn) {
        lgnHstryRepository.save(LgnHstry.failure(userId, ipAddr, mask(failRsn)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeSession(String sesnIdHash) {
        String sesnId = normalizeSesnIdHash(sesnIdHash);
        if (sesnId == null) {
            log.warn("[lgn-hstry] 세션 해시가 유효하지 않아 접속 종료 보정을 건너뜁니다 (입력='{}')", sesnIdHash);
            return;   // 🔴 no-op — 조회 키로 쓰면 남의 행을 닫는다
        }
        // 🔴 가장 최근 성공 행 1건만 채운다. PostgreSQL UPDATE 에는 ORDER BY/LIMIT 가 없어
        //    대상 1건을 먼저 고르고 변경 감지로 갱신한다(쓰기=JPA 규약 유지).
        lgnHstryRepository.findFirstBySesnIdAndLgnYnAndCntnEndDtIsNullOrderByLgnDtDesc(sesnId, "Y")
                .ifPresentOrElse(
                        row -> row.setCntnEndDt(LocalDateTime.now()),
                        () -> log.debug("[lgn-hstry] 접속 종료 보정 대상 없음 (sesnId={})", sesnId));
    }

    // ---------------------------------------------------------------- private

    /** null·공백·센티널({@code "-"})이면 null. 그 밖에는 입력을 그대로 쓴다(해시는 호출부가 만든다). */
    private String normalizeSesnIdHash(String sesnIdHash) {
        if (sesnIdHash == null || sesnIdHash.isBlank() || SESN_SENTINEL.equals(sesnIdHash.trim())) {
            return null;
        }
        return sesnIdHash.trim();
    }

    /**
     * 실패 사유 마스킹 — 사유 코드 8종으로 접고 200자로 자른다.
     *
     * <p>🔴 예외 메시지 원문을 그대로 남기지 않는다. 코드 목록에 없는 입력은 {@code UNKNOWN} 이다.
     */
    private String mask(String failRsn) {
        if (failRsn == null || failRsn.isBlank()) {
            return "UNKNOWN";
        }
        String candidate = failRsn.trim().toUpperCase(Locale.ROOT);
        for (String code : FAIL_CODES) {
            if (candidate.equals(code) || candidate.contains(code)) {
                return truncate(code);
            }
        }
        return "UNKNOWN";
    }

    /** 컬럼 길이(200) 방어. 사유 코드는 이미 짧지만 코드 목록이 늘어도 INSERT 가 깨지지 않게 둔다. */
    private String truncate(String value) {
        return value.length() <= FAIL_RSN_MAX_LEN ? value : value.substring(0, FAIL_RSN_MAX_LEN);
    }
}
