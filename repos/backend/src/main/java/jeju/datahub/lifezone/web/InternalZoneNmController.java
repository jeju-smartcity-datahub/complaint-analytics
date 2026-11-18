package jeju.datahub.lifezone.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.lifezone.service.LifezoneSupply;
import jeju.datahub.lifezone.service.ZoneNmPrefillResultVO;
import jeju.datahub.lifezone.service.ZoneNmPrefillService;
import lombok.RequiredArgsConstructor;

/**
 * 구역명 캐시 미리 채우기 내부 REST 컨트롤러.
 *
 * <p>🔴 <b>배치({@code zoneNmPrefillJob})만 부르는 내부 API</b> 다. 경로가 {@code /api/internal/**} 아래라
 * {@code SecurityConfig} 의 permitAll · CSRF 면제 · {@code InternalApiKeyFilter}({@code X-API-Key})가
 * 그대로 적용된다 — 보안 설정 수정이 필요 없다.
 *
 * <p>전역 예외 핸들러가 없으므로 잘못된 입력은 {@link ResponseStatusException} 으로 직접 400 을 낸다.
 *
 * <pre>
 * curl -X POST 'http://localhost:8180/api/internal/zone-names/prefill?gridSz=500&amp;limit=100' \
 *   -H 'X-API-Key: local-dev-key'
 * </pre>
 */
@Tag(name = "구역명 미리 채우기(내부)",
        description = "배치 전용 내부 API — 생활권 격자 중심 구역명 캐시(TB_ZONE_NM_CACHE) 미리 채우기. X-API-Key 헤더로 인증한다.")
@RestController
@RequestMapping("/api/internal/zone-names")
@RequiredArgsConstructor
public class InternalZoneNmController {

    /** 한 호출의 외부 조회 상한 최댓값 — 배치 읽기 타임아웃(120s) 안에 끝나야 한다 */
    static final int MAX_LIMIT = 200;

    private final ZoneNmPrefillService zoneNmPrefillService;

    @Operation(summary = "구역명 캐시 미리 채우기 (배치 전용)",
            description = "격자크기 gridSz(1000/500/250/100)의 민원·공영주차장 격자 중 캐시가 없는 것을 점 많은 순으로 "
                    + "최대 limit(1~200, 기본 100)개 조회해 캐시에 채운다. 목록 밖 gridSz · 범위 밖 limit 은 400. "
                    + "remainingCnt 가 0 이면 그 격자크기는 끝났다. X-API-Key 헤더가 틀리면 401, 서버에 키가 없으면 503.")
    @PostMapping("/prefill")
    public ZoneNmPrefillResultVO prefill(@RequestParam int gridSz,
                                         @RequestParam(defaultValue = "100") int limit) {
        if (!LifezoneSupply.GRID_SZS.contains(gridSz)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "gridSz 는 " + LifezoneSupply.GRID_SZS + " 중 하나여야 합니다: " + gridSz);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit 은 1~" + MAX_LIMIT + " 이어야 합니다: " + limit);
        }
        return zoneNmPrefillService.prefill(gridSz, limit);
    }
}
