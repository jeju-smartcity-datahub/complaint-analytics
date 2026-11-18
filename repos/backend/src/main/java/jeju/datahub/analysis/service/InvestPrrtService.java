package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 투자우선순위 분석 서비스. (분석 화면 "투자 우선순위" — TSK-4036)
 *
 * <p>노상·공한지를 제외한 공영주차장마다 종합점수·순위·등급을 낸다. 🔴 <b>조회는 적재하지 않는다</b>
 * (집중구역과 같은 방식 — 실행 이력·결과는 보고서로 저장할 때만 쌓인다. 조회마다 적재하는 구역추천과 다르다).
 */
public interface InvestPrrtService {

    /**
     * 조회 기간 상한(개월 — 걸친 달 수). 정확성이 아니라 <b>스캔 비용</b> 때문이다
     * ({@code ZoneRcmdtnController} 의 상한과 같은 값).
     */
    int MAX_PRD_MONTHS = 36;

    /** 기간을 생략했을 때의 창 길이(개월) — "오늘 기준 최근 1년"(TSK-4352 — 종전 3) */
    int DEFAULT_PRD_MONTHS = 12;

    /**
     * 투자우선순위를 계산한다.
     *
     * @param bgngYmd 분석 시작일(YYYYMMDD). {@code endYmd} 와 <b>둘 다 생략</b>하면 오늘에서 12개월 전
     * @param endYmd  분석 종료일(YYYYMMDD, 포함). 둘 다 생략하면 오늘
     * @param sggNm   시군구명 표시 필터. 생략(빈 값 포함)하면 걸지 않는다 — 🔴 점수·순위는 바뀌지 않는다
     * @param emdCd   읍면동코드 표시 필터. 생략(빈 값 포함)하면 걸지 않는다
     * @return 적용 기간·가중치·등급 기준·알림·요약과 주차장 목록(전체 순위 오름차순)
     * @throws org.springframework.web.server.ResponseStatusException 400 — 날짜가 한쪽만 왔거나 형식
     *         오류·역전·36개월 초과 / 409 — 분석변수 설정 오류(반영할 가중치 없음·등급 임계 역전)
     */
    InvestPrrtResultVO analyze(String bgngYmd, String endYmd, String sggNm, String emdCd);

    /**
     * 분석 계수 일시 입력을 얹어 계산한다 (TSK-4325). 설정 표에는 쓰지 않는다.
     *
     * @param vrblVls 변수코드 → 값. {@code null}·빈 Map 이면 설정값 — {@link #analyze(String, String, String, String)} 와 같다
     * @throws org.springframework.web.server.ResponseStatusException 위의 경우에 더해 400 — 조정할 수 없는 변수 ·
     *         범위 밖 · 가중치 합계가 100 아님 · 중간등급기준점수 ≥ 상위등급기준점수
     */
    InvestPrrtResultVO analyze(String bgngYmd, String endYmd, String sggNm, String emdCd,
                               Map<String, BigDecimal> vrblVls);

    /**
     * 🆕 TSK-4343 — 위 5인자와 같은 결과에 엑셀 재계산 데이터 {@code wbk}(모집단 수 · 주차장별 백분위 개수 · 계수 8행)를 붙인다.
     *
     * <p>🔴 <b>보고서 경로 전용</b>이다 — 조회 GET 응답에는 {@code wbk} 를 싣지 않는다(화면 페이로드 불변).
     * 단속({@code ENFORCE}) 가중치가 반영된 설정이면 {@code wbk} 는 {@code null} 이다(D2-1 — 결과는 그대로).
     */
    InvestPrrtResultVO analyzeWithWorkbook(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                           Map<String, BigDecimal> vrblVls);
}
