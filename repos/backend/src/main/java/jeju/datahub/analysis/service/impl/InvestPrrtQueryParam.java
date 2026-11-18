package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 투자우선순위 매퍼 전용 파라미터(TSK-4036). 서비스가 요청값을 검증·변환해 채운 뒤 매퍼로 넘긴다.
 * 모든 값은 XML 에서 {@code #{}} 로만 바인딩된다({@code ${}} 미사용 → 인젝션 방어).
 *
 * <p>🔴 <b>행정구역 칸이 없다 — 일부러다.</b> 백분위는 항상 "전 지역 대상 전체" 에서 계산하므로
 * 매퍼는 대상 주차장 전부를 돌려준다. 행정구역 필터는 서비스가 <b>표시만</b> 거른다 — 여기에 넣으면
 * 같은 주차장의 점수가 조회 조건마다 달라진다.
 *
 * <p>🔴 필드명이 매퍼 XML 의 {@code #{…}} 과 <b>정확히 일치</b>해야 한다 — 어긋나면 컴파일은
 * 통과하고 런타임에 바인딩만 실패한다.
 */
@Getter
@Setter
@ToString
public class InvestPrrtQueryParam {

    /**
     * 창 시작 일시(<b>포함</b>) — 요청 {@code bgngYmd} 의 00:00. 민원 {@code ocrn_dt} · 단속
     * {@code crdn_dt} 에 <b>같은 창</b>으로 걸린다(구역추천과 달리 창이 하나다).
     *
     * <p>🔴 두 컬럼 모두 {@code timestamp} 라 {@link LocalDateTime} 이다 — 문자열로 넘기면
     * {@code operator does not exist: timestamp >= character varying} 로 터진다.
     */
    private LocalDateTime bgngDt;

    /**
     * 창 종료 일시(<b>미포함</b> — 반열림 상한) — 요청 {@code endYmd} 의 <b>다음 날 00:00</b>.
     *
     * <p>🔴 안전점검의 기준일도 이 값에서 나온다 — {@code chck_ymd < to_char(endDtExcl,'YYYYMMDD')}
     * (= 종료일 <b>당일 포함</b> 이전의 점검).
     */
    private LocalDateTime endDtExcl;

    /**
     * 이용률 집계 첫 달({@code YYYYMM} — 🔴 대시 없음. {@code tb_pklt_utztn_pttn.tot_ym} 이
     * {@code CHAR(6)} 다). 🔁 TSK-4352 D1 — 시작일이 1일이 아니면 시작 달은 제외한다(한 달 안 창이면 끝 달로
     * clamp — {@code InvestPrrtServiceImpl.utztnBgngYm}). 끝 달은 진행 중이어도 포함한다(월별 집계라 쪼갤 수 없다).
     */
    private String bgngYm;

    /** 이용률 집계 마지막 달({@code YYYYMM} — 종료일의 달) — {@code tot_ym BETWEEN bgngYm AND endYm} 의 상한(포함) */
    private String endYm;

    /**
     * 최근접 배정 반경({@code ALTMNT_RNG_VL}, <b>미터</b>).
     *
     * <p>{@link BigDecimal} 인 이유는 분석변수 {@code stng_vl} 이 {@code NUMERIC} 이라 서비스가 그
     * 타입으로 조달해서다(XML 이 {@code double precision} 으로 캐스트한다).
     * 🔴 값을 바꾸면 배정 건수가 바뀐다 — 응답에 적용값을 함께 담아야 한다.
     */
    private BigDecimal rdsM;
}
