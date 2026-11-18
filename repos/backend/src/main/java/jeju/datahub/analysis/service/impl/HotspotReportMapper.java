package jeju.datahub.analysis.service.impl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 보고서 생성이 쓰는 조회 매퍼.
 *
 * <p>저장(3표 INSERT)은 JPA 담당이고, 여기 있는 것은
 * <b>저장 전에 채워야 하는 값을 읽는</b> 조회 하나뿐이다. {@code TB_ADMDST} 에는 JPA 엔티티가 없고
 * (여러 도메인이 조인으로만 쓴다) 두 칸을 대조 후 버리므로 엔티티를 새로 만들지 않는다 —
 * {@code ReportMapper.selectSggNmByEmdCd} 가 같은 표를 스칼라로 읽는 것과 같은 판단이다.
 *
 * <p>{@link HotspotAnalysisMapper}(군집 집계)에 얹지 않고 매퍼를 나눈 이유는
 * <b>수명이 다르기 때문</b>이다 — 저 매퍼는 분석 SQL 의 정본이고, 이쪽은 보고서 저장 경로에서만
 * 쓰인다. 분석 SQL 을 고칠 때 보고서용 조회가 함께 눈에 들어오면 두 관심사가 섞인다.
 */
@Mapper
public interface HotspotReportMapper {

    /**
     * 읍면동코드 → 시군구명 + 읍면동명 (한 번의 조회로 <b>둘 다</b>).
     *
     * <p>{@code emd_cd} 는 {@code TB_ADMDST} 의 PK 라 최대 1행이다. 없으면 {@code null} 이 온다 —
     * 다만 이 경로는 {@code HotspotAnalysisServiceImpl#resolveRegion} 이 이미 실재를 확인한 뒤이므로
     * 정상 흐름에서는 도달하지 않는다.
     *
     * @param emdCd 읍면동코드 (8자리)
     * @return 시군구명 + 읍면동명. 없으면 {@code null}
     */
    AdmdstNmVO selectAdmdstNm(@Param("emdCd") String emdCd);
}
