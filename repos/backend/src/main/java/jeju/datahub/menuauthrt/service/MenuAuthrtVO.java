package jeju.datahub.menuauthrt.service;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 권한 지정 대상 화면(메뉴) 조회 전용 VO. (parking.TB_MENU + TB_AUTHRT_MENU — MyBatis 전용)
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로 자동 처리.
 * 단, {@code authrtCds} 는 LEFT JOIN 결과를 모으는 컬렉션이라 매퍼 XML 의 resultMap 이 직접 매핑한다.
 */
@Schema(description = "권한 지정 대상 화면(메뉴) + 허용 권한 코드")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class MenuAuthrtVO {

    /** 메뉴ID (TB_MENU.menu_id) */
    @Schema(description = "메뉴ID", example = "M01")
    private String menuId;

    /** 상위 메뉴ID (up_menu_id, 최상위는 null) */
    @Schema(description = "상위 메뉴ID (최상위는 null)", example = "M03")
    private String upMenuId;

    /** 메뉴명 (menu_nm) */
    @Schema(description = "메뉴명", example = "대시보드")
    private String menuNm;

    /** 메뉴 경로 (menu_path_nm) */
    @Schema(description = "메뉴 경로(라우트)", example = "/")
    private String menuPathNm;

    /** 메뉴 단계 (menu_lvl, 1=대메뉴 / 2=중메뉴) */
    @Schema(description = "메뉴 단계 (1=대메뉴, 2=중메뉴)", example = "1")
    private Integer menuLvl;

    /** 정렬순서 (sort_seq) */
    @Schema(description = "정렬순서", example = "1")
    private Integer sortSeq;

    /** 구분명 — 권한 매핑에서 파생 (ROLE_USER 매핑 없음 → '관리', 있음 → '메뉴'). TSK-4077 D1 */
    @Schema(description = "구분명 (메뉴 | 관리) — 일반 사용자 권한이 없으면 '관리'", example = "메뉴")
    private String menuSeNm;

    /**
     * 이 메뉴에 허용된 권한코드 목록. 매핑이 없으면 <b>빈 배열</b>(null 이 아니다).
     *
     * <p>매퍼 XML 의 {@code notNullColumn="authrt_cd"} 가 LEFT JOIN 미매칭 행을 걸러 주고,
     * 여기서 빈 리스트로 초기화해 두어 어떤 경로로도 null 이 되지 않게 한다.
     */
    @Schema(description = "허용 권한코드 목록 (매핑이 없으면 빈 배열)")
    private List<String> authrtCds = new ArrayList<>();
}
