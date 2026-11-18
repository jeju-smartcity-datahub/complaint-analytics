package jeju.datahub.lifezone.service;

/**
 * 생활권 혼잡도(주차 혼잡 지수) 분석 서비스. (TSK-4090)
 *
 * <p>민원건수({@link LifezoneAnalysisService})·수급({@link LifezoneSupplyService})과 인터페이스를
 * 합치지 않는다 — 격자 기준(공급&gt;0)·지표(소수 지수)·세 상태 판정이 다르다.
 */
public interface LifezoneCngstService {

    /**
     * 격자 기반 혼잡지수 산정 · 세 상태 판정 · 등급 · 순위.
     *
     * @param req 조회 조건. {@code null}·잘못된 값은 400
     * @return 공급&gt;0 격자 전량 + 등급 기준 + 요약. 결과 0건도 <b>정상(200)</b> 이다
     */
    LifezoneCngstResultVO analyze(LifezoneCngstRequest req);

    /**
     * {@link #analyze} 결과에 엑셀 재계산 데이터 {@code wbk}(공급&gt;0 원자료 + 개월수 + 적용 계수)를 함께 구한다
     * (TSK-4340 Step 2 · D4·D6). 보고서 저장 전용이다.
     *
     * <p>🔴 원자료는 분석이 이미 받은 {@code selectCngstCells} 행 그대로다(재조회 없음 · SQL·산식 변경 없음).
     *
     * @param req 조회 조건 + 계수 일시 입력
     * @return 분석 결과 + 엑셀 데이터
     */
    LifezoneCngstWithWorkbook analyzeWithWorkbook(LifezoneCngstRequest req);

    /**
     * {@link #analyzeWithWorkbook} 의 결과 한 벌.
     *
     * @param result 분석 결과 — {@link #analyze} 와 같다
     * @param wbk    엑셀 재계산 데이터
     */
    record LifezoneCngstWithWorkbook(LifezoneCngstResultVO result, LifezoneCngstWorkbookVO wbk) {
    }
}
