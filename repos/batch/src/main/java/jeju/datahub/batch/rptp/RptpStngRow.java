package jeju.datahub.batch.rptp;

/**
 * ④{@code reportAutoCrtJob} Reader 가 내는 한 행 — <b>살아 있는 자동생성 설정 하나</b>
 *
 * <p><b>기준일을 담지 않는다.</b> 기준일은 Job 파라미터 {@code baseYmd} 하나가 정본이라
 * ({@code RiskAlertTargetRow} 와 같은 이유) 행마다 같은 값을 복사해 나르지 않는다 —
 * 두 곳에 두면 어긋날 자리만 생긴다.
 *
 * <p>🔴 <b>{@code sggNm}·{@code emdCd} 는 설정 원문 그대로</b> 나른다. 읍면동
 * <b>이름</b>으로 풀어 주는 일은 백엔드가 한다 — 배치가 {@code tb_admdst} 를 따로 읽지 않는다.
 *
 * @param stngId      설정 PK({@code stng_id}, BIGINT identity)
 * @param rptpTypeCd  보고서종류코드 {@code CVLCPT}(민원) | {@code HOTSPOT}(집중구역)
 * @param crltnCyclCd 배포주기코드 {@code MONTH|QUARTER|HALF|YEAR}
 *                    (🔴 {@code WEEK} 이 DB 직접 조작으로 들어올 수 있다 —
 *                    {@link ReportDueDateResolver#isDue} 가 false 로 끊는다)
 * @param crltnMm     배포기준월 1~12. {@code MONTH} 는 NULL 이다(주기가 기준월과 무관하다)
 * @param crltnDd     배포기준일 1~28. DB 체크 제약({@code ck_rptp_stng_crltn_dd})이 28 을 상한으로
 *                    둬서 "31일이 없는 달" 함정이 없다
 * @param sggNm       시군구명. 전체 지역 설정이면 NULL
 * @param emdCd       읍면동코드. 시군구 단위 설정이면 NULL
 * @param crltnDeptCd 배포부서코드 — {@code TB_RPTP} 스냅샷 칸에 그대로 실린다
 */
public record RptpStngRow(
        Long stngId,
        String rptpTypeCd,
        String crltnCyclCd,
        Integer crltnMm,
        Integer crltnDd,
        String sggNm,
        String emdCd,
        String crltnDeptCd) {
}
