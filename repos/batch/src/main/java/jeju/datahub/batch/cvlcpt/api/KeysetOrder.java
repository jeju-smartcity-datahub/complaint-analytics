package jeju.datahub.batch.cvlcpt.api;

import java.util.regex.Pattern;

/**
 * keyset 키의 <b>정렬 방식 선언</b> — SQL 리터럴 형태와 자바 비교기를 <b>한 곳에서</b> 뽑는다
 * (🔁 2026-09-17 TSK-4161 Phase 2 Step 10).
 *
 * <table>
 *   <tr><th>값</th><th>SQL 리터럴</th><th>자바 비교</th><th>쓰는 곳</th></tr>
 *   <tr><td>{@link #LEXICOGRAPHIC}</td><td>{@code '커서'}</td><td>{@code String.compareTo}</td>
 *       <td>민원 {@code cvlcpt_id} · 주차장 {@code pklt_id}({@code String})</td></tr>
 *   <tr><td>{@link #NUMERIC}</td><td>{@code 커서}(따옴표 없음)</td><td>{@code Long.compare}</td>
 *       <td>단속 {@code crdn_id}({@code Nullable(Int64)})</td></tr>
 * </table>
 *
 * <h2>🔴 왜 리터럴과 비교기를 한 선언에 묶나</h2>
 * SQL 정렬({@code ORDER BY 키})과 자바 미전진 판정이 <b>같은 기준</b>이어야 한다. 둘을 따로 정하면
 * 한쪽만 바뀐다 —
 * <ul>
 *   <li>숫자 키를 문자열로 비교하면 SQL 은 {@code 999 → 1000} 으로 전진했는데 자바는
 *       {@code "1000" < "999"} 로 보고 <b>미전진 오탐 → 매 실행 FAILED</b>(단속 195,418행은
 *       자릿수 경계를 반드시 넘는다).</li>
 *   <li>문자열 키를 숫자로 파싱·캐스팅하면({@code toUInt32(pklt_id)} 등) 주차장의 사전순
 *       ({@code min "1"}·{@code max "999"})과 커서 비교가 어긋나 <b>행이 조용히 누락</b>된다.
 *       🔴 주차장 키에 캐스팅을 섞지 않는다.</li>
 * </ul>
 * 참고로 원천(ClickHouse)은 리터럴을 <b>컬럼 타입</b>으로 변환해 비교한다(스파이크 실측 —
 * {@code crdn_id > '999'} 와 {@code > 999} 동일). 즉 정렬 방식을 실제로 정하는 것은 컬럼 타입이고,
 * 이 선언은 그것을 <b>자바 쪽에 정확히 옮겨 적는</b> 역할이다. 문자열 컬럼에 NUMERIC 을 잘못 걸면
 * 첫 페이지부터 422 로 실패한다(안전한 방향).
 */
public enum KeysetOrder {

    /** 문자열 사전순 — 커서 형식 검증은 민원이 쓰던 {@link DhubSql#stringLiteral} 그대로다. */
    LEXICOGRAPHIC {
        @Override
        public String literal(String cursor) {
            return DhubSql.stringLiteral(cursor);
        }

        @Override
        public int compare(String left, String right) {
            return left.compareTo(right);
        }
    },

    /**
     * 부호 없는 정수. 🔴 바인딩 파라미터가 없는 API 라 커서가 SQL 에 <b>따옴표 없이</b> 이어 붙는다 —
     * 숫자만 허용해야 {@code 1 OR 1=1} 같은 값이 조립 전에 막힌다.
     */
    NUMERIC {
        @Override
        public String literal(String cursor) {
            return String.valueOf(parse(cursor));
        }

        @Override
        public int compare(String left, String right) {
            return Long.compare(parse(left), parse(right));
        }
    };

    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("[0-9]+");

    /**
     * 커서를 SQL 리터럴로 만든다.
     *
     * @throws IllegalArgumentException 형식이 이 정렬 방식에 맞지 않을 때(SQL 조립 전에 막는다)
     */
    public abstract String literal(String cursor);

    /** SQL {@code ORDER BY} 와 같은 기준의 비교. */
    public abstract int compare(String left, String right);

    /**
     * 🔴 커서 미전진 판정 — 새 커서가 직전 커서보다 <b>엄격히 커야</b> 전진이다.
     * 같거나 작으면 {@code ORDER BY} 누락·키 유일성 붕괴이며, 그대로 두면 같은 페이지를 무한 반복한다.
     */
    public boolean isAdvanced(String previous, String next) {
        return compare(next, previous) > 0;
    }

    private static long parse(String cursor) {
        if (cursor == null || !UNSIGNED_INTEGER.matcher(cursor).matches()) {
            throw new IllegalArgumentException(
                    "NUMERIC keyset 커서는 부호 없는 정수만 허용합니다: " + cursor);
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("NUMERIC keyset 커서가 Int64 범위를 넘습니다: " + cursor, e);
        }
    }
}
