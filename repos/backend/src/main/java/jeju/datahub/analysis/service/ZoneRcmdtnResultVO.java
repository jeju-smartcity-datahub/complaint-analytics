package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천(요금제 적용) 분석 결과 1건 — 선택 지역 + 주차장 순위 (5).
 *
 * <p>화면이 답하는 질문은 <b>"이 읍면동에서 유료화한다면 어느 주차장부터 해야 하나"</b> 다.
 * 그래서 <b>주력은 {@link #lots}</b>(주차장 순위)이고 {@link #zone} 은 선택한 읍면동의 위치 정보다.
 *
 * <h2>(2026-11-11 사용자 결정) — 읍면동 요금제 대상 판정을 제거했다</h2>
 * 종전 {@code zone} 에 실리던 E0·필요 면수·충족률·추천유형({@code FEE}/{@code SHARE})·판정 불가 사유와
 * {@code lots[].reachNeed} 는 <b>서버에서도 계산하지 않는다</b>. 순위(검토지수·종합지수·단계·{@code rnk}·
 * {@code cumCpcty})는 판정과 무관해 그대로다. 🔴 구 보고서 스냅샷({@code rslt_json})에는 옛 키가 남아 있어
 * {@link Zone}·{@link ZoneRcmdtnLotVO}·{@link ZoneRcmdtnCrtrVls} 에 {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * 를 건다(전역 설정에 기대지 않는다 — 되읽기 500 방지).
 *
 * <h2>🔴 {@link #asgn} 블록이 필수다</h2>
 * 배정률 30% 는 <b>"민원 70% 는 이 주차장들로 설명되지 않는다"</b> 는 뜻이다. 이걸 빼면 순위가
 * 전체를 설명하는 것처럼 보인다(§0-0 제약 2 — 이도이동 민원 중앙값 거리가 474m 다).
 *
 * <p>🔴 <b>주차장별 "효과(감소 건수)" 는 어디에도 없다.</b> {@code E0}(불법주차 추정)가 읍면동
 * 단위라 주차장 단위 효과 산식이 성립하지 않는다 — 이 분석은 <b>순위까지만</b> 말한다
 * ({@link ZoneRcmdtnLotVO} 주석과 같은 근거).
 */
@Schema(description = "구역추천 분석 결과 (선택 읍면동 + 주차장 순위)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnResultVO {

    /**
     * 분석실행 ID. {@code parking.TB_ANLS_EXCN.excn_id} — <b>API 계층이 채운다.</b>
     *
     * <p>계산기는 이 값을 모른다({@code null}) — 실행이력 적재는 API 경계의 책임이다.
     */
    @Schema(description = "분석실행 ID (실행이력 적재 후 채워진다)", example = "24", nullable = true)
    private Long excnId;

    /**
     * 실행 범위. {@code "EMD"}(읍면동 모드) | {@code "SGG"}(시군구 모드) (하위 호환 추가).
     *
     * <p>🔴 <b>채우는 층은 서비스다</b>({@code ZoneRcmdtnServiceImpl.selectZoneRcmdtn}) — 계산기는 판정·순위만
     * 한다. 프론트·보고서 템플릿이 {@code zone == null} 로 모드를 추측하지 않게 하는 칸이다.
     * ⚠️ 이 칸이 생기기 전의 스냅샷에는 없다(null) — 프론트가 {@code EMD} 로 간주한다.
     */
    @Schema(description = "실행 범위 (EMD | SGG)", example = "EMD", allowableValues = {"EMD", "SGG"})
    private String scope;

    /**
     * 시군구명. 시군구 모드는 요청의 정규화된(trim) 값, 읍면동 모드는 {@code zone.sggNm} 과 같은 값.
     */
    @Schema(description = "시군구명 (시군구 모드는 요청값 · 읍면동 모드는 zone.sggNm 과 같다)", example = "제주시")
    private String sggNm;

    /**
     * 분석 방법. 값은 {@code "RVW_SCR"}(요금제 검토지수 — 시군구 고정 기준값 원점수 · 우선도 순번 ·
     * 우선도 100/60 단계) 하나다(생활권 {@code anlsMthd = 'SHRT_RT'} 선례 키 재사용).
     *
     * <p>🔴 <b>필드 초기값을 두지 않는다.</b> 보고서 조회는 {@code rslt_json} 을 이 VO 로 되읽는다
     * ({@code ZoneRcmdtnReportServiceImpl}). 초기값이 있으면 이 키가 없는 구 스냅샷(건수 순 · min-max 색)도
     * {@code RVW_SCR} 로 읽혀 새 단계로 잘못 그려진다. 값은 계산기 {@code assemble}·{@code assembleSgg} 가
     * 새 결과를 만들 때만 넣는다.
     */
    @Schema(description = "분석 방법 (RVW_SCR = 요금제 검토지수 · 구 스냅샷은 null)", example = "RVW_SCR",
            nullable = true)
    private String anlsMthd;

    /** 판정에 <b>실제로 적용된</b> 기준값. 🔴 설정이 바뀌면 결과가 달라지므로 응답에 굳힌다 */
    @Schema(description = "적용된 기준값")
    private ZoneRcmdtnCrtrVls crtrVls;

    /**
     * 분석 계수 적용 내역 — {@code {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}}.
     *
     * <p>조회 응답에만 채운다. 🔴 <b>필드 초기값을 두지 않는다</b> — 보고서 본문은 {@code rslt_json} 을 이 VO 로
     * 되읽으므로 이 칸이 {@code null} 이고, 보고서의 적용 내역은 응답 머리({@link ZoneRcmdtnReportVO#getVrblAjmt()})에 있다.
     */
    @Schema(description = "분석 계수 적용 내역 {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]} — "
            + "보고서 본문에서는 null (응답 머리 vrblAjmt 를 본다)", nullable = true)
    private Map<String, Object> vrblAjmt;

    /**
     * 경고 코드 목록. 정상 경로에서는 <b>빈 배열</b>이다.
     *
     * <p>🔴 {@code COEF_FALLBACK} 이 보이면 설정 행이 지워졌거나 {@code use_yn='N'} 이라 상수
     * 폴백으로 계산했다는 뜻이다 — <b>평시 경로가 아니다</b>. 같은 코드는 한 번만 담는다
     * (계수 7종이 같은 목록을 공유하므로 중복을 막지 않으면
     * {@code ["COEF_FALLBACK","COEF_FALLBACK",…]} 이 된다).
     */
    @Schema(description = "경고 코드 목록 (정상이면 빈 배열)")
    private List<String> warnings = new ArrayList<>();

    /** 선택한 읍면동(코드·이름·시군구). 시군구 모드는 {@code null} */
    @Schema(description = "선택한 읍면동 (시군구 모드는 null)", nullable = true)
    private Zone zone;

    /** 배정 커버리지 — 🔴 "순위가 민원의 몇 %를 설명하는가". 시군구 모드는 {@code null} */
    @Schema(description = "배정 커버리지 (시군구 모드는 null)", nullable = true)
    private Asgn asgn;

    /**
     * 후보 주차장 순위(추세 반영 검토지수 내림차순). 후보가 없는 동은 <b>빈 목록</b>이다.
     *
     * <p>⚠️ 후보 0개소는 빈 화면이 아니라 <b>결론</b>이다 — "유료화할 공영주차장이 없음 → SHARE
     * 검토" 로 안내한다.
     */
    @Schema(description = "후보 주차장 순위 (추세 반영 검토지수 내림차순 → 배정 민원 → 면수 → 주차장 ID)")
    private List<ZoneRcmdtnLotVO> lots = new ArrayList<>();

    /**
     * 범위 안 <b>후보 밖</b> 주차장 — 지도 회색 핀 전용. 순위·배정이 없고 결과 패널 목록에도
     * 나오지 않는다. 두 모드(EMD·SGG) 모두 내린다. 없으면 <b>빈 목록</b>이다.
     *
     * <p>🔴 {@link #lots} 와 겹치지 않는다 — 같은 범위·조건에서 후보 조건만 뒤집은 목록이다.
     *
     * <p>🔴 <b>보고서 스냅샷·실행 결과 JSON 에는 넣지 않는다</b> — 둘 다 키를 골라 담으므로
     * 이 필드는 자동으로 빠진다. 지도 참고용이라 문서에 남길 이유가 없다.
     */
    @Schema(description = "후보 밖 주차장 (지도 참고용 · 순위 없음)")
    private List<ZoneRcmdtnExclLotVO> exclLots = new ArrayList<>();

    /**
     * 요금제 엑셀 재계산 데이터. <b>보고서 경로({@code selectZoneRcmdtnWithWorkbook})만</b> 채운다.
     *
     * <p>🔴 <b>필드 초기값을 두지 않는다</b>(이 값을 저장하기 전 보고서는 {@code null}). 🔴 NON_NULL 은 <b>필드 단위</b>다 —
     * 전역 NON_NULL 설정이 없어 안 붙이면 조회 GET 에 {@code "wbk": null} 키가 생긴다. 클래스 단위로 걸면 다른 null 칸까지 사라진다.
     */
    @Schema(description = "요금제 엑셀 재계산 데이터 (보고서 경로만 · 조회 응답에는 없다)", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ZoneRcmdtnFeeWorkbookVO wbk;

    /**
     * 읍면동 1건(위치 3필드). {@code /zones}(읍면동 목록) 응답의 <b>배열 원소</b>이기도 하다 —
     * 그래서 {@link ZoneRcmdtnResultVO} 바깥에서도 쓸 수 있게 {@code static} 중첩 클래스다.
     *
     * <p>판정 7필드({@code cvlcptMmAvg}·{@code crtrIlglCnt}·{@code needCpcty}·
     * {@code ableCpcty}·{@code stsfRt}·{@code rcmdtnTypeCd}·{@code rsnCd})를 지웠다. 🔴 구 스냅샷의 그 키는 무시한다.
     */
    @Schema(description = "읍면동 1건 (코드·이름·시군구)")
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    public static class Zone {

        /** 읍면동 코드(8자리) */
        @Schema(description = "읍면동 코드", example = "50110104")
        private String emdCd;

        /** 읍면동명 */
        @Schema(description = "읍면동명", example = "이도이동")
        private String emdNm;

        /** 시군구명. 🔴 시군구를 가로지르는 순위를 만들지 않으므로 화면이 이것으로 나눈다 */
        @Schema(description = "시군구명", example = "제주시")
        private String sggNm;
    }

    /**
     * 배정 커버리지 블록. 🔴 <b>순위가 민원 전체를 설명하지 않는다</b>는 사실을 숫자로 남긴다.
     *
     * <p>이도이동 실측 — 민원 3,110건 중 후보 주차장 300m 안에 배정된 것은 933건(30%)뿐이고
     * 민원 중앙값 거리가 474m 다. 절반이 500m 밖이라 유료화로 손댈 수 없다(§0-0 제약 2).
     */
    @Schema(description = "배정 커버리지")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    public static class Asgn {

        /** 조회 구간 민원 총건수(미분류 99 제외) = {@code 민원 월평균 × 개월수} */
        @Schema(description = "조회 구간 민원 총건수", example = "3110")
        private long cvlcptTot;

        /** 후보 주차장에 <b>최근접 배정</b>된 민원 합. 🔴 중복이 없어 총건수를 넘지 않는다 */
        @Schema(description = "배정된 민원 합", example = "933")
        private long cvlcptAsgn;

        /** 배정률(%) = {@code 배정 ÷ 총건수 × 100}. 총건수가 0 이면 {@code null} */
        @Schema(description = "배정률 (%)", example = "30.0", nullable = true)
        private BigDecimal asgnRt;
    }
}
