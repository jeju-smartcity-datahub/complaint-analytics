package jeju.datahub.risknoti.service;

import java.util.List;

import jeju.datahub.common.PageResponse;

/**
 * 알림 수신자 · 위험단계 기준 서비스 인터페이스. (알림 관리 화면 {@code /alerts/management} — TSK-4028 Step 6)
 *
 * <p>영속성 규칙({@code .claude/rules/rule_persistence-jpa-mybatis.md})대로 역할이 갈린다.
 * <ul>
 *   <li><b>쓰기(C/U/D)는 JPA</b> — 등록·활성화 토글·소프트 삭제·기준 UPSERT</li>
 *   <li><b>조회는 MyBatis</b> — 공통코드·행정구역 LEFT JOIN + 단계 배열 접기 + 페이징,
 *       기준은 {@code TB_COM_CD.sort_seq} 조인 정렬</li>
 * </ul>
 * 한 로직을 두 기술로 중복 구현하지 않는다 — 쓰기 뒤에는 반드시 {@code flush()} 후 MyBatis 로 재조회한다.
 *
 * <p>알림 <b>이력 조회</b>는 이 인터페이스가 아니라 {@link RiskAlertService} 담당이다 (Step 5).
 */
public interface RiskNotiService {

    /**
     * 수신자 목록 (페이징, 단계 배열 포함). 정렬은 {@code rcvr_id} 고정.
     *
     * <p>🔴 <b>소프트 삭제분({@code del_yn='Y'})은 목록에서 숨긴다</b> (D5).
     * 목록 쿼리와 총건수 쿼리가 <b>같은 조건</b>을 걸어야 {@code totalElements} 가 부풀지 않는다.
     *
     * @param page 0-base 페이지 번호
     * @param size 페이지 크기 (검증은 컨트롤러가 한다 — 상한 100)
     */
    PageResponse<NotiReceiverVO> selectReceivers(int page, int size);

    /**
     * 수신자 등록. 단계 매핑({@code TB_NOTI_RCVR_LVL})까지 함께 INSERT 한다.
     * 등록 뒤 지역·단계 변경은 {@link #updateReceiver}(통째 교체 — TSK-4250 R10)가 맡는다.
     *
     * <p>🔁 TSK-4250 — {@code userId} 의 {@code TB_USER} 행에서 이름·이메일·부서를 복사하고(R8), 지역 매핑
     * ({@code TB_NOTI_RCVR_LVL} 옆 {@code TB_NOTI_RCVR_RGN})도 함께 INSERT 한다. 없거나 정지된 사용자·
     * 없는 코드/지역·지역 겹침은 400, 같은 사용자의 활성 수신자가 이미 있으면
     * {@link NotiReceiverDuplicatedException}(409 {@code RCVR_DUPLICATED}).
     *
     * @return 공통코드명·지역 표시명·단계 배열이 붙은 등록 결과 1건
     */
    NotiReceiverVO createReceiver(NotiReceiverRequest req);

    /**
     * 수신자 수정 (TSK-4250 R10) — 수신 지역({@code TB_NOTI_RCVR_RGN})과 수신 단계({@code TB_NOTI_RCVR_LVL})를
     * <b>통째로 교체</b>한다(delete-all → insert).
     *
     * <p>사람 정보({@code rcvr_nm}·{@code eml}·{@code dept_cd}·{@code user_id})는 바꾸지 않는다 —
     * {@code TB_USER} 가 정본이다(R8). 정규화·검증은 {@link #createReceiver} 와 같은 규칙이다
     * (없는 코드/지역·지역 겹침 400).
     *
     * <p>⚠️ 이미 적재된 알림 매핑({@code TB_NOTI_HSTRY_RCVR})은 건드리지 않는다 — <b>다음 판정부터</b> 반영된다
     * (과거분이 필요하면 백필 트리거).
     *
     * @param rcvrId 수신자ID. 없거나 이미 삭제됐으면 404
     * @return 갱신된 1건
     */
    NotiReceiverVO updateReceiver(Long rcvrId, NotiReceiverUpdateRequest req);

    /**
     * 수신자 활성화 토글 ({@code use_yn}).
     *
     * @param rcvrId 수신자ID. 없거나 이미 삭제됐으면 404
     * @param useYn  {@code Y} 또는 {@code N}
     * @return 갱신된 1건
     */
    NotiReceiverVO updateReceiverActivation(Long rcvrId, String useYn);

    /**
     * 수신자 <b>소프트 삭제</b> — {@code del_yn='Y'} 로 표시한다 (D5).
     *
     * <p>행도 단계 매핑도 지우지 않는다. 발송기록({@code TB_NOTI_HSTRY_RCVR})이 {@code rcvr_id} 로
     * 수신자를 참조하므로 하드 삭제는 FK 위반이고, 지우면 과거 발송기록이 함께 날아간다.
     *
     * @param rcvrId 수신자ID. 없거나 이미 삭제됐으면 404
     */
    void deleteReceiver(Long rcvrId);

    /**
     * 수신자 후보 목록 (TSK-4250 R12) — 활성 사용자 전원과 각자의 활성 수신자 등록 여부.
     *
     * <p>수신자는 {@code TB_USER} 에서 고른다(R8). {@code registered=true} 인 사람은 화면이 선택을 막는다(R9).
     * 무소속 사용자는 목록 맨 뒤에 온다(Q2).
     */
    List<NotiReceiverCandidateVO> getReceiverCandidates();

    /**
     * 단계별 기준 목록 (3건).
     *
     * <p>🔴 정렬은 {@code TB_COM_CD.sort_seq}(CRIT → WARN → CAUT)다 —
     * {@code ORDER BY risk_lvl_cd} 는 문자 정렬이라 시안과 반대가 된다.
     */
    List<RiskCriteriaVO> selectCriteria();

    /**
     * 단계별 기준 3건 일괄 UPSERT (D7). 부분 저장은 400 이다.
     *
     * @return 갱신된 3건 ({@link #selectCriteria()} 와 같은 순서·같은 형태)
     */
    List<RiskCriteriaVO> saveCriteria(RiskCriteriaRequest req);
}
