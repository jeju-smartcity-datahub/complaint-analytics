package jeju.datahub.analysis.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.analysis.service.AnalysisTypeWeightVO;
import jeju.datahub.analysis.service.AnalysisWeightHstryVO;
import jeju.datahub.analysis.service.AnalysisWeightSaveRequest;
import jeju.datahub.analysis.service.AnalysisWeightService;
import jeju.datahub.auth.service.LoginUserVO;
import jeju.datahub.common.web.CurrentUserResolver;
import lombok.RequiredArgsConstructor;

/**
 * 분석 변수 설정 REST 컨트롤러. (설정 화면 "분석 변수 설정" 탭)
 *
 * <p>표는 {@code parking.TB_ANLS_VRBL_STNG}(변경 이력 {@code TB_ANLS_VRBL_HSTRY}) 다 —
 * 종전 지표 가중치 표에서 개명됐다(마이그레이션 67 — 옛 이름은 그 SQL 이 정본이다). 표가 가중치({@code WGVL})
 * 뿐 아니라 계수({@code CFC})까지 담게 돼 "지표 가중치" 가 아니라 "변수 설정" 이다.
 *
 * <p>🔴 <b>그럼에도 URL 은 {@code /api/analysis-weights} 그대로 둔다</b>.
 * 바꾸면 {@code SecurityConfig}·{@code @PreAuthorize('M0603')}·프론트 라우팅이 함께 움직여
 * 회귀 면이 넓어진다 — 표 이름과 URL 이 어긋나는 것은 감수하고 문서에 사유를 남긴다.
 *
 * <p>전역 예외 핸들러가 없으므로 잘못된 입력은 {@link ResponseStatusException} 으로 직접 400 을 낸다
 * (메뉴 권한 · 보고서 컨트롤러와 동일 방침).
 *
 * <p>⚠️ <b>쿼리 파라미터에 {@code @Min}/{@code @Max} + {@code @Validated} 를 쓰지 않는다.</b>
 * 이 저장소에는 agent 도메인 한정 advice 1건({@code assignableTypes} 로 격리)을 빼면
 * {@code @ControllerAdvice}/{@code @ExceptionHandler} 가 없어,
 * 클래스에 {@code @Validated} 를 붙이면 Spring 6.1+ 의 내장 메서드 검증이 비활성화되고 AOP 가 던진
 * {@code ConstraintViolationException} 이 처리기 없이 <b>500</b> 으로 나간다. 수동 검증이 정답이다.
 *
 * <p>{@code AnalysisWeightService#selectEffectiveWeights(String)} 는 점수 산출용 내부 계약이라
 * 여기서 <b>노출하지 않는다</b> — 집중구역 분석이 서비스를 직접 주입해 쓴다.
 */
@Tag(name = "분석 변수 설정",
        description = "분석유형별 변수(가중치 WGVL · 계수 CFC) 조회·저장 + 변경 이력 "
                + "(TB_ANLS_VRBL_STNG · TB_ANLS_VRBL_HSTRY). URL 은 옛 이름을 유지한다")
@RestController
@RequestMapping("/api/analysis-weights")
@RequiredArgsConstructor
public class AnalysisWeightController {

    /** 이력 조회 최대 건수. 초과 요청은 400 (이 경로를 덮는 @ControllerAdvice 가 없어 수동 검증한다). */
    private static final int MAX_HISTORY_SIZE = 100;

    private final AnalysisWeightService analysisWeightService;
    private final CurrentUserResolver currentUser;

    @Operation(summary = "분석유형별 변수 목록",
            description = "변수가 등재된 분석유형(5종)을 표시 순서로 조회한다. 각 분석유형에 변수 목록"
                    + "(변수구분 vrblSeCd · 현재값 stngVl · 기본값 bscStngVl · 허용범위 minVl~maxVl · "
                    + "단위 unitNm · 사용여부 useYn)과 합계 stngVlSum 을 함께 내린다. "
                    + "stngVlSum 은 가중치(vrblSeCd='WGVL') 이면서 사용중(useYn='Y')인 행만 더한 값이라 "
                    + "계수(CFC)는 합계에 섞이지 않는다.")
    @GetMapping
    public List<AnalysisTypeWeightVO> weights() {
        return analysisWeightService.selectAnalysisTypeWeights();
    }

