package jeju.datahub.auth.service.impl;

import jeju.datahub.auth.service.LoginUserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code /me} 응답 조회 매퍼.
 *
 * <p>복합 JOIN 조회라 MyBatis 다.
 * 🔴 {@code @Mapper} 필수 ({@code MyBatisConfig} 가 이 애노테이션이 붙은 인터페이스만 스캔한다).
 */
@Mapper
public interface AuthMapper {

    /**
     * 🔴 조회 키는 {@code cert_id}(= {@code sub})다 — {@code user_id} 가 아니다.
     * {@code user_id} 는 규칙으로 절단·접미가 붙어 {@code preferred_username} 과 다를 수 있다.
     */
    LoginUserVO selectLoginUser(@Param("certId") String certId);

    /**
     * {@code SELECT DISTINCT} — 2026-09-03 탭 경로 분리로 {@code M0602}·{@code M0603} 이
     * 각자 경로({@code /settings/menu-authority}·{@code /settings/analysis-weight})를 갖게 돼
     * 지금은 메뉴 14개 = 경로 14개다. DISTINCT 는 사실상 no-op 이지만, 한 메뉴가 다시 경로를
     * 공유할 때를 대비해 남겨 둔다.
     */
    List<String> selectMenuPathNms(@Param("authrtCd") String authrtCd);

    /**
     * 🔴 <b>서버 내부 인가 판정 전용</b>. {@code /me} 응답과 무관하다.
     *
     * <p>요청자({@code cert_id} = OIDC {@code sub})가 해당 메뉴의 권한 매핑을 갖고 있는지 한 건
     * 판정한다 — {@code EXISTS} 라 목록을 만들지 않는다. 매 요청 호출되므로 조건은 인덱스가 잡히는
     * PK/FK 컬럼만 쓴다.
     */
    boolean existsMenuByCertId(@Param("certId") String certId, @Param("menuId") String menuId);

    /**
     * 🔴 <b>알림 수신자 판정 전용</b>. 요청자가 가진 살아 있는
     * {@code TB_NOTI_RCVR.rcvr_id} 목록을 준다 — 없으면 빈 목록이다.
     *
     * <p>조인 키는 <b>{@code user_id}</b> 다(이메일 매칭 개정). 수신자가 {@code TB_USER} 에서
     * 골라 등록되므로 {@code TB_NOTI_RCVR.user_id} 가 채워진다(NOT NULL). 입력 키는 그대로 {@code cert_id}.
     * 한 사용자 = 활성 수신자 1행이라 보통 0~1건이지만 반환은 목록 계약을 유지한다.
     */
    List<Long> selectActiveReceiverIds(@Param("certId") String certId);
}
