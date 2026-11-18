package jeju.datahub.batch.history;

import java.util.List;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;
import org.apache.logging.log4j.core.pattern.PatternFormatter;
import org.apache.logging.log4j.core.pattern.PatternParser;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * Log4j2 패턴 변환자 {@code %mask{<내부 패턴>}} — 내부 패턴을 찍은 결과에 {@link SecretMasker#mask} 를 건다 (TSK-4337 Step 5).
 *
 * <p>실행별 로그 파일({@code log4j2-spring.xml} 의 {@code jobLogRouting})이 메시지·예외를 이것으로 감싼다 —
 * 로그 조회 API 도 응답 직전에 같은 마스킹을 하지만, 파일 자체에도 비밀이 남지 않게 하는 이중 장치다.
 * 내장 {@code %replace{}} 로도 되지만 그러면 정규식이 XML 에 한 벌 더 생긴다(공용 규칙 한 곳 원칙).
 *
 * <p>🔴 플러그인 등록은 {@code build.gradle} 의 {@code annotationProcessor 'org.apache.logging.log4j:log4j-core'} 가
 * 만드는 {@code Log4j2Plugins.dat} 로 한다 — 그 줄을 지우면 {@code %mask} 를 모르는 패턴이 되어 파일 로그가 깨진다.
 */
@Plugin(name = "SecretMaskConverter", category = PatternConverter.CATEGORY)
@ConverterKeys({ "mask" })
public final class SecretMaskConverter extends LogEventPatternConverter {

    private final List<PatternFormatter> formatters;

    private SecretMaskConverter(List<PatternFormatter> formatters) {
        super("mask", "mask");
        this.formatters = formatters;
    }

    /** Log4j2 가 부르는 팩토리 — {@code options[0]} 이 내부 패턴. */
    public static SecretMaskConverter newInstance(Configuration config, String[] options) {
        if (options == null || options.length != 1 || options[0] == null) {
            StatusLogger.getLogger().error("%mask{pattern} 에는 내부 패턴 1개가 필요하다");
            return null;
        }
        PatternParser parser = PatternLayout.createPatternParser(config);
        return new SecretMaskConverter(parser.parse(options[0]));
    }

    @Override
    public void format(LogEvent event, StringBuilder toAppendTo) {
        StringBuilder buf = new StringBuilder();
        for (PatternFormatter formatter : formatters) {
            formatter.format(event, buf);
        }
        toAppendTo.append(SecretMasker.mask(buf.toString()));
    }

    /** 내부 패턴이 예외를 찍으면 바깥 PatternLayout 이 예외를 한 번 더 덧붙이지 않게 알린다. */
    @Override
    public boolean handlesThrowable() {
        return formatters.stream().anyMatch(PatternFormatter::handlesThrowable);
    }
}
