package jeju.datahub.batch.sftychck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.SkipListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jeju.datahub.batch.sftychck.api.PkltSftyChckOriginRow;

/**
 * 🔴 {@link PkltSftyChckSkipListener} 의 제네릭 실인자가 Step 청크 타입 {@code <PkltSftyChckOriginRow, PkltSftyChckRow>}
 * 와 같은지 고정한다 (선례 {@code PblpkltSkipListenerTypeTest}).
 *
 * <p>어긋나도 컴파일은 통과한다 — {@code FaultTolerantStepBuilder.listener(Object)} 오버로드가 잡혀 콜백이 0회
 * 등록되고 스킵 기록이 통째로 사라진다(주차장 1회차 E2E 102건 유실). 그래서 타입 자체를 테스트로 못박는다.
 */
class PkltSftyChckSkipListenerTypeTest {

    @Test
    @DisplayName("제네릭 실인자가 <PkltSftyChckOriginRow, PkltSftyChckRow> 다 — Step 청크 타입과 일치")
    void implementsSkipListenerWithChunkTypes() {
        ParameterizedType itf = Arrays.stream(PkltSftyChckSkipListener.class.getGenericInterfaces())
                .filter(ParameterizedType.class::isInstance)
                .map(ParameterizedType.class::cast)
                .filter(t -> t.getRawType().equals(SkipListener.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PkltSftyChckSkipListener 가 SkipListener 를 제네릭으로 구현하지 않는다"));
        Type[] args = itf.getActualTypeArguments();
        assertThat(args).containsExactly(PkltSftyChckOriginRow.class, PkltSftyChckRow.class);
    }

    @Test
    @DisplayName("typed 오버로드가 잡히는지 — 컴파일 타임 대입으로 고정한다")
    void assignableToTypedSkipListener() {
        PkltSftyChckSkipListener listener = new PkltSftyChckSkipListener(null);
        // 🔴 제네릭이 어긋나면 이 줄이 컴파일 에러가 된다.
        SkipListener<PkltSftyChckOriginRow, PkltSftyChckRow> typed = listener;
        assertThat(typed).isSameAs(listener);
    }

    @Test
    @DisplayName("onSkipInProcess 의 실 시그니처가 (PkltSftyChckOriginRow, Throwable) 이고 REQUIRES_NEW 다")
    void onSkipInProcessSignatureAndPropagation() throws NoSuchMethodException {
        Transactional tx = PkltSftyChckSkipListener.class
                .getDeclaredMethod("onSkipInProcess", PkltSftyChckOriginRow.class, Throwable.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).as("인터페이스 메서드에 직접 붙어야 프록시를 탄다").isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("기록 — job_se='SFTYCHCK' · trgt_id · trgt_de(DATE) · 사유 · note 로 upsert")
    void recordsSkip() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PkltSftyChckSkipListener listener = new PkltSftyChckSkipListener(jdbc);
        PkltSftyChckOriginRow row = PkltSftyChckResolverFixture.src(42, null, "가짜 주차장", "가짜 주소 1");
        LocalDate de = LocalDate.of(2026, 3, 1);

        listener.onSkipInProcess(row, new PkltSftyChckSkippableException(
                PkltSftyChckSkippableException.PKLT_NOT_FOUND, row, de, "후보 0개"));

        verify(jdbc).update(PkltSftyChckSkipListener.UPSERT_SQL, "SFTYCHCK", "42", java.sql.Date.valueOf(de),
                "PKLT_NOT_FOUND", "후보 0개");
        assertThat(PkltSftyChckSkipListener.UPSERT_SQL)
                .contains("ON CONFLICT (job_se, trgt_id, trgt_de) DO UPDATE SET")
                .contains("checked_dt = now()");
    }

    @Test
    @DisplayName("스킵 예외가 아니면 기록하지 않는다(ERROR 로그로만 드러낸다)")
    void ignoresForeignThrowable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new PkltSftyChckSkipListener(jdbc).onSkipInProcess(
                PkltSftyChckResolverFixture.src(1, null, "가짜", "가짜"), new IllegalStateException("x"));
        verifyNoInteractions(jdbc);
    }
}
