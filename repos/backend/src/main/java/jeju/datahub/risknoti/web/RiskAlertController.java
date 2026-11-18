package jeju.datahub.risknoti.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.auth.service.AuthLookupService;
import jeju.datahub.common.DateRanges;
import jeju.datahub.common.web.CurrentUserResolver;
import jeju.datahub.risknoti.service.RiskAlertDailyVO;
import jeju.datahub.risknoti.service.RiskAlertPageVO;
import jeju.datahub.risknoti.service.RiskAlertSearchVO;
import jeju.datahub.risknoti.service.RiskAlertService;
import lombok.RequiredArgsConstructor;

/**
 * 위험단계 알림 이력 REST 컨트롤러. (알림 조회 화면 {@code /alerts/inquiry} — TSK-4028 Step 5)
 *
 * <p>전역 예외 핸들러가 없으므로 잘못된 입력은 {@link ResponseStatusException} 으로 직접 400 을 낸다
 * (보고서 {@code ReportController} 와 동일 방침).
 *
 * <p>⚠️ <b>지역 목록({@code GET /api/regions})은 여기서 만들지 않는다</b> — {@code jeju.datahub.region}
 * 에 이미 있다(D9). 같은 경로를 또 매핑하면 {@code Ambiguous mapping} 으로 기동이 죽는다.
 *
 * <p>🔁 <b>TSK-4202 Step 8 — 세 엔드포인트가 모두 "내게 온 알림" 으로 좁혀졌다</b>(D10).
 * 🔁 <b>TSK-4250 R13 — 조회 화면({@code GET /api/risk-alerts})은 다시 수신자 무관(전 알림)</b>이다.
 * 수신자 기준은 🔔 벨({@code /daily})과 읽음({@code PATCH /{notiId}/read}) 둘에만 남는다.
 * 인증(401)은 세 엔드포인트 모두 그대로다.
 * 🔴 수신자 해석 순서가 중요하다 — {@link #resolveReceiverIds} 참고.
 */
@Tag(name = "위험단계 알림", description = "위험단계 알림 이력 조회·읽음 처리(TB_RISK_STP_NOTI_HSTRY) API")
@RestController
@RequestMapping("/api/risk-alerts")
@RequiredArgsConstructor
public class RiskAlertController {

    /**
     * 위험단계 화이트리스트. 그 외 값은 400 — SQL 에 임의 문자열이 흘러가지 않게 컨트롤러가 끊는다.
     *
     * <p>🔴 출처를 <b>상수로 둔다</b>(공통코드 {@code RISK_LVL_CD} 왕복이 아니라).
     * 조회 필터라 매 요청마다 {@code TB_COM_CD} 를 다녀올 이유가 없고, 값 3종은 Step 0 실측과 같다.
     * 선례는 {@code ReportController.CRT_TYPES}.
     * ⚠️ 쓰기 경로(Step 6 수신자 등록)는 다르다 — 거기선 {@code TB_COM_CD} 대조를 한다.
     */
    private static final Set<String> RISK_LVL_CDS = Set.of("CRIT", "WARN", "CAUT");

    /** 페이지 크기 상한 (D10). 초과하면 400 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 시안 select 의 '전체' 값 — 프론트가 그대로 흘려보내므로 서버가 접는다 */
    private static final String ALL = "all";

    /**
     * 종료일 경계 (23:59:59). {@code ocrn_dt} 가 TIMESTAMP 라 날짜를 그대로 비교하면
     * <b>종료일 당일 알림이 통째로 빠진다.</b>
     */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    /** 기준일 계산용 존. 배치 RiskAlertProperties 와 같은 값이어야 판정일과 화면 기준일이 갈리지 않는다 (D8) */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RiskAlertService riskAlertService;

    /** 🔴 401 판정기 (TSK-4240 Step 1). {@code resolveUserId} 가 돌려주는 것은 {@code user_id} 다 */
    private final CurrentUserResolver currentUser;

    /** 🔴 수신자 해석기. 받는 키는 {@code cert_id}(= {@code user.getSubject()}) 다 — {@code user_id} 가 아니다 */
    private final AuthLookupService authLookupService;

