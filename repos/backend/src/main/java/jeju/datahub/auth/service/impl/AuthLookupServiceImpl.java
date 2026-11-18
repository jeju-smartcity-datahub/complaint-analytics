package jeju.datahub.auth.service.impl;

import jeju.datahub.auth.service.AuthLookupService;
import jeju.datahub.auth.service.LoginUserVO;
import jeju.datahub.auth.service.UserLookupVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * {@link AuthLookupService} 구현 (TSK-4115 Step 7).
 *
 * <p>인가 판정·로그인 사용자·수신자 ID 조회는 {@code cert_id} 키의 JOIN/{@code EXISTS} 라 MyBatis
 * ({@link AuthMapper})이고, {@code user_id} PK 단건({@link #findUserById} — TSK-4250)은 JPA
 * ({@link UserRepository#findById})다 ({@code rule_persistence-jpa-mybatis.md}: 복합 JOIN 조회는 MyBatis ·
 * PK 단건 조회는 JPA).
 */
@Service
public class AuthLookupServiceImpl implements AuthLookupService {

    private final AuthMapper authMapper;
    private final UserRepository userRepository;

    public AuthLookupServiceImpl(AuthMapper authMapper, UserRepository userRepository) {
        this.authMapper = authMapper;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasMenu(String certId, String menuId) {
        if (certId == null || certId.isBlank() || menuId == null || menuId.isBlank()) {
            return false;
        }
        return authMapper.existsMenuByCertId(certId, menuId);
    }

    @Override
    @Transactional(readOnly = true)
    public String findUserId(String certId) {
        if (certId == null || certId.isBlank()) {
            return null;
        }
        LoginUserVO vo = authMapper.selectLoginUser(certId);
        return vo != null ? vo.getUserId() : null;
    }

    /** {@link #findUserId} 와 같은 가드 · 같은 매퍼 호출이다 — statement 를 늘리지 않는다 */
    @Override
    @Transactional(readOnly = true)
    public LoginUserVO findUserSummary(String certId) {
        if (certId == null || certId.isBlank()) {
            return null;
        }
        return authMapper.selectLoginUser(certId);
    }

    /** 정지 사용자도 그대로 돌려준다 — 활성 판정은 호출부가 {@code useYn} 으로 한다 */
    @Override
    @Transactional(readOnly = true)
    public UserLookupVO findUserById(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userRepository.findById(userId)
                .map(u -> new UserLookupVO(u.getUserId(), u.getUserNm(), u.getUserEmlAddr(),
                        u.getDeptCd(), u.getUseYn()))
                .orElse(null);
    }

    /**
     * 🔴 수신자가 아니면 <b>빈 목록</b>이다 — {@code null} 을 돌려주지 않는다(호출부가 NPE 없이
     * {@code isEmpty()} 로 분기할 수 있어야 한다). MyBatis 는 목록 반환 타입에 행이 없으면 빈 리스트를
     * 주므로 매퍼 결과를 그대로 넘긴다.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Long> findActiveReceiverIds(String certId) {
        if (certId == null || certId.isBlank()) {
            return Collections.emptyList();
        }
        return authMapper.selectActiveReceiverIds(certId);
    }
}
