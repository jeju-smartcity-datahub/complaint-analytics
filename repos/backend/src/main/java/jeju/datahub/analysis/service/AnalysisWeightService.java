package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 분석변수 설정 서비스 인터페이스. (설정 화면 "분석 변수 설정" 탭)
 *
 * <p>⚠️ <b>이 5개 메서드는 모듈 간 계약이다.</b> 주차 집중구역 분석이 이
 * 인터페이스를 주입해 {@code HOTSPOT} 가중치를 읽고, <b>재정규화도 직접 구현하지 않고</b>
 * {@link #selectEffectiveWeights(String)} 를 호출한다 (2026-08-26
 * 사용자 확정 "가중치는 모두 우리가 정리한다"). 이름이나 시그니처를 바꾸면 호출부도
 * 같은 커밋에서 함께 고친다.
 *
 * <p>🆕 <b>2026-09-11 — 종전 4개에서 {@link #coefficient(String, String)} 하나를
 * 늘려 5개가 됐다.</b> 표가 가중치({@code WGVL})뿐 아니라 계수({@code CFC})까지 담게 되면서
 * (마이그레이션 67), 생활권 공급-수요 분석의 민원 환산계수 {@code k} 가 {@code application.yml}
 * 에서 이 표로 옮겨 왔다 — <b>{@code k} 는 이 메서드로 읽는다</b>
 * ({@code LifezoneAnalysisServiceImpl}). 계수는 합계 100 도, 재정규화도 성립하지 않아
 * {@link #selectEffectiveWeights(String)} 로는 읽을 수 없기에 별도 창구를 낸다.
 * 이것도 위 4개와 같은 모듈 간 계약이므로 이름·시그니처를 바꾸면 호출부를 함께 고친다.
 *
 * <p>🆕 <b>2026-09-11 — {@link #selectHistories(int, String)} 의 시그니처가
 * 바뀌었다</b> (종전 {@code selectHistories(int size)}). 사유는 사용자 요청이다 —
 * `설정 &gt; 분석 지표 가중치 설정` 의 <b>설정 변경 이력이 전 분석유형을 섞어</b> 보여 줬다.
 * 🔴 프론트 필터로는 못 고친다: 매퍼가 {@code ORDER BY chg_dt DESC LIMIT #{size}} 로
 * <b>전 유형을 섞어 자른 뒤</b> 내려주므로, 한 유형을 {@code size} 번 저장하면 다른 탭 이력이
 * 실제로는 있는데도 0건이 된다. <b>거른 뒤에 자르도록</b> 매퍼(WHERE→LIMIT)에 걸었다.
 * 🔴 {@code anlsTypeCd} 는 <b>선택 파라미터</b>라 {@code null} 이면 종전과 같이 전 유형을 준다 —
 * 기존 호출자의 계약을 깨지 않기 위해서다. 메서드 개수는 5개 그대로다.
 */
public interface AnalysisWeightService {

    /** 분석유형 5종 + 지표 + 합계 (GET /api/analysis-weights). */
    List<AnalysisTypeWeightVO> selectAnalysisTypeWeights();

    /**
     * 분석유형 1건 전체 교체 저장 → 갱신 후 1건 (PUT /api/analysis-weights/{anlsTypeCd}).
     *
     * <p>🔁 2026-10-30 — 변경자({@code mdfr_id}·{@code chnrg_id}·{@code chnrg_nm})가 고정값 {@code system}
     * 에서 <b>로그인 사용자</b>로 바뀌어 {@code userId}·{@code userNm} 을 받는다. 로그인 사용자 해석과 401 은
     * 컨트롤러({@code CurrentUserResolver})가 끝낸다 — 서비스는 인증 기술을 모른다(관례).
     */
    AnalysisTypeWeightVO saveWeights(String anlsTypeCd, AnalysisWeightSaveRequest request,
                                     String userId, String userNm);

    /**
     * 변경 이력 최근 size 건 (GET /api/analysis-weights/histories).
     *
     * <p>🔴 {@code anlsTypeCd} 가 있으면 <b>거른 뒤에 size 건을 자른다</b> —
     * 자른 뒤 거르면 한 유형만 많이 저장했을 때 다른 유형 이력이 사라진다. 없는 유형 코드는
     * 예외가 아니라 <b>빈 목록</b>이다(화면은 "변경 이력이 없습니다").
     *
     * @param size       최대 건수 (컨트롤러가 1~100 으로 검증한다)
     * @param anlsTypeCd 분석유형코드. {@code null} 이면 전 유형(종전 동작)
     */
    List<AnalysisWeightHstryVO> selectHistories(int size, String anlsTypeCd);

    /**
     * 점수 산출용 <b>재정규화된</b> 가중치. API 로 노출하지 않는 내부 계약이다.
     * use_yn='N' 지표를 뺀 뒤 남은 합계로 나눠 다시 100 을 맞춘다.
     * 예) HOTSPOT 민원 60 · 단속 40 에서 단속이 'N' 이면 → 민원 100.
     * 남은 지표가 0개이거나 합계가 0 이면 빈 목록을 돌려준다(호출 측이 0건 응답으로 처리).
     *
     * <p>저장 경로({@link #saveWeights}) 는 이 재정규화를 거치지 않는다 — 원값 그대로 저장한다.
     */
    List<AnalysisWeightVO> selectEffectiveWeights(String anlsTypeCd);

    /**
     * 계수({@code vrbl_se_cd='CFC'}) 1건의 설정값.
     * {@link #selectEffectiveWeights(String)} 와 같은 <b>API 미노출 내부 계약</b>이다 —
     * 컨트롤러에 엔드포인트를 내지 않는다(화면은 {@code GET /api/analysis-weights} 로 이미 본다).
     *
     * <p>🔴 <b>행이 없거나 {@code use_yn='N'} 이면 예외가 아니라 폴백이다</b> —
     * {@code bsc_stng_vl}(기본설정값)이 있으면 그 값, 없으면 {@code 1.0} 을 돌려주고
     * <b>WARN 1건</b>을 남긴다. 설정 한 행이 빠졌다고 분석 화면 전체가 500 으로 죽으면 안 된다
     * (규약). 호출 측은 {@code null} 을 받지 않는다.
     *
     * @param anlsTypeCd 분석유형코드 (예: {@code LIFEZONE})
     * @param vrblCd     변수코드 (예: {@code MIN_CPCTY_VL})
     * @return 설정값 또는 폴백값 (절대 {@code null} 이 아니다)
     */
    BigDecimal coefficient(String anlsTypeCd, String vrblCd);
}
