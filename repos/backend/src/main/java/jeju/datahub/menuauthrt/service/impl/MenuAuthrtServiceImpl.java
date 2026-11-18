package jeju.datahub.menuauthrt.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.menuauthrt.service.AuthrtVO;
import jeju.datahub.menuauthrt.service.MenuAuthrtService;
import jeju.datahub.menuauthrt.service.MenuAuthrtVO;
import lombok.RequiredArgsConstructor;

/**
 * 메뉴 권한 관리 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>조회는 MyBatis({@link MenuAuthrtMapper}), 저장은 JPA({@link AuthrtMenuRepository}) 로 나눈다.
 */
@Service
@RequiredArgsConstructor
public class MenuAuthrtServiceImpl extends EgovAbstractServiceImpl implements MenuAuthrtService {

    /**
     * 등록자/수정자 ID 상수. 인증(로그인/세션)이 아직 없어 고정값을 쓴다.
     *
     * <p>TODO: 로그인 도입 시 이 상수를 걷어내고 인증 주체(SecurityContext 의 사용자 ID)로 교체한다.
     *       교체 지점은 이 상수를 참조하는 {@link #saveMenuAuthorities(String, List)} 한 곳뿐이다.
     */
    private static final String SYSTEM_USER_ID = "system";

    private final MenuAuthrtMapper menuAuthrtMapper;
    private final AuthrtMenuRepository authrtMenuRepository;

    @Override
    public List<AuthrtVO> selectAuthrtList() {
        return menuAuthrtMapper.selectAuthrtList();
    }

    @Override
    public List<MenuAuthrtVO> selectMenuList() {
        return menuAuthrtMapper.selectMenuList();
    }

    @Override
    @Transactional
    public MenuAuthrtVO saveMenuAuthorities(String menuId, List<String> authrtCds) {
        // 1) 메뉴 존재·유형 확인 — 판정은 MyBatis 매퍼로 한다 (Menu/Authrt JPA 엔티티를 따로 만들지 않는다)
        MenuAuthrtVO menu = menuAuthrtMapper.selectMenuById(menuId);
        if (menu == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "메뉴를 찾을 수 없습니다: " + menuId);
        }
        // 2) 폴더 메뉴(menu_path_nm IS NULL)는 권한 지정 대상이 아니다
        if (menu.getMenuPathNm() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "폴더 메뉴는 권한 지정 대상이 아닙니다: " + menuId);
        }

        // 3) 권한 코드 유효성 — 사용중(use_yn='Y') 권한 집합과 대조
        Set<String> validAuthrtCds = menuAuthrtMapper.selectAuthrtList().stream()
                .map(AuthrtVO::getAuthrtCd)
                .collect(Collectors.toSet());
        List<String> invalidAuthrtCds = authrtCds.stream()
                .filter(cd -> !validAuthrtCds.contains(cd))
                .distinct()
                .collect(Collectors.toList());
        if (!invalidAuthrtCds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "사용할 수 없는 권한코드입니다: " + String.join(", ", invalidAuthrtCds));
        }

        // 4) 🔴 SUPER 유형 권한은 어느 메뉴에서도 빠질 수 없다 (재개정).
        //    요청에 없으면 조용히 더해서 저장한다 — 400 을 내지 않는다(화면이 이미 못 빼게 막고,
        //    curl 우회는 무해하게 흡수한다). 그 결과 "빈 배열 저장" 은 더 이상 매핑을 비우지 않는다.
        //    ⚠️ 롤 이름을 쓰지 않는다 — "유형이 SUPER 인 권한" 을 조회한다(권한 이름이 바뀌어도 무관).
        //    ⚠️ 보정은 위 404·400 판정 **뒤**에 온다 — 앞지르면 없는 메뉴/폴더 메뉴 판정이 흐려진다.
        //    ⚠️ LinkedHashSet 으로 중복을 없앤다 — 요청에 이미 SUPER 가 있으면 복합 PK 충돌이 난다.
        Set<String> targetAuthrtCds = new LinkedHashSet<>(authrtCds);
        targetAuthrtCds.addAll(menuAuthrtMapper.selectSuperAuthrtCds());

        // 5) 전체 교체 — 기존 매핑 전부 삭제 후 선택분 재삽입
        //    ★ flush #1: 삭제를 DB 에 먼저 반영한다. 없으면 Hibernate 가 insert 를 먼저 내보내 PK 충돌이 날 수 있다.
        authrtMenuRepository.deleteByMenuId(menuId);
        authrtMenuRepository.flush();

        LocalDateTime now = LocalDateTime.now();
        List<AuthrtMenu> rows = new ArrayList<>(targetAuthrtCds.size());
        for (String authrtCd : targetAuthrtCds) {
            rows.add(new AuthrtMenu(authrtCd, menuId, SYSTEM_USER_ID, now));
        }
        authrtMenuRepository.saveAll(rows);
        // ★ flush #2: 같은 트랜잭션에서 JPA 쓰기 → MyBatis 재조회를 하므로, 재조회 전에 영속성 컨텍스트를
        //    DB 에 반영해야 이전 값이 읽히지 않는다 (영속성 규칙의 "flush 시점").
        authrtMenuRepository.flush();

        // 6) 갱신 결과 재조회 — 화면이 저장 직후 서버값으로 상태를 맞춘다
        return menuAuthrtMapper.selectMenuById(menuId);
    }
}
