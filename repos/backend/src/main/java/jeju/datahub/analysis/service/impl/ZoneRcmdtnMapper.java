package jeju.datahub.analysis.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import jeju.datahub.analysis.service.ZoneRcmdtnBaselineVO;
import jeju.datahub.analysis.service.ZoneRcmdtnExclLotVO;
import jeju.datahub.analysis.service.ZoneRcmdtnLotVO;

/**
 * 구역추천(요금제 적용) 분석 MyBatis 매퍼. SQL 은 resources/mapper/analysis/ZoneRcmdtnMapper.xml.
 *
 * <p>복합 JOIN·집계라 JPA 가 아니라 MyBatis 다.
 */
@Mapper
public interface ZoneRcmdtnMapper {

    /**
     * 읍면동 전체의 참고 지표 — 민원 월평균(C0)·단속 월평균(Enf)·전환 가능 면수·기존 유료 면수.
     *
     * <p>{@code TB_ADMDST} 를 왼쪽에 두고 {@code LEFT JOIN} 하므로 <b>원천이 0건인 동도 빠지지 않는다</b>
     * (그 동은 지표가 0 이다). 정렬은 {@code 시군구명 → 읍면동명}.
     *
     * <p>🔴 <b>판정(FEE/SHARE)·필요면수·충족률은 채우지 않는다</b> — 서비스가 이 지표로 계산한다.
     *
     * @param param 기간(민원 {@code YYYY-MM-DD} 문자열 · 단속 {@link java.time.LocalDateTime}
     *              반열림)·개월수 분모·후보 주차장 조건(최소 면수·유형 범위)
     * @return 읍면동 목록. 행정동 마스터 기준이라 비지 않는다
     */
    List<ZoneRcmdtnBaselineVO> selectBaseline(ZoneRcmdtnQueryParam param);

    /**
     * 선택한 읍면동 안의 후보 주차장 목록 — 주차장마다 <b>최근접 배정된</b> 민원·단속 건수를 센다.
     *
     * <p>민원·단속 1건은 가장 가까운 후보 주차장 <b>1곳에만</b> 센다({@code CROSS JOIN LATERAL … LIMIT 1}).
     * 🔴 <b>반경 집계가 아니라 중복이 없다</b> — 한 동의 {@code asgnCvlcptCnt} 합은 그 동의 좌표 보유
     * 민원 수를 넘지 않는다.
     *
     * <p>🔴 배정은 <b>읍면동 경계 안으로 가둔다</b> — 후보 주차장·민원·단속 세 CTE 모두에
     * {@code emd_cd = #{emdCd}} 를 건다. 동 경계 밖 민원을 끌어오면 옆 동 민원으로 순위가 만들어진다.
     *
     * <p>🔴 후보 주차장 조건은 {@code selectBaseline} 의 가능 면수 집계와 <b>같은 {@code <sql>} 조각</b>
     * ({@code convertibleLot})을 공유한다 — 한쪽만 바뀌면 "가능 면수 397면인데 목록 합이 다르다" 가 된다.
     *
     * <p>🔴 정렬은 {@code 배정 민원 DESC → 주차면수 DESC → pklt_id} 다. 배정 0건인 주차장이 흔해
     * 1차 키만으로는 호출마다 순서가 바뀐다(2·3차 키가 동순위 안정성을 지운다).
     *
     * <p>🔴 <b>순위 번호({@code rnk})·누적 면수·도달 여부는 채우지 않는다</b> — 서비스의 순위 루프가
     * 채운다. 매퍼는 순서만 정한다.
     *
     * @param param {@link ZoneRcmdtnQueryParam#getEmdCd() 읍면동 코드} ·
     *              {@link ZoneRcmdtnQueryParam#getAltmntRng() 배정 반경(m)} · 기간 · 후보 조건
     * @return 후보 주차장 목록(배정 민원 내림차순). 후보가 없는 동은 <b>빈 목록</b>이다
     */
    List<ZoneRcmdtnLotVO> selectLotRanking(ZoneRcmdtnQueryParam param);

    /**
     * 최근 3개월의 <b>월별</b> 배정 민원 — 3개월 추이 차트용 평면 행
     * {@code (pkltId, ym, cnt)}.
     *
     * <p>🔴 <b>쿼리를 따로 두는 이유</b> — 한 주차장이 월별로 여러 행이라 {@code selectLotRanking}
     * (주차장 1개소 = 1행) 과 카디널리티가 다르다. 같은 쿼리에 합치면 중첩
     * {@code <collection>} {@code resultMap} 이 필요해 {@code map-underscore-to-camel-case} 자동
     * 매핑을 포기해야 하고, 조인으로 행이 불어나 {@code ORDER BY}·개소 수 기대값(13개소)이 깨진다.
     *
     * <p>🔴 그래도 <b>배정 정의는 하나다</b> — 두 쿼리가 {@code <sql id="lotsAndCvAsgn">} 조각을
     * 공유한다. 한쪽만 고치면 "월별 합 ≠ {@code asgnCvlcptCntRcnt}" 으로 갈린다.
     *
     * <p>🔴 <b>배정 0건인 달은 행이 없다</b>(이도이동 {@code 1963-7} 은 2행뿐) — 빈 달을 0 으로
     * 채워 항상 3칸으로 만드는 것은 서비스의 몫이다({@code TrendCountVO → TrendPointVO} 선례).
     *
     * @param param {@link ZoneRcmdtnQueryParam#getTrendBgngYm() 최근 3개월 창}
     *              ({@code YYYY-MM}) · 읍면동 · 배정 반경 · 후보 조건
     * @return 월별 집계 행. 배정이 전혀 없으면 <b>빈 목록</b>이다
     */
    List<ZoneRcmdtnTrendCountVO> selectLotTrend(ZoneRcmdtnQueryParam param);

    /**
     * 범위 안 <b>후보 밖</b> 주차장 — 지도 회색 핀 전용 평면 목록.
     *
     * <p>🔴 범위({@code scopeWhere})·소멸·좌표 조건은 {@link #selectLotRanking} 의 후보 CTE 와 같고
     * {@code convertibleLot} 만 뒤집는다 — 같은 {@code param} 이면 두 목록은 <b>겹치지 않고</b>
     * 합이 범위 내 살아 있는 좌표 보유 주차장 수와 같다.
     *
     * <p>🔴 면수 NULL 주차장도 여기 들어온다({@code CPCTY_LACK}) — {@code NOT (convertibleLot)} 을
     * 그대로 쓰면 NULL 비교로 행이 빠지므로 {@code COALESCE(…, false)} 로 접는다.
     *
     * <p>사유({@code exclRsnCd})는 하나만, 우선순위 {@code PAID > CPCTY_LACK > PRVT > TYPE_EXCL}.
     * 순위·배정 건수는 없다 — 결과 패널 목록에 나오지 않는다.
     *
     * @param param 읍면동 코드 또는 시군구명(배타) · 후보 조건(최소 면수·유형 범위)
     * @return 후보 밖 주차장({@code pklt_id} 순). 없으면 <b>빈 목록</b>이다
     */
    List<ZoneRcmdtnExclLotVO> selectExclLots(ZoneRcmdtnQueryParam param);
}
