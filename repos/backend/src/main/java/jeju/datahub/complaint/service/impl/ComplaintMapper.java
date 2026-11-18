package jeju.datahub.complaint.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.complaint.service.ComplaintKpiRow;
import jeju.datahub.complaint.service.EmdCompareRow;
import jeju.datahub.complaint.service.EmdTopTypeRow;
import jeju.datahub.complaint.service.NegEmdCompareRow;

/**
 * 민원현황(GIS) 핵심 API 매퍼 (MyBatis). XML 네임스페이스:
 * {@code jeju.datahub.complaint.service.impl.ComplaintMapper}.
 *
 * <p>eGovFrame 패키지 관례에 따라 {@code @Mapper} 만 붙이고 빈 등록·는
 * {@code MyBatisConfig} (@Mapper 스캔) 가 담당한다.
 */
@Mapper
public interface ComplaintMapper {

    /**
     * §1 헤더 {@code lastSyncDt} 의 원천 — {@code parking.tb_cvlcpt.load_dt} 의 {@code MAX}.
     *
     * <p>약속: "이 헤더 시각은 배치 마지막 동기화 시각을 보여준다" — 화면의
     * 현재 조회 구간과 무관해야 한다. {@code selectKpi} 의 {@code WHERE ocrn_dt BETWEEN …} 에
     * 묶이면 가용 데이터가 구간 밖일 때 {@code null} 이 되어 헤더가 비는 회귀가 생긴다.
     * 그래서 별도 SELECT 로 떼어 WHERE 없이 항상 전 행의 {@code MAX(load_dt)} 를 받는다.
     *
     * <p>{@code ComplaintSyncScheduler}({@code jeju-datahub-batch}) 가 매일 02:00 cron 으로
     * 적재할 때마다 {@code load_dt} 가 갱신되므로, 이 값 = "마지막 동기화 시각".
     *
     * @return 가장 마지막 적재 행의 {@code load_dt} (LocalDateTime). 적재 0건이면 {@code null}.
     */
    java.time.LocalDateTime selectLastSyncDt();

