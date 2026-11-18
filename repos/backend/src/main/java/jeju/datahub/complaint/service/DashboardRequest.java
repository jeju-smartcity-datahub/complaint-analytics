package jeju.datahub.complaint.service;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 대시보드 집계 API 공통 요청 파라미터. kpi·regions·trend 가 공유한다.
 *
 * <p>쿼리스트링이 이 POJO 의 필드로 바인딩된다(getter/setter 기반). {@code unit} 은 enum 이라
 * 화이트리스트(TODAY/YESTERDAY/WEEK/MONTH) 밖 값이면 Spring 이 400 을 낸다. 필터 3종은 모두 optional 이며
 * 매퍼에서 {@code #{}} 파라미터로만 사용한다({@code ${}} 미사용 → 인젝션 방어).
 */
@Getter
@Setter
@ToString
public class DashboardRequest {

    /** 조회 단위. 기본 TODAY. startDt/endDt 가 둘 다 주어지면 무시된다. */
    @Schema(description = "조회 단위(TODAY/YESTERDAY/WEEK/MONTH). 생략 시 TODAY, 화면(대시보드)은 YESTERDAY 를 보낸다", defaultValue = "TODAY")
    private DashboardUnit unit = DashboardUnit.TODAY;

    /** 기간 직접 지정(시작일). endDt 와 함께 주어지면 unit 보다 우선. */
    @Schema(description = "기간 시작일(yyyy-MM-dd). endDt 와 함께 주면 unit 보다 우선")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDt;

    /** 기간 직접 지정(종료일). startDt 와 함께 주어지면 unit 보다 우선. */
    @Schema(description = "기간 종료일(yyyy-MM-dd). startDt 와 함께 주면 unit 보다 우선")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDt;

    /** 시군구 코드(5자리): 50110=제주시, 50130=서귀포시. emd_cd 접두 5자리와 대조. optional. */
    @Schema(description = "시군구 코드(50110=제주시, 50130=서귀포시)")
    private String sggCd;

    /** 민원유형코드(01~05. 미분류 99 는 조회에서 제외). optional. */
    @Schema(description = "민원유형코드(01~05)")
    private String typeCd;

    /** 위험단계코드 CRIT/WARN/CAUT. optional. 🔴 위험단계 알림 집계에만 적용된다 — 민원 집계에는 무영향. */
    @Schema(description = "위험단계코드(CRIT/WARN/CAUT) — riskAlert 집계에만 적용")
    private String riskLvlCd;

    /**
     * 추이 차트 x축 버킷(HOUR/DAY). optional — <b>생략하면 언제나 {@code HOUR}</b> 다.
     * 조회단위든 기간 직접 지정({@code startDt}/{@code endDt})이든 구분하지 않는다 — 추이 카드는
     * "몇 시에 몰리나"를 보는 화면이라 기간 길이와 무관하게 24칸으로 접는 것이 화면의 목적이다.
     * 날짜순 시계열이 필요하면 {@code bucket=DAY} 를 <b>명시</b>한다.
     * <b>{@code /trend} 에서만 쓰인다</b> — kpi·regions 는 이 값을 무시한다.
     */
    @Schema(description = "추이 x축 버킷(HOUR/DAY) — /trend 전용. 생략 시 항상 HOUR(24칸). 날짜순 시계열은 DAY 를 명시")
    private TrendBucket bucket;

    /** startDt·endDt 가 둘 다 주어졌는지(기간 직접 지정 모드) */
    public boolean hasExplicitRange() {
        return startDt != null && endDt != null;
    }
}
