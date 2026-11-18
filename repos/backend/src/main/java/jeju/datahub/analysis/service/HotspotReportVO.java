package jeju.datahub.analysis.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 집중구역 분석 보고서 응답 루트. (POST /api/analysis/hotspot/reports 201 · GET 단건 스냅샷)
 *
 * <p><b>저장된 스냅샷을 그대로 돌려준다</b> — 프론트는 이 응답 하나로 문서를 그린다(D3).
 * 그래서 화면이 다시 조립하지 않도록 서버 산출 헤더 4칸({@link #rptpNm}·{@link #regionNm}·
 * {@link #anlsPrdExpln}·{@link #crtYmd})을 값으로 내린다 — 3단 지역 규칙·기간 표기가 화면에
 * 복제되면 서버와 조용히 갈린다({@code HotspotReportNaming} 이 그 규칙의 유일한 출처다).
 *
 * <p>{@link #weights}·{@link #zones} 는 <b>타입을 새로 만들지 않고</b> 분석 응답의 것을 그대로 싣는다 —
 * 가중치 타입의 정본은 TSK-4020({@link AnalysisWeightVO}), 구역 타입의 정본은
 * TSK-4032({@link HotspotZoneVO}) 다. 보고서 전용 타입을 따로 두면 분석 화면과 보고서 화면이
 * 같은 값을 서로 다른 모양으로 받게 된다.
 *
 * <p>🔴 <b>POST 와 GET 의 {@link #zones} 는 비대칭</b>이다(R11). GET(스냅샷 복원)은
 * {@code TB_ANLS_RSLT.rslt_json} 에 담긴 7칸만 갖고 있어 {@code emdNm}·{@code zoneNmSrc} 가
 * {@code null} 이다(D15 — 이관된 기존 보고서와 계약을 같게 두려고 저장하지 않는다.
 * 종전 결과 표에서 옮겨 온 행은 좌표도 {@code NUMERIC(12,10)} 반올림값이다).
 * 그래도 <b>{@code @JsonInclude(NON_NULL)} 을 붙이지 않는다</b> — 공용 VO 라 POST 응답의 {@code emdNm} 이 정당하게 null 인 경우까지 키가 사라져
 * 프론트의 {@code emdNm: string | null} 계약이 흔들린다.
 */
@Schema(description = "집중구역 분석 보고서")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotReportVO {

    /** 보고서아이디 (rptp_id — TB_RPTP PK) */
    @Schema(description = "보고서아이디", example = "100")
    private Long rptpId;

    /** 실행아이디 (excn_id — TB_ANLS_EXCN PK. 순위 15행이 이 값으로 묶인다) */
    @Schema(description = "분석실행아이디", example = "100")
    private Long excnId;

    /** 보고서명. {@code 집중 구역 분석 보고서 {regionNm}} ({@code HotspotReportNaming} 이 조립) */
    @Schema(description = "보고서명", example = "집중 구역 분석 보고서 제주시 연동")
    private String rptpNm;

    /**
     * 지역명. 서버 3단 규칙 — {@code 전체 지역} / {@code 제주시 전체} / {@code 제주시 연동} (D5·D12).
     *
     * <p>DB 컬럼이 아니다 — POST 는 {@code HotspotReportNaming} 이, GET 은
     * {@code ReportMapper.selectReportById} 의 CASE 가 만든다. <b>두 경로가 같은 문자열을 내야 한다.</b>
     */
    @Schema(description = "지역명 (전체 지역 | {시군구} 전체 | {시군구} {읍면동})", example = "제주시 연동")
    private String regionNm;

    /** 분석기간설명 (anls_prd_expln — {@code yyyy.MM.dd ~ yyyy.MM.dd}, 시작=종료면 한 번만) */
    @Schema(description = "분석기간설명", example = "2025.12.01 ~ 2025.12.31")
    private String anlsPrdExpln;

    /** 파일형식 (file_fmt — PDF | DOCX | XLSX. 요청값을 그대로 저장·반환한다) */
    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF")
    private String fileFmt;

    /** 생성일시 (crt_ymd — 보고서 생성 시각. 문서 부제의 생성일 출처) */
    @Schema(description = "생성일시", example = "2026-08-27T14:03:11")
    private LocalDateTime crtYmd;

    /**
     * 계산에 실제로 쓴 가중치 목록. 분석 응답({@code HotspotAnalysisResultVO#getWeights()})을 그대로 싣는다.
     *
     * <p>문서 부제 {@code 민원 60% / 단속 40%} 의 출처다 — 보고서는 <b>생성 시점의 가중치</b>를 담아야
     * 하므로, 화면이 가중치 API 를 따로 부르면(그 사이 설정이 바뀌면) 본문과 부제가 어긋난다.
     */
    @Schema(description = "계산에 실제로 쓴 가중치 목록")
    private List<AnalysisWeightVO> weights = new ArrayList<>();

    /**
     * 가중치 조정 여부 (Y | N). 스냅샷은 {@code anls_param_json} 에서 읽는다 (TSK-4306 D16).
     *
     * <p>🔴 <b>필드 초기값을 두지 않는다</b> — 키가 없는 구 스냅샷은 {@code null} 이어야 한다.
     * 초기값 {@code N} 을 두면 조정 여부를 모르는 과거 보고서가 "조정 안 함" 으로 읽힌다.
     */
    @Schema(description = "가중치 조정 여부 (Y | N). 이 값을 저장하기 전 보고서는 null", example = "N", nullable = true)
    private String wgvlAjmtYn;

    /**
     * 점수 산정 방식 (TSK-4321 D7). 조회 결과 1위 = 100 인 상대 점수면 {@code LIST_MAX} 다.
     *
     * <p>🔴 <b>필드 초기값을 두지 않는다</b> — 키가 없는 구 스냅샷은 {@code null} 이어야 한다.
     * 프론트가 이 값으로 산정 기준 문구를 가르므로, 초기값을 두면 과거 보고서가 새 문구로 읽힌다.
     */
    @Schema(description = "점수 산정 방식 (LIST_MAX = 조회 결과 1위 100 환산). 이 값을 저장하기 전 보고서는 null",
            example = "LIST_MAX", nullable = true)
    private String anlsMthd;

    /**
     * 생성 당시의 등급 비율 (TSK-4321). 문서 {@code 점수 산정 기준} 문구의 출처다.
     * 🔴 비율은 설정값이라 저장해 둔 값을 쓴다 — 구 스냅샷은 {@code null} 이다.
     */
    @Schema(description = "생성 당시 등급 비율. 이 값을 저장하기 전 보고서는 null", nullable = true)
    private List<GradeCriterionVO> gradeCriteria;

    /**
     * 생성 당시 분석 계수 적용 내역 (TSK-4325 D10) — 문서에는 그리지 않고 저장·전달만 한다.
     * 🔴 <b>필드 초기값을 두지 않는다</b> — 이 값을 저장하기 전 보고서는 {@code null} 이다.
     */
    @Schema(description = "생성 당시 분석 계수 적용 내역 {ajmtYn, vrbls:[…]}. 이 값을 저장하기 전 보고서는 null", nullable = true)
    private Map<String, Object> vrblAjmt;

    /** 집중구역 목록. 종합점수 내림차순 <b>주의(40점) 이상</b>만 (생성 시점 기준 — TSK-4172) */
    @Schema(description = "집중구역 목록 (종합점수 내림차순)")
    private List<HotspotZoneVO> zones = new ArrayList<>();

    /**
     * 엑셀 재계산 데이터 (TSK-4338 D7-1) — 후보 구역 전부 + 상수. 형식과 무관하게 새 보고서마다 저장된다.
     * 🔴 <b>필드 초기값을 두지 않는다</b> — 이 값을 저장하기 전 보고서는 {@code null} 이다(D7-2 — 프론트가 Excel 버튼을 끈다).
     */
    @Schema(description = "엑셀 재계산 데이터 (후보 구역 + 상수). 이 값을 저장하기 전 보고서는 null", nullable = true)
    private HotspotWorkbookVO wbk;
}
