package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석변수 설정 엔티티. (parking.TB_ANLS_VRBL_STNG — 쓰기 전용)
 *
 * <p>설정값 저장(분석유형 1건 전체 교체)은 영속성 규칙상 JPA 담당이라 이 엔티티를 쓴다
 * (쓰기=JPA). 행은 마이그레이션 시드로
 * 이미 존재하므로 이 엔티티가 만드는 SQL 은 <b>UPDATE 뿐</b>이다(신규 INSERT 경로 없음).
 *
 * <p><b>조회는 이 엔티티로 하지 않는다.</b> 화면 목록은 분석유형명을 {@code TB_COM_CD} 에서
 * 가져오는 JOIN 이라 MyBatis({@link AnalysisWeightMapper})가 담당하고 결과는 조회 전용
 * {@link jeju.datahub.analysis.service.AnalysisWeightVO} 로 받는다 — 같은 테이블을 엔티티와 VO 로
 * 겸용하지 않는다는 규칙에 따른다.
 *
 * <p>컬럼명은 DDL {@code database/migrations/sql/67-TB_ANLS_VRBL_STNG.sql} 기준으로 명시한다.
 * JPA 는 MyBatis 의 {@code map-underscore-to-camel-case} 설정과 <b>무관</b>하므로
 * 컬럼 매핑 애노테이션의 {@code name} 속성을 생략하면 안 된다 — 필드마다 반드시 하나씩 붙인다.
 *
 * <p>{@code vrbl_nm}·{@code vrbl_expln}·{@code use_yn}·{@code sort_seq}·{@code vrbl_se_cd} 는 이 화면이
 * 바꾸지 않지만 매핑해 둔다 — 매핑해야 UPDATE 문이 기존 값을 그대로 실어 보내고, 나중에 변수 관리
 * 화면이 붙을 때 같은 엔티티를 쓸 수 있다. <b>저장 경로에서는 set 하지 않는다.</b>
 */
@Entity
@Table(name = "tb_anls_vrbl_stng", schema = "parking")
@IdClass(AnlsVrblStngId.class)
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AnlsVrblStng {

    /** 분석유형코드 (anls_type_cd, 복합 PK #1 — ANLS_TYPE_CD 공통코드) */
    @Id
    @Column(name = "anls_type_cd", nullable = false, length = 30)
    private String anlsTypeCd;

    /** 변수코드 (vrbl_cd, 복합 PK #2 — 분석유형 안에서 변수를 식별) */
    @Id
    @Column(name = "vrbl_cd", nullable = false, length = 30)
    private String vrblCd;

    /**
     * 분석내용코드 (anls_trgt_cd, VARCHAR(30) nullable — 대메뉴 안의 하위 유형. NULL = 대메뉴 공통).
     *
     * <p>🔴 {@code updatable = false} 다 — 하위 유형은 <b>서버(마이그레이션)만 정한다</b>.
     * 저장 경로가 덮을 수 있으면 행이 다른 묶음으로 옮겨져 묶음별 합계 100 검사가 우회된다.
     * PK 가 아니다 — {@link AnlsVrblStngId} 는 {@code (anls_type_cd, vrbl_cd)} 그대로다.
     */
    @Column(name = "anls_trgt_cd", length = 30, updatable = false)
    private String anlsTrgtCd;

    /** 변수명 (vrbl_nm, NOT NULL) — 이 화면이 바꾸지 않는다(읽기만) */
    @Column(name = "vrbl_nm", nullable = false, length = 100)
    private String vrblNm;

    /** 변수설명 (vrbl_expln, nullable) — 이 화면이 바꾸지 않는다(읽기만) */
    @Column(name = "vrbl_expln", length = 300)
    private String vrblExpln;

    /**
     * 변수구분코드 (vrbl_se_cd, VARCHAR(10) NOT NULL — {@code WGVL}(가중치) | {@code CFC}(계수)).
     *
     * <p>합계 100 검증은 {@code WGVL} 에만 적용한다. 이 화면이 바꾸지 않는다(읽기만).
     */
    @Column(name = "vrbl_se_cd", nullable = false, length = 10)
    private String vrblSeCd;

    /** 설정값 (stng_vl, NUMERIC(12,4) NOT NULL) — 저장 API 가 갱신하는 유일한 값 컬럼 */
    @Column(name = "stng_vl", nullable = false, precision = 12, scale = 4)
    private BigDecimal stngVl;

    /**
     * 기본설정값 (bsc_stng_vl, NUMERIC(12,4) NOT NULL) — 최초 등재값. <b>읽기만 한다.</b>
     *
     * <p>저장 경로에서 절대 set 하지 않는다. {@code updatable = false} 로 걸어
     * 실수로 setter 를 부르더라도 UPDATE 문에 실리지 않게 <b>매핑 수준에서 강제</b>한다 —
     * 화면 `기본값으로 초기화` 의 원천이라 값이 흔들리면 되돌릴 기준이 사라진다.
     */
    @Column(name = "bsc_stng_vl", nullable = false, precision = 12, scale = 4, updatable = false)
    private BigDecimal bscStngVl;

    /**
     * 최소값 (min_vl, NUMERIC(12,4) NOT NULL — 허용 하한. DB CHECK 이 강제한다).
     *
     * <p>🔴 {@code updatable = false} 다 — 저장 API 가 범위를 바꾸지 못하게 한다.
     * 바꿀 수 있으면 운영자가 상한을 올려 계수에 임의값을 넣을 수 있다.
     */
    @Column(name = "min_vl", nullable = false, precision = 12, scale = 4, updatable = false)
    private BigDecimal minVl;

    /** 최대값 (max_vl, NUMERIC(12,4) NOT NULL — 허용 상한. {@code minVl} 과 같은 이유로 읽기 전용) */
    @Column(name = "max_vl", nullable = false, precision = 12, scale = 4, updatable = false)
    private BigDecimal maxVl;

    /** 단위명 (unit_nm, nullable — 화면 표시용 `%` · `면/건`. 저장 API 가 바꾸지 않는다) */
    @Column(name = "unit_nm", length = 20, updatable = false)
    private String unitNm;

    /** 정렬순서 (sort_seq, NOT NULL) — 이 화면이 바꾸지 않는다(읽기만) */
    @Column(name = "sort_seq", nullable = false)
    private Short sortSeq;

    /** 사용여부 (use_yn, CHAR(1) NOT NULL) — 이 화면이 바꾸지 않는다(읽기만) */
    @Column(name = "use_yn", nullable = false, length = 1)
    private String useYn;

    /**
     * 노출여부 (expsr_yn, CHAR(1) NOT NULL). <b>읽기만 한다.</b>
     *
     * <p>🔴 {@code updatable = false} 다 — 숨김 지정은 마이그레이션만 한다. 화면 저장으로 바꿀 수 있으면
     * 숨긴 고정 계수를 다시 꺼내 값을 바꿀 수 있다.
     */
    @Column(name = "expsr_yn", nullable = false, length = 1, updatable = false)
    private String expsrYn;

    /** 수정자아이디 (mdfr_id, nullable — 저장한 로그인 사용자의 user_id. 2026-10-30 이전 행은 'system') */
    @Column(name = "mdfr_id", length = 20)
    private String mdfrId;

    /** 수정일시 (mdfcn_dt, nullable — 설정값 최종 저장 일시) */
    @Column(name = "mdfcn_dt")
    private LocalDateTime mdfcnDt;
}
