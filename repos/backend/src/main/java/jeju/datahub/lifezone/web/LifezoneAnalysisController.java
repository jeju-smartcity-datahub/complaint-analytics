package jeju.datahub.lifezone.web;

import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.lifezone.service.LifezoneAnalysisRequest;
import jeju.datahub.lifezone.service.LifezoneAnalysisResultVO;
import jeju.datahub.lifezone.service.LifezoneAnalysisService;
import jeju.datahub.lifezone.service.LifezoneCngstRequest;
import jeju.datahub.lifezone.service.LifezoneCngstResultVO;
import jeju.datahub.lifezone.service.LifezoneCngstService;
import jeju.datahub.lifezone.service.LifezoneSupplyRequest;
import jeju.datahub.lifezone.service.LifezoneSupplyResultVO;
import jeju.datahub.lifezone.service.LifezoneSupplyService;
import jeju.datahub.lifezone.service.LifezoneTargetVO;
import lombok.RequiredArgsConstructor;

/**
 * 생활권 시뮬레이션 REST 컨트롤러. (분석 화면 "생활권 시뮬레이션" — TSK-4088)
 *
 * <p><b>GET 인 이유</b> — 분석 결과를 저장하지 않는 순수 조회다(D4·D12). 조건이 URL 에 남아
 * 링크 공유·재현이 쉽고, 응답이 조건·격자크기·구간 경계를 함께 되비추므로 응답만으로 결과를
 * 설명할 수 있다.
 *
 * <p>{@code @RequestMapping("/api/analysis")} 를 {@code HotspotAnalysisController} 와 공유하지만
 * 하위 경로가 갈린다({@code /hotspot} vs {@code /lifezone}) — 충돌하지 않는다.
 *
 * <p>이 경로를 덮는 전역 예외 핸들러가 없으므로(agent 도메인 한정 advice 1건은 {@code assignableTypes}
 * 로 격리돼 여기 오지 않는다 — TSK-4121) 잘못된 입력은 바인딩 검증
 * ({@code @Valid} → 400)과 서비스의 {@link ResponseStatusException} 두 겹으로 낸다.
 *
 * <p>⚠️ 쿼리 파라미터에 {@code @Min}/{@code @Max} 를 직접 붙이고 클래스에 {@code @Validated} 를
 * 다는 방식은 <b>쓰지 않는다</b> — 핸들러가 없어 {@code ConstraintViolationException} 이 500 으로
 * 나간다. 제약은 {@link LifezoneAnalysisRequest} 안에 두고 {@code @Valid} 로 발동시킨다.
 */
