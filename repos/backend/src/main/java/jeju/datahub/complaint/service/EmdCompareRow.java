package jeju.datahub.complaint.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * §7 매퍼 결과 평면 row — selectEmdCompare 의 1행. (PostgreSQL FILTER 로 현재/직전 동시 집계)
 *
 * <p><b>왜 POJO + setter 인가</b> — Java record 의 컴포넌트명은 컴파일 시 손실되어
 * MyBatis record 자동 매핑이 SQL alias 와 정확히 매칭하지 못하는 케이스가 실측됨
 * (2026-08-31). record 의 canonical constructor 매개변수명은 컴파일 후 {@code param0},
 * {@code param1} ... 로 변경 — MyBatis 가 컴포넌트명을 accessor 메서드명(예:
 * {@code cvlcptCnt()})에서 추론해야 하는데, snake_case alias ↔ camelCase 컴포넌트의
 * 자동 변환이 두 long 컬럼 매핑에서 깨짐.
 *
 * <p>POJO + setter 로 컬럼명 ↔ 프로퍼티명 매핑이 setter 호출 형태로 확실히 동작.
 * 응답 VO({@link EmdCompareVO})는 record 그대로 — Jackson 직렬화는 record 에서 정상.
 *
 * <p>SQL alias (camelCase): {@code emdCd}, {@code emdNm}, {@code cvlcptCnt}, {@code prevCnt}.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class EmdCompareRow {

    /** 현재 기간 민원 건수 (§7 cvlcptCnt) */
    private long cvlcptCnt;

    /** 직전 동일 길이 구간 민원 건수 (§7 prevCnt) */
    private long prevCnt;

    /** 읍면동 코드 (8자리) */
    private String emdCd;

    /** 읍면동명 (TB_ADMDST LEFT JOIN) */
    private String emdNm;
}
