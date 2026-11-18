package jeju.datahub.batch.risknoti;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code parking.tb_risk_stp_noti_hstry} 에 적재할 <b>판정 결과 한 행</b>
 * (TSK-4069 Step 5 · D2·D5·D6·D8).
 *
 * <p>필드 순서 = {@link RiskAlertItemWriter#UPSERT_SQL} 의 INSERT 컬럼 순서 =
 * {@link RiskAlertItemWriter#SETTER} 의 인덱스 순서다. <b>셋을 어긋나게 두지 말 것</b>
 * (선례 {@code CvlcptRecord}/{@code CvlcptItemWriter} 와 같은 규약).
 *
 * <p>🔴 <b>{@code prcs_stts_cd} 는 필드가 아니다</b> — Writer SQL 에 {@code 'OPEN'} 리터럴로
 * 못박혀 있다. TSK-4028 범위에서 상태는 항상 OPEN 이고 실질 상태는 열람({@code prsl_yn})·
 * 확인({@code idnty_yn})이 담당한다(D7). 파라미터로 두면 "배치가 정하는 값"으로 오독된다.
 *
 * <p>🔴 열람·확인 4컬럼({@code prsl_yn}·{@code prsl_dt}·{@code idnty_yn}·{@code idnty_dt})도
 * 필드가 없다 — 배치가 만들지도, 갱신하지도 않는 값이다(D7).
 *
 * @param riskLvlCd    판정된 위험단계 코드({@code CRIT}/{@code WARN}/{@code CAUT}).
 *                     🔴 넘긴 기준 중 <b>{@code incrs_rt} 가 가장 큰 행</b> 하나다(D5).
 *                     하나도 못 넘으면 이 record 자체가 만들어지지 않는다(Processor 가 {@code null} 반환)
 * @param emdCd        판정 대상 읍면동 코드. {@code ocrnDt} 와 함께 upsert 키를 이룬다(D6)
 * @param cvlcptCnt    <b>당일 민원 건수(보정 전 실제 값)</b>. 분모 하한 보정(D2)은 {@code incrsRt}
 *                     계산에만 쓰고 이 값에는 적용하지 않는다 — 화면에 "몇 건이었나"로 그대로 나간다
 * @param incrsRt      전일 대비 증가율(%). {@code NUMERIC(7,2)} 라 scale 2 · 상한 99999.99 다
 *                     (초과분은 Processor 가 스킵으로 걷어낸다 — D9)
 * @param notiCtt      🔴 <b>판정 시점 스냅샷 문구</b> {@code "{읍면동명} 민원 {n}% 증가"} (D8).
 *                     조회가 재조립하지 않으므로 여기서 비우면 화면에 빈 칸이 나간다
 * @param incrsRtThrsh 🔴 <b>판정 당시 기준값 스냅샷</b>(D8). 기준({@code tb_risk_stp_noti_crtr})이
 *                     바뀌어도 과거 판정 근거가 남는다. 컬럼명은 {@code incrs_rt_thrsh} 다
 *                     (49-STANDARDIZE-PARKING-NAMES 로 {@code thrsh_incrs_rt} 에서 개명)
 * @param ocrnDt       🔴 <b>판정일 00:00:00</b>(D1·D6). 실행 시각이 아니다 —
 *                     {@code UNIQUE (emd_cd, ocrn_dt)} 가 "읍면동 × 판정일 1건"을 뜻하려면
 *                     같은 날 몇 번을 돌려도 같은 값이어야 한다
 */
public record RiskAlertRecord(
        String riskLvlCd,
        String emdCd,
        long cvlcptCnt,
        BigDecimal incrsRt,
        String notiCtt,
        BigDecimal incrsRtThrsh,
        LocalDateTime ocrnDt) {
}
