package jeju.datahub.batch.history;

import org.springframework.batch.core.JobParametersBuilder;

/**
 * 실행 구분·실행자·상위 실행 — Job 파라미터 키와 구분값의 정본 (TSK-4337 Step 2).
 *
 * <p>진입점(스케줄러·연쇄·HTTP 트리거·운영 API)이 이 클래스로 파라미터를 싣고,
 * {@link BatchRunHistoryRecorder} 가 같은 키로 읽어 {@code batch.tb_btch_excn_hstry} 의
 * {@code excn_se_cd}·{@code excutr_id}·{@code up_excn_id} 에 쓴다. 키·값을 복사하지 말고 여기를 쓴다.
 *
 * <h2>🔴 세 값은 전부 <b>비식별</b> 파라미터다</h2>
 * 식별 파라미터로 넣으면 JobInstance 판정이 바뀐다 — 같은 실행이 "누가 눌렀나" 에 따라 다른
 * 인스턴스가 되고, 이력 칸 하나 때문에 재시작·중복 판정의 의미가 달라진다.
 */
public final class ExcnSe {

    /** Job 파라미터 키 — 실행 구분({@code excn_se_cd}). */
    public static final String PARAM_EXCN_SE = "excnSe";

    /** Job 파라미터 키 — 실행자 {@code user_id}({@code excutr_id}). 운영 API(MANUAL)만 싣는다. */
    public static final String PARAM_EXCUTR_ID = "excutrId";

    /** Job 파라미터 키 — 연쇄를 일으킨 앞 잡의 {@code jobExecutionId}({@code up_excn_id}). Long. */
    public static final String PARAM_PRNT_EXCN_ID = "prntExcnId";

    /** 스케줄러(cron)가 실행했다. */
    public static final String SCHDL = "SCHDL";

    /** 운영 화면에서 사람이 실행했다(Step 4 운영 API — 실행자가 함께 남는다). */
    public static final String MANUAL = "MANUAL";

    /** 앞 잡이 {@code COMPLETED} 로 끝나 이어서 실행됐다(민원 → 위험판정, 주차장 → 이용패턴). */
    public static final String CHAIN = "CHAIN";

    /** 기존 HTTP 트리거({@code POST /api/batch/*}, {@code X-API-Key})로 실행됐다. */
    public static final String API = "API";

    private ExcnSe() {
    }

    /** 실행 구분만 싣는다(SCHDL·API). */
    public static JobParametersBuilder tag(JobParametersBuilder builder, String excnSe) {
        return builder.addString(PARAM_EXCN_SE, excnSe, false);
    }

    /** 연쇄 실행 — 구분 {@link #CHAIN} + 앞 잡 실행 ID. */
    public static JobParametersBuilder chain(JobParametersBuilder builder, Long prntExcnId) {
        tag(builder, CHAIN);
        if (prntExcnId != null) {
            // Spring Batch 5 의 JobParameter 는 null 값을 받지 않는다 — 있을 때만 싣는다.
            builder.addLong(PARAM_PRNT_EXCN_ID, prntExcnId, false);
        }
        return builder;
    }

    /** 운영 API 실행 — 구분 {@link #MANUAL} + 실행자(Step 4). */
    public static JobParametersBuilder manual(JobParametersBuilder builder, String excutrId) {
        tag(builder, MANUAL);
        if (excutrId != null && !excutrId.isBlank()) {
            builder.addString(PARAM_EXCUTR_ID, excutrId, false);
        }
        return builder;
    }
}
