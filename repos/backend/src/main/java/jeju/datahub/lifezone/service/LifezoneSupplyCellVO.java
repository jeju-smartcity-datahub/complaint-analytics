package jeju.datahub.lifezone.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 수급 격자 셀 1칸. (TSK-4089)
 *
 * <p>민원건수 분석의 {@link LifezoneCellVO} 와 <b>지표 개수가 다르다</b> — 단일 값 하나가 아니라
 * 민원·공급 두 축과 보조 지표가 함께 실린다. 화면은 이 값들을 팝업에 그대로 뿌린다(인계서 §4-3).
 *
 * <p>🔁 <b>TSK-4258 — 사분면 → 부족률 등간격 5단계(🔁 D19).</b> 신규 필드({@link #grade}·{@link #gradeNm}·
 * {@link #shortageRatio}·{@link #demandIndex})는 신규 응답에서 <b>항상 채운다</b>(5단계라 등급 없는 격자가 없다).
 * 구 필드({@link #qdrn}·{@link #qdrnNm}·{@link #cvlcptPerSlot})는 <b>구 스냅샷 역직렬화 전용</b>으로 남긴다(D10·D14) —
 * 보고서 스냅샷을 {@code treeToValue(LifezoneSupplyCellVO[].class)} 로 되읽으므로 지우면 값이 조용히 사라진다.
 * 양쪽 모두 <b>필드 단위</b> {@code @JsonInclude(NON_NULL)} 이라 서로의 응답에서 키가 생략된다.
 * 🔴 클래스 단위 NON_NULL 로 바꾸지 않는다 — {@code sggNm} 등 "null 도 의미가 있는" 키까지 사라진다
 * ({@code HotspotReportVO} 주석 관례).
 */
@Schema(description = "수급 격자 셀 1칸")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneSupplyCellVO {

    /**
     * 격자ID — {@code {격자크기}m_{gx}_{gy}} (예: {@code 300m_3021_5007}).
     *
     * <p>{@code gx}·{@code gy} 는 EPSG:5179 좌표를 격자크기로 나눈 몫({@code floor})이라
     * 이 ID 하나로 격자 경계가 결정된다 — 별도 격자 마스터가 필요 없다.
     */
    @Schema(description = "격자ID ({격자크기}m_{gx}_{gy})", example = "300m_3021_5007")
    private String gridId;

    /**
     * 등급코드 — {@link LifezoneGrade} (TSK-4258 D13). 부족률 80/60/40/20% 경계로 서버가 판정한다(D6·D19).
     * 구 스냅샷 셀에는 없다(키 생략).
     */
    @Schema(description = "등급코드 (SEVERE | WARN | CAUTION | NORMAL | LOW) — 부족률 80/60/40/20% 이상",
            example = "SEVERE")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String grade;

    /** 등급명 */
    @Schema(description = "등급명", example = "심각")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String gradeNm;

    /**
     * 부족률 = 민원 ÷ (면수 + 민원), 소수 4자리 (D5 — 공간 집중 분석 수요 부족 지역과 같은 식·반올림).
     * 화면은 ×100 해 % 로 보인다. 공급 0 격자는 {@code 1}(100%)이다.
     */
    @Schema(description = "부족률 = 민원 ÷ (공영주차면 + 민원), 0~1 · 소수 4자리", example = "0.8123")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal shortageRatio;

    /** 수요지수 = 공영주차면 + 민원 (D5). 🔴 래퍼형이다 — 구 스냅샷 셀에서 0 으로 채워지지 않게 */
    @Schema(description = "수요지수 = 공영주차면 + 민원", example = "875")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer demandIndex;

    /**
     * 사분면코드 (Q1 | Q2 | Q3 | Q4).
     *
     * @deprecated TSK-4258 — 구 스냅샷(사분면) 역직렬화·렌더 전용(D10·D14). 신규 응답은 채우지 않아 키가 생략된다.
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) 사분면코드 (Q1 | Q2 | Q3 | Q4)", example = "Q1", deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String qdrn;

    /**
     * 사분면명.
     *
     * @deprecated TSK-4258 — 구 스냅샷 전용(D10·D14).
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) 사분면명", example = "부족 의심", deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String qdrnNm;

    /**
     * 🔁 부족 순위 — <b>전체 순위 1..N</b>(TSK-4258 D11, 동점 없음 · SQL {@code row_number()}).
     * 부족률 내림차순(= 면수 ÷ 민원 오름차순) → 민원 내림차순 → gx·gy 순이다.
     * ⚠️ 구 스냅샷 셀에서는 종전 의미("사분면 내 순번", 민원건수 내림차순) 그대로다.
     */
    @Schema(description = "부족 순위 (전체 1..N · 부족률 내림차순 → 민원 내림차순). 구 스냅샷은 사분면 내 순번",
            example = "1")
    private int rnk;

    /** 시군구명 (매칭 없으면 null) */
    @Schema(description = "시군구명", example = "제주시")
    private String sggNm;

    /** 읍면동명 — 격자 안 민원의 <b>최빈</b> 읍면동이다(격자가 경계를 걸칠 수 있다) */
    @Schema(description = "읍면동명 (격자 내 최빈)", example = "용담이동")
    private String emdNm;

    /**
     * 구역명 — VWorld POI 최근접(없으면 역지오코딩 지번)에 읍면동명 접두를 붙인 이름 (TSK-4272).
     *
     * <p>🔴 <b>목록 대상 등급(심각·경고·주의)에만 채운다</b> — 보통·낮음 셀은 조회하지 않는다(셀 전량을 태우면
     * 외부 호출 상한이 즉시 소진된다). 이름을 못 얻었거나 상한({@code analysis.zone-name.max-lookups-per-run})에
     * 걸리면 {@code null} 이고 키가 생략된다 — 화면은 {@link #emdNm} 으로 폴백한다. 구 스냅샷 셀에도 없다.
     */
    @Schema(description = "구역명 (POI · 목록 대상 등급만 · 없으면 키 생략 → emdNm 폴백)", example = "용담이동 월성사거리",
            nullable = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String cntrPoiNm;

    /** 읍면동코드 (격자 내 최빈) */
    @Schema(description = "읍면동코드 (격자 내 최빈)", example = "50110109")
    private String emdCd;

    /** 민원건수 */
    @Schema(description = "민원건수", example = "711")
    private int cvlcptCnt;

    /** 공영주차면수 — 🔴 "부족 면수" 가 아니라 <b>공급량 자체</b>다(인계서 §4-4) */
    @Schema(description = "공영주차면수 (공급량)", example = "0")
    private int pkltCpctySum;

    /** 공영주차장 수 */
    @Schema(description = "공영주차장 수", example = "0")
    private int pkltCnt;

    /**
     * 면당 민원 = 민원건수 / 주차면수.
     *
     * <p>🔴 주차면 0 이면 <b>{@code null}</b> 이다 — 0 으로 나누지 않는다(인계서 §5-7).
     * {@code Infinity}·{@code 0} 으로 대신하면 정렬이 뒤집히거나 "민원이 없는 것" 처럼 보인다.
     * Q2 내부 정렬에만 쓰는 보조 지표다.
     *
     * @deprecated TSK-4258 — 구 스냅샷 전용(D10·D14). 신규 응답은 채우지 않는다(키 생략).
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) 면당 민원 (주차면 0 이면 null)", example = "164.5", nullable = true,
            deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal cvlcptPerSlot;

    /**
     * 격자 안 민원의 <b>고유 좌표 개수</b>.
     *
     * <p>이 값이 작은데 민원이 많으면 특정 지번 한두 곳에 몰린 것이다 — 판정 결과는
     * {@link #cncntWarnYn} 에 함께 실린다.
     */
    @Schema(description = "고유 좌표 개수", example = "1")
    private int dstncPtCnt;

    /**
     * 소수 지점 집중 주의 여부 ({@code Y}/{@code N}).
     *
     * <p>🔴 <b>판정은 서버가 한다</b>(인계서 §4-3 조건: {@code 고유좌표수 <= 3 && 민원 >= 50}).
     * 프론트에 같은 조건을 또 적으면 두 곳이 갈린다 — 화면은 이 값으로 안내 문구만 띄운다.
     */
    @Schema(description = "소수 지점 집중 주의 여부 (Y | N)", example = "Y")
    private String cncntWarnYn;

    /** 격자 중심 위도 (EPSG:4326) */
    @Schema(description = "격자 중심 위도 (EPSG:4326)", example = "33.508044")
    private BigDecimal ctrLat;

    /** 격자 중심 경도 (EPSG:4326) */
    @Schema(description = "격자 중심 경도 (EPSG:4326)", example = "126.4927719")
    private BigDecimal ctrLot;

    /**
     * 격자 꼭짓점 링 {@code [[위도, 경도], …]} 4점.
     *
     * <p>🔴 <b>서버가 EPSG:5179 정방형을 4326 으로 변환해 내린다</b>(인계서 §4-2).
     * 프론트가 중심 위경도에 {@code ±size/2} 를 더해 사각형을 그리면 실제 격자와 어긋난다 —
     * 경도 1도의 실제 거리가 위도에 따라 달라지기 때문이다.
     */
    @Schema(description = "격자 꼭짓점 링 [[위도, 경도], …] 4점")
    private List<List<BigDecimal>> ring = new ArrayList<>();
}
