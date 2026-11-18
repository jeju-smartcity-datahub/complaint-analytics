package jeju.datahub.analysis.service.impl;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.analysis.service.CvlcptTypeCntVO;
import jeju.datahub.analysis.service.HotspotHeatmapPointVO;
import jeju.datahub.analysis.service.EnforcementTopMthdRow;
import jeju.datahub.analysis.service.HotspotRankingZoneVO;

/**
 * 분석레이어 순위 매퍼 (MyBatis). XML 네임스페이스:
 * {@code jeju.datahub.analysis.service.impl.HotspotRankingMapper}.
 *
 * <p>가이드 §4 결정: view 별로 다른 SELECT.
 * 컬럼명 ({@code zone_nm/cvlcpt_cnt/crdn_cnt/mrk_lat/mrk_lot/emd_cd}) 은 매퍼 XML 안에 박혀 있어
 * 실제 테이블 스키마와 다르면 XML만 갱신하면 된다. 인터페이스는 컬럼명을 모르므로 영향 없음.
 *
 * <p>기존 군집 분석 ({@link HotspotAnalysisMapper}) 과 독립. 두 매퍼는 같은
 * {@code analysis} 패키지에 있어 화면 분기(§4 vs §6 분석레이어) 와 매핑 분기를 명확히 한다.
 *
 * <p><b>2026-09-04 갱신 — view=shortage 복원, Jenks DB 즉시 계산</b>:
 * <ul>
 *   <li>{@code selectMostComplaint} — 데이터 소스 {@code parking.tb_cvlcpt} 직접 집계. 2026-09-04 갱신 유지.</li>
 *   <li>{@code selectEnforcement} — 2026-09-03 갱신 그대로 유지.</li>
 *   <li>{@code selectShortage} (2026-09-04 신규 복원) — {@code parking.fn_jenks_shortage(...)} 호출.
 *       사용자 결정: "폐기" 가 아니라 "옛 정의 적용 안 함" + Jenks 통계적 기법 DB 즉시 계산.
 *       산출 근거: PSDR = tb_pblpklt.pklt_cpcty 합산 / tb_cvlcpt 주차 관련 5종 민원 수.
 *       Jenks k=3 → "심각" 등급 zone 만 ranking 응답으로 노출. §3 KPI #4 shortageCount 와 동일 베이스.</li>
 * </ul>
 */
@Mapper
public interface HotspotRankingMapper {

