package jeju.datahub.batch.utztn;

import java.math.BigDecimal;

/**
 * 적재 직전 한 행 — Processor 출력이자 Writer 입력이다 (TSK-4189).
 *
 * @param pkltId        해석된 우리 주차장 ID (🔴 원천 값이 아니다 — {@link PkltUtztnPkltIdResolver} 가 해석했다)
 * @param totYm         집계연월 {@code yyyyMM} (CHAR(6))
 * @param prkCntom      주차대수 (원천 {@code use_cnt})
 * @param dayAvgTrnvrRt 일평균회전율 (원천 {@code use_rate})
 */
public record PkltUtztnRow(long pkltId, String totYm, int prkCntom, BigDecimal dayAvgTrnvrRt, String srcJson) {
    public PkltUtztnRow(long id, String month, int count, BigDecimal rate) {
        this(id, month, count, rate, null);
    }
}
