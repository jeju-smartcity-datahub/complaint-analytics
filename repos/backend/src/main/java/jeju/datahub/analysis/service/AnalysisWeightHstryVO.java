package jeju.datahub.analysis.service;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석변수 설정 변경 이력 1건 조회 전용 VO. (parking.TB_ANLS_VRBL_HSTRY — MyBatis 전용)
 *
 * <p>이력 1행 = 분석유형 1건의 저장 1회다. 표시용 문장 {@code chgCn} 은
 * <b>기록 시점에 조립해 굳힌</b> 값이라, 뒤에 변수명이 바뀌어도 과거 이력의 뜻이 변하지 않는다.
 * 🔴 그래서 표·컬럼이 개명돼도(마이그레이션 67 — 옛 이력 표 → {@code TB_ANLS_VRBL_HSTRY})
 * <b>이미 쌓인 {@code chg_cn} 문장은 고치지 않는다</b>(기록의 위조 금지).
 * 되돌리기·감사용 스냅샷(bfr_vrbl_json · aftr_vrbl_json)은 화면에 쓰지 않아 이 VO 에 담지 않는다.
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로 자동 처리.
 */
@Schema(description = "가중치 변경 이력 1건")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AnalysisWeightHstryVO {

    /**
     * 변경이력일련번호 (chg_hstry_sn — PK, DB identity).
     *
     * <p>프론트 이력 표의 행 key 라 매퍼 SELECT 에서 빠지면 안 된다.
     */
    @Schema(description = "변경이력일련번호 (이력 표의 행 key)", example = "12")
    private Long chgHstrySn;

    /** 분석유형코드 (anls_type_cd — 변경 대상 분석유형) */
    @Schema(description = "분석유형코드", example = "HOTSPOT")
    private String anlsTypeCd;

    /** 변경일시 (chg_dt — 저장이 커밋된 시각. 이력은 이 값 내림차순으로 내려온다) */
    @Schema(description = "변경일시", example = "2026-08-26T10:15:30")
    private LocalDateTime chgDt;

    /** 변경자명 (chnrg_nm — 화면 표시용. 저장한 로그인 사용자의 user_nm) */
    @Schema(description = "변경자명", example = "홍길동")
    private String chnrgNm;

    /** 변경항목명 (chg_artcl_nm — 기록 시점의 분석유형명) */
    @Schema(description = "변경항목명 (기록 시점의 분석유형명)", example = "주차 집중구역 분석")
    private String chgArtclNm;

    /** 변경내용 (chg_cn — 기록 시점에 조립해 굳힌 표시용 문장) */
    @Schema(description = "변경내용", example = "민원빈도 60 → 55, 단속건수 40 → 45")
    private String chgCn;

    /** 변경사유 (chg_rsn — 사용자가 입력한 사유. 선택이라 null 일 수 있다) */
    @Schema(description = "변경사유 (선택)", example = "단속 실적 반영 비중 상향")
    private String chgRsn;
}
