package jeju.datahub.dashboard.service;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 데이터 최신 갱신 — 원천 1종(= 수집 배치 1개) 1행 (TSK-4169).
 *
 * <p>화면 상단 {@code 최신 갱신} 표기의 (!) 툴팁 한 행이다. 출처는 민원 발생일이 아니라
 * <b>수집 배치 실행일</b>({@code batch.tb_btch_excn_hstry})이다 —
 * {@code GET /api/dashboard/data-status}(민원이 언제 <b>발생</b>했나)와 다른 사실을 말한다.
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 map-underscore-to-camel-case=true 로 자동 처리.
 * {@code dataNm}·{@code cyclNm} 은 SQL 이 주지 않고 서비스가 채운다(배치 cron 의 복제이며
 * 정본은 {@code docs/Batch-guide/batch-info.md}).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class DataFreshnessItemVO {

    /** Spring Batch Job 이름 (job_nm) — 예: {@code complaintFetchJob}. 프론트의 행 key. */
    private String jobNm;

    /** 화면 표시명 — {@code 민원} · {@code 단속} · {@code 공영주차장 현황} · {@code 공영주차장 이용률}. */
    private String dataNm;

    /**
     * 마지막 <b>성공</b>(COMPLETED) 배치 실행 일시 (last_excn_dt — {@code coalesce(ended_dt, started_dt)}).
     *
     * <p>🔴 한 번도 안 돌았으면 {@code null} 이다 — 행 자체는 사라지지 않는다("목록에 없다" 와
     * "아직 안 돌았다" 를 구분해야 한다).
     */
    private LocalDateTime lastExcnDt;

    /** 갱신 주기 문구. 🔴 단속은 스케줄러가 없어 {@code 수동 실행} 이다. */
    private String cyclNm;
}
