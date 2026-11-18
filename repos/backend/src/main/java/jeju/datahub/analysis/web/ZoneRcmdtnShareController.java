package jeju.datahub.analysis.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

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
import jeju.datahub.analysis.service.ZoneRcmdtnReportService;
import jeju.datahub.analysis.service.ZoneRcmdtnReportVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareReportCreateRequest;
import jeju.datahub.analysis.service.ZoneRcmdtnShareResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareService;
import jeju.datahub.auth.service.LoginUserVO;
import jeju.datahub.common.web.CurrentUserResolver;
import lombok.RequiredArgsConstructor;

/**
 * 구역추천(공유주차제 적용) 분석 REST 컨트롤러. (분석 화면 "구역 추천" — TSK-4208 Step 4)
 *
 * <p>이 화면이 답하는 질문은 <b>"주차 민원 기준으로, 공유주차제를 검토해 볼 만한 읍면동은 어디인가"</b>
 * 다. 요금제 트랙({@link ZoneRcmdtnController})과 <b>단위가 다르다</b> — 그쪽은 읍면동을 고른 뒤 그 안의
 * 주차장 순위를 내고, 이쪽은 기간만 받아 <b>읍면동 목록</b>을 낸다(지역은 필터다). 그래서 결과 이름은
 * "공유주차 검토 후보" 이고 "적합·필요·추천 구역" 이라고 부르지 않는다.
 *
 * <p>🔴 <b>요금제 트랙의 컨트롤러와 파일을 가른다</b> — 경로 접두({@code /api/analysis/zone-recommendation})
 * 는 같지만 두 작업이 같은 파일을 고치지 않게 한다.
 *
 * <h2>🔴 이 API 가 답하지 못하는 것</h2>
 * <ol>
 *   <li><b>공유할 부설주차장이 있는가</b> — 부설주차장 자료가 DB 에 없다</li>
 *   <li><b>어느 시간대에 부족한가</b> — 시각이 있는 민원이 거의 없고 이용 실적은 월 합계다</li>
 *   <li><b>공유주차제를 하면 민원이 얼마나 줄어드는가</b> — 효과 산식이 없다</li>
 *   <li><b>읍면동 안의 어디인가</b> — 민원이 한 구역·한 지번에 몰린 곳이 있다(주의 표시 {@code TOP_PNT})</li>
 * </ol>
 *
 * <p><b>GET 인데 쓰기가 섞인 이유</b> — 조회이면서 실행 1회를 이력으로 남긴다({@code TB_ANLS_EXCN} 1행 +
 * {@code TB_ANLS_RSLT} 1행). "언제 어떤 기준값으로 낸 목록인가" 가 재현의 전부라서다. 적재 규칙은 전부
 * {@link ZoneRcmdtnShareService#analyzeAndRecord} 안에 있다.
 *
 * <p>이 경로를 덮는 전역 예외 핸들러가 없으므로 잘못된 입력은 <b>컨트롤러의 기간 상한</b>과 서비스의
 * {@link ResponseStatusException}(400) 두 겹으로 낸다({@link ZoneRcmdtnController} 와 같은 층 구성) —
 * 날짜 형식·역전·{@code sggNm} 길이·없는 {@code sggNm}/{@code emdCd} 는 서비스에서 나고, 전부
 * <b>적재보다 앞</b>이다.
 *
 * <p>⚠️ <b>기간 상한은 요금제 트랙과 같은 36개월이다</b>({@link ZoneRcmdtnController#MAX_PRD_MONTHS} 를
 * 참조한다 — 다시 선언하지 않는다. 같은 화면의 두 트랙이 다른 상한을 가지면 안 된다). 이유도 같다 —
 * 정확성이 아니라 {@code tb_cvlcpt} <b>스캔 비용</b>이다. 공용 {@code DateRanges.validate}(366일)는
 * 그쪽과 폭이 달라 쓰지 않는다.
 * ⚠️ 쿼리 파라미터에 {@code @Min}/{@code @Max} 를 붙이고 클래스에 {@code @Validated} 를 다는 방식은
 * 쓰지 않는다 — 핸들러가 없어 {@code ConstraintViolationException} 이 500 으로 나간다
 * ({@link ZoneRcmdtnController} 와 같은 방침).
 *
 * <p>⚠️ <b>인가 어노테이션을 붙이지 않는다</b> — 메뉴 단위 인가는 쓰기 매핑에만 걸고 조회({@code GET})는
 * 대상이 아니다. 인증은 {@code SecurityConfig} 의 {@code /api/**} 가 건다(미인증은 401 JSON).
 *
 * <h2>보고서 저장(POST)을 이 컨트롤러에 두는 이유 (TSK-4240 Step 3)</h2>
 * 화면의 "내보내기" 는 조회와 <b>같은 조건</b>을 받으므로 같은 기간 상한을 걸어야 한다. 그 검증
 * ({@code validatePrdSpan})이 private 이라 같은 클래스 안에서 그대로 부른다.
 * 저장 POST 에도 인가 애노테이션은 없다 — 기존 보고서 저장 POST 들처럼 인증만 요구한다(D13).
 * 🔴 POST 라 CSRF 토큰은 필요하다({@code X-XSRF-TOKEN} 이 없으면 403).
 */
