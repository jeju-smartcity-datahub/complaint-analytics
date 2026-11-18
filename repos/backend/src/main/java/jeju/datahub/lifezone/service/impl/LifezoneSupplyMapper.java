package jeju.datahub.lifezone.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import jeju.datahub.analysis.service.DataRangeVO;

/**
 * 수급 격자 집계 매퍼.
 *
 * <p>🔴 {@code @Mapper} 가 필수다 — {@code MyBatisConfig} 가 이 애노테이션이 붙은 인터페이스만
 * 매퍼 빈으로 스캔한다.
 *
 * <p>격자 집계는 복합 JOIN + 공간연산 + 집계라 MyBatis 담당이다
 * ("복잡·통계·동적·대용량 조회는 MyBatis").
 */
@Mapper
public interface LifezoneSupplyMapper {

    /** 격자별 민원·공급 집계 (민원>0 격자 전량) */
    List<LifezoneSupplyRow> selectSupplyCells(LifezoneSupplyQueryParam param);

    /**
     * 원천 보유 구간 (발생일시 {@code ocrn_dt} 기준 · 🔁 2026-09-13).
     *
     * <p>⚠️ {@code LifezoneAnalysisMapper.selectDataRange()} 와 <b>기준 컬럼은 같지만 조건이
     * 다르다</b> — 그쪽은 미분류({@code '99'})를 빼고 좌표 범위를 보지 않으며, 이쪽은 전 유형에
     * 좌표 유효 범위만 본다. 그래서 값이 조금 다를 수 있어 쿼리를 따로 둔다.
     */
    DataRangeVO selectDataRange();
}
