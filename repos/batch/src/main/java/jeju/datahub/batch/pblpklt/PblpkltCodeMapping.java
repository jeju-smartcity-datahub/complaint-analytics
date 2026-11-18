package jeju.datahub.batch.pblpklt;

import java.util.Map;

/**
 * 원천 주차장 유형 → 공통코드 {@code PKLT_TYPE_CD} 매핑 (TSK-4142 §3-1 · §7-4).
 *
 * <h2>🔁 2026-09-12 — 코드가 아니라 <b>이름</b>으로 분류한다</h2>
 * 새 원천은 {@code pklt_type_cd} 를 100% null 로 보내고 {@code pklt_type_nm}("공한지")만 준다
 * (Step 0 실측 2,272행). 그래서 판정 입력이 {@link #NAME_TO_CD} 로 옮겨졌다. 원천이 나중에
 * 코드를 채워 보내면 그 값을 그대로 쓴다({@link #resolveTypeCd} 의 첫 분기) — 이름 매핑은
 * 코드가 없을 때의 파생 경로다.
 *
 * <h2>🔴 제거됨 — {@code OFSTR} → {PBLPKLT,VACLOT,MULTFLR} 해시 자동 배정</h2>
 * 종전에는 mock 호환을 이유로 {@code OFSTR} 을 origin_pk 해시로 세 코드에 흩뿌렸다. 실데이터를
 * 임의로 나누는 코드인 데다 배정 결과가 당시 공통코드에 없던 값이라 이미 어긋나 있었다.
 * 클래스 주석의 "운영 진입 전 제거 예정" 을 TSK-4142 에서 이행했다.
 *
 * <h2>대응표 (계획서 §3-1 이 정본 — 공통코드 마이그레이션 64 와 같은 표)</h2>
 * <table>
 *   <tr><th>원문 {@code pklt_type_nm}</th><th>건수</th><th>→ {@code pklt_type_cd}</th></tr>
 *   <tr><td>노상</td><td align="right">654</td><td>{@code ONSTR}</td></tr>
 *   <tr><td>노상(23년 조사)</td><td align="right">216</td><td>{@code ONSTR}</td></tr>
 *   <tr><td>노상공영</td><td align="right">128</td><td>{@code ONSTR}</td></tr>
 *   <tr><td>노외공영</td><td align="right">114</td><td>{@code OFSTR}</td></tr>
 *   <tr><td>공영</td><td align="right">312</td><td>{@code PBLPKLT}</td></tr>
 *   <tr><td>공한지</td><td align="right">820</td><td>{@code VACLOT}</td></tr>
 *   <tr><td>복층화</td><td align="right">28</td><td>{@code MULTFLR}</td></tr>
 *   <tr><td>그 외·null</td><td align="right">0</td><td>{@link #TYPE_CD_ETC} (+ WARN·미분류 카운트)</td></tr>
 * </table>
 *
 * <p>🔴 <b>위치(노상/노외)를 소유(공영)보다 우선</b>한다 — 이 표 전체가 공영주차장이라 "공영" 은
 * 구분력이 없다. 위치 구분 없이 "공영" 만 온 원문만 {@code PBLPKLT} 로 간다.
 *
 * <p>원문은 {@code tb_pblpklt.pklt_type_nm} 에 그대로 적재되므로, 대응 규칙을 바꾸면 적재된 행을
 * <b>테이블 안에서 재분류</b>할 수 있다({@code tb_cvlcpt.dclr_type} 과 같은 원본 보존 방식).
 */
final class PblpkltCodeMapping {

    /**
     * 매핑에 없는 값의 fallback. cvlcpt 의 {@code 99}(미분류) 와 의미가 다르다 —
     * {@code ETC} 라도 실재하는 주차장이므로 지오코딩·공간매칭은 그대로 진행한다.
     * 공통코드에도 {@code PKLT_TYPE_CD / ETC / 기타} 로 등재돼 있다(마이그레이션 64).
     */
    static final String TYPE_CD_ETC = "ETC";

    /**
     * 원문 유형명 → 공통코드 값. 계획서 §3-1 대응표가 정본이다.
     *
     * <p>키는 원천이 보내는 문자열 그대로다. 공백만 다듬어 비교하고({@link #resolveTypeCd})
     * 그 밖의 정규화(괄호 제거·부분 일치)는 하지 않는다 — 부분 일치를 허용하면 "노상공영" 이
     * 규칙 순서에 따라 {@code ONSTR}/{@code PBLPKLT} 로 갈려 재현성이 사라진다.
     */
    static final Map<String, String> NAME_TO_CD = Map.of(
            "노상", "ONSTR",
            "노상(23년 조사)", "ONSTR",
            "노상공영", "ONSTR",
            "노외공영", "OFSTR",
            "공영", "PBLPKLT",
            "공한지", "VACLOT",
            "복층화", "MULTFLR");

    /**
     * 유형 코드 결정.
     *
     * @param pkltTypeCd 원천 {@code pklt_type_cd}. 채워져 있으면 <b>그 값이 이긴다</b>
     *                   (원천이 정리해 보내기 시작하면 우리 표를 거치지 않는다)
     * @param pkltTypeNm 원천 {@code pklt_type_nm}. 코드가 없을 때의 판정 입력
     * @return 매핑된 코드. 둘 다 없거나 표에 없으면 {@link #TYPE_CD_ETC}
     */
    static String resolveTypeCd(String pkltTypeCd, String pkltTypeNm) {
        if (pkltTypeCd != null && !pkltTypeCd.isBlank()) {
            return pkltTypeCd.strip();
        }
        if (pkltTypeNm == null || pkltTypeNm.isBlank()) {
            return TYPE_CD_ETC;
        }
        return NAME_TO_CD.getOrDefault(pkltTypeNm.strip(), TYPE_CD_ETC);
    }

    private PblpkltCodeMapping() {
    }
}
