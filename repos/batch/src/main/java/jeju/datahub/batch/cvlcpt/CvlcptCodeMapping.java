package jeju.datahub.batch.cvlcpt;

import java.util.Map;

/**
 * 원천 문자열 → 공통코드 값 매핑.
 *
 * <p>{@link CvlcptGeocodeProcessor} 가 참조하는 유일한 코드 변환 지점이다. 코드값은 2026-08-03 에
 * {@code 04~08} → {@code 01~05} 로 재정렬됐고, 공통코드
 * ({@code database/migrations/sql/99-common-code-data.sql})·시드 빌더
 * ({@code tools/seed/cvlcpt/build_csv.py})·Node 프로토타입과 같은 번호 체계다.
 *
 * <h2>🔁 2026-09-01 — 분류는 3단이다: 원천 코드 → 원천 분류명 → CASE</h2>
 * 원천이 이미 판정한 값이 있으면 그것을 쓰고, <b>없을 때만</b> 우리가 계산한다. 원천 스키마가
 * 언제 채워지고 언제 비는지 우리가 정할 수 없으므로 <b>세 단계 모두 항상 살아 있어야 한다.</b>
 *
 * <pre>
 * ① cvlcpt_type_cd  ─→ typeCdIfKnown  ─→ 알려진 코드면 즉시 채택 (01~05 · 99)
 *      └ 없거나 미지 코드
 * ② prk_cvlcpt_type ─→ (분류 결과명 그대로)  ┐
 *      └ 없음                               ├→ TYPE_CD_MAP ─→ cvlcpt_type_cd
 * ③ dclr_type ─→ CASE_TYPE_NAMES ─→ 분류 결과명 ┘  (없으면 null → 99 + WARN)
 * </pre>
 *
 * <p>원천 표기({@code 장애인전용구역})와 공통코드 이름({@code 장애인전용구역 위반})이 다르다. ③은
 * 반드시 <b>CASE 로 분류 결과명을 만든 뒤</b> 그 이름을 {@link #TYPE_CD_MAP} 키로 넘긴다. ②는 원천이
 * 이미 결과명으로 주므로({@code 인도·횡단보도 점유} 등) 그대로 키가 된다.
 *
 * <p><b>②를 되살려도 분류 결과는 바뀌지 않는다 — 실측으로 확인했다.</b> 새 원천
 * {@code tb_cvlcpt} 78,386행에서 {@code prk_cvlcpt_type} 과 ③ CASE 결과를 전수 대조한 결과
 * <b>일치 40,878 / 불일치 0 / CASE 만 잡힌 행 0 / 둘 다 없음 37,508</b> 이었다
 * (2026-08-24 이 구 원천 156,883행에서 확인한 100% 일치와 같은 결론).
 * 즉 ②는 ③의 결과를 바꾸는 것이 아니라, <b>원천이 CASE 좌변 밖의 새 유형을 보내기 시작했을 때
 * 그것을 놓치지 않기 위한</b> 경로다.
 *
 * <p>둘 다 없는 37,508행(47.9%)은 비주차 민원이라 {@link #TYPE_CD_ETC} 로 적재된다 — 설계상
 * 정상이다. 이 비율이 갑자기 튀면 원천 표기가 바뀐 신호다(축 2 의 테스트가 잡는다).
 */
final class CvlcptCodeMapping {

    /**
     * 분류 결과명 → {@code CVLCPT_TYPE_CD}. 공통코드의 {@code cd_nm} 과 정확히 일치시킨다.
     *
     * <p>⚠️ 키는 <b>분류 결과명</b>이지 원천 {@code dclr_type} 이 아니다({@link #CASE_TYPE_NAMES} 참고).
     */
    static final Map<String, String> TYPE_CD_MAP = Map.of(
            "안전시설 인근 위반", "01",
            "인도·횡단보도 점유", "02",
            "장애인전용구역 위반", "03",
            "친환경차충전구역 위반", "04",
            "기타 불법주정차", "05");

    /** 미분류 — 지오코딩을 생략하고 {@code emd_cd}/{@code lat}/{@code lot} 을 NULL 로 적재한다. */
    static final String TYPE_CD_ETC = "99";

