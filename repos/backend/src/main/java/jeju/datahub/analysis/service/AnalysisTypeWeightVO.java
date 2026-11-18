package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석유형 1건 + 그 유형의 변수 설정값 목록. (parking.TB_COM_CD(ANLS_TYPE_CD) + TB_ANLS_VRBL_STNG)
 *
 * <p>매퍼는 분석유형 머리(anlsTypeCd·anlsTypeNm·sortSeq)만 조회하고,
 * {@code weights} 는 <b>서비스가</b> 전체 변수 목록을 분석유형별로 묶어 채운다 (TSK-4020 Step 3·Step 4).
 * 프론트는 이 중첩 응답을 받은 순서 그대로 그린다 — 정렬은 서버가 확정한다.
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로 자동 처리.
 */
@Schema(description = "분석유형 1건 + 변수 설정값 목록")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AnalysisTypeWeightVO {

    /** 분석유형코드 (tb_com_cd.cd_vl — ANLS_TYPE_CD 그룹) */
    @Schema(description = "분석유형코드", example = "HOTSPOT")
    private String anlsTypeCd;

    /** 분석유형명 (tb_com_cd.cd_nm — 화면 세그먼트 라벨) */
    @Schema(description = "분석유형명", example = "주차 집중구역 분석")
    private String anlsTypeNm;

    /** 정렬순서 (tb_com_cd.sort_seq — 화면 세그먼트 표시 순서) */
    @Schema(description = "정렬순서", example = "1")
    private Integer sortSeq;

    /**
     * 이 분석유형의 변수 설정값 목록 (sort_seq 순). 변수가 없으면 <b>빈 배열</b>(null 이 아니다).
     *
     * <p>여기서 빈 리스트로 초기화해 두어 어떤 경로로도 null 이 되지 않게 한다.
     */
    @Schema(description = "변수 설정값 목록 (변수가 없으면 빈 배열)")
    private List<AnalysisWeightVO> weights = new ArrayList<>();

    /**
     * 가중치비율 합계(%). 매퍼 SELECT 에 없는 파생값으로, <b>서비스가</b> {@code weights} 의
     * {@code stngVl} 를 {@link BigDecimal} 로 합산해 채운다 (TSK-4020 Step 4).
     *
     * <p>{@code NUMERIC(12,4)} 라 {@code 100.00} 으로 표기될 수 있다 — 화면·테스트의 100 판정은
     * 문자열이 아니라 값으로 비교한다.
     *
     * <p>⚠️ <b>대메뉴 전체 합이다 — 하위호환용으로만 남긴다</b> (TSK-4217). 합계 100 은 하위 유형 묶음마다
     * 보므로, 묶음이 둘 이상이면 이 값은 의미가 없다. 화면은 {@link #trgtSums} 를 쓴다.
     */
    @Schema(description = "가중치비율 합계(%) — 대메뉴 전체 합. ⚠️ Deprecated(하위호환용): 합계 100 은 하위 유형 묶음마다 보므로 trgtSums 를 쓴다",
            example = "100", deprecated = true)
    private BigDecimal stngVlSum;

    /**
     * 하위 유형 묶음별 가중치 합계 (TSK-4217). 매퍼 SELECT 에 없는 파생값으로 <b>서비스가</b> 채운다.
     *
     * <p>순서는 {@code weights} 의 묶음 순서와 같다(대메뉴 공통 묶음 먼저 → 공통코드 {@code sort_seq} 순).
     * 여기서 빈 리스트로 초기화해 두어 어떤 경로로도 null 이 되지 않게 한다.
     */
    @Schema(description = "하위 유형 묶음별 가중치 합계 (묶음이 없으면 빈 배열)")
    private List<AnalysisTrgtSumVO> trgtSums = new ArrayList<>();
}
