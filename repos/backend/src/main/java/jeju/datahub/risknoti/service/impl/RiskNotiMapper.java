package jeju.datahub.risknoti.service.impl;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.risknoti.service.NotiReceiverCandidateVO;
import jeju.datahub.risknoti.service.NotiReceiverRegionVO;
import jeju.datahub.risknoti.service.NotiReceiverVO;
import jeju.datahub.risknoti.service.RiskAlertDailyVO;
import jeju.datahub.risknoti.service.RiskAlertSearchVO;
import jeju.datahub.risknoti.service.RiskAlertSummaryVO;
import jeju.datahub.risknoti.service.RiskAlertVO;
import jeju.datahub.risknoti.service.RiskCriteriaVO;

/**
 * 위험단계 알림 MyBatis 매퍼. SQL 은 resources/mapper/risknoti/RiskNotiMapper.xml.
 *
 * <p>동적조건 검색 + 페이징 + 집계 + 다중 LEFT JOIN + 컬렉션 접기라 영속성 규칙상 MyBatis 담당이다.
 * 쓰기(등록·활성화·소프트 삭제·기준 UPSERT)는 JPA 이므로 INSERT/UPDATE/DELETE 는 여기 없다.
 * 🔴 {@code MyBatisConfig} 가 {@code @Mapper} 애노테이션이 붙은 인터페이스만 스캔하므로 애노테이션은 필수다.
 *
 * <p><b>조회 화면(TSK-4028)용 알림 이력 statement 3개</b>({@code selectAlertList}·{@code selectAlertCount}·
 * {@code selectAlertSummary})는 모두 {@link RiskAlertSearchVO} 하나를 받아 XML 의 {@code alertFrom}·
 * {@code alertWhere} 조각을 <b>그대로 공유</b>한다 — 목록과 요약이 서로 다른 집합을 세지 않게 하는 장치다.
 *
 * <p>⚠️ <b>팝오버 2종({@code selectDailyAlerts}·{@code selectDailyAlertCount}, TSK-4086)은 예외</b>다.
 * {@code @Param} 값만 받고 {@code alertWhere} 를 공유하지 않는다
 * ({@code alertWhere} 가 {@link RiskAlertSearchVO} 의 프로퍼티를 전제하기 때문). {@code alertFrom} 은 함께 쓴다.
 *
 * <p>🔁 <b>TSK-4250 R13 — 수신자 필터는 벨 2종에만 걸린다.</b> TSK-4202 Step 8(D10)은 조회 3종에도
 * {@code alertWhere} 안의 {@code receiverExists} 로 걸었지만, R13 으로 조회 화면은 다시 수신자 무관(전 알림)이
 * 되어 {@code alertWhere} 에서 그 조각을 뺐다. 벨 2종은 직접 적은 {@code WHERE} 뒤에 같은 조각을 그대로 붙인다
 * ({@code rcvrIds} 는 {@code @Param} 이다 — {@link RiskAlertSearchVO} 에는 더 이상 없다).
 *
 * <p>⚠️ 여기 메서드명과 XML 의 {@code <select id="...">} 는 1:1 이어야 한다.
 * 어긋나도 컴파일은 통과하고 <b>호출 시점</b>에 {@code Invalid bound statement (not found)} 로 터진다.
 * 같은 이유로 XML 을 고친 뒤에는 백엔드를 재기동한다({@code compileJava} 는 리소스를 다시 굽지 않는다).
 */
@Mapper
public interface RiskNotiMapper {

    /**
     * 알림 이력 목록 (필터 + {@code ocrn_dt DESC, noti_id DESC} 정렬 + 페이징).
     *
     * <p>tie-breaker({@code noti_id})는 필수다 — 같은 시각에 여러 읍면동이 동시에 뜨는 일이 흔해
     * {@code ocrn_dt} 단독 정렬이면 페이지 경계에서 행이 중복되거나 빠진다.
     */
    List<RiskAlertVO> selectAlertList(RiskAlertSearchVO search);

