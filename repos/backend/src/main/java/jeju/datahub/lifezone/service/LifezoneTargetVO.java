package jeju.datahub.lifezone.service;

import java.util.Arrays;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석유형 가용 목록 1건. {@link LifezoneTarget} 상수 하나를 그대로 옮긴 <b>읽기 전용 투영</b>이다.
 *
 * <p><b>왜 응답에 싣나</b>(D2) — 설정 패널 세그먼트를 프론트가 하드코딩하면 유형이 구현되는 시점에
 * 화면을 함께 고쳐야 하고, 그전까지는 "눌리는데 400 이 나는 버튼"이 된다. 가용 여부와 사유를
 * 서버가 알리면 프론트는 <b>목록을 그리기만</b> 한다.
 *
 * <p>🔴 미구현 유형({@code available=false})도 <b>목록에는 들어간다</b> — 비활성 세그먼트로
 * 그려야 사용자가 "곧 생길 기능"임을 안다. 그 코드로 실제 조회하면 서비스가 400 이다.
 *
 * <p><b>값을 두 번 적지 않는다</b> — {@link #allOf()} 가 enum 에서 파생한다. 문구를 고치는 곳은
 * {@link LifezoneTarget} 한 곳뿐이다.
 */
@Schema(description = "분석유형 가용 정보 (LifezoneTarget 파생)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneTargetVO {

    /** 분석내용코드. 공통코드 {@code ANLS_TRGT_CD} 의 {@code cd_vl} 과 같다(D15) */
    @Schema(description = "분석내용코드", example = "CVLCPT_CNT")
    private String anlsTrgtCd;

    /** 분석유형명. 세그먼트 라벨 */
    @Schema(description = "분석유형명", example = "민원건수")
    private String anlsTrgtNm;

    /** 값 단위 */
    @Schema(description = "값 단위", example = "건")
    private String unit;

    /** 구현 여부. false 면 그 코드로 조회 시 400 (D2) */
    @Schema(description = "구현 여부", example = "true")
    private boolean available;

    /** 미구현 사유. {@code available=true} 면 null */
    @Schema(description = "미구현 사유 (available=true 면 null)", example = "도로 혼잡도 원천 데이터 준비 중")
    private String unavailableRsn;

    /**
     * 이 유형이 지원하는 격자크기(m) 목록.
     *
     * <p>민원건수 {@code [1000, 500, 250, 100]}(격자 마스터 보유분)이고, 🔁 수급도 같은 목록이다
     * (TSK-4206 — 종전 {@code [100, 200, 300, 500]}). 프론트가 탭을 이 값으로 그린다.
     * 지금은 같아도 유형별 필드로 둔다 — 목록을 화면에 박으면 해상도가 다른 유형이 생길 때 서버가 400 을 낸다.
     */
    @Schema(description = "지원 격자크기(m) 목록", example = "[1000, 500, 250, 100]")
    private List<Integer> gridSzs;

    /** 기본 격자크기(m) */
    @Schema(description = "기본 격자크기(m)", example = "500")
    private int dfltGridSz;

    /**
     * 조회 경로 접미사 — {@code /api/analysis/lifezone} 뒤에 붙는다.
     * 빈 문자열이면 기본 경로, {@code "/supply"} 면 수급 전용 핸들러다.
     */
    @Schema(description = "조회 경로 접미사 ('' | '/supply' | '/congestion')", example = "/supply")
    private String anlsPathSfx;

    /**
     * 이 유형이 허용하는 등급 분류방법 목록 (TSK-4090). 목록 밖 값으로 조회하면 400 이다.
     *
     * <p>🔴 프론트가 <b>조회 전에</b> 분류방법 선택 UI 를 그리는 근거다 — 화면에 박으면 유형을 바꿔도
     * 선택지가 남아 서버가 400 을 낸다({@code gridSzs} 와 같은 이유). 등급 분류가 없는 유형(수급 —
     * 사분면)은 빈 목록이다.
     */
    @Schema(description = "허용 등급 분류방법 목록 (수급은 빈 목록)", example = "[\"EQUAL_INTERVAL\"]")
    private List<LifezoneClsfMthd> clsfMthds;

    /** {@code clsfMthd} 생략 시 기본 분류방법. 등급 분류가 없는 유형이면 {@code null} */
    @Schema(description = "기본 등급 분류방법 (수급은 null)", example = "EQUAL_INTERVAL", nullable = true)
    private LifezoneClsfMthd dfltClsfMthd;

    /**
     * 이 유형의 원천 보유 시작일 (YYYYMMDD). 구현되지 않은 유형이면 {@code null}.
     *
     * <p>🔴 <b>유형마다 다르다</b>(TSK-4089) — 민원건수는 {@code ocrn_dt}(발생일시), 수급은
     * 집계 조건이 다르기 때문이다(민원건수는 미분류 제외·좌표 무관 / 수급은 전 유형·좌표 유효).
     * 🔁 2026-09-13 이전에는 <b>기준 컬럼</b>까지 갈려 있어(수급 {@code dclr_ymd}) 두 구간이 1년
     * 어긋났고, 한쪽 구간으로 다른 쪽을 조회하면 <b>0건</b>이 나왔다.
     *
     * <p>🔴 <b>화면이 실행 전에 이 값을 알아야 한다.</b> 기간 세그먼트(최근 1/3/6개월)는 <b>오늘</b>
     * 기준이라 보유 구간이 과거인 유형은 기본값으로 <b>영원히 0건</b>이다 — 실행한 뒤 "결과 0건"
     * 안내를 보고서야 알게 두면, 사용자는 화면이 고장 난 것으로 읽는다.
     */
    @Schema(description = "원천 보유 시작일 (YYYYMMDD). 미구현 유형이면 null",
            example = "20250101", nullable = true)
    private String dataBgngYmd;

    /** 이 유형의 원천 보유 종료일 (YYYYMMDD). 구현되지 않은 유형이면 {@code null} */
    @Schema(description = "원천 보유 종료일 (YYYYMMDD). 미구현 유형이면 null",
            example = "20250831", nullable = true)
    private String dataEndYmd;

    /** 원천 보유 구간을 채운다. 목록을 만든 쪽(카탈로그)이 유형별 출처를 알고 넣어 준다 */
    public void setDataRange(String dataBgngYmd, String dataEndYmd) {
        this.dataBgngYmd = dataBgngYmd;
        this.dataEndYmd = dataEndYmd;
    }

    private LifezoneTargetVO(LifezoneTarget target) {
        this.anlsTrgtCd = target.name();
        this.anlsTrgtNm = target.getAnlsTrgtNm();
        this.unit = target.getUnit();
        this.available = target.isAvailable();
        this.unavailableRsn = target.getUnavailableRsn();
        this.gridSzs = target.getGridSzs();
        this.dfltGridSz = target.getDfltGridSz();
        this.anlsPathSfx = target.getAnlsPathSfx();
        this.clsfMthds = target.getClsfMthds();
        this.dfltClsfMthd = target.getDfltClsfMthd();
    }

    /** 모든 분석유형. 선언 순서 그대로이며 세그먼트 표시 순서다 */
    /**
     * 정적 목록 — <b>보유 구간이 비어 있다</b>({@code dataBgngYmd}·{@code dataEndYmd} 가 null).
     *
     * <p>DB 를 보지 않는 경량 생성자라 분석 응답의 {@code availableTargets} 처럼 "세그먼트를 그릴
     * 정보" 만 필요한 곳이 쓴다. 🔴 <b>설정 패널이 부르는 {@code GET /targets} 는 이것을 쓰면 안
     * 된다</b> — 보유 구간을 모르면 기간 기본값으로 0건이 나오는 것을 실행 전에 경고할 수 없다.
     * 그쪽은 {@code LifezoneTargetCatalog} 가 채운 목록을 쓴다.
     */
    public static List<LifezoneTargetVO> allOf() {
        return Arrays.stream(LifezoneTarget.values()).map(LifezoneTargetVO::new).toList();
    }
}
