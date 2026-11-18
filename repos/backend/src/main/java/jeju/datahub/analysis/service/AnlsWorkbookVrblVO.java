package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석 보고서 엑셀 재계산 데이터의 분석 계수 1행 — {@code wbk.vrbls[]} 공용
 * (생활권 수급·혼잡도 · 구역추천 요금제).
 *
 * <p>(구역추천 엑셀) 생활권 {@code lifezone.service.LifezoneWorkbookVrblVO} 를 여기로 옮겼다.
 * 🔴 {@code analysis} 가 {@code lifezone} 을 import 하면 의존 방향이 뒤집힌다(생활권이 이미 {@code analysis.service.AnlsVrblOvrd} 를 쓴다).
 *
 * <p>엑셀 위쪽 계수 셀 1개 = 이 행 1개다. 초기값은 {@link #aplcnVl}(이번 분석이 실제로 쓴 값 — 일시 조정 반영),
 * 데이터 유효성은 {@link #minVl}~{@link #maxVl}·{@link #intOnlyYn} 이다. {@link #inptYn} 이 {@code N} 이면
 * 입력 셀이 아니라 잠긴 조건 셀(또는 수식 셀)이다.
 *
 * <p>🔴 {@code aplcnVl} 키는 기존 적용 내역({@code vrblAjmt.vrbls[].aplcnVl} — {@code AnlsVrblOvrd.toAjmtJson})과 같은 뜻·같은 이름이다.
 * 같은 뜻의 키를 두 벌 만들지 않는다.
 *
 * <p>⚠️ {@code intOnlyYn} 은 <b>임시 이름</b>이다 — 데이터표준사전 v1.0 에 단어 {@code 정수} 가 없다
 * (그리고 {@code INT} 는 이미 {@code 이자} 의 약어다). 등재 요청 대상.
 * {@code inptYn} 은 표준용어 {@code 입력여부 INPT_YN} 그대로다.
 */
@Schema(description = "엑셀 분석 계수 1행 (입력 셀 초기값·범위)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AnlsWorkbookVrblVO {

    /** 변수코드 ({@code tb_anls_vrbl_stng.vrbl_cd}) — 엑셀 정의된 이름의 키 */
    @Schema(description = "변수코드", example = "SPLY_MIN_MNTH_AVG_CVLCPT")
    private String vrblCd;

    /** 변수명 — 엑셀 라벨. 설정 행이 없으면 {@link #vrblCd} */
    @Schema(description = "변수명 (엑셀 라벨)", example = "수급 최소 월평균 민원")
    private String vrblNm;

    /** 적용값 — 이번 분석이 실제로 쓴 값(설정값 + 일시 조정). 엑셀 입력 셀 초기값 */
    @Schema(description = "적용값 (분석이 실제로 쓴 값 — 엑셀 초기값)", example = "5")
    private BigDecimal aplcnVl;

    /** 최솟값 ({@code min_vl}). 설정 행이 없으면 {@code null} */
    @Schema(description = "최솟값", example = "1")
    private BigDecimal minVl;

    /** 최댓값 ({@code max_vl}). 설정 행이 없으면 {@code null} */
    @Schema(description = "최댓값", example = "30")
    private BigDecimal maxVl;

    /** 정수 전용 여부 (Y | N) — 수급 5개는 Y, 혼잡도·구역추천은 전부 N(소수 넷째 자리까지). ⚠️ 임시 이름 */
    @Schema(description = "정수 전용 여부 (Y | N)", example = "Y")
    private String intOnlyYn;

    /**
     * 입력여부 (Y | N) — 엑셀 노란 입력 셀이면 {@code Y}. {@code N} 이면 적용값만 보이는 잠긴 셀이다
     * (설정 행 없음·{@code use_yn='N'} · 구역추천 공간 계수 · 시군구 모드 판정 계수 · 가중치 쌍의 수식 쪽).
     * (구역추천) 키 추가뿐이다(생활권 엑셀은 이 키를 읽지 않는다).
     */
    @Schema(description = "입력여부 (Y | N) — N 이면 잠긴 조건 셀", example = "Y")
    private String inptYn;
}
