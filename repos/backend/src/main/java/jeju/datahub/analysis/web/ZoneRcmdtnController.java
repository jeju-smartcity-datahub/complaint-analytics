package jeju.datahub.analysis.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.ZoneRcmdtnReportCreateRequest;
import jeju.datahub.analysis.service.ZoneRcmdtnReportService;
import jeju.datahub.analysis.service.ZoneRcmdtnReportVO;
import jeju.datahub.analysis.service.ZoneRcmdtnResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnService;
import jeju.datahub.auth.service.LoginUserVO;
import jeju.datahub.common.web.CurrentUserResolver;
import lombok.RequiredArgsConstructor;

/**
 * 구역추천(요금제 적용) 분석 REST 컨트롤러. (분석 화면 "구역 추천")
 *
 * <p>이 화면이 답하는 질문은 <b>"이 읍면동에서 유료화한다면 어느 주차장부터 해야 하나"</b> 다 —
 * <b>읍면동을 추천하는 화면이 아니다.</b> 그래서 창구가 둘이다.
 * <ul>
 *   <li>{@code GET /api/analysis/zone-recommendation/zones} — 읍면동을 <b>고르기 위한</b> 참고
 *       목록({@code TB_ADMDST} 전량 · 주차장 조회를 하지 않는다)</li>
 *   <li>{@code GET /api/analysis/zone-recommendation} — 고른 뒤의 <b>주력</b>(주차장 순위 + 배정률)</li>
 * </ul>
 *
 * <p><b>GET 인데 쓰기가 섞인 이유</b> — 주력 창구는 조회이면서 실행 1회를 이력으로 남긴다
 * ({@code TB_ANLS_EXCN} 1행 + {@code TB_ANLS_RSLT} 1행). "언제 어떤 계수로 낸 순위인가" 가
 * 재현의 전부라서다. 적재 규칙은 전부 {@link ZoneRcmdtnService#analyzeAndRecord} 안에 있다.
 * ⚠️ 참고 목록({@code /zones})은 <b>적재하지 않는다</b> — 화면이 동을 고르려고 매번 부르는 경로라
 * 거기서 이력을 남기면 실행 이력이 조회 로그가 된다.
 *
 * <p>이 경로를 덮는 전역 예외 핸들러가 없으므로(agent 도메인 한정 advice 1건은 {@code assignableTypes}
 * 로 격리돼 여기 오지 않는다) 잘못된 입력은 <b>컨트롤러의 기간 상한</b>과 서비스의
 * {@link ResponseStatusException} 두 겹으로 낸다 — {@code HotspotAnalysisController} 와 같은 방침이다.
 *
 * <p>⚠️ 쿼리 파라미터에 {@code @Min}/{@code @Max} 를 직접 붙이고 클래스에 {@code @Validated} 를
 * 다는 방식은 <b>쓰지 않는다</b> — 핸들러가 없어 {@code ConstraintViolationException} 이 500 으로
 * 나간다. 그래서 검증은 전부 {@link ResponseStatusException}(400) 으로 낸다.
 *
 * <h2>🔴 기간 파라미터가 민원·단속 둘로 갈린다</h2>
 * 단속 보유 구간이 민원과 달라({@code tb_crdn_dsctn} 은 2025-02~2026-10, 이도이동은
 * 2025-09~2026-07 뿐) 민원 창을 그대로 쓰면 배정 단속이 0건이 된다(종전엔 E0 판정이
 * 이 창을 썼다). 단속 창은 생략 가능하고 기본값은 분석 정본과 같은 <b>20개월</b>
 * ({@link ZoneRcmdtnService#DEFAULT_CRDN_BGNG_YMD}~{@link ZoneRcmdtnService#DEFAULT_CRDN_END_YMD})이다.
 *
 * <h2>⚠️ 기간 상한은 36개월이고 공용 가드가 아니다</h2>
 * 공용 {@code DateRanges.validate}(366일 상한)를 <b>쓸 수 없다</b> — 단속 기본 창이 20개월이라
 * 그 검증에 걸려 평시 호출이 400 이 된다. 그래서 여기 {@link #MAX_PRD_MONTHS} 를 따로 두고
 * <b>민원 창·단속 창을 각각</b> 센다(합치지 않는다 — 둘은 서로 다른 구간이다).
 * 🔴 생략된 단속 창도 <b>기본값을 채워 넣고</b> 센다 — 한쪽만 준 요청
 * ({@code crdnBgngYmd=20000101} + 생략된 종료일)이 상한을 비껴가지 않게 한다.
 *
 * <h2>보고서 저장(POST)을 이 컨트롤러에 두는 이유</h2>
 * 화면의 "내보내기" 는 조회와 <b>같은 조건</b>을 받으므로 같은 기간 상한을 걸어야 한다. 그 검증
 * ({@link #validatePrdSpan}·{@link #validateCrdnPrdSpan})이 private 이라 같은 클래스 안에서 그대로 부른다 —
 * 밖으로 빼면 조회와 저장의 상한이 갈릴 자리가 생긴다.
 * 인가 애노테이션은 없다 — 기존 보고서 저장 POST 들처럼 인증만 요구한다(인증은 {@code /api/**} 가 건다).
 * 🔴 POST 라 CSRF 토큰은 필요하다({@code X-XSRF-TOKEN} 이 없으면 403).
 */
