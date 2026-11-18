package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석변수 1건 조회 전용 VO. (parking.TB_ANLS_VRBL_STNG — MyBatis 전용)
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로 자동 처리.
 *
 * <p>설정값은 모두 {@link BigDecimal} 이다 — 컬럼이 {@code NUMERIC(12,4)} 이고, 합계 100 판정을
 * 부동소수로 하면 {@code 0.1 + 0.2} 류 오차 때문에 간헐 실패한다.
 *
 * <p>🔴 <b>{@code @JsonAlias} 5개는 보고서 재현 스냅샷을 되읽기 위한 폴백이다</b>.
 * {@code HotspotReportServiceImpl.buildParamJson} 이 이 VO 를 {@code TB_ANLS_EXCN.anls_param_json}
 * 에 굳혀 두고 {@code parseWeights} 가 {@code convertValue(…, List<AnalysisWeightVO>)} 로 되읽는데,
 * 개명(마이그레이션 67) 이전에 굳은 JSON 은 옛 키를 담고 있다. 주입된 {@code ObjectMapper} 는
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} 가 꺼져 있어 <b>예외도 로그도 없이</b> 과거 보고서의 가중치
 * 부제({@code 민원 60% / 단속 40%})가 비어 버린다.
 * 🆕 신규 4필드({@code vrblSeCd}·{@code minVl}·{@code maxVl}·{@code unitNm})는 옛 스냅샷에 애초에
 * 없으므로 null 이 정상이다 — 보고서 재현 경로가 쓰지 않는 값이다.
 */
@Schema(description = "분석변수 1건")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AnalysisWeightVO {

    /** 분석유형코드 (anls_type_cd — ANLS_TYPE_CD 공통코드) */
    @Schema(description = "분석유형코드", example = "HOTSPOT")
    private String anlsTypeCd;

    /**
     * 분석내용코드 (anls_trgt_cd — 대메뉴 안의 하위 유형. NULL 이면 대메뉴 공통).
     *
     * <p>값의 코드그룹은 대메뉴마다 다르다({@code LIFEZONE → ANLS_TRGT_CD}). 서버(마이그레이션)만 정하며
     * 요청으로 바꿀 수 없다. 🆕 옛 보고서 스냅샷에는 없으므로 null 이 정상이다.
     */
    @Schema(description = "분석내용코드 — 대메뉴 안의 하위 유형 (null 이면 대메뉴 공통)", example = "CNGST", nullable = true)
    private String anlsTrgtCd;

    /**
     * 분석내용명 (tb_com_cd.cd_nm — 매퍼 SELECT 에 없는 파생값. 서비스가 대메뉴별 코드그룹으로 채운다).
     *
     * <p>{@code anlsTrgtCd} 가 null(대메뉴 공통)이거나 매핑 상수에 없는 대메뉴·미등재 코드면 null 이다.
     */
    @Schema(description = "분석내용명 — 하위 유형 이름 (null 이면 대메뉴 공통 또는 이름 미등재)", example = "혼잡도", nullable = true)
    private String anlsTrgtNm;

    /** 변수코드 (vrbl_cd — 분석유형 안에서 변수를 식별) */
    @Schema(description = "변수코드", example = "COMPLAINT")
    @JsonAlias("idctCd")
    private String vrblCd;

    /** 변수명 (vrbl_nm — 화면 표시용) */
    @Schema(description = "변수명", example = "민원빈도")
    @JsonAlias("idctNm")
    private String vrblNm;

    /** 변수설명 (vrbl_expln — 화면 보조설명, 원천 데이터 안내. 없을 수 있다) */
    @Schema(description = "변수설명 (원천 데이터 안내)", example = "수집플랫폼 표준 민원 데이터")
    @JsonAlias("idctExpln")
    private String vrblExpln;

    /**
     * 변수구분코드 (vrbl_se_cd — {@code WGVL}(가중치) | {@code CFC}(계수)).
     *
     * <p>🔴 합계 100 검증은 {@code WGVL} 행에만 적용한다. 계수는 합계 개념이 없다.
     */
    @Schema(description = "변수구분코드 (WGVL 가중치 | CFC 계수)", example = "WGVL")
    private String vrblSeCd;

    /** 설정값 (stng_vl — 가중치는 %, 계수는 자체 단위. WGVL 은 분석유형별 합계 100 이 저장 조건) */
    @Schema(description = "설정값 (가중치는 %, 계수는 자체 단위)", example = "60")
    @JsonAlias("wgvlRt")
    private BigDecimal stngVl;

    /**
     * 기본설정값 (bsc_stng_vl — 최초 등재값, 불변).
     *
     * <p>화면 `기본값으로 초기화` 의 원천이며 저장 API 가 갱신하지 않는다.
     */
    @Schema(description = "기본설정값 — 최초 등재값(불변)", example = "60")
    @JsonAlias("bscWgvlRt")
    private BigDecimal bscStngVl;

    /** 최소값 (min_vl — 허용 하한. DB CHECK 이 강제하며 저장 API 가 바꾸지 못한다) */
    @Schema(description = "최소값 (허용 하한 — 읽기 전용)", example = "0")
    private BigDecimal minVl;

    /** 최대값 (max_vl — 허용 상한. DB CHECK 이 강제하며 저장 API 가 바꾸지 못한다) */
    @Schema(description = "최대값 (허용 상한 — 읽기 전용)", example = "100")
    private BigDecimal maxVl;

    /** 단위명 (unit_nm — 화면 표시용. `%` · `면/건`. 없을 수 있다) */
    @Schema(description = "단위명 (화면 표시용)", example = "%")
    private String unitNm;

    /**
     * 사용여부 (use_yn — 'Y' / 'N').
     *
     * <p>'N' 이면 점수 산출에서 제외하고 나머지 변수로 재정규화한다
     * ({@link AnalysisWeightService#selectEffectiveWeights(String)}).
     */
    @Schema(description = "사용여부 (Y | N)", example = "Y")
    private String useYn;

    /**
     * 노출여부 (expsr_yn — 'Y' / 'N').
     *
     * <p>'N' 이면 설정·분석 계수 조정·보고서 화면에 보이지 않는다. 🔴 <b>계산 스위치가 아니다</b>(그건
     * {@code useYn}) — 계산은 {@code stngVl} 을 그대로 쓴다. 숨김 행은 저장 API 가 요청값을 버리고
     * 현재값을 유지하며, 일시 조정 {@code vrblVls} 로는 400 이다.
     *
     * <p>🔴 판정은 전부 {@code "N".equals(expsrYn)} 로 한다 — null(옛 스냅샷·단위 테스트 픽스처)은 노출이다.
     */
    @Schema(description = "노출여부 (Y | N — N 이면 화면에 보이지 않는 고정 계수. 계산은 stngVl 그대로)", example = "Y")
    private String expsrYn;

    /** 정렬순서 (sort_seq — 화면 표시 순서. 정렬은 서버가 확정한다) */
    @Schema(description = "정렬순서", example = "1")
    private Integer sortSeq;

    /** 수정일시 (mdfcn_dt — 설정값 최종 저장 일시. 한 번도 저장하지 않았으면 null) */
    @Schema(description = "수정일시 (저장 이력이 없으면 null)", example = "2026-08-26T10:15:30")
    private LocalDateTime mdfcnDt;
}
