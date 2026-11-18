package jeju.datahub.risknoti.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import jeju.datahub.common.PageResponse;
import jeju.datahub.risknoti.service.NotiReceiverActivationRequest;
import jeju.datahub.risknoti.service.NotiReceiverCandidateVO;
import jeju.datahub.risknoti.service.NotiReceiverRequest;
import jeju.datahub.risknoti.service.NotiReceiverUpdateRequest;
import jeju.datahub.risknoti.service.NotiReceiverVO;
import jeju.datahub.risknoti.service.RiskNotiService;
import lombok.RequiredArgsConstructor;

/**
 * 알림 수신자 REST 컨트롤러. (알림 관리 화면 {@code /alerts/management} — TSK-4028 Step 6)
 *
 * <p>전역 예외 핸들러가 없으므로 잘못된 입력은 {@link ResponseStatusException} 으로 직접 400 을 낸다
 * (보고서 {@code ReportController} · 알림 이력 {@link RiskAlertController} 와 동일 방침).
 * 등록 중복(409 {@code RCVR_DUPLICATED}, TSK-4250 R9)만 이 컨트롤러 전용 {@link NotiReceiverExceptionHandler} 가
 * {@code code} 본문으로 옮긴다.
 *
 * <p>🔴 <b>DELETE 는 소프트 삭제다</b> (D5) — 행도 단계 매핑도 지우지 않고 {@code del_yn='Y'} 로
 * 표시한다. 목록에서만 사라지고 과거 알림 이력·발송기록은 그대로 남는다.
 */
@Tag(name = "알림 수신자", description = "위험단계 알림 수신자(TB_NOTI_RCVR) 관리 API")
@RestController
@RequestMapping("/api/noti-receivers")
@RequiredArgsConstructor
public class NotiReceiverController {

    /** 페이지 크기 상한 (D10). 초과하면 400 — 검증은 각 컨트롤러 책임이다 */
    private static final int MAX_PAGE_SIZE = 100;

    private final RiskNotiService riskNotiService;

    /**
     * 수신자 목록 (페이징, 단계 배열 포함).
     *
     * <p>정렬은 {@code rcvr_id} 고정이고 소프트 삭제분은 보이지 않는다 (D5).
     */
    @Operation(summary = "알림 수신자 목록",
            description = "알림 수신자를 rcvr_id 순으로 페이징 조회한다. 각 행은 수신 단계 배열(riskLvlCds)과 "
                    + "지역 표시명(regionNm — '제주시 연동' | '제주시 전체' | '전체 지역')을 포함한다. "
                    + "삭제된 수신자(del_yn='Y')는 목록과 총건수 모두에서 제외된다. "
                    + "page 는 0-base(기본 0), size 는 기본 10·최대 100. 범위를 벗어나면 400.")
    @GetMapping
    public PageResponse<NotiReceiverVO> notiReceivers(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {

        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 는 0 이상이어야 합니다: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size 는 1 ~ " + MAX_PAGE_SIZE + " 범위여야 합니다: " + size);
        }
        return riskNotiService.selectReceivers(page, size);
    }

