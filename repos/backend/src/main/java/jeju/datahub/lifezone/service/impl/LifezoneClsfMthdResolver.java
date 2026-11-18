package jeju.datahub.lifezone.service.impl;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.lifezone.service.LifezoneClsfMthd;
import jeju.datahub.lifezone.service.LifezoneTarget;

/**
 * 생활권 분석의 <b>등급 분류방법 해석</b> — 민원건수·혼잡도 두 분석이 공유한다.
 *
 * <p>{@code LifezoneAnalysisServiceImpl} 안에 있던 {@code resolveClsfMthd(String)} 를 꺼냈다
 * ({@link LifezoneRegionResolver} 선례). 규칙이 유형에 매이게 되면서 두 벌로 두면 반드시 갈린다.
 *
 * <ol>
 *   <li>🔴 <b>생략·빈 값은 그 유형의 기본값</b>({@link LifezoneTarget#getDfltClsfMthd()})이다 —
 *       {@link LifezoneClsfMthd#ofCode} 는 null 에 {@code JENKS} 를 돌려주므로 <b>그 전에</b> 판정한다.
 *       유형 기본값은 민원건수 {@code EQUAL_INTERVAL} · 혼잡도 {@code CNGST_ABSOLUTE} 다
 *       (종전 민원건수 기본은 {@code JENKS}).</li>
 *   <li>🔴 <b>유형의 허용 목록({@link LifezoneTarget#getClsfMthds()}) 밖이면 400</b> 이다 — 안 그러면
 *       {@code QUANTILE} 이 민원건수 경로에서 받아들여진다. 400 메시지의 "사용 가능" 도 전 상수가 아니라
 *       <b>그 유형 목록</b>이다.</li>
 *   <li>미지원 문자열도 400 이다.</li>
 * </ol>
 *
 * <p>🔴 예외는 {@link ResponseStatusException} 이다 — {@code valueOf} 의 {@link IllegalArgumentException}
 * 은 전역 핸들러가 없어 <b>500</b> 으로 나간다.
 */
public final class LifezoneClsfMthdResolver {

    private LifezoneClsfMthdResolver() {
    }

    /**
     * 분류방법 코드 → {@link LifezoneClsfMthd}.
     *
     * @param target 이미 해석된 분석유형 (null 이면 안 된다)
     * @param code   요청 {@code clsfMthd}. {@code null}·공백이면 유형 기본값
     * @throws ResponseStatusException 400 — 미지원 값 · 유형 허용 목록 밖 · 등급 분류가 없는 유형
     */
    public static LifezoneClsfMthd resolve(LifezoneTarget target, String code) {
        List<LifezoneClsfMthd> allowed = target.getClsfMthds();
        if (code == null || code.isBlank()) {
            LifezoneClsfMthd dflt = target.getDfltClsfMthd();
            if (dflt == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        target.getAnlsTrgtNm() + " 분석은 등급 분류방법이 없습니다");
            }
            return dflt;
        }
        LifezoneClsfMthd mthd = LifezoneClsfMthd.ofCode(code);
        if (mthd == null || !allowed.contains(mthd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    target.getAnlsTrgtNm() + " 분석이 지원하지 않는 등급 분류방법입니다: " + code
                            + " (사용 가능: " + codes(allowed) + ")");
        }
        return mthd;
    }

    private static String codes(List<LifezoneClsfMthd> allowed) {
        return String.join(", ", allowed.stream().map(Enum::name).toList());
    }
}
