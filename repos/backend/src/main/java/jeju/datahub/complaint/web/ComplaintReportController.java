package jeju.datahub.complaint.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.auth.service.LoginUserVO;
import jeju.datahub.common.web.CurrentUserResolver;
import jeju.datahub.complaint.service.ComplaintReportCreateRequest;
import jeju.datahub.complaint.service.ComplaintReportService;
import jeju.datahub.complaint.service.ComplaintReportVO;
import lombok.RequiredArgsConstructor;

/**
 * 민원현황 보고서 REST 컨트롤러 — 화면 "내보내기" 의 생성 창구. (TSK-4164 Step 3 · D10)
 *
 * <p>조회 전용인 {@code ComplaintController}({@code @RequestMapping("/api/complaints")})와
 * <b>클래스를 나눈다</b> — 쓰기 경로라 트랜잭션·응답 코드(201)·검증 규약이 다르고, 조회 컨트롤러가
 * {@code ComplaintService} 하나만 주입하는 단순함을 지키기 위해서다. 클래스 매핑을
 * {@code /api} 로 두고 메서드에 {@code /complaints/reports} 를 적어 <b>{@code /api/complaints} 와
 * 매핑이 겹치지 않는다</b>(경로는 같은 지붕 아래지만 핸들러 시그니처가 달라 충돌하지 않는다).
 *
 * <p>🔴 <b>{@code @PreAuthorize} 를 걸지 않는다 — 로그인만 요구한다</b>(D10). 집중구역·생활권 생성
 * API 도 같고, 메뉴 단위 인가는 현재 <b>쓰기 9개 매핑</b>에만 걸려 있다. 화면 내보내기는 그 목록에
 * 없으므로 여기서 혼자 정책을 바꾸지 않는다.
 *
 * <p>🔴 POST 라 {@code X-XSRF-TOKEN} 헤더가 <b>필수</b>다 — 프론트는 공용 {@code api}(axios)
 * 인스턴스를 써야 하고, 전송용으로 새 인스턴스를 만들면 그 헤더가 빠져 403 이다.
 */
@Tag(name = "민원현황 보고서", description = "민원현황 화면(UI-PAR-002) 내보내기 — 보고서 생성 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ComplaintReportController {

    private final ComplaintReportService complaintReportService;

    /** 로그인 사용자 해석(401 판정은 그 한 곳이다) */
    private final CurrentUserResolver currentUser;

    /**
     * 민원현황 보고서 생성 (민원현황 화면 "내보내기").
     *
     * <p>한 번의 호출이 실행 이력({@code TB_ANLS_EXCN}) · 분석 결과({@code TB_ANLS_RSLT}) ·
     * 보고서({@code TB_RPTP}) 세 표에 <b>정확히 한 벌</b>을 남긴다(D1). 저장 규칙은 전부
     * {@link ComplaintReportService} 안에 있다.
     *
     * <p>🔴 {@code @Valid} 를 빼면 {@link ComplaintReportCreateRequest} 의 제약({@code fileFmt}
     * 화이트리스트·기간 형식)이 <b>통째로</b> 발동하지 않아, 빈 바디({@code {}})가 400 이 아니라
     * NPE(500)로 흐른다 — 이 경로를 덮는 전역 예외 핸들러가 레포에 없다.
     *
     * <p>🔴 집계 결과가 <b>0건이어도 201</b> 이다(D5) — 집중구역(0건이면 400)과 정책이 다르다.
     * "이 기간에 민원이 0건이었다" 도 보고할 값이고, 주기별 자동 생성이 붙으면 주기마다 빠짐없이
     * 1건이 쌓여야 목록에 구멍이 나지 않는다. 400 은 잘못된 요청(없는 지역 코드·역전 구간·1년
     * 초과·형식 오류)뿐이며 그때는 세 표에 아무것도 남지 않는다.
     */
    @Operation(summary = "민원현황 보고서 생성",
            description = "같은 조건으로 서버가 다시 집계해 그 결과를 보고서로 저장한다(화면이 보낸 숫자는 쓰지 않는다). "
                    + "실행 이력(TB_ANLS_EXCN) 1행 · 분석 결과(TB_ANLS_RSLT) 1행 · 보고서(TB_RPTP) 1행을 "
                    + "한 트랜잭션으로 남기고, 저장된 스냅샷을 그대로 201 로 돌려준다. "
                    + "fileFmt 는 PDF | DOCX 이며 그 밖의 값이면 400. "
                    + "🔴 집계 결과가 0건이어도 201 이다(집중구역과 다르다) — 400 은 없는 지역 코드·역전 구간·"
                    + "1년 초과·형식 오류뿐이고 그때는 아무것도 저장하지 않는다.")
    @PostMapping("/complaints/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ComplaintReportVO createReport(@AuthenticationPrincipal OidcUser user,
                                          @Valid @RequestBody ComplaintReportCreateRequest request) {
        // 🔴 서비스보다 앞 — 401 이면 아무것도 저장되지 않는다
        LoginUserVO me = currentUser.resolve(user);
        return complaintReportService.createReport(request, me.getUserId(), me.getUserNm());
    }
}
