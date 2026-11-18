package jeju.datahub.complaint.service;

/**
 * §7 매퍼 결과 — selectTopTypeByEmd 의 1행. ({@code DISTINCT ON (c.emd_cd)} 로 읍면동별 최다 유형 1건)
 *
 * < PostgreSQL {@code DISTINCT ON (c.emd_cd)} 로 (emd_cd, type_count DESC) 정렬
 * 후 첫 행만 남긴다 — 읍면동별 최다 유형 1건. 서비스가 {@link EmdCompareRow#topTypeCd()}
 * 와 typeCount 를 매칭해 {@link EmdCompareVO} 의 {@code topTypeRatio} 를 계산한다.
 *
 * * {@code typeNm} 은 매퍼에서 {@code LEFT JOIN TB_COM_CD} 로 같이 가져온다 — 응답 VO 의
 *   {@link EmdCompareVO#topTypeNm()} 가 별도 매핑 없이 사용 가능 (코드 없으면 null).
 */
public record EmdTopTypeRow(
        String emdCd,
        String typeCd,
        long typeCount,
        String typeNm
) {
}