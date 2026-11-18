package jeju.datahub.lifezone.service;

/**
 * 생활권 격자 등급 (TSK-4088 D5 · D17).
 *
 * <p>{@code HotspotGrade}(80/60/40 고정)를 <b>재사용하지 않는다</b> — 생활권은 임계값이 분석유형마다
 * 다르고, 그마저도 상수가 아니라 <b>조회 결과에서 산출</b>된다(D17). 그래서 이 enum 은
 * <b>등급 코드·이름만</b> 갖고, 경계는 {@link LifezoneGradeScale} 이 값 객체로 따로 낸다.
 * 🔁 수급(TSK-4258)은 같은 코드를 부족률 등간격({@link #ofShrtRt} — D19)으로 판정한다. 경계는
 * {@link LifezoneGradeScale} 이 아니라 {@link LifezoneSupply.GrdBndry}(설정 표 {@code SPLY_GRD_BNDRY1~4} — D20)다.
 *
 * <p>코드값 3종({@code SEVERE}/{@code WARN}/{@code CAUTION})은 집중구역과 <b>같게</b> 두고
 * {@code NORMAL}·{@code LOW} 를 더해 5종으로 한다 — 프론트가 등급 → 색 매핑을 공유할 수 있다.
 * 라벨은 응답 {@code gradeNm} 이 정본이라 집중구역의 {@code NORMAL='양호'} 와 이름이 달라도
 * (생활권은 {@code 보통}) 문제되지 않는다.
 *
 * <p>🔴 <b>선언 순서가 계약이다</b> — 심각 → 낮음(경계 내림차순). 응답 {@code gradeCriteria} 의
 * 행 순서이자 {@link LifezoneGradeScale} 이 경계 배열을 담는 순서다. 상수를 추가·재배치하면
 * 두 곳이 함께 어긋난다.
 */
public enum LifezoneGrade {

    /** 심각 — 산출 경계 5단계 중 최상위 */
    SEVERE("심각"),

    /** 경고 */
    WARN("경고"),

    /** 주의 */
    CAUTION("주의"),

    /** 보통 */
    NORMAL("보통"),

    /**
     * 낮음 — 값이 1 이상인 격자의 최하위 구간 (값 0 인 격자는 아예 그리지 않는다 — D5).
     * 🔁 수급(TSK-4258)에서는 부족률 20% 미만이다(D19).
     */
    LOW("낮음");

    /** 화면 표시용 등급명 */
    private final String gradeNm;

    LifezoneGrade(String gradeNm) {
        this.gradeNm = gradeNm;
    }

    public String getGradeNm() {
        return gradeNm;
    }

    /**
     * 결과 리스트(순위)에 싣는 등급인지 — 심각·경고·주의만 (TSK-4227). 보통·낮음은 지도·요약에만 남는다.
     *
     * <p>🔴 프론트 {@code lifezoneGrade.ts} {@code LISTED_GRADES}·{@code isListedGrade} 와 같은 집합이다 —
     * 한쪽만 바꾸지 않는다.
     */
    public boolean isListed() {
        return this == SEVERE || this == WARN || this == CAUTION;
    }

    /**
     * 부족률 등간격으로 등급을 고른다 — 수급 전용 (TSK-4258 D19 · D13 개정 · 🔁 D20 경계 인자형).
     *
     * <p>부족률 = {@code cvlcptCnt / dmndIdx}(수요지수 = 면수 + 민원) 이고 아래 경계 포함(≥)이다 —
     * 심각 하한 이상 심각 · 경고 하한 이상 경고 · 주의 하한 이상 주의 · 보통 하한 이상 보통 · 그 밖 낮음
     * (기본 80/60/40/20 — 🔁 D20 부터 경계는 설정 표 값을 호출부가 넘긴다).
     * 🔴 응답 {@code shortageRatio}(소수 4자리 반올림값)가 아니라 <b>반올림 전 정수 비교</b>
     * ({@code cvlcptCnt × 100 >= 경계 × dmndIdx})로 판정한다 — {@code 0.79996} 이 {@code 0.8000} 으로 반올림돼
     * 한 칸 올라가지 않게. 공급 0 격자는 부족률 100% 라 항상 심각이다(경계 ≤ 99). 항상 5단계 중 하나를 돌려준다.
     *
     * @param cvlcptCnt 격자 주차 민원 건수 (0 이상)
     * @param dmndIdx   수요지수 = 공영주차면 합계 + 민원 건수
     * @param bndry     등급 경계 4개 (보통·주의·경고·심각 하한, %)
     * @throws IllegalArgumentException {@code dmndIdx <= 0} — 분모가 없으면 부족률도 없다
     */
    public static LifezoneGrade ofShrtRt(int cvlcptCnt, int dmndIdx, LifezoneSupply.GrdBndry bndry) {
        if (dmndIdx <= 0) {
            throw new IllegalArgumentException("수요지수는 1 이상이어야 합니다: " + dmndIdx);
        }
        long scaled = (long) cvlcptCnt * 100;
        if (scaled >= (long) bndry.severe() * dmndIdx) {
            return SEVERE;
        }
        if (scaled >= (long) bndry.warn() * dmndIdx) {
            return WARN;
        }
        if (scaled >= (long) bndry.caution() * dmndIdx) {
            return CAUTION;
        }
        if (scaled >= (long) bndry.normal() * dmndIdx) {
            return NORMAL;
        }
        return LOW;
    }

    /**
     * 요청 문자열 → 등급. 미지원·공백이면 {@code null} — 400 판단은 호출부가 한다
     * ({@link LifezoneQuadrant#ofCode} 와 같은 형태 · TSK-4258).
     *
     * <p>{@code valueOf} 를 그대로 쓰지 않는 이유: {@link IllegalArgumentException} 은 전역 핸들러가
     * 없어 <b>500</b> 으로 나간다. 대소문자·앞뒤 공백은 관대하게 받는다({@code trim + toUpperCase}).
     *
     * @param code {@code SEVERE}~{@code LOW}. {@code null}·빈 문자열은 {@code null}
     */
    public static LifezoneGrade ofCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String key = code.trim().toUpperCase();
        for (LifezoneGrade g : values()) {
            if (g.name().equals(key)) {
                return g;
            }
        }
        return null;
    }
}
