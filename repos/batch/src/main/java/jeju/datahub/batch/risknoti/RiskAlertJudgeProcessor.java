package jeju.datahub.batch.risknoti;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import jeju.datahub.batch.risknoti.RiskCriteriaCache.RiskCriteria;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 읍면동 하나의 (당일, 전일) 건수를 <b>증가율 → 위험단계 → 알림 스냅샷</b>으로 바꾼다.
 *
 * <h2>🔴 판정일은 Reader 가 아니라 Job 파라미터에서 직접 온다</h2>
 * {@link RiskAlertTargetRow} 는 집계 결과만 담고 <b>날짜를 담지 않는다</b>(같은 값을 행마다
 * 복제해 나르게 되므로). 그런데 여기서는 {@code ocrn_dt}(= 판정일 00:00:00 · D1·D6)와 스킵
 * 기록용 {@code trgt_de}(D9) 둘 다 필요하다 — 그래서 <b>Reader 와 똑같은 방식·똑같은 규칙</b>으로
 * 판정일을 확정한다.
 *
 * <p>⚠️ 날짜 해석은 {@link RiskAlertProperties#resolveTargetDate(LocalDate)} <b>한 곳만</b> 쓴다.
 * Reader 와 Processor 가 각자 {@code LocalDate.now()} 를 부르면 자정 근처에서 <b>하루가 갈릴 수
 * 있다.</b> 트리거·스케줄러가 {@code targetDate} 를 항상 넣도록 한 것도 같은 이유다(그러면 이
 * 경로는 파라미터를 파싱만 한다).
 *
 * <h2>⚠️ 계산은 {@link BigDecimal} 로 한다 — {@code double} 로 하지 않는다</h2>
 * 이진 부동소수는 {@code 30} 같은 경계에서 {@code 29.999999...} 로 떨어져 <b>판정을 뒤집을 수
 * 있다.</b> 기준 비교가 "이상"이라 경계값이 그대로 단계 차이가 된다.
 */
@Component
@StepScope
public class RiskAlertJudgeProcessor implements ItemProcessor<RiskAlertTargetRow, RiskAlertRecord> {

    /** {@code tb_risk_stp_noti_hstry.incrs_rt} 는 {@code NUMERIC(7,2)} — 상한이 이 값이다(D9). */
    static final BigDecimal MAX_INCRS_RT = new BigDecimal("99999.99");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** {@code incrs_rt} 의 소수 자릿수. DB 컬럼 scale 과 같아야 반올림 결과가 한 번 더 흔들리지 않는다. */
    private static final int SCALE = 2;

    private final RiskCriteriaCache criteriaCache;
    private final LocalDate judgeDate;

    /**
     * @param criteriaCache 이번 실행의 판정 기준({@code @StepScope} 라 Step 시작 때 새로 읽는다)
     * @param targetDateParam 선택 Job 파라미터 {@code targetDate}({@code yyyy-MM-dd}).
     *                        없거나 비면 "Asia/Seoul 기준 어제"(D1·D11)
     */
    public RiskAlertJudgeProcessor(RiskCriteriaCache criteriaCache,
                                   @Value("#{jobParameters['targetDate']}") String targetDateParam) {
        this.criteriaCache = criteriaCache;
        // 🔴 Reader 와 같은 파서를 쓴다 — 빈 문자열 처리까지 같아야 두 경로의 판정일이 갈리지 않는다.
        this.judgeDate = RiskAlertProperties.resolveTargetDate(
                RiskAlertTargetItemReader.parseTargetDate(targetDateParam));
    }

    /**
     * @return 적재할 판정 결과. <b>어떤 기준도 못 넘으면 {@code null}</b> — 알림이 없다는 뜻이고
     *         Spring Batch 는 그 행을 조용히 걸러 낸다(정상 경로. 대부분의 읍면동이 여기다)
     * @throws RiskAlertSkippableException 증가율이 저장 범위를 넘어 <b>이 행만</b> 건너뛸 때(D9)
     */
    @Override
    public RiskAlertRecord process(RiskAlertTargetRow row) {
        // 0) 판정일 — 생성자에서 확정한 값. ocrn_dt 는 '판정일 00:00:00' 이다 (D1·D6).
        //    ⚠️ 실행 시각(now())이 아니다 — 같은 날 두 번 돌려도 upsert 키가 같아야 한다.
        LocalDateTime ocrnDt = judgeDate.atStartOfDay();

        // 1) 증가율 — D2. 🔴 전일 0건이면 분모를 1로 친다.
        //    분모 0 을 피하려 스킵하면 `0 → 20건` 같은 최악의 급증을 통째로 놓친다.
        //    ⚠️ 분자에서도 같은 보정값을 뺀다(GREATEST 는 분자·분모 양쪽에 적용된다).
        //    🔁 하한이 1 이상이면 전일 0건은 아래 2) 에서 탈락한다. 이 보정이 알림으로
        //       이어지는 것은 하한 0(꺼짐) 일 때뿐이다.
        long prevBase = Math.max(row.prevCnt(), 1L);
        BigDecimal incrsRt = BigDecimal.valueOf(row.targetCnt() - prevBase)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(prevBase), SCALE, RoundingMode.HALF_UP);

        // 1-1) 🔴 저장 가능 범위 검사 — D9. 읍면동 한 곳의 병적인 값이 그날 전체 판정을 죽이지 않게
        //      여기서 걸러 스킵으로 넘긴다(그대로 두면 INSERT 가 numeric field overflow 로 죽는다).
        //      ⚠️ 실측상 현재 데이터의 읍면동 최대 일일 민원은 61건이라 발생하지 않는다 — 예방 장치다.
        if (incrsRt.compareTo(MAX_INCRS_RT) > 0) {
            throw new RiskAlertSkippableException(
                    RiskAlertSkippableException.RATE_OVERFLOW, row, incrsRt, judgeDate);
        }

        // 2) 단계 판정 — D5: 넘긴 기준 중 임계가 가장 큰 행 하나. 못 넘으면 null(알림 없음).
        //    🔴 당일 건수 하한도 함께 본다. 표본이 작으면 증가율 자체가 무의미하기 때문이다
        //       (전일 1건 → 당일 3건 = 200% 가 전일 20건 → 당일 25건 = 25% 보다 심각하게 뜨던 문제).
        //    🔁 전일 건수도 같은 하한을 본다. 전일이 작으면 분모가 작아 증가율이 과대해진다.
        //       🔴 prevBase(보정값)가 아니라 row.prevCnt()(실제 값)를 넘긴다.
        RiskCriteria hit = criteriaCache.highestMatched(incrsRt, row.targetCnt(), row.prevCnt());
        if (hit == null) {
            return null;                                   // ⚠️ 정상 경로다. 예외가 아니다.
        }

        // 3) 스냅샷 — D8. 조회가 재조립하지 않으니 여기서 만들어 둔다.
        String notiCtt = row.emdNm() + " 민원 " + trimZeros(incrsRt) + "% 증가";

        return new RiskAlertRecord(
                hit.riskLvlCd(), row.emdCd(), row.targetCnt(),
                incrsRt, notiCtt, hit.incrsRt(), ocrnDt);
    }

    /**
     * 알림 문구용 숫자 표기 — <b>{@code rtrim(to_char(incrs_rt,'FM990.99'),'.')} 와 같은 결과</b>여야
     * 한다(D8. 시드가 그 SQL 로 만든 18행과 표기가 갈리면 안 된다).
     *
     * <pre>
     *   26.50 → "26.5"      45.00 → "45"      1900.00 → "1900"      0.00 → "0"
     * </pre>
     *
     * <p>🔴 {@code 45.} 처럼 <b>소수점만 남기지 않는다</b> — {@code "45.% 증가"} 가 저장되면
     * 검증({@code noti_ctt LIKE '%.\%%'} → 0)이 깨진다. {@code stripTrailingZeros()} 는
     * {@code 45.00} 을 {@code 4.5E+1}(scale −1)로 만들므로 {@code toPlainString()} 을 그대로 쓰면
     * 되지만, scale 이 0 이하로 떨어진 경우에는 {@code toBigInteger()} 로 정수 표기를 확정한다.
     */
    static String trimZeros(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0
                ? stripped.toBigInteger().toString()      // 45.00 → "45" (🔴 "45." 이 되면 안 된다)
                : stripped.toPlainString();               // 26.50 → "26.5"
    }
}
