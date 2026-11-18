package jeju.datahub.policysim.service;

/**
 * 정책 시뮬레이션 시나리오 비교 보고서 서비스 (TSK-4229).
 *
 * <p>이 서비스는 인증 기술을 모른다 — 로그인 사용자 해석과 401 은 컨트롤러가 서비스보다 앞에서 끝낸다(D7).
 */
public interface PolicySimReportService {

    /**
     * 보고서를 만든다 — {@code TB_ANLS_EXCN} 1행 + {@code TB_ANLS_RSLT} 1행 + {@code TB_RPTP} 1행({@code MANUAL})을
     * 한 트랜잭션에 남긴다(D1). {@code TB_PLCY_SMLT_RSLT} 에는 쓰지 않는다(D3).
     *
     * <p>시나리오 값은 저장분 = 저장값 그대로, 미저장분 = 서버 재계산이다(D4 — 화면 숫자를 받지 않는다).
     *
     * @param userId 실행자·생성자 — 로그인 사용자의 {@code TB_USER.user_id}. null 이면 {@link IllegalArgumentException}
     * @param userNm 생성자명 — 50자로 잘라 저장한다
     * @throws org.springframework.web.server.ResponseStatusException 400 — {@code headIdx} 범위 밖 · 같은 {@code rsltId} 중복 ·
     *         미저장분 재계산이 {@code NO_ENF}/{@code NO_SPLY} · 조건 검증 실패 / 404 — {@code rsltId} 가 없거나 남의 것
     * @throws PolicySimParamMissingException 필수 계수 누락 (503)
     */
    PolicySimReportVO createReport(PolicySimReportCreateRequest req, String userId, String userNm);

    /**
     * 보고서 스냅샷 — 저장 시점 내용({@code rslt_json})을 그대로 되살린다. 다시 계산하지 않는다 (TSK-4229 Step 4).
     *
     * <p>🔴 {@code userId} 를 받지 않는다 — 소유 확인(남의 것 404)은 컨트롤러가 이 호출 직전에
     * {@code ReportOwnershipGuard} 로 한다(D8). 다른 스냅샷 서비스와 같은 시그니처다.
     *
     * @throws org.springframework.web.server.ResponseStatusException 404 — 보고서 없음 · {@code POLICY_SIM} 실행이 아님 ·
     *         결과 행 없음 / 500 — 저장된 JSON 역직렬화 실패
     */
    PolicySimReportVO selectReportSnapshot(Long rptpId);
}
