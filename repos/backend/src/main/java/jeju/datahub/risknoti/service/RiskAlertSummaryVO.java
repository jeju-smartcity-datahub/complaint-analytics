package jeju.datahub.risknoti.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 알림 조회 화면 상단 요약 카드 VO. (TSK-4028 D8 — 목록과 <b>한 응답</b>으로 내린다)
 *
 * <p><b>페이지가 아니라 필터 전체 기준</b>이다 — 목록·카운트와 같은 {@code alertWhere} 조각을 공유하므로
 * 페이지를 넘겨도 값이 변하지 않는다. 프론트가 페이징된 데이터로 집계하면 총계가 틀린다.
 *
 * <p><b>알림 건수 4종({@code totalCnt}·{@code critCnt}·{@code warnCnt}·{@code cautCnt})은 '사건' 기준이다</b>
 * (TSK-4028 D13·D15) — 이력 1행 = 판정 사건 1건이라 {@code count(*)} 가 그대로 사건 단위 집계다.
 * 수신자 조인이 없어 {@code DISTINCT} 우회도 필요 없다.
 *
 * <p>🔴 <b>{@code cvlcptSumCnt} 만 원천(민원 테이블)이 다르다</b> (TSK-4069 Step 9) — 이력이 아니라
 * {@code parking.tb_cvlcpt} 를 직접 센다. 이력 합은 판정 배치가 <b>기준을 넘은 읍면동만</b> 남기므로
 * "알림이 난 지역의 민원" 만 세어 카드 부제(선택 기간 합계)와 어긋난다.
 * 그래서 <b>단계 필터({@code riskLvlCd})로는 이 값만 변하지 않는다</b> — 민원 raw 에 위험단계가 없기 때문이며
 * 의도된 동작이다. 기간·지역 필터는 그대로 반영된다.
 */
@Schema(description = "위험단계 알림 요약 (필터 전체 기준)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RiskAlertSummaryVO {

    /** 전체 알림 건수 (필터 적용 후) */
    @Schema(description = "전체 알림 건수", example = "18")
    private long totalCnt;

    /** 심각(CRIT) 건수 */
    @Schema(description = "심각 건수", example = "4")
    private long critCnt;

    /** 경고(WARN) 건수 */
    @Schema(description = "경고 건수", example = "8")
    private long warnCnt;

    /** 주의(CAUT) 건수 */
    @Schema(description = "주의 건수", example = "6")
    private long cautCnt;

    /**
     * 선택 기간·지역의 <b>실제 민원 총합</b> — {@code parking.tb_cvlcpt} 를 직접 센다
     * ({@code emd_cd IS NOT NULL} 인 행만. 미분류 민원은 지오코딩을 생략해 읍면동이 없다).
     * 대상이 0건이면 0. 🔴 <b>단계 필터로는 변하지 않는다</b>(민원 raw 에 위험단계가 없다).
     */
    @Schema(description = "선택 기간·지역의 민원 총합 (알림 이력이 아니라 민원 테이블 기준). 단계 필터로는 변하지 않는다",
            example = "168")
    private long cvlcptSumCnt;
}
