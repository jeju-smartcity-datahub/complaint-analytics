package jeju.datahub.complaint.service.impl;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 대시보드 집계 매퍼 전용 파라미터. 서비스가 조회 기간(현재/직전 구간)을 자바에서 계산해 채운 뒤
 * 매퍼로 넘긴다. 모든 값은 XML 에서 {@code #{}} 로만 바인딩된다({@code ${}} 미사용 → 인젝션 방어).
 *
 * <p>구간 경계는 {@link LocalDate}(DATE) 로 넘겨 SQL 에서는 {@code ocrn_dt::date BETWEEN} 만 쓴다.
 * unit(enum)·기간 계산 로직은 SQL 에 닿지 않는다.
 */
@Getter
@Setter
@ToString
public class DashboardQueryParam {

    /** 현재 구간 시작일(포함) */
    private LocalDate curStart;

    /** 현재 구간 종료일(포함) */
    private LocalDate curEnd;

    /** 직전 구간 시작일(포함) */
    private LocalDate prevStart;

    /** 직전 구간 종료일(포함) */
    private LocalDate prevEnd;

    /** 시군구 코드(5자리, emd_cd 접두 대조). null 이면 미필터. */
    private String sggCd;

    /** 민원유형코드. null 이면 미필터. */
    private String typeCd;

    /** 위험단계코드. null 이면 미필터. */
    private String riskLvlCd;
}
