package jeju.datahub.batch.sftychck;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.sftychck.PkltSftyChckCodeMapper.Result;
import jeju.datahub.batch.sftychck.PkltSftyChckPkltIdResolver.Match;
import jeju.datahub.batch.sftychck.api.PkltSftyChckOriginRow;

/** 원천 날짜·유형 결측을 유지하고 주소로 주차장을 해석한다. 같은 날짜의 다른 원천 행도 관측으로 전달한다. */
@Component
public class PkltSftyChckProcessor implements ItemProcessor<PkltSftyChckOriginRow, PkltSftyChckRow> {

    private static final Logger log = LoggerFactory.getLogger(PkltSftyChckProcessor.class);

    /** {@code rmrk_cn VARCHAR(4000)} — PostgreSQL 은 문자 수로 센다. */
    static final int RMRK_CN_MAX = 4000;

    /** 🔴 {@code uuuu} + STRICT — {@code yyyy} 는 STRICT 에서 연대(era) 없이 해석되지 않는다. 20260230 같은 비실존 날짜를 거른다. */
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final PkltSftyChckPkltIdResolver resolver;
    private final Clock clock;

    @Autowired
    public PkltSftyChckProcessor(PkltSftyChckPkltIdResolver resolver) {
        this(resolver, Clock.system(DhubProperties.ZONE));
    }

    /** 테스트용 — 실행일(점검일이 없을 때의 {@code trgt_de})을 고정한다. */
    PkltSftyChckProcessor(PkltSftyChckPkltIdResolver resolver, Clock clock) {
        this.resolver = resolver;
        this.clock = clock;
    }

    @Override
    public PkltSftyChckRow process(PkltSftyChckOriginRow row) {
        String ymd = row.chckYmd() == null ? null : row.chckYmd().trim();
        LocalDate chckDate = parseYmd(ymd);
        // 스킵 표 trgt_de — 점검일이 해석되면 그 날, 아니면 실행일(Asia/Seoul).
        LocalDate trgtDe = chckDate != null ? chckDate : LocalDate.now(clock);

        // ① 주차장
        Match match = resolver.resolve(row);
        if (!match.resolved()) {
            throw skip(match.failRsn(), row, trgtDe,
                    "후보 " + match.candCnt() + "개 · 원천 pklt_id " + (row.pkltId() == null ? "없음" : "있음")
                            + " · 원천 '" + row.pkltNm() + "' / '" + row.pkltAddr() + "'");
        }
        long pkltId = match.pkltId();

        // 원천에 날짜/유형이 없으면 관측 이력에 NULL 로 보존한다. 수집일을 점검일로 대체하지 않는다.
        if (ymd != null && !ymd.isBlank() && chckDate == null) {
            throw skip(PkltSftyChckSkippableException.YMD_INVALID, row, trgtDe, "잘못된 원천 점검일");
        }
        String typeCode = null;
        if (row.sftyChckTypeCd() != null && !row.sftyChckTypeCd().isBlank()) {
            Result type = PkltSftyChckCodeMapper.mapType(row.sftyChckTypeCd());
            if (!type.mapped()) throw skip(type.failRsn(), row, trgtDe, "미지원 원천 점검유형");
            typeCode = type.code();
        }
        Result result = PkltSftyChckCodeMapper.mapRslt(row.sftyChckRsltCd());
        if (!result.mapped()) throw skip(result.failRsn(), row, trgtDe, "미지원 원천 점검결과");
        String periodCode = null;
        if (row.chckPrdCd() != null && !row.chckPrdCd().isBlank()) {
            Result period = PkltSftyChckCodeMapper.mapPrd(row.chckPrdCd(), chckDate);
            if (!period.mapped()) throw skip(period.failRsn(), row, trgtDe, "미지원 원천 점검기간");
            periodCode = period.code();
        }
        // 동일 날짜의 서로 다른 원천 행도 각각의 관측이다. 실행 내 재시도는 Writer 의 원문 해시로 막는다.
        return new PkltSftyChckRow(pkltId, chckDate == null ? null : ymd,
                periodCode, typeCode, result.code(), truncateRmrk(row.rmrkCn()), row.srcJson());
    }

    /** 8자리 숫자 + 실존 날짜면 그 날, 아니면 {@code null}. */
    static LocalDate parseYmd(String ymd) {
        if (ymd == null || !ymd.matches("\\d{8}")) {
            return null;
        }
        try {
            return LocalDate.parse(ymd, YMD);
        } catch (DateTimeParseException e) {
            // 4.2 — 삼키는 사유를 남긴다(TSK-4336) — 비실존 날짜(20260230 등)는 null → 호출부가 사유와 함께 스킵한다.
            log.debug("점검일자 파싱 실패 — 비실존 날짜 ymd={}", ymd);
            return null;
        }
    }

    /** {@code rmrk_cn} 4000자 절단 — 문자(code point) 단위로 잘라 서로게이트 쌍을 가르지 않는다. */
    static String truncateRmrk(String rmrk) {
        if (rmrk == null || rmrk.codePointCount(0, rmrk.length()) <= RMRK_CN_MAX) {
            return rmrk;
        }
        return rmrk.substring(0, rmrk.offsetByCodePoints(0, RMRK_CN_MAX));
    }

    private static PkltSftyChckSkippableException skip(String reason, PkltSftyChckOriginRow row,
                                                       LocalDate trgtDe, String note) {
        return new PkltSftyChckSkippableException(reason, row, trgtDe, note);
    }

    private static String quote(String v) {
        return v == null ? "없음" : "'" + cut(v, 30) + "'";
    }

    private static String cut(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }
}
