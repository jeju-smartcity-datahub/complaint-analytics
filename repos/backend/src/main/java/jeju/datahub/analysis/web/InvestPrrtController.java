package jeju.datahub.analysis.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.auth.service.LoginUserVO;
import jeju.datahub.common.web.CurrentUserResolver;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.InvestPrrtReportCreateRequest;
import jeju.datahub.analysis.service.InvestPrrtReportService;
import jeju.datahub.analysis.service.InvestPrrtReportVO;
import jeju.datahub.analysis.service.InvestPrrtResultVO;
import jeju.datahub.analysis.service.InvestPrrtService;
import lombok.RequiredArgsConstructor;

/**
 * 투자우선순위 분석 REST 컨트롤러. (분석 화면 "투자 우선순위")
 *
 * <p>이 화면이 답하는 질문은 <b>"노상·공한지를 제외한 공영주차장 중, 먼저 살펴볼 후보는 어디인가"</b> 다 —
 * 투자 규모·비용·효과를 답하지 않는다.
 *
 * <p>🔴 <b>조회는 적재하지 않는다</b>(집중구역 방식). 실행 이력·결과는 보고서로 저장할 때만 쌓인다 —
 * 조회마다 적재하는 구역추천({@code ZoneRcmdtnController})과 다르다.
 *
 * <p>검증(기간 한쪽만·형식·역전·36개월 초과)은 전부 서비스가 {@code ResponseStatusException}(400) 으로
 * 낸다 — 기본 기간을 채우는 곳과 검증하는 곳을 한 곳에 두려는 것이다. 이 경로를 덮는 전역 예외 핸들러는 없다.
 * ⚠️ 쿼리 파라미터에 {@code @Min}/{@code @Pattern} 을 직접 붙이지 않는다 — 핸들러가 없어 500 으로 나간다.
 *
 * <p>인가 애노테이션이 없다 — 조회(GET)는 메뉴 단위 인가 대상이 아니고, 인증은 {@code /api/**} 가 건다.
 * 보고서 저장(POST)도 집중구역 {@code POST /hotspot/reports} 의 선례대로 걸지 않는다
 * (🔴 POST 라 CSRF 토큰은 필요하다 — {@code X-XSRF-TOKEN} 이 없으면 403).
 */
