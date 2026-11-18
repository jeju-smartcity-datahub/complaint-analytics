package jeju.datahub.policysim.service;

import java.util.List;

import jeju.datahub.common.PageResponse;
import jeju.datahub.policysim.service.impl.PolicySimCalcStts;

/**
 * 정책 시뮬레이션 서비스 (TSK-4037 Step 5 주차장 확충 · TSK-4038 Step 4 이면도로 정비).
 */
public interface PolicySimService {

    /**
     * 계산만 한다 — 저장하지 않는다.
     *
     * @throws PolicySimParamMissingException 필수 계수 누락 (503)
     */
    PolicySimResultVO simulate(PolicySimRequest request);

    /**
     * 같은 조건으로 <b>서버가 재계산</b>해 {@code TB_ANLS_EXCN} 1행 + {@code TB_PLCY_SMLT_RSLT} 1행을
     * 한 트랜잭션에 저장한다. 화면이 보낸 숫자는 쓰지 않는다.
     * 계산 결과가 {@link #isBlocked 차단 상태}면 <b>저장하지 않고</b> 결과만 돌려준다(TSK-4226).
     *
     * @param excutrId 실행자 — 로그인 사용자의 {@code TB_USER.user_id}(컨트롤러가 {@code cert_id}=sub 로 조회해 넘긴다)
     * @throws PolicySimParamMissingException 필수 계수 누락 (503)
     */
    PolicySimResultVO save(PolicySimRequest request, String excutrId);

    /**
     * 저장하지 않는 차단 상태인가 — {@code NO_ENF}(단속기록 없음)·{@code NO_SPLY}(공영주차장 없음) (TSK-4226 Step 1 · D1).
     *
     * <p>{@link #save} 는 이 상태면 두 표 어디에도 쓰지 않고 결과만 돌려주며, 컨트롤러는 같은 판정으로 200/201 을 가른다.
     * 🔴 인터페이스 static 이라 구현 클래스에 상속되지 않는다 — 항상 {@code PolicySimService.isBlocked(...)} 로 부른다.
     */
    static boolean isBlocked(String calcSttsCd) {
        return PolicySimCalcStts.NO_ENF.name().equals(calcSttsCd)
                || PolicySimCalcStts.NO_SPLY.name().equals(calcSttsCd);
    }

    /**
     * 내 저장 결과 목록 — {@code excutr_id = userId} 인 {@code POLICY_SIM} 실행만, 최신순 페이지 (TSK-4037 Step 7-4).
     * 저장 당시 값을 그대로 돌려준다(재계산하지 않는다).
     *
     * @param userId 로그인 사용자의 {@code TB_USER.user_id} (컨트롤러가 정한다)
     * @param page   0-base
     * @param size   1 ~ 100 (검증은 컨트롤러)
     */
    PageResponse<PolicySimSavedResultVO> selectSavedResults(String userId, int page, int size);

    /**
     * 내 저장 결과 중 {@code rsltIds} 에 든 것 — 저장 당시 값 그대로(목록과 같은 변환) (TSK-4229 Step 3 · D4).
     *
     * <p>소유 조건은 {@link #selectSavedResults} 와 같다. 🔴 <b>순서를 보장하지 않고</b>, 없는 것·남의 것은 결과에서
     * 빠진다 — 요청 순서 정렬과 404 판정은 호출부가 한다. 빈 목록·null 이면 빈 목록.
     *
     * @param userId 로그인 사용자의 {@code TB_USER.user_id}
     */
    List<PolicySimSavedResultVO> selectSavedResultsByIds(String userId, List<Long> rsltIds);

    /**
     * 내 결과 행 + 그 실행 행을 한 트랜잭션에 지운다 (TSK-4037 Step 7-4).
     *
     * @throws org.springframework.web.server.ResponseStatusException 404 — 없거나, {@code POLICY_SIM} 이 아니거나,
     *         남의 것(존재 여부를 드러내지 않는다)
     */
    void deleteSavedResult(String userId, Long rsltId);
}
