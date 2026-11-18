package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.SkipListener;

import jeju.datahub.batch.pblpklt.api.PblpkltOriginRow;

/**
 * 🔴 A 회귀 (TSK-4142 §10-1) — {@link PblpkltSkipListener} 의 제네릭 실인자가
 * <b>{@code pblpkltFetchStep} 의 청크 타입</b>과 같은지 고정한다.
 *
 * <h2>왜 이런 테스트가 필요한가</h2>
 * 제네릭이 어긋나도(예전: {@code SkipListener<PblpkltFetchRow, PblpkltRecord>})
 * <b>컴파일은 통과한다</b> — {@code FaultTolerantStepBuilder.listener(Object)} 오버로드가 잡히고,
 * 그 경로는 {@code @OnSkipInProcess} 애노테이션만 스캔하므로 콜백이 <b>0회</b> 등록된 채
 * 스킵 기록이 통째로 사라진다(1회차 E2E 에서 102건 유실). 에러도 경고도 없으니
 * 정적 검증으로는 잡히지 않는다. 그래서 타입 자체를 테스트로 못박는다.
 *
 * <p>{@code pblpkltFetchStep} 은 {@code .<PblpkltOriginRow, PblpkltRecord>chunk(...)} 로
 * 선언돼 있다. {@code PblpkltFetchRow} 는 {@code CompositeItemProcessor} <b>내부의 중간 타입</b>일
 * 뿐이라 Spring Batch 가 스킵 콜백에 넘기는 아이템이 아니다.
 */
class PblpkltSkipListenerTypeTest {

    @Test
    @DisplayName("제네릭 실인자가 <PblpkltOriginRow, PblpkltRecord> 다 — Step 청크 타입과 일치")
    void implementsSkipListenerWithChunkTypes() {
        ParameterizedType skipListenerItf = Arrays.stream(PblpkltSkipListener.class.getGenericInterfaces())
                .filter(ParameterizedType.class::isInstance)
                .map(ParameterizedType.class::cast)
                .filter(t -> t.getRawType().equals(SkipListener.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "PblpkltSkipListener 가 SkipListener 를 제네릭으로 구현하지 않는다 — "
                                + "raw type 으로 두면 listener(Object) 오버로드가 잡혀 콜백이 등록되지 않는다."));

        Type[] args = skipListenerItf.getActualTypeArguments();
        assertThat(args)
                .as("스킵 콜백이 받는 아이템은 Reader 가 읽은 원본 타입이다 — 중간 타입(PblpkltFetchRow)이 아니다")
                .containsExactly(PblpkltOriginRow.class, PblpkltRecord.class);
    }

    @Test
    @DisplayName("typed 오버로드가 잡히는지 — 컴파일 타임 대입으로 고정한다")
    void assignableToTypedSkipListener() {
        PblpkltSkipListener listener = new PblpkltSkipListener(null);

        // 🔴 이 대입이 성립해야 FaultTolerantStepBuilder 의
        //    listener(SkipListener<? super PblpkltOriginRow, ? super PblpkltRecord>) 가 선택된다.
        //    제네릭이 어긋나면 이 줄이 **컴파일 에러**가 되어 즉시 드러난다.
        SkipListener<PblpkltOriginRow, PblpkltRecord> typed = listener;

        assertThat(typed).isSameAs(listener);
    }

    @Test
    @DisplayName("onSkipInProcess 의 파라미터 타입도 PblpkltOriginRow 다 — 브리지 메서드가 아닌 실 시그니처")
    void onSkipInProcessTakesOriginRow() throws NoSuchMethodException {
        assertThat(PblpkltSkipListener.class
                .getDeclaredMethod("onSkipInProcess", PblpkltOriginRow.class, Throwable.class))
                .isNotNull();
    }
}
