package jeju.datahub.lifezone.service;

/**
 * 수급 분석의 사분면 — <b>범주형 4종</b>이다 (TSK-4089 · 인계서 §2-3).
 *
 * <p>🔴 민원건수 분석의 {@link LifezoneGrade}(심각→낮음 5단계)와 <b>성격이 다르다</b>.
 * 등급은 하나의 값을 크기순으로 자른 <b>순서 있는</b> 구간이지만, 사분면은 민원·공급 두 축을
 * 교차시킨 <b>순서 없는 범주</b>다. 그래서 지도 색도 단계색(순차 팔레트)이 아니라 범주형
 * 4색이어야 한다(인계서 §4-2) — 두 enum 을 합치면 이 구분이 지워진다.
 *
 * <p>우선순위 개념은 Q1 하나뿐이다(부족 의심 구역). Q2~Q4 사이에는 "더 나쁨"이 없다.
 */
public enum LifezoneQuadrant {

    /** 민원高 · 공급低 — 부족 의심 구역 (최우선) */
    Q1("부족 의심", "민원 많음 · 공영주차면 적음"),

    /** 민원高 · 공급充 — 공급은 있는데 민원이 몰리는 구역 */
    Q2("민원 집중", "민원 많음 · 공영주차면 있음"),

    /** 민원低 · 공급低 — 공급이 적지만 민원도 적은 구역 */
    Q3("공급 부족", "민원 적음 · 공영주차면 적음"),

    /** 민원低 · 공급充 — 특이사항 없는 구역 */
    Q4("양호", "민원 적음 · 공영주차면 있음");

    /**
     * 사분면명. 🔴 인계서 §4-4 표현 규칙을 지킨 문구다 — {@code 주차 부족 지역}·{@code 위험 지역}
     * 같은 표현을 쓰지 않는다(수요를 측정하지 않았으므로 "부족" 을 단정할 수 없다).
     */
    private final String qdrnNm;

    /** 판정 근거 요약. 범례의 임계값 숫자와 함께 쓴다 */
    private final String qdrnExpln;

    LifezoneQuadrant(String qdrnNm, String qdrnExpln) {
        this.qdrnNm = qdrnNm;
        this.qdrnExpln = qdrnExpln;
    }

    public String getQdrnNm() {
        return qdrnNm;
    }

    public String getQdrnExpln() {
        return qdrnExpln;
    }

    /**
     * 코드 → 상수. <b>미지원 값이면 {@code null}</b> 이다 — 호출부(서비스)가 400 으로 바꾼다
     * ({@link LifezoneClsfMthd#ofCode} 와 같은 형태 · TSK-4227 D17).
     *
     * <p>{@code valueOf} 를 그대로 쓰지 않는 이유: {@link IllegalArgumentException} 은 전역 핸들러가
     * 없어 <b>500</b> 으로 나간다. 대소문자·앞뒤 공백은 관대하게 받는다({@code trim + toUpperCase}).
     *
     * @param code {@code Q1}~{@code Q4}. {@code null}·빈 문자열은 호출부가 먼저 거른다
     */
    public static LifezoneQuadrant ofCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String key = code.trim().toUpperCase();
        for (LifezoneQuadrant q : values()) {
            if (q.name().equals(key)) {
                return q;
            }
        }
        return null;
    }

    /**
     * 민원·공급 판정 결과로 사분면을 고른다.
     *
     * <p>🔴 경계는 <b>기준값 포함</b>이다({@code >=} · {@code <=}) — 인계서 §5-6 이 검증한다.
     * 호출부가 비교를 하고 이 메서드는 조합만 한다: 비교를 여기에 두면 임계값을 함께 넘겨야 해
     * 부동소수 비교가 두 곳으로 흩어진다.
     *
     * @param complaintHigh 민원건수 &gt;= P80 이면 true
     * @param supplyLow     주차면수 &lt;= P70 이면 true
     */
    public static LifezoneQuadrant of(boolean complaintHigh, boolean supplyLow) {
        if (complaintHigh) {
            return supplyLow ? Q1 : Q2;
        }
        return supplyLow ? Q3 : Q4;
    }
}
