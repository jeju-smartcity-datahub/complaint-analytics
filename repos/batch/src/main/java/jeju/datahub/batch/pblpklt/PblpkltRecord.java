package jeju.datahub.batch.pblpklt;

import java.math.BigDecimal;

/**
 * 가공 단계 Writer 의 입력 — 적재 직전 레코드.
 *
 * <h2>🔁 2026-09-18(TSK-4162 Step 1 · C′) — {@code pkltId} 가 사라졌다</h2>
 * 종전에는 인메모리 배정기가 매긴 {@code {읍면동}-{순번}} 값을 여기 실어 Writer 가 함께
 * INSERT 했다. 그 카운터가 스킵·롤백마다 순번을 소모해, 이제 <b>{@code pklt_id} 는 DB 가
 * IDENTITY 로 매긴다</b>(마이그레이션 70). Writer 는 INSERT 시점에 새 번호를 알 수 없으므로
 * 이 레코드도 들고 다니지 않는다.
 *
 * <p>{@code originPk} 는 마지막으로 관측한 원천 키다. 비유일이며 주소 기준 갱신의
 * 식별자로 사용하지 않는다. 이상주소 관측은 계속 원천 행의 키로 기록한다.
 *
 * <h2>🔁 2026-09-12(TSK-4142 §7-5) — 원천 확장 25종이 합류했다</h2>
 * 앞 9필드는 종전 그대로다(Writer 가 accessor 로 쓴다 — 🔁 {@code pkltId} 가 빠져 10 → 9). 뒤
 * 25필드가 새 원천이 주는 값으로,
 * <b>Processor 는 값을 바꾸지 않고 그대로 나른다</b> — 파싱·정규화는 이미
 * {@link jeju.datahub.batch.pblpklt.api.PblpkltOriginRow} 가 끝냈고, 여기서 한 번 더 손대면
 * "원천이 준 값" 과 "우리가 만든 값" 의 경계가 흐려진다.
 *
 * <p>🔴 <b>{@code pkltAddr} 은 원천 {@code pklt_addr} 원문이다</b>(2026-09-12 의미 변경).
 * 종전에는 "지오코딩 입력으로 쓴 문자열"(= {@code pkltNm})을 담았다. 새 원천이 주소를 실제로
 * 주므로 원문을 그대로 적재하고, 지오코딩 입력은 원문에 접두를 붙일 수 있어
 * ({@code batch.pblpklt.geocode-addr-prefix}) <b>이 값과 분리</b>한다 —
 * {@link PblpkltGeocodeProcessor} 참고. 지오코딩에 실제로 무엇을 넣었는지는
 * "{@code pkltAddr} 이 비어 있지 않으면 그것, 아니면 {@code pkltNm}" 으로 결정적으로 되짚을 수
 * 있고, 실패 행은 {@code batch.tb_pblpklt_skip} 에 입력 문자열이 그대로 남는다.
 */
public record PblpkltRecord(
        String originPk,
        String pkltNm,
        String emdCd,
        BigDecimal lat,
        BigDecimal lot,
        Integer pkltCpcty,
        String pkltTypeCd,
        /** 원천 {@code pklt_addr} 원문 (접두를 붙이지 않은 값). 위 클래스 주석 참고. */
        String pkltAddr,
        /**
         * 주소 특이 패턴 관측 결과 (2026-08-24 합류).
         *
         * <p>Writer 가 서비스 테이블 upsert 와 <b>같은 청크 트랜잭션</b>에서
         * {@code batch.tb_pblpklt_addr_anomaly} 에 남긴다. 값을 바꾸지 않는 곁가지 기록이라
         * 정상 행이면 빈 리스트다.
         */
        java.util.List<String> anomalyTypes,
        // ── 2026-09-12 TSK-4142 확장 25종 (원천 값 그대로) ──
        String orgnlId,
        Integer orgnlSn,
        /** 원천 {@code emd_nm} 원문. {@code emdCd} 이름 폴백의 입력이기도 하다(§7-3). */
        String emdNm,
        /** 원천 유형명 원문. {@code pkltTypeCd} 의 판정 입력이라 반드시 함께 적재한다(재분류용). */
        String pkltTypeNm,
        BigDecimal pkltar,
        String prkCrgYn,
        String prkCrgCn,
        String prkCrgSwtcYm,
        String instlYm,
        String instlYmd,
        BigDecimal rttrt,
        Integer dayAvgVhclCnt,
        BigDecimal incmAmt,
        BigDecimal expndAmt,
        Integer mngNope,
        String mngDeptNm,
        String pkltMthdNm,
        Integer evChrgrCnt,
        String ownshpTypeNm,
        String prkSlotGrd,
        String obstclCn,
        Integer obstclSlotCnt,
        String trfcSftySignInstlYn,
        String stprInstlYn,
        String rmrkCn) {

    public PblpkltRecord {
        anomalyTypes = anomalyTypes == null ? java.util.List.of() : java.util.List.copyOf(anomalyTypes);
    }
}
