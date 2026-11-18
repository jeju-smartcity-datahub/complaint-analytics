package jeju.datahub.lifezone.service;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 혼잡도 엑셀 원자료 1행 — {@code selectCngstCells} 결과(공급&gt;0 격자) 1행 그대로.
 *
 * <p>🔴 점수·등급·제외 판정은 싣지 않는다 — 엑셀이 계수 10개로 {@code LifezoneCngstServiceImpl.score} 와 같은 순서로 계산한다.
 * 근거 없음·면수 하한 미만 격자도 싣는다(계수에 따라 분석 대상이 될 수 있다).
 *
 * <p>🔴 {@code nFixed}·{@code nPda}·{@code nManual} 은 getter 를 직접 쓰고 거기에 {@link JsonProperty} 를 단다 —
 * Lombok 은 필드의 {@code @JsonProperty} 를 생성 getter 로 옮기지 않아, Jackson 이 {@code getNFixed()} 를 별개 키
 * {@code nfixed} 로 함께 내보낸다(키 두 벌). 필드에만 달면 안 된다.
 */
@Schema(description = "혼잡도 엑셀 원자료 1행 (공급>0 격자)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneCngstWorkbookCellVO {

    /** 격자코드 {@code {격자크기}m_{gx}_{gy}} (수급 {@code gridId} 와 같은 형식) */
    @Schema(description = "격자코드", example = "500m_1863_3005")
    private String gridCd;

    /** 구역명 — 순위 행 이름(같은 gridCd) → 셀 이름(캐시 → 생활권 폴백) */
    @Schema(description = "구역명", example = "제주시청")
    private String cntrPoiNm;

    /** 읍면동명 */
    @Schema(description = "읍면동명", example = "연동")
    private String emdNm;

    /** 공영주차면 합 (공급량 S · 항상 &gt; 0) */
    @Schema(description = "공영주차면 합", example = "120")
    private int pkltCpctySum;

    /** 공영주차장 수 */
    @Schema(description = "공영주차장 수", example = "3")
    private int pkltCnt;

    /** 고정형 단속 건수 ({@code n_fixed}) */
    @JsonProperty("nFixed")
    @Schema(description = "고정형 단속 건수", example = "10")
    private int nFixed;

    /** PDA 단속 건수 ({@code n_pda}) */
    @JsonProperty("nPda")
    @Schema(description = "PDA 단속 건수", example = "20")
    private int nPda;

    /** 수동 단속 건수 ({@code n_manual} — 민원 기원 단속 포함) */
    @JsonProperty("nManual")
    @Schema(description = "수동 단속 건수 (민원 기원 포함)", example = "5")
    private int nManual;

    /** 민원 건수 */
    @Schema(description = "민원 건수", example = "15")
    private int cvlcptCnt;

    @JsonProperty("nFixed")
    public int getNFixed() {
        return nFixed;
    }

    @JsonProperty("nPda")
    public int getNPda() {
        return nPda;
    }

    @JsonProperty("nManual")
    public int getNManual() {
        return nManual;
    }
}
