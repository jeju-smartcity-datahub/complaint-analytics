package jeju.datahub.menuauthrt.web;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.menuauthrt.service.AuthrtVO;
import jeju.datahub.menuauthrt.service.MenuAuthrtSaveRequest;
import jeju.datahub.menuauthrt.service.MenuAuthrtService;
import jeju.datahub.menuauthrt.service.MenuAuthrtVO;
import lombok.RequiredArgsConstructor;

/**
 * 메뉴 권한 관리 REST 컨트롤러. (설정 화면 "메뉴 권한 관리" 탭 — TSK-4019)
 */
@Tag(name = "메뉴 권한 관리", description = "권한 그룹 · 화면별 허용 권한(TB_AUTHRT / TB_MENU / TB_AUTHRT_MENU) API")
@RestController
@RequestMapping("/api/menu-authorities")
@RequiredArgsConstructor
public class MenuAuthrtController {

    private final MenuAuthrtService menuAuthrtService;

    @Operation(summary = "권한 그룹 목록", description = "사용중인 권한 그룹을 화면 표시 순서(등록일시 오름차순)로 조회한다.")
    @GetMapping("/authorities")
    public List<AuthrtVO> authorities() {
        return menuAuthrtService.selectAuthrtList();
    }

    @Operation(summary = "화면 목록 + 허용 권한",
            description = "권한 지정 대상 화면(폴더 메뉴 제외)을 트리 표시 순서로 조회한다. 각 화면의 허용 권한코드 배열을 함께 내린다.")
    @GetMapping("/menus")
    public List<MenuAuthrtVO> menus() {
        return menuAuthrtService.selectMenuList();
    }

    /**
     * 메뉴 1건의 허용 권한 전체 교체 저장.
     *
     * <p>{@code @Valid} 가 없으면 {@code MenuAuthrtSaveRequest} 의 {@code @NotNull} 이 발동하지 않아
     * 바디 누락({@code {}})이 400 이 아니라 NPE 로 흐른다 — 반드시 함께 둔다.
     *
     * <p>✅ 반환 타입은 {@code MenuAuthrtVO} 그대로다 (TSK-4115 D7 재개정). {@code SUPER} 권한 누락을
     * 400 으로 거절하지 않고 <b>서비스가 자동 보정</b>하므로, 우리가 직접 만드는 오류 바디가 없다.
     */
    @Operation(summary = "메뉴 권한 저장(전체 교체)",
            description = "해당 메뉴의 기존 매핑을 모두 지우고 요청된 권한코드로 재삽입한다(한 트랜잭션, 멱등). "
                    + "응답은 갱신 후의 메뉴 1건이다. "
                    + "없는 메뉴는 404, 폴더 메뉴이거나 사용할 수 없는 권한코드가 섞이면 400. "
                    + "⚠️ SUPER 유형 권한(authrtTypeCd='SUPER')은 요청에서 빠져 있어도 저장 직전에 "
                    + "자동으로 더해진다 — 요청과 저장 결과가 다를 수 있으므로 응답을 정본으로 삼는다. "
                    + "그래서 빈 배열을 보내도 매핑이 완전히 비지는 않는다.")
    @ApiResponse(responseCode = "200", description = "갱신 후의 메뉴 1건",
            content = @Content(schema = @Schema(implementation = MenuAuthrtVO.class)))
    /**
     * 🔴 TSK-4115 Step 7 — <b>표 파생 인가</b>. 롤 리터럴을 쓰지 않는다.
     * 요청자가 M0602(메뉴 권한 관리) 매핑을 갖고 있어야 한다 — 화면과 같은 표
     * ({@code TB_AUTHRT_MENU})를 매 요청 조회하므로 권한을 저장한 즉시 반영된다.
     */
    @PreAuthorize("@menuAccessGuard.can(authentication, 'M0602')")
    @PutMapping("/menus/{menuId}/authorities")
    public MenuAuthrtVO saveAuthorities(@PathVariable("menuId") String menuId,
                                        @Valid @RequestBody MenuAuthrtSaveRequest request) {
        return menuAuthrtService.saveMenuAuthorities(menuId, request.getAuthrtCds());
    }
}
