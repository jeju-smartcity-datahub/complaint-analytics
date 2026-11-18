package jeju.datahub.report.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import jeju.datahub.common.web.CurrentUserResolver;
import jeju.datahub.analysis.service.HotspotReportService;
import jeju.datahub.analysis.service.HotspotReportVO;
import jeju.datahub.analysis.service.InvestPrrtReportService;
import jeju.datahub.analysis.service.InvestPrrtReportVO;
import jeju.datahub.analysis.service.ZoneRcmdtnReportService;
import jeju.datahub.analysis.service.ZoneRcmdtnReportVO;
import jeju.datahub.common.PageResponse;
import jeju.datahub.complaint.service.ComplaintReportService;
import jeju.datahub.complaint.service.ComplaintReportVO;
import jeju.datahub.policysim.service.PolicySimReportService;
import jeju.datahub.policysim.service.PolicySimReportVO;
import jeju.datahub.report.service.AiReportService;
import jeju.datahub.report.service.AiReportSnapshotVO;
import jeju.datahub.report.service.ReportListFilter;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.ReportOwnershipGuard;
import jeju.datahub.report.service.ReportService;
import jeju.datahub.report.service.ReportSettingRequest;
import jeju.datahub.report.service.ReportSettingVO;
import lombok.RequiredArgsConstructor;

/**
 * 보고서 REST 컨트롤러. (보고서 목록 `/reports` · 보고서 관리 `/reports/management`)
 *
 * <p>보고서와 자동 생성 설정은 URL 접두사가 달라({@code /api/reports} · {@code /api/report-settings})
 * 클래스 매핑은 {@code /api} 로 두고 메서드에서 전체 경로를 적는다.
 *
 * <p>전역 예외 핸들러가 없으므로 잘못된 입력은 {@link ResponseStatusException} 으로 직접 400 을 낸다
 * (메뉴 권한 컨트롤러와 동일 방침).
 */
