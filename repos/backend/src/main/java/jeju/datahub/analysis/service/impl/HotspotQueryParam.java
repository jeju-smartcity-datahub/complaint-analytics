package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 집중구역(핫스팟) 집계 매퍼 전용 파라미터. 서비스가 요청값을 검증·변환해 채운 뒤 매퍼로 넘긴다.
 * 모든 값은 XML 에서 {@code #{}} 로만 바인딩된다({@code ${}} 미사용 → 인젝션 방어).
 *
 * <p>기간은 <b>반열림 구간</b>이다 — {@code ocrn_dt >= bgngDt AND ocrn_dt < endDtExcl}.
 * 요청의 {@code endYmd} 당일을 포함하려면 서비스가 {@code endDtExcl = endYmd + 1일}(00:00) 로 만들어 넘긴다.
 * 원천 컬럼({@code ocrn_dt}·{@code crdn_dt})이 {@code timestamp} 라 경계도 {@link LocalDateTime} 으로 맞춘다
 * ({@code ::date} 캐스팅을 넣으면 인덱스를 못 탄다).
 */
@Getter
@Setter
@ToString
public class HotspotQueryParam {

    /**
     * 시군구 코드(읍면동코드 앞 5자리 — 제주시 50110 / 서귀포시 50130). null 이면 미필터.
     *
     * <p>{@code emd_cd LIKE sggCd || '%'} 로 대조한다. 요청은 {@code sggNm} 으로 받으므로 서비스에서 변환한다.
     */
    private String sggCd;

    /** 읍면동 코드(8자리). null 이면 미필터. 지정되면 {@link #sggCd} 보다 우선한다. */
    private String emdCd;

    /** 조회 시작 일시(포함) */
    private LocalDateTime bgngDt;

    /** 조회 종료 일시(<b>미포함</b> — 반열림 구간의 상한) */
    private LocalDateTime endDtExcl;

    /**
     * DBSCAN 이웃 반경(m). 좌표를 EPSG:5179(미터 좌표계)로 변환한 뒤 군집화하므로 단위가 곧 미터다.
     */
    private int eps;

    /** DBSCAN 최소 점 개수. 이 미만이면 군집이 만들어지지 않는다(noise 로 빠져 결과에서 제외). */
    private int minPoints;

    /**
     * 민원 가중치 계수. {@code TB_ANLS_VRBL_STNG} 의 {@code stng_vl}(%) 를 100 으로 나눈 값(예: 0.60).
     *
     * <p>{@code NUMERIC(5,2)} 라 부동소수로 받으면 등급 경계 판정이 오차로 흔들린다 → {@link BigDecimal}.
     */
    private BigDecimal cvlcptWgt;

    /** 단속 가중치 계수. {@link #cvlcptWgt} 와 같은 규칙. */
    private BigDecimal crdnWgt;

    /**
     * 적용 민원 가중치(<b>%</b>, 예: 60 · 62.5) — 엑셀 후보 조회({@code selectHotspotWorkbook*}) 전용.
     *
     * <p>가중치 집합 {@code 0~100} 에 {@code UNION} 으로 더해진다(정수면 중복이 지워진다). 🔴 출처는
     * 서비스가 실제 계산에 쓴 {@code HotspotWeights.cvlcptWgt() × 100} 하나다 — 요청 {@code cvlcptWgvl} 이 아니다
     * (설정값 실행이면 요청값이 {@code null} 이다). {@link #cvlcptWgt}(비율)와 단위가 다르다.
     * {@code null} 이면 0~100 정수만 펼친다. 화면 조회({@code selectHotspotZones})는 읽지 않는다.
     */
    private BigDecimal cvlcptWgvl;

    /**
     * 조회 범위 = 순위 상위 이 비율(%) 안의 군집만 담는다.
     * 값의 출처는 설정값 주의 비율({@code GRD_BNDRY3}) 하나다 — 서비스가 넣는다.
     */
    private int maxRankPct;

    /**
     * 상위 N 개 (LIMIT). {@code null} 이면 <b>전량</b> — 매퍼가 {@code LIMIT} 절 자체를 빼므로
     * 조회 범위({@link #maxRankPct})만 걸린다.
     */
    private Integer limit;
}
