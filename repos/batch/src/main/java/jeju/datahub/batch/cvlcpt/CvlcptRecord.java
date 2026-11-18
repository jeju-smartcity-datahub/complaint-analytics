package jeju.datahub.batch.cvlcpt;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 적재 직전의 주차민원 한 행 — Processor({@link CvlcptGeocodeProcessor}) 산출 = Writer 입력 타입.
 *
 * <p>필드는 {@code parking.tb_cvlcpt} 의 INSERT 컬럼과 1:1 이며 순서도 맞춘다
 * ({@link CvlcptItemWriter#UPSERT_SQL}).
 *
 * <h2>🔁 2026-08-24 — 원본(staging) 폐지로 필드가 크게 늘었다</h2>
 * {@code parking.tb_cvlcpt_origin} 이 사라지고 수집 Job 이 {@code tb_cvlcpt} 에 바로 쓴다
 * (47-CVLCPT-DIRECT.sql). 원본에만 있던 17컬럼이 서비스 테이블로 옮겨 왔으므로 이 레코드도
 * 그 값들을 함께 실어 나른다. <b>가공값과 원본값이 한 행에 공존한다</b> —
 * {@code cvlcptTypeCd}·{@code emdCd}·{@code lat}·{@code lot}·{@code srcCd} 는 이 배치가 만든
 * 값이고, {@code dclrType}·{@code prkCvlcptType}·{@code srcPath} 등은 원천 원문이다.
 *
 * <p>원문을 함께 남기는 이유는 <b>재분류 가능성</b>이다. 분류 규칙을 고쳤을 때 원문이 없으면
 * 이미 적재된 행을 다시 분류할 방법이 없고, 원천 API 는 과거 구간을 계속 준다는 보장이 없다
 * (과거 이관에서 2026-01~05 52,951행이 실제로 사라졌고, 이후에는 원천 테이블이 통째로
 * 교체되면서 {@code dclr_ymd} 커버리지가 2025-12-15 에서 끊겼다).
 *
 * <p><b>{@code risk_lvl_cd} 는 필드로 두지 않는다.</b> 이 배치가 산출하는 값이 아니라 항상 NULL 이고,
 * Writer SQL 이 리터럴 {@code NULL} 로 쓴다 — 필드를 만들면 언제나 null 인 인자가 하나 늘 뿐이라
 * "언젠가 채워지는 값"으로 오독된다. 위험도 산정이 생기면 그때 추가한다.
 *
 * <h2>NULL 이 될 수 있는 필드와 그 뜻</h2>
 * <ul>
 *   <li>{@code emdCd}/{@code lat}/{@code lot} — <b>미분류(99) 행에서만</b> NULL 이 될 수 있다.
 *       그 행에는 지오코딩·공간매칭을 새로 돌리지 않기 때문이다(원천이 값을 줬다면 99 행에도
 *       채워진다 — 공짜로 온 값을 버릴 이유는 없다). 주차민원({@code 01~05})은 좌표·행정동을 못
 *       구하면 적재되지 않고 {@link GeocodeSkippableException} 으로 스킵된다 — 즉 "좌표를 못 구한
 *       주차민원"이 NULL 로 섞여 들어오는 경로는 없다.</li>
 *   <li>{@code srcCd} — 매핑에 없으면 {@code ETC} 라 NULL 이 되지 않는다.</li>
 *   <li>{@code aprsCd} — 원천이 감정값을 주지 않은 행. "분석 안 함"과 "중립"이 뭉개지지 않게 NULL 로 둔다.</li>
 *   <li>{@code prkCvlcptType} — 원천이 주차민원 유형명을 주지 않은 행(비주차 민원이 대부분).</li>
 * </ul>
 *
 * @param originPk       {@code origin_pk} (nullable 원천 ID. 내부 PK는 DB 시퀀스)
 * @param cvlcptTypeCd   {@code CVLCPT_TYPE_CD} — {@code 01~05} 또는 미분류 {@code 99}.
 *                       원천이 준 코드가 있으면 그 값, 없으면 배치가 분류한 값이다
 * @param emdCd          원천값 우선, 없으면 좌표 → 2단 공간매칭 결과. 둘 다 없으면 {@code null}
 * @param lat            위도. {@code NUMERIC(12,10)} 에 맞춰 소수 10자리
 * @param lot            경도. {@code NUMERIC(13,10)} 에 맞춰 소수 10자리
 * @param ocrnDt         발생일시. 원천 {@code ocrn_dt} 그대로 — <b>시·분·초를 담는다</b>.
 *                       조회 필터 키이기도 하다
 * @param srcCd          원천 {@code src_cd} 우선, 없으면 {@code src_path} → {@code SRC_CD_MAP}
 *                       (그것도 없으면 {@code ETC})
 * @param cvlcptCn       {@code cvlcpt_cn} (민원 내용)
 * @param loadDt         원천 {@code load_dt} 그대로. 결측은 null이며 월별 증분 워터마크에 사용
 * @param aprsCd         감정구분코드 — 원천 감정값 원문을 {@code APRS_CD} 로 변환한 값
 * @param dclrYmd        신고일자 (원문 {@code char(10)}). ⚠️ 조회 필터가 아니다 —
 *                       새 원천은 이 값이 2025 구간까지만 채워져 있다
 * @param ttl            제목 (원문)
 * @param dclrOcrnRgn    신고발생지역 — 지오코딩 입력으로 쓴 주소 원문
 * @param prgrsStts      진행상황 (원문)
 * @param ansPrcsStts    답변처리상황 (원문)
 * @param ansYmd         답변일자 (ISO 정규화 원문)
 * @param aplcntSe       신청자구분 (원문)
 * @param dclrFld        신고분야 (원문)
 * @param rwrdYn         포상여부 (원문)
 * @param prcsInstCnt    처리기관수
 * @param prcsInstNm     처리기관명
 * @param prcsInstPicNm  처리기관담당자명 🔒 개인정보
 * @param dclrGrpNm      신고단체명
 * @param mbrGrpNm       회원단체명
 * @param dclrType       신고유형 원문 — 분류 CASE 의 입력이자 재분류의 근거
 * @param prkCvlcptType  주차민원유형 원문 — 분류에서 {@code dclr_type} CASE 보다 우선한다
 * @param srcPath        유입경로 원문 — {@code srcCd} 변환 전 값
 */
public record CvlcptRecord(
        String originPk,
        String cvlcptTypeCd,
        String emdCd,
        BigDecimal lat,
        BigDecimal lot,
        LocalDateTime ocrnDt,
        String srcCd,
        String cvlcptCn,
        LocalDateTime loadDt,
        String aprsCd,
        String dclrYmd,
        String ttl,
        String dclrOcrnRgn,
        String prgrsStts,
        String ansPrcsStts,
        String ansYmd,
        String aplcntSe,
        String dclrFld,
        String rwrdYn,
        Integer prcsInstCnt,
        String prcsInstNm,
        String prcsInstPicNm,
        String dclrGrpNm,
        String mbrGrpNm,
        String dclrType,
        String prkCvlcptType,
        String srcPath) {
    /** 원천 PK/원천 load_dt/가공 좌표와 독립적인 원문 내용 키. DB 마이그레이션과 같은 순서다. */
    public String srcHash() {
        return jeju.datahub.batch.collect.SourceFingerprint.hash(
                dclrYmd, jeju.datahub.batch.collect.SourceTimestamp.format(ocrnDt), aplcntSe, dclrFld,
                srcPath, dclrOcrnRgn, ttl, cvlcptCn, prgrsStts, ansPrcsStts, ansYmd, rwrdYn,
                prcsInstCnt == null ? null : prcsInstCnt.toString(), prcsInstNm, prcsInstPicNm,
                dclrGrpNm, mbrGrpNm, dclrType, prkCvlcptType, aprsCd);
    }

}
