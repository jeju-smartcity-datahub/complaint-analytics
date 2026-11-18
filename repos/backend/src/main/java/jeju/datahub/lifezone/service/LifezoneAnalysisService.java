package jeju.datahub.lifezone.service;

import java.util.List;

/**
 * 생활권 시뮬레이션 분석 서비스 인터페이스. (분석 화면 "생활권 시뮬레이션" — TSK-4088)
 *
 * <p>조회 전용이다 — 분석 결과를 저장하지 않는다(D12 · TSK-4032 D7 과 동일).
 * {@code TB_ANLS_EXCN}·{@code TB_ANLS_RSLT} 기록은 보고서 저장 기능이 담당한다.
 *
 * <p>가중치를 쓰지 않는다(D8) — 분석유형이 배타 선택이라 지표를 섞을 일이 없다. 집중구역과 달리
 * {@code AnalysisWeightService} 를 주입하지 않는다.
 *
 * <p>🔴 <b>입력 값 검증은 이 계층의 책임</b>이다. 전역 예외 핸들러가 없어
 * {@code LifezoneAnalysisRequest} 의 빈 API 제약만으로는 400 을 낼 수 없다 —
 * 격자크기 4종 여부 · 미구현 분석유형 · 빈 문자열 지역 · 기간 역전 · 미등록 지역을 모두
 * {@code ResponseStatusException(BAD_REQUEST, …)} 로 던진다.
 */
public interface LifezoneAnalysisService {

    /**
     * 조건에 맞는 격자를 집계해 지도 셀·순위 목록으로 돌려준다.
     * (GET /api/analysis/lifezone)
     *
     * <p>결과가 0건이어도 예외가 아니라 <b>빈 {@code cells}·{@code ranking}</b> 으로 돌려준다 —
     * 조건에 데이터가 없는 정상 상태다(D11). 이때도 원천 보유 구간과 등급 기준 5종은 채워 보낸다.
     *
     * <p>등급 경계는 <b>조회 결과에서 산출</b>한다(D17) — 전 셀을 다 받은 뒤에야 정해지므로
     * 셀별 판정보다 목록 확보가 먼저다.
     *
     * @param req 분석유형 · 격자크기 · 기간(필수) · 지역(선택) · 순위 개수
     * @return 조건 echo · 유형 문구 · 가용 유형 · 구간 경계 · 요약 집계 · 셀 전량 · 순위 목록
     */
    LifezoneAnalysisResultVO analyze(LifezoneAnalysisRequest req);

    /**
     * 분석유형 가용 목록. (GET /api/analysis/lifezone/targets)
     *
     * <p>설정 패널이 마운트 시 1회 부른다 — 진입 직후에는 분석을 돌리지 않으므로(D1) 세그먼트를
     * 그리는 데 필요한 정보만 따로 낸다. 미구현 유형도 사유와 함께 포함한다(D2).
     *
     * @return {@link LifezoneTarget} 선언 순서 3건
     */
    List<LifezoneTargetVO> selectTargets();
}