    /** 알림 이력 총건수 (필터 기준) — 페이징 메타용 */
    int selectAlertCount(RiskAlertSearchVO search);

    /**
     * 요약 카드 (필터 기준). 대상이 0건이어도 집계 함수라 <b>항상 1행</b>을 돌려준다
     * ({@code cvlcpt_sum_cnt} 도 {@code count(*)} 스칼라 서브쿼리라 0 이 나온다).
     *
     * <p>🔴 <b>{@code cvlcpt_sum_cnt} 만 원천이 이력이 아니라 {@code parking.tb_cvlcpt} 다</b>
     * (TSK-4069 Step 9) — 따라서 {@code riskLvlCd} 필터는 그 값에 반영되지 않는다.
     */
    RiskAlertSummaryVO selectAlertSummary(RiskAlertSearchVO search);

    // === 팝오버 — 판정일 하루치 (TSK-4086) ===================================================
    // 🔴 기간은 [baseDt, nextDt) 반열림 구간이다. `ocrn_dt <= baseDt` 로 적으면 시각이 섞인 행이 빠진다.
    // 🔴 TSK-4202 Step 8 — 두 statement 에 rcvrIds 가 더해졌다. 벨 2종은 RiskAlertSearchVO 를 받지
    //    않아 **인자 추가 외에 수신자 값을 전달할 경로가 없다**(🔁 TSK-4250 R13 — 조회 3종은 수신자 무관이 됐다).

    /**
     * 팝오버 — 판정일 하루치 알림 전부 (D3. LIMIT 없음: 하루 최대 = 읍면동 수 74행).
     *
     * <p>🔴 {@code prsl_yn}·{@code prsl_dt} 는 이력이 아니라 <b>내 수신자 행의 집계</b>다 (D9 · D11 근거는 TSK-4250 R9 로 사라졌지만 rcvrIds 가 목록이라 방어선으로 유지) —
     * 내 행이 전부 {@code 'Y'} 일 때만 읽음이다. 남이 읽은 알림이 내게 읽음으로 보이지 않게 한다.
     *
     * @param rcvrIds 요청자의 활성 수신자 ID 목록. 비면 XML 가드가 0건으로 접지만,
     *                서비스가 그 전에 조기 반환하므로 평시엔 닿지 않는다
     */
    List<RiskAlertVO> selectDailyAlerts(@Param("baseDt") LocalDateTime baseDt,
                                        @Param("nextDt") LocalDateTime nextDt,
                                        @Param("rcvrIds") List<Long> rcvrIds);

    /**
     * 팝오버 — 같은 구간의 전체/미읽음 건수. 집계라 0건이어도 1행이 온다.
     *
     * <p>🔴 {@code unread_cnt} 는 <b>알림 단위(1건 = 1)</b> 집계다 —
     * {@code count(*) FILTER (WHERE m.prsl_yn='N')} 로 세면 내 수신자 행이 2개인 알림이 2로 세어진다 (D11 — R9 뒤에도 방어선으로 유지).
     */
    RiskAlertDailyVO selectDailyAlertCount(@Param("baseDt") LocalDateTime baseDt,
                                           @Param("nextDt") LocalDateTime nextDt,
                                           @Param("rcvrIds") List<Long> rcvrIds);

    // === 읽음 처리 (TSK-4202 Step 8 · D9) ====================================================
    // 🔴 SELECT → UPDATE **2문장**이다. 한 UPDATE 의 영향 행 0 으로는 "내 매핑이 아니다(404)" 와
    //    "이미 읽었다(200 멱등)" 를 구분할 수 없다.
    // ⚠️ 쓰기는 JPA 가 원칙이지만(rule_persistence-jpa-mybatis.md) 복합키 (noti_id, rcvr_id)
    //    벌크 UPDATE 라 MyBatis 로 한다 — 엔티티를 N건 로드해 변경 감지로 돌릴 이유가 없다.
    // 🔴 두 statement 는 `IN (...)` 을 직접 적는다(별칭 h 가 없어 receiverExists 조각을 못 쓴다) —
    //    **빈 rcvrIds 는 서비스가 SQL 전에 404 로 끊어야 한다.** 비면 `IN )` 문법 오류 500 이다.