    /**
     * {@link jeju.datahub.analysis.service.HotspotView#MOST_COMPLAINT} — 민원다발.
     * 데이터 소스 = {@code parking.tb_cvlcpt} 직접 집계 (가이드 §4).
     * 정의: 현재 구간 안에서 {@code emd_cd IS NOT NULL} 이고 {@code cvlcpt_type_cd <> '99'} 인 distinct 읍면동.
     * 정렬은 {@code COUNT(*)} DESC, {@code emd_cd} ASC tie-breaker
     * (🔁 2026-10-02 TSK-4197 — DISTINCT 기준 폐기).
     */
    List<HotspotRankingZoneVO> selectMostComplaint(@Param("startDt") LocalDate startDt,
                                                   @Param("endDt") LocalDate endDt,
                                                   @Param("sggCd") String sggCd,
                                                   @Param("emdCd") String emdCd,
                                                   @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * most-complaint 팝업용 민원유형별 건수 (TSK-4165 D2). {@code (emdCd, 유형코드, 유형명, 건수)} 행을
     * {@code emdCd, TB_COM_CD.sort_seq} 순으로 돌려준다 — 서비스가 {@code emdCd} 로 묶어 zone 에 매핑한다.
     * WHERE 는 {@link #selectMostComplaint} 와 같다(그래야 유형별 합 == {@code cvlcptCnt}).
     */
    List<CvlcptTypeCntVO> selectTypeBreakdown(@Param("startDt") LocalDate startDt,
                                             @Param("endDt") LocalDate endDt,
                                             @Param("sggCd") String sggCd,
                                             @Param("emdCd") String emdCd,
                                             @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * {@link jeju.datahub.analysis.service.HotspotView#ENFORCEMENT} — 집중단속.
     * 데이터 소스 = {@code parking.tb_crdn_dsctn} 직접 집계.
     *
     * <p>🔁 2026-10-02 TSK-4197 — {@code prevStart}/{@code prevEnd}(직전 동일 길이 구간) 2개를 더 받는다.
     * WHERE 를 {@code prevStart ~ endDt} 로 넓히고 현재/직전 건수를 {@code FILTER} 로 갈라 센다
     * (민원 {@code ComplaintMapper.selectEmdCompare} 와 같은 패턴). 응답에
     * {@code crdnCnt} 와 함께 {@code prevCrdnCnt}(서비스 전용, {@code @JsonIgnore})가 실린다.
     * ⚠️ 인자 순서는 {@link #selectShortage} 와 같다 — {@code LocalDate} 4개가 연달아 있어
     * 순서가 갈리면 컴파일이 통과한 채 구간이 뒤섞인다.
     */
    List<HotspotRankingZoneVO> selectEnforcement(@Param("startDt") LocalDate startDt,
                                                 @Param("endDt") LocalDate endDt,
                                                 @Param("prevStart") LocalDate prevStart,
                                                 @Param("prevEnd") LocalDate prevEnd,
                                                 @Param("sggCd") String sggCd,
                                                 @Param("emdCd") String emdCd,
                                                 @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * view=enforcement — 읍면동별 최다 단속유형 1건 (TSK-4322).
     * 🔴 현재 구간만 읽는다({@link #selectEnforcement} 처럼 직전 구간까지 넓히면 비율이 100% 를 넘는다).
     * 동률은 {@code crdn_mthd} 오름차순으로 한 개를 고른다.
     */
    List<EnforcementTopMthdRow> selectEnforcementTopMthd(@Param("startDt") LocalDate startDt,
                                                         @Param("endDt") LocalDate endDt,
                                                         @Param("sggCd") String sggCd,
                                                         @Param("emdCd") String emdCd,
                                                         @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * {@link jeju.datahub.analysis.service.HotspotView#SHORTAGE} — 수요 부족 (2026-09-04 신규).
     * 데이터 소스 = {@code parking.fn_jenks_shortage(...)} DB 즉시 계산 결과의 "심각" 등급 zone.
     * Jenks k=3 등급 분류 + PSDR 기반 — 산출 알고리즘 상세는
     * {@code database/migrations/sql/59-jenks-shortage-fn.sql}.
     * 응답 필드: rank(RANK() — 동률 동순위) / zoneNm / emdCd / cvlcptCnt(=0, 집계 컬럼 아님) / crdnCnt(=0, 집계 컬럼 아님)
     *            / mrkLat / mrkLot (TB_ADMDST 중심점).
     * cvlcptTypeCd 는 §3 KPI 정책대로 PSDR 분모에 영향 없음 (5종 한정).
     * 🔁 2026-10-02 TSK-4197 — {@code prevStart}/{@code prevEnd}(직전 동일 길이 구간) 2개를 더 받는다.
     *   같은 함수를 직전 구간으로 한 번 더 호출해 LEFT JOIN 하고 {@code shortageDeltaPp} ·
     *   {@code deltaPctInferred} 를 SQL 이 직접 낸다. ⚠️ 인자 순서는 {@link #selectEnforcement} 와 같다.
     * 2026-09-04 갱신 — shortageCnt 응답 필드 제거 (PR #24 Copilot review).
     *   HotspotRankingZoneVO 에 해당 필드가 없어 매퍼 SELECT 와 응답 스키마 사이에
     *   거짓 약속이 되어 있었다. 단위별 boundary 동봉 결정 후 후속 라운드에서
     *   shortageGrade/shortageIndex 등 필요 필드를 VO 에 추가 검토.
     */
    List<HotspotRankingZoneVO> selectShortage(@Param("startDt") LocalDate startDt,
                                               @Param("endDt") LocalDate endDt,
                                               @Param("prevStart") LocalDate prevStart,
                                               @Param("prevEnd") LocalDate prevEnd,
                                               @Param("sggCd") String sggCd,
                                               @Param("emdCd") String emdCd,
                                               @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * 히트맵 — {@link jeju.datahub.analysis.service.HotspotView#MOST_COMPLAINT} (TSK-4295).
     * 좌표를 0.001° 칸으로 반올림해 GROUP BY 한 {@code (lat, lot, cnt)} 행들.
     * 🔴 WHERE 는 {@link #selectMostComplaint} 와 같은 식이다(+ 좌표 NOT NULL) —
     * 그래야 {@code sum(cnt)} == 순위 응답의 {@code cvlcptCnt} 합계. 한쪽을 고치면 다른 쪽도 함께 고친다.
     */
    List<HotspotHeatmapPointVO> selectComplaintHeatmap(@Param("startDt") LocalDate startDt,
                                                       @Param("endDt") LocalDate endDt,
                                                       @Param("sggCd") String sggCd,
                                                       @Param("emdCd") String emdCd,
                                                       @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * 히트맵 — {@link jeju.datahub.analysis.service.HotspotView#ENFORCEMENT} (TSK-4295).
     * 🔴 {@link #selectEnforcement} 와 달리 <b>현재 구간만</b> 받는다({@code prevStart}/{@code prevEnd} 없음 —
     * 증감률이 없다). WHERE 는 그 쿼리의 현재 구간 조건과 같은 식이다.
     * ⚠️ {@code cvlcptTypeCd} 는 순위 쿼리와 같이 {@code crdn_type_cd} 에 건다(알려진 한계 — 매퍼 XML 주석).
     */
    List<HotspotHeatmapPointVO> selectCrdnHeatmap(@Param("startDt") LocalDate startDt,
                                                  @Param("endDt") LocalDate endDt,
                                                  @Param("sggCd") String sggCd,
                                                  @Param("emdCd") String emdCd,
                                                  @Param("cvlcptTypeCd") String cvlcptTypeCd);
}
