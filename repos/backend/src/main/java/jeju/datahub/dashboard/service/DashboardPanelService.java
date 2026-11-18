package jeju.datahub.dashboard.service;

import java.util.List;

/**
 * 대시보드 하단/집중구역 카드 4종 조회 서비스 인터페이스.
 *
 * <p>집중구역 순위·최근 분석·최근 리포트·AI 추천 질문. 뒤 3종은 조회 전용(MyBatis)이고,
 * 집중구역은 {@code jeju.datahub.analysis} 의 실시간 군집 분석에 위임한다.
 * 데이터가 없으면 각 메서드는 빈 리스트를 반환한다(에러 아님).
 */
public interface DashboardPanelService {

    /**
     * 집중구역 순위 (종합점수 내림차순 top N).
     *
     * <p>저장된 결과 조회가 아니라 <b>실시간 군집 분석</b>이며, 기간은 대시보드 조회단위와 무관하게
     * <b>최근 30일 고정</b>이다 — 근거는 구현체
     * {@code DashboardPanelServiceImpl#getHotspots(int, String)} 의 주석 참고.
     *
     * <p>🔴 기준일은 오늘이 아니라 <b>최신 민원 발생일</b>이다.
     *
     * @param sggCd 시군구코드(5자리). 미전송·빈 문자열·미등록 코드면 전 지역으로 분석한다.
     */
    List<HotspotVO> getHotspots(int limit, String sggCd);

    /**
     * 최근 분석 실행 (excn_dt 내림차순 top N, 코드→명 변환).
     *
     * @param anlsTypeCd 실행유형 코드. null·빈 문자열이면 분석 화면용 5종 전체, 값이 있으면 그 1종으로 좁힌다.
     * @param userId     요청자의 {@code TB_USER.user_id}. 유형과 무관하게
     *                   {@code excutr_id = userId} 인 실행만 보인다. 기존 {@code 'system'} 실행 행은
     *                   누구에게도 보이지 않는다. 서비스는 인증 기술을 모른다 — principal 해석과 401 은
     *                   컨트롤러({@code CurrentUserResolver})가 한다. {@code null} 이면 0건이다.
     */
    List<AnalysisVO> getRecentAnalyses(int limit, String anlsTypeCd, String userId);

    /**
     * 최근 리포트 (crt_ymd 내림차순 top N, 상태 코드→명 변환).
     *
     * @param crtType 생성구분 코드(RPTP_CRT_TYPE_CD — AUTO·MANUAL·AI). null·빈 문자열이면 종전대로 전체,
     *                값이 있으면 그 생성구분으로 좁힌다(대시보드 보고서 카드 = {@code MANUAL}).
     *                미등록 코드는 예외가 아니라 빈 목록이다.
     * @param ownerId 요청자의 {@code TB_USER.user_id} (요청 ①). 자동 생성분은
     *                배포 부서·{@code ALL}·보고서 관리 메뉴 보유자에게만, 직접·AI 생성분은
     *                요청자가 만든 것만 보인다. 서비스는 인증 기술을
     *                모른다 — principal 해석과 401 은 컨트롤러({@code CurrentUserResolver})가 한다.
     */
    List<ReportVO> getRecentReports(int limit, String crtType, String ownerId);

    /** AI 추천 질문 (노출 대상, expsr_seq 오름차순 top N). */
    List<SuggestionVO> getAiSuggestions(int limit);

    /**
     * 데이터 최신 갱신 — 수집 배치 4종의 마지막 <b>성공</b> 실행 시각.
     *
     * <p>🔴 {@code getDataStatus()}(민원이 언제 <b>발생</b>했나)와 <b>다른 사실</b>이다 — 이쪽은
     * 원천을 언제 <b>수집</b>했나({@code batch.tb_btch_excn_hstry})이며, 화면 상단
     * {@code 최신 갱신} 표기가 쓴다. 둘을 합치지 않는다.
     *
     * <p>🔴 {@code items} 는 <b>항상 4행</b>이다(민원 → 단속 → 공영주차장 현황 → 이용률) —
     * 한 번도 안 돈 잡도 {@code lastExcnDt: null} 로 채워 넣는다.
     */
    DataFreshnessVO getDataFreshness();
}
