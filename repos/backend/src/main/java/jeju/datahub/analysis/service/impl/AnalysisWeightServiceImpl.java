package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.AnalysisTrgtSumVO;
import jeju.datahub.analysis.service.AnalysisTypeWeightVO;
import jeju.datahub.analysis.service.AnalysisWeightHstryVO;
import jeju.datahub.analysis.service.AnalysisWeightItemRequest;
import jeju.datahub.analysis.service.AnalysisWeightSaveRequest;
import jeju.datahub.analysis.service.AnalysisWeightService;
import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblScope.Codes;
import jeju.datahub.commoncode.service.CommonCodeService;
import jeju.datahub.commoncode.service.CommonCodeVO;
import lombok.RequiredArgsConstructor;

/**
 * 분석지표 가중치 설정 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>조회는 MyBatis({@link AnalysisWeightMapper}), 저장·이력 append 는 JPA
 * ({@link AnlsVrblStngRepository} · {@link AnlsVrblHstryRepository}) 로 나눈다.
 *
 * <p>🔴 <b>가중치 비교는 전부 {@link BigDecimal#compareTo(BigDecimal)} 로 한다 — {@code equals} 금지.</b>
 * 컬럼이 {@code NUMERIC(5,2)} 라 DB 에서 읽은 값은 {@code 60.00} 이고 요청 JSON 은 {@code 60} 이라,
 * {@code equals}(스케일까지 비교)로 짜면 <b>같은 값 재저장이 "변경 있음" 으로 판정</b>돼
 * 5) 조기 반환과 7) 의 "변경된 지표만" 이 동시에 깨진다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisWeightServiceImpl extends EgovAbstractServiceImpl implements AnalysisWeightService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisWeightServiceImpl.class);

    /** 가중치 합계 기준값(%). 분석유형별 합계가 정확히 이 값이어야 저장된다. */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 가중치비율 스케일 — 컬럼이 {@code NUMERIC(5,2)} 다. 재정규화 나눗셈의 스케일로도 쓴다. */
    private static final int WGVL_SCALE = 2;

    /**
     * 변수구분코드 — 가중치({@code vrbl_se_cd = 'WGVL'}).
     *
     * <p>🔴 합계 100 검증과 재정규화는 <b>이 구분의 행에만</b> 적용한다.
     * 계수({@code CFC})는 합계 개념 자체가 없어 섞이면 집중구역 저장이 400 이 되고,
     * 재정규화 분모에 섞이면 점수가 조용히 틀어진다.
     */
    private static final String VRBL_SE_WGVL = "WGVL";

    /** 사용여부 'Y' — 합계 검증·재정규화 대상 판정에 쓴다. */
    private static final String USE_Y = "Y";

    /** 노출여부 'N' — 숨김 행. 🔴 판정은 {@code EXPSR_N.equals(expsrYn)} 로만 한다(null = 노출). */
    private static final String EXPSR_N = "N";

    /**
     * 계수 폴백값. 행이 없고 {@code bsc_stng_vl} 도 없을 때 쓴다.
     *
     * <p>🔴 <b>1.0 인 이유는 "곱해도 값이 변하지 않는 수" 이기 때문이다</b> — 계수가 빠진 분석을
     * 0 으로 죽이거나 임의 값으로 왜곡하지 않고, 계수 도입 전과 같은 결과로 돌려 놓는다.
     */
    private static final BigDecimal DEFAULT_CFC = new BigDecimal("1.0");

    /**
     * 대메뉴({@code anls_type_cd}) → 하위 유형({@code anls_trgt_cd}) 값의 공통코드 그룹.
     *
     * <p>하위 유형 코드그룹은 대메뉴마다 다르다 — 그래서 매퍼가 코드그룹 하나로 JOIN 하지 않고,
     * 서비스가 이 상수로 그룹을 골라 {@code tb_com_cd} 를 읽어 이름({@code anlsTrgtNm})과 묶음 순서를 정한다.
     * <b>여기 없는 대메뉴는 이름이 null</b> 이고 묶음 순서는 코드값 순으로 떨어진다(FK 없이 앱이 대조하는 관례).
     *
     * <p>{@code POLICY_SIM → PLCY_TYPE_CD} 는 나중에 추가됐다(정책 시나리오는 기존 그룹을 그대로 쓴다).
     * POLICY_SIM 은 공유 7행 NULL(공통) · 확충 전용 6행 {@code EXPAND}(주차장확충)이다(= B).
     *
     * <p>{@code RECOMMEND → ZONE_RCMDTN_TYPE_CD} 는 나중에 추가됐다(구역추천 유형 그룹을 그대로 쓴다).
     * RECOMMEND 는 선별 기준 5행 NULL(공통) · 공유주차제 전용 1행 {@code SHARE}(마이그레이션 85)이다 —
     * 이 항목이 빠지면 설정 화면의 묶음 제목이 비거나 {@code SHARE} 가 코드 그대로 보인다.
     */
    private static final Map<String, String> TRGT_CD_GROUPS = Map.of(
            "LIFEZONE", "ANLS_TRGT_CD",
            "POLICY_SIM", "PLCY_TYPE_CD",
            "RECOMMEND", "ZONE_RCMDTN_TYPE_CD");

    private final AnalysisWeightMapper analysisWeightMapper;
    private final AnlsVrblStngRepository anlsVrblStngRepository;
    private final AnlsVrblHstryRepository anlsVrblHstryRepository;
    private final ObjectMapper objectMapper;
    private final CommonCodeService commonCodeService;

    // ------------------------------------------------------------------ 조회

    @Override
    public List<AnalysisTypeWeightVO> selectAnalysisTypeWeights() {
        // 분석유형 머리(5종) + 전체 지표를 각각 한 번씩 읽고 서비스가 묶는다 (N+1 회피).
        List<AnalysisTypeWeightVO> types = analysisWeightMapper.selectAnalysisTypes();
        Map<String, List<AnalysisWeightVO>> weightsByType = analysisWeightMapper.selectWeights().stream()
                .collect(Collectors.groupingBy(AnalysisWeightVO::getAnlsTypeCd,
                        LinkedHashMap::new, Collectors.toList()));

        for (AnalysisTypeWeightVO type : types) {
            assemble(type, weightsByType.get(type.getAnlsTypeCd()));
        }
        return types;
    }

    /**
     * 🔴 필터는 매퍼(SQL)가 진다 — 여기서 목록을 받아 걸러 내면 {@code LIMIT} 이 먼저 걸린 뒤라
     * 다른 분석유형의 이력이 통째로 사라진다.
     */
    @Override
    public List<AnalysisWeightHstryVO> selectHistories(int size, String anlsTypeCd) {
        return analysisWeightMapper.selectHistories(size, anlsTypeCd);
    }

    // ------------------------------------------------------------------ 저장

    @Override
    @Transactional
    public AnalysisTypeWeightVO saveWeights(String anlsTypeCd, AnalysisWeightSaveRequest request,
                                            String userId, String userNm) {
        // 1) 분석유형 존재 확인 — 판정 기준은 조회(GET)와 같은 selectAnalysisTypes() 결과다.
        //    ⚠️ TB_COM_CD(ANLS_TYPE_CD) 대조로 짜면 안 된다 — 공통코드는 7종인데 가중치가 있는 건
        //    5종이라(POLICY_SIM 계수 13행 등재로 4→5) CVLCPT·DASHBOARD 가 404 를 통과해
        //    3) 에서 400 으로 새어 나간다.
        //    여기서 찾은 분석유형명(anlsTypeNm)은 7) 이력의 chg_artcl_nm 과 8) 응답에 재사용한다.
        AnalysisTypeWeightVO type = analysisWeightMapper.selectAnalysisTypes().stream()
                .filter(t -> t.getAnlsTypeCd().equals(anlsTypeCd))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "분석유형을 찾을 수 없습니다: " + anlsTypeCd));

        // 2) 현재값 스냅샷 (sort_seq 순) — 이력의 bfr_vrbl_json 원천이자 지표 집합 검증의 기준
        List<AnalysisWeightVO> before = analysisWeightMapper.selectWeightsByType(anlsTypeCd);

        // 3) 요청 지표 집합 == 현재 지표 집합 검증 --------------------------------------
        //    중복을 먼저 잡는다 — 중복이 있으면 집합이 줄어 "누락" 으로 잘못 보고된다.
        Map<String, BigDecimal> requested = new LinkedHashMap<>();
        Set<String> duplicated = new LinkedHashSet<>();
        for (AnalysisWeightItemRequest item : request.getWeights()) {
            if (requested.put(item.getVrblCd(), item.getStngVl()) != null) {
                duplicated.add(item.getVrblCd());
            }
        }
        if (!duplicated.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "같은 지표가 두 번 이상 왔습니다: " + String.join(", ", duplicated));
        }

        Set<String> currentVrblCds = before.stream()
                .map(AnalysisWeightVO::getVrblCd)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> unknown = requested.keySet().stream()
                .filter(cd -> !currentVrblCds.contains(cd))
                .collect(Collectors.toList());
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "이 분석유형에 없는 지표입니다: " + String.join(", ", unknown));
        }

        List<String> missing = currentVrblCds.stream()
                .filter(cd -> !requested.containsKey(cd))
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "전체 교체 저장이므로 지표를 모두 보내야 합니다: " + String.join(", ", missing));
        }

        // 4-0) 🆕 숨김 행 보호 ((a)) — expsr_yn='N' 행은 요청값을 버리고 현재값(before)으로 바꿔 끼운다.
        //      400 으로 끊지 않는다 — 낡은 화면·API 직접 호출이 숨김 행에 다른 값을 실어도 저장은 통과하고
        //      그 행만 바뀌지 않는다. 이후 범위·합계·변경 판정·이력은 바꿔 끼운 값으로 한다.
        //      🔴 판정은 "N".equals — null 은 노출이다. 비교는 compareTo(클래스 머리 규칙).
        for (AnalysisWeightVO w : before) {
            if (!EXPSR_N.equals(w.getExpsrYn())) {
                continue;
            }
            BigDecimal value = requested.get(w.getVrblCd());
            if (value == null || value.compareTo(w.getStngVl()) != 0) {
                log.info("숨김 분석 계수의 저장 요청값을 버리고 현재값을 유지합니다: anlsTypeCd={}, vrblCd={}, 요청={}, 현재={}",
                        anlsTypeCd, w.getVrblCd(), value == null ? "-" : fmt(value), fmt(w.getStngVl()));
                requested.put(w.getVrblCd(), w.getStngVl());
            }
        }

        // 4) 합계 검증 — 🔴 WGVL(가중치) + use_yn='Y' 행에만 적용한다
        //    생활권처럼 가중합이 성립하지 않는 분석유형은 WGVL·Y 행이 0개라 검증 대상이 없다.
        //    계수(CFC)는 합계 개념 자체가 없으므로 여기서 제외한다 — 범위는 DB CHECK 이 잡는다.
        //    합계 100 은 (anls_type_cd, anls_trgt_cd) 묶음마다 본다(NULL 도 한 묶음).
        //    🔴 묶음 키는 요청이 아니라 DB 행(before)의 anls_trgt_cd 다 — 요청 DTO 에 그 칸이 없다.
        //    활성 WGVL 이 0건인 묶음은 건너뛴다(종전 "0건이면 건너뜀" 을 묶음마다 유지).
        //    HOTSPOT·INVEST·RECOMMEND 는 전부 NULL 묶음 하나라 종전과 같은 판정이다.
        Map<String, List<AnalysisWeightVO>> sumTargetsByTrgt = groupByTrgt(before.stream()
                .filter(w -> VRBL_SE_WGVL.equals(w.getVrblSeCd()))
                .filter(w -> USE_Y.equals(w.getUseYn()))
                .collect(Collectors.toList()));

        for (Map.Entry<String, List<AnalysisWeightVO>> group : sumTargetsByTrgt.entrySet()) {
            BigDecimal sum = group.getValue().stream()
                    .map(w -> requested.get(w.getVrblCd()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(HUNDRED) != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        type.getAnlsTypeNm() + " · " + trgtLabel(anlsTypeCd, group.getKey())
                                + " 가중치 합계가 100 이 아닙니다 (현재 " + fmt(sum) + ")");
            }
        }

        // 4-1) 행별 범위 검증 — min_vl ~ max_vl 밖이면 400 + 필드명.
        //      DB CHECK(ck_tb_anls_vrbl_stng_vl)도 같은 것을 막지만, 그쪽이 먼저 잡으면
        //      DataIntegrityViolationException 이 500 으로 나간다(이 경로에 전역 핸들러가 없다).
        //      🔴 범위 상수를 코드에 두지 않는다 — 근거는 행의 min_vl/max_vl 뿐이다.
        for (AnalysisWeightVO w : before) {
            BigDecimal value = requested.get(w.getVrblCd());
            BigDecimal min = w.getMinVl();
            BigDecimal max = w.getMaxVl();
            boolean underflow = min != null && value.compareTo(min) < 0;
            boolean overflow = max != null && value.compareTo(max) > 0;
            if (underflow || overflow) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "허용 범위를 벗어났습니다: " + w.getVrblCd() + " = " + fmt(value)
                                + " (minVl " + (min == null ? "-" : fmt(min))
                                + " ~ maxVl " + (max == null ? "-" : fmt(max)) + ")");
            }
        }

        // 4-2) 혼잡 등급 경계 단조 검증 — BND1 < BND2 < BND3 < BND4 가 아니면 400.
        //      요청에 CNGST_GRD_BNDRY1~4 가 있을 때(= LIFEZONE)만 적용한다. 어긋나면 CNGST_ABSOLUTE
        //      분류의 등급이 뒤집힌다. 🔴 DB CHECK 은 행 단위라 행 사이의 순서를 잡지 못한다.
        if (Codes.CNGST_GRD_BNDRYS.stream().allMatch(requested::containsKey)) {
            for (int i = 1; i < Codes.CNGST_GRD_BNDRYS.size(); i++) {
                String lowerCd = Codes.CNGST_GRD_BNDRYS.get(i - 1);
                String upperCd = Codes.CNGST_GRD_BNDRYS.get(i);
                BigDecimal lower = requested.get(lowerCd);
                BigDecimal upper = requested.get(upperCd);
                if (upper.compareTo(lower) <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "혼잡 등급 경계는 BND1 < BND2 < BND3 < BND4 여야 합니다: "
                                    + lowerCd + " = " + fmt(lower) + ", " + upperCd + " = " + fmt(upper));
                }
            }
        }

        // 4-3) 🆕 수급 설정 정수 검증 — SPLY_* 5행은 소수부가 0 이 아니면 400.
        //      🔴 scale 이 아니라 값으로 본다 — 20.0000(DB numeric scale 4)·20.0 은 정수다.
        //      범위는 4-1 이 행의 min_vl/max_vl 로 이미 봤다.
        for (String vrblCd : Codes.SPLY_INT_VRBLS) {
            BigDecimal value = requested.get(vrblCd);
            if (value != null && value.stripTrailingZeros().scale() > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "정수만 입력할 수 있습니다: " + vrblCd + " = " + fmt(value));
            }
        }

        // 4-4) 🆕 수급 등급 경계 단조 검증 — BNDRY1 < BNDRY2 < BNDRY3 < BNDRY4 가 아니면 400.
        //      4-2(혼잡도)와 같은 형식이다. 요청에 SPLY_GRD_BNDRY1~4 가 있을 때(= LIFEZONE)만 적용한다.
        if (Codes.SPLY_GRD_BNDRYS.stream().allMatch(requested::containsKey)) {
            for (int i = 1; i < Codes.SPLY_GRD_BNDRYS.size(); i++) {
                String lowerCd = Codes.SPLY_GRD_BNDRYS.get(i - 1);
                String upperCd = Codes.SPLY_GRD_BNDRYS.get(i);
                BigDecimal lower = requested.get(lowerCd);
                BigDecimal upper = requested.get(upperCd);
                if (upper.compareTo(lower) <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "수급 등급 경계는 BNDRY1 < BNDRY2 < BNDRY3 < BNDRY4 여야 합니다: "
                                    + lowerCd + " = " + fmt(lower) + ", " + upperCd + " = " + fmt(upper));
                }
            }
        }

        // 4-5) 🆕 집중구역 등급 비율 검증 — 정수 · 심각 < 경고 < 주의. 요청에 3행이 있을 때(= HOTSPOT)만 적용한다.
        //      🔴 DB CHECK 은 행 단위라 행 사이의 순서를 잡지 못한다. 범위는 4-1 이 봤다.
        if (Codes.HOTSPOT_GRD_BNDRYS.stream().allMatch(requested::containsKey)) {
            for (String vrblCd : Codes.HOTSPOT_GRD_BNDRYS) {
                BigDecimal value = requested.get(vrblCd);
                if (value.stripTrailingZeros().scale() > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "정수만 입력할 수 있습니다: " + vrblCd + " = " + fmt(value));
                }
            }
            for (int i = 1; i < Codes.HOTSPOT_GRD_BNDRYS.size(); i++) {
                String lowerCd = Codes.HOTSPOT_GRD_BNDRYS.get(i - 1);
                String upperCd = Codes.HOTSPOT_GRD_BNDRYS.get(i);
                BigDecimal lower = requested.get(lowerCd);
                BigDecimal upper = requested.get(upperCd);
                if (upper.compareTo(lower) <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "등급 비율은 심각 < 경고 < 주의 여야 합니다: "
                                    + lowerCd + " = " + fmt(lower) + ", " + upperCd + " = " + fmt(upper));
                }
            }
        }

        // 5) 변경 없음 → UPDATE·이력 없이 현재값을 그대로 돌려준다.
        //    ⚠️ equals 로 짜면 60.00 vs 60 이 "다름" 이 되어 이 분기가 영원히 타지 않고,
        //    저장 버튼을 두 번 누를 때마다 같은 내용의 이력이 쌓여 이력 표가 무의미해진다.
        List<AnalysisWeightVO> changed = before.stream()
                .filter(w -> w.getStngVl().compareTo(requested.get(w.getVrblCd())) != 0)
                .collect(Collectors.toList());
        if (changed.isEmpty()) {
            return assemble(type, before);
        }

        // 6) 값 반영 — findById 로 읽어 stngVl·mdfrId·mdfcnDt 만 set (dirty checking).
        //    bsc_stng_vl 는 updatable=false 라 매핑 수준에서 UPDATE 대상이 아니다.
        LocalDateTime now = LocalDateTime.now();
        for (AnalysisWeightVO w : before) {
            AnlsVrblStng entity = anlsVrblStngRepository
                    .findById(new AnlsVrblStngId(anlsTypeCd, w.getVrblCd()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "지표를 찾을 수 없습니다: " + anlsTypeCd + " / " + w.getVrblCd()));
            entity.setStngVl(requested.get(w.getVrblCd()));
            entity.setMdfrId(userId);
            entity.setMdfcnDt(now);
        }

        // 7) 이력 1행 append — 값이 실제로 바뀐 저장만 기록한다 (5 에서 걸러졌다)
        List<AnalysisWeightVO> after = before.stream()
                .map(w -> withStngVl(w, requested.get(w.getVrblCd()), now))
                .collect(Collectors.toList());
        String chgCn = changed.stream()
                .map(w -> w.getVrblNm() + " " + fmt(w.getStngVl()) + " → " + fmt(requested.get(w.getVrblCd())))
                .collect(Collectors.joining(", "));
        anlsVrblHstryRepository.save(new AnlsVrblHstry(
                anlsTypeCd,                 // 🔴 NOT NULL · DEFAULT 없음 — 빼면 첫 저장에서 500
                type.getAnlsTypeNm(),
                chgCn,
                request.getChgRsn(),
                toSnapshotJson(before),
                toSnapshotJson(after),
                userId,
                userNm,
                now));

        // 8) 갱신 후 분석유형 1건 반환.
        //    ⚠️ 같은 트랜잭션에서 JPA 쓰기 직후 MyBatis 로 재조회하면 flush 전 값을 읽을 수 있으므로
        //    재조회하지 않고 방금 반영한 값으로 조립한다.
        return assemble(type, after);
    }

    // -------------------------------------------------- 재정규화 (점수 산출 전용, API 미노출)

    /**
     * {@inheritDoc}
     *
     * <p>계산 규칙
     * <ol>
     *   <li>분석유형의 지표 전량 조회</li>
     *   <li>{@code vrbl_se_cd='WGVL'} 이고 {@code use_yn='Y'} 인 것만 남긴다
     *       (계수(CFC)는 가중치가 아니라 재정규화 분모에 섞이면 안 된다)</li>
     *   <li>남은 게 없으면 빈 목록 (호출 측이 "가중치 없음 = 0건 응답" 으로 처리)</li>
     *   <li>🆕 하위 유형 묶음({@code anls_trgt_cd}, NULL 포함)마다 아래 4·5 를 따로 한다 — 합계 0 인 묶음은 빠진다</li>
     *   <li>{@code stngVl × 100 / 남은합계} (scale 2, HALF_UP)</li>
     *   <li>반올림 잔차는 마지막({@code sort_seq} 최대) 지표에 몰아 합계를 정확히 100 으로 맞춘다 —
     *       없으면 3분할(33.33×3 = 99.99)에서 점수 산식이 미세하게 어긋난다</li>
     * </ol>
     *
     * <p>⚠️ <b>저장 경로({@link #saveWeights})는 이 메서드를 절대 거치지 않는다.</b> 재정규화 값을
     * 저장하면 지표를 다시 켰을 때 원래 비율을 복원할 수 없다. 화면(GET)도 원값을 내린다.
     */
    @Override
    public List<AnalysisWeightVO> selectEffectiveWeights(String anlsTypeCd) {
        // 1) 전량 조회 → 2) 가중치(WGVL) 이면서 사용중인 지표만
        //    🔴 vrbl_se_cd = 'WGVL' 필터가 없으면 계수(CFC) 행이 재정규화 분모에 섞여
        //       집중구역 점수를 조용히 틀어 놓는다.
        List<AnalysisWeightVO> used = analysisWeightMapper.selectWeightsByType(anlsTypeCd).stream()
                .filter(w -> VRBL_SE_WGVL.equals(w.getVrblSeCd()))
                .filter(w -> USE_Y.equals(w.getUseYn()))
                .filter(w -> w.getStngVl() != null)
                // 아래에서 sort() 하므로 가변 리스트가 보장돼야 한다.
                // Collectors.toList() 는 가변성을 명세로 보장하지 않는다.
                .collect(Collectors.toCollection(ArrayList::new));

        // 3) 남은 게 없으면 빈 목록
        if (used.isEmpty()) {
            return new ArrayList<>();
        }

        // 잔차를 몰아 줄 대상은 sort_seq 최대 지표다. 매퍼가 이미 sort_seq 순으로 내리지만,
        // 정렬 근거를 이 메서드 안에 남겨 매퍼 ORDER BY 가 바뀌어도 규칙이 유지되게 한다.
        // 묶음으로 나눈 뒤에도 각 묶음 안의 순서가 이 정렬을 그대로 따른다(LinkedHashMap · 안정 정렬).
        used.sort(Comparator.comparing(AnalysisWeightVO::getSortSeq,
                Comparator.nullsLast(Comparator.naturalOrder())));

        // 재정규화도 저장 검사(4-1)와 같은 (anls_type_cd, anls_trgt_cd) 묶음 단위다.
        //    🔴 단위가 다르면 "저장은 됐는데 분석에 쓰인 가중치는 다른 묶음 합으로 나뉜다" 가 된다.
        //    HOTSPOT 은 NULL 묶음 하나라 결과가 종전과 같다.
        List<AnalysisWeightVO> effective = new ArrayList<>(used.size());
        for (List<AnalysisWeightVO> group : groupByTrgt(used).values()) {
            // 합계 0 인 묶음은 건너뛴다 (종전 "합계 0 → 빈 목록" 을 묶음마다 적용)
            BigDecimal total = group.stream().map(AnalysisWeightVO::getStngVl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal accumulated = BigDecimal.ZERO.setScale(WGVL_SCALE);
            for (int i = 0; i < group.size(); i++) {
                AnalysisWeightVO src = group.get(i);
                BigDecimal rate;
                if (i < group.size() - 1) {
                    // 4) 묶음의 남은 합계로 다시 100 을 맞춘다
                    rate = src.getStngVl().multiply(HUNDRED).divide(total, WGVL_SCALE, RoundingMode.HALF_UP);
                    accumulated = accumulated.add(rate);
                } else {
                    // 5) 묶음 안 마지막(sort_seq 최대) 지표가 반올림 잔차를 흡수한다
                    rate = HUNDRED.setScale(WGVL_SCALE).subtract(accumulated);
                }
                effective.add(withStngVl(src, rate, src.getMdfcnDt()));
            }
        }
        return effective;
    }

    // ---------------------------------------------------- 계수 조회 (분석 산식 전용, API 미노출)

    /**
     * {@inheritDoc}
     *
     * <p>조회는 {@link AnalysisWeightMapper#selectWeightsByType(String)} 결과를 재사용한다 —
     * 이미 {@code vrbl_se_cd}·{@code stng_vl}·{@code bsc_stng_vl}·{@code use_yn} 을 모두 내리므로
     * 계수 전용 SQL 을 따로 만들지 않는다(SELECT 컬럼이 두 곳으로 갈리는 것을 막는다).
     */
    @Override
    public BigDecimal coefficient(String anlsTypeCd, String vrblCd) {
        AnalysisWeightVO row = analysisWeightMapper.selectWeightsByType(anlsTypeCd).stream()
                .filter(w -> vrblCd.equals(w.getVrblCd()))
                .findFirst()
                .orElse(null);

        // 1) 행 자체가 없다 — 시드 누락·환경 차이. 기본설정값도 없으므로 1.0 으로 진행한다.
        if (row == null) {
            log.warn("분석 계수 행이 없어 기본값 {} 로 진행합니다: anlsTypeCd={}, vrblCd={}",
                    DEFAULT_CFC, anlsTypeCd, vrblCd);
            return DEFAULT_CFC;
        }

        // 2) 꺼져 있거나(use_yn='N') 값이 비었다 — bsc_stng_vl(최초 등재값) → 1.0 순으로 폴백.
        //    🔴 예외를 던지지 않는다. 설정 한 행 때문에 분석 화면 전체가 500 이 되면 안 된다
        //       (규약). 대신 WARN 1건으로 드러낸다.
        if (!USE_Y.equals(row.getUseYn()) || row.getStngVl() == null) {
            BigDecimal fallback = row.getBscStngVl() != null ? row.getBscStngVl() : DEFAULT_CFC;
            log.warn("분석 계수를 쓸 수 없어 폴백값 {} 로 진행합니다: anlsTypeCd={}, vrblCd={}, useYn={}, stngVl={}",
                    fallback, anlsTypeCd, vrblCd, row.getUseYn(), row.getStngVl());
            return fallback;
        }

        return row.getStngVl();
    }

    // ------------------------------------------------------------------ 내부 헬퍼

    /**
     * 분석유형 1건에 변수 목록과 합계를 채운다. 조회(GET)·저장(PUT) 응답이 <b>같은 조립 메서드</b>를 쓴다.
     *
     * <p>{@code stngVlSum} 은 매퍼 SELECT 에 없는 파생값이라 여기서 {@link BigDecimal} 로 합산한다.
     *
     * <p>🔴 <b>합산 대상은 {@code vrbl_se_cd='WGVL'} 이고 {@code use_yn='Y'} 인 행뿐이다</b>
     * "합계 100" 은 <b>가중치 한정</b> 계약이라, 계수(CFC)를 섞으면
     * 생활권의 {@code stngVlSum} 이 {@code 67+33+1.0 = 101} 이 되어 응답이 계약과 어긋난다.
     * 4) 합계 검증·{@link #selectEffectiveWeights(String)} 재정규화와 <b>같은 필터</b>를 쓴다 —
     * 한 곳만 좁히면 "저장은 되는데 응답 합계가 100 이 아닌" 상태가 생긴다.
     * ⚠️ 화면의 합계 배지는 프론트가 자체 합산한다 — 여기는 API 계약 쪽이다.
     *
     * <p>🆕 하위 유형 (=A) — 행마다 {@code anlsTrgtNm} 을 채우고 행을 <b>묶음 단위로</b> 다시 줄 세운다.
     * 조회(GET 전체)·저장(PUT) 응답이 모두 여기를 지나므로 묶음 순서가 한 곳에서 정해진다.
     */
    private AnalysisTypeWeightVO assemble(AnalysisTypeWeightVO type, List<AnalysisWeightVO> weights) {
        List<AnalysisWeightVO> list = weights == null ? new ArrayList<>() : new ArrayList<>(weights);
        arrangeByTrgt(type.getAnlsTypeCd(), list);
        type.setWeights(list);
        type.setStngVlSum(list.stream()
                .filter(w -> VRBL_SE_WGVL.equals(w.getVrblSeCd()))
                .filter(w -> USE_Y.equals(w.getUseYn()))
                .map(AnalysisWeightVO::getStngVl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        type.setTrgtSums(trgtSums(list));
        return type;
    }

    /**
     * 하위 유형 묶음별 가중치 합계를 만든다. {@code list} 는 {@link #arrangeByTrgt} 를 거친 뒤라
     * 묶음 순서가 그대로 결과 순서가 된다.
     *
     * <p>합산 필터는 {@code stngVlSum}·4) 합계 검증·재정규화와 같다(WGVL · {@code use_yn='Y'}).
     * 계수(CFC)만 있는 묶음도 원소로 내리되 {@code wgvlYn='N'}·합계 0 이다 — 화면이 묶음마다 배지를 그릴지
     * 이 값으로 판정한다.
     */
    private List<AnalysisTrgtSumVO> trgtSums(List<AnalysisWeightVO> list) {
        List<AnalysisTrgtSumVO> sums = new ArrayList<>();
        for (List<AnalysisWeightVO> group : groupByTrgt(list).values()) {
            List<BigDecimal> active = group.stream()
                    .filter(w -> VRBL_SE_WGVL.equals(w.getVrblSeCd()))
                    .filter(w -> USE_Y.equals(w.getUseYn()))
                    .map(AnalysisWeightVO::getStngVl)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            AnalysisTrgtSumVO sum = new AnalysisTrgtSumVO();
            sum.setAnlsTrgtCd(group.get(0).getAnlsTrgtCd());
            sum.setAnlsTrgtNm(group.get(0).getAnlsTrgtNm());
            sum.setStngVlSum(active.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            sum.setWgvlYn(active.isEmpty() ? "N" : USE_Y);
            sums.add(sum);
        }
        return sums;
    }

    /**
     * 행을 하위 유형({@code anls_trgt_cd}) 묶음으로 나눈다 — 합계 검증(4-1)·재정규화(4-2)·응답 합계(4-3)가
     * <b>같은 묶음 키</b>를 쓰도록 한 곳에 둔다.
     *
     * <p>NULL(대메뉴 공통)도 하나의 묶음이다. {@code Collectors.groupingBy} 는 null 키에서 NPE 라 쓰지 않는다.
     * 묶음 순서는 처음 나타난 순서, 묶음 안 순서는 입력 순서를 그대로 지킨다.
     */
    private static Map<String, List<AnalysisWeightVO>> groupByTrgt(List<AnalysisWeightVO> rows) {
        Map<String, List<AnalysisWeightVO>> groups = new LinkedHashMap<>();
        for (AnalysisWeightVO w : rows) {
            groups.computeIfAbsent(w.getAnlsTrgtCd(), k -> new ArrayList<>()).add(w);
        }
        return groups;
    }

    /**
     * 400 메시지용 하위 유형 표기 — NULL 이면 {@code 공통}, 이름이 있으면 이름, 없으면 코드값.
     *
     * <p>🔴 {@code 공통} 은 {@code anls_trgt_cd IS NULL} 일 때만 쓴다 — 이름 미등재 코드(매핑 상수에 없는 대메뉴 ·
     * 공통코드 미등재)를 {@code 공통} 으로 적으면 어느 묶음이 틀렸는지 가려진다.
     * 실패 경로에서만 부르므로 공통코드 조회는 400 직전 1회다.
     */
    private String trgtLabel(String anlsTypeCd, String anlsTrgtCd) {
        if (anlsTrgtCd == null) {
            return "공통";
        }
        CommonCodeVO c = trgtCodes(anlsTypeCd).get(anlsTrgtCd);
        return c == null || c.getCdNm() == null ? anlsTrgtCd : c.getCdNm();
    }

    /**
     * 대메뉴의 하위 유형 공통코드 — 코드값 → 공통코드. LinkedHashMap 순서 = 공통코드 {@code sort_seq} 순.
     * 매핑 상수({@link #TRGT_CD_GROUPS})에 없는 대메뉴는 빈 맵이다(조회하지 않는다).
     */
    private Map<String, CommonCodeVO> trgtCodes(String anlsTypeCd) {
        Map<String, CommonCodeVO> codes = new LinkedHashMap<>();
        String groupCd = TRGT_CD_GROUPS.get(anlsTypeCd);
        if (groupCd != null) {
            for (CommonCodeVO c : commonCodeService.selectCommonCodesByGroup(groupCd)) {
                codes.put(c.getCdVl(), c);
            }
        }
        return codes;
    }

    /**
     * 하위 유형 이름을 채우고 행을 묶음 단위로 정렬한다 (=A). {@code list} 를 제자리에서 바꾼다.
     *
     * <p>정렬 규칙 — ① 대메뉴 공통({@code anls_trgt_cd} NULL) 묶음 먼저 ② 그다음 공통코드 {@code sort_seq} 순
     * ③ 묶음 안은 행 {@code sort_seq} 순. 공통코드에 없는 코드(미등재·{@code use_yn='N'}·매핑 상수에 없는 대메뉴)는
     * 맨 뒤에 코드값 순으로 둔다 — 이름이 없으니 기댈 순서가 그것뿐이다.
     *
     * <p>🔴 <b>{@code ORDER BY anls_trgt_cd}(알파벳순)로 대신하지 않는다.</b> {@code CNGST < CVLCPT_CNT < SPLY_DMND} 라
     * 공통코드 순서(민원건수 → 수급 → 혼잡도)와 어긋난다.
     *
     * <p>공통코드 조회는 매핑된 대메뉴일 때만, 대메뉴당 1회다(공통코드 도메인 {@link CommonCodeService} 재사용 —
     * {@code use_yn='Y'} · {@code sort_seq} 순으로 내린다).
     */
    private void arrangeByTrgt(String anlsTypeCd, List<AnalysisWeightVO> list) {
        if (list.isEmpty()) {
            return;
        }
        // 코드값 → (이름, 공통코드 순위). LinkedHashMap 순서 = 공통코드 sort_seq 순.
        Map<String, CommonCodeVO> codes = trgtCodes(anlsTypeCd);
        List<String> rank = new ArrayList<>(codes.keySet());

        for (AnalysisWeightVO w : list) {
            // 시큐어코딩 5.1 — 삼항 연산자 대신 if 로 null 을 먼저 걸러 낸다(동작 동일).
            String trgtCd = w.getAnlsTrgtCd();
            if (trgtCd == null) {
                w.setAnlsTrgtNm(null);
                continue;
            }
            CommonCodeVO c = codes.get(trgtCd);
            if (c == null) {
                w.setAnlsTrgtNm(null);
                continue;
            }
            w.setAnlsTrgtNm(c.getCdNm());
        }

        // 묶음 순위: NULL = -1(먼저) · 공통코드 순위 · 미등재 = 맨 뒤(같은 순위끼리는 코드값으로 갈라 묶음이 섞이지 않게)
        Comparator<AnalysisWeightVO> byGroup = Comparator.comparingInt(w -> {
            String cd = w.getAnlsTrgtCd();
            if (cd == null) {
                return -1;
            }
            int idx = rank.indexOf(cd);
            return idx < 0 ? Integer.MAX_VALUE : idx;
        });
        list.sort(byGroup
                .thenComparing(AnalysisWeightVO::getAnlsTrgtCd, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(AnalysisWeightVO::getSortSeq, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /**
     * 조회 VO 를 복사해 설정값·수정일시만 바꾼 새 VO 를 만든다 (원본 VO 를 훼손하지 않는다).
     *
     * <p>🔴 <b>세터를 손으로 나열한 복사라, VO 에 필드를 추가하면 여기도 함께 늘려야 한다</b>
     * 이 메서드의 결과가 PUT 응답이자 재정규화 출력이라, 빠뜨린 필드는
     * {@code null} 인 채로 응답에 나가고 {@code vrblSeCd} 를 보는 검증·필터가 통째로 꺼진다.
     */
    private AnalysisWeightVO withStngVl(AnalysisWeightVO src, BigDecimal rate, LocalDateTime mdfcnDt) {
        AnalysisWeightVO copy = new AnalysisWeightVO();
        copy.setAnlsTypeCd(src.getAnlsTypeCd());
        copy.setAnlsTrgtCd(src.getAnlsTrgtCd());   // 빠지면 저장 응답에서만 하위 유형이 null
        copy.setAnlsTrgtNm(src.getAnlsTrgtNm());
        copy.setVrblCd(src.getVrblCd());
        copy.setVrblNm(src.getVrblNm());
        copy.setVrblExpln(src.getVrblExpln());
        copy.setVrblSeCd(src.getVrblSeCd());
        copy.setStngVl(rate);
        copy.setBscStngVl(src.getBscStngVl());
        copy.setMinVl(src.getMinVl());
        copy.setMaxVl(src.getMaxVl());
        copy.setUnitNm(src.getUnitNm());
        copy.setUseYn(src.getUseYn());
        copy.setExpsrYn(src.getExpsrYn());   // 빠지면 PUT 응답 expsrYn 이 null → 프론트가 저장 직후 전 행을 숨긴다
        copy.setSortSeq(src.getSortSeq());
        copy.setMdfcnDt(mdfcnDt);
        return copy;
    }

    /**
     * 이력 스냅샷 JSON 조립 — {@code [{"vrblCd":…,"anlsTrgtCd":…,"vrblNm":…,"stngVl":…}, …]}.
     *
     * <p>🆕 {@code anlsTrgtCd} 키는 이전 스냅샷에 없다 — 읽는 쪽은 키가 없으면 NULL(대메뉴 공통)로 본다.
     *
     * <p>감사용이라 <b>스케일을 정규화하지 않고 그대로</b> 싣는다({@code 60.00} 은 {@code 60.00} 으로).
     * 화면 표시용 문장({@code chg_cn})만 {@link #fmt(BigDecimal)} 로 다듬는다.
     */
    private String toSnapshotJson(List<AnalysisWeightVO> weights) {
        List<Map<String, Object>> snapshot = new ArrayList<>(weights.size());
        for (AnalysisWeightVO w : weights) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vrblCd", w.getVrblCd());
            row.put("anlsTrgtCd", w.getAnlsTrgtCd());
            row.put("vrblNm", w.getVrblNm());
            row.put("stngVl", w.getStngVl());
            snapshot.add(row);
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "가중치 이력 스냅샷 직렬화에 실패했습니다", e);
        }
    }

    /**
     * 화면 표시용 숫자 포맷. {@code NUMERIC(5,2)} 에서 읽은 {@code 60.00} 을 {@code 60} 으로 다듬는다.
     *
     * <p>이걸 빼면 이력 문장이 "민원빈도 60.00 → 55" 처럼 이전/이후 표기가 어긋난다 —
     * DB 에서 읽은 '이전' 값은 스케일 2, 요청 JSON 의 '이후' 값은 스케일 0 이기 때문이다.
     * {@code toPlainString()} 을 쓰는 이유는 {@code stripTrailingZeros()} 가 {@code 100} 을
     * {@code 1E+2} 로 바꾸기 때문이다.
     */
    private String fmt(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
