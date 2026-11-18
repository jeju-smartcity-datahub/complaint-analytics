package jeju.datahub.complaint.service;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 대시보드 상단 "최종 업데이트" 표기용 데이터 현황. 필터를 받지 않는 전역 지표다.
 *
 * <p>🔴 출처는 {@code MAX(tb_cvlcpt.ocrn_dt)} 다 — {@code load_dt} 는 이름과 달리
 * 배치가 넣는 값이 원천 답변일(ans_ymd) 자정이라 "언제까지 들어왔나"를 나타내지 못한다
 * (TSK-4085 D7). 배치 실행 시각이 필요해지면 출처를 {@code batch.tb_btch_excn_hstry} 로
 * 바꾸면 되고, 화면·프론트 계약은 그대로다.
 *
 * <p>이름 근거(데이터표준사전 v1.0): 최종 LAST · 발생 OCRN · 일시 DT · 민원 CVLCPT · 수 CNT.
 */
@Getter
@Setter
@ToString
public class DataStatusVO {

    /** 민원 최종 발생일시(MAX(ocrn_dt)). 데이터가 없으면 null. */
    private LocalDateTime lastOcrnDt;

    /** 집계 대상 민원 총건수. */
    private long cvlcptCnt;
}
