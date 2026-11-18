package jeju.datahub.batch.pblpklt;

import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.item.ItemProcessor;

import jeju.datahub.batch.pblpklt.api.PblpkltOriginRow;

/**
 * 가이드 3-1 의 "주소 패턴 검증(관측용, 값 변형 없음)" — <b>4종 패턴 판정</b>.
 *
 * <p>이 Processor 의 출력은 두 갈래로 갈라진다:
 * <ul>
 *   <li>원본 row → 그대로 origin 테이블에 적재 (값 변형 없음)</li>
 *   <li>판정 결과(여러 건 가능) → anomaly 테이블에 적재</li>
 * </ul>
 *
 * <h2>왜 {@code List<AnomalyRecord>} 를 같이 emit 하는가</h2>
 * Spring Batch 가 Processor 출력 1개 → Writer 입력 1개를 기대하므로, 원본 row 와
 * anomaly 목록을 <b>같은 객체에 묶어</b> 내보낸다. 그리고 Writer 측에서는
 * {@link org.springframework.batch.item.support.ClassifierCompositeItemWriter} 또는
 * 별도 컴포지트로 갈라치기 한다 (구현은 {@link PblpkltFetchJobConfig}).
 *
 * <h2>판정 패턴 (가이드 3-1, 2026-08-11 개정 반영)</h2>
 * <ul>
 *   <li>{@code DIRECTION_SUFFIX} — 방향 접미사(남측/북측/동측/서측)</li>
 *   <li>{@code NON_ADDRESS_NAME} — 지번·도로명 패턴이 아닌 지명</li>
 *   <li>{@code ETC} — 위 둘에 안 걸리지만 이상</li>
 * </ul>
 * {@code MULTI_LOT}(쉼표로 필지·도로명 여러 개 결합)은 실측 30%가 여기 걸려 확인해보니, 정해진
 * 검색 규칙(첫 필지만 사용, 가이드 6-1)으로 정상 처리되는 케이스라 판정 패턴에서 제외됐다 —
 * 아래 {@link #process} 구현부 주석 참고.
 *
 * <h2>🔴 판정 입력은 {@code pklt_addr} 이다 (🔁 2026-09-16)</h2>
 * 종전에는 원천(7필드)에 주소 컬럼이 없어 {@code pklt_nm} 을 "주소 역할" 로 보고 판정했다.
 * 그런데 anomaly 테이블에 <b>저장되는 값은 {@code orgnl_addr}({@code pklt_addr})</b> 이라
 * <b>판정과 저장이 어긋나 있었다</b> — 이름으로 판정해 놓고 주소를 기록했고, 옛 데이터셋엔
 * {@code pklt_addr} 컬럼 자체가 없어 그 INSERT 가 {@code null value in column "orgnl_addr"} 로
 * Job 을 죽였다(2026-09-16 실측).
 *
 * <p>그래서 판정 입력을 {@link #anomalyInput(PblpkltOriginRow)} <b>한 곳</b>으로 모았다.
 * {@link PblpkltItemWriter} 가 저장할 때도 같은 메서드를 쓰므로 둘이 다시 갈릴 수 없다.
 * TSK-4142 이후 원천이 34필드가 되어 {@code pklt_addr} 결측은 0건이며, 폴백은 옛 데이터셋
 * 호환용으로만 남는다.
 */
public class PblpkltAddressAnomalyClassifier implements ItemProcessor<PblpkltOriginRow, PblpkltFetchRow> {

    /** 방향 접미사 — 가이드 3-1 표 그대로. */
    private static final List<String> DIRECTION_SUFFIXES = List.of("남측", "북측", "동측", "서측");

    /**
     * 주소 특이 패턴 판정의 입력이자 anomaly 테이블 {@code orgnl_addr} 에 적재되는 값.
     *
     * <p>🔴 <b>판정하는 값과 저장하는 값은 반드시 같아야 한다</b> — 다르면 "왜 이 주소가
     * 이상치로 잡혔나" 를 추적할 수 없다. 그래서 {@link PblpkltItemWriter} 도 이 메서드를 쓴다.
     * 어느 한쪽만 고치는 실수를 구조적으로 막으려고 {@code static} 으로 뺐다.
     *
     * <p>{@code pklt_addr} 이 없으면(옛 7필드 데이터셋) {@code pklt_nm} 으로 폴백한다.
     * 둘 다 없으면 {@code null} 이고, 그 행은 판정 대상에서 빠진다 —
     * {@code orgnl_addr} 은 NOT NULL 이라 null 을 적재하면 Job 이 죽는다.
     */
    static String anomalyInput(PblpkltOriginRow row) {
        return row == null ? null : anomalyInput(row.pkltAddr(), row.pkltNm());
    }

    /**
     * 위 메서드의 실제 식 — Writer 가 보는 {@link PblpkltRecord} 는 원본 row 가 아니라 평탄화된
     * 레코드라 타입이 달라서, <b>식 자체</b>를 공유하려고 두 인자를 받는 형태로 뺐다.
     */
    static String anomalyInput(String pkltAddr, String pkltNm) {
        return pkltAddr != null ? pkltAddr : pkltNm;
    }

    /** 한 행이 여러 패턴에 동시 해당하면 anomaly_type 별로 행이 여러 개 쌓인다 (가이드 3-1). */
    @Override
    public PblpkltFetchRow process(PblpkltOriginRow row) {
        if (row == null) {
            return null;
        }
        String raw = anomalyInput(row);  // 주소. Writer 가 저장하는 값과 같은 식이다.
        List<String> anomalies = new ArrayList<>(2);

        // 1) DIRECTION_SUFFIX — 방향 접미사
        if (raw != null) {
            for (String suffix : DIRECTION_SUFFIXES) {
                if (raw.contains(suffix)) {
                    anomalies.add("DIRECTION_SUFFIX");
                    break;
                }
            }
        }
        // 2) NON_ADDRESS_NAME — 지번·도로명 패턴이 아닌 경우
        //    지번: "리이름 + 숫자-숫자" 또는 "리이름 + 숫자"
        //    도로명: "로/길/대로" + 숫자
        //    위 둘 어느 쪽에도 안 맞으면 지명 (예: "한림 상두거리")
        if (raw != null && !isLotOrRoadName(raw)) {
            anomalies.add("NON_ADDRESS_NAME");
        }
        // 3) ETC — 위 둘에 안 걸리는 경우 (현재 패턴에선 거의 안 잡힘, 자리는 남겨둠)
        //
        // ⚠️ 가이드 3-1 개정 (2026-08-11) — MULTI_LOT 은 더 이상 anomaly 가 아니다.
        //    쉼표로 필지가 여러 개 묶인 건 (예: 한림리 1198-3, 1198-4) 비정상이 아니라
        //    예정된 정상 분기다 — 가이드 6-1 에서 "첫 번째 필지만 지오코딩 입력으로 쓴다" 로
        //    처리 규칙이 정해져 있다. anomaly 에 넣으면 운영상 의미 없는 알람만 늘어난다.

        // anomalies 가 비어 있어도 origin 은 무조건 적재해야 하므로 row 는 항상 emit.
        return new PblpkltFetchRow(row, anomalies);
    }

    private static boolean isLotOrRoadName(String s) {
        // 1) 지번 패턴: 한글/영문 시작 + 숫자(-숫자)?(,...) — 단순 포함 검사.
        if (s.matches(".*[\\p{L}]+\\s*\\d+(-\\d+)?.*")) {
            return true;
        }
        // 2) 도로명 패턴: 로/길/대로/번길 등
        if (s.matches(".*(로|길|대로|번길).*")) {
            return true;
        }
        return false;
    }
}