    /**
     * 원천 {@code dclr_type} → 분류 결과명. 사전조사의 유형 분류 SQL CASE 를 그대로
     * 옮긴 것이다.
     *
     * <pre>{@code
     * CASE
     *   WHEN dclr_type IN ('횡단보도','인도')                    THEN '인도·횡단보도 점유'
     *   WHEN dclr_type IN ('교차로 모퉁이','소화전','버스정류소',
     *                      '어린이보호구역','소방차전용구역')      THEN '안전시설 인근 위반'
     *   WHEN dclr_type = '장애인전용구역'                        THEN '장애인전용구역 위반'
     *   WHEN dclr_type = '친환경차충전구역'                      THEN '친환경차충전구역 위반'
     *   WHEN dclr_type = '기타불법주정차'                        THEN '기타 불법주정차'
     *   ELSE NULL
     * END
     * }</pre>
     *
     * <p>좌변 10종은 원천 {@code dclr_type} 34종 중 실재가 확인된 값들이다. 주차민원 유형인데 여기
     * 빠진 값이 나오면(원천 표기 변경 등) 이 맵을 보강한다 — 그때까지는 {@link #TYPE_CD_ETC} 로 간다.
     */
    static final Map<String, String> CASE_TYPE_NAMES = Map.of(
            "횡단보도", "인도·횡단보도 점유",
            "인도", "인도·횡단보도 점유",
            "교차로 모퉁이", "안전시설 인근 위반",
            "소화전", "안전시설 인근 위반",
            "버스정류소", "안전시설 인근 위반",
            "어린이보호구역", "안전시설 인근 위반",
            "소방차전용구역", "안전시설 인근 위반",
            "장애인전용구역", "장애인전용구역 위반",
            "친환경차충전구역", "친환경차충전구역 위반",
            "기타불법주정차", "기타 불법주정차");

    /**
     * 원천 {@code src_path} → {@code SRC_CD}.
     *
     * <p>2026-08-03 원천 실측: 모바일앱 145,648 / 포털 11,212 / API 23 / 콜센터 0.
     * 기존 {@code API → CALL} 매핑은 오류였다 — 공통코드에 {@code API} 가 따로 있다.
     * {@code 콜센터} 는 현재 0건이지만 향후 유입 대비로 유지한다.
     */
    static final Map<String, String> SRC_CD_MAP = Map.of(
            "모바일앱", "APP",
            "포털", "PORTAL",
            "API", "API",
            "콜센터", "CALL");

    /** 매핑에 없는 유입 경로. */
    static final String SRC_CD_ETC = "ETC";

    private CvlcptCodeMapping() {
    }

    /**
     * 분류 결과명을 구한다 — {@code prk_cvlcpt_type} 우선, 없으면 {@code dclr_type} 을 CASE 에 태운다.
     *
     * <p>🔁 2026-09-01 — 첫 인자가 <b>실제로 채워져 들어온다</b>. 그 전에는 호출부가
     * 리터럴 {@code null} 을 넘겨 CASE 경로만 돌았다.
     *
     * @return 분류 결과명. 둘 다 없거나 CASE 좌변 밖이면 {@code null}(= SQL {@code ELSE NULL})
     */
    static String typeName(String prkCvlcptType, String dclrType) {
        if (prkCvlcptType != null && !prkCvlcptType.isBlank()) {
            return prkCvlcptType.strip();
        }
        if (dclrType == null || dclrType.isBlank()) {
            return null;
        }
        return CASE_TYPE_NAMES.get(dclrType.strip());
    }

    /**
     * 원천이 준 {@code cvlcpt_type_cd} 를 <b>그대로 쓸 수 있는지</b> 판정한다.
     *
     * <p>원천값을 무조건 신뢰하면 공통코드에 없는 값이 서비스 테이블로 새어 든다 — 화면의 유형
     * 필터가 조용히 그 행을 못 찾게 된다. 그래서 <b>우리 코드 체계에 있는 값일 때만</b> 채택하고,
     * 아니면 {@code null} 을 돌려 호출자가 자체 분류 경로로 되돌아가게 한다.
     *
     * @return 알려진 코드({@code 01~05} 또는 {@value #TYPE_CD_ETC})면 그 값, 아니면 {@code null}
     */
    static String typeCdIfKnown(String rawTypeCd) {
        if (rawTypeCd == null || rawTypeCd.isBlank()) {
            return null;
        }
        String trimmed = rawTypeCd.strip();
        if (TYPE_CD_ETC.equals(trimmed) || TYPE_CD_MAP.containsValue(trimmed)) {
            return trimmed;
        }
        return null;
    }

