package jeju.datahub.analysis.web;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.auth.service.LoginUserVO;
import jeju.datahub.common.web.CurrentUserResolver;
import jeju.datahub.analysis.service.HotspotAnalysisRequest;
import jeju.datahub.analysis.service.HotspotAnalysisResultVO;
import jeju.datahub.analysis.service.HotspotAnalysisService;
import jeju.datahub.analysis.service.HotspotReportCreateRequest;
import jeju.datahub.analysis.service.HotspotReportService;
import jeju.datahub.analysis.service.HotspotReportVO;
import lombok.RequiredArgsConstructor;

/**
 * 불법주차 집중구역 분석 REST 컨트롤러. (분석 화면 "불법 주차 집중 구역 분석" — TSK-4032)
 *
 * <p><b>GET 인 이유</b> — 분석 결과를 저장하지 않는 순수 조회다(D7). 조건이 URL 에 남아
 * 링크 공유·재현이 쉽고, 응답이 조건·군집 파라미터·가중치를 함께 되비추므로 응답만으로
 * 결과를 설명할 수 있다.
 *
 * <p>이 경로를 덮는 전역 예외 핸들러가 없으므로(agent 도메인 한정 advice 1건은 {@code assignableTypes}
 * 로 격리돼 여기 오지 않는다 — TSK-4121) 잘못된 입력은 바인딩 검증
 * ({@code @Valid} → 400)과 서비스의 {@link ResponseStatusException} 두 겹으로 낸다 —
 * TSK-4019·TSK-4020·TSK-4026 과 같은 방침이다.
 *
 * <p>⚠️ 쿼리 파라미터에 {@code @Min}/{@code @Max} 를 직접 붙이고 클래스에 {@code @Validated} 를
 * 다는 방식은 <b>쓰지 않는다</b> — 핸들러가 없어 {@code ConstraintViolationException} 이 500 으로
 * 나간다. 제약은 {@link HotspotAnalysisRequest} 안에 두고 {@code @Valid} 로 발동시킨다.
 */