    /**
     * 수신자 후보 목록 (TSK-4250 R12) — 등록 모달의 "소속 → 사용자" select 를 채운다.
     *
     * <p>⚠️ 경로 충돌 없음 — {@code /{rcvrId}} 는 {@code PUT}·{@code DELETE} 에만, {@code /{rcvrId}/activation} 은
     * {@code PATCH} 에만 매핑돼 있고, 같은 메서드였더라도 리터럴 경로가 패턴보다 우선한다.
     */
    @Operation(summary = "알림 수신자 후보 목록",
            description = "활성 사용자(TB_USER.use_yn='Y') 전원을 소속부서명·이메일과 함께 돌려준다. "
                    + "registered=true 면 이미 활성 수신자가 있는 사용자라 선택할 수 없다(한 사용자 = 수신자 1행). "
                    + "정렬은 부서 정렬순서 → 부서명 → 사용자명이며 무소속(deptCd=null)은 맨 뒤다. "
                    + "M0404(위험단계 알림 관리) 권한이 없으면 403.")
    /**
     * 🔴 첫 인가 GET(TSK-4250 Q1) — 전 사용자 이메일 목록이라 수신자 관리 권한자(M0404)만 본다.
     * 다른 조회(GET)는 아직 인가 대상이 아니다(TSK-4115) — 이 엔드포인트만 예외다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0404')")
    @GetMapping("/candidates")
    public List<NotiReceiverCandidateVO> notiReceiverCandidates() {
        return riskNotiService.getReceiverCandidates();
    }

    /**
     * 수신자 등록 — <b>201</b> + 생성된 1건.
     *
     * <p>{@code @Valid} 가 없으면 {@link NotiReceiverRequest} 의 형식 검증이 통째로 발동하지 않는다.
     * 코드·지역 실재 여부는 DB 를 봐야 알아 서비스가 판단한다(어느 쪽이든 400).
     */
    @Operation(summary = "알림 수신자 등록",
            description = "사용자(TB_USER) 1명을 알림 수신자로 등록한다 — 이름·이메일·부서는 서버가 사용자 행에서 복사한다. "
                    + "수신 단계 매핑(TB_NOTI_RCVR_LVL)과 수신 지역 매핑(TB_NOTI_RCVR_RGN)을 함께 등록한다. "
                    + "regions 는 최소 1개이며 전체 지역은 원소 {} 다(빈 배열·누락은 400). "
                    + "rcvrId 는 DB identity 가 매기고 use_yn='Y'·del_yn='N'·통보채널 EMAIL 은 서버가 채운다. "
                    + "riskLvlCds·regions 의 중복은 서버가 제거한다. "
                    + "없거나 사용 중지된 userId·없는 코드값(riskLvlCds)·없는 지역(emdCd·sggNm)·"
                    + "지역 겹침(전체 지역 + 다른 지역, 시군구 전체 + 그 시군구 읍면동)은 400. "
                    + "같은 사용자의 활성 수신자가 이미 있으면 409 {\"code\":\"RCVR_DUPLICATED\"}.")
    /**
     * 🔴 TSK-4115 Step 7 — <b>표 파생 인가</b>. 롤 리터럴을 쓰지 않는다.
     * 요청자가 M0404(위험단계 알림 관리) 매핑을 갖고 있어야 한다 — 화면과 같은 표
     * ({@code TB_AUTHRT_MENU})를 매 요청 조회하므로 권한을 저장한 즉시 반영된다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0404')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotiReceiverVO createNotiReceiver(@Valid @RequestBody NotiReceiverRequest request) {
        return riskNotiService.createReceiver(request);
    }

    /**
     * 수신자 수정 (TSK-4250 R10) — 지역·단계를 통째 교체하고 갱신된 1건을 돌려준다.
     *
     * <p>사람 필드는 요청 DTO 에 없다(받지 않는다). 활성화는 {@code PATCH /{rcvrId}/activation} 이 맡는다.
     */
    @Operation(summary = "알림 수신자 수정 (지역·단계 통째 교체)",
            description = "수신 지역(TB_NOTI_RCVR_RGN)과 수신 단계(TB_NOTI_RCVR_LVL)를 요청 내용으로 통째 교체한다 "
                    + "(이전 행은 남지 않는다). 사람 정보(이름·이메일·부서)는 수정하지 않는다 — TB_USER 가 정본이다. "
                    + "활성화는 PATCH /{rcvrId}/activation 을 쓴다. "
                    + "이미 적재된 알림 매핑은 바뀌지 않고 다음 위험단계 판정부터 반영된다(과거분은 백필 트리거). "
                    + "regions 는 최소 1개이며 전체 지역은 원소 {} 다. 중복은 서버가 제거한다. "
                    + "없는 코드값·없는 지역·지역 겹침(전체 지역 + 다른 지역, 시군구 전체 + 그 시군구 읍면동)·빈 배열은 400, "
                    + "없거나 이미 삭제된 rcvrId 는 404, M0404(위험단계 알림 관리) 권한이 없으면 403.")
    /**
     * 🔴 TSK-4115 Step 7 — <b>표 파생 인가</b>. 롤 리터럴을 쓰지 않는다.
     * 요청자가 M0404(위험단계 알림 관리) 매핑을 갖고 있어야 한다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0404')")
    @PutMapping("/{rcvrId}")
    public NotiReceiverVO updateNotiReceiver(
            @PathVariable("rcvrId") Long rcvrId,
            @Valid @RequestBody NotiReceiverUpdateRequest request) {
        return riskNotiService.updateReceiver(rcvrId, request);
    }

    /** 수신자 활성화 토글 ({@code use_yn}) — 갱신된 1건. 없거나 이미 삭제된 수신자면 404 */
    @Operation(summary = "알림 수신자 활성화 토글",
            description = "화면의 토글 스위치에 대응한다. use_yn 만 바꾸며 Y/N 외 값은 400, "
                    + "없거나 이미 삭제된 rcvrId 는 404. 삭제(del_yn)와는 뜻이 다르다.")
    /**
     * 🔴 TSK-4115 Step 7 — <b>표 파생 인가</b>. 롤 리터럴을 쓰지 않는다.
     * 요청자가 M0404(위험단계 알림 관리) 매핑을 갖고 있어야 한다 — 화면과 같은 표
     * ({@code TB_AUTHRT_MENU})를 매 요청 조회하므로 권한을 저장한 즉시 반영된다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0404')")
    @PatchMapping("/{rcvrId}/activation")
    public NotiReceiverVO updateNotiReceiverActivation(
            @PathVariable("rcvrId") Long rcvrId,
            @Valid @RequestBody NotiReceiverActivationRequest request) {
        return riskNotiService.updateReceiverActivation(rcvrId, request.getUseYn());
    }

    /**
     * 수신자 삭제 — <b>204</b>. 🔴 <b>소프트 삭제</b>({@code del_yn='Y'} UPDATE)다 (D5).
     *
     * <p>행도 단계 매핑도 지우지 않는다 — 발송기록({@code TB_NOTI_HSTRY_RCVR})이 {@code rcvr_id} 로
     * 수신자를 참조하므로 하드 삭제는 FK 위반이고, 지우면 과거 발송기록이 함께 날아간다.
     */
    @Operation(summary = "알림 수신자 삭제 (소프트 삭제)",
            description = "행을 지우지 않고 del_yn='Y' 로 표시한다. 목록·총건수에서만 사라지고 "
                    + "단계 매핑과 과거 알림 이력은 그대로 남는다. "
                    + "없거나 이미 삭제된 rcvrId 는 404.")
    /**
     * 🔴 TSK-4115 Step 7 — <b>표 파생 인가</b>. 롤 리터럴을 쓰지 않는다.
     * 요청자가 M0404(위험단계 알림 관리) 매핑을 갖고 있어야 한다 — 화면과 같은 표
     * ({@code TB_AUTHRT_MENU})를 매 요청 조회하므로 권한을 저장한 즉시 반영된다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0404')")
    @DeleteMapping("/{rcvrId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNotiReceiver(@PathVariable("rcvrId") Long rcvrId) {
        riskNotiService.deleteReceiver(rcvrId);
    }
}
