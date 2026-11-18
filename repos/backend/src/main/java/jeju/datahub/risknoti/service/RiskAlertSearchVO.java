package jeju.datahub.risknoti.service;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 알림 이력 조회 파라미터 VO. <b>MyBatis 매퍼 파라미터 겸용</b>이다
 * (목록·카운트·요약 3개 statement 가 같은 객체를 받아 같은 {@code alertWhere} 조각을 공유한다).
 *
 * <p>🔴 <b>여기 도착한 값은 이미 정규화·검증이 끝난 상태여야 한다.</b> 정규화는 컨트롤러 책임이다
 * ({@code RiskAlertController}):
 * <ul>
 *   <li>필터 3종({@code riskLvlCd}·{@code sggNm}·{@code emdCd})은 <b>빈 문자열·{@code "all"} 을 null 로 접은 뒤</b>
 *       담는다. {@code alertWhere} 의 {@code <if test="... != null">} 는 빈 문자열을 그대로 통과시켜
 *       {@code h.risk_lvl_cd = ''} 로 0건이 된다.</li>
 *   <li>기간은 {@code LocalDate} → {@code LocalDateTime} 으로 펼친 값이다 —
 *       {@code fromDt} 는 {@code 00:00:00}, {@code toDt} 는 <b>{@code 23:59:59}</b>.
 *       날짜를 그대로 비교하면 종료일 당일 알림이 통째로 빠진다.</li>
 * </ul>
 *
 * <p>{@code page} 는 담지 않는다 — SQL 이 쓰는 것은 {@code offset} 뿐이고,
 * 응답 메타용 {@code page} 는 서비스가 별도 인자로 받는다.
 */
@Schema(description = "위험단계 알림 조회 파라미터 (정규화 완료 상태)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RiskAlertSearchVO {

    /** 위험단계 코드 필터 (CRIT | WARN | CAUT). null 이면 전체 */
    @Schema(description = "위험단계 코드 필터. null 이면 전체", example = "WARN")
    private String riskLvlCd;

    /** 시군구명 필터 — 이력에 컬럼이 없어 {@code a.sgg_nm}(TB_ADMDST)에 건다 (D12). null 이면 전체 */
    @Schema(description = "시군구명 필터. null 이면 전체", example = "제주시")
    private String sggNm;

    /** 읍면동코드 필터 ({@code h.emd_cd}). null 이면 전체 */
    @Schema(description = "읍면동코드 필터. null 이면 전체", example = "50110104")
    private String emdCd;

    /** 조회 시작일시 — {@code from} 날짜의 {@code 00:00:00}. null 이면 하한 없음 */
    @Schema(description = "조회 시작일시 (from 00:00:00)", example = "2026-05-01T00:00:00")
    private LocalDateTime fromDt;

    /** 조회 종료일시 — {@code to} 날짜의 {@code 23:59:59}. null 이면 상한 없음 */
    @Schema(description = "조회 종료일시 (to 23:59:59)", example = "2026-05-31T23:59:59")
    private LocalDateTime toDt;

    /** LIMIT — 페이지 크기 (컨트롤러가 1~100 으로 검증한다) */
    @Schema(description = "페이지 크기 (LIMIT)", example = "10")
    private int size;

    /**
     * OFFSET — {@code page * size}. int 로 두면 page 가 클 때 곱셈이 int 범위를 넘어
     * 음수 OFFSET 으로 뒤집히므로 long 이다.
     */
    @Schema(description = "OFFSET (page * size)", example = "0")
    private long offset;

    // 🔁 TSK-4250 R13 — 종전 rcvrIds(요청자의 활성 수신자 ID 목록, TSK-4202 D10) 필드를 걷었다.
    //    조회 화면 3종(목록·총건수·요약)은 다시 수신자 무관이라 이 VO 가 수신자 값을 실을 이유가 없다.
    //    수신자 기준이 남은 벨·읽음은 이 VO 를 받지 않고 @Param("rcvrIds") 로 따로 받는다.
}