@Tag(name = "구역추천 분석 (공유주차제)",
        description = "구역추천(공유주차제 적용) 분석 — 주차 민원 기준 공유주차 검토 후보 읍면동 목록 "
                + "(GET /zone-recommendation/share · 보고서 저장 POST /zone-recommendation/share/reports)")
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class ZoneRcmdtnShareController {

    private static final Logger log = LoggerFactory.getLogger(ZoneRcmdtnShareController.class);

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final ZoneRcmdtnShareService zoneRcmdtnShareService;

    private final ZoneRcmdtnReportService zoneRcmdtnReportService;

    /** 로그인 사용자 해석(401 판정은 그 한 곳이다 — D7) */
    private final CurrentUserResolver currentUser;

    /**
     * 읍면동 전체의 공유주차 검토 판정. 🔴 <b>실행 1회를 이력으로 남긴다</b>.
     *
     * <p>🔴 {@code zones} 는 후보만이 아니라 <b>필터에 걸린 읍면동 전부</b>다 — 후보가 0곳이어도 빈 배열이
     * 아니라 전 읍면동이 후보 아님으로 온다(200).
     * <p>🔴 {@code smry}·{@code rvwScr}·등급은 <b>도 전체 기준</b>이다 — 지역 필터를 걸어도 달라지지 않는다.
     * {@code rnk} 만 <b>조회 결과(필터 적용 후) 안에서</b> 1 부터 매긴 표시 순번이다.
     */
    @Operation(summary = "구역추천 공유주차제 검토 후보 (읍면동 판정 목록)",
            description = "민원 기간(bgngYmd·endYmd, YYYYMMDD)으로 읍면동 전체를 판정해 '공유주차 검토 후보' 를 낸다. "
                    + "후보 조건은 월평균 민원과 공영 100면당 월평균 민원이 모두 기준값 이상인 것이고, 기준값은 요청 "
                    + "파라미터가 아니라 RECOMMEND 분석변수(CVLCPT_MM_CRTR_VL·CVLCPT_RT_CRTR_VL)에서 읽어 응답 "
                    + "crtrVls 에 굳힌다(증가 가중치·상한 CVLCPT_INCRS_WGVL·CVLCPT_INCRS_RT_UPLMT_VL 도 같은 분석변수다). 두 조건은 검토 지수 rvwScr = min(월평균 민원 ÷ 민원 월기준값, 100면당 월평균 "
                    + "민원 ÷ 민원 비율기준값) 하나로도 내린다 — 1 이상 = 후보, 2 이상 = 핵심 후보이고 소수 2자리 "
                    + "버림이며 면수 0(UNABLE)이면 null 이다. 🔴 검토 지수는 기준을 넘은 정도이지 공유주차 적합도·효과가 "
                    + "아니다. zones 는 필터(sggNm·emdCd)에 걸린 읍면동 전부이며 순서는 rnk 오름차순(추세 반영 "
                    + "지수(우선도) 내림차순 → 민원 건수 내림차순 → 읍면동코드) → 맨 뒤에 판정 불가(UNABLE)다. 추세 반영 지수 "
                    + "incrsRfltRvwScr = rvwScr × 증가계수 incrsCfc(= 1 + 가중치 × clamp(증가율 cvlcptIncrsRt, ±상한))이고, "
                    + "증가율은 직전 동일 기간 대비(전 읍면동 합 0건이면 조회 기간 반 분할 — TREND_BFR_FALLBACK, 이전 블록 "
                    + "10건 미만이면 null · 계수 1, 조회 기간 14일 미만이면 TREND_WINDOW_SHORT)다. 🔴 등급·후보는 rvwScr 로 "
                    + "정하고 순서만 추세로 바꾼다 — 후보 안에서 핵심 후보가 후보 뒤에 설 수 있다. 🔴 rnk 는 사업 "
                    + "우선순위가 아니라 표시 순번이다 — 조회 결과(필터 적용 후) 안에서 검토 지수가 있는 읍면동 전부에 "
                    + "1 부터 매긴다(판정 불가는 null). 그래서 필터에 따라 같은 읍면동의 번호가 달라진다. 등급·rvwScr "
                    + "는 필터와 무관하게 같고, smry 도 필터와 무관한 도 전체 값이다. 후보 0곳도 200 이며, 기간에 민원이 0건이면 warnings 에 NO_CVLCPT_IN_RANGE 가 담긴다. "
                    + "dataBgngYmd·dataEndYmd 는 조회 기간과 무관한 자료 구간이다. "
                    + "vrblVls(JSON 문자열)로 계수 4개를 일시 조정할 수 있다 — 설정은 바꾸지 않으며, 목록 밖 코드 · 범위 밖이면 400 이다. "
                    + "적용 내역은 응답 vrblAjmt 다. "
                    + "🔴 이 API 가 답하지 못하는 것 — ① 공유할 부설주차장이 있는가(자료 없음) ② 어느 시간대에 "
                    + "부족한가 ③ 공유주차제를 하면 민원이 얼마나 줄어드는가(효과 산식 없음) ④ 읍면동 안의 어디인가. "
                    + "그래서 결과는 '적합·필요 지역' 이 아니라 검토 후보다. "
                    + "실행 1회 = TB_ANLS_EXCN 1행 + TB_ANLS_RSLT 1행을 한 트랜잭션으로 남기고 excnId 를 함께 내린다"
                    + "(anls_param_json.rcmdtnTypeCd = SHARE). 날짜 형식 오류·기간 역전·36개월을 넘는 조회 기간·"
                    + "40자를 넘는 sggNm·실재하지 않는 시군구명·실재하지 않는 읍면동코드는 400 이고 그때는 이력이 "
                    + "남지 않는다. 실행 이력의 실행자는 로그인 사용자(excutr_id)이며 미인증은 401 이다.")
    @GetMapping("/zone-recommendation/share")
    public ZoneRcmdtnShareResultVO share(
            @Parameter(description = "민원 조회 시작일 (YYYYMMDD). 종료일까지 36개월을 넘으면 400", example = "20260101", required = true)
            @RequestParam String bgngYmd,
            @Parameter(description = "민원 조회 종료일 (YYYYMMDD, 포함)", example = "20260831", required = true)
            @RequestParam String endYmd,
            @Parameter(description = "시군구명 필터(40자 이내). 생략하거나 비우면 걸지 않는다. 없는 이름이면 400",
                    example = "제주시")
            @RequestParam(required = false) String sggNm,
            @Parameter(description = "읍면동 코드(8자리) 필터. 생략하거나 비우면 걸지 않는다. 없는 코드면 400",
                    example = "50110137")
            @RequestParam(required = false) String emdCd,
            @Parameter(description = "분석 계수 일시 입력 (JSON 문자열, 선택 — CVLCPT_MM_CRTR_VL·CVLCPT_RT_CRTR_VL·"
                    + "CVLCPT_INCRS_WGVL·CVLCPT_INCRS_RT_UPLMT_VL. 생략 = 설정값)",
                    example = "{\"CVLCPT_RT_CRTR_VL\":10}")
            @RequestParam(required = false) String vrblVls,
            @AuthenticationPrincipal OidcUser user) {

        // 🔴 서비스(적재)보다 앞이다 — 400 이면 실행 행이 생기지 않는다
        validatePrdSpan(bgngYmd, endYmd);

        return zoneRcmdtnShareService.analyzeAndRecord(bgngYmd, endYmd, sggNm, emdCd,
                currentUser.resolveUserId(user),    // 실행자 = 로그인 사용자 · 미인증 401 (TSK-4249)
                AnlsVrblOvrd.parse(vrblVls));
    }

    /**
     * 공유주차제 적용 보고서 저장 — 분석 화면의 "내보내기"(TSK-4240 Step 3).
     *
     * <p>🔴 <b>순서가 계약이다</b> — ①로그인 사용자 해석 → ②기간 상한 → ③서비스. 사용자 해석이 서비스보다
     * 앞이라 401 이면 아무것도 저장되지 않는다. 생성자는 principal 에서 해석한 {@code TB_USER} 의 사용자이고
     * (D6·D8) 폴백이 없다 — 미인증 · 사용자 없음 · 정지는 401 이다(D7).
     *
     * <p>🔴 {@code @Valid} 를 빼면 {@code fileFmt}·기간의 {@code @NotBlank}/{@code @Pattern} 이 발동하지 않아
     * 누락·오타가 400 이 아니라 그대로 저장된다. 요청 바디 검증 실패는 Spring 이 400 으로 낸다.
     *
     * <p>⚠️ 조회(GET)의 실행 이력과 <b>따로</b> 실행 1행이 더 남는다(로그인 사용자). 조회 쪽 실행자는 이
     * 작업에서 바꾸지 않는다(D12).
     */
    @Operation(summary = "구역추천 공유주차제 적용 보고서 저장",
            description = "같은 조건으로 다시 계산해 그 결과를 보고서로 저장한다(201) — 화면이 보낸 판정·순번을 받지 않는다. "
                    + "한 번의 호출이 분석 실행 1행 · 분석 결과 1행 · 보고서 1행(직접 생성 MANUAL)을 한 트랜잭션으로 남기고, "
                    + "응답은 저장된 스냅샷 그대로다(rcmdtnTypeCd='SHARE' · share = 조회 응답과 같은 본문 · fee 는 null). "
                    + "🔴 생성자·실행자는 로그인 사용자다(미인증 · 사용자 없음 · 정지는 401 이며 폴백이 없다). "
                    + "🔴 실행 ID 는 응답 머리의 excnId 한 곳이다 — share.excnId 는 null 이다. "
                    + "bgngYmd·endYmd 는 필수이고 sggNm 은 선택이다(생략·빈 값이면 전체 지역). 🔴 읍면동 필터(emdCd)는 받지 않는다. "
                    + "vrblVls(바디 객체)는 조회와 규칙이 같고 적용 내역이 anls_param_json.vrblAjmt 와 응답 머리 vrblAjmt 에 남는다. "
                    + "fileFmt 는 PDF | DOCX | XLSX 만(누락·그 밖 400). "
                    + "🆕 TSK-4340 — 형식과 무관하게 share.wbk(엑셀 재계산 데이터)를 저장·응답한다(스냅샷도 같다 · 조회 GET 에는 없다). "
                    + "share.zones 는 후보만이 아니라 필터에 걸린 읍면동 전부다(smry 의 건수와 맞춘다). "
                    + "기간 형식·순서 오류 · 36개월 초과 · 40자를 넘거나 실재하지 않는 시군구명 · 검토 후보(rvwTrgtYn='Y') 0곳이면 "
                    + "400 이며 어느 경우든 아무것도 저장하지 않는다.")
    @PostMapping("/zone-recommendation/share/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ZoneRcmdtnReportVO createShareReport(@AuthenticationPrincipal OidcUser user,
                                                @Valid @RequestBody ZoneRcmdtnShareReportCreateRequest req) {
        // 🔴 서비스보다 앞 — 401 이면 아무것도 저장되지 않는다
        LoginUserVO me = currentUser.resolve(user);
        validatePrdSpan(req.getBgngYmd(), req.getEndYmd());
        return zoneRcmdtnReportService.createShareReport(req, me.getUserId(), me.getUserNm());
    }

    /**
     * 기간 상한 검증 — {@link ZoneRcmdtnController} 의 {@code validatePrdSpan} 과 <b>같은 식·같은 문구</b>다
     * (그쪽이 private 이라 호출하지 못해 같은 식을 여기 둔다. 상한 값은 그쪽 상수를 참조한다).
     *
     * <p>⚠️ <b>형식 오류·역전은 여기서 판정하지 않는다</b> — 파싱에 실패하면 조용히 통과시키고 서비스의
     * {@code parseYmd} 가 필드명과 함께 400 을 낸다(같은 오류를 두 곳에서 다른 문구로 내지 않는다).
     * 여기서는 <b>폭</b>만 본다. 🔴 기간 상한 판정만 걸친 달 수(월초끼리)로 센다 — 월평균 분모는
     * {@code PrdMonths}(TSK-4284 · D5).
     */
    private static void validatePrdSpan(String bgngYmd, String endYmd) {
        // 시큐어코딩 5.1(TSK-4336) — null 검사를 하나씩 나눠 둔다(동작 동일). 형식·순서 오류는 서비스가 400 을 낸다.
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
        if (months > ZoneRcmdtnController.MAX_PRD_MONTHS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "bgngYmd~endYmd 조회 기간은 " + ZoneRcmdtnController.MAX_PRD_MONTHS
                            + "개월을 넘을 수 없습니다: " + bgng + " ~ " + end + " (" + months + "개월)");
        }
    }

    private static LocalDate tryParse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), YMD);
        } catch (DateTimeParseException e) {
            // 형식이 틀린 날짜는 "값 없음" 으로 본다 — 호출부가 null 을 기본 구간으로 처리한다 (4.2 — TSK-4336)
            log.debug("날짜 파싱 실패 — null 로 처리: value={}", value);
            return null;
        }
    }
}
