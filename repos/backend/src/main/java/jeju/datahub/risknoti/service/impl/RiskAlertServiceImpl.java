package jeju.datahub.risknoti.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.risknoti.service.RiskAlertDailyVO;
import jeju.datahub.risknoti.service.RiskAlertPageVO;
import jeju.datahub.risknoti.service.RiskAlertSearchVO;
import jeju.datahub.risknoti.service.RiskAlertService;
import jeju.datahub.risknoti.service.RiskAlertSummaryVO;
import jeju.datahub.risknoti.service.RiskAlertVO;
import lombok.RequiredArgsConstructor;

/**
 * 위험단계 알림 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>조회가 주 책임이지만 팝오버 읽음 처리({@link #markRead(Long, List)}) 하나만 쓰기다 —
 * <b>그 메서드에만 {@code @Transactional} 을 건다</b>(조회 경로는 트랜잭션 경계를 잡지 않는다).
 * 수신자·기준 CRUD 쓰기는 여전히 {@link RiskNotiServiceImpl} 담당이다.
 *
 * <p>🔁 <b>TSK-4202 Step 8 — 세 경로 모두 요청자의 수신자 ID 목록({@code rcvrIds})으로 걸러졌다</b>(D10).
 * 🔁 <b>TSK-4250 R13 — 조회 화면({@link #selectAlerts})은 다시 수신자 무관(전 알림)</b>이고,
 * 수신자 기준은 벨({@link #selectDailyAlerts})·읽음({@link #markRead}) 둘에만 남는다.
 * 🔴 그 목록은 <b>컨트롤러가 해석해 넘기는 인가 값</b>이고, 서비스는 인증 기술을 모른다.
 * 🔴 여기 도달한 빈 목록은 <b>"수신자가 아니다"(200 + 0건)</b> 뿐이다 — 미인증(401)은 컨트롤러의
 * {@code CurrentUserResolver} 가 이미 걸렀다.
 *
 * <p>🔴 <b>SQL 빈 목록 가드는 XML {@code receiverExists} 조각 안에 있다</b>({@code <otherwise>AND 1 = 0}).
 * 그 위에 얹는 조기 반환 두 자리는 <b>필수</b>다:
 * <ul>
 *   <li>🔴 <b>벨({@link #selectDailyAlerts})은 통째 조기 반환이 필수</b>다. {@code selectDailyAlertCount}
 *       가 {@code totalCnt} 와 무관하게 항상 호출돼, 조각 가드로 0건이 되더라도 쿼리는 나간다.</li>
 *   <li>🔴 <b>{@link #markRead} 의 빈 목록은 SQL 전에 404 로 끊어야 한다.</b> 그 두 statement 는
 *       별칭 {@code h} 가 없어 조각을 못 쓰고 {@code IN (...)} 을 직접 적는다 — 비면 문법 오류 500 이다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RiskAlertServiceImpl extends EgovAbstractServiceImpl implements RiskAlertService {

    private final RiskNotiMapper riskNotiMapper;

    // 🔴 RiskNotiHstryRepository 는 더 이상 주입하지 않는다 (TSK-4202 Step 8) —
    //    읽음의 저장 위치가 이력(TB_RISK_STP_NOTI_HSTRY)에서 매핑(TB_NOTI_HSTRY_RCVR)으로 옮겨가(D9),
    //    404 의 근거도 "알림이 존재하는가" 에서 "내 매핑이 있는가" 로 바뀌었다.

    @Override
    public RiskAlertPageVO selectAlerts(RiskAlertSearchVO search, int page) {
        // 🔁 TSK-4250 R13 — 조회 화면은 수신자 무관(전 알림)이라 비수신자 조기 반환이 없다.
        //    요약은 페이지가 아니라 필터 전체 기준이라(D8) 총건수가 0 이어도 그대로 내린다
        //    (집계 함수라 항상 1행이고 카드가 0 으로 채워진다).
        RiskAlertSummaryVO summary = riskNotiMapper.selectAlertSummary(search);

        int totalElements = riskNotiMapper.selectAlertCount(search);

        // 총건수가 0 이면 목록 쿼리를 보낼 이유가 없다 (보고서 selectReports 와 같은 관례)
        List<RiskAlertVO> content = (totalElements == 0)
                ? Collections.<RiskAlertVO>emptyList()
                : riskNotiMapper.selectAlertList(search);

        return new RiskAlertPageVO(content, page, search.getSize(), totalElements, summary);
    }

    @Override
    public RiskAlertDailyVO selectDailyAlerts(LocalDate baseDe, List<Long> rcvrIds) {
        // 🔴 비수신자는 **통째로** 조기 반환한다 — 아래 selectDailyAlertCount 는 항상 호출되므로
        //    selectAlerts 식의 부분 생략으로는 빈 rcvrIds 가 그대로 흘러간다.
        if (!isReceiver(rcvrIds)) {
            RiskAlertDailyVO empty = new RiskAlertDailyVO();
            empty.setBaseDe(baseDe);
            empty.setTotalCnt(0);
            empty.setUnreadCnt(0);
            empty.setItems(Collections.<RiskAlertVO>emptyList());
            return empty;
        }

        LocalDateTime baseDt = baseDe.atStartOfDay();
        LocalDateTime nextDt = baseDe.plusDays(1).atStartOfDay();

        // 집계가 항상 1행이라 null 이 아니다(count 계열). 그래도 방어적으로 받는다.
        RiskAlertDailyVO result = riskNotiMapper.selectDailyAlertCount(baseDt, nextDt, rcvrIds);
        result.setBaseDe(baseDe);

        // 0건이면 목록 쿼리를 보내지 않는다 (selectAlerts 와 같은 관례)
        result.setItems(result.getTotalCnt() == 0
                ? Collections.<RiskAlertVO>emptyList()
                : riskNotiMapper.selectDailyAlerts(baseDt, nextDt, rcvrIds));
        return result;
    }

    /**
     * 🔴 <b>SELECT → UPDATE 2문장</b>이다. 단일 {@code UPDATE ... AND prsl_yn='N'} 의 영향 행 0 은
     * <b>(a) 내 매핑이 아니다(404)</b> 와 <b>(b) 이미 읽었다(204 멱등)</b> 를 구분하지 못한다 —
     * 존재 확인(①)과 멱등 갱신(②)을 나눠야 둘 다 성립한다.
     *
     * <p>⚠️ 쓰기는 JPA 가 원칙이지만({@code rule_persistence-jpa-mybatis.md}) 복합키
     * {@code (noti_id, rcvr_id)} 의 <b>벌크 UPDATE</b> 라 MyBatis 로 한다 — {@code rcvrIds} 가 목록이라
     * (D11 근거는 TSK-4250 R9 로 사라졌지만 방어선으로 유지) 엔티티를 N건 로드해 변경 감지로 돌릴 이유가 없고, 그 복합키 엔티티도 없다.
     */
    @Transactional
    @Override
    public void markRead(Long notiId, List<Long> rcvrIds) {
        // ⓪ 🔴 빈 목록은 SQL 을 보내기 **전에** 끊는다 — 아래 두 statement 는 `IN (...)` 을 직접 적어
        //    비면 `IN )` 문법 오류 500 이다. "수신자가 아니다" 는 "내 매핑이 없다" 와 같은 결론(404)이라
        //    분기를 더해도 계약이 바뀌지 않는다.
        if (!isReceiver(rcvrIds)) {
            throw notFound(notiId);
        }

        // ① 내 매핑이 있는가 — 없으면 404. 남의 알림은 존재 여부도 알려주지 않는다.
        if (riskNotiMapper.countAlertReceiverMapping(notiId, rcvrIds) == 0) {
            throw notFound(notiId);
        }

        // ② 내 수신자 행 전부를 올린다(rcvrIds 목록 전부 — R9 뒤에도 방어선으로 유지). 🔴 영향 행이 0 이어도 성공(204)이다 —
        //    이미 읽었다는 뜻이고, prsl_dt("처음 읽은 시각")를 덮지 않는 것이 멱등의 정의다.
        riskNotiMapper.markRead(notiId, rcvrIds);
    }

    /** 수신자 여부 — {@code null}(해석 전)과 빈 목록(비수신자)을 같게 본다 */
    private static boolean isReceiver(List<Long> rcvrIds) {
        return rcvrIds != null && !rcvrIds.isEmpty();
    }

    /** 🔴 비수신자와 "남의 알림" 을 같은 문구로 돌려준다 — 메시지로 존재 여부가 새면 안 된다 */
    private static ResponseStatusException notFound(Long notiId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다: " + notiId);
    }
}
