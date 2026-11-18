package jeju.datahub.analysis.service;

/**
 * 불법주차 집중구역 분석 서비스 인터페이스. (분석 화면 "불법 주차 집중 구역 분석" — TSK-4032)
 *
 * <p>조회 전용이다 — 분석 결과를 저장하지 않는다(D7). {@code TB_ANLS_EXCN}·
 * {@code TB_ANLS_RSLT} 기록은 후속 보고서 생성 기능이 담당한다.
 *
 * <p>가중치는 이 서비스가 직접 읽거나 재정규화하지 않는다 — TSK-4020 의
 * {@link AnalysisWeightService#selectEffectiveWeights(String)} 를 주입해 쓴다(D4 · TSK-4020 D14).
 */
public interface HotspotAnalysisService {

    /**
     * 조건에 맞는 집중구역을 군집 분석해 순위 목록으로 돌려준다.
     * (GET /api/analysis/hotspot)
     *
     * <p>결과가 0건이어도 예외가 아니라 <b>빈 {@code zones}</b> 로 돌려준다 — 조건에 데이터가
     * 없거나 군집이 형성되지 않은 정상 상태다(D11). 이때도 원천 보유 구간은 채워 보낸다.
     *
     * @param req 기간(필수) · 지역(선택) · 결과 개수
     * @return 조건 echo · 군집 파라미터 · 적용 가중치 · 원천 보유 구간 · 집중구역 목록
     */
    HotspotAnalysisResultVO analyze(HotspotAnalysisRequest req);

    /**
     * {@link #analyze} 결과에 엑셀 재계산 데이터 {@code wbk}(후보 구역 + 상수)를 함께 구한다 (TSK-4338 D2·D3·D5).
     * 보고서 저장 전용이다.
     *
     * <p>🔴 둘을 한 메서드로 묶은 이유: 구역명 외부 조회 상한이 <b>세션 단위</b>(요청당 50회)라,
     * 분석과 후보 조회가 같은 세션을 써야 보고서 1건의 VWorld 호출이 50회를 넘지 않는다.
     * 후보 중 표시 구역은 분석 결과의 이름을 중심좌표로 짝지어 복사하므로 PDF 와 이름이 갈리지 않는다.
     *
     * <p>{@code analyze()} 와 같은 파라미터 조립(기간·지역·가중치·등급 비율·eps·minPoints)을 쓴다.
     * 표시 구역이 0건이면 {@code wbk} 는 {@code null} 이다(보고서는 그 경우 400 이다).
     *
     * @param req 기간(필수) · 지역(선택) · 가중치·계수 일시 입력
     * @return 분석 결과 + 엑셀 데이터
     */
    HotspotAnalysisWithWorkbook analyzeWithWorkbook(HotspotAnalysisRequest req);

    /**
     * {@link #analyzeWithWorkbook} 의 결과 한 벌.
     *
     * @param result 분석 결과 — {@link #analyze} 와 같다
     * @param wbk    엑셀 재계산 데이터. 표시 구역이 0건이면 {@code null}
     */
    record HotspotAnalysisWithWorkbook(HotspotAnalysisResultVO result, HotspotWorkbookVO wbk) {
    }
}
