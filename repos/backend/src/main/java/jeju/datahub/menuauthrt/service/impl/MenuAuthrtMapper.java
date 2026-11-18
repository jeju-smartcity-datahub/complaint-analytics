package jeju.datahub.menuauthrt.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.menuauthrt.service.AuthrtVO;
import jeju.datahub.menuauthrt.service.MenuAuthrtVO;

/**
 * 메뉴 권한 관리 MyBatis 매퍼. SQL 은 resources/mapper/menuauthrt/MenuAuthrtMapper.xml.
 *
 * <p>다중 테이블 LEFT JOIN + 컬렉션 집계 조회라 영속성 규칙상 MyBatis 담당이다.
 * {@code MyBatisConfig} 가 {@code @Mapper} 애노테이션이 붙은 인터페이스만 스캔하므로 애노테이션은 필수다.
 */
@Mapper
public interface MenuAuthrtMapper {

    /** 사용중인 권한 그룹 목록 (등록일시 오름차순 = 화면 표시 순서) */
    List<AuthrtVO> selectAuthrtList();

    /** 권한 지정 대상 화면 목록 + 허용 권한코드 배열 (트리 표시 순서) */
    List<MenuAuthrtVO> selectMenuList();

    /**
     * 메뉴 단건 조회. 엔드포인트로 노출하지 않고 저장 API(Step 4)의 응답·오류 판정에서만 쓴다.
     * 폴더 메뉴 판정(400)을 위해 {@code menu_path_nm IS NOT NULL} 조건을 걸지 않는다.
     *
     * @return 존재하지 않거나 미사용(use_yn='N') 메뉴면 null
     */
    MenuAuthrtVO selectMenuById(@Param("menuId") String menuId);

    /**
     * <b>{@code SUPER} 유형</b> 권한코드 목록 (TSK-4115 Step 2 / D7 재개정).
     *
     * <p>저장 API 가 이 목록을 요청에 union 해 "SUPER 권한은 어느 메뉴에서도 빠질 수 없다" 를 보장한다.
     *
     * <p>🔴 롤 이름 리터럴을 쓰지 않는다 — 판정은 유형뿐이라 권한 이름이 바뀌어도 코드가 그대로다.
     * {@code use_yn='N'} 인 권한은 제외한다.
     *
     * @return SUPER 유형 권한코드 목록(등록일시 오름차순). 없으면 빈 리스트
     */
    List<String> selectSuperAuthrtCds();
}
