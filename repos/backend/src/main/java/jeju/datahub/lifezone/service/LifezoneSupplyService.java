package jeju.datahub.lifezone.service;

/**
 * 생활권 수급 분석 서비스. (TSK-4089)
 *
 * <p>민원건수 분석({@link LifezoneAnalysisService})과 인터페이스를 합치지 않는다 — 응답 타입이
 * 다르고(5등급 ↔ 4사분면) 격자 체계·기간 컬럼도 다르다.
 */
public interface LifezoneSupplyService {

    /**
     * 격자 기반 수급 분석.
     *
     * @param req 조회 조건. {@code null}·잘못된 값은 400
     * @return 격자 셀 전량 + 사분면 범례. 결과 0건도 <b>정상(200)</b> 이다
     */
    LifezoneSupplyResultVO analyze(LifezoneSupplyRequest req);

    /**
     * {@link #analyze} 결과에 엑셀 재계산 데이터 {@code wbk}(후보 격자 + 적용 계수)를 함께 구한다 (TSK-4340 Step 1 · D4·D5).
     * 보고서 저장 전용이다.
     *
     * <p>{@code analyze()} 와 같은 파라미터 조립(격자·기간·지역·조회 일수·적용 계수)을 쓰고, 후보는 같은 SQL 을
     * 최소 월평균 민원 = 범위 하한({@code min_vl}) 으로 한 번 더 부른 결과다. 결과 0건이어도 {@code wbk} 는 채운다(빈 {@code cells}).
     *
     * @param req 조회 조건 + 계수 일시 입력
     * @return 분석 결과 + 엑셀 데이터
     */
    LifezoneSupplyWithWorkbook analyzeWithWorkbook(LifezoneSupplyRequest req);

    /**
     * {@link #analyzeWithWorkbook} 의 결과 한 벌.
     *
     * @param result 분석 결과 — {@link #analyze} 와 같다
     * @param wbk    엑셀 재계산 데이터
     */
    record LifezoneSupplyWithWorkbook(LifezoneSupplyResultVO result, LifezoneSupplyWorkbookVO wbk) {
    }
}
