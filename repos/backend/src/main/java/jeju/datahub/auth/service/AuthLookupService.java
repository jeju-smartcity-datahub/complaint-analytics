package jeju.datahub.auth.service;

import java.util.List;

/**
 * 서버 내부 인가 판정용 조회.
 *
 * <p>🔴 도메인을 넘는 의존은 이 레포에서 항상 {@code service} 인터페이스 경유다 — 다른 도메인의
 * {@code service.impl}(매퍼·리포지터리)을 직접 참조하지 않는다. {@code MenuAccessGuard} 는
 * 이 인터페이스만 본다.
 *
 * <p>⚠️ {@code /me} 응답과 무관하다 — {@code menuPathNms} 는 화면 렌더링용 목록이고,
 * 이쪽은 "이 요청자가 이 메뉴를 갖고 있는가" 한 건 판정 전용이다.
 */
public interface AuthLookupService {

    /**
     * 요청자({@code cert_id} = OIDC {@code sub})가 해당 메뉴의 권한 매핑을 갖고 있는지.
     *
     * <p>🔴 <b>매 요청 DB 를 본다</b> — 세션에 굳은 authority 가 아니다. 관리자가 매핑을 저장하면
     * 재로그인 없이 즉시 반영된다(폐기 후 "부여한 대로" 원칙).
     */
    boolean hasMenu(String certId, String menuId);

    /**
     * 요청자({@code cert_id} = OIDC {@code sub})의 {@code TB_USER.user_id}. 없거나 정지
     * ({@code use_yn <> 'Y'})면 {@code null}.
     *
     * <p>🔴 {@code authentication.getName()} 을 키로 쓰지 않는다 — {@code user_id} 는 규칙으로
     * 20자 절단·접미가 붙어 {@code preferred_username} 과 다를 수 있다. 조건은 {@code /me} 와 같다
     * ({@code AuthMapper.selectLoginUser} 재사용).
     */
    String findUserId(String certId);

    /**
     * 요청자({@code cert_id} = OIDC {@code sub})의 로그인 사용자({@code user_id} · {@code user_nm}).
     * 없거나 정지({@code use_yn <> 'Y'})면 {@code null}.
     *
     * <p>🔴 <b>매퍼 statement 를 늘리지 않는다</b> — {@link #findUserId} 와 같은
     * {@code AuthMapper.selectLoginUser} 를 재사용한다. 두 벌이 되면 정지 사용자 판정이 갈린다.
     *
     * <p>⚠️ 반환 타입은 {@code /me} 응답 VO 다 — {@code selectLoginUser} 는 {@code menuPathNms} 를
     * 채우지 않아 <b>null 로 온다.</b> 호출부는 {@code getUserId()}·{@code getUserNm()} 만 읽고,
     * 이 객체를 응답으로 그대로 내보내지 않는다.
     */
    LoginUserVO findUserSummary(String certId);

    /**
     * {@code TB_USER.user_id} 로 사용자 단건을 읽는다 (알림 수신자 등록).
     * 없으면 {@code null}.
     *
     * <p>⚠️ {@link #findUserId}·{@link #findUserSummary} 와 달리 <b>정지 사용자({@code use_yn <> 'Y'})도
     * 돌려준다</b> — 키가 {@code cert_id} 가 아니라 {@code user_id} 이고, "없음" 과 "정지" 를 호출부가
     * 각자의 문구로 판정한다. 활성 판정 칸·값은 후보 목록({@code RiskNotiMapper.selectReceiverCandidates}
     * 의 {@code use_yn = 'Y'})과 같아야 한다.
     *
     * <p>PK 단건이라 JPA({@code UserRepository.findById})다.
     */
    UserLookupVO findUserById(String userId);

    /**
     * 요청자({@code cert_id} = OIDC {@code sub})가 가진 <b>살아 있는</b> 알림 수신자 ID 목록
     * ({@code TB_NOTI_RCVR.rcvr_id}). 수신자가 아니면 <b>빈 목록</b>이다 — {@code null} 이 아니다.
     *
     * <p>🔴 매칭 키는 <b>{@code user_id}</b> 다(이메일 매칭 개정) —
     * {@code TB_NOTI_RCVR.user_id} = {@code TB_USER.user_id}. 수신자는 {@code TB_USER} 에서 골라 등록하므로
     * 이 칸이 채워지고 NOT NULL 이다. IdP 에서 이메일이 바뀌어도(또는 이메일이 없어도) 매칭이 끊기지 않는다.
     * ⚠️ {@code user_id} 는 이메일을 20자로 자른 모양이라 이메일과 같은 값이 아니다.
     *
     * <p>한 사용자 = 활성 수신자 1행이라 보통 0~1건이지만 <b>목록 계약은 유지</b>한다 — 그 위의
     * {@code EXISTS}·알림 단위 집계가 방어선으로 남아 있다. 반환 타입이 {@code Long} 인 것은 {@code rcvr_id} 가 BIGINT 라서다.
     *
     * <p>⚠️ 정지 계정({@code u.use_yn <> 'Y'})·중지/삭제된 수신자
     * ({@code r.use_yn <> 'Y'} · {@code r.del_yn = 'Y'})도 제외된다.
     */
    List<Long> findActiveReceiverIds(String certId);
}
