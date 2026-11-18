package jeju.datahub.batch.sftychck;

import java.time.LocalDate;
import java.util.Set;

/** 원천 코드를 그대로 보존한다. D 는 원천 등급이며 투자분석의 A/B/C 점수로 변환하지 않는다.
 * 날짜·점검유형·점검기간 결측은 Processor 가 NULL 로 유지한다.
 */
public final class PkltSftyChckCodeMapper {

    static final Set<String> RSLT_CODES = Set.of("A", "B", "C", "D");
    static final Set<String> TYPE_CODES = Set.of("REGULAR", "OCCASION", "PRECISION");
    static final Set<String> PRD_CODES = Set.of("H1", "H2");

    private PkltSftyChckCodeMapper() {
    }

    /** 매핑 결과 — 성공이면 {@code code}, 실패면 {@code failRsn} 이 있다. */
    public record Result(String code, String failRsn) {
        static Result ok(String code) {
            return new Result(code, null);
        }

        static Result fail(String failRsn) {
            return new Result(null, failRsn);
        }

        public boolean mapped() {
            return code != null;
        }
    }

    /** 결과 — 목록 밖이면 {@code RSLT_UNMAPPED}. */
    public static Result mapRslt(String raw) {
        return allowList(raw, RSLT_CODES, PkltSftyChckSkippableException.RSLT_UNMAPPED);
    }

    /** 유형 — 목록 밖이면 {@code TYPE_UNMAPPED}. */
    public static Result mapType(String raw) {
        return allowList(raw, TYPE_CODES, PkltSftyChckSkippableException.TYPE_UNMAPPED);
    }

    /**
     * 시기 — 원천값이 NULL·공백이면 점검월로 유도, 값이 있는데 목록 밖이면 {@code PRD_UNMAPPED}.
     *
     * @param chckDate 검증을 마친 점검일(Processor 가 {@code YMD_INVALID} 판정 <b>뒤에</b> 부르므로 null 이 아니다)
     */
    public static Result mapPrd(String raw, LocalDate chckDate) {
        if (raw == null || raw.isBlank()) {
            return Result.ok(chckDate.getMonthValue() <= 6 ? "H1" : "H2");
        }
        return allowList(raw, PRD_CODES, PkltSftyChckSkippableException.PRD_UNMAPPED);
    }

    private static Result allowList(String raw, Set<String> allowed, String failRsn) {
        if (raw == null) {
            return Result.fail(failRsn);
        }
        String v = raw.trim();
        return allowed.contains(v) ? Result.ok(v) : Result.fail(failRsn);
    }
}
