package jeju.datahub.dashboard.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 대시보드 "AI 추천 질문" 카드용 조회 전용 VO. (parking.TB_FAQ — MyBatis 전용)
 *
 * <p>노출 대상(expsr_yn=true)·노출 순서(expsr_seq)로 정렬한 FAQ 질문을 추천 질문으로 사용한다.
 * 컬럼(스네이크) → 필드(카멜) 매핑은 map-underscore-to-camel-case=true 로 자동 처리.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class SuggestionVO {

    /** FAQ ID (faq_id, BIGINT) */
    private Long faqId;

    /** 질문 (qstn) */
    private String qstn;
}