    /** ① 내 매핑 존재 확인 — 0 이면 404 (남의 알림은 존재 여부도 알려주지 않는다) */
    int countAlertReceiverMapping(@Param("notiId") Long notiId, @Param("rcvrIds") List<Long> rcvrIds);

    /**
     * ② 내 수신자 행 <b>전부</b>를 읽음으로 올린다 (rcvrIds 가 2개 이상이면 하나만 올릴 때 배지가 덜 준다 — D11 근거는 TSK-4250 R9 로 사라졌지만 방어선으로 유지).
     *
     * <p>🔴 {@code prsl_yn='N'} 조건이 멱등을 만든다 — 이미 읽은 행의 {@code prsl_dt}("처음 읽은 시각")를
     * 덮지 않는다. <b>영향 행이 0 이어도 성공(204)</b> 이다.
     *
     * @return 갱신된 행 수 (0 이어도 정상)
     */
    int markRead(@Param("notiId") Long notiId, @Param("rcvrIds") List<Long> rcvrIds);

    // === 수신자 · 기준 (Step 6) ==============================================================

    /**
     * 수신자 목록 (페이징 + 단계 배열). 정렬은 {@code rcvr_id} 고정.
     *
     * <p>🔴 XML 은 <b>수신자를 먼저 자르는 CTE</b> 다 — {@code <collection>} 과 {@code LIMIT} 을 같은
     * 쿼리에 두면 {@code LIMIT} 이 수신자가 아니라 <b>1:N 조인 결과 행</b>에 걸려, 10 을 요청해도
     * 8~9명만 오고 경계에 걸린 수신자가 두 페이지에 반쪽씩 나뉜다
     * (부록 {@code TSK-4028-부록-수신자목록-페이징.md} §5·§8).
     *
     * <p>소프트 삭제분({@code del_yn='Y'})은 목록에서 숨긴다 (D5).
     *
     * @param size   LIMIT (CTE 안에서 수신자에 걸린다)
     * @param offset OFFSET (page * size — 오버플로를 피해 long 으로 받는다)
     */
    List<NotiReceiverVO> selectReceiverPage(@Param("size") int size, @Param("offset") long offset);

    /**
     * 수신자 총건수 — 페이징 메타용. 조인 없이 수신자 테이블만 센다.
     *
     * <p>🔴 <b>{@code del_yn='N'} 조건이 목록과 같아야 한다</b> (D5). 빠뜨리면 삭제분까지 세어
     * {@code totalElements} 가 부풀고 <b>마지막 페이지가 빈 표</b>로 뜬다(부록 §7).
     */
    int selectReceiverCount();

    /**
     * 수신자 단건 (등록·활성화 응답용). 삭제분({@code del_yn='Y'})은 없는 것으로 본다 (D5).
     *
     * <p>★ XML 에 {@code flushCache="true"} 가 걸려 있다 — 같은 트랜잭션에서 쓰기 전후로 두 번 부르면
     * MyBatis 1차 캐시({@code localCacheScope=SESSION})가 <b>저장 전 값</b>을 돌려주기 때문이다
     * (TSK-4019 Step 4 실측 버그).
     *
     * @return 없거나 이미 삭제됐으면 null
     */
    NotiReceiverVO selectReceiverById(@Param("rcvrId") Long rcvrId);

    /**
     * 수신자들의 수신 지역 (TSK-4250 Step 5 · R6) — 목록 페이지·단건 결과의 {@code rcvr_id} 로 <b>한 번</b> 읽는다.
     *
     * <p>🔴 receiverMap 의 {@code <collection>} 으로 붙이지 않는 이유: 단계까지 1:N 이라 행이 단계 × 지역으로 곱해진다.
     * 정렬은 수신자 → 시군구 → 읍면동명(시군구 전체·전체 지역 행이 앞)이고, 원소 {@code regionNm} 3분기는 XML CASE 한 곳이 만든다.
     *
     * <p>🔴 {@code rcvrIds} 가 비면 <b>부르지 않는다</b> — {@code IN )} 문법 오류 500.
     */
    List<NotiReceiverRegionVO> selectReceiverRegions(@Param("rcvrIds") List<Long> rcvrIds);

