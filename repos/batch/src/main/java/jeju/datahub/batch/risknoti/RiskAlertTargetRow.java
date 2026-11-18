package jeju.datahub.batch.risknoti;

/**
 * ③{@code riskAlertJudgeJob} Reader 가 내는 한 행 — <b>읍면동 하나의 (당일, 전일) 민원 건수</b>
 * (TSK-4069 Step 4 · D3·D4).
 *
 * <p><b>날짜를 담지 않는다.</b> 집계 SQL 이 이미 {@code :targetDate} 로 걸러 낸 뒤라 행마다
 * 같은 값을 74번 복사하게 되고, Processor 는 어차피 Job 파라미터에서 판정일을 다시 받는다
 * (Step 5) — 두 곳에 같은 날짜를 두면 어긋날 자리만 생긴다.
 *
 * @param emdCd     읍면동 코드({@code parking.tb_cvlcpt.emd_cd}). 🔴 NULL 은 SQL 에서 이미
 *                  제외됐다 — 실측상 {@code cvlcpt_type_cd=99}(미분류) 전량이다(D3)
 * @param emdNm     읍면동 명({@code parking.tb_admdst.emd_nm}). 알림 문구
 *                  ({@code noti_ctt})에 그대로 실린다(D8)
 * @param targetCnt <b>판정일 당일</b> 민원 건수. 1 이상이다 — 0건인 읍면동은 애초에 읽지 않는다(D4)
 * @param prevCnt   <b>전일</b> 민원 건수. 🔴 <b>0 일 수 있다</b> — {@code LEFT JOIN} +
 *                  {@code COALESCE(...,0)} 라 전일 무민원 읍면동도 그대로 흘러온다.
 *                  분모 보정({@code GREATEST(prevCnt,1)})은 Processor 의 몫이다(D2)
 */
public record RiskAlertTargetRow(
        String emdCd,
        String emdNm,
        long targetCnt,
        long prevCnt) {
}