@Tag(name = "생활권 시뮬레이션",
        description = "격자 기반 민원·수급·혼잡도 공간 분석 (조회 전용)")
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class LifezoneAnalysisController {

    private final LifezoneAnalysisService lifezoneAnalysisService;
    private final LifezoneSupplyService lifezoneSupplyService;
    private final LifezoneCngstService lifezoneCngstService;

    /**
     * 🔴 보고서 경로 전용 칸 {@code vrblVlsMap} 을 쿼리로 채우지 못하게 막는다 (TSK-4325 Step 1).
     *
     * <p>세터를 패키지 전용으로 두었지만 {@code vrblVlsMap[KEY]=값} 은 게터로 Map 을 꺼내 넣으려다
     * {@code InvalidPropertyException} → <b>500</b> 이 난다(2026-11-06 실측). 금지 필드는 바인더가 조용히 버린다 —
     * 값은 무시되고 함께 온 {@code vrblVls} 가 적용된다. 🔴 수급·혼잡도 요청에만 건다 — {@code /lifezone}
     * (민원 건수) 계약은 바꾸지 않는다(D12). 이름은 {@code @ParameterObject} 모델 속성의 기본 이름(클래스명)이다.
     */
    @InitBinder({ "lifezoneSupplyRequest", "lifezoneCngstRequest" })
    void disallowVrblVlsMap(WebDataBinder binder) {
        binder.setDisallowedFields("vrblVlsMap*");
    }

    /**
     * 생활권 격자 분석.
     *
     * <p>🔴 {@code @Valid} 를 빼면 {@link LifezoneAnalysisRequest} 의 형식·범위 제약이 <b>통째로</b>
     * 발동하지 않는다. 서비스도 값 검증을 하지만(Step 11 보고서 저장이 직접 호출한다) 두 겹을
     * 유지한다 — 컨트롤러 쪽이 없으면 오류 메시지가 필드명을 잃는다.
     *
     * <p>결과 0건은 오류가 아니라 <b>빈 {@code cells}·{@code ranking} + 200</b> 이다 — 조회 기간에
     * 데이터가 없는 정상 상태이며, 이때도 원천 보유 구간({@code dataBgngYmd}·{@code dataEndYmd})을
     * 함께 내린다.
     */
    @Operation(summary = "생활권 격자 분석",
            description = "기간(bgngYmd·endYmd, YYYYMMDD)의 민원 좌표를 격자(1000|500|250|100 m)에 공간조인해 "
                    + "격자별 건수를 집계하고, 조회 결과의 최대값까지를 로그 축에서 5등분해 등급을 매긴다. "
                    + "cells 는 값>0 격자 전량이고 ranking 은 심각·경고·주의 셀 전부(limit 지정 시 그 값까지 · 생략 = 상한 없음)이며, "
                    + "구역명 POI 조회는 ranking 행에만 적용한다(요청당 외부 조회 상한을 넘는 행은 폴백 이름). "
                    + "sggNm·emdCd 는 생략하면 전 지역이며(빈 값은 400), 미구현 분석유형(SPLY_DMND·CNGST)은 400 이다. "
                    + "가중치는 배타 선택이라 weights 는 항상 빈 배열이다. 결과가 0건이어도 200 이다.")
    @GetMapping("/lifezone")
    public LifezoneAnalysisResultVO lifezone(@ParameterObject @Valid LifezoneAnalysisRequest request) {
        return lifezoneAnalysisService.analyze(request);
    }

    /**
     * 생활권 <b>수급</b> 격자 분석. (TSK-4089)
     *
     * <p>🔴 <b>{@code /lifezone} 과 핸들러를 나눈 이유</b> — 같은 화면의 다른 탭이지만 파이프라인이
     * 전혀 다르다. 격자를 {@code TB_GRID} 에서 읽지 않고 EPSG:5179 좌표로 <b>계산</b>하며
     * (마스터에 200·300m 가 없다), 기간 기준 컬럼도 {@code ocrn_dt} 가 아니라 <b>{@code dclr_ymd}</b>
     * 다(원천이 +365일 이동된 값을 준다). 결과도 값 경계 5등급이 아니라 🔁 <b>부족률 등간격 5등급</b>
     * (TSK-4258 — 종전 범주형 4사분면)이라 요약·범례·셀 지표가 겹치지 않는다. 기본 핸들러로 {@code anlsTrgtCd=SPLY_DMND} 를 보내면
     * <b>400</b> 이며 이 경로를 안내한다.
     *
     * <p>🔴 응답의 {@code disclaimer} 는 <b>화면에 상시 표기</b>해야 한다 — 이 산출물은
     * "주차 부족 면수" 가 아니라 <b>공영주차 공급 대비 민원 발생량</b>이다(주차 수요 자료가 없어
     * 확보율을 계산할 수 없다). 문구 규칙은 인계서 §4-4.
     *
     * <p>결과 0건은 오류가 아니라 <b>빈 {@code cells} + 200</b> 이다.
     */
    @Operation(summary = "생활권 수급 격자 분석",
            description = "기간(bgngYmd·endYmd, YYYYMMDD)의 민원 발생일시(ocrn_dt) 기준으로 좌표를 "
                    + "EPSG:5179 정방격자(1000|500|250|100 m — 민원건수와 같다)에 배정해 격자별 주차 민원(유형 01~05) 건수와 "
                    + "공영주차면 수(PBLPKLT·ONSTR·VACLOT)를 집계한다. 월평균 민원 5건 이상 격자만 평가하며"
                    + "(minMnthAvgCvlcpt), 부족률 = 민원 ÷ (공영주차면 + 민원)을 80/60/40/20% 경계(아래 경계 포함)로 "
                    + "심각·경고·주의·보통·낮음(grade) 5단계로 판정하고, 순위(rnk, 1..N — 부족률 내림차순·민원 내림차순)는 "
                    + "리스트 순서에만 쓴다. "
                    + "cells 는 기준 통과 격자 전량이며 rnk 오름차순이다. 100m 격자에는 해석 주의 안내(gridNotice)가 붙는다. "
                    + "anlsMthd 는 항상 SHRT_RT 이다. "
                    + "vrblVls(JSON 문자열)로 SPLY_MIN_MNTH_AVG_CVLCPT·SPLY_GRD_BNDRY1~4 를 일시 조정할 수 있다 — 설정은 바꾸지 않으며, "
                    + "목록 밖 코드 · 범위 밖 · 소수 · 경계 역전(설정값과 합친 결과)이면 400 이다. 적용 내역은 응답 vrblAjmt 다. "
                    + "sggNm·emdCd 는 생략하면 전 지역이고(빈 값은 400), 결과가 0건이어도 200 이다. "
                    + "⚠️ 주차 부족 면수가 아니라 공영주차 공급 대비 민원 발생량이다 — 응답의 "
                    + "disclaimer 를 화면에 상시 표기한다.")
    @GetMapping("/lifezone/supply")
    public LifezoneSupplyResultVO supply(@ParameterObject @Valid LifezoneSupplyRequest request) {
        return lifezoneSupplyService.analyze(request);
    }

    /**
     * 생활권 <b>혼잡도</b>(주차 혼잡 지수) 격자 분석. (TSK-4090)
     *
     * <p>🔴 <b>핸들러·응답 VO 를 민원건수와 나눈 이유</b> — 격자 기준이 공영주차 공급&gt;0 칸이고
     * (수급과 같은 EPSG:5179 계산 격자), 지표가 혼잡 점수({@code 지수×100} · 상한 없음 · {@code anlsVl} = 점수×100)이며,
     * 불법주차 근거(단속·민원)가 없는 칸과 면수 하한 미만 칸을 결과에서 빼는 판정이 있다. 응답은
     * {@link LifezoneCngstResultVO} 로 내리되 민원건수 응답의 공통 필드를 <b>같은 이름</b>으로 싣는다
     * (프론트가 민원건수 컴포넌트를 재사용한다).
     *
     * <p>🔴 {@code over1GridCnt} 는 데이터가 아니라 <b>설정값({@code occUsedVl})의 함수</b>라 두 값을
     * 함께 내린다. {@code totGridCnt} = 5등급 합 = {@code grdTrgtGridCnt}(R10 성립)이고,
     * {@code noEnfGridCnt}·{@code refGridCnt} 는 각각 근거 없음·면수 하한 미만으로 제외된 격자 수다
     * (2026-10-08 — 둘 다 {@code cells} 에 없다. 겹치면 noEnf 로 센다).
     *
     * <p>{@code clsfMthd} 생략 = {@code CNGST_ABSOLUTE}(TSK-4227 — 1종 확정). 목록 밖 분류방법·격자크기·366일 초과 기간은 400 이다.
     * 결과 0건은 오류가 아니라 <b>빈 {@code cells}·{@code ranking} + 200</b> 이다.
     */
    @Operation(summary = "생활권 혼잡도 격자 분석",
            description = "기간(bgngYmd·endYmd, YYYYMMDD — 최대 366일)의 단속(crdn_dt)·민원(ocrn_dt) 좌표를 "
                    + "EPSG:5179 정방격자(1000|500|250|100 m)에 배정하고, 공영주차 공급>0 격자마다 "
                    + "주차 혼잡 지수 H = occ + 추정 초과 주차량 / 면수 를 산정해 혼잡 점수 = H×100 으로 환산한다 "
                    + "(상한 없음 · anlsVl = 점수×100 정수 · 100점 초과 = 수요가 면수 초과). "
                    + "불법주차 근거(단속·민원)가 없어 추정량이 0 인 칸과 면수가 MIN_CPCTY_VL 미만인 칸은 "
                    + "등급 계산 전에 cells·요약·순위에서 모두 제외한다. "
                    + "등급은 점수에 clsfMthd(CNGST_ABSOLUTE 1종 — 생략 시 기본)로 매기며 목록 밖은 400 이다. "
                    + "요약은 totGridCnt(= 5등급 합 = grdTrgtGridCnt) · noEnfGridCnt(근거 없음 제외 격자 수) · "
                    + "refGridCnt(면수 하한 미만 제외 격자 수 — 겹치면 noEnf 로 센다) · over1GridCnt(100점 초과 격자 수 — occUsedVl 과 함께 읽는다). "
                    + "vrblVls(JSON 문자열)로 PCSHR_CRTR_VL·CNGST_GRD_BNDRY1~4·DSCL_MLTP·FIX_WGVL·GEO_CVRG_CRCT_VL·"
                    + "CVLCPT_WGVL·MIN_CPCTY_VL 을 일시 조정할 수 있다 — 설정은 바꾸지 않으며, 목록 밖 코드 · 범위 밖 · "
                    + "경계 역전(설정값과 합친 결과)이면 400 이다. 적용 내역은 응답 vrblAjmt 다. "
                    + "sggNm·emdCd 는 생략하면 전 지역이고(빈 값은 400), 결과가 0건이어도 200 이다. "
                    + "⚠️ 실측 점유율이 아니라 단속·민원 기반 대리지표다.")
    @GetMapping("/lifezone/congestion")
    public LifezoneCngstResultVO congestion(@ParameterObject @Valid LifezoneCngstRequest request) {
        return lifezoneCngstService.analyze(request);
    }

    /**
     * 분석유형 가용 목록.
     *
     * <p>설정 패널이 마운트 시 1회 부른다 — 진입 직후에는 분석을 돌리지 않으므로(D1) 세그먼트를
     * 그리는 데 필요한 정보만 따로 낸다({@code /api/regions} 와 같은 성격).
     */
    @Operation(summary = "분석유형 가용 목록",
            description = "생활권 분석유형 3종(CVLCPT_CNT · SPLY_DMND · CNGST)과 각각의 구현 여부·미구현 사유 · "
                    + "지원 격자크기 목록(gridSzs) · 기본 격자크기 · 조회 경로 접미사(anlsPathSfx). "
                    + "설정 패널이 세그먼트와 격자 탭을 그리고 호출 경로를 고르는 데 쓴다.")
    @GetMapping("/lifezone/targets")
    public List<LifezoneTargetVO> targets() {
        return lifezoneAnalysisService.selectTargets();
    }
}
