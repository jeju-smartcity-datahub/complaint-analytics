package jeju.datahub.menuauthrt.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 메뉴 권한 전체 교체 저장 요청 바디. (PUT /api/menu-authorities/menus/{menuId}/authorities)
 *
 * <p>{@code authrtCds} 는 필수지만 <b>빈 배열은 허용</b>한다 — 해당 메뉴의 모든 권한 해제를 뜻한다.
 * 따라서 {@code @NotEmpty} 가 아니라 {@code @NotNull} 이다.
 * 컨트롤러에서 {@code @Valid} 를 빠뜨리면 이 검증이 발동하지 않는다.
 */
@Schema(description = "메뉴 권한 전체 교체 저장 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class MenuAuthrtSaveRequest {

    /** 이 메뉴에 허용할 권한코드 목록. 빈 배열이면 모든 권한 해제. */
    @NotNull(message = "authrtCds 는 필수입니다 (모든 권한 해제는 빈 배열)")
    @Schema(description = "허용할 권한코드 목록 (빈 배열 = 모든 권한 해제)",
            example = "[\"ROLE_SYSTEM_ADMIN\", \"ROLE_USER\"]")
    private List<String> authrtCds;
}
