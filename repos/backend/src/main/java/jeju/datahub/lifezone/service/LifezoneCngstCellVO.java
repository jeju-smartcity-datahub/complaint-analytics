package jeju.datahub.lifezone.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 혼잡도(주차 혼잡 지수) 격자 셀 1칸. (TSK-4090 Step 5)
 *
 * <p>🔴 <b>민원건수 셀({@link LifezoneCellVO})을 상속한다</b> — 프론트가 민원건수 지도·결과 패널을
 * 재사용하므로(Step 7) 공통 필드는 이름까지 같아야 한다. 상속 필드의 뜻:
 * <ul>
 *   <li>{@code gridCd} — 수급 {@code gridId} 와 같은 형식 {@code {격자크기}m_{gx}_{gy}}
 *       (이름만 민원건수에 맞춘다)</li>
 *   <li>{@code anlsVl} — <b>{@code round(H × 10000)}</b> 정수 = 혼잡 점수 × 100 (상한 없음 — 10000 = 100점,
 *       그 초과 = 수요가 면수 초과). 표시는 ÷100 점수 소수 첫째 자리 (occ 0.85 → 8500 → 85점 · H 1.597 → 159.7점 ·
 *       2026-10-08 점수 환산 ⑩ · 상한 해제 ⑪). 점수 = 지수 × 100 이라 원 지수 필드를 따로 두지 않는다</li>
 *   <li>{@code grade} — 선택한 분류방법의 등급</li>
 *   <li>{@code cntrPoiNm} — 생활권 폴백 구역명({@code {읍면동명} 격자 {gridCd}})</li>
 * </ul>
 *
 * <p>⚠️ {@code crdnCnt}·{@code excsPrkPrsmpVl} 은 <b>임시 이름</b>이다(데이터표준사전 대조 대상).
 * DB 컬럼이 아니라 API 응답 필드뿐이다.
 */
@Schema(description = "혼잡도 격자 셀 1칸 (LifezoneCellVO 확장)")
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
public class LifezoneCngstCellVO extends LifezoneCellVO {

    /** 공영주차면 합계 S (항상 &gt; 0 — 공급 0 격자는 계산 제외) */
    @Schema(description = "공영주차면수 (공급량 S)", example = "120")
    private int pkltCpctySum;

    /** 공영주차장 수 */
    @Schema(description = "공영주차장 수", example = "3")
    private int pkltCnt;

    /**
     * 단속 건수 — 전 수단({@code n_fixed + n_pda + n_manual}, 민원 기원 단속 포함).
     * 팝업의 "단속 기록 없음 · 민원 N건" 판정용이다(가중치를 곱하지 않은 원 건수).
     */
    @Schema(description = "단속 건수 (전 수단 · 민원 기원 단속 포함)", example = "42")
    private int crdnCnt;

    /** 같은 기간 창의 민원건수 */
    @Schema(description = "민원건수", example = "15")
    private int cvlcptCnt;

    /** 초과주차 추정량(대) — {@code (m × Enf_g + w_cv × Cv_g) ÷ 30 × g_geo}, 소수 1자리 */
    @Schema(description = "초과주차 추정량 (대, 소수 1자리)", example = "3.4")
    private BigDecimal excsPrkPrsmpVl;

    /**
     * 불법주차 근거 없음 여부 — 단속·민원이 모두 0 이라 초과주차 추정량이 0 인 칸.
     * 🔴 그런 칸은 응답 {@code cells} 에서 제외되므로(2026-10-08) <b>응답 셀에서는 항상 {@code N}</b> 이다.
     * 필드는 프론트의 혼잡도 셀 타입가드({@code 'noEnfYn' in c})가 쓰므로 유지한다.
     */
    @Schema(description = "불법주차 근거 없음 여부 (Y | N) — Y 칸은 cells 에서 제외되므로 응답에선 항상 N (혼잡도 셀 식별용으로 유지)", example = "N")
    private String noEnfYn;

    /**
     * 면수 하한 미만 여부 — 면수가 {@code MIN_CPCTY_VL} 미만.
     * 🔴 그런 칸은 등급 계산 전에 응답 {@code cells} 에서 제외되므로(2026-10-08) <b>응답 셀에서는 항상 {@code N}</b> 이다.
     * 하위호환으로 필드만 유지한다.
     */
    @Schema(description = "면수 하한 미만 여부 (Y | N) — Y 칸은 cells 에서 제외되므로 응답에선 항상 N (하위호환으로 유지)", example = "N")
    private String refYn;
}