@Tag(name = "투자우선순위 분석",
        description = "투자우선순위 분석 — 노상·공한지를 제외한 공영주차장의 종합점수·순위·등급 "
                + "(조회 GET /investment-priority · 보고서 저장 POST /investment-priority/reports)")
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class InvestPrrtController {

    private final InvestPrrtService investPrrtService;

    private final InvestPrrtReportService investPrrtReportService;

    /** 로그인 사용자 해석(401 판정은 그 한 곳이다) */
    private final CurrentUserResolver currentUser;

    /**
     * 투자우선순위 조회.
     *
     * <p>🔴 {@code sggNm}·{@code emdCd} 는 <b>표시 필터</b>다 — 점수·순위는 항상 전 지역 대상 기준이라
     * 필터를 걸어도 같은 주차장의 {@code snthsScr}·{@code rnk} 가 바뀌지 않는다. 응답의 {@code smry}·
     * {@code warnings} 도 필터와 무관하게 전 지역 기준이다.
     */
    @Operation(summary = "투자우선순위 분석",
            description = "노상·공한지를 제외한 공영주차장마다 4지표(안전점검 결과 · 주변 민원 · 이용 강도 · 주변 단속)를 "
                    + "가중평균한 종합점수(0~100, 소수 9자리)와 순위·등급(H/M/L)을 낸다. "
                    + "민원·단속·이용은 전 지역 대상 안에서의 백분위 점수이고, 민원·단속 건수는 그 주차장 안의 건수가 아니라 "
                    + "배정 반경(crtrVls.altmntRngVl) 안에서 가장 가까운 대상 주차장에 배정한 건수다. "
                    + "기간(bgngYmd·endYmd, YYYYMMDD)은 둘 다 생략하면 오늘 기준 최근 1년이며, 한쪽만 주거나 "
                    + "형식이 틀리거나 종료일이 시작일보다 빠르거나 36개월을 넘으면 400 이다. "
                    + "🔴 sggNm·emdCd 는 표시 필터다 — lots 만 거르고 점수·순위(rnk)는 다시 매기지 않으며, "
                    + "smry·warnings 는 필터와 무관하게 전 지역 기준이다. "
                    + "가중치·등급 임계·배정 반경은 INVEST 분석변수에서 읽어 응답 weights·gradeCriteria·crtrVls 로 되비춘다"
                    + "(설정 오류면 409). vrblVls(JSON 문자열)로 가중치 4개와 계수 4개를 일시 조정할 수 있다 — 설정은 바꾸지 않으며, "
                    + "목록 밖 코드 · 범위 밖 · 가중치 합계가 100 아님 · 중간 ≥ 상위 등급 기준이면 400 이다. 적용 내역은 응답 vrblAjmt 다. "
                    + "조회는 실행 이력을 남기지 않는다. 결과가 0건이어도 200 이다.")
    @GetMapping("/investment-priority")
    public InvestPrrtResultVO investmentPriority(
            @Parameter(description = "분석 시작일 (YYYYMMDD). endYmd 와 함께 생략하면 오늘에서 12개월 전",
                    example = "20260713")
            @RequestParam(required = false) String bgngYmd,
            @Parameter(description = "분석 종료일 (YYYYMMDD, 포함). bgngYmd 와 함께 생략하면 오늘",
                    example = "20261013")
            @RequestParam(required = false) String endYmd,
            @Parameter(description = "시군구명 표시 필터. 생략하면 전 지역 (점수·순위는 바뀌지 않는다)", example = "제주시")
            @RequestParam(required = false) String sggNm,
            @Parameter(description = "읍면동코드(8자리) 표시 필터. 생략하면 걸지 않는다 (점수·순위는 바뀌지 않는다)",
                    example = "50110250")
            @RequestParam(required = false) String emdCd,
            @Parameter(description = "분석 계수 일시 입력 (JSON 문자열, 선택 — 가중치 SAFETY·COMPLAINT·USAGE·ACCIDENT, "
                    + "계수 ALTMNT_RNG_VL·UP_GRD_CRTR_SCR·MD_GRD_CRTR_SCR·INDCT_PKLT_CNT. 생략 = 설정값)",
                    example = "{\"ALTMNT_RNG_VL\":500}")
            @RequestParam(required = false) String vrblVls) {

        return investPrrtService.analyze(bgngYmd, endYmd, sggNm, emdCd, AnlsVrblOvrd.parse(vrblVls));
    }

    /**
     * 투자우선순위 보고서 저장 — 분석 화면의 "내보내기".
     *
     * <p>🔴 {@code @Valid} 를 빼면 {@code fileFmt} 의 {@code @NotBlank}/{@code @Pattern} 이 발동하지 않아
     * 누락·오타가 400 이 아니라 그대로 저장된다. 요청 바디 검증 실패는 Spring 이 400 으로 낸다
     * (쿼리 파라미터와 달리 {@code MethodArgumentNotValidException} 은 기본 처리기가 있다).
     */
    @Operation(summary = "투자우선순위 보고서 저장",
            description = "같은 조건으로 다시 분석해 그 결과를 보고서로 저장한다(201) — 화면이 보낸 순위를 받지 않는다. "
                    + "한 번의 호출이 분석 실행 1행 · 분석 결과 1행 · 보고서 1행(직접 생성 MANUAL)을 남기고, "
                    + "응답은 저장된 스냅샷 그대로다(GET /api/reports/{rptpId}/investment-priority 와 같은 모양). "
                    + "기간은 조회와 같은 규칙이다 — 둘 다 생략하면 오늘 기준 최근 1년이 '실제 적용된 날짜'로 저장된다. "
                    + "sggNm·emdCd 는 보고서에 담을 주차장을 거르며(lots 는 필터 통과분 전체 — 상위 N 으로 자르지 않는다) "
                    + "rnk 는 전 지역 순위 그대로다. vrblVls(바디 객체)는 조회와 규칙이 같고 적용 내역이 "
                    + "anls_param_json.vrblAjmt 에 남는다. fileFmt 는 PDF | DOCX 만(누락·그 밖 400). "
                    + "기간 오류 · 없는 읍면동코드 · 시군구와 읍면동 불일치 · 필터 통과 0곳이면 400, "
                    + "분석변수 설정 오류면 409 이며 어느 경우든 아무것도 저장하지 않는다.")
    @PostMapping("/investment-priority/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestPrrtReportVO createInvestmentPriorityReport(
            @AuthenticationPrincipal OidcUser user,
            @Valid @RequestBody InvestPrrtReportCreateRequest request) {
        // 🔴 서비스보다 앞 — 401 이면 아무것도 저장되지 않는다
        LoginUserVO me = currentUser.resolve(user);
        return investPrrtReportService.createReport(request, me.getUserId(), me.getUserNm());
    }
}
