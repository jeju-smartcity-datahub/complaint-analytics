package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 구역추천(공유주차제 적용) 분석 서비스 인터페이스 (TSK-4208 Step 3).
 *
 * <p>이 화면이 답하는 질문은 <b>"주차 민원 기준으로, 공유주차제를 검토해 볼 만한 읍면동은 어디인가"</b>
 * 다. 요금제 트랙({@link ZoneRcmdtnService})과 단위가 다르다 — 그쪽은 읍면동을 <b>고른 뒤</b> 그 안의
 * 주차장 순위를 내고, 이쪽은 기간만 받아 <b>읍면동 목록</b>을 낸다(지역은 필터다).
 *
 * <p>🔴 <b>기준값은 요청 파라미터가 아니라 설정이다</b>({@code RECOMMEND} 분석변수
 * {@code CVLCPT_MM_CRTR_VL}·{@code CVLCPT_RT_CRTR_VL}). 적용값은 응답 {@code crtrVls} 에 굳힌다.
 *
 * <p>⚠️ 구현({@code ZoneRcmdtnShareServiceImpl})은 {@code YYYYMMDD} 문자열을 파싱해 매퍼가 요구하는
 * {@code timestamp} 반열림 구간으로 바꾼다. 형식 오류·역전·없는 {@code emdCd} 는 SQL·적재로
 * 흘려보내지 않고 <b>400</b> 으로 끊는다.
 */
public interface ZoneRcmdtnShareService {

    /**
     * 읍면동 전체를 판정해 검토 후보 목록을 돌려준다. <b>조회만 한다</b> — 실행이력을 남기지 않는다.
     *
     * <p>🔴 판정은 <b>도 전체</b>로 한 뒤 지역 필터를 건다 — 그래서 같은 읍면동의 등급·{@code rvwScr}
     * 는 필터와 무관하게 같고, {@code smry} 도 필터와 무관한 도 전체 값이다. 순번({@code rnk})만
     * <b>조회 결과(필터 적용 후) 안에서</b> 1 부터 매긴다.
     *
     * @param bgngYmd 민원 조회 시작일 {@code YYYYMMDD} (필수)
     * @param endYmd  민원 조회 종료일 {@code YYYYMMDD} (필수 · 포함)
     * @param sggNm   시군구명 필터. {@code null}·빈 값이면 걸지 않는다
     * @param emdCd   읍면동 코드 필터. {@code null}·빈 값이면 걸지 않는다. 없는 코드면 <b>400</b>
     * @return 결과 1건. {@code excnId} 는 <b>채우지 않는다</b>({@code null}) — 실행이력 적재는
     *         Step 4 의 책임이다
     */
    ZoneRcmdtnShareResultVO selectShare(String bgngYmd, String endYmd, String sggNm, String emdCd);

    /**
     * 위와 같고 분석 계수 일시 입력을 받는다 (TSK-4325). 적용 내역은 응답 {@code vrblAjmt} 다.
     *
     * @param vrblVls 일시 입력({@code RECOMMEND_SHARE} 스코프). {@code null}·빈 Map 이면 설정값이다. 규칙 위반은 400
     */
    ZoneRcmdtnShareResultVO selectShare(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                        Map<String, BigDecimal> vrblVls);

    /**
     * 🆕 TSK-4340 Step 2 — {@link #selectShare(String, String, String, String, Map)} 와 같은 판정에 엑셀 재계산 데이터
     * {@code wbk}(표시 읍면동 원자료 · 확정 블록 건수 · 계수 4행 — D7)를 붙인다. <b>보고서 경로 전용</b>이다 —
     * 조회 GET 응답에는 싣지 않는다. 조회만 한다(실행이력을 남기지 않는다).
     */
    ZoneRcmdtnShareResultVO selectShareWithWorkbook(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                                    Map<String, BigDecimal> vrblVls);

    /**
     * {@link #selectShare} 와 같은 판정을 하고 <b>실행 1회를 이력으로 남긴다</b> (TSK-4208 Step 4).
     *
     * <p>실행 1회 = {@code TB_ANLS_EXCN} 1행({@code anls_type_cd='RECOMMEND'}) + {@code TB_ANLS_RSLT}
     * 1행이며 한 트랜잭션이다. {@code anls_param_json} 에 {@code rcmdtnTypeCd="SHARE"} 와 적용 기준값을,
     * {@code rslt_json} 에 응답의 {@code smry}·{@code zones} 를 굳힌다.
     *
     * <p>🔴 400(형식·역전·없는 {@code emdCd})으로 끊긴 요청은 <b>아무 행도 남기지 않는다</b> — 검증이
     * 적재보다 앞이다.
     *
     * @param bgngYmd 민원 조회 시작일 {@code YYYYMMDD} (필수)
     * @param endYmd  민원 조회 종료일 {@code YYYYMMDD} (필수 · 포함)
     * @param sggNm   시군구명 필터. {@code null}·빈 값이면 걸지 않는다
     * @param emdCd   읍면동 코드 필터. {@code null}·빈 값이면 걸지 않는다. 없는 코드면 <b>400</b>
     * @param excutrId 실행자 ID — 로그인 사용자 {@code user_id}. 해석·401 은 컨트롤러 책임이다(TSK-4249)
     * @return 결과 1건. {@code excnId} 가 채워져 있다
     */
    ZoneRcmdtnShareResultVO analyzeAndRecord(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                             String excutrId);

    /**
     * 위와 같고 분석 계수 일시 입력을 받는다 (TSK-4325). 적용 내역은 {@code anls_param_json.vrblAjmt} 에 남는다(D10).
     *
     * @param vrblVls 일시 입력({@code RECOMMEND_SHARE} 스코프). {@code null}·빈 Map 이면 설정값이다. 규칙 위반은 400
     */
    ZoneRcmdtnShareResultVO analyzeAndRecord(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                             String excutrId, Map<String, BigDecimal> vrblVls);
}
