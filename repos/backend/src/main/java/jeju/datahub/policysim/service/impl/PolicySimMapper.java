package jeju.datahub.policysim.service.impl;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 정책 시뮬레이션 기준선 집계 MyBatis 매퍼 (TSK-4037 Step 2). SQL 은
 * resources/mapper/policysim/PolicySimMapper.xml.
 *
 * <p>읍면동별 집계(GROUP BY)라 영속성 규칙상 MyBatis 담당이다(rule_persistence-jpa-mybatis.md).
 * 🔴 {@code MyBatisConfig} 는 {@code @Mapper} 가 붙은 인터페이스만 스캔한다 — 애노테이션 필수.
 */
@Mapper
public interface PolicySimMapper {

    /** 민원 보유 구간 — {@code min(ocrn_dt)}·{@code max(ocrn_dt)} (기준 구간의 끝·민원 나눗수를 정하는 입력) */
    PolicySimCvlcptPeriodRow selectCvlcptPeriod();

    /** 단속 최종 일시 {@code max(crdn_dt)} — 기준 구간의 끝 판정용. 단속 0건이면 null. */
    LocalDateTime selectMaxCrdnDt();

    /**
     * {@code tb_admdst} 읍면동별 원자료 건수 — 산식은 {@link PolicySimBaselineCalculator} 가 적용한다.
     * {@code param.emdCds} 가 null 이면 전 읍면동(74행), 아니면 그 코드만(TSK-4038 Step 3 — 인접 기준선).
     */
    List<PolicySimEmdCountRow> selectEmdCounts(PolicySimBaselineQueryParam param);

    /**
     * 내 저장 결과 건수 — {@code anls_type_cd='POLICY_SIM' AND excutr_id = userId} (TSK-4037 Step 7-4).
     * 🔴 목록과 같은 WHERE 다 — 갈리면 마지막 페이지가 빈 표로 뜬다.
     */
    int selectSavedResultCount(@Param("userId") String userId);

    /** 내 저장 결과 한 페이지 — {@code excn_dt DESC, rslt_id DESC} */
    List<PolicySimSavedRow> selectSavedResultPage(@Param("userId") String userId,
                                                  @Param("size") int size,
                                                  @Param("offset") long offset);

    /**
     * 내 저장 결과 중 {@code rsltIds} 에 든 것 (TSK-4229 Step 3 — 보고서의 저장분 시나리오).
     *
     * <p>{@link #selectSavedResultPage} 와 <b>같은 SELECT 칸 · 같은 소유 조건</b>
     * ({@code anls_type_cd='POLICY_SIM' AND excutr_id = userId})에 {@code rslt_id IN (...)} 만 더한다.
     * 🔴 순서를 보장하지 않는다 — 호출부가 요청 순서로 다시 맞춘다. 남의 것·없는 것은 결과에서 빠진다(호출부가 404).
     * 빈 목록·null 이면 {@code WHERE false} 로 빈 결과다.
     */
    List<PolicySimSavedRow> selectSavedResultsByIds(@Param("userId") String userId,
                                                    @Param("rsltIds") List<Long> rsltIds);

    /**
     * 같은 생성자 · 같은 날 정책 시뮬레이션 보고서 이름의 최대 순번 (TSK-4229 Step 3) — 없으면 0.
     *
     * <p>이름 {@code {ymd}({n})_정책 시뮬레이션 시나리오 비교} 에서 {@code n} 을 정규식으로 뽑는다.
     * 🔴 개수({@code count(*)})가 아니라 최댓값이다 — 중간 보고서가 지워지면 개수로는 같은 이름이 둘 생긴다.
     * 🔴 {@code LIKE} 를 쓰지 않는다 — {@code _} 가 와일드카드다.
     *
     * @param ymd {@code YYYYMMDD}
     */
    int selectMaxRptpSeqOfDay(@Param("userId") String userId, @Param("ymd") String ymd);

    /**
     * 정책 시뮬레이션 보고서 스냅샷 원문 1행 (TSK-4229 Step 4) — {@code tb_rptp ⟕ tb_anls_excn ⟕ tb_anls_rslt}.
     *
     * <p>보고서가 없으면 null. 실행·결과가 없으면 해당 칸이 null 이다(404 판정은 서비스).
     * 🔴 소유 조건을 걸지 않는다 — 소유 판정은 컨트롤러의 {@code ReportOwnershipGuard} 다(D8).
     */
    PolicySimReportSnapshotRow selectReportSnapshot(@Param("rptpId") Long rptpId);

    /**
     * 대상 지역 바깥의 인접 읍면동 코드 (TSK-4038 Step 2 — 풍선효과 대상 {@code N = T 바깥의 이웃}).
     *
     * <p>🔴 판정식은 {@code ST_Intersects} 다(2026-10-11 사용자 결정) — 로컬 경계 폴리곤이 미세하게 중첩돼
     * {@code ST_Touches} 는 이웃 46쌍을 놓친다(계획서 §0-3). 대상 지역({@code targetEmdCds})은 결과에서 뺀다.
     * 빈 목록·null 이면 IN () 대신 WHERE false 로 빈 결과다(XML 의 {@code <choose>}).
     */
    List<String> selectAdjacentEmd(@Param("targetEmdCds") List<String> targetEmdCds);

    /**
     * 요금제 적용 대상 주차면수 — 읍면동별 (TSK-4039 Step 2).
     *
     * <p>{@code crgYn} 은 RAISE → {@code "Y"}(유료) / CONVERT → {@code "N"}(무료, {@code prk_crg_yn} NULL 포함).
     * 🔴 {@code emdCd} 를 지정하면 {@code zone}·{@code sggNm} 을 무시하고 그 읍면동만 집계한다(명세 6.3 — 읍면동의 급지).
     * 아니면 {@code zone}(Z1 동 / Z2 읍·면 / 그 밖 전체)과 {@code sggNm}(null 이면 제주 전체)으로 좁힌다.
     * 급지는 {@code emd_nm} 의 {@code 읍}·{@code 면} 접미사로 판정한다 — 별도 급지 컬럼이 없다.
     */
    List<FeeTargetRow> selectFeeTargetSlot(@Param("crgYn") String crgYn, @Param("zone") String zone,
                                           @Param("sggNm") String sggNm, @Param("emdCd") String emdCd);
}
