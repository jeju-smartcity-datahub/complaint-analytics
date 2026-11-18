package jeju.datahub.policysim.web;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.auth.service.LoginUserVO;
import jeju.datahub.common.PageResponse;
import jeju.datahub.common.web.CurrentUserResolver;
import jeju.datahub.policysim.service.PolicySimReportCreateRequest;
import jeju.datahub.policysim.service.PolicySimReportService;
import jeju.datahub.policysim.service.PolicySimReportVO;
import jeju.datahub.policysim.service.PolicySimRequest;
import jeju.datahub.policysim.service.PolicySimResultVO;
import jeju.datahub.policysim.service.PolicySimSavedResultVO;
import jeju.datahub.policysim.service.PolicySimService;
import lombok.RequiredArgsConstructor;

/**
 * 정책 시뮬레이션 REST 컨트롤러 — 주차장 확충 (TSK-4037 Step 5) · 이면도로 정비 (TSK-4038 Step 4).
 *
 * <p>{@code /api/analysis} 를 집중구역·생활권 컨트롤러와 공유하지만 하위 경로({@code /policy-sim})가 갈린다.
 * 잘못된 입력은 바인딩 검증({@code @Valid} → 400)과 서비스의 {@code ResponseStatusException}(400) 두 겹이고,
 * 계수 누락만 {@link PolicySimExceptionHandler} 가 503 으로 옮긴다.
 *
 * <p>🔴 POST·DELETE 는 CSRF 대상이다 — 프론트는 공용 {@code api} 인스턴스로 호출한다({@code X-XSRF-TOKEN}).
 */