@Tag(name = "집중구역 분석",
        description = "불법주차 집중구역(핫스팟) 군집 분석 — PostGIS DBSCAN + 가중합 점수 "
                + "(조회 GET /hotspot · 보고서 저장 POST /hotspot/reports)")
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class HotspotAnalysisController {

    private final HotspotAnalysisService hotspotAnalysisService;

    private final HotspotReportService hotspotReportService;

    /** 로그인 사용자 해석(401 판정은 그 한 곳이다) */
    private final CurrentUserResolver currentUser;

    /**
     * 🔴 보고서 경로 전용 칸 {@code vrblVlsMap} 을 쿼리로 채우지 못하게 막는다 (TSK-4325 Step 1).
     *
     * <p>세터를 패키지 전용으로 두었지만 {@code vrblVlsMap[KEY]=값} 은 게터로 Map 을 꺼내 넣으려다
     * {@code InvalidPropertyException} → <b>500</b> 이 난다(2026-11-06 실측). 금지 필드는 바인더가 조용히 버린다 —
     * 값은 무시되고 함께 온 {@code vrblVls} 가 적용된다. 🔴 조회 요청에만 건다 — 보고서 저장은
     * {@code @RequestBody} 라 이 바인더를 타지 않는다. 이름은 {@code @ParameterObject} 모델 속성의 기본 이름(클래스명)이다.
     */
    @InitBinder("hotspotAnalysisRequest")
    void disallowVrblVlsMap(WebDataBinder binder) {
        binder.setDisallowedFields("vrblVlsMap*");
    }

    /**
     * 집중구역 순위 조회.
     *
     * <p>🔴 {@code @Valid} 를 빼면 {@link HotspotAnalysisRequest} 의 형식·범위 제약이 <b>통째로</b>
     * 발동하지 않는다. 서비스도 같은 검증을 하지만(직접 호출하는 내부 사용처가 있다) 두 겹을
     * 유지한다 — 컨트롤러 쪽이 없으면 오류 메시지가 필드명을 잃는다.
     *
     * <p>결과 0건은 오류가 아니라 <b>빈 {@code zones} + 200</b> 이다(D11) — 조회 기간에 데이터가
     * 없거나 군집이 형성되지 않은 정상 상태이며, 이때도 원천 보유 구간을 함께 내린다.
     */
    @Operation(summary = "불법주차 집중구역 분석",
            description = "기간(bgngYmd·endYmd, YYYYMMDD)의 민원·단속 좌표를 PostGIS DBSCAN 으로 군집화해 "
                    + "종합점수(조회 결과 1위 = 100 인 상대 점수) 내림차순으로 "
                    + "주의(40점) 이상 구역만 반환한다 — 그 미달 구역은 응답에 오지 않는다. "
                    + "sggNm·emdCd 는 생략하면 전 지역이며(빈 값은 400), limit 은 생략하면 전량이다(상한 없음). "
                    + "cvlcptWgvl·crdnWgvl(%)을 함께 보내면 그 값으로 분석하고 설정은 바꾸지 않는다 — 둘 다 생략하면 설정값이며, "
                    + "한쪽만 · 합계가 100 이 아님 · 설정 범위 밖 · 소수 셋째 자리 이상 · 꺼진 지표가 있으면 400 이다. "
                    + "vrblVls(JSON 문자열)로 등급 비율 GRD_BNDRY1~3 을 일시 조정할 수 있다 — 설정은 바꾸지 않으며, "
                    + "목록 밖 코드(COMPLAINT 등) · 범위 밖 · 소수 · 심각<경고<주의 위반이면 400 이다. 적용 내역은 응답 vrblAjmt 다. "
                    + "응답에 적용 가중치·군집 파라미터(eps·minPoints)·원천 보유 구간을 함께 담아 결과를 재현할 수 있다. "
                    + "결과가 0건이어도 200 이다.")
    @GetMapping("/hotspot")
    public HotspotAnalysisResultVO hotspot(@ParameterObject @Valid HotspotAnalysisRequest request) {
        return hotspotAnalysisService.analyze(request);
    }

    /**
     * 집중구역 분석 보고서 생성 (분석 화면 "내보내기" — TSK-4078).
     *
     * <p>같은 클래스의 조회 핸들러와 달리 <b>쓰기</b>다 — 한 번의 호출이 실행 이력·순위·보고서
     * 세 표에 정확히 한 벌을 남긴다. 조회와 한 컨트롤러에 두는 이유는 URL 접두사({@code /api/analysis})와
     * 조건 계약을 공유하기 때문이며, 저장 규칙은 전부 {@link HotspotReportService} 안에 있다.
     *
     * <p>🔴 {@code @Valid} 를 빼면 {@link HotspotReportCreateRequest} 의 제약({@code fileFmt}
     * 화이트리스트·기간 형식)이 <b>통째로</b> 발동하지 않아, 빈 바디({@code {}})가 400 이 아니라
     * NPE(500)로 흐른다 — 전역 예외 핸들러가 없어 그대로 500 이 나간다.
     *
     * <p>결과 0건은 조회와 달리 <b>400</b> 이다 — 열 수 없는 빈 보고서를 목록에 남기지 않는다.
     */
    @Operation(summary = "집중구역 분석 보고서 생성",
            description = "같은 조건으로 서버가 다시 분석해 그 결과를 보고서로 저장한다(화면이 보낸 숫자는 쓰지 않는다). "
                    + "실행 이력(TB_ANLS_EXCN) 1행 · 분석 결과(TB_ANLS_RSLT) 1행 · 보고서(TB_RPTP) 1행을 "
                    + "한 트랜잭션으로 남기고, 저장된 스냅샷을 그대로 201 로 돌려준다. "
                    + "cvlcptWgvl·crdnWgvl(%)은 조회와 규칙이 같고, 적용값이 anls_param_json.weights 에 남는다(설정은 바꾸지 않는다). "
                    + "vrblVls(바디 객체)도 조회와 규칙이 같고, 적용 내역이 anls_param_json.vrblAjmt 에 남는다. "
                    + "fileFmt 는 PDF | DOCX 이며 그 밖의 값이면 400, 분석 결과가 0건이어도 400 이다(저장하지 않는다).")
    @PostMapping("/hotspot/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public HotspotReportVO createHotspotReport(@AuthenticationPrincipal OidcUser user,
                                               @Valid @RequestBody HotspotReportCreateRequest request) {
        // 🔴 서비스보다 앞 — 401 이면 아무것도 저장되지 않는다
        LoginUserVO me = currentUser.resolve(user);
        return hotspotReportService.createReport(request, me.getUserId(), me.getUserNm());
    }
}
