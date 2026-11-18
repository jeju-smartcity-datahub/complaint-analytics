package jeju.datahub.risknoti.service;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 상단 알림 벨 팝오버 전용 응답.
 *
 * <p>{@code GET /api/risk-alerts} 의 {@code RiskAlertPageVO} 와 형태가 다르다 —
 * 팝오버는 페이징도 필터도 없고 <b>판정일 하루치 전부</b>를 내린다.
 *
 * <p>🔴 {@code items} 의 원소 타입은 <b>기존 {@link RiskAlertVO} 를 재사용</b>한다.
 * 조회 화면과 같은 표를 두 벌 만들지 않는다 — {@code prslYn}·{@code prslDt} 가 이미 그 VO 에 있어
 * 읽은 건 흐리게 표시하는 데 그대로 쓸 수 있다.
 */
@Schema(description = "상단 알림 벨 팝오버 응답 (판정일 하루치)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RiskAlertDailyVO {

    /** 기준일(판정일) = 전일. yyyy-MM-dd. 서버가 Asia/Seoul 로 확정한다 */
    @Schema(description = "기준일(판정일) — 서버가 Asia/Seoul 로 확정", example = "2026-05-28")
    private LocalDate baseDe;

    /** 기준일에 생성된 알림 전체 건수 — 헤더 문구 `전일 기준 N건` */
    @Schema(description = "기준일 알림 전체 건수", example = "3")
    private int totalCnt;

    /** 그중 미읽음(prsl_yn='N') 건수 — 벨 배지 */
    @Schema(description = "미읽음 건수 (벨 배지)", example = "1")
    private int unreadCnt;

    /** 목록. 개수 제한 없음. 정렬 ocrn_dt DESC, noti_id DESC */
    @Schema(description = "알림 목록 (개수 제한 없음, ocrn_dt DESC · noti_id DESC)")
    private List<RiskAlertVO> items;
}