@Tag(name = "보고서", description = "보고서 목록 · 자동 생성 설정(TB_RPTP / TB_RPTP_AUTO_CRT_STNG) API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReportController {

    /** 생성구분 화이트리스트. 그 외 값은 400 — SQL 에 임의 문자열이 흘러가지 않게 컨트롤러가 끊는다 */
    private static final Set<String> CRT_TYPES = Set.of("AUTO", "MANUAL", "AI");

    /** 페이지 크기 상한. 초과하면 400 */
    private static final int MAX_PAGE_SIZE = 100;

    private final ReportService reportService;

    /**
     * 집중구역 보고서 스냅샷 조회.
     *
     * <p>🔴 서비스는 {@code analysis} 에 둔다 — 404 3종 판정 · 가중치 역직렬화 · 등급 판정 ·
     * {@link HotspotReportVO} 조립이 전부 집중구역 도메인 지식이고, <b>POST(생성)와 GET(조회)이
     * 같은 VO 를 만들기 때문</b>이다. 여기(보고서 표현 계층)에는 URL 만 둔다.
     * 선례: {@code DashboardPanelServiceImpl} 도 {@code jeju.datahub.analysis} 서비스를 주입해 쓴다.
     */
    private final HotspotReportService hotspotReportService;

    /**
     * 민원현황 보고서 스냅샷 조회.
     *
     * <p>🔴 서비스는 {@code complaint} 에 둔다 — 404 3종 판정 · {@code rslt_json} 역직렬화 ·
     * {@link ComplaintReportVO} 조립이 전부 민원 도메인 지식이고, <b>POST(생성)와 GET(조회)이
     * 같은 VO 를 만들기 때문</b>이다. 여기(보고서 표현 계층)에는 URL 만 둔다 — 바로 위
     * {@link #hotspotReportService} 와 같은 방향이라 새 선례가 아니다.
     */
    private final ComplaintReportService complaintReportService;

    /**
     * 투자우선순위 보고서 스냅샷 조회.
     *
     * <p>서비스는 {@code analysis} 에 둔다 — 위 {@link #hotspotReportService} 와 같은 이유·같은 방향이다
     * (404 판정 · {@code rslt_json} 복원 · VO 조립이 도메인 지식이고 POST 와 GET 이 같은 VO 를 만든다).
     */
    private final InvestPrrtReportService investPrrtReportService;

    /**
     * 구역추천 보고서 스냅샷 조회.
     *
     * <p>서비스는 {@code analysis} 에 둔다 — 위 {@link #hotspotReportService} 와 같은 이유·같은 방향이다
     * (404 판정 · 보기 판별 · {@code rslt_json} 복원 · VO 조립이 도메인 지식이고 POST 와 GET 이 같은 VO 를 만든다).
     */
    private final ZoneRcmdtnReportService zoneRcmdtnReportService;

    /**
     * 정책 시뮬레이션 시나리오 비교 보고서 스냅샷 조회.
     *
     * <p>서비스는 {@code policysim} 에 둔다 — 위 {@link #hotspotReportService} 와 같은 이유·같은 방향이다
     * (404 판정 · {@code rslt_json} 복원 · VO 조립이 도메인 지식이고 POST 와 GET 이 같은 VO 를 만든다).
     * 다른 도메인이라 인터페이스로만 부른다.
     */
    private final PolicySimReportService policySimReportService;

    /**
     * AI 보고서 스냅샷 조회.
     *
     * <p>등록은 {@link AiReportController}({@code POST /api/ai/sessions/{sessionId}/reports})가 하고, 저장된 본문을
     * 다시 여는 것은 다른 스냅샷과 같은 자리 · 같은 두 줄(해석 → 소유 가드 → 서비스)이다.
     */
    private final AiReportService aiReportService;

    /** 로그인 사용자 해석(401 판정은 그 한 곳이다) */
    private final CurrentUserResolver currentUser;

    /**
     * 보고서 소유 판정 한 곳 (요청 ①).
     *
     * <p>🔴 <b>단건·스냅샷은 서비스가 아니라 여기(컨트롤러)에서 가드를 부른다</b> —
     * {@code selectReportSnapshot(Long)} 에 {@code ownerId} 를 더하면 인터페이스 7 + 구현 7 +
     * 호출부 7 이 전부 컴파일 에러다. 서비스 시그니처는 바꾸지 않는다.
     *
     * <p>🔴 가드는 <b>서비스 호출 직전</b>에 부른다. 남의 보고서는 403 이 아니라 <b>404</b> 다.
     */
    private final ReportOwnershipGuard reportOwnershipGuard;

    /**
     * 보고서 목록 (페이징).
     *
     * <p>{@code crtType} 은 필수다 — 빠지면 Spring 이 400(MissingServletRequestParameter)을 낸다.
     * 두 탭(자동 생성 / 직접 생성)이 서로 다른 칸을 보여주기 때문에 "전체" 조회를 만들지 않는다.
     *
     * <p>목록 필터 4종({@code keyword}·{@code anlsTypeCd}·{@code fromYmd}·{@code toYmd})은
     * <b>모두 선택</b>이다(없으면 종전과 같은 결과). 정규화는 {@link ReportListFilter#of}, 값 검증(400)은
     * 서비스 한 곳이다 — 출처 코드 판정에 공통코드 조회가 필요해서다. 날짜 형식 오류는 Spring 바인딩이 400 을 낸다.
     */
    @Operation(summary = "보고서 목록",
            description = "생성구분(AUTO|MANUAL|AI)별 보고서 목록을 최신순으로 페이징 조회한다. "
                    + "page 는 0-base(기본 0), size 는 기본 10·최대 100. "
                    + "crtType 이 없거나 화이트리스트 밖이면 400, size 가 범위를 벗어나면 400. "
                    + "선택 필터(목록·총건수에 함께 적용, 모두 AND): "
                    + "keyword = 보고서명 부분 일치(대소문자 구분, 앞뒤 공백 제거, 빈 값은 미적용, 100자 초과 400) · "
                    + "anlsTypeCd = 출처(ANLS_TYPE_CD 사용중 코드, 그 외 400) · "
                    + "fromYmd·toYmd = 생성일 범위(yyyy-MM-dd, 양 끝 포함, fromYmd > toYmd 면 400).")
    @GetMapping("/reports")
    public PageResponse<ReportListVO> reports(@AuthenticationPrincipal OidcUser user,
                                              @RequestParam("crtType") String crtType,
                                              @RequestParam(name = "page", defaultValue = "0") int page,
                                              @RequestParam(name = "size", defaultValue = "10") int size,
                                              @RequestParam(name = "keyword", required = false) String keyword,
                                              @RequestParam(name = "anlsTypeCd", required = false) String anlsTypeCd,
                                              @RequestParam(name = "fromYmd", required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromYmd,
                                              @RequestParam(name = "toYmd", required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toYmd) {
        if (!CRT_TYPES.contains(crtType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 생성구분입니다: " + crtType + " (AUTO | MANUAL | AI)");
        }
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 는 0 이상이어야 합니다: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size 는 1 ~ " + MAX_PAGE_SIZE + " 범위여야 합니다: " + size);
        }
        ReportListFilter filter = ReportListFilter.of(keyword, anlsTypeCd, fromYmd, toYmd);
        // 🔴 MANUAL·AI 는 내 것만. AUTO 는 배포 부서·ALL·보고서 관리 메뉴 보유자. 미인증은 폴백이 아니라 401
        return reportService.selectReports(crtType, currentUser.resolveUserId(user), filter, page, size);
    }

    @Operation(summary = "보고서 단건", description = "보고서 1건을 조회한다(미리보기 헤더용). 없으면 404.")
    @GetMapping("/reports/{rptpId}")
    public ReportListVO report(@AuthenticationPrincipal OidcUser user,
                               @PathVariable("rptpId") Long rptpId) {
        // 🔴 남의 직접 생성 보고서는 404. 여기를 빼면 URL 로 rptpId 를 바꿔 헤더를 그대로 읽는다
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        return reportService.selectReport(rptpId);
    }

    /**
     * 직접 생성 보고서 삭제 — 하드 삭제다.
     *
     * <p>🔴 소유 가드를 서비스 호출 <b>직전</b>에 부른다 — 남의 보고서는 403 이 아니라 404.
     * 자동 생성 보고서는 가드를 통과해도 서비스가 400 으로 막는다.
     */
    @Operation(summary = "보고서 삭제",
            description = "직접 생성(MANUAL)·AI 생성 보고서 1건을 삭제한다(204). 생성자 본인만 지울 수 있고 "
                    + "없거나 남의 보고서는 404, 자동 생성(AUTO) 보고서는 400. "
                    + "묶인 분석 실행·결과는 남는다.")
    @DeleteMapping("/reports/{rptpId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReport(@AuthenticationPrincipal OidcUser user,
                             @PathVariable("rptpId") Long rptpId) {
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        reportService.deleteReport(rptpId);
    }

    /**
     * 집중구역 보고서 스냅샷 — 저장된 분석 결과를 생성 당시 그대로 돌려준다.
     *
     * <p>URL 이 {@code /api/analysis/...} 가 아니라 {@code /api/reports/{rptpId}/hotspot} 인 이유:
     * 이 자원의 식별자는 <b>보고서</b>이고, 호출자는 보고서 목록에서 한 행을 골라 들어온다.
     */
    @Operation(summary = "집중구역 보고서 스냅샷",
            description = "저장된 집중구역 분석 보고서를 생성 당시 스냅샷 그대로 조회한다(재분석하지 않는다). "
                    + "응답 모양은 생성 API(POST /api/analysis/hotspot/reports)와 같다. "
                    + "보고서가 없거나 · 집중구역 분석 보고서가 아니거나 · 저장된 결과가 0행이면 404. "
                    + "⚠ zones 의 emdNm·zoneNmSrc 는 결과 테이블에 컬럼이 없어 항상 null 이고, "
                    + "좌표는 저장 시 NUMERIC 반올림된 값이라 생성 응답과 자릿수가 다르다.")
    @GetMapping("/reports/{rptpId}/hotspot")
    public HotspotReportVO reportHotspotSnapshot(@AuthenticationPrincipal OidcUser user,
                                                 @PathVariable("rptpId") Long rptpId) {
        // 🔴 목록만 막으면 본문이 그대로 열린다. 서비스 호출 **직전**에 판정한다
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        return hotspotReportService.selectReportSnapshot(rptpId);
    }

    /**
     * 민원현황 보고서 스냅샷 — 저장된 집계 결과를 생성 당시 그대로 돌려준다.
     *
     * <p>바로 위 집중구역과 <b>같은 자리</b>다 — 이 자원의 식별자는 <b>보고서</b>이고,
     * 호출자는 보고서 목록에서 한 행을 골라 들어온다. 판정·역직렬화·조립은 도메인 서비스가 한다.
     *
     * <p>🔴 <b>0건은 404 가 아니다</b> — 집중구역은 결과가 0행이면 404 지만 민원현황은
     * {@code totalCount=0}·{@code emdCompare=[]} 인 보고서도 정상이라 200 이다.
     */
    @Operation(summary = "민원현황 보고서 스냅샷",
            description = "저장된 민원현황 보고서를 생성 당시 스냅샷 그대로 조회한다(재집계하지 않는다). "
                    + "응답 모양은 생성 API(POST /api/complaints/reports)와 같다. "
                    + "보고서가 없거나 · 민원현황 보고서가 아니거나(anlsTypeCd != CVLCPT) · "
                    + "저장된 결과가 없으면 404. "
                    + "⚠ 0건 보고서는 404 가 아니라 200 이다 — kpi.totalCount=0, emdCompare=[] 로 내려간다 "
                    + "(집중구역과 정책이 다르다).")
    @GetMapping("/reports/{rptpId}/complaint")
    public ComplaintReportVO reportComplaintSnapshot(@AuthenticationPrincipal OidcUser user,
                                                     @PathVariable("rptpId") Long rptpId) {
        // 🔴 목록만 막으면 본문이 그대로 열린다
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        return complaintReportService.selectReportSnapshot(rptpId);
    }

    /**
     * 투자우선순위 보고서 스냅샷 — 저장된 분석 내용을 저장 시점 그대로 돌려준다.
     *
     * <p>위 집중구역·민원현황과 <b>같은 자리</b>다 — 이 자원의 식별자는 <b>보고서</b>이고, 호출자는 보고서
     * 목록에서 한 행을 골라 들어온다. 판정·복원·조립은 도메인 서비스가 한다.
     */
    @Operation(summary = "투자우선순위 보고서 스냅샷",
            description = "저장된 투자우선순위 보고서를 저장 시점 스냅샷 그대로 조회한다(다시 계산하지 않는다 — "
                    + "그 뒤 가중치 설정이나 데이터가 바뀌어도 값이 같다). "
                    + "응답 모양은 생성 API(POST /api/analysis/investment-priority/reports)와 같다. "
                    + "보고서가 없거나 · 투자우선순위 보고서가 아니거나(anlsTypeCd != INVEST) · "
                    + "저장된 결과가 없으면 404.")
    @GetMapping("/reports/{rptpId}/investment-priority")
    public InvestPrrtReportVO reportInvestmentPrioritySnapshot(@AuthenticationPrincipal OidcUser user,
                                                               @PathVariable("rptpId") Long rptpId) {
        // 🔴 목록만 막으면 본문이 그대로 열린다
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        return investPrrtReportService.selectReportSnapshot(rptpId);
    }

    /**
     * 구역추천 보고서 스냅샷 — 저장된 분석 내용을 저장 시점 그대로 돌려준다.
     *
     * <p>위 스냅샷 3종과 <b>같은 자리</b>다 — 이 자원의 식별자는 <b>보고서</b>이고, 호출자는 보고서 목록에서
     * 한 행을 골라 들어온다. 판정·복원·조립은 도메인 서비스가 한다.
     *
     * <p>🔴 <b>두 보기(요금제 적용 · 공유주차제 적용)가 이 경로 하나를 같이 쓴다</b> — 둘 다 분석유형코드가
     * {@code RECOMMEND} 라 목록만 보고는 가를 수 없어, 응답 최상위 {@code rcmdtnTypeCd} 가 본문 모양을 정한다.
     */
    @Operation(summary = "구역추천 보고서 스냅샷",
            description = "저장된 구역추천 보고서를 저장 시점 스냅샷 그대로 조회한다(다시 계산하지 않는다 — "
                    + "그 뒤 분석 변수 설정이나 데이터가 바뀌어도 값이 같다). "
                    + "응답 모양은 생성 API(POST /api/analysis/zone-recommendation/reports · "
                    + "POST /api/analysis/zone-recommendation/share/reports)와 같다. "
                    + "rcmdtnTypeCd 가 FEE 면 fee, SHARE 면 share 만 채워진다(다른 쪽은 null). "
                    + "보고서가 없거나 · 구역추천 보고서가 아니거나(anlsTypeCd != RECOMMEND) · "
                    + "보기를 알 수 없거나(rcmdtnTypeCd 가 FEE·SHARE 가 아님) · 저장된 결과가 없으면 404.")
    @GetMapping("/reports/{rptpId}/zone-recommendation")
    public ZoneRcmdtnReportVO reportZoneRecommendationSnapshot(@AuthenticationPrincipal OidcUser user,
                                                               @PathVariable("rptpId") Long rptpId) {
        // 🔴 목록만 막으면 본문이 그대로 열린다
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        return zoneRcmdtnReportService.selectReportSnapshot(rptpId);
    }

    /**
     * 정책 시뮬레이션 시나리오 비교 보고서 스냅샷 — 저장된 내용을 저장 시점 그대로 돌려준다.
     *
     * <p>위 스냅샷들과 <b>같은 자리 · 같은 두 줄</b>이다 — ① 해석(401) → ② 소유 가드(남의 것 404) → ③ 서비스.
     */
    @Operation(summary = "정책 시뮬레이션 보고서 스냅샷",
            description = "저장된 정책 시뮬레이션 시나리오 비교 보고서를 저장 시점 스냅샷 그대로 조회한다(다시 계산하지 않는다 — "
                    + "그 뒤 계수 설정이나 데이터가 바뀌어도 값이 같다). "
                    + "응답 모양은 생성 API(POST /api/analysis/policy-sim/reports)와 같다. "
                    + "내 보고서가 아니거나 · 보고서가 없거나 · 정책 시뮬레이션 보고서가 아니거나(anlsTypeCd != POLICY_SIM) · "
                    + "저장된 결과가 없으면 404. 익명·사용자 없음은 401. 저장된 JSON 이 깨졌으면 500.")
    @GetMapping("/reports/{rptpId}/policy-sim")
    public PolicySimReportVO reportPolicySimSnapshot(@AuthenticationPrincipal OidcUser user,
                                                     @PathVariable("rptpId") Long rptpId) {
        // 🔴 ① 해석(401) → ② 소유 가드(남의 것 404) → ③ 서비스. 옆 /zone-recommendation 과 같은 두 줄이다
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        return policySimReportService.selectReportSnapshot(rptpId);
    }

    /**
     * AI 보고서 스냅샷 — 등록 시 저장한 HTML 본문을 돌려준다.
     *
     * <p>위 스냅샷들과 <b>같은 자리 · 같은 두 줄</b>이다 — ① 해석(401) → ② 소유 가드(남의 것 404 · {@code AI} 는
     * 생성자 본인만) → ③ 서비스. {@code html} 은 저장 때 새니타이즈된 값을 읽을 때 한 번 더 새니타이즈한다.
     */
    @Operation(summary = "AI 보고서 스냅샷",
            description = "등록된 AI 보고서의 HTML 본문을 조회한다(rptpId·rptpNm·html). html 은 새니타이즈된 값이다. "
                    + "내 보고서가 아니거나 · 보고서가 없거나 · 저장된 본문이 없으면(AI 보고서가 아님) 404. 익명·사용자 없음은 401.")
    @GetMapping("/reports/{rptpId}/ai")
    public AiReportSnapshotVO reportAiSnapshot(@AuthenticationPrincipal OidcUser user,
                                               @PathVariable("rptpId") Long rptpId) {
        // 🔴 ① 해석(401) → ② 소유 가드(남의 것 404) → ③ 서비스. 옆 /policy-sim 과 같은 두 줄이다
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        return aiReportService.selectReportSnapshot(rptpId);
    }

    @Operation(summary = "자동 생성 설정 목록",
            description = "보고서 자동 생성 설정을 최신 등록순(crt_dt DESC)으로 전체 조회한다. "
                    + "삭제된 설정(del_dt)은 제외된다. 지역 표시명(regionNm)은 서버가 조립해 내린다.")
    @GetMapping("/report-settings")
    public List<ReportSettingVO> reportSettings() {
        return reportService.selectSettings();
    }

    /**
     * 자동 생성 설정 등록.
     *
     * <p>{@code @Valid} 가 없으면 {@link ReportSettingRequest} 의 {@code @NotBlank}/{@code @Min}/
     * {@code @Max} 가 발동하지 않아 바디 누락({@code {}})이 400 이 아니라 NPE(500)로 흐른다 —
     * 반드시 함께 둔다.
     */
    @Operation(summary = "자동 생성 설정 등록",
            description = "보고서 자동 생성 설정을 등록한다(201). stngId 는 DB 가 매기고, 다음 배포 예정일·"
                    + "활성여부·생성자·생성일시는 서버가 채운다. 지역은 emdCd 기준으로 서버가 정규화한다 "
                    + "(보낸 sggNm 은 무시). 없는 코드값·주기별 필수값(crltnMm) 누락·없는 지역은 400.")
    /**
     * 🔴 <b>표 파생 인가</b>. 롤 리터럴을 쓰지 않는다.
     * 요청자가 M0402(보고서 관리) 매핑을 갖고 있어야 한다 — 화면과 같은 표
     * ({@code TB_AUTHRT_MENU})를 매 요청 조회하므로 권한을 저장한 즉시 반영된다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0402')")
    @PostMapping("/report-settings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportSettingVO createReportSetting(@AuthenticationPrincipal OidcUser user,
                                               @Valid @RequestBody ReportSettingRequest request) {
        // 🔴 서비스보다 앞 — 401 이면 아무것도 저장되지 않는다
        LoginUserVO me = currentUser.resolve(user);
        return reportService.createSetting(request, me.getUserId(), me.getUserNm());
    }

    /** 자동 생성 설정 수정. 없거나 이미 삭제된 설정이면 404 (지운 설정은 되살아나지 않는다). */
    @Operation(summary = "자동 생성 설정 수정",
            description = "설정 1건을 수정한다(200). 등록과 같은 검증·지역 정규화를 거치고 다음 배포 예정일도 "
                    + "다시 계산한다. 생성자·생성일시·활성여부는 그대로 둔다. "
                    + "없거나 이미 삭제된 설정은 404, 입력이 잘못되면 400.")
    /**
     * 🔴 <b>표 파생 인가</b>. 롤 리터럴을 쓰지 않는다.
     * 요청자가 M0402(보고서 관리) 매핑을 갖고 있어야 한다 — 화면과 같은 표
     * ({@code TB_AUTHRT_MENU})를 매 요청 조회하므로 권한을 저장한 즉시 반영된다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0402')")
    @PutMapping("/report-settings/{stngId}")
    public ReportSettingVO updateReportSetting(@PathVariable("stngId") Long stngId,
                                               @Valid @RequestBody ReportSettingRequest request) {
        return reportService.updateSetting(stngId, request);
    }

    /**
     * 자동 생성 설정 삭제 — 소프트 삭제다.
     *
     * <p>보고서가 참조 중이어도 204 다. 행을 지우지 않고 {@code del_dt} 만 채우므로
     * FK 를 건드리지 않는다 (409 는 개정 전 정책).
     */
    @Operation(summary = "자동 생성 설정 삭제",
            description = "설정 1건을 소프트 삭제한다(204). 행은 남고 del_dt 만 채워져 목록에서 빠진다. "
                    + "보고서가 참조 중이어도 성공하며, 없거나 이미 삭제된 설정은 404.")
    /**
     * 🔴 <b>표 파생 인가</b>. 롤 리터럴을 쓰지 않는다.
     * 요청자가 M0402(보고서 관리) 매핑을 갖고 있어야 한다 — 화면과 같은 표
     * ({@code TB_AUTHRT_MENU})를 매 요청 조회하므로 권한을 저장한 즉시 반영된다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0402')")
    @DeleteMapping("/report-settings/{stngId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReportSetting(@PathVariable("stngId") Long stngId) {
        reportService.deleteSetting(stngId);
    }
}