@Tag(name = "정책 시뮬레이션", description = "주차장 확충·이면도로 정비 정책의 민원·주차 혼잡 효과 예측")
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class PolicySimController {

    /** 페이지 크기 상한 — 기존 페이징 API(ReportController·NotiReceiverController)와 같다 */
    private static final int MAX_PAGE_SIZE = 100;

    private final PolicySimService policySimService;
    /** 로그인 사용자 해석 — 401 판정은 그쪽 한 곳이다. POST 저장·GET 목록·DELETE 가 함께 쓴다 */
    private final CurrentUserResolver currentUser;
    /**
     * 시나리오 비교 보고서 생성 (TSK-4229 Step 4). 🔴 이 컨트롤러에 둔다 — {@link PolicySimExceptionHandler} 가
     * {@code assignableTypes = PolicySimController.class} 라 다른 컨트롤러면 계수 누락이 503 이 아니라 500 이다.
     */
    private final PolicySimReportService policySimReportService;

    /**
     * 🔴 GET 쿼리로 {@code vrblVls} 를 받지 않는다 (TSK-4332 D9). {@link PolicySimRequest} 가 GET·POST 공용이라
     * 막지 않으면 {@code vrblVls[KEY]=} 바인딩이 열려 500 이 될 수 있다(TSK-4325 Step 5 선례). 금지 필드는 바인더가 조용히 버린다.
     * 🔴 {@code @RequestBody} JSON(POST 저장·보고서)은 이 바인더를 타지 않는다. 이름은 {@code @ParameterObject} 모델 속성 기본 이름이다.
     */
    @InitBinder("policySimRequest")
    void disallowVrblVls(WebDataBinder binder) {
        binder.setDisallowedFields("vrblVls*");
    }

    /** 계산만 한다 — 저장하지 않는다 */
    @Operation(summary = "정책 시뮬레이션 계산 (주차장 확충 · 이면도로 정비 · 요금제 적용)",
            description = "plcyType=EXPAND 의 확충 방식(VACLOT|MULTFLR|NEW)·면수(50|80|100|150|200)·예측기간(3|6|12|24개월)으로 "
                    + "조회 범위(sggNm·emdCd — 생략 = 제주 전체)의 기준선(C0·E0·H0)과 확충 후 값(R·I·C1·H1)·비용·등급을 계산한다. "
                    + "plcyType=REPAIR 는 정비 방식(roadMtncType RSDNC|CMRC)·연장(roadMtncLen 1~10000m)·예측기간(필수, 계산에는 미사용)으로 "
                    + "환산 확보 면수(vldAddPkltSlot)와 인접 읍면동 풍선효과(adjcCvlcptCnt·adjcEmdCnt)를 포함한 순효과 R 을 계산한다 "
                    + "(혼잡 지수는 대상만 · cstAmt null · cstLvlCd TBD). "
                    + "plcyType=FEE 는 적용 방식(feeAplcnMthd RAISE|CONVERT)·급지(feeAplcnZone ALL|Z1|Z2, 생략 ALL, emdCd 지정 시 무시)·"
                    + "인상률(feeIncrsRt 10|20|30, RAISE 만)·예측기간(필수, 계산에는 미사용)으로 이탈(lvngVhclCnt)·흡수(absrpCnt)·전이(trnsfCnt)와 "
                    + "인접 전이를 포함한 순효과 R(양수 = 민원 증가)·민감도 범위(cvlcptRdctRtRng)를 계산한다(cstAmt null · cstLvlCd TBD). "
                    + "유형별 필수 필드가 빠지면 400. "
                    + "응답에 적용 계수·기준 구간(aplcnParam)을 함께 담아 결과를 재현할 수 있다. "
                    + "혼잡도는 주차 혼잡이며 도로 통행량이 아니다. 목록 밖 값·없는 읍면동은 400, "
                    + "POLICY_SIM 계수 행 누락은 503 {\"code\":\"POLICY_SIM_PARAM_MISSING\"} (REPAIR·FEE 는 각 전용 4계수까지 17종 확인).")
    @GetMapping("/policy-sim")
    public PolicySimResultVO simulate(@ParameterObject @Valid PolicySimRequest request) {
        return policySimService.simulate(request);
    }

    /** 같은 조건으로 서버가 재계산해 저장 → 201 · 차단 상태(NO_ENF·NO_SPLY)면 저장하지 않고 200 (TSK-4226) */
    @Operation(summary = "정책 시뮬레이션 결과 저장",
            description = "요청 조건으로 서버가 다시 계산해 TB_ANLS_EXCN(anls_type_cd=POLICY_SIM) 1행과 TB_PLCY_SMLT_RSLT 1행을 "
                    + "한 트랜잭션에 저장하고 201 로 계산 결과를 돌려준다. 화면이 계산한 숫자는 받지 않는다. "
                    + "실행자(excutr_id)는 로그인 사용자의 user_id 다(cert_id=sub 로 TB_USER 조회). "
                    + "calcSttsCd 가 NO_ENF·NO_SPLY 면 저장하지 않고 200 으로 계산 결과만 돌려준다. "
                    + "익명·사용자 없음(정지 포함)은 401. CSRF 헤더(X-XSRF-TOKEN)가 없으면 403. 그 밖의 오류 규칙은 GET 과 같다.")
    @PostMapping("/policy-sim/results")
    public ResponseEntity<PolicySimResultVO> save(@AuthenticationPrincipal OidcUser user,
                                                  @Valid @RequestBody PolicySimRequest request) {
        PolicySimResultVO vo = policySimService.save(request, currentUser.resolveUserId(user));
        HttpStatus status = PolicySimService.isBlocked(vo.getCalcSttsCd()) ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(vo);
    }

    /** 내 저장 결과 목록 (TSK-4037 Step 7-4) — 사용자 ID 는 받지 않는다(세션이 정한다) */
    @Operation(summary = "내 정책 시뮬레이션 저장 결과 목록",
            description = "로그인 사용자가 저장한 정책 시뮬레이션 결과를 저장 시각 최신순으로 페이징 조회한다. "
                    + "각 행은 계산 응답 필드 전부 + rsltId·excnDt + 요청 조건(plcyType·expandMethod·addPkltSlot·roadMtncType·roadMtncLen·predcMm·sggNm·emdCd)이며 "
                    + "저장 당시 값 그대로다(재계산하지 않는다 — cstAmt 만 addPkltSlot × 저장된 단가로 다시 만든다 · 이면도로는 null · adjcEmdCnt 는 저장하지 않아 null). "
                    + "page 는 0-base(기본 0), size 는 기본 10·최대 100. 범위를 벗어나면 400. 익명·사용자 없음은 401.")
    @GetMapping("/policy-sim/results")
    public PageResponse<PolicySimSavedResultVO> savedResults(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        String userId = currentUser.resolveUserId(user);
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 는 0 이상이어야 합니다: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size 는 1 ~ " + MAX_PAGE_SIZE + " 범위여야 합니다: " + size);
        }
        return policySimService.selectSavedResults(userId, page, size);
    }

    /** 내 결과 행 + 그 실행 행 삭제 → 204 (TSK-4037 Step 7-4) */
    @Operation(summary = "내 정책 시뮬레이션 저장 결과 삭제",
            description = "TB_PLCY_SMLT_RSLT 1행과 연결된 TB_ANLS_EXCN 1행을 한 트랜잭션에 지운다(204). "
                    + "없거나 남의 것(또는 POLICY_SIM 이 아닌 실행)이면 404 — 존재 여부를 드러내지 않는다. "
                    + "익명·사용자 없음은 401. CSRF 헤더(X-XSRF-TOKEN)가 없으면 403.")
    @DeleteMapping("/policy-sim/results/{rsltId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSavedResult(@AuthenticationPrincipal OidcUser user,
                                  @PathVariable("rsltId") Long rsltId) {
        policySimService.deleteSavedResult(currentUser.resolveUserId(user), rsltId);
    }

    /** 시나리오 비교 보고서 생성 → 201 (TSK-4229 Step 4) — 생성자는 세션 사용자다 */
    @Operation(summary = "정책 시뮬레이션 시나리오 비교 보고서 생성",
            description = "scenarios(1~15건, 화면 순서 그대로)를 한 보고서로 묶어 TB_ANLS_EXCN(anls_type_cd=POLICY_SIM) 1행 · "
                    + "TB_ANLS_RSLT 1행 · TB_RPTP 1행(MANUAL)을 한 트랜잭션에 저장하고 201 로 스냅샷을 돌려준다"
                    + "(GET /api/reports/{rptpId}/policy-sim 과 같은 모양). 각 항목은 rsltId(저장분 — 저장값 그대로) 또는 "
                    + "cond(미저장분 — 서버 재계산) 중 정확히 하나다. 화면이 계산한 숫자는 받지 않는다. "
                    + "headIdx 는 scenarios 의 0-base 인덱스(대표 시나리오), fileFmt 는 PDF | DOCX. "
                    + "생성자(creatr_id·excutr_id)는 로그인 사용자다. "
                    + "익명·사용자 없음(정지 포함)은 401 — 아무것도 저장하지 않는다. CSRF 헤더(X-XSRF-TOKEN)가 없으면 403. "
                    + "fileFmt 누락·오타 · scenarios 0건·16건 이상 · headIdx 범위 밖 · 한 항목에 rsltId 와 cond 가 둘 다 있음/둘 다 없음 · "
                    + "cond 조건 검증 실패 · 같은 rsltId 중복 · 미저장분 재계산이 NO_ENF·NO_SPLY 면 400. "
                    + "rsltId 가 없거나 남의 것이면 404(존재 여부를 드러내지 않는다). "
                    + "POLICY_SIM 계수 행 누락은 503 {\"code\":\"POLICY_SIM_PARAM_MISSING\"}.")
    @PostMapping("/policy-sim/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicySimReportVO createReport(@AuthenticationPrincipal OidcUser user,
                                          @Valid @RequestBody PolicySimReportCreateRequest request) {
        LoginUserVO me = currentUser.resolve(user);                // 🔴 익명·사용자 없음·정지 → 401
        return policySimReportService.createReport(request, me.getUserId(), me.getUserNm());
    }
}
