package jeju.datahub.menuauthrt.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 권한 그룹 조회 전용 VO. (parking.TB_AUTHRT — MyBatis 전용)
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로 자동 처리.
 */
@Schema(description = "권한 그룹")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AuthrtVO {

    /** 권한코드 (TB_AUTHRT.authrt_cd) */
    @Schema(description = "권한코드", example = "ROLE_SYSTEM_ADMIN")
    private String authrtCd;

    /** 권한명 (authrt_nm) */
    @Schema(description = "권한명", example = "시스템 관리자")
    private String authrtNm;

    /** 권한설명 (authrt_expln) */
    @Schema(description = "권한설명", example = "전체 시스템 설정")
    private String authrtExpln;

    /**
     * 권한유형코드 (TB_AUTHRT.authrt_type_cd). 유형이 없으면 null.
     *
     * <p>TSK-4115 D7 재개정 — {@code SUPER} 유형 권한은 <b>모든 화면 메뉴에 접근</b>한다.
     * 화면에서 뺄 수 없고(체크박스가 항상 체크 + 비활성), 저장 요청에서 빠져 있으면
     * {@code saveMenuAuthorities} 가 도로 채워 넣는다(400 이 아니라 자동 보정).
     *
     * <p>🔴 프론트도 백엔드도 <b>롤 이름을 보지 않는다</b> — 이 유형만 본다. 권한 이름이 바뀌어도
     * 코드는 그대로다. 프론트는 이 값을 받아 체크박스를 고정한다.
     */
    @Schema(description = "권한유형코드 (SUPER = 전 메뉴 접근 보장 · 회수 불가). 유형이 없으면 null",
            example = "SUPER")
    private String authrtTypeCd;
}
