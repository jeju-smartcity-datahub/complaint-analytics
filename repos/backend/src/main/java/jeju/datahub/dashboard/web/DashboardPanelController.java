package jeju.datahub.dashboard.web;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.common.web.CurrentUserResolver;
import jeju.datahub.dashboard.service.AnalysisVO;
import jeju.datahub.dashboard.service.DashboardPanelService;
import jeju.datahub.dashboard.service.DataFreshnessVO;
import jeju.datahub.dashboard.service.HotspotVO;
import jeju.datahub.dashboard.service.ReportVO;
import jeju.datahub.dashboard.service.SuggestionVO;
import lombok.RequiredArgsConstructor;

/**
 * 대시보드 하단/집중구역 카드 REST 컨트롤러.
 *
 * <p>집중구역 순위·최근 분석·최근 리포트·AI 추천 질문 4종의 top N 조회 API를 제공한다.
 * 뒤 3종은 조회 전용(MyBatis)이고 집중구역은 실시간 군집 분석 위임이다(TSK-4032 D8).
 * 전부 데이터가 없으면 빈 배열을 반환한다.
 * (KPI·읍면동 순위 집계 API는 {@code ComplaintDashboardController} 가 담당.)
 */
@Tag(name = "대시보드 패널", description = "대시보드 하단/집중구역 카드 조회 API (집중구역·분석·리포트·AI 추천질문)")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardPanelController {

    private final DashboardPanelService dashboardPanelService;

    /**
     * 로그인 사용자 해석(401 판정은 그 한 곳이다) — "최근 리포트" 카드가 쓴다 (TSK-4202 Step 4).
     *
     * <p>🔴 카드는 보고서 목록의 <b>축약</b>이라 같은 소유자 규칙을 받는다 — 여기만 빠지면
     * 목록에서 가린 남의 보고서가 대시보드 첫 화면에 그대로 뜬다.
     */
    private final CurrentUserResolver currentUser;

    /**
     * 민원 집중구역 순위 (TSK-4032 D8).
     *
     * <p><b>저장된 분석 결과가 아니라 실시간 군집 분석</b> 결과다 — 분석 화면
     * ({@code GET /api/analysis/hotspot})과 같은 서비스를 호출해 산식·등급 임계값을 공유한다.
     *
     * <p>기간은 대시보드 조회단위(어제/주간/월간)와 무관하게 <b>최근 30일 고정</b>이다 —
     * 하루치로는 군집이 형성되지 않아(실측 0개) 카드가 늘 비기 때문이다.
     * 🔴 기준일은 오늘이 아니라 <b>최신 민원 발생일</b>이며(TSK-4169), 지역은 {@code sggCd} 를
     * 따른다 — <b>미전송·빈 값·미등록 코드일 때만</b> 전 지역이다.
     *
     * <p>결과 0건은 오류가 아니라 <b>빈 배열 + 200</b> 이다(D11) — 지역을 좁혀 DBSCAN 최소 점
     * 개수를 못 채운 경우도 포함한다.
     * {@code limit} 은 범위 밖 값이어도 예외가 아니라 분석 서비스가 클램프한다(1~30).
     */
    @Operation(summary = "민원 집중구역 순위",
            description = "최근 30일(기준일=최신 민원 발생일) 민원·단속 좌표를 실시간 군집 분석해 종합점수 내림차순 상위 N건 반환. "
                    + "구역명·민원건수·단속건수·종합점수·등급(grade/gradeNm)·대표좌표. "
                    + "기간이 30일 고정인 이유는 하루치로 군집이 형성되지 않기 때문이다. "
                    + "sggCd(시군구코드 5자리)를 주면 그 지역으로 좁힌다 — 미전송·빈 값·미등록 코드면 전 지역. "
                    + "군집이 없으면 빈 배열이며(200), limit 은 1~30 으로 클램프된다.")
    @GetMapping("/hotspots")
    public List<HotspotVO> hotspots(
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(required = false) String sggCd) {
        return dashboardPanelService.getHotspots(limit, sggCd);
    }

    /**
     * 최근 분석 실행. TB_ANLS_EXCN 을 실행일시(excn_dt) 내림차순으로 top N 반환(유형명·상태명 포함).
     *
     * <p>{@code anlsTypeCd} 를 주면 그 실행유형 1종으로 좁힌다(미전송 시 분석 화면용 5종).
     * 미등록 코드를 줘도 오류가 아니라 <b>빈 배열 + 200</b> 이다.
     *
     * <p>🔴 로그인 사용자가 실행한 것만 낸다(TSK-4249 E1·E2 — {@code excutr_id = 나}, 유형과 무관).
     * 정책 시뮬레이션 화면의 저장 목록과 같은 소유 규칙이다(행 집합은 카드 조건 때문에 다르다). 실행자는 화면이 넘기지 않고 세션에서 정한다.
     */
    @Operation(summary = "최근 분석 실행",
            description = "분석 실행 이력을 실행일시(excn_dt) 내림차순으로 상위 N건 반환. 분석유형·상태는 TB_COM_CD 조인으로 코드명 포함. "
                    + "anlsTypeCd 를 주면 그 실행유형 1종으로 좁힌다(미전송 시 분석 화면용 5종). "
                    + "로그인 사용자가 실행한 것만(excutr_id = 나) 반환한다 — 미인증은 401.")
    @GetMapping("/analyses")
    public List<AnalysisVO> analyses(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(required = false) String anlsTypeCd) {
        // 🔴 TSK-4249 항목 3 — 내가 실행한 것만. 미인증은 401(판정은 CurrentUserResolver 한 곳)
        return dashboardPanelService.getRecentAnalyses(limit, anlsTypeCd, currentUser.resolveUserId(user));
    }

    /**
     * 최근 리포트. TB_RPTP 를 생성일시(crt_ymd) 내림차순으로 top N 반환(상태명 포함).
     *
     * <p>{@code crtType}(생성구분, {@code RPTP_CRT_TYPE_CD}) 을 주면 그 생성구분으로 좁힌다
     * (대시보드 보고서 카드 = {@code MANUAL} 직접 생성분. TSK-4169). 미전송·빈 값이면 종전대로
     * 전체(자동+직접)이며, 미등록 코드를 줘도 오류가 아니라 <b>빈 배열 + 200</b> 이다
     * ({@code anlsTypeCd} 와 같은 규약).
     */
    @Operation(summary = "최근 리포트",
            description = "리포트 생성 이력을 생성일시(crt_ymd) 내림차순으로 상위 N건 반환. 상태는 TB_COM_CD 조인으로 코드명 포함. "
                    + "crtType(생성구분 RPTP_CRT_TYPE_CD — AUTO=자동 · MANUAL=직접 · AI=AI 생성)을 주면 그 생성구분으로 좁힌다 "
                    + "— 미전송·빈 값이면 전체(자동+직접), 미등록 코드면 빈 배열(200).")
    @GetMapping("/reports")
    public List<ReportVO> reports(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(required = false) String crtType) {
        // 🔴 TSK-4202 요청 ① — 직접·AI 는 내 것만. 자동 생성분은 배포 부서·ALL·보고서 관리 메뉴 보유자(TSK-4257). 미인증은 401(D5)
        return dashboardPanelService.getRecentReports(limit, crtType, currentUser.resolveUserId(user));
    }

    /**
     * AI 추천 질문. TB_FAQ 중 노출 대상(expsr_yn=true)을 노출순서(expsr_seq) 오름차순으로 top N 반환.
     */
    @Operation(summary = "AI 추천 질문",
            description = "노출 대상(expsr_yn=true) FAQ 질문을 노출순서(expsr_seq) 오름차순으로 상위 N건(기본 3) 반환.")
    @GetMapping("/ai-suggestions")
    public List<SuggestionVO> aiSuggestions(@RequestParam(defaultValue = "3") int limit) {
        return dashboardPanelService.getAiSuggestions(limit);
    }

    /**
     * 데이터 최신 갱신 — 수집 배치 4종의 마지막 <b>성공</b> 실행 시각 (TSK-4169). 파라미터 없음.
     *
     * <p>🔴 {@code GET /api/dashboard/data-status} 와 <b>다른 사실</b>이다 — 그쪽은 민원이 언제
     * <b>발생</b>했나({@code tb_cvlcpt.ocrn_dt}), 이쪽은 원천을 언제 <b>수집</b>했나
     * ({@code batch.tb_btch_excn_hstry})다. 화면 상단 {@code 최신 갱신} 표기가 이 API 를 쓰고,
     * {@code data-status} 는 집중구역 카드 기준일이 계속 쓴다. 둘을 합치지 않는다.
     *
     * <p>🔴 {@code items} 는 <b>항상 4행</b>(민원 → 단속 → 공영주차장 현황 → 이용률)이며, 한 번도
     * 안 돈 잡은 {@code lastExcnDt: null} 이다 — 행 자체가 빠지지는 않는다.
     */
    @Operation(summary = "데이터 최신 갱신",
            description = "수집 배치 4종(민원·단속·공영주차장 현황·공영주차장 이용률)의 마지막 성공(COMPLETED) 실행 시각. "
                    + "items 는 항상 4행이며(순서 고정), 한 번도 안 돈 잡은 lastExcnDt=null 이다. "
                    + "latestDt 는 null 이 아닌 값 중 최댓값(전부 null 이면 null). "
                    + "⚠️ data-status(민원 발생일)와 다른 사실이다 — 이쪽은 배치 실행일이다.")
    @GetMapping("/data-freshness")
    public DataFreshnessVO dataFreshness() {
        return dashboardPanelService.getDataFreshness();
    }
}
