package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.AnalysisTypeWeightVO;
import jeju.datahub.analysis.service.AnalysisWeightHstryVO;
import jeju.datahub.analysis.service.AnalysisWeightSaveRequest;
import jeju.datahub.analysis.service.AnalysisWeightService;
import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 분석 지표 가중치 설정 API 통합 테스트 (TSK-4020 Step 6).
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도 스레드·별도
 * 커넥션으로 처리하므로 테스트 트랜잭션이 서버 쪽 쓰기를 감싸지 못한다(롤백이 걸리지 않는다).
 * 그래서 "실제 커밋 + {@link #restoreSeed()} 수동 복원"을 쓴다
 * ({@code MenuAuthrtIntegrationTest}·{@code ReportIntegrationTest}·{@code RiskNotiIntegrationTest} 와 같은 전략).
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다 — 모든 프로파일이 PostgreSQL 이고 H2 는 없다.
 * 마이그레이션 53 + 67 + 77({@code database/migrations/sql/74-HOTSPOT-SCORE-CRTR-VL.sql} — 🔁 TSK-4321 로 삭제)의
 * 시드 <b>22행</b>(분석유형 4종 · 가중치 12행 + 계수 10행)이 적용된 상태를 전제로 한다.
 * 🆕 TSK-4037 — 마이그레이션 81({@code 81-POLICY-SIM-PARAM.sql})이 POLICY_SIM 계수 13행(CFC)을
 * 더해 분석유형 <b>5종 · 35행</b>이 됐다(하위 유형: 공유 7행 NULL · 확충 전용 6행 {@code EXPAND}).
 * 🆕 TSK-4038 — 마이그레이션 82({@code 82-POLICY-SIM-REPAIR.sql})가 POLICY_SIM 이면도로 계수 4행(CFC ·
 * {@code REPAIR})을 더해 <b>5종 · 39행</b>이 됐다(POLICY_SIM 17행 = 공유 7 · EXPAND 6 · REPAIR 4).
 * 🆕 TSK-4039 — 마이그레이션 83({@code 83-POLICY-SIM-FEE.sql})이 POLICY_SIM 요금제 계수 4행(CFC ·
 * {@code FEE})을 더해 <b>5종 · 43행</b>이 됐다(POLICY_SIM 21행 = 공유 7 · EXPAND 6 · REPAIR 4 · FEE 4).
 * 🆕 TSK-4207 — 마이그레이션 84({@code 84-RECOMMEND-VRBL-STNG.sql})가 RECOMMEND 의 자리표시자 가중치
 * 4행(WGVL)을 걷고 선별 기준 5행(CFC)을 더해 <b>5종 · 44행</b>이 됐다 — 🔴 RECOMMEND 는 가중합이
 * 아니라 산식이라 WGVL 행을 두지 않는다.
 * 🆕 TSK-4208 — 마이그레이션 85({@code 85-RECOMMEND-SHARE-VRBL-STNG.sql})가 RECOMMEND 에 공유주차제 판정 기준
 * 1행(CFC · {@code SHARE})을 더해 <b>5종 · 45행</b>이 됐다(RECOMMEND 6행 = 공통 5 · SHARE 1).
 * 🆕 TSK-4036 — 마이그레이션 87({@code 87-INVEST-VRBL-STNG.sql})이 INVEST 에 단속 가중치 1행(WGVL · {@code ENFORCE})과
 * 계수 4행(CFC — 배정범위·등급 기준점수 2종·표시 주차장 수)을 더해 <b>5종 · 50행</b>이 됐다(INVEST 9행 = WGVL 5 + CFC 4).
 * INVEST 가중치는 균등 25 × 4행이 {@code use_yn='Y'} 이고 사고이력(ACCIDENT)만 0 · {@code 'N'} 이다.
 * 🔁 TSK-4296 — 마이그레이션 95({@code 95-INVEST-ACCIDENT-VRBL.sql})이 INVEST 사고이력(ACCIDENT)을 {@code 'Y'} 로 켜고
 * 단속(ENFORCE) WGVL 행을 지워 <b>5종 · 64행</b>이 됐다(INVEST 8행 = WGVL 4 + CFC 4 · 사고 Y · 단속 행 삭제).
 * 🆕 TSK-4090 — 마이그레이션 79({@code 79-LIFEZONE-CNGST-VRBL.sql})가 LIFEZONE 에 혼잡도 설정값 10행(CFC)을 더했다.
 * 🆕 TSK-4217 — 마이그레이션 80({@code 80-ANLS-VRBL-STNG-TRGT.sql})이 LIFEZONE 의 SUPPLY·CONGESTION·CVLCPT_TO_CPCTY
 * 3행을 지우고 혼잡도 10행에 하위 유형 {@code anls_trgt_cd='CNGST'} 를 달았다 — LIFEZONE 은 이제 <b>CFC 10행뿐</b>이다.
 * 🆕 TSK-4258 D20 — 마이그레이션 91({@code 91-LIFEZONE-SPLY-VRBL.sql})이 LIFEZONE 에 수급 설정값 5행(CFC ·
 * {@code anls_trgt_cd='SPLY_DMND'})을 더해 <b>5종 · 55행</b>이 됐다(LIFEZONE 15행 = 수급 5 · 혼잡도 10).
 * 가중치(WGVL)가 {@code use_yn='Y'} 인 것은 HOTSPOT 2행(60/40)과 🆕 INVEST 4행(25 × 4 · TSK-4036)뿐이다.
 * 🔁 TSK-4296 — INVEST 의 Y 4행은 안전·민원·이용·<b>사고</b>다(사고 Y · 단속 행 삭제).
 *
 * <p>🆕 <b>하위 유형 묶음(TSK-4217)</b> — 합계 100 검사·재정규화는 {@code (anls_type_cd, anls_trgt_cd)} 묶음마다 돈다.
 * 실데이터에 "WGVL 이 두 묶음 이상인 대메뉴" 가 없어 전용 대메뉴 {@value #TST_TYPE} 를 <b>그 메서드 안에서만</b>
 * 넣고 {@code finally} 로 지운다(케이스 24·25). {@code @BeforeEach} 에 넣으면 GET 이 그 유형까지 내려 케이스 1·2 가 깨진다.
 *
 * <p>🔴 <b>저장 API 는 "요청 지표 집합 == 현재 지표 집합" 을 요구하는 전체 교체 계약이다</b>(의도된 계약이다 —
 * 완화하지 않는다). 행이 빠지면 무엇을 검증하려 했든 <b>집합 불일치 400</b> 이 먼저 걸려 케이스가 무의미해지므로
 * HOTSPOT 저장 바디는 전부 {@link #hotspotItems(int, int)} 로 만든다. 프론트도 같은 이유로
 * 응답의 변수 집합 전체를 되보낸다({@code AnalysisWeightTab.tsx} 의 payload 조립).
 *
 * <p>🔴 <b>"모든 분석유형의 합계가 100" 은 더 이상 성립하지 않는다</b> (TSK-4140 D2·D5).
 * {@code stngVlSum} 은 {@code vrbl_se_cd='WGVL'} <b>이면서</b> {@code use_yn='Y'} 인 행만 더한 값이라,
 * LIFEZONE·POLICY_SIM 은 WGVL 행이 아예 없어(CFC 뿐) 둘 다 <b>0</b> 이 정상이다 — 버그가 아니다.
 * 🔁 TSK-4282 — RECOMMEND 는 마이그레이션 94 로 요금제적용(FEE) 묶음에 WGVL 2행(70/30 · Y)이 생겨 <b>100</b> 이다.
 * 🔁 TSK-4036 — INVEST 는 마이그레이션 87 로 WGVL 4행(25 × 4)이 {@code use_yn='Y'} 가 되어 HOTSPOT 과 함께
 * <b>100</b> 이다(종전에는 전 행이 {@code use_yn='N'} 이라 0 이었다). 사고이력(ACCIDENT)은 {@code 'N'} 이라 합계에 들지 않는다.
 * 🔁 TSK-4296 — 사고 Y · 단속 행 삭제로 INVEST 는 안전·민원·이용·사고 25 × 4 = <b>100</b> 이다.
 *
 * <p>응답 바디의 {@code message} 문자열은 단언하지 않는다 — 로컬에서는 devtools 가
 * {@code server.error.include-message=always} 로 덮어써 보이지만 jar 배포에는 devtools 가 없어 사라진다.
 * 환경에 따라 깨지는 단언이 되므로 <b>상태코드만</b> 단언한다. 같은 이유로 실패가 예상되는 호출은
 * 응답 타입을 {@code String.class} 로 받는다(에러 바디를 VO 로 역직렬화하려다 터지는 것을 막는다).
 *
 * <p>🔴 {@link BigDecimal} 단언은 전부 {@code isEqualByComparingTo} 로 한다 — 컬럼이
 * {@code NUMERIC(5,2)} 라 DB 에서 온 값은 {@code 100.00}, 리터럴은 {@code 100} 이라
 * {@code isEqualTo} 는 스케일 차이로 실패한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisWeightIntegrationTest {

    private static final String WEIGHTS_URL = "/api/analysis-weights";
    private static final String HISTORIES_URL = WEIGHTS_URL + "/histories";

    /** 가중치를 {@code use_yn='Y'} 로 켜 둔 분석유형 (지표 2종 · 60/40). 🔁 TSK-4036 — INVEST 도 켜져 더 이상 유일하지 않다 */
    private static final String HOTSPOT = "HOTSPOT";
    /** 🆕 TSK-4036 — 가중치 4행(25 × 4)이 {@code use_yn='Y'} 인 투자우선순위. 계수 4행(마이그레이션 87). 🔁 TSK-4296 — 사고 Y · 단속 행 삭제 */
    private static final String INVEST = "INVEST";
    /** 구역추천. 🔁 TSK-4282 — 요금제적용(FEE) 묶음에 WGVL 2행(70/30)이 생겨 재정규화 대상이 2행이다 */
    private static final String RECOMMEND = "RECOMMEND";

    /**
     * 계수({@code vrbl_se_cd='CFC'})만 가진 분석유형 — 혼잡도 설정값 10행(TSK-4090), 가중치(WGVL) 0행.
     * 🔁 TSK-4217 — 종전 가중치 2행(SUPPLY·CONGESTION)과 환산계수(CVLCPT_TO_CPCTY)는 마이그레이션 80 이 지웠다.
     */
    private static final String LIFEZONE = "LIFEZONE";
    /** 🆕 TSK-4037 — 계수(CFC)만 가진 정책 시뮬레이션(마이그레이션 81·82). 공유 7행 NULL(공통) · 확충 전용 6행 EXPAND · 🆕 TSK-4038 이면도로 전용 4행 REPAIR · 🆕 TSK-4039 요금제 전용 4행 FEE. */
    private static final String POLICY_SIM = "POLICY_SIM";

    /** LIFEZONE 혼잡도 설정값 10행의 하위 유형 ({@code anls_trgt_cd} · 공통코드 {@code ANLS_TRGT_CD}) — TSK-4217 */
    private static final String CNGST = "CNGST";

    /** 🆕 LIFEZONE 수급 설정값 5행의 하위 유형 (TSK-4258 D20 · 마이그레이션 91). 공통코드 sort_seq 가 CNGST 보다 앞이다 */
    private static final String SPLY_DMND = "SPLY_DMND";

    /** 🆕 수급 등급 경계 1·2·4 · 최소 월평균 민원 (TSK-4258 D20). 정수·단조(BNDRY1<BNDRY2<BNDRY3<BNDRY4) 검증 대상 */
    private static final String SPLY_GRD_BNDRY1 = "SPLY_GRD_BNDRY1";
    private static final String SPLY_GRD_BNDRY2 = "SPLY_GRD_BNDRY2";
    private static final String SPLY_GRD_BNDRY4 = "SPLY_GRD_BNDRY4";
    private static final String SPLY_MIN_MNTH_AVG_CVLCPT = "SPLY_MIN_MNTH_AVG_CVLCPT";

    private static final String COMPLAINT = "COMPLAINT";
    private static final String ENFORCE = "ENFORCE";

    /** 🆕 TSK-4282 — RECOMMEND 요금제적용 하위 유형(공통코드 ZONE_RCMDTN_TYPE_CD · 이름 '요금제적용') · 마이그레이션 94 */
    private static final String FEE = "FEE";
    /** 🆕 TSK-4282 — 요금제적용 가중치 2행(WGVL · 70/30 · 범위 0~100) */
    private static final String FEE_CVLCPT_CNT_WGVL = "FEE_CVLCPT_CNT_WGVL";
    private static final String FEE_CVLCPT_RT_WGVL = "FEE_CVLCPT_RT_WGVL";
    /** 🆕 TSK-4282 — 요금제적용 제주시 월 민원 기준값(CFC · 범위 1~500). 하한 검증 대상 */
    private static final String FEE_CVLCPT_MM_CRTR_JEJU = "FEE_CVLCPT_MM_CRTR_JEJU";

    /**
     * LIFEZONE 혼잡도 계수 — 주차공유 기준값(CFC·Y, 범위 0.3~1.0). 시드와 다른 값을 함께 보내 400 뒤 롤백 여부를 가르는
     * 판별값으로 쓴다(🔁 TSK-4217 — 종전 판별값 SUPPLY 가 사라졌다).
     */
    private static final String PCSHR_CRTR_VL = "PCSHR_CRTR_VL";

    /**
     * 🆕 LIFEZONE 혼잡도 계수 — 단속 좌표 결측 보정(CFC·Y·노출, 범위 1~4). 시드와 다른 값을 함께 보내 400 뒤 롤백 여부를
     * 가르는 판별값이자 상한 초과 케이스(20) 대상이다. 🔁 TSK-4346 D10 — 종전 PCSHR_CRTR_VL·MIN_CPCTY_VL 은 숨김(expsr_yn='N')이라
     * 저장 4-0 이 요청값을 버려 판별값이 되지 못한다.
     */
    private static final String GEO_CVRG_CRCT_VL = "GEO_CVRG_CRCT_VL";

    /** LIFEZONE 혼잡도 계수 — 최소 주차면 수(CFC·Y, 범위 0~50 · 🆕 TSK-4346 숨김). 숨김 행 무시 케이스(20-0) 대상 */
    private static final String MIN_CPCTY_VL = "MIN_CPCTY_VL";

    /** 🆕 LIFEZONE 혼잡 등급 경계 1·2 (TSK-4090 · 마이그레이션 79). 단조({@code BND1<BND2<BND3<BND4}) 검증 대상 */
    private static final String CNGST_GRD_BNDRY1 = "CNGST_GRD_BNDRY1";
    private static final String CNGST_GRD_BNDRY2 = "CNGST_GRD_BNDRY2";

    /**
     * 🆕 LIFEZONE 혼잡도 설정값 10행의 시드값(= {@code bsc_stng_vl}, sort_seq 순) — TSK-4090 · 마이그레이션 79.
     * LIFEZONE 저장 바디에 <b>그대로 되보내</b> 값이 바뀌지 않게 한다 — 전체 교체 계약이라 빠지면 "누락 400" 이다.
     */
    private static final Map<String, String> LIFEZONE_CNGST_SEED = cngstSeed();

    private static Map<String, String> cngstSeed() {
        Map<String, String> seed = new LinkedHashMap<>();
        seed.put(PCSHR_CRTR_VL, "0.85");
        // 🔁 2026-10-08 — 경계 단위가 지수 → 점(100점 = 지수 1.0)
        seed.put(CNGST_GRD_BNDRY1, "88");
        seed.put(CNGST_GRD_BNDRY2, "92");
        seed.put("CNGST_GRD_BNDRY3", "96");
        seed.put("CNGST_GRD_BNDRY4", "100");
        seed.put("DSCL_MLTP", "3.0");
        seed.put("FIX_WGVL", "0.5");
        seed.put(GEO_CVRG_CRCT_VL, "1.815");
        seed.put("CVLCPT_WGVL", "1.0");
        seed.put(MIN_CPCTY_VL, "20");
        // 🆕 TSK-4258 D20 — 수급 설정값 5행(마이그레이션 91). 빠지면 LIFEZONE 저장이 전부 누락 400 이다
        seed.put(SPLY_MIN_MNTH_AVG_CVLCPT, "5");
        seed.put(SPLY_GRD_BNDRY1, "20");
        seed.put(SPLY_GRD_BNDRY2, "40");
        seed.put("SPLY_GRD_BNDRY3", "60");
        seed.put(SPLY_GRD_BNDRY4, "80");
        return seed;
    }

    /** HOTSPOT 등급 순위 비율 (TSK-4321 · 마이그레이션 101). 값을 바꾸는 검증은 케이스 20-5 다. */
    private static final String GRD_BNDRY1 = "GRD_BNDRY1";
    private static final String GRD_BNDRY2 = "GRD_BNDRY2";
    private static final String GRD_BNDRY3 = "GRD_BNDRY3";

    /**
     * 시드 변수 총 행수 = HOTSPOT 5(WGVL 2 + CFC 3) + RECOMMEND 16(WGVL 2 + CFC 14) + INVEST 8(WGVL 4 + CFC 4)
     * + LIFEZONE 15(CFC 15) + POLICY_SIM 21(CFC 21).
     * 🔁 TSK-4217 — LIFEZONE 13 → 10 (마이그레이션 80 이 SUPPLY·CONGESTION·CVLCPT_TO_CPCTY 를 지웠다).
     * 🔁 TSK-4037 — POLICY_SIM 13행 추가(마이그레이션 81) → 22 + 13 = 35.
     * 🔁 TSK-4038 — POLICY_SIM 이면도로 계수 4행 추가(마이그레이션 82) → 35 + 4 = 39.
     * 🔁 TSK-4039 — POLICY_SIM 요금제 계수 4행 추가(마이그레이션 83) → 39 + 4 = 43.
     * 🔁 TSK-4207 — RECOMMEND WGVL 4행 → CFC 5행 → 43 + 1 = 44.
     * 🔁 TSK-4208 — RECOMMEND CFC 5 → 6행(SHARE 1행) → 44 + 1 = 45.
     * 🔁 TSK-4036 — INVEST 4 → 9행(WGVL ENFORCE 1행 + CFC 4행 · 마이그레이션 87) → 45 + 5 = 50.
     * 🔁 TSK-4258 — LIFEZONE 10 → 15행(수급 CFC 5행 · 마이그레이션 91) → 50 + 5 = 55.
     * 🔁 TSK-4283 — RECOMMEND CFC 6 → 8행(SHARE 민원 추세 반영 2행 · 마이그레이션 93) → 55 + 2 = 57.
     * 🔁 TSK-4282 — RECOMMEND 8 → 16행(요금제적용 FEE 8행 = WGVL 2 + CFC 6 · 마이그레이션 94) → 57 + 8 = 65.
     * 🔁 TSK-4296 — INVEST 9 → 8행(단속 ENFORCE WGVL 1행 삭제 · 사고 ACCIDENT Y · 마이그레이션 95) → 65 − 1 = 64.
     * 🔁 TSK-4321 — HOTSPOT 월기준값 2행 삭제(마이그레이션 100) → 64 − 2 = 62.
     * 🔁 TSK-4321 — HOTSPOT 등급 비율 3행 추가(마이그레이션 101) → 62 + 3 = 65.
     * 🔴 변수를 더하는 마이그레이션을 쌓으면 여기가 먼저 깨진다 — 그때 이 주석의 내역을 함께 고친다.
     */
    private static final int SEED_VARIABLE_COUNT = 65;

    /**
     * 🆕 묶음별 합계·재정규화 전용 대메뉴 (TSK-4217 Step 5). 실데이터에 WGVL 이 두 묶음 이상인 대메뉴가 없어 만든다.
     * 🔴 행·공통코드는 <b>그 테스트 메서드 안에서만</b> 넣고 {@code finally} 로 지운다 — 남은 것은
     * {@link #resetVariableSeed()} 가 치운다.
     */
    private static final String TST_TYPE = "TST_4217";
    private static final String TST_TRGT_A = "TST_A";
    private static final String TST_TRGT_B = "TST_B";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 재정규화({@code selectEffectiveWeights})는 API 로 노출되지 않는 내부 계약이라(TSK-4020 D10)
     * 서비스를 직접 주입해 호출한다.
     */
    @Autowired
    private AnalysisWeightService analysisWeightService;

    /**
     * 🔴 TSK-4115 Step 7 — {@code PUT /api/analysis-weights/{anlsTypeCd}} 가 {@code M0603} 매핑을
     * 요구하게 됐다({@code @PreAuthorize}). 메서드 보안은 test 체인의 {@code permitAll} 과 무관하게
     * 익명 호출을 403 으로 막으므로, 이 클래스의 <b>모든 쓰기 호출이 세션을 타야</b> 한다.
     * 조회는 종전대로 익명이어도 된다(필터체인이 permitAll 이고 쓰기 API 만 잠갔다).
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void loginWithMenuGrant() {
        // 🔴 테스트 '전' 에도 되돌린다 (TSK-4140 Step 6). @AfterEach 만으로는 <b>이전 실행·수동 검증이
        //    남긴 상태</b>를 치우지 못한다 — 실제로 Step 3·4 검증이 남긴 mdfr_id 때문에 케이스 5
        //    (`mdfr_id IS NOT NULL count == 0`)가 먼저 도는 순간 거짓 실패했다.
        //    ⚠️ 별도 @BeforeEach 로 빼지 않는다 — @BeforeEach 끼리는 실행 순서가 보장되지 않아
        //       auth 초기화와 경쟁한다.
        resetVariableSeed();

        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        // ① mock-login (ROLE_USER) → ② ROLE_USER 에 M0603 부여. 재로그인은 없다 — 표 파생 판정이
        //    매 요청 TB_AUTHRT_MENU 를 조회하므로 다음 요청이 곧바로 새 매핑을 본다.
        session = auth.mockSessionWithMenus("M0603");
    }

    /**
     * 설정값·이력을 시드 상태로 되돌린다. 케이스 1·3·13 이 HOTSPOT 60/40 시작을 전제한다.
     *
     * <p>복원 원천은 <b>{@code bsc_stng_vl}</b>(불변 기본값)다 — 시드 값을 테스트에 다시 적어 두면
     * 마이그레이션 시드가 바뀔 때 복원이 조용히 어긋난다.
     *
     * <p>🔴 <b>"직전 현재값 복원" 으로 바꾸지 않는다</b> (TSK-4140 Step 6). 케이스 1 이 리터럴
     * {@code 60}/{@code 40} 을 단언하므로, DB 가 드리프트한 상태에서 시작하면 그 순간 깨진다.
     * 대가는 <b>계수 {@code k} 도 기본값(1.0)으로 되돌아간다</b>는 것 — 손으로 바꿔 둔 값이 있으면
     * {@code ./gradlew test} 한 번에 사라진다.
     */
    @AfterEach
    void restoreSeed() {
        // TSK-4115 Step 7-1 — 헬퍼가 넣은 매핑·mock 계정·로그인 이력을 함께 되돌린다
        auth.cleanUp();
        resetVariableSeed();
    }

    /**
     * 설정값·수정자·이력을 시드 상태로 되돌리는 실제 문장. {@link #loginWithMenuGrant()} 와 공유한다.
     * 🆕 TSK-4217 — 케이스 24·25 가 실패·중단으로 남긴 전용 대메뉴 행·공통코드도 여기서 치운다.
     */
    private void resetVariableSeed() {
        jdbcTemplate.update("DELETE FROM parking.tb_anls_vrbl_hstry");
        jdbcTemplate.update("DELETE FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ?", TST_TYPE);
        jdbcTemplate.update("DELETE FROM parking.tb_com_cd WHERE group_cd = 'ANLS_TYPE_CD' AND cd_vl = ?", TST_TYPE);
        jdbcTemplate.update(
                "UPDATE parking.tb_anls_vrbl_stng SET stng_vl = bsc_stng_vl, mdfr_id = NULL, mdfcn_dt = NULL");
    }

    // ---------------------------------------------------------------- 조회

    @Test
    @DisplayName("1. GET /api/analysis-weights — 분석유형 5종 · 변수 65건 · 합계 100 은 HOTSPOT·INVEST·RECOMMEND · 하위 유형·묶음 합계")
    void selectWeights() {
        List<AnalysisTypeWeightVO> types = getWeights();

        // 공통코드 ANLS_TYPE_CD 는 7종이지만 가중치 행이 있는 5종만 내려온다 (매퍼의 EXISTS)
        // 🔁 TSK-4037 — POLICY_SIM 계수 13행 등재로 4 → 5종
        assertThat(types).hasSize(5);
        assertThat(types).extracting(AnalysisTypeWeightVO::getAnlsTypeCd)
                .containsExactlyInAnyOrder(HOTSPOT, RECOMMEND, INVEST, "LIFEZONE", POLICY_SIM);
        assertThat(types).allSatisfy(type -> assertThat(type.getAnlsTypeNm()).isNotBlank());

        // 변수 총 50건 = 가중치 7 + 계수 43. 계수는 HOTSPOT 월기준값 2행(TSK-4196 · 마이그레이션 77) +
        // LIFEZONE 혼잡도 설정값 10행(TSK-4090 · 마이그레이션 79) + POLICY_SIM 21행(TSK-4037 · 마이그레이션 81 13행
        // + TSK-4038 · 마이그레이션 82 4행 + TSK-4039 · 마이그레이션 83 4행)이다.
        // 🔁 TSK-4217 — 환산계수·LIFEZONE 가중치 2행 삭제.
        // 🔁 TSK-4208 — RECOMMEND 공유주차제 판정 기준 1행(SHARE · 마이그레이션 85) 추가로 계수 38 → 39.
        // 🔁 TSK-4036 — INVEST 단속 가중치 1행 + 계수 4행(마이그레이션 87) 추가로 가중치 6 → 7 · 계수 39 → 43.
        // 🔁 TSK-4258 — LIFEZONE 수급 설정값 5행(마이그레이션 91) 추가로 계수 43 → 48.
        // 🔁 TSK-4283 — RECOMMEND SHARE 민원 추세 반영 2행(마이그레이션 93) 추가로 계수 48 → 50.
        // 🔁 TSK-4282 — RECOMMEND 요금제적용(FEE) 8행(마이그레이션 94) 추가로 가중치 7 → 9 · 계수 50 → 56.
        // 🔁 TSK-4296 — INVEST 단속 가중치 1행 삭제(마이그레이션 95 · 사고 Y)로 가중치 9 → 8.
        // 🔁 TSK-4321 — HOTSPOT 월기준값 2행 삭제(마이그레이션 100)로 계수 56 → 54. 등급 비율 3행 추가(마이그레이션 101)로 → 57.
        int totalVariables = types.stream().mapToInt(type -> type.getWeights().size()).sum();
        assertThat(totalVariables).as("가중치 8행 + 계수 57행 (%s)", "HOTSPOT 5 · RECOMMEND 16 · INVEST 8 · LIFEZONE 15 · POLICY_SIM 21")
                .isEqualTo(SEED_VARIABLE_COUNT);

        // 목록 자체는 어떤 분석유형도 비지 않는다 (화면이 '미사용' 태그로 그린다)
        assertThat(types).allSatisfy(type -> assertThat(type.getWeights()).isNotEmpty());

        // 🔴 합계 100 은 HOTSPOT·INVEST·RECOMMEND 셋이다 — stngVlSum 이 WGVL·use_yn='Y' 한정이라(D2·D5)
        //    WGVL 행이 아예 없는 나머지 둘(LIFEZONE·POLICY_SIM)은 0 이다.
        //    🔁 TSK-4036 — INVEST 는 WGVL·Y 4행 × 25 = 100 이다(사고이력은 use_yn='N' 이라 빠진다).
        //    🔁 TSK-4296 — 사고 Y · 단속 행 삭제 — INVEST 는 안전·민원·이용·사고 4행 × 25 = 100 그대로다.
        //    🔁 TSK-4282 — RECOMMEND 는 요금제적용(FEE) WGVL·Y 2행 70 + 30 = 100 이다(CFC 14행은 섞이지 않는다).
        //    "모든 유형이 100" 으로 되돌리면 계수(CFC)가 합계에 섞이는 구현을 이 테스트가 통과시켜 버린다.
        for (AnalysisTypeWeightVO type : types) {
            BigDecimal expected = HOTSPOT.equals(type.getAnlsTypeCd()) || INVEST.equals(type.getAnlsTypeCd())
                    || RECOMMEND.equals(type.getAnlsTypeCd())
                    ? new BigDecimal("100")
                    : BigDecimal.ZERO;
            assertThat(type.getStngVlSum())
                    .as("%s 의 stngVlSum (WGVL·use_yn='Y' 행만 합산)", type.getAnlsTypeCd())
                    .isEqualByComparingTo(expected);

            // 합계 필드가 '같은 필터로 고른' 실제 행 합과 일치하는지도 함께 본다
            BigDecimal sum = type.getWeights().stream()
                    .filter(w -> "WGVL".equals(w.getVrblSeCd()))
                    .filter(w -> "Y".equals(w.getUseYn()))
                    .map(AnalysisWeightVO::getStngVl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).as("%s 의 WGVL·Y 행 합", type.getAnlsTypeCd()).isEqualByComparingTo(expected);
        }

        // 🔴 계수(CFC)는 합계에 섞이지 않는다 — LIFEZONE 은 혼잡도 계수가 use_yn='Y' 인데도 합계가 0 이다
        AnalysisTypeWeightVO lifezone = findType(types, LIFEZONE);
        assertThat(findWeight(lifezone, PCSHR_CRTR_VL).getVrblSeCd()).isEqualTo("CFC");
        assertThat(findWeight(lifezone, PCSHR_CRTR_VL).getUseYn()).isEqualTo("Y");
        // 🆕 TSK-4346 — 숨김 행도 GET 에 내려가고 expsrYn 으로 구분된다(null 이면 매퍼 SELECT 누락)
        assertThat(findWeight(lifezone, PCSHR_CRTR_VL).getExpsrYn()).isEqualTo("N");
        assertThat(findWeight(lifezone, GEO_CVRG_CRCT_VL).getExpsrYn()).isEqualTo("Y");
        assertThat(types).flatExtracting(AnalysisTypeWeightVO::getWeights)
                .extracting(AnalysisWeightVO::getExpsrYn).doesNotContainNull();
        assertThat(lifezone.getStngVlSum())
                .as("계수가 켜져 있어도 합계는 가중치만 본다")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // 🆕 TSK-4217 — 하위 유형. LIFEZONE 은 수급(SPLY_DMND · 🆕 TSK-4258) + 혼잡도(CNGST), 나머지 대메뉴는 전 행이 공통(null)이다.
        assertThat(lifezone.getWeights()).extracting(AnalysisWeightVO::getAnlsTrgtCd).containsOnly(SPLY_DMND, CNGST);
        assertThat(lifezone.getWeights().stream().filter(w -> SPLY_DMND.equals(w.getAnlsTrgtCd())).count())
                .as("LIFEZONE 수급 설정값(SPLY_DMND) 행 수").isEqualTo(5);
        assertThat(lifezone.getWeights())
                .as("SPLY_DMND·CNGST 는 공통코드 ANLS_TRGT_CD 에 등재돼 있어 이름이 채워진다")
                .allSatisfy(w -> assertThat(w.getAnlsTrgtNm()).isNotBlank());
        for (AnalysisTypeWeightVO type : types) {
            if (LIFEZONE.equals(type.getAnlsTypeCd()) || POLICY_SIM.equals(type.getAnlsTypeCd())
                    || RECOMMEND.equals(type.getAnlsTypeCd())) {
                // POLICY_SIM 은 공통 + EXPAND + REPAIR + FEE 네 묶음, RECOMMEND 는 공통 + FEE + SHARE 세 묶음이다 — 아래에서 따로 본다
                continue;
            }
            assertThat(type.getWeights()).as("%s 의 anlsTrgtCd (대메뉴 공통)", type.getAnlsTypeCd())
                    .extracting(AnalysisWeightVO::getAnlsTrgtCd).containsOnlyNulls();
            assertThat(type.getWeights()).extracting(AnalysisWeightVO::getAnlsTrgtNm).containsOnlyNulls();
        }

        // 🆕 TSK-4217 — 묶음별 합계(trgtSums). 지금 데이터는 모든 대메뉴가 1묶음이다.
        AnalysisTypeWeightVO hotspotType = findType(types, HOTSPOT);
        assertThat(hotspotType.getTrgtSums()).hasSize(1);
        assertThat(hotspotType.getTrgtSums().get(0).getAnlsTrgtCd()).isNull();
        assertThat(hotspotType.getTrgtSums().get(0).getWgvlYn()).isEqualTo("Y");
        assertThat(hotspotType.getTrgtSums().get(0).getStngVlSum()).isEqualByComparingTo(new BigDecimal("100"));

        // 🔁 TSK-4258 — 두 묶음(수급 → 혼잡도 · 공통코드 ANLS_TRGT_CD sort_seq 순)
        assertThat(lifezone.getTrgtSums()).hasSize(2);
        assertThat(lifezone.getTrgtSums()).extracting(t -> t.getAnlsTrgtCd()).containsExactly(SPLY_DMND, CNGST);
        assertThat(lifezone.getTrgtSums()).allSatisfy(t -> {
            assertThat(t.getAnlsTrgtNm()).isNotBlank();
            assertThat(t.getWgvlYn()).as("계수만 있는 묶음은 합계 배지 대상이 아니다").isEqualTo("N");
            assertThat(t.getStngVlSum()).isEqualByComparingTo(BigDecimal.ZERO);
        });

        // 🆕 TSK-4037 — POLICY_SIM 은 계수(CFC) 13행 = 공통(null) 7행 + 확충 전용 EXPAND 6행이다(TSK-4217 D2 = B).
        // 🆕 TSK-4038 — 이면도로 전용 REPAIR 4행이 더해져 17행 · 세 묶음이다(묶음 순서 = 공통 → 공통코드 PLCY_TYPE_CD sort_seq).
        // 🆕 TSK-4039 — 요금제 전용 FEE 4행이 더해져 21행 · 네 묶음이다(FEE 는 PLCY_TYPE_CD sort_seq 3 이라 마지막).
        //    WGVL 0행이라 네 묶음 모두 합계 배지 대상이 아니다. EXPAND·REPAIR·FEE 이름은 공통코드 PLCY_TYPE_CD 에서 온다.
        AnalysisTypeWeightVO policySim = findType(types, POLICY_SIM);
        assertThat(policySim.getWeights()).hasSize(21);
        assertThat(policySim.getWeights()).extracting(AnalysisWeightVO::getVrblSeCd).containsOnly("CFC");
        assertThat(policySim.getWeights().stream().filter(w -> w.getAnlsTrgtCd() == null).count())
                .as("POLICY_SIM 공유 계수(공통) 행 수").isEqualTo(7);
        assertThat(policySim.getWeights().stream().filter(w -> "EXPAND".equals(w.getAnlsTrgtCd())).count())
                .as("POLICY_SIM 확충 전용(EXPAND) 행 수").isEqualTo(6);
        assertThat(policySim.getWeights().stream().filter(w -> "REPAIR".equals(w.getAnlsTrgtCd())).count())
                .as("POLICY_SIM 이면도로 전용(REPAIR) 행 수").isEqualTo(4);
        assertThat(policySim.getWeights().stream().filter(w -> "FEE".equals(w.getAnlsTrgtCd())).count())
                .as("POLICY_SIM 요금제 전용(FEE) 행 수").isEqualTo(4);
        assertThat(policySim.getTrgtSums()).hasSize(4);
        assertThat(policySim.getTrgtSums().get(0).getAnlsTrgtCd()).as("공통 묶음이 먼저").isNull();
        assertThat(policySim.getTrgtSums().get(1).getAnlsTrgtCd()).isEqualTo("EXPAND");
        assertThat(policySim.getTrgtSums().get(1).getAnlsTrgtNm())
                .as("EXPAND 는 공통코드 PLCY_TYPE_CD 에 등재돼 있어 이름이 채워진다").isNotBlank();
        assertThat(policySim.getTrgtSums().get(2).getAnlsTrgtCd()).isEqualTo("REPAIR");
        assertThat(policySim.getTrgtSums().get(2).getAnlsTrgtNm())
                .as("REPAIR 는 공통코드 PLCY_TYPE_CD 에 등재돼 있어 이름이 채워진다").isNotBlank();
        assertThat(policySim.getTrgtSums().get(3).getAnlsTrgtCd()).as("FEE 묶음이 마지막(PLCY_TYPE_CD sort_seq 3)").isEqualTo("FEE");
        assertThat(policySim.getTrgtSums().get(3).getAnlsTrgtNm())
                .as("FEE 는 공통코드 PLCY_TYPE_CD 에 '요금제적용' 으로 등재돼 있다").isEqualTo("요금제적용");
        assertThat(policySim.getTrgtSums()).allSatisfy(t -> {
            assertThat(t.getWgvlYn()).isEqualTo("N");
            assertThat(t.getStngVlSum()).isEqualByComparingTo(BigDecimal.ZERO);
        });

        // 🆕 TSK-4208 — RECOMMEND 공통(null) 5행 + 공유주차제 전용 SHARE 3행(마이그레이션 85 1행 ·
        //    🔁 TSK-4283 마이그레이션 93 민원 추세 반영 2행).
        // 🔁 TSK-4282 — 요금제적용 전용 FEE 8행(WGVL 2 + CFC 6 · 마이그레이션 94)이 더해져 16행 = CFC 14 + WGVL 2 다.
        //    세 묶음(공통 → FEE → SHARE · 공통코드 ZONE_RCMDTN_TYPE_CD sort_seq 순)이고 FEE 만 합계 배지 대상이다.
        //    FEE·SHARE 이름은 공통코드 ZONE_RCMDTN_TYPE_CD 에서 온다(AnalysisWeightServiceImpl.TRGT_CD_GROUPS).
        AnalysisTypeWeightVO recommend = findType(types, RECOMMEND);
        assertThat(recommend.getWeights()).hasSize(16);
        assertThat(recommend.getWeights()).extracting(AnalysisWeightVO::getVrblSeCd).containsOnly("WGVL", "CFC");
        assertThat(recommend.getWeights().stream().filter(w -> "WGVL".equals(w.getVrblSeCd())))
                .as("RECOMMEND 의 WGVL 은 요금제적용(FEE) 2행뿐")
                .extracting(AnalysisWeightVO::getVrblCd, AnalysisWeightVO::getAnlsTrgtCd)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(FEE_CVLCPT_CNT_WGVL, FEE),
                        org.assertj.core.groups.Tuple.tuple(FEE_CVLCPT_RT_WGVL, FEE));
        assertThat(recommend.getWeights().stream().filter(w -> FEE.equals(w.getAnlsTrgtCd())).count())
                .as("RECOMMEND 요금제적용 전용(FEE) 행 수").isEqualTo(8);
        assertThat(recommend.getWeights().stream().filter(w -> w.getAnlsTrgtCd() == null).count())
                .as("RECOMMEND 선별 기준(공통) 행 수").isEqualTo(5);
        assertThat(recommend.getWeights().stream().filter(w -> "SHARE".equals(w.getAnlsTrgtCd())).count())
                .as("RECOMMEND 공유주차제 전용(SHARE) 행 수").isEqualTo(3);
        assertThat(findWeight(recommend, "CVLCPT_RT_CRTR_VL").getAnlsTrgtCd())
                .as("SHARE 1행은 민원비율기준값이다").isEqualTo("SHARE");
        assertThat(findWeight(recommend, "CVLCPT_RT_CRTR_VL").getAnlsTrgtNm())
                .as("SHARE 는 공통코드 ZONE_RCMDTN_TYPE_CD 에 '공유주차제' 로 등재돼 있다").isEqualTo("공유주차제");
        assertThat(recommend.getTrgtSums()).hasSize(3);
        assertThat(recommend.getTrgtSums().get(0).getAnlsTrgtCd()).as("공통 묶음이 먼저").isNull();
        assertThat(recommend.getTrgtSums().get(0).getWgvlYn()).isEqualTo("N");
        assertThat(recommend.getTrgtSums().get(0).getStngVlSum()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(recommend.getTrgtSums().get(1).getAnlsTrgtCd()).as("FEE 묶음(ZONE_RCMDTN_TYPE_CD sort_seq 1)").isEqualTo(FEE);
        assertThat(recommend.getTrgtSums().get(1).getAnlsTrgtNm())
                .as("FEE 는 공통코드 ZONE_RCMDTN_TYPE_CD 에 '요금제적용' 으로 등재돼 있다").isEqualTo("요금제적용");
        assertThat(recommend.getTrgtSums().get(1).getWgvlYn()).as("FEE 만 합계 배지 대상").isEqualTo("Y");
        assertThat(recommend.getTrgtSums().get(1).getStngVlSum()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(recommend.getTrgtSums().get(2).getAnlsTrgtCd()).isEqualTo("SHARE");
        assertThat(recommend.getTrgtSums().get(2).getAnlsTrgtNm()).isEqualTo("공유주차제");
        assertThat(recommend.getTrgtSums().get(2).getWgvlYn()).isEqualTo("N");
        assertThat(recommend.getTrgtSums().get(2).getStngVlSum()).isEqualByComparingTo(BigDecimal.ZERO);

        // HOTSPOT 은 sort_seq 순으로 민원빈도(1) → 단속건수(2) → 등급 비율(11~13). 🔁 TSK-4321.
        AnalysisTypeWeightVO hotspot = findType(types, HOTSPOT);
        assertThat(hotspot.getWeights()).extracting(AnalysisWeightVO::getVrblCd)
                .containsExactly(COMPLAINT, ENFORCE, GRD_BNDRY1, GRD_BNDRY2, GRD_BNDRY3);
        assertThat(findWeight(hotspot, COMPLAINT).getStngVl()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(findWeight(hotspot, ENFORCE).getStngVl()).isEqualByComparingTo(new BigDecimal("40"));
    }

    @Test
    @DisplayName("2. GET — 응답에 bscStngVl 가 포함되고 시드값(DB의 bsc_stng_vl)과 일치한다")
    void responseCarriesBscStngVl() {
        List<AnalysisTypeWeightVO> types = getWeights();

        // 시드 값을 테스트에 다시 적지 않고 DB 의 bsc_stng_vl 를 원천으로 대조한다
        Map<String, BigDecimal> seed = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT anls_type_cd, vrbl_cd, bsc_stng_vl FROM parking.tb_anls_vrbl_stng")) {
            seed.put(row.get("anls_type_cd") + "|" + row.get("vrbl_cd"), (BigDecimal) row.get("bsc_stng_vl"));
        }
        assertThat(seed).as("가중치 8행 + 계수 56행 (TSK-4196 + TSK-4090 − TSK-4217 3행 + TSK-4037 13행 + TSK-4038 4행 + TSK-4039 4행 + TSK-4207 RECOMMEND −4 WGVL +5 CFC + TSK-4208 RECOMMEND SHARE 1행 + TSK-4036 INVEST WGVL 1행 · CFC 4행 + TSK-4258 LIFEZONE 수급 5행 + TSK-4283 RECOMMEND SHARE 추세 2행 + TSK-4282 RECOMMEND FEE 8행 + TSK-4296 INVEST ENFORCE −1)").hasSize(SEED_VARIABLE_COUNT);

        for (AnalysisTypeWeightVO type : types) {
            for (AnalysisWeightVO weight : type.getWeights()) {
                BigDecimal expected = seed.get(type.getAnlsTypeCd() + "|" + weight.getVrblCd());
                assertThat(expected).as("시드에 없는 지표: %s / %s", type.getAnlsTypeCd(), weight.getVrblCd())
                        .isNotNull();
                assertThat(weight.getBscStngVl())
                        .as("bscStngVl: %s / %s", type.getAnlsTypeCd(), weight.getVrblCd())
                        .isNotNull()
                        .isEqualByComparingTo(expected);
            }
        }

        // 시드 미변경 상태에서는 현재값 == 기본값이다
        AnalysisTypeWeightVO hotspot = findType(types, HOTSPOT);
        assertThat(findWeight(hotspot, COMPLAINT).getBscStngVl()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(findWeight(hotspot, ENFORCE).getBscStngVl()).isEqualByComparingTo(new BigDecimal("40"));
    }

    // ---------------------------------------------------------------- 저장

    @Test
    @DisplayName("3. PUT 정상 저장 (60/40 → 55/45) — 200 · 응답 합계 100 · 이력 1행 증가")
    void saveWeightsSucceeds() {
        int before = historyCount();

        ResponseEntity<AnalysisTypeWeightVO> saved = putWeights(HOTSPOT,
                body(hotspotItems(55, 45), "단속 실적 반영 비중 상향"),
                AnalysisTypeWeightVO.class);

        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        AnalysisTypeWeightVO type = saved.getBody();
        assertThat(type).isNotNull();
        assertThat(type.getAnlsTypeCd()).isEqualTo(HOTSPOT);
        assertThat(type.getStngVlSum()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(findWeight(type, COMPLAINT).getStngVl()).isEqualByComparingTo(new BigDecimal("55"));
        assertThat(findWeight(type, ENFORCE).getStngVl()).isEqualByComparingTo(new BigDecimal("45"));

        // 트랜잭션 밖 재조회(새 요청)에서도 같아야 한다 — JPA flush 누락 회귀 방지
        AnalysisTypeWeightVO refetched = findType(getWeights(), HOTSPOT);
        assertThat(findWeight(refetched, COMPLAINT).getStngVl()).isEqualByComparingTo(new BigDecimal("55"));
        assertThat(findWeight(refetched, ENFORCE).getStngVl()).isEqualByComparingTo(new BigDecimal("45"));

        // DB 에도 실제로 커밋됐고 수정자가 기록됐다
        assertThat(stngVlInDb(HOTSPOT, COMPLAINT)).isEqualByComparingTo(new BigDecimal("55"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mdfr_id FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND vrbl_cd = ?",
                String.class, HOTSPOT, COMPLAINT)).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        // 이력의 변경자도 로그인 사용자다 (고정값 'system'/'시스템' 회귀 방지)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chnrg_id FROM parking.tb_anls_vrbl_hstry ORDER BY chg_hstry_sn DESC LIMIT 1",
                String.class)).isEqualTo(IntegrationLoginSupport.MOCK_ID);

        assertThat(historyCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("4. PUT 저장 후 bsc_stng_vl 는 불변 — 60/40 유지 (D3 회귀 방지선)")
    void saveDoesNotTouchBscStngVl() {
        putWeights(HOTSPOT, body(hotspotItems(55, 45), null), AnalysisTypeWeightVO.class);

        // DB 원본 — updatable=false 가 풀리면 여기서 55/45 가 되어 '기본값으로 초기화' 가 영구히 망가진다
        assertThat(bscStngVlInDb(HOTSPOT, COMPLAINT)).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(bscStngVlInDb(HOTSPOT, ENFORCE)).isEqualByComparingTo(new BigDecimal("40"));

        // API 응답에서도 기본값은 그대로다 (현재값만 바뀐다)
        AnalysisTypeWeightVO refetched = findType(getWeights(), HOTSPOT);
        assertThat(findWeight(refetched, COMPLAINT).getBscStngVl()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(findWeight(refetched, ENFORCE).getBscStngVl()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(findWeight(refetched, COMPLAINT).getStngVl()).isEqualByComparingTo(new BigDecimal("55"));
    }

    @Test
    @DisplayName("5. PUT 같은 값 재저장 — 200 · 이력 증가 없음 (BigDecimal.equals 회귀 방지)")
    void resavingSameValuesDoesNotAppendHistory() {
        int before = historyCount();

        // 시드와 같은 60/40 을 그대로 다시 저장한다. DB 값은 60.00, 요청은 60 이라
        // 비교를 equals 로 짜면 '변경 있음' 으로 오판해 이력이 쌓인다.
        ResponseEntity<AnalysisTypeWeightVO> saved = putWeights(HOTSPOT,
                body(hotspotItems(60, 40), null), AnalysisTypeWeightVO.class);

        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getBody()).isNotNull();
        assertThat(saved.getBody().getStngVlSum()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(historyCount()).isEqualTo(before);

        // 변경이 없으므로 수정자·수정일시도 찍히지 않는다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND mdfr_id IS NOT NULL",
                Integer.class, HOTSPOT)).isZero();
    }

    // ---------------------------------------------------------------- 검증 실패

    @Test
    @DisplayName("6. PUT 합계 ≠ 100 — 400 · 기존 값 보존")
    void saveWithWrongSumReturns400() {
        // 🔴 계수 2행을 함께 보낸다 — 빼면 '합계 90' 이 아니라 '집합 불일치' 로 400 이 되어
        //    이 케이스가 검증하려던 것을 더 이상 검증하지 않는다(조용히 무의미해진다).
        ResponseEntity<String> response = putWeights(HOTSPOT,
                body(hotspotItems(60, 30), null), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertUnchangedHotspotSeed();
    }

    @Test
    @DisplayName("7. PUT 없는 지표코드 포함 — 400")
    void saveWithUnknownIndicatorReturns400() {
        ResponseEntity<String> response = putWeights(HOTSPOT,
                body(List.of(item(COMPLAINT, 60), item("NOT_EXISTS", 40)), null), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertUnchangedHotspotSeed();
    }

    @Test
    @DisplayName("8. PUT 지표 일부 누락 — 400 (전체 교체 저장이므로 전부 보내야 한다)")
    void saveWithMissingIndicatorReturns400() {
        ResponseEntity<String> response = putWeights(HOTSPOT,
                body(List.of(item(COMPLAINT, 100)), null), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertUnchangedHotspotSeed();
    }

    @Test
    @DisplayName("9. PUT 같은 지표코드 중복 — 400 (중복 검사가 누락 검사보다 앞선다)")
    void saveWithDuplicatedIndicatorReturns400() {
        ResponseEntity<String> response = putWeights(HOTSPOT,
                body(List.of(item(COMPLAINT, 60), item(COMPLAINT, 40)), null), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertUnchangedHotspotSeed();
    }

    @Test
    @DisplayName("10. PUT 없는 분석유형 — 404")
    void saveUnknownAnalysisTypeReturns404() {
        ResponseEntity<String> response = putWeights("NOT_EXISTS",
                body(List.of(item(COMPLAINT, 100)), null), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("11. PUT 바디 누락 {} — 400 (@Valid 회귀 방지, NPE 아님)")
    void saveWithEmptyBodyReturns400() {
        ResponseEntity<String> response = putWeights(HOTSPOT, Map.of(), String.class);

        // @Valid 가 빠지면 weights 가 null 인 채 서비스로 들어가 NPE → 500 이 된다
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertUnchangedHotspotSeed();
    }

    @Test
    @DisplayName("12. GET /histories?size=999 — 400 (최대 100)")
    void historiesWithTooLargeSizeReturns400() {
        ResponseEntity<String> response = restTemplate.exchange(
                HISTORIES_URL + "?size=999", HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- 이력

    @Test
    @DisplayName("13. 이력의 chgCn — '민원빈도 60 → 55, 단속건수 40 → 45' (스케일 정규화 포함)")
    void historyChgCnSentence() {
        putWeights(HOTSPOT, body(hotspotItems(55, 45), "단속 실적 반영 비중 상향"),
                AnalysisTypeWeightVO.class);

        List<AnalysisWeightHstryVO> histories = getHistories(20);
        assertThat(histories).hasSize(1);

        AnalysisWeightHstryVO history = histories.get(0);
        // fmt() 가 빠지면 '민원빈도 60.00 → 55' 처럼 이전/이후 표기가 어긋난다
        assertThat(history.getChgCn()).isEqualTo("민원빈도 60 → 55, 단속건수 40 → 45");
        assertThat(history.getAnlsTypeCd()).isEqualTo(HOTSPOT);
        assertThat(history.getChgArtclNm()).isNotBlank();
        assertThat(history.getChgRsn()).isEqualTo("단속 실적 반영 비중 상향");
        assertThat(history.getChnrgNm()).isEqualTo("mock 사용자");   // MockAuthController.MOCK_NM — 로그인 사용자가 변경자다
        assertThat(history.getChgHstrySn()).isNotNull();
        assertThat(history.getChgDt()).isNotNull();
    }

    @Test
    @DisplayName("14. 이력의 bfr/aftr_vrbl_json — 새 키(vrblCd·anlsTrgtCd·vrblNm·stngVl)로 적재된다 (TSK-4140 D6 · TSK-4217)")
    void historySnapshotJsonColumns() throws Exception {
        putWeights(HOTSPOT, body(hotspotItems(55, 45), null), AnalysisTypeWeightVO.class);

        // ⚠️ 이 두 컬럼은 감사용이라 AnalysisWeightHstryVO 에도 selectHistories 조회 컬럼에도 없다.
        //    API 응답으로 단언하면 반드시 실패하므로 JdbcTemplate 으로 DB 를 직접 읽는다.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT bfr_vrbl_json::text AS bfr, aftr_vrbl_json::text AS aftr"
                        + "  FROM parking.tb_anls_vrbl_hstry ORDER BY chg_hstry_sn DESC LIMIT 1");

        List<Map<String, Object>> bfr = parseSnapshot((String) row.get("bfr"));
        List<Map<String, Object>> aftr = parseSnapshot((String) row.get("aftr"));

        // 🔴 스냅샷은 '바뀐 행' 이 아니라 그 분석유형의 <b>전 행</b>이다 (HOTSPOT 은 가중치 2 + 등급 비율 3행이다).
        assertThat(bfr).hasSize(5);
        assertThat(aftr).hasSize(5);
        assertThat(bfr).extracting(item -> item.get("vrblCd"))
                .containsExactly(COMPLAINT, ENFORCE, GRD_BNDRY1, GRD_BNDRY2, GRD_BNDRY3);
        assertThat(aftr).extracting(item -> item.get("vrblCd"))
                .containsExactly(COMPLAINT, ENFORCE, GRD_BNDRY1, GRD_BNDRY2, GRD_BNDRY3);
        // 🔴 새 키로만 적재된다 — 옛 키(idctCd·idctNm·wgvlRt)가 남아 있으면 감사 스냅샷이 두 형식으로
        //    갈려, 되읽는 쪽(@JsonAlias 폴백)이 어느 쪽을 봐야 하는지가 데이터마다 달라진다.
        //    🆕 TSK-4217 — anlsTrgtCd 키가 더해졌다. HOTSPOT 은 대메뉴 공통이라 값은 null 이지만 키는 실린다
        //    (Jackson 기본 설정은 Map 의 null 값도 키로 쓴다).
        assertThat(bfr).allSatisfy(item -> {
            assertThat(item).containsOnlyKeys("vrblCd", "anlsTrgtCd", "vrblNm", "stngVl");
            assertThat(item).doesNotContainKeys("idctCd", "idctNm", "wgvlRt");
            assertThat(item.get("anlsTrgtCd")).isNull();
        });
        assertThat(aftr).allSatisfy(item -> {
            assertThat(item).containsOnlyKeys("vrblCd", "anlsTrgtCd", "vrblNm", "stngVl");
            assertThat(item.get("anlsTrgtCd")).isNull();
        });
        assertThat(bfr).extracting(item -> item.get("vrblNm"))
                .containsExactly("민원빈도", "단속건수", "심각 상위 비율", "경고 상위 비율", "주의 상위 비율");

        assertThat(rate(bfr.get(0))).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(rate(bfr.get(1))).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(rate(aftr.get(0))).isEqualByComparingTo(new BigDecimal("55"));
        assertThat(rate(aftr.get(1))).isEqualByComparingTo(new BigDecimal("45"));
    }

    @Test
    @DisplayName("20-5. 🆕 PUT HOTSPOT 등급 비율 — 순서가 뒤집히거나 소수·범위 밖이면 400, 정상 값은 저장 (TSK-4321)")
    void hotspotGradeBoundaryValidation() {
        ResponseEntity<String> order = putWeights(HOTSPOT, body(List.of(item(COMPLAINT, 60), item(ENFORCE, 40),
                item(GRD_BNDRY1, 10), item(GRD_BNDRY2, 10), item(GRD_BNDRY3, 15)), null), String.class);
        assertThat(order.getStatusCode().value()).as("심각 = 경고").isEqualTo(400);

        ResponseEntity<String> over = putWeights(HOTSPOT, body(List.of(item(COMPLAINT, 60), item(ENFORCE, 40),
                item(GRD_BNDRY1, 5), item(GRD_BNDRY2, 10), item(GRD_BNDRY3, 101)), null), String.class);
        assertThat(over.getStatusCode().value()).as("상한 100 초과").isEqualTo(400);

        ResponseEntity<AnalysisTypeWeightVO> ok = putWeights(HOTSPOT, body(List.of(item(COMPLAINT, 60), item(ENFORCE, 40),
                item(GRD_BNDRY1, 3), item(GRD_BNDRY2, 7), item(GRD_BNDRY3, 12)), null), AnalysisTypeWeightVO.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(stngVlInDb(HOTSPOT, GRD_BNDRY3)).isEqualByComparingTo(new BigDecimal("12"));
    }

    // ------------------------------------------------- 재정규화 (API 미노출 — 서비스 직접 호출)

    @Test
    @DisplayName("15. selectEffectiveWeights(HOTSPOT) — 두 지표 모두 use_yn='Y' 라 원값 60/40, 합계 100")
    void effectiveWeightsForHotspot() {
        List<AnalysisWeightVO> effective = analysisWeightService.selectEffectiveWeights(HOTSPOT);

        assertThat(effective).hasSize(2);
        assertThat(effective).extracting(AnalysisWeightVO::getVrblCd).containsExactly(COMPLAINT, ENFORCE);
        assertThat(effective.get(0).getStngVl()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(effective.get(1).getStngVl()).isEqualByComparingTo(new BigDecimal("40"));

        BigDecimal sum = effective.stream()
                .map(AnalysisWeightVO::getStngVl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("16. selectEffectiveWeights(RECOMMEND) — 16행 중 WGVL 은 요금제적용(FEE) 2행뿐 · 원값 70/30 (TSK-4282)")
    void effectiveWeightsForRecommendAreFeeOnly() {
        // 원본은 16행(CFC 14 + WGVL 2)이고 화면 GET 은 전부 내린다. 재정규화 대상은 WGVL·Y 인 FEE 2행뿐이다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ?",
                Integer.class, RECOMMEND)).isEqualTo(16);

        List<AnalysisWeightVO> effective = analysisWeightService.selectEffectiveWeights(RECOMMEND);
        assertThat(effective).extracting(AnalysisWeightVO::getVrblCd)
                .containsExactlyInAnyOrder(FEE_CVLCPT_CNT_WGVL, FEE_CVLCPT_RT_WGVL);
        assertThat(effective).extracting(AnalysisWeightVO::getAnlsTrgtCd).containsOnly(FEE);
        Map<String, BigDecimal> byVrbl = new LinkedHashMap<>();
        effective.forEach(w -> byVrbl.put(w.getVrblCd(), w.getStngVl()));
        assertThat(byVrbl.get(FEE_CVLCPT_CNT_WGVL)).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(byVrbl.get(FEE_CVLCPT_RT_WGVL)).isEqualByComparingTo(new BigDecimal("30.00"));
    }

    // ------------------------------------------ 가중치 합계 · 계수 범위 (TSK-4140 D2 · D3 회귀 장치)

    @Test
    @DisplayName("17. PUT HOTSPOT 합계 100 — 200 (WGVL·use_yn='Y' 합계 검증을 통과한다)")
    void hotspotSumOfHundredIsAccepted() {
        ResponseEntity<AnalysisTypeWeightVO> saved = putWeights(HOTSPOT,
                body(hotspotItems(70, 30), null), AnalysisTypeWeightVO.class);

        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getBody()).isNotNull();
        assertThat(saved.getBody().getStngVlSum()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(stngVlInDb(HOTSPOT, COMPLAINT)).isEqualByComparingTo(new BigDecimal("70"));
        assertThat(stngVlInDb(HOTSPOT, ENFORCE)).isEqualByComparingTo(new BigDecimal("30"));
    }

    @Test
    @DisplayName("18. PUT HOTSPOT 합계 110 — 400 (초과도 미달과 같이 막힌다) · 기존 값 보존")
    void hotspotSumOverHundredReturns400() {
        ResponseEntity<String> response = putWeights(HOTSPOT,
                body(hotspotItems(70, 40), null), String.class);

        assertThat(response.getStatusCode().value())
                .as("합계 110 은 100 이 아니므로 400 이다 (케이스 6 의 90 과 대칭)")
                .isEqualTo(400);
        assertUnchangedHotspotSeed();
    }

    @Test
    @DisplayName("19. 🔴 PUT LIFEZONE — 계수(CFC)만이라 합계 검사를 건너뛴다 · 200 · 응답 합계 0 (TSK-4217 재작성)")
    void lifezoneSumIsNotValidatedAndCoefficientStaysOutOfIt() {
        // 전제 — 🔁 TSK-4217 이후 LIFEZONE 은 가중치(WGVL) 행이 아예 없다(혼잡도 계수 10행 + 🆕 수급 5행뿐).
        //        계수는 합계 개념이 없어 검증에도 합산에도 들어가지 않으므로, 이 대메뉴는 합계 검사 대상이 0건이다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND vrbl_se_cd = 'WGVL'",
                Integer.class, LIFEZONE))
                .as("이 케이스는 LIFEZONE 의 WGVL 행이 0건이어야 성립한다")
                .isZero();

        // 계수 하나를 시드와 다른 값으로 바꿔 저장한다 — 합계 검사가 걸리면(계수를 합산하면) 400 이 된다
        ResponseEntity<AnalysisTypeWeightVO> saved = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(GEO_CVRG_CRCT_VL, "2.0")), null),
                AnalysisTypeWeightVO.class);

        assertThat(saved.getStatusCode().value())
                .as("가중합이 성립하지 않는 분석유형은 합계 100 을 요구하지 않는다 (D2)")
                .isEqualTo(200);
        AnalysisTypeWeightVO type = saved.getBody();
        assertThat(type).isNotNull();

        // 🔴 핵심 — 응답 합계에 계수가 섞이면 0 이 아니다. WGVL·Y 가 0건이므로 0 이 정답이다.
        assertThat(type.getStngVlSum())
                .as("stngVlSum 은 WGVL·use_yn='Y' 한정 — 계수는 섞이지 않는다")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(type.getTrgtSums()).extracting(t -> t.getAnlsTrgtCd()).containsExactly(SPLY_DMND, CNGST);
        assertThat(type.getTrgtSums()).allSatisfy(t -> {
            assertThat(t.getWgvlYn()).isEqualTo("N");
            assertThat(t.getStngVlSum()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        // 저장 응답(withStngVl 복사본)에도 하위 유형이 실린다
        assertThat(type.getWeights()).extracting(AnalysisWeightVO::getAnlsTrgtCd).containsOnly(SPLY_DMND, CNGST);

        // 값 자체는 요청대로 저장된다 (검증을 건너뛴 것이지 저장을 건너뛴 것이 아니다)
        assertThat(stngVlInDb(LIFEZONE, GEO_CVRG_CRCT_VL)).isEqualByComparingTo(new BigDecimal("2.0"));
    }

    @Test
    @DisplayName("20. 🔴 PUT 계수가 max_vl 초과 — 400 이다 (DB CHECK 이 먼저 잡아 500 이 되면 안 된다)")
    void coefficientAboveMaxReturns400Not500() {
        // 상한을 테스트에 다시 적지 않는다 — 근거는 그 행의 max_vl 뿐이다 (D3)
        BigDecimal max = jdbcTemplate.queryForObject(
                "SELECT max_vl FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND vrbl_cd = ?",
                BigDecimal.class, LIFEZONE, GEO_CVRG_CRCT_VL);
        assertThat(max).as("계수 행에 max_vl 이 없으면 이 케이스가 성립하지 않는다").isNotNull();
        BigDecimal over = max.add(BigDecimal.ONE);

        // 🔁 TSK-4217 — 롤백 판별값을 SUPPLY 대신 다른 혼잡도 계수로 바꿨다.
        // 🔁 TSK-4346 D10 — 상한 초과 대상은 노출 계수 GEO_CVRG_CRCT_VL, 판별값은 경계 CNGST_GRD_BNDRY1(88 → 90 · 단조 유지)
        //    (숨김 MIN_CPCTY_VL·PCSHR_CRTR_VL 은 4-0 이 요청값을 버려 범위 검사·롤백 판별에 쓸 수 없다).
        ResponseEntity<String> response = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(GEO_CVRG_CRCT_VL, over.toPlainString(), CNGST_GRD_BNDRY1, "90")), null),
                String.class);

        // 🔴 500 이 아니라 400 이어야 한다 — 앱 검증이 DB CHECK 보다 앞서지 않으면
        //    DataIntegrityViolationException 이 그대로 500 으로 나간다(이 경로에 전역 핸들러가 없다).
        assertThat(response.getStatusCode().value())
                .as("허용 범위 초과는 사용자 입력 오류다 (500 이면 앱 범위 검증이 빠진 것)")
                .isEqualTo(400);

        // 한 트랜잭션이므로 같은 요청의 나머지 값도 저장되지 않는다
        assertThat(stngVlInDb(LIFEZONE, GEO_CVRG_CRCT_VL))
                .isEqualByComparingTo(new BigDecimal(LIFEZONE_CNGST_SEED.get(GEO_CVRG_CRCT_VL)));
        assertThat(stngVlInDb(LIFEZONE, CNGST_GRD_BNDRY1))
                .isEqualByComparingTo(new BigDecimal(LIFEZONE_CNGST_SEED.get(CNGST_GRD_BNDRY1)));
        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("20-0. 🆕 PUT LIFEZONE 숨김 행(MIN_CPCTY_VL·PCSHR_CRTR_VL) 값이 달라도 — 200 · 요청값 무시 · 현재값 유지 (TSK-4346 D13)")
    void hiddenRowValueIsIgnoredOnSave() {
        // 숨김 행만 바꿔 보내면 4-0 이 현재값으로 바꿔 끼워 "변경 없음" 이 된다 — 이력도 남지 않는다.
        // 범위 밖 값(MIN_CPCTY_VL max+1)도 4-1 범위 검사 전에 버려져 400 이 아니다.
        BigDecimal max = jdbcTemplate.queryForObject(
                "SELECT max_vl FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND vrbl_cd = ?",
                BigDecimal.class, LIFEZONE, MIN_CPCTY_VL);
        ResponseEntity<AnalysisTypeWeightVO> hiddenOnly = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(MIN_CPCTY_VL, max.add(BigDecimal.ONE).toPlainString(),
                        PCSHR_CRTR_VL, "0.9")), null),
                AnalysisTypeWeightVO.class);
        assertThat(hiddenOnly.getStatusCode().value()).as("숨김 행은 400 이 아니라 무시한다 (D13 (a))").isEqualTo(200);
        assertThat(stngVlInDb(LIFEZONE, MIN_CPCTY_VL))
                .isEqualByComparingTo(new BigDecimal(LIFEZONE_CNGST_SEED.get(MIN_CPCTY_VL)));
        assertThat(stngVlInDb(LIFEZONE, PCSHR_CRTR_VL))
                .isEqualByComparingTo(new BigDecimal(LIFEZONE_CNGST_SEED.get(PCSHR_CRTR_VL)));
        assertThat(historyCount()).as("숨김 행만 바꾼 저장은 변경 없음이다").isZero();
        assertThat(findWeight(hiddenOnly.getBody(), MIN_CPCTY_VL).getExpsrYn()).isEqualTo("N");

        // 노출 행과 함께 보내면 노출 행만 바뀌고, 이력 변경내용에 숨김 행이 없다. 응답(withStngVl 복사본)에도 expsrYn 이 실린다.
        ResponseEntity<AnalysisTypeWeightVO> mixed = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(GEO_CVRG_CRCT_VL, "2.0", PCSHR_CRTR_VL, "0.9")), null),
                AnalysisTypeWeightVO.class);
        assertThat(mixed.getStatusCode().value()).isEqualTo(200);
        assertThat(stngVlInDb(LIFEZONE, GEO_CVRG_CRCT_VL)).isEqualByComparingTo(new BigDecimal("2.0"));
        assertThat(stngVlInDb(LIFEZONE, PCSHR_CRTR_VL))
                .isEqualByComparingTo(new BigDecimal(LIFEZONE_CNGST_SEED.get(PCSHR_CRTR_VL)));
        assertThat(findWeight(mixed.getBody(), PCSHR_CRTR_VL).getStngVl())
                .isEqualByComparingTo(new BigDecimal(LIFEZONE_CNGST_SEED.get(PCSHR_CRTR_VL)));
        assertThat(mixed.getBody().getWeights()).extracting(AnalysisWeightVO::getExpsrYn).doesNotContainNull();
        assertThat(historyCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chg_cn FROM parking.tb_anls_vrbl_hstry ORDER BY chg_hstry_sn DESC LIMIT 1", String.class))
                .as("변경내용에는 노출 행만").doesNotContain("기준 점유율").doesNotContain("0.9");
    }

    @Test
    @DisplayName("20-1. 🆕 PUT LIFEZONE 혼잡 경계 BND2 ≤ BND1 — 400 (단조 검증) · 기존 값 보존 · 이력 0 (TSK-4090)")
    void lifezoneCngstBoundsNotMonotonicReturns400() {
        // BND2(92점) 를 BND1(88점) 아래로 — 각 값은 행 범위(50~100점) 안이라 범위 검증은 통과한다.
        // 🔴 GEO_CVRG_CRCT_VL 을 시드와 다른 값으로 함께 보내 롤백 여부를 실제로 가른다(🔁 TSK-4217 — 종전 SUPPLY · 🔁 TSK-4346 — 종전 PCSHR_CRTR_VL 은 숨김).
        ResponseEntity<String> response = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(CNGST_GRD_BNDRY2, "80", GEO_CVRG_CRCT_VL, "2.0")), null),
                String.class);

        assertThat(response.getStatusCode().value())
                .as("BND1 < BND2 < BND3 < BND4 가 아니면 등급이 뒤집히므로 400 이다 (saveWeights 4-2)")
                .isEqualTo(400);

        // 같은 값(BND2 == BND1)도 단조 위반이다 — 엄격 증가
        ResponseEntity<String> equal = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(CNGST_GRD_BNDRY2, "88", GEO_CVRG_CRCT_VL, "2.0")), null),
                String.class);
        assertThat(equal.getStatusCode().value()).as("BND2 == BND1 도 400 (엄격 증가)").isEqualTo(400);

        assertThat(stngVlInDb(LIFEZONE, CNGST_GRD_BNDRY1)).isEqualByComparingTo(new BigDecimal("88"));
        assertThat(stngVlInDb(LIFEZONE, CNGST_GRD_BNDRY2)).isEqualByComparingTo(new BigDecimal("92"));
        assertThat(stngVlInDb(LIFEZONE, GEO_CVRG_CRCT_VL))
                .isEqualByComparingTo(new BigDecimal(LIFEZONE_CNGST_SEED.get(GEO_CVRG_CRCT_VL)));
        assertThat(historyCount()).isZero();

        // 정상 순서면 저장된다 (대조군)
        ResponseEntity<AnalysisTypeWeightVO> ok = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(CNGST_GRD_BNDRY2, "90")), null),
                AnalysisTypeWeightVO.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(stngVlInDb(LIFEZONE, CNGST_GRD_BNDRY2)).isEqualByComparingTo(new BigDecimal("90"));
    }

    @Test
    @DisplayName("20-2. 🆕 PUT LIFEZONE 수급 경계 비단조(BNDRY2 ≤ BNDRY1) — 400 · 혼잡도와 같은 메시지 형식 · 기존 값 보존 · 이력 0 (TSK-4258 D20)")
    void lifezoneSplyBoundsNotMonotonicReturns400() {
        // BNDRY2 를 BNDRY1(20) 아래로 — 행 범위(1~99) 안이라 범위 검증은 통과하고 4-4 에서 걸린다.
        // 🔴 GEO_CVRG_CRCT_VL 을 시드와 다른 값으로 함께 보내 롤백 여부를 가른다(🔁 TSK-4346 — 종전 PCSHR_CRTR_VL 은 숨김)
        ResponseEntity<String> lower = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(SPLY_GRD_BNDRY2, "10", GEO_CVRG_CRCT_VL, "2.0")), null), String.class);
        assertThat(lower.getStatusCode().value()).isEqualTo(400);
        // 사유 문구는 test 프로파일 응답 본문에 실리지 않는다(include-message 꺼짐) — 서비스를 직접 불러 확인한다
        assertThat(rejectReason(lifezoneItems(Map.of(SPLY_GRD_BNDRY2, "10"))))
                .as("혼잡도(4-2)와 같은 형식")
                .isEqualTo("수급 등급 경계는 BNDRY1 < BNDRY2 < BNDRY3 < BNDRY4 여야 합니다: "
                        + "SPLY_GRD_BNDRY1 = 20, SPLY_GRD_BNDRY2 = 10");

        ResponseEntity<String> equal = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(SPLY_GRD_BNDRY2, "20", GEO_CVRG_CRCT_VL, "2.0")), null), String.class);
        assertThat(equal.getStatusCode().value()).as("BNDRY2 == BNDRY1 도 400 (엄격 증가)").isEqualTo(400);

        assertThat(stngVlInDb(LIFEZONE, SPLY_GRD_BNDRY2)).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(stngVlInDb(LIFEZONE, GEO_CVRG_CRCT_VL))
                .isEqualByComparingTo(new BigDecimal(LIFEZONE_CNGST_SEED.get(GEO_CVRG_CRCT_VL)));
        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("20-3. 🆕 PUT LIFEZONE 수급 설정 소수값 — 400 (정수만) · 20.0 처럼 소수부 0 은 정수로 받는다 (TSK-4258 D20)")
    void lifezoneSplyDecimalValueReturns400() {
        ResponseEntity<String> bndry = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(SPLY_GRD_BNDRY1, "20.5")), null), String.class);
        assertThat(bndry.getStatusCode().value()).as("경계 소수").isEqualTo(400);
        assertThat(rejectReason(lifezoneItems(Map.of(SPLY_GRD_BNDRY1, "20.5"))))
                .as("범위·단조가 아니라 정수 검사(4-3)에서 걸린다").startsWith("정수만 입력할 수 있습니다: SPLY_GRD_BNDRY1");

        ResponseEntity<String> min = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(SPLY_MIN_MNTH_AVG_CVLCPT, "5.5")), null), String.class);
        assertThat(min.getStatusCode().value()).as("최소 월평균 민원 소수").isEqualTo(400);
        assertThat(rejectReason(lifezoneItems(Map.of(SPLY_MIN_MNTH_AVG_CVLCPT, "5.5"))))
                .startsWith("정수만 입력할 수 있습니다: " + SPLY_MIN_MNTH_AVG_CVLCPT);

        assertThat(stngVlInDb(LIFEZONE, SPLY_GRD_BNDRY1)).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(stngVlInDb(LIFEZONE, SPLY_MIN_MNTH_AVG_CVLCPT)).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(historyCount()).isZero();

        // 대조군 — 소수부 0(30.0)은 정수다. 정상 저장된다
        ResponseEntity<AnalysisTypeWeightVO> ok = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(SPLY_GRD_BNDRY1, "30.0")), null), AnalysisTypeWeightVO.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(stngVlInDb(LIFEZONE, SPLY_GRD_BNDRY1)).isEqualByComparingTo(new BigDecimal("30"));
    }

    @Test
    @DisplayName("20-4. 🆕 PUT LIFEZONE 수급 설정 범위 밖(BNDRY4 100 · 최소 월평균 0·31) — 400 (행의 min_vl/max_vl · TSK-4258 D20)")
    void lifezoneSplyOutOfRangeReturns400() {
        ResponseEntity<String> over = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(SPLY_GRD_BNDRY4, "100")), null), String.class);
        assertThat(over.getStatusCode().value()).as("경계 상한 99 초과").isEqualTo(400);
        assertThat(rejectReason(lifezoneItems(Map.of(SPLY_GRD_BNDRY4, "100"))))
                .startsWith("허용 범위를 벗어났습니다: " + SPLY_GRD_BNDRY4);

        ResponseEntity<String> minZero = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(SPLY_MIN_MNTH_AVG_CVLCPT, "0")), null), String.class);
        assertThat(minZero.getStatusCode().value()).as("최소 월평균 하한 1 미만").isEqualTo(400);
        ResponseEntity<String> minOver = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(SPLY_MIN_MNTH_AVG_CVLCPT, "31")), null), String.class);
        assertThat(minOver.getStatusCode().value()).as("최소 월평균 상한 30 초과").isEqualTo(400);

        assertThat(stngVlInDb(LIFEZONE, SPLY_GRD_BNDRY4)).isEqualByComparingTo(new BigDecimal("80"));
        assertThat(stngVlInDb(LIFEZONE, SPLY_MIN_MNTH_AVG_CVLCPT)).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(historyCount()).isZero();

        // 대조군 — 단조·정수·범위를 모두 지키면 저장된다
        ResponseEntity<AnalysisTypeWeightVO> ok = putWeights(LIFEZONE,
                body(lifezoneItems(Map.of(SPLY_GRD_BNDRY4, "99", SPLY_MIN_MNTH_AVG_CVLCPT, "30")), null),
                AnalysisTypeWeightVO.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(stngVlInDb(LIFEZONE, SPLY_GRD_BNDRY4)).isEqualByComparingTo(new BigDecimal("99"));
    }

    // ------------------------------------------------ 이력 분석유형 필터 (Step 7-1)

    @Test
    @DisplayName("21. GET /histories?anlsTypeCd=HOTSPOT — 그 분석유형 이력만 (TSK-4140 Step 7-1)")
    void historiesFilteredByAnalysisType() {
        // HOTSPOT 1건 + LIFEZONE 1건을 쌓는다 (둘 다 값이 실제로 바뀌므로 이력 1행씩)
        putWeights(HOTSPOT, body(hotspotItems(55, 45), null), AnalysisTypeWeightVO.class);
        putWeights(LIFEZONE, body(lifezoneItems(Map.of(GEO_CVRG_CRCT_VL, "2.0")), null),
                AnalysisTypeWeightVO.class);

        assertThat(getHistories(20)).as("필터 없이는 두 유형이 함께 온다(기존 계약)").hasSize(2);

        assertThat(getHistories(20, HOTSPOT))
                .extracting(AnalysisWeightHstryVO::getAnlsTypeCd)
                .containsExactly(HOTSPOT);
        assertThat(getHistories(20, LIFEZONE))
                .extracting(AnalysisWeightHstryVO::getAnlsTypeCd)
                .containsExactly(LIFEZONE);
    }

    @Test
    @DisplayName("22. 🔴 필터가 LIMIT 보다 먼저다 — HOTSPOT 을 size 만큼 쌓아도 LIFEZONE 이력이 남는다")
    void historyFilterIsAppliedBeforeLimit() {
        // 먼저 쌓인 LIFEZONE 1건은 최신순 size=2 안에서 HOTSPOT 2건에 밀려난다.
        putWeights(LIFEZONE, body(lifezoneItems(Map.of(GEO_CVRG_CRCT_VL, "2.0")), null),
                AnalysisTypeWeightVO.class);
        putWeights(HOTSPOT, body(hotspotItems(55, 45), null), AnalysisTypeWeightVO.class);
        putWeights(HOTSPOT, body(hotspotItems(50, 50), null), AnalysisTypeWeightVO.class);

        // 전제 — size=2 로 자르면 LIFEZONE 은 잘려 나간다(= 프론트 필터로는 0건이 되는 상황)
        assertThat(getHistories(2))
                .extracting(AnalysisWeightHstryVO::getAnlsTypeCd)
                .containsOnly(HOTSPOT);

        // 🔴 핵심 — 거른 뒤에 자르므로 LIFEZONE 이력은 그대로 보인다. 순서가 뒤집히면 여기서 0건이 된다.
        assertThat(getHistories(2, LIFEZONE))
                .as("WHERE 가 LIMIT 보다 먼저여야 한다 — 0건이면 자른 뒤 거른 것이다")
                .extracting(AnalysisWeightHstryVO::getAnlsTypeCd)
                .containsExactly(LIFEZONE);
    }

    @Test
    @DisplayName("23. GET /histories?anlsTypeCd=NO_SUCH_TYPE — 빈 배열 200 (400 아님)")
    void historiesWithUnknownAnalysisTypeReturnsEmpty200() {
        putWeights(HOTSPOT, body(hotspotItems(55, 45), null), AnalysisTypeWeightVO.class);

        // 없는 코드를 400 으로 막지 않는다 — "이력이 아직 없는 유형" 과 구분할 실익이 없다
        assertThat(getHistories(20, "NO_SUCH_TYPE")).isEmpty();
    }

    // ------------------------------------------- 하위 유형 묶음 (TSK-4217 Step 5 — 전용 대메뉴 TST_4217)

    @Test
    @DisplayName("24. 🆕 PUT 묶음별 합계 100 — A 100·B 90 은 400 · 둘 다 100 은 200 · B 전부 미사용이면 B 검사 건너뜀 · 스냅샷 anlsTrgtCd")
    void sumIsValidatedPerTrgtGroup() throws Exception {
        // 🔴 저장 API 의 유형 존재 검사(selectAnalysisTypes — 공통코드 ANLS_TYPE_CD·use_yn='Y' + 변수 행 EXISTS)를
        //    통과해야 하므로 공통코드도 함께 넣는다(없으면 PUT 이 404). 메서드 안에서만 넣고 finally 로 지운다.
        try {
            jdbcTemplate.update("INSERT INTO parking.tb_com_cd (group_cd, cd_vl, cd_nm, sort_seq, use_yn)"
                    + " VALUES ('ANLS_TYPE_CD', ?, 'TSK-4217 테스트', 999, 'Y')", TST_TYPE);
            insertTstRow(TST_TRGT_A, "A1", "60", 1, "Y");
            insertTstRow(TST_TRGT_A, "A2", "40", 2, "Y");
            insertTstRow(TST_TRGT_B, "B1", "50", 3, "Y");
            insertTstRow(TST_TRGT_B, "B2", "50", 4, "Y");

            // A 합 100 · B 합 90 → 묶음 B 가 틀렸으므로 400 (대메뉴 전체 합 190 으로 보는 구현도 여기서 400 이지만,
            // 아래 '둘 다 100 → 200' 이 전체 합(200) 검사를 걸러 낸다)
            ResponseEntity<String> bad = putWeights(TST_TYPE,
                    body(List.of(item("A1", 60), item("A2", 40), item("B1", 50), item("B2", 40)), null), String.class);
            assertThat(bad.getStatusCode().value()).as("묶음 B 합 90").isEqualTo(400);
            assertThat(historyCount()).isZero();

            // 두 묶음 모두 100 → 200 (전체 합 200 이지만 묶음마다 100 이므로 통과)
            ResponseEntity<String> ok = putWeights(TST_TYPE,
                    body(List.of(item("A1", 70), item("A2", 30), item("B1", 50), item("B2", 50)), null), String.class);
            assertThat(ok.getStatusCode().value()).as("묶음마다 합 100").isEqualTo(200);

            // 🆕 이력 스냅샷 — 원소마다 anlsTrgtCd 키에 그 행의 하위 유형이 실린다
            String aftrJson = jdbcTemplate.queryForObject(
                    "SELECT aftr_vrbl_json::text FROM parking.tb_anls_vrbl_hstry"
                            + " WHERE anls_type_cd = ? ORDER BY chg_hstry_sn DESC LIMIT 1",
                    String.class, TST_TYPE);
            List<Map<String, Object>> aftr = parseSnapshot(aftrJson);
            Map<String, Object> trgtByVrbl = new LinkedHashMap<>();
            aftr.forEach(item -> trgtByVrbl.put((String) item.get("vrblCd"), item.get("anlsTrgtCd")));
            assertThat(trgtByVrbl).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "A1", TST_TRGT_A, "A2", TST_TRGT_A, "B1", TST_TRGT_B, "B2", TST_TRGT_B));

            // 묶음 B 가 전부 use_yn='N' 이면 활성 WGVL 0건이라 B 검사를 건너뛴다 → B 합 30 이어도 200
            jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET use_yn = 'N'"
                    + " WHERE anls_type_cd = ? AND anls_trgt_cd = ?", TST_TYPE, TST_TRGT_B);
            ResponseEntity<String> skipped = putWeights(TST_TYPE,
                    body(List.of(item("A1", 60), item("A2", 40), item("B1", 10), item("B2", 20)), null), String.class);
            assertThat(skipped.getStatusCode().value()).as("활성 WGVL 0건 묶음은 검사하지 않는다").isEqualTo(200);
        } finally {
            jdbcTemplate.update("DELETE FROM parking.tb_anls_vrbl_hstry WHERE anls_type_cd = ?", TST_TYPE);
            jdbcTemplate.update("DELETE FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ?", TST_TYPE);
            jdbcTemplate.update("DELETE FROM parking.tb_com_cd WHERE group_cd = 'ANLS_TYPE_CD' AND cd_vl = ?", TST_TYPE);
        }
    }

    @Test
    @DisplayName("25. 🆕 selectEffectiveWeights — 묶음마다 합 100 으로 재정규화 · 잔차는 묶음 안 sort_seq 마지막 행")
    void effectiveWeightsAreRenormalizedPerTrgtGroup() {
        // 직접 호출이라(API 미노출 — 케이스 15 방식) 공통코드 행은 필요 없다.
        // sort_seq 를 두 묶음에 엇갈려 준다 — 잔차가 '전체 마지막' 이 아니라 '묶음 안 마지막' 에 붙는지 가른다.
        try {
            insertTstRow(TST_TRGT_A, "A1", "1", 1, "Y");
            insertTstRow(TST_TRGT_B, "B1", "30", 2, "Y");
            insertTstRow(TST_TRGT_A, "A2", "1", 3, "Y");
            insertTstRow(TST_TRGT_B, "B2", "20", 4, "Y");
            insertTstRow(TST_TRGT_A, "A3", "1", 5, "Y");
            insertTstRow(TST_TRGT_B, "B3", "50", 6, "N");   // 미사용 — 재정규화 분모에서 빠진다

            List<AnalysisWeightVO> effective = analysisWeightService.selectEffectiveWeights(TST_TYPE);

            assertThat(effective).extracting(AnalysisWeightVO::getVrblCd)
                    .containsExactlyInAnyOrder("A1", "A2", "A3", "B1", "B2");

            Map<String, BigDecimal> byVrbl = new LinkedHashMap<>();
            Map<String, BigDecimal> sumByTrgt = new LinkedHashMap<>();
            for (AnalysisWeightVO w : effective) {
                byVrbl.put(w.getVrblCd(), w.getStngVl());
                sumByTrgt.merge(w.getAnlsTrgtCd(), w.getStngVl(), BigDecimal::add);
            }
            assertThat(sumByTrgt).containsOnlyKeys(TST_TRGT_A, TST_TRGT_B);
            assertThat(sumByTrgt.get(TST_TRGT_A)).isEqualByComparingTo(new BigDecimal("100"));
            assertThat(sumByTrgt.get(TST_TRGT_B)).isEqualByComparingTo(new BigDecimal("100"));

            // 묶음 A 3분할 — 33.33 · 33.33 · 마지막(sort_seq 5)이 잔차를 흡수해 33.34
            assertThat(byVrbl.get("A1")).isEqualByComparingTo(new BigDecimal("33.33"));
            assertThat(byVrbl.get("A2")).isEqualByComparingTo(new BigDecimal("33.33"));
            assertThat(byVrbl.get("A3")).isEqualByComparingTo(new BigDecimal("33.34"));
            // 묶음 B — 30:20 을 활성 합 50 으로 나눠 60 · 40 (미사용 B3 50 이 분모에 섞이면 30 · 20 이 된다)
            assertThat(byVrbl.get("B1")).isEqualByComparingTo(new BigDecimal("60"));
            assertThat(byVrbl.get("B2")).isEqualByComparingTo(new BigDecimal("40"));
        } finally {
            jdbcTemplate.update("DELETE FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ?", TST_TYPE);
        }
    }

    // ------------------------------------------- 구역추천 요금제적용 묶음 (TSK-4282 Step 4)

    @Test
    @DisplayName("26. 🆕 PUT RECOMMEND 요금제적용 가중치 합계 99 — 400 · 메시지에 '요금제적용' · 기존 값 보존 · 이력 0 (TSK-4282)")
    void recommendFeeWeightSumNotHundredReturns400() {
        // 70/30 → 70/29 (합 99). 🔴 전체 교체라 RECOMMEND 16행을 모두 보낸다 — 빠지면 누락 400 이 먼저 난다.
        List<Map<String, Object>> items = recommendItems(Map.of(FEE_CVLCPT_RT_WGVL, "29"));
        assertThat(items).hasSize(16);

        ResponseEntity<String> response = putWeights(RECOMMEND, body(items, null), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        // 사유는 test 프로파일 응답 본문에 실리지 않아 서비스를 직접 불러 확인한다
        assertThat(rejectReason(RECOMMEND, items))
                .as("합계 검사(4)에서 FEE 묶음 라벨 '요금제적용' 으로 걸린다")
                .contains("요금제적용")
                .contains("가중치 합계가 100 이 아닙니다");

        assertThat(stngVlInDb(RECOMMEND, FEE_CVLCPT_RT_WGVL)).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("27. 🆕 PUT RECOMMEND 요금제적용 기준값 0 — 400 (min_vl 1 하한) · 기존 값 보존 · 이력 0 (TSK-4282)")
    void recommendFeeCriterionZeroReturns400() {
        List<Map<String, Object>> items = recommendItems(Map.of(FEE_CVLCPT_MM_CRTR_JEJU, "0"));

        ResponseEntity<String> response = putWeights(RECOMMEND, body(items, null), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(rejectReason(RECOMMEND, items))
                .as("범위 검사(4-1)에서 걸린다").startsWith("허용 범위를 벗어났습니다: " + FEE_CVLCPT_MM_CRTR_JEJU);

        assertThat(stngVlInDb(RECOMMEND, FEE_CVLCPT_MM_CRTR_JEJU))
                .isEqualByComparingTo(bscStngVlInDb(RECOMMEND, FEE_CVLCPT_MM_CRTR_JEJU));
        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("28. 🆕 PUT RECOMMEND 요금제적용 가중치 60/40 — 200 · FEE 묶음 합계 100 · DB 반영 · 이력 1 (TSK-4282)")
    void recommendFeeWeightsSaveSucceeds() {
        ResponseEntity<AnalysisTypeWeightVO> saved = putWeights(RECOMMEND,
                body(recommendItems(Map.of(FEE_CVLCPT_CNT_WGVL, "60", FEE_CVLCPT_RT_WGVL, "40")), null),
                AnalysisTypeWeightVO.class);

        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        AnalysisTypeWeightVO type = saved.getBody();
        assertThat(type).isNotNull();
        assertThat(type.getStngVlSum()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(type.getTrgtSums()).filteredOn(t -> FEE.equals(t.getAnlsTrgtCd())).singleElement()
                .satisfies(t -> {
                    assertThat(t.getWgvlYn()).isEqualTo("Y");
                    assertThat(t.getStngVlSum()).isEqualByComparingTo(new BigDecimal("100"));
                });
        assertThat(stngVlInDb(RECOMMEND, FEE_CVLCPT_CNT_WGVL)).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(stngVlInDb(RECOMMEND, FEE_CVLCPT_RT_WGVL)).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(historyCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 헬퍼

    /**
     * 🆕 RECOMMEND 의 <b>전체 교체 저장용</b> 지표 목록 (TSK-4282). 행 번호·시드값을 테스트에 적지 않고
     * <b>DB 현재 행 전체</b>를 읽어 {@code overrides} 에 적은 항목만 바꿔 보낸다 — 빠지면 누락 400 이 먼저 난다.
     */
    private List<Map<String, Object>> recommendItems(Map<String, String> overrides) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT vrbl_cd, stng_vl FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? ORDER BY sort_seq, vrbl_cd",
                RECOMMEND)) {
            String cd = (String) row.get("vrbl_cd");
            String current = ((BigDecimal) row.get("stng_vl")).toPlainString();
            items.add(item(cd, overrides.getOrDefault(cd, current)));
        }
        assertThat(items).extracting(i -> i.get("vrblCd")).containsAll(overrides.keySet());
        return items;
    }

    /** 전용 대메뉴 {@value #TST_TYPE} 의 가중치(WGVL · 범위 0~100) 1행. 케이스 24·25 전용 — 호출 메서드가 finally 로 지운다. */
    private void insertTstRow(String anlsTrgtCd, String vrblCd, String stngVl, int sortSeq, String useYn) {
        jdbcTemplate.update("INSERT INTO parking.tb_anls_vrbl_stng"
                + " (anls_type_cd, anls_trgt_cd, vrbl_cd, vrbl_nm, vrbl_se_cd, stng_vl, bsc_stng_vl,"
                + "  min_vl, max_vl, sort_seq, use_yn)"
                + " VALUES (?, ?, ?, ?, 'WGVL', ?, ?, 0, 100, ?, ?)",
                TST_TYPE, anlsTrgtCd, vrblCd, "테스트 " + vrblCd,
                new BigDecimal(stngVl), new BigDecimal(stngVl), sortSeq, useYn);
    }

    private List<AnalysisTypeWeightVO> getWeights() {
        ResponseEntity<List<AnalysisTypeWeightVO>> response = restTemplate.exchange(
                WEIGHTS_URL, HttpMethod.GET, null, new ParameterizedTypeReference<List<AnalysisTypeWeightVO>>() {});
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<AnalysisWeightHstryVO> getHistories(int size) {
        return getHistories(size, null);
    }

    /** {@code anlsTypeCd} 가 null 이면 쿼리에서 아예 뺀다 — 미지정(전 유형) 경로를 그대로 태운다. */
    private List<AnalysisWeightHstryVO> getHistories(int size, String anlsTypeCd) {
        String url = HISTORIES_URL + "?size=" + size
                + (anlsTypeCd == null ? "" : "&anlsTypeCd=" + anlsTypeCd);
        ResponseEntity<List<AnalysisWeightHstryVO>> response = restTemplate.exchange(
                url, HttpMethod.GET, null,
                new ParameterizedTypeReference<List<AnalysisWeightHstryVO>>() {});
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /** 🔴 쓰기는 반드시 세션을 실어 보낸다 (TSK-4115 Step 7 — @PreAuthorize). */
    private <T> ResponseEntity<T> putWeights(String anlsTypeCd, Object body, Class<T> responseType) {
        return restTemplate.exchange(WEIGHTS_URL + "/" + anlsTypeCd, HttpMethod.PUT,
                auth.jsonEntity(session, body), responseType);
    }

    /** 저장 요청 바디. {@code chgRsn} 이 null 이면 키 자체를 넣지 않는다(선택 항목). */
    private Map<String, Object> body(List<Map<String, Object>> weights, String chgRsn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weights", new ArrayList<>(weights));
        if (chgRsn != null) {
            body.put("chgRsn", chgRsn);
        }
        return body;
    }

    /**
     * HOTSPOT 의 <b>전체 교체 저장용</b> 지표 목록 = 가중치 2행 + 등급 비율 3행 (🔁 TSK-4321).
     *
     * <p>🔴 저장 API 는 "요청 지표 집합 == 현재 지표 집합" 을 요구한다(의도된 전체 교체 계약 —
     * {@code AnalysisWeightServiceImpl.saveWeights()} 3). 계수 2행이 빠지면 합계·범위·이력 무엇을
     * 검증하려 했든 <b>집합 불일치 400</b> 이 먼저 걸린다. 그래서 400 을 기대하는 케이스도 이것을 쓴다 —
     * 그러지 않으면 "의도한 이유로 400" 인지 "집합이 틀려서 400" 인지 구분되지 않는다.
     *
     * @param complaint 민원빈도 가중치(%)
     * @param enforce   단속건수 가중치(%)
     */
    private List<Map<String, Object>> hotspotItems(int complaint, int enforce) {
        return List.of(item(COMPLAINT, complaint), item(ENFORCE, enforce),
                item(GRD_BNDRY1, 5), item(GRD_BNDRY2, 10), item(GRD_BNDRY3, 15));
    }

    /**
     * LIFEZONE 의 <b>전체 교체 저장용</b> 지표 목록 = 혼잡도 설정값 10행 (TSK-4090) + 🆕 수급 5행 (TSK-4258 D20).
     * 🔁 TSK-4217 — 가중치 2행(SUPPLY·CONGESTION)·환산계수(CVLCPT_TO_CPCTY)가 지워져 10항목이었고, 이제 15항목이다.
     *
     * <p>🔴 혼잡도 10행은 시드값을 그대로 되보낸다 — 빠지면 무엇을 검증하려 했든 <b>누락 400</b> 이 먼저 걸린다
     * ({@link #hotspotItems(int, int)} 와 같은 이유). {@code overrides} 에 적은 항목만 시드값 대신 그 값을 보낸다.
     */
    /**
     * 🆕 LIFEZONE 저장을 서비스로 직접 불러 400 사유 문구를 꺼낸다 (TSK-4258 D20).
     * test 프로파일은 응답 본문에 사유를 싣지 않아(HTTP 로는 상태코드만 보인다) 어느 검사에서 걸렸는지 가르려면 이 길뿐이다.
     * 예외로 끝나 트랜잭션이 롤백되므로 값·이력이 남지 않는다.
     */
    private String rejectReason(List<Map<String, Object>> items) {
        return rejectReason(LIFEZONE, items);
    }

    /** 🆕 TSK-4282 — 분석유형을 받는 오버로드 (RECOMMEND 요금제적용 케이스 26·27). */
    private String rejectReason(String anlsTypeCd, List<Map<String, Object>> items) {
        AnalysisWeightSaveRequest req = objectMapper.convertValue(body(items, null), AnalysisWeightSaveRequest.class);
        ResponseStatusException e = catchThrowableOfType(
                () -> analysisWeightService.saveWeights(anlsTypeCd, req, IntegrationLoginSupport.MOCK_ID, "테스트"),
                ResponseStatusException.class);
        assertThat(e).as("400 이어야 한다").isNotNull();
        assertThat(e.getStatusCode().value()).isEqualTo(400);
        return e.getReason();
    }

    private List<Map<String, Object>> lifezoneItems(Map<String, String> overrides) {
        List<Map<String, Object>> items = new ArrayList<>();
        LIFEZONE_CNGST_SEED.forEach((cd, seed) -> items.add(item(cd, overrides.getOrDefault(cd, seed))));
        return items;
    }

    private Map<String, Object> item(String vrblCd, int stngVl) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("vrblCd", vrblCd);
        item.put("stngVl", stngVl);
        return item;
    }

    /**
     * 소수 설정값용 오버로드 — 계수({@code CFC})는 {@code 0.5} 같은 값을 받는다 (TSK-4140).
     *
     * <p>🔴 {@link BigDecimal} 로 담는다 — 문자열로 넣으면 요청 JSON 이 {@code "1.0"}(따옴표)로 나가
     * 숫자 계약을 검증하지 못한다.
     */
    private Map<String, Object> item(String vrblCd, String stngVl) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("vrblCd", vrblCd);
        item.put("stngVl", new BigDecimal(stngVl));
        return item;
    }

    private List<Map<String, Object>> parseSnapshot(String json) throws Exception {
        assertThat(json).isNotNull();
        return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
    }

    /** 스냅샷 JSON 의 수치는 Jackson 이 Integer/Double 중 무엇으로 읽어도 되도록 문자열을 거쳐 비교한다. */
    private BigDecimal rate(Map<String, Object> snapshotItem) {
        return new BigDecimal(String.valueOf(snapshotItem.get("stngVl")));
    }

    private int historyCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_vrbl_hstry", Integer.class);
        return count == null ? 0 : count;
    }

    private BigDecimal stngVlInDb(String anlsTypeCd, String vrblCd) {
        return jdbcTemplate.queryForObject(
                "SELECT stng_vl FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND vrbl_cd = ?",
                BigDecimal.class, anlsTypeCd, vrblCd);
    }

    private BigDecimal bscStngVlInDb(String anlsTypeCd, String vrblCd) {
        return jdbcTemplate.queryForObject(
                "SELECT bsc_stng_vl FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND vrbl_cd = ?",
                BigDecimal.class, anlsTypeCd, vrblCd);
    }

    /** 검증 실패 케이스 공통 — 한 트랜잭션이므로 기존 값·이력이 그대로여야 한다. */
    private void assertUnchangedHotspotSeed() {
        assertThat(stngVlInDb(HOTSPOT, COMPLAINT)).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(stngVlInDb(HOTSPOT, ENFORCE)).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(historyCount()).isZero();
    }

    private AnalysisTypeWeightVO findType(List<AnalysisTypeWeightVO> types, String anlsTypeCd) {
        return types.stream()
                .filter(type -> anlsTypeCd.equals(type.getAnlsTypeCd()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("분석유형을 목록에서 찾지 못했습니다: " + anlsTypeCd));
    }

    private AnalysisWeightVO findWeight(AnalysisTypeWeightVO type, String vrblCd) {
        return type.getWeights().stream()
                .filter(weight -> vrblCd.equals(weight.getVrblCd()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("지표를 찾지 못했습니다: " + vrblCd));
    }
}