    /**
     * 분석유형 1건의 가중치 전체 교체 저장.
     *
     * <p>{@code @Valid} 가 없으면 {@link AnalysisWeightSaveRequest} 의 {@code @NotNull} 이 발동하지 않아
     * 바디 누락({@code {}})이 400 이 아니라 NPE 로 흐른다 — 반드시 함께 둔다.
     */
    @Operation(summary = "변수 설정값 저장(분석유형 1건 전체 교체)",
            description = "해당 분석유형의 변수 설정값(stngVl)을 요청 값으로 전체 교체한다(한 트랜잭션, 멱등). "
                    + "가중치(WGVL·useYn='Y') 행의 합계가 100 이 아니거나, 변수 집합이 어긋나거나, "
                    + "행별 허용범위(minVl~maxVl)를 벗어나면 400, 없는 분석유형은 404. "
                    + "계수(CFC)는 합계 검증 대상이 아니며 범위 검증만 받는다. "
                    + "값이 실제로 바뀐 경우에만 변경 이력 1행이 쌓인다. 응답은 갱신 후의 분석유형 1건이다.")
    /**
     * 🔴 <b>표 파생 인가</b>. 롤 리터럴을 쓰지 않는다.
     * 요청자가 M0603(분석 지표 가중치 설정) 매핑을 갖고 있어야 한다 — 화면과 같은 표
     * ({@code TB_AUTHRT_MENU})를 매 요청 조회하므로 권한을 저장한 즉시 반영된다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0603')")
    @PutMapping("/{anlsTypeCd}")
    public AnalysisTypeWeightVO save(@AuthenticationPrincipal OidcUser user,
                                     @PathVariable("anlsTypeCd") String anlsTypeCd,
                                     @Valid @RequestBody AnalysisWeightSaveRequest request) {
        // 🔴 서비스보다 앞 — 변경자는 로그인 사용자다(고정값 'system' 폐기). 401 이면 아무것도 저장되지 않는다
        LoginUserVO me = currentUser.resolve(user);
        return analysisWeightService.saveWeights(anlsTypeCd, request, me.getUserId(), me.getUserNm());
    }

    /**
     * 설정 변경 이력 조회.
     *
     * <p>🔴 <b>{@code anlsTypeCd} 로 거르는 일은 SQL 이 한다</b>
     * (매퍼의 {@code WHERE} 가 {@code LIMIT} 보다 앞). 여기서 목록을 받아 걸러 내거나 프론트에서
     * 거르면 <b>자른 뒤 거르는</b> 순서가 되어, 한 분석유형을 {@code size} 번 저장했을 때 다른
     * 분석유형의 이력이 실제로는 있는데도 0건으로 보인다.
     *
     * <p>없는 분석유형 코드는 <b>400 이 아니라 빈 배열 200</b> 이다 — 이력이 아직 없는 유형과
     * 구분할 실익이 없고, 화면은 두 경우 모두 "변경 이력이 없습니다" 로 그린다.
     * 빈 문자열({@code ?anlsTypeCd=})은 미지정과 같게 취급한다(전 유형).
     */
    @Operation(summary = "설정 변경 이력",
            description = "변수 설정 변경 이력(TB_ANLS_VRBL_HSTRY)을 최신순으로 조회한다. "
                    + "size 기본 20 · 최대 100. 범위를 벗어나면 400. "
                    + "anlsTypeCd(선택)를 주면 그 분석유형의 이력만 내린다 — 필터가 size 보다 "
                    + "먼저 걸리므로 다른 유형을 많이 저장해도 이력이 밀려나지 않는다. "
                    + "생략하거나 빈 값이면 전 분석유형을 섞어 최신순으로 내린다. "
                    + "없는 분석유형 코드는 400 이 아니라 빈 배열이다. "
                    + "되돌리기·감사용 스냅샷 JSON 은 응답에 싣지 않는다.")
    @GetMapping("/histories")
    public List<AnalysisWeightHstryVO> histories(
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "anlsTypeCd", required = false) String anlsTypeCd) {
        if (size < 1 || size > MAX_HISTORY_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size 는 1 ~ " + MAX_HISTORY_SIZE + " 범위여야 합니다: " + size);
        }
        // 공백만 온 경우도 미지정으로 본다 — 매퍼의 <if test="anlsTypeCd != null"> 기준을 맞춘다.
        String typeCd = (anlsTypeCd == null || anlsTypeCd.isBlank()) ? null : anlsTypeCd.trim();
        return analysisWeightService.selectHistories(size, typeCd);
    }
}
