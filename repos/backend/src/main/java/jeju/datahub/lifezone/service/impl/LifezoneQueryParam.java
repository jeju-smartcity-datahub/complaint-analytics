package jeju.datahub.lifezone.service.impl;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 격자 집계 매퍼 전용 파라미터. 서비스가 요청값을 검증·변환해 채운 뒤 매퍼로 넘긴다.
 * 모든 값은 XML 에서 {@code #{}} 로만 바인딩된다({@code ${}} 미사용 → 인젝션 방어) —
 * {@code gridSz} 까지 SQL 에 상수로 박지 않는다.
 *
 * <p>기간은 <b>반열림 구간</b>이다 — {@code ocrn_dt >= bgngDt AND ocrn_dt < endDtExcl}.
 * 요청의 {@code endYmd} 당일을 포함하려면 서비스가 {@code endDtExcl = endYmd + 1일}(00:00) 로 만들어 넘긴다.
 * 원천 컬럼({@code ocrn_dt})이 {@code timestamp} 라 경계도 {@link LocalDateTime} 으로 맞춘다
 * ({@code ::date} 캐스팅을 넣으면 인덱스를 못 탄다).
 */
@Getter
@Setter
@ToString
public class LifezoneQueryParam {

    /**
     * 격자 크기(m). {@code TB_GRID.grid_sz} 와 대조한다 — 1000 · 500 · 250 · 100 만 유효하다
     * (테이블 체크 제약 {@code ck_tb_grid_grid_sz}).
     */
    private int gridSz;

    /** 조회 시작 일시(포함) */
    private LocalDateTime bgngDt;

    /** 조회 종료 일시(<b>미포함</b> — 반열림 구간의 상한) */
    private LocalDateTime endDtExcl;

    /** 읍면동 코드(8자리). null 이면 미필터. 지정되면 {@link #sggCd} 보다 우선한다. */
    private String emdCd;

    /**
     * 시군구 코드(읍면동코드 앞 5자리 — 제주시 50110 / 서귀포시 50130). null 이면 미필터.
     *
     * <p>{@code emd_cd LIKE sggCd || '%'} 로 대조한다. 지역 필터는 공간연산이 아니라 코드로 건다.
     */
    private String sggCd;
}
