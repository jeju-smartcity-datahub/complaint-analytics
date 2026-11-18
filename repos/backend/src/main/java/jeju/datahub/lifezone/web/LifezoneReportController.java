package jeju.datahub.lifezone.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import jeju.datahub.lifezone.service.LifezoneCngstReportCreateRequest;
import jeju.datahub.lifezone.service.LifezoneCngstReportService;
import jeju.datahub.lifezone.service.LifezoneCngstReportVO;
import jeju.datahub.lifezone.service.LifezoneReportCreateRequest;
import jeju.datahub.lifezone.service.LifezoneReportService;
import jeju.datahub.lifezone.service.LifezoneReportVO;
import jeju.datahub.lifezone.service.LifezoneSupplyReportCreateRequest;
import jeju.datahub.lifezone.service.LifezoneSupplyReportService;
import jeju.datahub.lifezone.service.LifezoneSupplyReportVO;
import jeju.datahub.report.service.ReportOwnershipGuard;
import lombok.RequiredArgsConstructor;

/**
 * 생활권 시뮬레이션 <b>보고서</b> REST 컨트롤러. (분석 화면 "내보내기")
 *
 * <p>조회 전용인 {@code LifezoneAnalysisController} 와 <b>분리</b>한다 — 그쪽은 아무것도 저장하지
 * 않는다는 것이 계약이고, 이 클래스는 세 표에 쓴다. 한 컨트롤러에 섞으면 "이 화면의 어떤
 * 호출이 데이터를 남기는가" 가 흐려진다.
 *
 * <p>🔴 <b>두 엔드포인트의 URL 접두가 다르다.</b>
 * <ul>
 *   <li>{@code POST /api/analysis/lifezone/reports} — 분석 조건으로 <b>만든다</b>(분석 도메인)</li>
 *   <li>{@code GET /api/reports/{rptpId}/lifezone} — 보고서 아이디로 <b>다시 연다</b>(보고서 도메인).
 *       집중구역의 {@code /api/reports/{rptpId}/hotspot} 과 같은 자리이며, 보고서 목록에서
 *       재다운로드할 때 쓴다</li>
 * </ul>
 * 그래서 클래스 접두는 {@code /api} 하나로 두고 메서드에서 갈라 붙인다.
 *
 * <p>이 경로를 덮는 전역 예외 핸들러가 없으므로(agent 도메인 한정 advice 1건은 {@code assignableTypes}
 * 로 격리돼 여기 오지 않는다) 잘못된 입력은 바인딩 검증
 * ({@code @Valid} → 400)과 서비스의 {@code ResponseStatusException} 두 겹으로 낸다.
 */