    /**
     * 알림 이력 목록 + 요약 (D8 — 한 응답).
     *
     * <p>필터 4종(단계·시군구·읍면동·기간)이 목록·총건수·요약에 <b>동시에</b> 반영된다.
     *
     * <p>🔁 <b>TSK-4250 R13 — 수신자 필터가 없다</b>(전 알림). TSK-4202 D10 의 "조회 화면도 내게 온 알림만" 을
     * 이 엔드포인트에 한해 되돌렸다(사용자 결정 — "리스트에서는 내 것만 거르지 말고 전부").
     * 🔴 그래도 <b>인증은 판정한다</b> — {@code resolveUserId} 의 401(미인증·{@code TB_USER} 부재·정지)은 유지한다.
     */
    @Operation(summary = "위험단계 알림 이력 조회",
            description = "위험단계 알림 이력을 최신순(ocrn_dt DESC, noti_id DESC)으로 페이징 조회하고 "
                    + "같은 필터 기준 요약(전체·심각·경고·주의 건수, 민원 건수 합계)을 함께 내린다. "
                    + "riskLvlCd·sggNm·emdCd 는 생략하거나 빈 값·all 이면 전체다. "
                    + "from·to 는 yyyy-MM-dd 이며 종료일은 23:59:59 까지 포함한다. "
                    + "page 는 0-base(기본 0), size 는 기본 10·최대 100. "
                    + "riskLvlCd 가 화이트리스트 밖이거나 from > to 이거나 page·size 가 범위를 벗어나면 400. "
                    + "수신자 여부와 무관하게 전 알림을 대상으로 한다(벨·읽음만 수신자 기준).")
    @GetMapping
    public RiskAlertPageVO riskAlerts(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(name = "riskLvlCd", required = false) String riskLvlCd,
            @RequestParam(name = "sggNm", required = false) String sggNm,
            @RequestParam(name = "emdCd", required = false) String emdCd,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {

        // 🔴 0) 인증 판정이 **가장 먼저**다 — 미인증(401)을 입력 검증(400)보다 앞에 둔다.
        //    🔁 TSK-4250 R13 — 수신자 해석은 하지 않는다(조회 화면은 전 알림). 반환값(user_id)은 버린다.
        currentUser.resolveUserId(user);

        // 🔴 1) 필터 3종은 '검증 전에' 정규화한다.
        //    빈 문자열을 그대로 두면 h.risk_lvl_cd = '' 로 0건이 되고(<if> 는 null 만 걸러낸다),
        //    시안이 '전체' 값으로 쓰는 'all' 은 화이트리스트에 걸려 400 이 난다.
        String normalizedRiskLvlCd = normalizeFilter(riskLvlCd);
        String normalizedSggNm = normalizeFilter(sggNm);
        String normalizedEmdCd = normalizeFilter(emdCd);

        // 2) 정규화 결과가 null 이 아닐 때만 화이트리스트를 건다 (null = 전체 조회라 검증 대상이 아니다).
        //    ⚠️ sggNm·emdCd 는 존재 검증을 하지 않는다 — 조회 필터라 없는 지역이면 0건이 정답이고,
        //       요청마다 TB_ADMDST 를 왕복할 이유가 없다. 존재 검증은 쓰기 경로(Step 6)의 몫이다.
        if (normalizedRiskLvlCd != null && !RISK_LVL_CDS.contains(normalizedRiskLvlCd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 위험단계입니다: " + normalizedRiskLvlCd + " (CRIT | WARN | CAUT)");
        }
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 는 0 이상이어야 합니다: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size 는 1 ~ " + MAX_PAGE_SIZE + " 범위여야 합니다: " + size);
        }
        DateRanges.validate(from, to);

        RiskAlertSearchVO search = new RiskAlertSearchVO();
        search.setRiskLvlCd(normalizedRiskLvlCd);
        search.setSggNm(normalizedSggNm);
        search.setEmdCd(normalizedEmdCd);
        // 3) 기간 정규화 — from 은 00:00:00, to 는 23:59:59 로 펼친다
        search.setFromDt(from != null ? from.atStartOfDay() : null);
        search.setToDt(to != null ? to.atTime(END_OF_DAY) : null);
        search.setSize(size);
        // offset 은 long 으로 계산한다 — page 가 크면 page * size 가 int 범위를 넘어
        // 음수 OFFSET 으로 뒤집힐 수 있다 (SQL 오류 500)
        search.setOffset((long) page * size);

        return riskAlertService.selectAlerts(search, page);
    }

    @Operation(summary = "상단 알림 벨 — 판정일 하루치 알림",
            description = "배치가 가장 최근에 판정한 하루치(=전일) 알림을 개수 제한 없이 내린다. "
                    + "totalCnt 는 그날 생성된 전체 건수(헤더 문구용), unreadCnt 는 미읽음 건수(배지용)다. "
                    + "기준일은 서버가 Asia/Seoul 로 확정하며 요청 파라미터가 없다.")
    @GetMapping("/daily")
    public RiskAlertDailyVO dailyAlerts(@AuthenticationPrincipal OidcUser user) {
        List<Long> rcvrIds = resolveReceiverIds(user);
        // 🔴 DB CURRENT_DATE 가 아니라 여기서 확정한다 (D8).
        //    서버 타임존이 UTC 면 한국 시각 오전 9시 이전에 하루가 밀린다 — 배치가 겪은 문제와 같다.
        LocalDate baseDe = LocalDate.now(SEOUL).minusDays(1);
        return riskAlertService.selectDailyAlerts(baseDe, rcvrIds);
    }

    /**
     * 알림 읽음 처리 — 팝오버 행 클릭 (TSK-4086).
     *
     * <p>경로가 세그먼트 2개({@code /{notiId}/read})라 세그먼트 1개인 {@code /daily} 와 겹치지 않는다.
     */
    @Operation(summary = "알림 읽음 처리",
            description = "알림 1건을 읽음(prsl_yn='Y')으로 바꾸고 읽은일시를 남긴다. "
                    + "이미 읽은 건을 다시 호출해도 204 이며 읽은일시는 유지된다(멱등). "
                    + "없는 notiId 면 404.")
    @PatchMapping("/{notiId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal OidcUser user,
                         @PathVariable("notiId") Long notiId) {
        riskAlertService.markRead(notiId, resolveReceiverIds(user));
    }

    /**
     * 요청자의 <b>활성 수신자 ID 목록</b> 해석 (TSK-4202 Step 8 · D10).
     *
     * <p>🔴 <b>두 호출의 역할이 다르다 — 순서도 바꿀 수 없다.</b>
     * <ol>
     *   <li>{@code currentUser.resolveUserId(user)} 는 <b>401 판정기</b>다. principal 이 null 이거나
     *       {@code TB_USER} 에 없거나 정지면 여기서 401 로 끊긴다. 🔴 <b>반환값({@code user_id})은
     *       쓰지 않는다</b> — 수신자 조회 키가 아니다.</li>
     *   <li>{@code findActiveReceiverIds} 가 받는 것은 <b>{@code cert_id}(= OIDC {@code sub} =
     *       {@code user.getSubject()})</b> 다. 🔴 위에서 받은 {@code user_id} 를 넘기면 조인이 성립하지
     *       않아 <b>전원이 조용히 0건</b>이 된다.</li>
     * </ol>
     *
     * <p>🔴 <b>미인증(401)과 비수신자(200 + 0건)는 다른 상태다 — 뭉개지 않는다.</b>
     * 로그인은 했는데 수신자 등록이 없는 사람에게 401 을 주면 프론트가 로그인 화면으로 튕긴다.
     *
     * @return 활성 수신자 ID 목록. 수신자가 아니면 빈 목록({@code null} 이 아니다)
     */
    private List<Long> resolveReceiverIds(OidcUser user) {
        currentUser.resolveUserId(user);                               // ① 401 먼저 (반환값은 버린다)
        return authLookupService.findActiveReceiverIds(user.getSubject()); // ② 키는 cert_id
    }

    /**
     * 필터 값 정규화 — 빈 문자열과 시안의 '전체' 값({@code all})을 null 로 접는다.
     *
     * <p>null 은 "필터 없음" 이라 {@code alertWhere} 의 {@code <if>} 가 조건 자체를 빼버린다.
     */
    private static String normalizeFilter(String value) {
        return (value == null || value.isBlank() || ALL.equals(value)) ? null : value;
    }
}
