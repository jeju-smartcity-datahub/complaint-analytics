package jeju.datahub.lifezone.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 격자 분석 조회 요청. (GET /api/analysis/lifezone)
 *
 * <p><b>GET 인 이유</b> — 분석 결과를 저장하지 않는 순수 조회다(TSK-4088 D12 · TSK-4032 D7 과 동일).
 * 파라미터가 URL 에 남아 링크 공유·재현이 쉽다. 쿼리스트링을 이 클래스로 바인딩하므로
 * 컨트롤러에서 {@code @Valid} 를 빠뜨리면 아래 제약이 <b>통째로</b> 발동하지 않는다.
 *
 * <p>⚠️ <b>클래스에 {@code @Validated} 를 달거나 파라미터에 제약을 직접 붙이지 않는다</b> —
 * 전역 예외 핸들러가 없어 {@code ConstraintViolationException} 이 <b>500</b> 으로 나간다.
 * 필드 제약(형식)만 여기서 걸고, <b>값 검증은 서비스가 400 으로 던진다</b>:
 * <ul>
 *   <li>{@link #anlsTrgtCd} — 미지원 코드 · 미구현 유형(D2)</li>
 *   <li>{@link #gridSz} — {@code 1000/500/250/100} 외</li>
 *   <li>{@link #clsfMthd} — 그 유형의 허용 목록({@code LifezoneTarget#getClsfMthds()}) 밖</li>
 *   <li>{@link #sggNm}·{@link #emdCd} — <b>빈 문자열</b>(키를 아예 넣지 않는 것이 "전체"다)</li>
 *   <li>기간 역전 · 미등록 지역</li>
 * </ul>
 */
@Schema(description = "생활권 격자 분석 조회 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneAnalysisRequest {

    /**
     * 분석내용코드. 기본 {@code CVLCPT_CNT}.
     *
     * <p>{@link LifezoneTarget} 상수명과 같은 값이며 공통코드 {@code ANLS_TRGT_CD} 의
     * {@code cd_vl} 과도 같다(D15). 미지원 코드·미구현 유형은 <b>서비스가 400</b> 이다(D2).
     */
    @Schema(description = "분석내용코드 (CVLCPT_CNT | SPLY_DMND | CNGST)",
            example = "CVLCPT_CNT", defaultValue = "CVLCPT_CNT")
    private String anlsTrgtCd = LifezoneTarget.CVLCPT_CNT.name();

    /**
     * 격자크기(m). 기본 {@code 500}.
     *
     * <p>{@code TB_GRID} 가 보유한 4종({@code 1000}·{@code 500}·{@code 250}·{@code 100}) 중
     * 하나여야 한다 — 그 외는 <b>서비스가 400</b> 이다. 값이 작아질수록 그리는 셀이 급격히 늘어난다
     * (전 기간·전 지역 실측: 1km 341칸 → 100m 2,015칸).
     */
    @Schema(description = "격자크기(m) — 1000 | 500 | 250 | 100", example = "500", defaultValue = "500")
    private int gridSz = 500;

    /**
     * 등급 <b>분류방법</b>. 생략하면 <b>그 유형의 기본값</b>이다(민원건수 = {@code EQUAL_INTERVAL} 등간격).
     *
     * <p>{@link LifezoneClsfMthd} 상수명과 같은 값이며, 🔁 TSK-4090 부터 <b>유형마다 허용 목록이 다르다</b>
     * ({@code GET /api/analysis/lifezone/targets} 의 {@code clsfMthds}·{@code dfltClsfMthd}).
     * 🔁 TSK-4227 — 민원건수는 {@code EQUAL_INTERVAL} 1종이고, 그 밖의 값은
     * <b>서비스가 400</b> 이다(다른 검증과 같은 {@code ResponseStatusException}). 빈 값·생략은 유형 기본값이다.
     *
     * <p>🔴 <b>초기값이 {@code null} 이다</b> — 특정 상수로 두면 유형 기본값이 적용될 틈이 없다.
     *
     * <p>🔴 이름은 데이터표준사전을 따랐다 — <b>분류 {@code CLSF}</b> + <b>방법 {@code MTHD}</b>
     * (기존 {@code crdn_mthd} 선례). TSK-4227 로 유형별 1종 확정 — 경위는 {@link LifezoneClsfMthd}
     * 의 클래스 주석에 있다.
     */
    @Schema(description = "등급 분류방법 — 생략 시 유형 기본값(민원건수 EQUAL_INTERVAL). 허용 목록은 /targets 의 clsfMthds",
            example = "EQUAL_INTERVAL", nullable = true)
    private String clsfMthd;

    /** 분석 시작일 (YYYYMMDD, 필수) */
    @NotBlank(message = "bgngYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 시작일 (YYYYMMDD)", example = "20251201",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String bgngYmd;

    /** 분석 종료일 (YYYYMMDD, 필수 · 당일 포함) */
    @NotBlank(message = "endYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 종료일 (YYYYMMDD, 당일 포함)", example = "20251231",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String endYmd;

    /**
     * 시군구명(선택). 생략하면 전 지역.
     *
     * <p>키를 <b>아예 넣지 않는 것</b>이 "전체"다 — 빈 문자열은 400 이다(서비스 판정).
     */
    @Schema(description = "시군구명 (생략 = 전 지역)", example = "제주시")
    private String sggNm;

    /**
     * 읍면동코드(선택). 생략하면 선택 시군구 전체.
     *
     * <p>지역 필터는 공간연산이 아니라 원천의 {@code emd_cd} 로 <b>격자 집계 전에</b> 거른다.
     */
    @Schema(description = "읍면동코드 (생략 = 시군구 전체)", example = "50110560")
    private String emdCd;

    /**
     * 순위 목록 개수(선택). 🔁 TSK-4227 D21 — <b>생략하면 상한 없음</b>(심각·경고·주의 셀 전부),
     * 지정하면 그 값까지(1 이상 · 종전 상한 30 은 없앴다). 화면은 보내지 않는다.
     *
     * <p>지도에 그리는 <b>셀은 값&gt;0 전량</b>이고(D5) 이 값은 <b>순위 목록만</b> 정한다.
     * ⚠️ 순위 행마다 구역명 POI 조회(VWorld)를 시도하지만 외부 호출은 요청당
     * {@code analysis.zone-name.max-lookups-per-run} 으로 묶여 있어, 넘는 행은 폴백 이름
     * ({@code {읍면동} 격자 {코드}})이 된다 — 순위 자체는 끊기지 않는다(D10).
     */
    @Min(value = 1, message = "limit 은 1 이상이어야 합니다")
    @Schema(description = "순위 개수 (생략 = 심각·경고·주의 전부, 지정 시 1 이상)", example = "15", nullable = true)
    private Integer limit;
}