@Tag(name = "생활권 시뮬레이션 보고서",
        description = "생활권 격자 분석 결과 저장·재현 "
                + "(민원건수: POST /api/analysis/lifezone/reports · GET /api/reports/{rptpId}/lifezone / "
                + "수급: POST /api/analysis/lifezone/supply/reports · GET /api/reports/{rptpId}/lifezone/supply / "
                + "혼잡도: POST /api/analysis/lifezone/congestion/reports · GET /api/reports/{rptpId}/lifezone/congestion)")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LifezoneReportController {

    private final LifezoneReportService lifezoneReportService;
    private final LifezoneSupplyReportService lifezoneSupplyReportService;
    private final LifezoneCngstReportService lifezoneCngstReportService;

    /** 로그인 사용자 해석(401 판정은 그 한 곳이다) */
    private final CurrentUserResolver currentUser;

    /**
     * 보고서 소유 판정 한 곳 (요청 ①) — {@code ReportController} 와 <b>같은 빈</b>이다.
     *
     * <p>🔴 스냅샷 엔드포인트가 컨트롤러 <b>2개</b>에 흩어져 있어, 한쪽만 걸면 이쪽 2종이 그대로 뚫린다.
     * 🔴 서비스 시그니처({@code selectReportSnapshot(Long)})는 바꾸지 않는다 — 컨트롤러가 호출 직전에 부른다.
     */
    private final ReportOwnershipGuard reportOwnershipGuard;

    /**
     * 생활권 시뮬레이션 보고서 생성.
     *
     * <p>🔴 {@code @Valid} 를 빼면 {@link LifezoneReportCreateRequest} 의 제약({@code fileFmt}
     * 화이트리스트·기간 형식)이 <b>통째로</b> 발동하지 않아, 빈 바디({@code {}})가 400 이 아니라
     * NPE(500)로 흐른다 — 전역 예외 핸들러가 없어 그대로 500 이 나간다.
     *
     * <p>결과 0건은 조회와 달리 <b>400</b> 이다 — 열 수 없는 빈 보고서를 목록에 남기지 않는다.
     */
    @Operation(summary = "생활권 시뮬레이션 보고서 생성",
            description = "같은 조건으로 서버가 다시 분석해 그 결과를 보고서로 저장한다(화면이 보낸 숫자는 쓰지 않는다). "
                    + "실행 이력(TB_ANLS_EXCN) 1행 · 분석 결과(TB_ANLS_RSLT) 1행 · 보고서(TB_RPTP) 1행을 "
                    + "한 트랜잭션으로 남기고, 저장된 스냅샷을 그대로 201 로 돌려준다. "
                    + "등급 경계는 조회 결과에서 산출되므로 분류방법·경계 5행을 anls_param_json 에 함께 굳힌다. "
                    + "fileFmt 는 PDF | DOCX | XLSX 이며 그 밖의 값이면 400, 분석 결과가 0건이어도 400 이다(저장하지 않는다).")
    @PostMapping("/analysis/lifezone/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public LifezoneReportVO createLifezoneReport(@AuthenticationPrincipal OidcUser user,
                                                 @Valid @RequestBody LifezoneReportCreateRequest request) {
        // 🔴 서비스보다 앞 — 401 이면 아무것도 저장되지 않는다
        LoginUserVO me = currentUser.resolve(user);
        return lifezoneReportService.createReport(request, me.getUserId(), me.getUserNm());
    }

    /**
     * 생활권 시뮬레이션 보고서 스냅샷 조회 (보고서 목록 → 재다운로드).
     *
     * <p><b>보고서를 새로 만들지 않는다</b> — 저장된 {@code rslt_json}·{@code anls_param_json} 과
     * 실행 행의 기간·지역만 읽어 생성 당시와 같은 응답을 조립한다. 원천 데이터가 그 사이 바뀌어도
     * 보고서는 만들어진 날의 기록이어야 하기 때문이다.
     */
    /**
     * 생활권 <b>수급</b> 보고서 생성.
     *
     * <p>🔴 <b>민원건수 보고서와 경로·저장코드가 갈린다.</b> 저장되는 결과 JSON 의 모양이 달라
     * (민원건수 5등급 ↔ 수급 부족률 등간격 5단계 · 구 스냅샷 4사분면) 되읽는 파서가 다르고, 보고서 목록은 {@code anls_type_cd} 로 어느 스냅샷
     * API 를 부를지 고른다 — 같은 코드로 저장하면 수급 보고서를 민원건수 파서가 읽어 <b>조용히
     * 빈 표</b>가 나온다. 그래서 {@code LIFEZONE_SPLY} 로 남긴다.
     */
    @Operation(summary = "생활권 주차 수급 보고서 생성",
            description = "같은 조건으로 서버가 다시 분석해 그 결과를 보고서로 저장한다(화면이 보낸 숫자는 쓰지 않는다). "
                    + "실행 이력(TB_ANLS_EXCN, anls_type_cd=LIFEZONE_SPLY) 1행 · 분석 결과(TB_ANLS_RSLT) 1행 · "
                    + "보고서(TB_RPTP) 1행을 한 트랜잭션으로 남기고 스냅샷을 그대로 201 로 돌려준다. "
                    + "anls_param_json 에 분석 방법(anlsMthd=SHRT_RT — 부족률 등간격 5단계)과 등급 범례 5행"
                    + "(gradeCriteria), 표 등급(grade)을 함께 굳힌다. "
                    + "grade 는 SEVERE | WARN | CAUTION 이며 생략 = 전체, 그 밖의 값(NORMAL·LOW 포함)이면 400. "
                    + "vrblVls(바디 객체)는 조회와 규칙이 같고, 적용 내역이 anls_param_json.vrblAjmt 에 남는다(설정은 바꾸지 않는다). "
                    + "fileFmt 는 PDF | DOCX | XLSX 이며 그 밖의 값이면 400, 분석 결과가 0건이어도 400 이다(저장하지 않는다).")
    @PostMapping("/analysis/lifezone/supply/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public LifezoneSupplyReportVO createLifezoneSupplyReport(
            @AuthenticationPrincipal OidcUser user,
            @Valid @RequestBody LifezoneSupplyReportCreateRequest request) {
        // 🔴 서비스보다 앞 — 401 이면 아무것도 저장되지 않는다
        LoginUserVO me = currentUser.resolve(user);
        return lifezoneSupplyReportService.createReport(request, me.getUserId(), me.getUserNm());
    }

    /**
     * 생활권 <b>수급</b> 보고서 스냅샷 조회.
     *
     * <p>🔴 {@code LIFEZONE}(민원건수) 실행으로 저장된 보고서를 이 경로로 부르면 <b>404</b> 다 —
     * 반대도 같다. 파서가 다르므로 조용히 읽어 빈 결과를 내는 대신 끊는다.
     */
    @Operation(summary = "생활권 주차 수급 보고서 스냅샷 조회",
            description = "저장된 수급 분석 결과를 생성 당시 그대로 다시 읽는다(재분석하지 않는다). "
                    + "응답 모양은 분석 조회(GET /api/analysis/lifezone/supply)와 같고 보고서 헤더 7칸이 더해진다. "
                    + "cells 의 등급·순위는 **다시 판정하지 않고** 저장된 값을 그대로 쓴다. "
                    + "anlsMthd=SHRT_RT 이면 신규(부족률 등간격 5단계) 스냅샷이고, 이 값이 없으면 구 스냅샷(4사분면)으로 "
                    + "저장 당시 키(qdrn·qdrnCriteria·q1Cnt~q4Cnt 등)를 원문 그대로 돌려준다 — 프론트는 anlsMthd 로 렌더러를 고른다. "
                    + "보고서가 없거나 · LIFEZONE_SPLY 실행이 아니거나 · 스냅샷이 비었으면 404 다. "
                    + "⚠️ dataBgngYmd·dataEndYmd 는 비어 있다 — 원천 보유 구간은 '지금' 의 값이라 스냅샷이 아니다.")
    @GetMapping("/reports/{rptpId}/lifezone/supply")
    public LifezoneSupplyReportVO reportLifezoneSupplySnapshot(@AuthenticationPrincipal OidcUser user,
                                                               @PathVariable("rptpId") Long rptpId) {
        // 🔴 남의 직접 생성 보고서는 404. 목록만 막으면 본문이 그대로 열린다
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        return lifezoneSupplyReportService.selectReportSnapshot(rptpId);
    }

    /**
     * 생활권 <b>혼잡도</b> 보고서 생성.
     *
     * <p>🔴 저장 코드는 {@code LIFEZONE_CNGST} 다 — 보고서 목록이 이 코드로 스냅샷 경로를 고른다.
     * 격자가 있는데 순위가 0행인 조건은 정상이라 저장한다.
     */
    @Operation(summary = "생활권 혼잡도 보고서 생성",
            description = "같은 조건으로 서버가 다시 분석해 그 결과를 보고서로 저장한다(화면이 보낸 숫자는 쓰지 않는다). "
                    + "실행 이력(TB_ANLS_EXCN, anls_type_cd=LIFEZONE_CNGST) 1행 · 분석 결과(TB_ANLS_RSLT) 1행 · "
                    + "보고서(TB_RPTP) 1행을 한 트랜잭션으로 남기고 스냅샷을 그대로 201 로 돌려준다. "
                    + "anlsVl 은 점수 × 100 정수다. "
                    + "vrblVls(바디 객체)는 조회와 규칙이 같고, 적용 내역이 anls_param_json.vrblAjmt 에 남는다(설정은 바꾸지 않는다). "
                    + "fileFmt 는 PDF | DOCX | XLSX 이며 그 밖의 값이면 400, 격자가 0건이어도 400 이다(저장하지 않는다). "
                    + "심각·경고·주의가 없어 순위만 0행인 경우는 201 이다.")
    @PostMapping("/analysis/lifezone/congestion/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public LifezoneCngstReportVO createLifezoneCngstReport(
            @AuthenticationPrincipal OidcUser user,
            @Valid @RequestBody LifezoneCngstReportCreateRequest request) {
        // 🔴 서비스보다 앞 — 401 이면 아무것도 저장되지 않는다
        LoginUserVO me = currentUser.resolve(user);
        return lifezoneCngstReportService.createReport(request, me.getUserId(), me.getUserNm());
    }

    /** 생활권 <b>혼잡도</b> 보고서 스냅샷 조회. 🔴 다른 유형의 보고서를 이 경로로 부르면 404 다(반대도 같다). */
    @Operation(summary = "생활권 혼잡도 보고서 스냅샷 조회",
            description = "저장된 혼잡도 분석 결과를 생성 당시 그대로 다시 읽는다(재분석하지 않는다). "
                    + "응답 모양은 분석 조회(GET /api/analysis/lifezone/congestion)와 같고 보고서 헤더 7칸이 더해진다. "
                    + "등급·순위·범례는 다시 판정하지 않고 저장된 값을 그대로 쓴다. "
                    + "보고서가 없거나 · LIFEZONE_CNGST 실행이 아니거나 · 스냅샷이 비었거나 · 내 보고서가 아니면 404 다. "
                    + "⚠️ dataBgngYmd·dataEndYmd 는 비어 있다 — 원천 보유 구간은 '지금' 의 값이라 스냅샷이 아니다.")
    @GetMapping("/reports/{rptpId}/lifezone/congestion")
    public LifezoneCngstReportVO reportLifezoneCngstSnapshot(@AuthenticationPrincipal OidcUser user,
                                                             @PathVariable("rptpId") Long rptpId) {
        // 🔴 남의 직접 생성 보고서는 404. 목록만 막으면 본문이 그대로 열린다
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        return lifezoneCngstReportService.selectReportSnapshot(rptpId);
    }

    @Operation(summary = "생활권 보고서 스냅샷 조회",
            description = "저장된 생활권 분석 결과를 생성 당시 그대로 다시 읽는다(재분석하지 않는다). "
                    + "응답 모양은 분석 조회(GET /api/analysis/lifezone)와 같고 보고서 헤더 7칸이 더해진다. "
                    + "cells 의 등급은 저장된 gradeCriteria 경계로 재판정하고 ranking 은 저장 순서·등급 그대로다. "
                    + "보고서가 없거나 · LIFEZONE 실행이 아니거나 · 스냅샷이 비었으면 404 다.")
    @GetMapping("/reports/{rptpId}/lifezone")
    public LifezoneReportVO reportLifezoneSnapshot(@AuthenticationPrincipal OidcUser user,
                                                   @PathVariable("rptpId") Long rptpId) {
        // 🔴 남의 직접 생성 보고서는 404. 목록만 막으면 본문이 그대로 열린다
        reportOwnershipGuard.check(rptpId, currentUser.resolveUserId(user));
        return lifezoneReportService.selectReportSnapshot(rptpId);
    }
}
