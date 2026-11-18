package jeju.datahub.dashboard.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.dashboard.service.AnalysisVO;
import jeju.datahub.dashboard.service.DataFreshnessItemVO;
import jeju.datahub.dashboard.service.ReportVO;
import jeju.datahub.dashboard.service.SuggestionVO;

/**
 * 대시보드 하단 카드 MyBatis 매퍼.
 * SQL 은 resources/mapper/dashboard/DashboardPanelMapper.xml.
 *
 * <p>{@code @Mapper} 필수 — {@code MyBatisConfig} 가 이 애노테이션이 붙은 인터페이스만 매퍼 빈으로 스캔한다.
 *
 * <p>집중구역 카드는 여기 없다 — 저장된 분석 결과 조회를 걷어내고 실시간 군집 분석
 * ({@code HotspotAnalysisService})에 위임했다.
 */
@Mapper
public interface DashboardPanelMapper {

    /**
     * 최근 분석: excn_dt 내림차순 top N. anlsTypeCd 가 null 이 아니면 그 실행유형 1종으로 좁힌다.
     *
     * <p>🔴 {@code userId} — {@code excutr_id = userId} 로 거른다. {@code @Param} 을
     * 빠뜨리면 컴파일은 통과하고 호출 시점에 500 이다(아래 selectRecentReports 와 같은 함정).
     * {@code null} 이면 {@code = NULL} 이라 0건이다.
     */
    List<AnalysisVO> selectRecentAnalyses(@Param("limit") int limit,
                                          @Param("anlsTypeCd") String anlsTypeCd,
                                          @Param("userId") String userId);

    /**
     * 최근 리포트: crt_ymd 내림차순 top N. TB_COM_CD 조인으로 상태명.
     * crtType 이 null 이 아니면 그 생성구분(RPTP_CRT_TYPE_CD)으로 좁힌다.
     *
     * <p>🔴 {@code @Param} 필수 — 2인자가 되면서 MyBatis 가 인자 이름을 잃어
     * 런타임 {@code Parameter 'limit' not found} 로 500 이 된다(컴파일은 통과한다).
     *
     * <p>🔴 {@code ownerId} — XML 이 {@code ReportMapper.ownerWhereRowwise} 조각을
     * FQ include 해 OGNL 로 읽는다. 선언을 빠뜨리면 컴파일은 통과하고 <b>호출 시점</b>에
     * {@code no getter for 'ownerId'} 로 500 이다. {@code null} 이면 0건이다(미인증 전체 공개 방지).
     */
    List<ReportVO> selectRecentReports(@Param("limit") int limit,
                                       @Param("crtType") String crtType,
                                       @Param("ownerId") String ownerId);

    /** AI 추천 질문: expsr_yn=true, expsr_seq 오름차순 top N (parking.TB_FAQ). */
    List<SuggestionVO> selectAiSuggestions(@Param("limit") int limit);

    /**
     * 시군구코드(5자리) → 시군구명. 집중구역 카드의 지역 필터가 쓴다 —
     * 분석 서비스가 코드가 아니라 이름을 받으므로 상수표 대신 TB_ADMDST 로 옮긴다.
     *
     * <p>⚠️ 미등록 코드면 {@code null} 이다(= 전 지역). 호출부에서 빈 문자열은 미리 걸러 온다.
     */
    String selectSggNm(@Param("sggCd") String sggCd);

    /**
     * 데이터 최신 갱신일: 수집 배치별 마지막 COMPLETED 실행 시각.
     *
     * <p>🔴 {@code @Param("jobNms")} 필수 — 없으면 MyBatis 가 단일 컬렉션 인자를
     * {@code list}/{@code collection} 이름으로만 노출해 {@code Parameter 'jobNms' not found}
     * 런타임 예외가 난다(컴파일은 통과한다).
     *
     * <p>⚠️ 한 번도 안 돈 잡은 결과에 <b>없다</b> — 4행 고정은 서비스가 채운다.
     *
     * @return {@code jobNm} + {@code lastExcnDt} 만 채운 행들({@code dataNm}·{@code cyclNm} 은 null)
     */
    List<DataFreshnessItemVO> selectDataFreshness(@Param("jobNms") List<String> jobNms);
}
