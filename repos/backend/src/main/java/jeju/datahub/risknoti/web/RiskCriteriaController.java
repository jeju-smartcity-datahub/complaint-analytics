package jeju.datahub.risknoti.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.risknoti.service.RiskCriteriaRequest;
import jeju.datahub.risknoti.service.RiskCriteriaVO;
import jeju.datahub.risknoti.service.RiskNotiService;
import lombok.RequiredArgsConstructor;

/**
 * 위험단계 알림 기준 REST 컨트롤러. (알림 관리 화면 {@code /alerts/management} — TSK-4028 Step 6)
 *
 * <p>기준은 <b>전역 단일</b>이다 (D18) — 3행(단계별 1행)이 제주도 전 지역·전 수신자에 똑같이
 * 적용된다. 사람별·부서별·지역별 기준은 없다.
 *
 * <p>응답은 래핑 없는 <b>최상위 배열</b>이고 요청만 {@code {criteria:[...]}} 로 감싼다.
 * 순서는 {@code TB_COM_CD.sort_seq}(CRIT → WARN → CAUT)이며 프론트는 받은 순서를 그대로 렌더한다.
 *
 * <p>전역 예외 핸들러가 없으므로 잘못된 입력은 {@link ResponseStatusException} 으로 직접 400 을 낸다.
 */
@Tag(name = "위험단계 알림 기준", description = "위험단계별 알림 발생 기준(TB_RISK_STP_NOTI_CRTR) API")
@RestController
@RequestMapping("/api/risk-criteria")
@RequiredArgsConstructor
public class RiskCriteriaController {

    private final RiskNotiService riskNotiService;

    /** 단계별 기준 3건 ({@code sort_seq} 순 — 심각·경고·주의) */
    @Operation(summary = "위험단계 알림 기준 조회",
            description = "단계별 증가율·최소 민원수 기준 3건을 TB_COM_CD.sort_seq 순(CRIT → WARN → CAUT)으로 내린다. "
                    + "단계 라벨은 응답에 없다 — 프론트가 코드값에서 만든다.")
    @GetMapping
    public List<RiskCriteriaVO> riskCriteria() {
        return riskNotiService.selectCriteria();
    }

    /**
     * 단계별 기준 3건 일괄 저장 (UPSERT — D7) → 갱신된 3건.
     *
     * <p>🔴 <b>부분 저장을 허용하지 않는다</b> — 단계가 하나라도 빠지면 400.
     * {@code @Valid} 가 없으면 {@link RiskCriteriaRequest} 의 범위·자릿수 검증이 발동하지 않는다.
     *
     * <p>⚠️ 기준을 바꿔도 과거 알림 이력의 {@code incrs_rt_thrsh} 는 건드리지 않는다
     * (D7 — 판정 당시 스냅샷이라 소급 수정하면 의미가 사라진다).
     */
    @Operation(summary = "위험단계 알림 기준 일괄 저장",
            description = "3단계 기준을 한 번에 UPSERT 한다(thrsh_id = 'THRSH_' + riskLvlCd 고정). "
                    + "단계가 하나라도 빠지거나 중복이거나 incrsRt 가 0 이하·100 초과·소수 3자리 이상이면 400. "
                    + "minCvlcptCnt(최소 민원수 하한)는 필수이며 0~1000 범위를 벗어나면 400. "
                    + "bscMinCvlcptCnt(기본값)는 요청에서 받지 않는다 — 응답 전용 불변값이다. "
                    + "응답은 저장 후 재조회한 3건이며 GET 과 순서·형태가 같다. "
                    + "과거 알림 이력의 기준 스냅샷(incrs_rt_thrsh)은 바뀌지 않는다.")
    /**
     * 🔴 TSK-4115 Step 7 — <b>표 파생 인가</b>. 롤 리터럴을 쓰지 않는다.
     * 요청자가 M0404(위험단계 알림 관리) 매핑을 갖고 있어야 한다 — 화면과 같은 표
     * ({@code TB_AUTHRT_MENU})를 매 요청 조회하므로 권한을 저장한 즉시 반영된다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0404')")
    @PutMapping
    public List<RiskCriteriaVO> saveRiskCriteria(@Valid @RequestBody RiskCriteriaRequest request) {
        return riskNotiService.saveCriteria(request);
    }
}
