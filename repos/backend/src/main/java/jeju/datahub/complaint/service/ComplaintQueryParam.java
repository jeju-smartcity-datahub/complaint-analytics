package jeju.datahub.complaint.service;

import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 매퍼에 전달되는 내부 조회 파라미터. MyBatis 가 {@code #{curStart}} / {@code #{curEnd}} /
 * {@code #{prevStart}} / {@code #{sggCd}} / {@code #{emdCd}} / {@code #{cvlcptTypeCd}} 로 바인딩할 수
 * 있도록 필드명을 SQL 토큰과 맞춘다.
 *
 * <p><b>왜 POJO + setter 인가</b> — 2026-08-31 Q 라운드 실측: Java record 의 컴포넌트명이 컴파일 시
 * 손실되어 MyBatis 가 record 매개변수의 {@code curStart} 와 {@code prevStart} 를 구분하지 못하고
 * {@code #{}} 토큰 매핑이 깨짐. 그 결과 {@code #{curStart}} 가 {@code prevStart} 값으로,
 * {@code #{prevStart}} 가 {@code curStart} 값으로 들어가 두 FILTER 가 같은 구간을 집계해 값이 합쳐짐
 * (실측 803+885=1688). POJO + setter 로 컴파일 후에도 프로퍼티명이 보존되어 매핑 확실.
 *
 * <p>컨트롤러/서비스 단계에서 {@link ComplaintRequest} 의 {@code unit/startDt/endDt/sggCd/emdCd/cvlcptTypeCd}
 * 를 받아 {@link #curStart} / {@link #curEnd} 로 변환해 만든다. 직접 매퍼에 넘기지 않는다.
 *
 * * {@link #prevStart} 는 §7 의 SELECT 에서만 사용 — {@code curStart} 와 동일 길이의 직전 구간 시작일.
 *   §3 / §8 SELECT 는 사용하지 않지만 nullable 로 두고 XML 의 {@code <if test="prevStart != null">}
 *   로 분기해 매퍼가 한 종류의 시그니처만 갖도록 한다 (가이드 §0 의 "매퍼는 한 종류" 의도).
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ComplaintQueryParam {

    private LocalDate curStart;
    private LocalDate curEnd;
    private LocalDate prevStart;
    private String sggCd;
    private String emdCd;
    private String cvlcptTypeCd;

    public ComplaintQueryParam(LocalDate curStart, LocalDate curEnd, LocalDate prevStart,
                               String sggCd, String emdCd, String cvlcptTypeCd) {
        this.curStart = curStart;
        this.curEnd = curEnd;
        this.prevStart = prevStart;
        this.sggCd = sggCd;
        this.emdCd = emdCd;
        this.cvlcptTypeCd = cvlcptTypeCd;
    }
}