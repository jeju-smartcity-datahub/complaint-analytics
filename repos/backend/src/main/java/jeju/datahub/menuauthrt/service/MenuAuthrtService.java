package jeju.datahub.menuauthrt.service;

import java.util.List;

/**
 * 메뉴 권한 관리 서비스 인터페이스. (설정 화면 "메뉴 권한 관리" 탭 — TSK-4019)
 */
public interface MenuAuthrtService {

    /** 사용중인 권한 그룹 목록 조회 (화면 표시 순서 = 등록일시 오름차순) */
    List<AuthrtVO> selectAuthrtList();

    /** 권한 지정 대상 화면 목록 + 각 화면의 허용 권한코드 배열 조회 (트리 표시 순서) */
    List<MenuAuthrtVO> selectMenuList();

    /**
     * 특정 메뉴의 허용 권한을 <b>전체 교체</b> 저장한다. (TSK-4019 D6 — 기존 매핑 전부 삭제 후 재삽입, 한 트랜잭션)
     *
     * <p>🔴 <b>{@code SUPER} 유형 권한은 자동 보정된다</b> (TSK-4115 D7 재개정). 요청에 없으면 조용히
     * 더해서 저장하므로 <b>요청과 저장 결과가 다를 수 있다</b> — 응답이 저장 결과를 돌려주므로 화면은
     * 즉시 올바른 상태를 본다. 따라서 빈 리스트를 보내도 매핑이 완전히 비지는 않는다.
     *
     * @param menuId    대상 메뉴 ID
     * @param authrtCds 허용할 권한코드 목록. 중복은 내부에서 제거한다. {@code SUPER} 유형 권한은
     *                  빠져 있어도 저장 직전에 더해진다.
     * @return 갱신 후 재조회한 메뉴 1건 (화면이 서버값으로 상태를 맞출 수 있도록)
     * @throws org.springframework.web.server.ResponseStatusException 없는 메뉴(404),
     *         폴더 메뉴이거나 잘못된 권한코드가 섞인 경우(400)
     */
    MenuAuthrtVO saveMenuAuthorities(String menuId, List<String> authrtCds);
}