@Tag(name = "구역추천 분석",
        description = "구역추천(요금제 적용) 분석 — 읍면동·시군구 안 공영주차장의 "
                + "유료화 검토 순위 (참고 목록 GET /zone-recommendation/zones · 상세 GET /zone-recommendation · "
                + "보고서 저장 POST /zone-recommendation/reports)")
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class ZoneRcmdtnController {

    private static final Logger log = LoggerFactory.getLogger(ZoneRcmdtnController.class);

    /**
     * 조회 기간 상한(개월). 🔴 민원 창·단속 창에 <b>각각</b> 적용한다.
     *
     * <p>정확성 문제가 아니라 <b>스캔 비용</b> 때문이다 — 상한이 없으면 10년 창 조회가 400 없이
     * 통과해 {@code tb_cvlcpt}·{@code tb_crdn_dsctn} 전량을 훑는다. 36 은 "분기 추이를 3년까지
     * 본다" 는 폭이며, 단속 기본 창(20개월)이 막히지 않는 값이다.
     */
    static final int MAX_PRD_MONTHS = 36;

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final ZoneRcmdtnService zoneRcmdtnService;

    private final ZoneRcmdtnReportService zoneRcmdtnReportService;

    /** 로그인 사용자 해석(401 판정은 그 한 곳이다) */
    private final CurrentUserResolver currentUser;

    /**
     * 읍면동 선택 목록 ({@code emdCd}·{@code emdNm}·{@code sggNm}).
     *
     * <p>🔴 {@code TB_ADMDST} 기준이라 원천이 0건인 동도 빠지지 않는다.
     * 읍면동 요금제 대상 판정(E0·필요 면수·충족률·FEE/SHARE·{@code rsnCd})은 제거됐다.
     *
     * <p>⚠️ {@code emdCd} 를 받지 않는다(읍면동 전량이다). 주차장 조회를 하지 않으므로 순위·배정률은
     * 없고, <b>실행 이력도 남기지 않는다</b>.
     */
    @Operation(summary = "구역추천 읍면동 목록 (선택용)",
            description = "읍면동 전량을 {emdCd, emdNm, sggNm} 으로 낸다(원천 0건인 동도 포함 · 시군구명→읍면동명 순). "
                    + "읍면동 요금제 대상 판정(FEE/SHARE·필요 면수·충족률·rsnCd)은 제거됐다. "
                    + "파라미터(민원 기간 bgngYmd·endYmd, 단속 창 crdnBgngYmd·crdnEndYmd, trgtTypeSe, vrblVls)는 종전처럼 받고 검증한다 — "
                    + "trgtTypeSe 는 NARROW(기본) | WIDE 이며 그 밖의 값은 400, 한 창이 36개월을 넘으면 400, "
                    + "vrblVls 의 목록 밖 코드(공통 2 + FEE_* 8 밖)·범위 밖은 400 이다. "
                    + "주차장 조회를 하지 않으므로 순위·배정률은 없고 실행 이력도 남지 않는다.")
    @GetMapping("/zone-recommendation/zones")
    public List<ZoneRcmdtnResultVO.Zone> zones(
            @Parameter(description = "민원 조회 시작일 (YYYYMMDD)", example = "20250101", required = true)
            @RequestParam String bgngYmd,
            @Parameter(description = "민원 조회 종료일 (YYYYMMDD, 포함)", example = "20250831", required = true)
            @RequestParam String endYmd,
            @Parameter(description = "단속 창 시작일 (YYYYMMDD). 생략하면 20250101", example = "20250101")
            @RequestParam(required = false) String crdnBgngYmd,
            @Parameter(description = "단속 창 종료일 (YYYYMMDD, 포함). 생략하면 20260831", example = "20260831")
            @RequestParam(required = false) String crdnEndYmd,
            @Parameter(description = "후보 주차장 유형 범위 (NARROW | WIDE). 생략하면 NARROW", example = "NARROW")
            @RequestParam(required = false) String trgtTypeSe,
            @Parameter(description = "분석 계수 일시 입력 (JSON 문자열, 선택 — 공통 ALTMNT_RNG_VL·SWTC_TRGT_MIN_CPCTY "
                    + "+ FEE_* 8개 = 10개. 생략 = 설정값)",
                    example = "{\"ALTMNT_RNG_VL\":500}")
            @RequestParam(required = false) String vrblVls) {

        validatePrdSpan(bgngYmd, endYmd, "bgngYmd", "endYmd");
        validateCrdnPrdSpan(crdnBgngYmd, crdnEndYmd);

        return zoneRcmdtnService.selectZones(bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, trgtTypeSe,
                AnlsVrblOvrd.parse(vrblVls));
    }

    /**
     * 선택한 읍면동 + 후보 주차장 순위 + 배정 커버리지. 🔴 <b>실행 1회를 이력으로 남긴다</b>.
     * 읍면동 판정({@code zone} 의 E0·필요 면수·충족률·추천유형·사유 ·
     * {@code lots[].reachNeed})은 제거됐다. {@code zone} 은 {@code emdCd}·{@code emdNm}·{@code sggNm} 뿐이다.
     *
     * <p>⚠️ 후보 0개소는 빈 화면이 아니라 <b>결론</b>이다(빈 {@code lots} + 200) — "유료화할 공영
     * 주차장이 없음 → SHARE 검토" 로 안내한다.
     */
    @Operation(summary = "구역추천 상세 (주차장 유료화 순위)",
            description = "선택한 읍면동(emdCd) 안 후보 공영주차장의 유료화 검토 순위(종합지수 "
                    + "내림차순)·배정 커버리지를 낸다. zone 은 {emdCd, emdNm, sggNm} 뿐이다(읍면동 요금제 대상 판정 제거). "
                    + "🔁 시군구(sggNm)로 실행하면 그 시군구 안 전 읍면동의 후보 주차장 순위(한 목록)만 내며 "
                    + "zone·asgn 은 null, lots[].cumCpcty 는 null 이다(배정은 읍면동 경계 안 — 같은 주차장의 배정 민원은 "
                    + "읍면동 실행과 같다). emdCd·sggNm 은 정확히 하나만 보낸다(둘 다 없음·둘 다 있음·40자 초과·"
                    + "없는 시군구는 400). 응답 scope 가 EMD|SGG 다. 🔴 응답의 asgn 블록이 '순위가 민원의 몇 %를 설명하는가' 다. "
                    + "단속 창을 생략하면 20250101~20260831 이고, 적용 계수(배정 반경·면수 하한·FEE 검토지수 8종)는 "
                    + "RECOMMEND 분석변수에서 읽어 응답 crtrVls 에 굳힌다. "
                    + "vrblVls(JSON 문자열)로 계수 10개를 일시 조정할 수 있다 — 설정은 바꾸지 않으며, 목록 밖 코드 · 범위 밖 · "
                    + "FEE 가중치 합계가 100 아님이면 400 이다. 적용 내역은 응답 vrblAjmt 다. "
                    + "실행 1회 = TB_ANLS_EXCN 1행 + TB_ANLS_RSLT 1행을 한 트랜잭션으로 남기고 excnId 를 함께 내린다. "
                    + "trgtTypeSe 는 NARROW(기본) | WIDE 이며 그 밖의 값은 400, 한 창이 36개월을 넘으면 400, "
                    + "실재하지 않는 읍면동코드는 404 다. "
                    + "실행 이력의 실행자는 로그인 사용자(excutr_id)이며 미인증은 401 이다.")
    @GetMapping("/zone-recommendation")
    public ZoneRcmdtnResultVO zoneRecommendation(
            @Parameter(description = "읍면동 코드(8자리) — emdCd·sggNm 중 정확히 하나", example = "50110104")
            @RequestParam(required = false) String emdCd,
            @Parameter(description = "시군구명(시군구 모드) — emdCd·sggNm 중 정확히 하나", example = "제주시")
            @RequestParam(required = false) String sggNm,
            @Parameter(description = "민원 조회 시작일 (YYYYMMDD)", example = "20250101", required = true)
            @RequestParam String bgngYmd,
            @Parameter(description = "민원 조회 종료일 (YYYYMMDD, 포함)", example = "20250831", required = true)
            @RequestParam String endYmd,
            @Parameter(description = "단속 창 시작일 (YYYYMMDD). 생략하면 20250101", example = "20250101")
            @RequestParam(required = false) String crdnBgngYmd,
            @Parameter(description = "단속 창 종료일 (YYYYMMDD, 포함). 생략하면 20260831", example = "20260831")
            @RequestParam(required = false) String crdnEndYmd,
            @Parameter(description = "후보 주차장 유형 범위 (NARROW | WIDE). 생략하면 NARROW", example = "NARROW")
            @RequestParam(required = false) String trgtTypeSe,
            @Parameter(description = "분석 계수 일시 입력 (JSON 문자열, 선택 — 공통 ALTMNT_RNG_VL·SWTC_TRGT_MIN_CPCTY "
                    + "+ FEE_* 8개 = 10개. 생략 = 설정값)",
                    example = "{\"ALTMNT_RNG_VL\":500}")
            @RequestParam(required = false) String vrblVls,
            @AuthenticationPrincipal OidcUser user) {

        validatePrdSpan(bgngYmd, endYmd, "bgngYmd", "endYmd");
        validateCrdnPrdSpan(crdnBgngYmd, crdnEndYmd);

        // emdCd·sggNm 배타 검증(정확히 하나)은 서비스(resolveScope)가 한다 — 보고서 경로와 한 곳
        return zoneRcmdtnService.analyzeAndRecord(
                emdCd, sggNm, bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, trgtTypeSe,
                currentUser.resolveUserId(user),    // 실행자 = 로그인 사용자 · 미인증 401
                AnlsVrblOvrd.parse(vrblVls));
    }

    /**
     * 요금제 적용 보고서 저장 — 분석 화면의 "내보내기".
     *
     * <p>🔴 <b>순서가 계약이다</b> — ①로그인 사용자 해석 → ②기간 상한 → ③서비스. 사용자 해석이 서비스보다
     * 앞이라 401 이면 아무것도 저장되지 않는다. 생성자는 principal 에서 해석한 {@code TB_USER} 의 사용자이고
     * 폴백이 없다 — 미인증 · 사용자 없음 · 정지는 401 이다.
     *
     * <p>🔴 {@code @Valid} 를 빼면 {@code fileFmt}·기간의 {@code @NotBlank}/{@code @Pattern} 이 발동하지 않아
     * 누락·오타가 400 이 아니라 그대로 저장된다. 요청 바디 검증 실패는 Spring 이 400 으로 낸다
     * (쿼리 파라미터와 달리 {@code MethodArgumentNotValidException} 은 기본 처리기가 있다).
     *
     * <p>⚠️ 조회(GET)의 실행 이력과 <b>따로</b> 실행 1행이 더 남는다 — "실행" 1회 = 이력 1행, "내보내기" 1회 =
     * 이력 1행 더(로그인 사용자). 조회 쪽 실행자는 이 작업에서 바꾸지 않는다.
     */
    @Operation(summary = "구역추천 요금제 적용 보고서 저장",
            description = "같은 조건으로 다시 계산해 그 결과를 보고서로 저장한다(201) — 화면이 보낸 순위를 받지 않는다. "
                    + "한 번의 호출이 분석 실행 1행 · 분석 결과 1행 · 보고서 1행(직접 생성 MANUAL)을 한 트랜잭션으로 남기고, "
                    + "응답은 저장된 스냅샷 그대로다(rcmdtnTypeCd='FEE' · fee = 조회 응답과 같은 본문 · share 는 null). "
                    + "🔴 생성자·실행자는 로그인 사용자다(미인증 · 사용자 없음 · 정지는 401 이며 폴백이 없다). "
                    + "🔴 실행 ID 는 응답 머리의 excnId 한 곳이다 — fee.excnId 는 null 이다. "
                    + "범위는 emdCd(읍면동) 또는 sggNm(시군구 전체) 중 정확히 하나다(둘 다·둘 다 없음·"
                    + "없는 시군구·40자 초과 sggNm 은 400). 시군구 보고서는 이름이 '… {시군구} 전체' 이고 "
                    + "fee.scope='SGG' · zone·asgn 은 null 이다. "
                    + "vrblVls(바디 객체)는 조회와 규칙이 같고 적용 내역이 anls_param_json.vrblAjmt 와 응답 머리 vrblAjmt 에 남는다. "
                    + "bgngYmd·endYmd 는 필수이고 단속 창(crdnBgngYmd·crdnEndYmd)을 생략하면 조회와 같은 "
                    + "20250101~20260831 이다. trgtTypeSe 는 NARROW(기본) | WIDE, fileFmt 는 PDF | DOCX | XLSX 만(누락·그 밖 400). "
                    + "형식과 무관하게 fee.wbk(엑셀 재계산 데이터)를 저장·응답한다(스냅샷도 같다 · 조회 GET 에는 없다). "
                    + "기간 형식·순서 오류 · 한 창이 36개월 초과 · 유형 범위 오류 · 후보 주차장 0개소면 400, "
                    + "실재하지 않는 읍면동코드는 404 이며 어느 경우든 아무것도 저장하지 않는다.")
    @PostMapping("/zone-recommendation/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ZoneRcmdtnReportVO createFeeReport(@AuthenticationPrincipal OidcUser user,
                                              @Valid @RequestBody ZoneRcmdtnReportCreateRequest req) {
        // 🔴 서비스보다 앞 — 401 이면 아무것도 저장되지 않는다
        LoginUserVO me = currentUser.resolve(user);
        validatePrdSpan(req.getBgngYmd(), req.getEndYmd(), "bgngYmd", "endYmd");
        validateCrdnPrdSpan(req.getCrdnBgngYmd(), req.getCrdnEndYmd());
        return zoneRcmdtnReportService.createFeeReport(req, me.getUserId(), me.getUserNm());
    }

    /**
     * 단속 창 상한 검증. 🔴 <b>생략값을 기본값으로 채운 뒤</b> 센다 — 한쪽만 준 요청이 상한을
     * 비껴가지 않게 한다.
     */
    private static void validateCrdnPrdSpan(String crdnBgngYmd, String crdnEndYmd) {
        String bgng = isBlank(crdnBgngYmd) ? ZoneRcmdtnService.DEFAULT_CRDN_BGNG_YMD : crdnBgngYmd;
        String end = isBlank(crdnEndYmd) ? ZoneRcmdtnService.DEFAULT_CRDN_END_YMD : crdnEndYmd;
        validatePrdSpan(bgng, end, "crdnBgngYmd", "crdnEndYmd");
    }

    /**
     * 기간 상한 검증.
     *
     * <p>⚠️ <b>형식 오류는 여기서 판정하지 않는다</b> — 파싱에 실패하면 조용히 통과시키고 서비스의
     * {@code parseYmd} 가 필드명과 함께 400 을 낸다(같은 오류를 두 곳에서 다른 문구로 내지 않는다).
     * 순서(종료 ≥ 시작)도 서비스가 본다 — 여기서는 <b>폭</b>만 본다.
     */
    private static void validatePrdSpan(String bgngYmd, String endYmd,
                                        String bgngField, String endField) {
        // 시큐어코딩 5.1 — null 검사를 하나씩 나눠 둔다(동작 동일). 형식·순서 오류는 서비스가 400 을 낸다.
        LocalDate bgng = tryParse(bgngYmd);
        if (bgng == null) {
            return;
        }
        LocalDate end = tryParse(endYmd);
        if (end == null) {
            return;
        }
        if (end.isBefore(bgng)) {
            return;
        }
        long months = ChronoUnit.MONTHS.between(bgng.withDayOfMonth(1), end.withDayOfMonth(1)) + 1;
        if (months > MAX_PRD_MONTHS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    bgngField + "~" + endField + " 조회 기간은 " + MAX_PRD_MONTHS
                            + "개월을 넘을 수 없습니다: " + bgng + " ~ " + end + " (" + months + "개월)");
        }
    }

    private static LocalDate tryParse(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), YMD);
        } catch (DateTimeParseException e) {
            // 형식이 틀린 날짜는 "값 없음" 으로 본다 — 호출부가 null 을 기본 구간으로 처리한다 (4.2)
            log.debug("날짜 파싱 실패 — null 로 처리: value={}", value);
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