    /**
     * 원천이 준 {@code src_cd} 를 그대로 쓸 수 있는지 판정한다({@link #typeCdIfKnown} 과 같은 이유).
     *
     * @return 알려진 코드면 그 값, 아니면 {@code null}(호출자가 {@link #srcCd} 로 변환한다)
     */
    static String srcCdIfKnown(String rawSrcCd) {
        if (rawSrcCd == null || rawSrcCd.isBlank()) {
            return null;
        }
        String trimmed = rawSrcCd.strip();
        if (SRC_CD_ETC.equals(trimmed) || SRC_CD_MAP.containsValue(trimmed)) {
            return trimmed;
        }
        return null;
    }

    /**
     * 분류 결과명 → 코드.
     *
     * @return {@code CVLCPT_TYPE_CD}. 미지 이름이면 {@code null} — 호출자가 {@link #TYPE_CD_ETC} 와
     *         WARN 로그를 처리한다. 여기서 바로 {@code 99} 를 돌려주면 "정상 분류된 기타"와
     *         "분류 실패"를 호출자가 구분하지 못해 원천 표기 변경이 조용히 묻힌다
     */
    static String typeCd(String typeName) {
        return typeName == null ? null : TYPE_CD_MAP.get(typeName);
    }

    /**
     * 원천 감정값 원문 → {@code APRS_CD} 공통코드.
     *
     * <p>원천({@code minwon_sentiment.dclr_cn_sntmnt})은 한글 원문(<b>긍정 / 부정 / 보통</b> — 🔁 현행 <b>좋음 / 보통 / 나쁨</b>)을 준다.
     * 감정 분석은 이 프로젝트가 하지 않는다 — 원천이 이미 분석한 값을 코드로 바꿔 담을 뿐이다.
     *
     * <p>🔁 2026-08-24 — 코드그룹이 {@code SNTMNT_CD}(감성) 에서 {@code APRS_CD}(감정) 로 바뀌었다.
     * 데이터표준사전에 '감성' 은 단어·용어 통틀어 0건이고 '감정 = APRS(Appraisal)' 이 등재돼 있다
     * (49-STANDARDIZE-PARKING-NAMES.sql).
     *
     * <p>🔁 2026-11-13 — 원천 값이 <b>좋음 / 보통 / 나쁨</b> 으로 바뀌었다. 표에 없어 {@code 좋음}·{@code 나쁨} 이
     * 전부 {@code null} 로 적재됐다(로컬 3,602행 중 3,267행 NULL · {@code 보통} 335행만 {@code NEUTRAL}). 새 표기를 더하고
     * 종전 표기({@code 긍정}·{@code 부정})도 그대로 받는다 — 원천이 다시 바꿔도 조용히 빠지지 않게.
     * ⚠️ 이미 적재된 행은 원문을 보관하지 않아 DB 안에서 재분류할 수 없다 — 민원 backfill 재수집이 덮어쓴다.
     */
    static final Map<String, String> APRS_CD_MAP = Map.of(
            "긍정", "POSITIVE",
            "좋음", "POSITIVE",
            "부정", "NEGATIVE",
            "나쁨", "NEGATIVE",
            "보통", "NEUTRAL");

    /**
     * @return {@code APRS_CD} 코드값. <b>매핑에 없거나 원천이 값을 주지 않으면 {@code null}</b> —
     *         {@code srcCd} 와 달리 {@code ETC} 로 뭉개지 않는다. "분석하지 않음"과 "분석했으나
     *         분류 불가"를 구분할 방법이 없는 상태에서 임의의 코드를 만들면 감정별 집계가 오염된다.
     */
    static String aprsCd(String rawSentiment) {
        if (rawSentiment == null || rawSentiment.isBlank()) {
            return null;
        }
        String trimmed = rawSentiment.strip();
        // 🔁 2026-09-01 — 원천이 이미 코드({@code NEUTRAL})로 줄 수도 있다. 새 원천의 컬럼명이
        //    dclr_cn_sntmnt 에서 aprs_cd 로 바뀌어(값은 아직 한글 원문) 언제 코드로 바뀌어도
        //    이상하지 않다. 코드로 오면 변환 없이 통과시킨다 — 그때 조용히 전 행이 null 이 되면
        //    감정별 집계가 통째로 빈다.
        if (APRS_CD_MAP.containsValue(trimmed)) {
            return trimmed;
        }
        return APRS_CD_MAP.get(trimmed);
    }


    /** 유입 경로 → {@code SRC_CD}. 매핑에 없으면 {@link #SRC_CD_ETC}. */
    static String srcCd(String srcPath) {
        if (srcPath == null || srcPath.isBlank()) {
            return SRC_CD_ETC;
        }
        return SRC_CD_MAP.getOrDefault(srcPath.strip(), SRC_CD_ETC);
    }
}
