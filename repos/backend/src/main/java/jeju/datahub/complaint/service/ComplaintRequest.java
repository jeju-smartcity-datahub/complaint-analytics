package jeju.datahub.complaint.service;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 민원현황(GIS) 핵심 API {@code GET /api/complaints/gis} 요청 파라미터.
 *
 * <p>쿼리스트링이 이 POJO 의 필드로 바인딩된다(getter/setter 기반). {@code unit} 은 enum 이라
 * 화이트리스트(YESTERDAY/WEEK/MONTH) 밖 값이면 Spring 이 400 을 낸다. 필터 4종은 모두 optional 이며
 * 매퍼에서 {@code #{}} 파라미터로만 사용한다({@code ${}} 미사용 → 인젝션 방어).
 *
 * * 결정 (2026-08-21): startDt/endDt 둘 다 주어지면 unit 보다 우선.
 * * 결정 (2026-08-28): cvlcptTypeCd 는 백엔드 스펙에 유지(다른 페이지 호환) —
 *   프런트 민원현황 페이지에서는 호출 안 함.
 * * 결정 (2026-08-28): 행정구역 dropdown이 시군구 단위라 `sggId` (= `sggCd`) 로 매핑.
 */
@Getter
@Setter
@ToString
public class ComplaintRequest {

    /** 조회 단위. 기본 YESTERDAY. startDt/endDt 가 둘 다 주어지면 무시된다. */
    @Schema(description = "조회 단위(YESTERDAY/WEEK/MONTH). 생략 시 YESTERDAY", defaultValue = "YESTERDAY")
    private ComplaintUnit unit = ComplaintUnit.YESTERDAY;

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

    /** 읍면동코드(8자리). optional. sggCd 와 동시 지정 시 emdCd 우선. */
    @Schema(description = "읍면동코드(8자리) — sggCd 와 동시 지정 시 emdCd 우선")
    private String emdCd;

    /** 민원유형코드(01~05, 미분류 99 는 집계 제외). optional. */
    @Schema(description = "민원유형코드(01~05). 다른 페이지 호환용 — 본 화면 프런트는 호출 안 함")
    private String cvlcptTypeCd;

    /** startDt·endDt 가 둘 다 주어졌는지(기간 직접 지정 모드) */
    public boolean hasExplicitRange() {
        return startDt != null && endDt != null;
    }
}