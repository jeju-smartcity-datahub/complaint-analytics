package jeju.datahub.lifezone.service.impl;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 수급 격자 집계 조회 파라미터.
 *
 * <p><b>기간 기준은 {@code tb_cvlcpt.ocrn_dt}(발생일시)</b>이며 민원건수 분석과 같다
 * (🔁 2026-09-13 — 처음에는 설계 결정을 따라 {@code dclr_ymd} 를 썼다).
 *
 * <p>🔴 <b>바꾼 이유는 {@code dclr_ymd} 가 없는 행이 들어왔기 때문이다.</b> 좌표 유효 49,612건
 * 중 <b>8,738건</b>({@code ocrn_dt} 2025-11-04~2025-12-31)이 {@code dclr_ymd} 가 {@code NULL}
 * 이라, 그 컬럼으로 거르면 <b>통째로 분석에서 빠진다</b>. 더해서 두 탭의 기간 기준이 갈려 있으면
 * 같은 기간을 골라도 한쪽만 결과가 나온다(오늘 기준 세그먼트 + 1년 어긋난 구간 = 0건).
 *
 * <p>⚠️ <b>대가</b> — {@code ocrn_dt} 는 원천이 실제 발생일보다 정확히 +365일 이동된 값을 준다
 * (실측: 40,874건 중 40,871건). 그래서 화면에 보이는 날짜가 1년 뒤다. 다만 이는 민원건수 탭이
 * 이미 지고 있던 성질이라, 바꿈으로써 <b>화면 전체가 같은 기준</b>이 됐다.
 *
 * <p><b>반열림 구간</b>이다 — {@code >= bgngDt AND < endDtExcl}. {@code endYmd} 당일 포함은
 * 서비스가 {@code endYmd + 1일 00:00} 으로 만들어 넘긴다(민원건수 분석과 같은 규약).
 */
@Getter
@Setter
@ToString
public class LifezoneSupplyQueryParam {

    /** 격자크기(m) */
    private int gridSz;

    /** 분석 시작일시 (포함) */
    private LocalDateTime bgngDt;

    /** 분석 종료일시 (<b>제외</b>) — {@code endYmd + 1일 00:00} */
    private LocalDateTime endDtExcl;

    /** 읍면동코드 (null 이면 시군구/전체) */
    private String emdCd;

    /** 시군구코드 5자리 (null 이면 전 지역) */
    private String sggCd;

    /**
     * 조회 일수 = {@code endYmd − bgngYmd + 1} (최소 월평균 기준의 환산 재료).
     * ⚠️ 혼잡도 매퍼는 읽지 않는다.
     */
    private int prdDays;

    /**
     * 최소 월평균 민원건수 — 격자 민원 수 × 30 ≥ 이 값 × {@link #prdDays} 인 격자만 모집단이다.
     * ⚠️ 혼잡도 매퍼는 읽지 않는다.
     */
    private int minMnthAvgCvlcpt;
}