    /**
     * 수신자 후보 — 활성 사용자({@code use_yn='Y'}) 전원 + 활성 수신자 등록 여부 (TSK-4250 R12).
     *
     * <p>정렬: 소속 있는 사용자 먼저 → 부서 {@code sort_seq}(NULL 뒤) → 부서명 → 사용자명 → {@code user_id}.
     * 무소속({@code dept_cd} NULL)은 맨 뒤다(Q2).
     */
    List<NotiReceiverCandidateVO> selectReceiverCandidates();

    /**
     * 단계별 기준 3건.
     *
     * <p>🔴 정렬은 {@code TB_COM_CD.sort_seq}(CRIT → WARN → CAUT)다 —
     * {@code ORDER BY c.risk_lvl_cd} 는 문자 정렬이라 {@code CAUT→CRIT→WARN} 이 돼 시안과 반대가 된다.
     */
    List<RiskCriteriaVO> selectCriteria();

    // --- 수신자·기준 쓰기(Step 6) 입력 검증용 조회 ------------------------------------------
    // 쓰기는 JPA 지만 "값이 실제로 존재하는 코드·지역인가" 는 공통코드/행정구역 테이블을 읽어야 안다.
    // 단순 조회라 엔티티를 새로 만들 이유가 없어(대조만 하고 버린다) 스칼라로 받는다.
    // ⚠️ ReportMapper 에 같은 이름의 statement 가 있지만 도메인 매퍼를 교차 주입하지 않는다 —
    //    eGov 계층 관례상 도메인은 자기 매퍼만 쓴다(보고서도 같은 이유로 자기 매퍼에 뒀다).

    /**
     * 특정 코드 그룹의 사용중({@code use_yn='Y'}) 코드값 목록 ({@code sort_seq} 순).
     * 수신자 등록·기준 저장 모두 {@code RISK_LVL_CD} 에 쓴다.
     * (수신자 부서는 요청으로 받지 않는다 — 서버가 {@code TB_USER} 에서 복사한다, TSK-4250 R8)
     */
    List<String> selectCodeValues(@Param("groupCd") String groupCd);

    /**
     * 읍면동코드로 시군구명을 찾는다 (지역 정규화 — D13).
     *
     * <p>클라이언트가 보낸 {@code sggNm} 을 믿지 않고 이 값으로 덮어쓴다.
     *
     * @return 없는 {@code emdCd} 면 null (→ 서비스가 400. FK 위반을 500 으로 흘리지 않는다)
     */
    String selectSggNmByEmdCd(@Param("emdCd") String emdCd);

    /**
     * 시군구명 존재 확인 (읍면동 없이 시군구 전체를 지정한 경우). 없으면 0.
     *
     * <p>🔴 이 대조가 없으면 오타('제주 시')가 그대로 저장돼 화면 {@code regionNm} 에 노출된다.
     */
    int countSggNm(@Param("sggNm") String sggNm);

    /**
     * 같은 사용자의 활성 수신자({@code del_yn='N'})가 있는지 — 등록 중복 선검사 (TSK-4250 R9 → 409).
     *
     * <p>🔴 조건은 후보 목록({@link #selectReceiverCandidates})의 {@code registered} 와 <b>같아야 한다</b>
     * (같은 칸·같은 값 — {@code user_id} · {@code del_yn = 'N'}). 갈리면 화면은 고를 수 있는데 등록은 409 가 된다.
     * {@code use_yn}(활성화 토글)은 보지 않는다 — 꺼 둔 수신자도 한 사람 1행(부분 유니크 인덱스와 같은 범위).
     */
    boolean existsActiveReceiverByUserId(@Param("userId") String userId);
}