    /**
     * §3 KPI 4필드 (totalCount / sentimentDist 분해 / totalSntmntCount).
     * 한 스캔 + FILTER 로 집계, 감성 분모는 aprs_cd IS NOT NULL 행만.
     *
     * <p>⚠️ {@code lastSyncDt} 는 더 이상 여기서 가져오지 않는다 — 위 {@link #selectLastSyncDt}
     * 가 전 행의 {@code MAX(load_dt)} 를 별도로 돌려준다.
     */
    ComplaintKpiRow selectKpi(@Param("curStart") java.time.LocalDate curStart,
                               @Param("curEnd") java.time.LocalDate curEnd,
                               @Param("prevStart") java.time.LocalDate prevStart,
                               @Param("sggCd") String sggCd,
                               @Param("emdCd") String emdCd,
                               @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * §3 KPI #3 "단속 집중 지역" 개수. 결정 (2026-08-31 갱신 — 원천 기반):
     * {@code TB_HTSPT_ANLS_RSLT} view 부재로 원천 테이블 {@code TB_CRDN_DSCTN} 직접 집계.
     * 정의 = "현재 구간 내 {@code tb_crdn_dsctn.emd_cd} 가 NOT NULL 인 읍면동 중 <b>심각 등급</b> 수"
     * (🔁 2026-09-30 {@code FLOOR(N * 0.1)}. 종전엔 그 모집단 전체를 셌다).
     * §4 의 "집중단속지역" view 와 다른 의미 — 본 SELECT 는 단순 카운트, view 는 rank 정렬.
     */
    long selectEnforcementCount(@Param("curStart") java.time.LocalDate curStart,
                                 @Param("curEnd") java.time.LocalDate curEnd,
                                 @Param("prevStart") java.time.LocalDate prevStart,
                                 @Param("sggCd") String sggCd,
                                 @Param("emdCd") String emdCd,
                                 @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * §3 KPI #4 "수요 부족 지역" 개수. 결정 (2026-08-31 갱신 — 원천 기반):
     * §3 KPI #4 shortageCount = <b>모집단(주차민원이 있는 zone) 중 rank 상위 10% 인 동 수</b>
     * (🔁 2026-10-02 <b>Jenks 등급 분류를 쓰지 않는다</b>).
     * - 모집단 = {@code parking.fn_jenks_shortage(…, NULL::text)} 중 {@code out_cvlcpt_sum > 0} 인 행
     *   (🔴 종전 {@code parking.fn_shortage_count(...)} 1줄 호출은 걷혔다 — 그 래퍼는 Jenks "심각"
     *   수를 세던 것이다. 매퍼가 직접 집계한다)
     * - 본 매퍼가 그 행 수에 {@code FLOOR(… × 0.1)} 한 겹을 씌운다 (🔁 2026-09-30 유지 —
     *   프론트 {@code legendConstants.PCT_10} 과 같은 규칙. 한쪽만 고치면 KPI 와 리스트가 어긋난다)
     * - 🔴 함수 인자·WHERE 는 리스트 쪽({@code HotspotRankingMapper.selectShortage})과 글자 그대로
     *   같아야 KPI 와 리스트 개수가 맞는다
     * - 산출 근거: PSDR(공급면수 ÷ 주차 관련 민원 5종) — (2026-08-28 회의)
     * - 직전 구간(prevStart)은 zone 단위 점수이므로 불필요
     * - 옛 정의("민원 0건 zone 수") 폐기 (2026-09-04) — 본 SELECT 결과는 완전히 다른 정의
     * - 실측 2026-10-02(9월 전역): 모집단 67 → KPI 6 (종전 39 → 3)
     */
    long selectShortageCount(@Param("curStart") java.time.LocalDate curStart,
                              @Param("curEnd") java.time.LocalDate curEnd,
                              @Param("sggCd") String sggCd,
                              @Param("emdCd") String emdCd,
                              @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * §7 selectEmdCompare — 읍면동별 현재/직전 동시 집계 (PostgreSQL FILTER).
     * Dashboard selectRegionRanks 패턴 차용. {@code count > 0} 인 읍면동만 (HAVING).
     * 정렬은 SQL 안에서 {@code count DESC, emd_cd ASC} — 결정적 순위 (rank 가 서비스에서 매겨짐).
     *
     * <p><b>primitive 6개 매개변수 + {@code @Param} 명시</b> — 2026-08-31 Q 라운드 실측:
     * POJO 매개변수 + XML {@code #{}} 토큰 매핑이 MyBatis 버전/설정에 따라 불안정.
     * primitive + {@code @Param} 으로 매핑을 명시적으로 못박아 회귀 차단.
     */
    List<EmdCompareRow> selectEmdCompare(@Param("curStart") java.time.LocalDate curStart,
                                          @Param("curEnd") java.time.LocalDate curEnd,
                                          @Param("prevStart") java.time.LocalDate prevStart,
                                          @Param("sggCd") String sggCd,
                                          @Param("emdCd") String emdCd,
                                          @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * §7 selectTopTypeByEmd — 읍면동별 최다 유형 1건 (PostgreSQL DISTINCT ON).
     * (emd_cd, type_count DESC) 정렬 후 첫 행만. {@code TB_COM_CD} LEFT JOIN 으로
     * {@code type_nm} 까지 가져온다 (코드 없어도 행 빠지지 않게).
     */
    List<EmdTopTypeRow> selectTopTypeByEmd(@Param("curStart") java.time.LocalDate curStart,
                                            @Param("curEnd") java.time.LocalDate curEnd,
                                            @Param("prevStart") java.time.LocalDate prevStart,
                                            @Param("sggCd") String sggCd,
                                            @Param("emdCd") String emdCd,
                                            @Param("cvlcptTypeCd") String cvlcptTypeCd);

    /**
     * §8 selectNegEmdCompare — 읍면동별 부정 민원 집계 (PostgreSQL FILTER).
     * 결정 (§7 selectEmdCompare 와 같은 구조, 필터만 부정):
     * * FILTER (aprs_cd='NEGATIVE') 로 현재/직전 부정 카운트 분기.
     * * 별도 FILTER 로 zone 전체 카운트 집계 → {@code NULLIF(전체,0)} 로 ratio 계산.
     * * LEFT JOIN TB_ADMDST 로 emd_nm.
     * * HAVING count > 0 — 부정 0건 zone 제외.
     * * ORDER BY count DESC, emd_cd ASC — 결정적 rank.
     */
    List<NegEmdCompareRow> selectNegEmdCompare(@Param("curStart") java.time.LocalDate curStart,
                                                @Param("curEnd") java.time.LocalDate curEnd,
                                                @Param("prevStart") java.time.LocalDate prevStart,
                                                @Param("sggCd") String sggCd,
                                                @Param("emdCd") String emdCd,
                                                @Param("cvlcptTypeCd") String cvlcptTypeCd);
}