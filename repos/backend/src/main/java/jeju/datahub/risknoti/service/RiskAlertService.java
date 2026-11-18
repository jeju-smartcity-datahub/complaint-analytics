package jeju.datahub.risknoti.service;

import java.time.LocalDate;
import java.util.List;

/**
 * 위험단계 알림 이력 서비스 인터페이스 — 조회 2종 + 읽음 처리 1종.
 * (알림 조회 화면 {@code /alerts/inquiry} — TSK-4028 / 상단 알림 벨 팝오버 — TSK-4086)
 *
 * <p>조회는 필터 4종 + 집계 + 페이징이라
 * {@code .claude/rules/rule_persistence-jpa-mybatis.md} 기준 <b>MyBatis</b> 담당이고,
 * 유일한 쓰기인 {@link #markRead(Long, List)} 도 복합키 벌크 UPDATE 라 MyBatis 다
 * (예외 사유는 구현체 주석).
 *
 * <p>🔁 <b>TSK-4202 Step 8 — "조회는 수신자와 무관하다"(TSK-4028 D13) 는 폐기됐다.</b>
 * 세 경로(조회 화면 · 벨 팝오버 · 읽음)가 모두 <b>요청자의 수신자 ID 목록</b>으로 걸러졌다(D10).
 * 🔁 <b>TSK-4250 R13 — 조회 화면({@link #selectAlerts})은 다시 수신자 무관(전 알림)</b>이다(사용자 결정).
 * 수신자 기준은 벨({@link #selectDailyAlerts})·읽음({@link #markRead}) 둘에만 남는다.
 *
 * <p>🔴 <b>{@code rcvrIds} 는 인가 값이다 — 호출자(컨트롤러)가
 * {@code AuthLookupService.findActiveReceiverIds(user.getSubject())} 로 해석해 넘긴다.</b>
 * 서비스는 인증 기술을 모른다. 🔴 <b>미인증은 401 이고 비수신자는 200 + 0건이다</b> — 둘을 뭉개지 않는다.
 * 401 판정은 컨트롤러의 {@code CurrentUserResolver} 가 하므로 여기 도달한 빈 목록은 "수신자가 아니다" 뿐이다.
 */
public interface RiskAlertService {

    /**
     * 알림 이력 조회 — 요약 + 목록 + 페이징 메타를 한 응답으로 내린다 (D8).
     *
     * <p>요약은 페이지가 아니라 <b>필터 전체 기준</b>이라 페이지를 넘겨도 값이 변하지 않는다.
     *
     * <p>🔁 <b>TSK-4250 R13 — 수신자 필터가 없다</b>(전 알림). 목록·총건수·요약 모두 요청자의 수신 여부와
     * 무관하다. 요약의 {@code cvlcptSumCnt} 는 원래부터 "선택 기간 제주 전역 민원 합계" 다(TSK-4069 Step 9).
     *
     * @param search 정규화·검증이 끝난 조회 파라미터 ({@code size}·{@code offset} 포함)
     * @param page   응답 메타용 0-base 페이지 번호. SQL 은 {@code search.offset} 만 쓴다
     * @return 요약 + 목록 + 페이징 메타
     */
    RiskAlertPageVO selectAlerts(RiskAlertSearchVO search, int page);

    /**
     * 상단 알림 벨 팝오버 전용 — 판정일 하루치 알림을 통째로 내린다 (TSK-4086).
     *
     * <p>{@link #selectAlerts} 와 달리 <b>필터도 페이징도 없다</b> — 기준일 하루에 생성된 알림을
     * <b>개수 제한 없이</b> 모두 담는다(D3). 헤더 문구용 전체 건수({@code totalCnt})와
     * 벨 배지용 미읽음 건수({@code unreadCnt})를 목록과 한 응답으로 함께 내린다.
     *
     * <p>기준일은 <b>호출자(컨트롤러)가 {@code Asia/Seoul} 로 확정</b>해 넘긴다 (D8) —
     * 서비스도 DB도 "오늘"을 스스로 판단하지 않는다.
     *
     * <p>🔴 <b>{@code rcvrIds} 가 인자로 늘었다</b>(TSK-4202 Step 8) — 벨 2종은 매퍼에서
     * {@link RiskAlertSearchVO} 를 받지 않아 <b>인자 추가 외에 전달 경로가 없다.</b>
     *
     * <p>🔴 <b>비수신자(빈 목록)는 쿼리를 한 건도 보내지 않고 통째로 조기 반환</b>한다 —
     * {@code selectDailyAlertCount} 는 {@code totalCnt} 와 무관하게 <b>항상</b> 호출되므로
     * {@link #selectAlerts} 식의 부분 생략으로는 막히지 않는다.
     *
     * @param baseDe  기준일(판정일). 보통 전일
     * @param rcvrIds 요청자의 활성 수신자 ID 목록. 비면 {@code totalCnt=0}·{@code unreadCnt=0}·빈 목록
     * @return 기준일 + 전체·미읽음 건수 + 목록. 0건이면 {@code items} 는 빈 배열
     */
    RiskAlertDailyVO selectDailyAlerts(LocalDate baseDe, List<Long> rcvrIds);

    /**
     * 알림 1건 읽음 처리 — 팝오버 행 클릭 (TSK-4086 · TSK-4202 Step 8 에서 <b>수신자별</b>로 바뀜).
     *
     * <p>🔴 <b>읽음은 이력이 아니라 {@code TB_NOTI_HSTRY_RCVR} 의 내 행에 남는다</b>(D9) —
     * 사람마다 미읽음 배지가 따로 집계된다. 내 수신자 행이 여러 개면 <b>전부</b> 갱신한다
     * (D11 근거는 TSK-4250 R9 로 사라졌지만 {@code rcvrIds} 가 목록이라 방어선으로 유지).
     * {@code TB_RISK_STP_NOTI_HSTRY.prsl_yn} 은 더 이상 이 경로에서 갱신하지 않는다.
     *
     * <p>🔴 <b>멱등</b>하다 — 이미 읽은 건을 다시 호출해도 성공이고 {@code prsl_dt} 를 덮어쓰지 않는다.
     * "처음 읽은 시각" 이 갱신되면 이력의 뜻이 달라진다.
     *
     * <p>🔴 <b>404 의 근거가 "알림이 존재하는가" 에서 "내 매핑이 있는가" 로 바뀌었다</b> —
     * 남의 알림은 존재 여부조차 알려주지 않는다. 비수신자({@code rcvrIds} 가 빈 목록)도 같은 결론이다.
     *
     * @param notiId  알림아이디
     * @param rcvrIds 요청자의 활성 수신자 ID 목록
     * @throws org.springframework.web.server.ResponseStatusException 내 수신자 매핑이 없으면 404
     */
    void markRead(Long notiId, List<Long> rcvrIds);
}
