package jeju.datahub.lifezone.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import jeju.datahub.analysis.service.DataRangeVO;

/**
 * 혼잡도(주차 혼잡 지수) 격자 집계 매퍼.
 *
 * <p>🔴 {@code @Mapper} 가 필수다 — {@code MyBatisConfig} 가 이 애노테이션이 붙은 인터페이스만
 * 매퍼 빈으로 스캔한다.
 *
 * <p>격자 집계는 복합 JOIN + 공간연산 + 집계라 MyBatis 담당이다
 * ("복잡·통계·동적·대용량 조회는 MyBatis").
 *
 * <p>🔴 수급 매퍼({@link LifezoneSupplyMapper})와 <b>기준 CTE 가 반대</b>다 — 수급은 "민원 ≥ 1
 * 격자", 혼잡도는 <b>"공급 &gt; 0 격자"</b>(명세 745행). 같은 쿼리로 묶으면 한쪽이 틀린다.
 *
 * <p>파라미터는 수급과 같은 {@link LifezoneSupplyQueryParam} 을 재사용한다(필드 5개가 같다).
 * ⚠️ 여기서는 그 기간 창이 <b>단속 {@code crdn_dt} 와 민원 {@code ocrn_dt} 양쪽</b>에 걸린다.
 */
@Mapper
public interface LifezoneCngstMapper {

    /** 격자별 공급·단속(수단 3분류)·민원 집계 (공급&gt;0 격자 전량) */
    List<LifezoneCngstRow> selectCngstCells(LifezoneSupplyQueryParam param);

    /**
     * 원천 보유 구간 — 단속 {@code crdn_dt} 기준 · 좌표 유효 범위만.
     *
     * <p>공급(공영주차장)은 시점이 없고 민원은 보조값이라, 지표의 주 입력인 단속 구간을 안내한다.
     */
    DataRangeVO selectDataRange();
}
