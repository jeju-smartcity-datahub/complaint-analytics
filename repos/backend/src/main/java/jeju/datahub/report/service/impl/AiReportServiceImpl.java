package jeju.datahub.report.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.agent.service.AiReportHtmlSanitizer;
import jeju.datahub.report.service.AiReportCrtVO;
import jeju.datahub.report.service.AiReportService;
import jeju.datahub.report.service.AiReportSnapshotVO;
import jeju.datahub.report.service.ReportCreatorName;
import lombok.RequiredArgsConstructor;

/**
 * AI 보고서 등록·스냅샷 구현 (TSK-4293 Step 5).
 *
 * <p>쓰기는 JPA({@link Rptp} + {@link AiRptpCn})다 — {@code rule_persistence-jpa-mybatis.md}.
 * 의존 방향은 {@code report} → {@code agent.service}(새니타이저) 한 방향이다.
 */
@Service
@RequiredArgsConstructor
public class AiReportServiceImpl extends EgovAbstractServiceImpl implements AiReportService {

    /** 생성구분 ({@code RPTP_CRT_TYPE}) — 보고서 목록·대시보드 `최근 리포트` 의 AI 칸 */
    private static final String CRT_TYPE_AI = "AI";

    /** 상태코드 ({@code RPTP_STTS_CD}) — 동기 등록이라 항상 DONE */
    private static final String RPTP_STTS_DONE = "DONE";

    /** {@code rptp_nm VARCHAR(256)} (D8) */
    private static final int RPTP_NM_MAX_LEN = 256;

    /** 제목이 비었을 때의 이름 (D8 마지막 폴백 — 추출기와 같은 모양) */
    private static final String FALLBACK_NM_PREFIX = "AI 보고서_";
    private static final DateTimeFormatter FALLBACK_NM_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private final RptpRepository rptpRepository;
    private final AiRptpCnRepository aiRptpCnRepository;

    @Override
    @Transactional(readOnly = true)
    public AiReportCrtVO findRegistered(String userId, String sesnId, String msgId, int rptSeq) {
        return aiRptpCnRepository.findByUserIdAndSesnIdAndMsgIdAndRptpSeq(userId, sesnId, msgId, rptSeq)
                .flatMap(cn -> rptpRepository.findById(cn.getRptpId()))
                .map(rptp -> new AiReportCrtVO(rptp.getRptpId(), rptp.getRptpNm(), false))
                .orElse(null);
    }

    @Override
    @Transactional
    public AiReportCrtVO register(String userId, String userNm, String sesnId, String msgId, int rptSeq,
                                  String title, String html) {
        LocalDateTime now = LocalDateTime.now();

        Rptp rptp = new Rptp();
        rptp.setCrtType(CRT_TYPE_AI);
        rptp.setRptpNm(rptpNm(title, now));
        rptp.setSttsCd(RPTP_STTS_DONE);
        rptp.setFileFmt(null);                             // 형식은 다운로드 때 고른다
        rptp.setCreatrId(userId);
        rptp.setCreatrNm(ReportCreatorName.cut(userNm));   // 🔴 50자 절단 — 공용 유틸(TSK-4202)
        rptp.setCrtYmd(now);                               // 🔴 NULL 이면 crt_ymd DESC 정렬에서 맨 앞에 붙는다
        rptpRepository.save(rptp);

        AiRptpCn cn = new AiRptpCn();
        cn.setRptpId(rptp.getRptpId());
        cn.setUserId(userId);
        cn.setSesnId(sesnId);
        cn.setMsgId(msgId);
        cn.setRptpSeq(rptSeq);
        // 🔴 D3 — 추출 경로에서 이미 새니타이즈됐어도 저장 직전에 명시적으로 한 번 더 부른다
        cn.setRptpCn(AiReportHtmlSanitizer.sanitize(html));
        cn.setRegDt(now);
        // 🔴 saveAndFlush — UNIQUE 위반을 여기서 DataIntegrityViolationException 으로 드러낸다
        //    (flush 를 커밋까지 미루면 다른 예외 타입으로 나온다)
        aiRptpCnRepository.saveAndFlush(cn);

        return new AiReportCrtVO(rptp.getRptpId(), rptp.getRptpNm(), true);
    }

    @Override
    @Transactional(readOnly = true)
    public AiReportSnapshotVO selectReportSnapshot(Long rptpId) {
        AiRptpCn cn = aiRptpCnRepository.findById(rptpId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Rptp rptp = rptpRepository.findById(rptpId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // 🔴 D3 — 읽을 때 한 번 더 새니타이즈한다(DB 직접 조작·과거 규칙으로 저장된 행 방어)
        return new AiReportSnapshotVO(rptp.getRptpId(), rptp.getRptpNm(),
                AiReportHtmlSanitizer.sanitize(cn.getRptpCn()));
    }

    /** D8 — 블록 title(추출기가 이미 정함) · 비었으면 날짜 폴백 · 256자 절단(서로게이트 반쪽 방지) */
    private static String rptpNm(String title, LocalDateTime now) {
        String nm = StringUtils.hasText(title) ? title.strip() : FALLBACK_NM_PREFIX + now.format(FALLBACK_NM_FMT);
        if (nm.length() <= RPTP_NM_MAX_LEN) {
            return nm;
        }
        int cut = Character.isHighSurrogate(nm.charAt(RPTP_NM_MAX_LEN - 1)) ? RPTP_NM_MAX_LEN - 1 : RPTP_NM_MAX_LEN;
        return nm.substring(0, cut);
    }
}
