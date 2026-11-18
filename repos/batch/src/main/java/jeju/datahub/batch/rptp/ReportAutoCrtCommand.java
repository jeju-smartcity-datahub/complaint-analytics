package jeju.datahub.batch.rptp;

/**
 * Processor 산출물 — <b>백엔드에 보낼 "보고서 한 건을 만들어라" 요청</b> 하나.
 *
 * <p>필드는 백엔드 {@code ReportAutoCrtRequest}와 <b>일대일</b>이다.
 * {@code BackendReportClient} 가 이 record 를 그대로 JSON 바디로 직렬화한다.
 *
 * <p>🔴 <b>집계 결과(건수·통계)를 담지 않는다.</b> 배치가 숫자를 보내면 그 값이 보고서에
 * 영구 보존된다(와 같은 근거) — 숫자는 백엔드가 조회 시점에 계산한다.
 *
 * <p>🔴 <b>{@code fileFmt} 를 담지 않는다</b>(D14). 자동 생성분은 {@code file_fmt} 가 NULL 이며
 * 배치가 보낼 값이 없다.
 *
 * <p>🔴 <b>보고서명({@code rptpNm})을 담지 않는다.</b> 이름은 백엔드가
 * {@code ReportAutoCrtNaming} 으로 짓는다 — 지역 접미를 붙이려면 {@code emd_cd → emd_nm}
 * 을 풀어야 하고 그 표({@code tb_admdst})는 백엔드가 읽는다. 배치는 설정 원문만 나른다(D10).
 *
 * @param stngId      설정 PK — 멱등키의 한 칸({@code uq_rptp_stng_prd})
 * @param rptpTypeCd  보고서종류 {@code CVLCPT} | {@code HOTSPOT}
 * @param trgtPrdCd   대상기간 코드 — 🔴 <b>멱등키의 다른 한 칸.</b> 배치가 만들어 보낸다
 * @param bgngYmd     대상기간 시작일 {@code YYYYMMDD}
 * @param endYmd      대상기간 종료일 {@code YYYYMMDD}
 * @param sggNm       시군구명(설정 원문, nullable)
 * @param emdCd       읍면동코드(설정 원문, nullable)
 * @param crltnDeptCd 배포부서코드 — {@code TB_RPTP} 스냅샷 칸
 * @param crltnCyclCd 배포주기코드 — 동
 */
public record ReportAutoCrtCommand(
        Long stngId,
        String rptpTypeCd,
        String trgtPrdCd,
        String bgngYmd,
        String endYmd,
        String sggNm,
        String emdCd,
        String crltnDeptCd,
        String crltnCyclCd) {
}
