package jeju.datahub.risknoti.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.auth.service.AuthLookupService;
import jeju.datahub.auth.service.UserLookupVO;
import jeju.datahub.common.PageResponse;
import jeju.datahub.risknoti.service.NotiReceiverCandidateVO;
import jeju.datahub.risknoti.service.NotiReceiverDuplicatedException;
import jeju.datahub.risknoti.service.NotiReceiverRegionRequest;
import jeju.datahub.risknoti.service.NotiReceiverRegionVO;
import jeju.datahub.risknoti.service.NotiReceiverRequest;
import jeju.datahub.risknoti.service.NotiReceiverUpdateRequest;
import jeju.datahub.risknoti.service.NotiReceiverVO;
import jeju.datahub.risknoti.service.RiskCriteriaRequest;
import jeju.datahub.risknoti.service.RiskCriteriaVO;
import jeju.datahub.risknoti.service.RiskNotiService;
import lombok.RequiredArgsConstructor;

/**
 * 알림 수신자 · 위험단계 기준 서비스 구현체. 전자정부 {@link EgovAbstractServiceImpl} 상속.
 *
 * <p>쓰기(등록·활성화 토글·소프트 삭제·기준 UPSERT)는 JPA, 조회는 MyBatis({@link RiskNotiMapper}) 다
 * ({@code .claude/rules/rule_persistence-jpa-mybatis.md}). 같은 로직을 두 기술로 구현하지 않는다.
 *
 * <p>🔴 <b>쓰기 직후 MyBatis 재조회 전에는 반드시 {@code flush()}</b> 한다.
 * MyBatis SELECT 는 JPA 영속성 컨텍스트를 flush 시키지 않으므로, 빠뜨리면 응답이 <b>갱신 전 값</b>으로
 * 나간다. 기준 UPSERT 는 할당형 PK 라 {@code save()} 가 {@code merge} 로 가서 더 잘 드러난다.
 */
@Service
@RequiredArgsConstructor
public class RiskNotiServiceImpl extends EgovAbstractServiceImpl implements RiskNotiService {

    /**
     * 수정자 ID 상수. 인증(로그인/세션)이 아직 없어 고정값을 쓴다 (범위 밖 #6 — Step 4 시드와 같은 값).
     *
     * <p>TODO: 로그인 도입 시 인증 주체(SecurityContext 의 사용자 ID)로 교체한다.
     */
    private static final String SYSTEM_USER_ID = "system";

    /** 코드 그룹명 — 위험단계 */
    private static final String GROUP_RISK_LVL_CD = "RISK_LVL_CD";

    /** 기준아이디 접두사 — {@code thrsh_id = 'THRSH_' + risk_lvl_cd} 고정 (D7) */
    private static final String THRSH_ID_PREFIX = "THRSH_";

    /**
     * 통보채널 기본값. POST 바디에 대응 필드가 없어 서버가 채운다 (Step 6 규칙 4-2).
     * 시드 12행이 전부 {@code 'EMAIL'} 이라 신규 행이 갈리지 않게 한다.
     */
    private static final String DEFAULT_NTFCTN_CH_CD = "EMAIL";

    /** 삭제 플래그 값 (D5 — 소프트 삭제) · 사용자 활성 값 ({@code TB_USER.use_yn}) */
    private static final String YES = "Y";

    /** {@code TB_NOTI_RCVR.eml} 길이 — {@code TB_USER.user_eml_addr}(200)를 복사할 때 여기서 자른다 */
    private static final int EML_MAX_LEN = 100;

    /** 합친 regionNm 구분자 · 지역 0행 표시 (TSK-4250 Step 5 — "전체 지역" 으로 보이면 fail-closed 를 가린다) */
    private static final String REGION_NM_SEPARATOR = ", ";
    private static final String REGION_NM_NONE = "지역 없음";

    /** 사용자당 활성 수신자 1행 부분 유니크 인덱스 (TSK-4250 R9) — 이 제약명일 때만 409 로 번역한다 */
    private static final String UQ_RCVR_USER = "uq_tb_noti_rcvr_user";

    private final RiskNotiMapper riskNotiMapper;
    /**
     * 🔴 등록 대상 사용자 단건 조회 창구 (TSK-4250 R8). risknoti 는 auth 의 <b>공개 인터페이스만</b> 참조한다 —
     * {@code auth.service.impl} 의 {@code UserRepository} 를 직접 주입하지 않는다({@code CurrentUserResolver} 관례).
     */
    private final AuthLookupService authLookupService;
    private final NotiRcvrRepository notiRcvrRepository;
    private final NotiRcvrLvlRepository notiRcvrLvlRepository;
    private final NotiRcvrRgnRepository notiRcvrRgnRepository;
    private final RiskCrtrRepository riskCrtrRepository;

    // === 수신자 =============================================================================

    @Override
    public PageResponse<NotiReceiverVO> selectReceivers(int page, int size) {
        // 🔴 COUNT 도 del_yn='N' 만 센다 — 목록(CTE)과 조건이 갈리면 totalElements 가 부풀어
        //    마지막 페이지가 빈 표로 뜬다 (D5 / 부록 §7).
        int totalElements = riskNotiMapper.selectReceiverCount();
        if (totalElements == 0) {
            return PageResponse.of(Collections.<NotiReceiverVO>emptyList(), page, size, totalElements);
        }
        // offset 은 long 으로 계산한다 — page 가 크면 page * size 가 int 범위를 넘어
        // 음수 OFFSET 으로 뒤집힐 수 있다 (SQL 오류 500)
        long offset = (long) page * size;
        List<NotiReceiverVO> content = riskNotiMapper.selectReceiverPage(size, offset);
        // 지역은 페이지 rcvr_id 로 한 번 더 읽어 붙인다 (Step 5 — <collection> 이면 단계 × 지역으로 곱해진다)
        attachRegions(content);
        return PageResponse.of(content, page, size, totalElements);
    }

    /**
     * 🔁 TSK-4250 Step 3 — 수신자는 {@code TB_USER} 에서 고르고(R8) 지역은 다중이다(R1~R3).
     *
     * <p>순서: ① 사용자 조회(없음·정지 400) ② 중복 선검사(409) ③ 단계·지역 정규화(400) ④ 수신자 + 단계 + 지역을
     * <b>한 트랜잭션</b>에 저장. 어느 단계에서 예외가 나도 전부 롤백된다.
     */
    @Override
    @Transactional
    public NotiReceiverVO createReceiver(NotiReceiverRequest req) {
        // ① 사용자 — 이름·이메일·부서는 TB_USER 가 정본이고 수신자 행에는 복사본을 남긴다(R8)
        String userId = trimToNull(req.getUserId());
        UserLookupVO user = userId == null ? null : authLookupService.findUserById(userId);
        // 🔴 활성 판정(use_yn='Y')은 후보 목록(selectReceiverCandidates)과 같은 칸·같은 값이다
        if (user == null || !YES.equals(user.getUseYn())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "등록할 수 없는 사용자입니다(없거나 사용 중지): userId=" + userId);
        }

        // ② 한 사용자 = 활성 수신자 1행 (R9). 경합은 아래 saveAndFlush 의 제약 위반이 받는다
        if (riskNotiMapper.existsActiveReceiverByUserId(user.getUserId())) {
            throw new NotiReceiverDuplicatedException(user.getUserId());
        }

        // ③ 정규화 — 단계(RISK_LVL_CD 대조·중복 제거) · 지역(실재 대조·중복 제거·겹침 400)
        Set<String> riskLvlCds = normalizeRiskLvlCds(req.getRiskLvlCds());
        Set<RegionKey> regions = normalizeRegions(req.getRegions());

        NotiRcvr entity = new NotiRcvr();
        entity.setUserId(user.getUserId());
        entity.setRcvrNm(user.getUserNm());
        // 🔴 user_eml_addr 는 VARCHAR(200), eml 은 VARCHAR(100) — 안 자르면 500.
        //    매칭은 user_id(Q3)·화면은 TB_USER 최신값(Q4)이라 복사본 절단은 기능에 영향이 없다
        entity.setEml(truncate(user.getUserEmlAddr(), EML_MAX_LEN));
        // 무소속(dept_cd NULL)도 그대로 복사한다 (Q2)
        entity.setDeptCd(user.getDeptCd());
        // 4-2) 통보채널은 요청 바디에 없다 — 시드와 같은 값으로 서버가 고정한다
        entity.setNtfctnChCd(DEFAULT_NTFCTN_CH_CD);
        // rcvrId 는 넣지 않는다 — DB identity 가 매긴다 (D11).
        // 4-1) use_yn='Y' · del_yn='N' 은 엔티티 필드 초기값이 채운다 (NotiRcvr 참고).

        // ④-1 수신자 — saveAndFlush 로 INSERT 를 여기서 밀어 부분 유니크 인덱스 위반을 이 자리에서 잡는다.
        //      (뒤로 미뤄지면 커밋 시점에 터져 번역할 자리가 없다 — 500)
        try {
            notiRcvrRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // 🔴 uq_tb_noti_rcvr_user 일 때만 409 — 그 밖의 무결성 위반은 버그라 그대로 500 으로 드러낸다
            if (isConstraintViolation(e, UQ_RCVR_USER)) {
                throw new NotiReceiverDuplicatedException(user.getUserId(), e);
            }
            throw e;
        }

        // ④-2·④-3 단계·지역 매핑 INSERT + flush — 수정(updateReceiver)과 같은 헬퍼
        insertMappings(entity.getRcvrId(), riskLvlCds, regions);

        // 부서명·단계 배열·지역 배열·합친 regionNm 이 붙은 VO 로 되돌려준다 (단건 3경로 공통 헬퍼)
        return findReceiverView(entity.getRcvrId());
    }

    /**
     * 🔁 TSK-4250 Step 4 (R10) — 지역·단계 매핑 <b>통째 교체</b>. 사람 정보는 손대지 않는다.
     *
     * <p>순서: ① 존재·미삭제 확인(404) ② 단계·지역 정규화(400 — 등록과 같은 메서드) ③ 벌크 DELETE 2건
     * ④ 새 매핑 INSERT + flush ⑤ 재조회. 검증을 삭제보다 <b>먼저</b> 해서 400 이면 아무것도 지우지 않는다
     * (어차피 같은 트랜잭션이라 롤백되지만, DB 왕복을 줄인다).
     *
     * <p>🔴 ③ 은 벌크 JPQL 이라 {@code clearAutomatically} 로 영속성 컨텍스트가 비워진다 — ① 에서 읽은 엔티티는
     * 이후 detached 이므로 <b>수정하지 않는다</b>(수정해도 UPDATE 가 나가지 않는다).
     * {@code TB_NOTI_HSTRY_RCVR}(이미 적재된 알림 매핑)는 건드리지 않는다 — 다음 판정부터 반영된다.
     */
    @Override
    @Transactional
    public NotiReceiverVO updateReceiver(Long rcvrId, NotiReceiverUpdateRequest req) {
        // ① 없음·삭제됨 → 404 (활성화·삭제와 같은 판정)
        loadAlive(rcvrId);

        // ② 정규화 — 등록과 같은 규칙(RISK_LVL_CD 대조 · 지역 실재 대조 · 겹침 400)
        Set<String> riskLvlCds = normalizeRiskLvlCds(req.getRiskLvlCds());
        Set<RegionKey> regions = normalizeRegions(req.getRegions());

        // ③ 기존 매핑 즉시 삭제 — 🔴 em.remove 로 미루면 flush 때 INSERT 가 DELETE 보다 먼저 나가
        //    유지되는 단계/지역이 PK·UNIQUE 위반(500)이 된다. 리포지터리 javadoc 참고
        notiRcvrLvlRepository.deleteAllByRcvrIdInBulk(rcvrId);
        notiRcvrRgnRepository.deleteAllByRcvrIdInBulk(rcvrId);

        // ④ 새 매핑 INSERT + flush (등록과 같은 헬퍼)
        insertMappings(rcvrId, riskLvlCds, regions);

        // ⑤ 등록과 같은 재조회 경로
        return findReceiverView(rcvrId);
    }

    @Override
    @Transactional
    public NotiReceiverVO updateReceiverActivation(Long rcvrId, String useYn) {
        NotiRcvr entity = loadAlive(rcvrId);
        entity.setUseYn(useYn);          // JPA 변경 감지로 UPDATE

        // MyBatis 재조회 전에 반드시 DB 로 밀어야 한다 (안 밀면 직전 값이 응답으로 나간다)
        notiRcvrRepository.flush();

        // ★ selectReceiverById 는 XML 에 flushCache="true" 가 걸려 있다 —
        //   같은 트랜잭션에서 이 statement 를 두 번 부르는 경로가 생겨도 1차 캐시가 직전 값을 돌려주지 않게.
        // 🔴 등록·수정과 같은 헬퍼 — 빠지면 토글 직후 화면(replaceRow)이 그 행의 지역 칸을 비운다
        return findReceiverView(rcvrId);
    }

    @Override
    @Transactional
    public void deleteReceiver(Long rcvrId) {
        NotiRcvr entity = loadAlive(rcvrId);
        // 🔴 소프트 삭제 (D5) — repository.delete() 를 부르지 않는다.
        //    행을 지우면 발송기록(TB_NOTI_HSTRY_RCVR)이 FK 로 참조하는 대상이 사라지고,
        //    단계 매핑(TB_NOTI_RCVR_LVL)까지 ON DELETE CASCADE 로 함께 날아간다.
        entity.setDelYn(YES);            // JPA 변경 감지로 UPDATE
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotiReceiverCandidateVO> getReceiverCandidates() {
        return riskNotiMapper.selectReceiverCandidates();
    }

    // === 기준 ===============================================================================

    @Override
    public List<RiskCriteriaVO> selectCriteria() {
        return riskNotiMapper.selectCriteria();
    }

    @Override
    @Transactional
    public List<RiskCriteriaVO> saveCriteria(RiskCriteriaRequest req) {
        // 필수 단계는 공통코드가 정한다 — 하드코딩하지 않는다 (sort_seq 순으로 온다)
        List<String> requiredLvls = riskNotiMapper.selectCodeValues(GROUP_RISK_LVL_CD);
        Set<String> required = new LinkedHashSet<>(requiredLvls);

        Set<String> submitted = new LinkedHashSet<>();
        List<RiskCrtr> entities = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (RiskCriteriaRequest.Item item : req.getCriteria()) {
            String riskLvlCd = item.getRiskLvlCd();
            if (!required.contains(riskLvlCd)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "사용할 수 없는 위험단계 코드입니다: " + riskLvlCd
                                + " (" + String.join(" | ", requiredLvls) + ")");
            }
            if (!submitted.add(riskLvlCd)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "위험단계가 중복됐습니다: " + riskLvlCd + " (단계당 1건)");
            }

            // 값 범위(0 < x <= 100)·소수 2자리는 요청 DTO 의 @DecimalMin/@DecimalMax/@Digits 가 이미 봤다.
            RiskCrtr entity = new RiskCrtr();
            entity.setThrshId(THRSH_ID_PREFIX + riskLvlCd);   // D7: ID 생성이 없다
            entity.setRiskLvlCd(riskLvlCd);
            entity.setIncrsRt(item.getIncrsRt());
            entity.setMinCvlcptCnt(item.getMinCvlcptCnt());   // 🔴 NOT NULL — 빠뜨리면 merge 가 NULL 로 덮는다
            // ⚠️ setBscMinCvlcptCnt(...) 를 부르지 않는다 (D11). updatable=false 라 UPDATE 문에 들어가지 않고,
            //    요청 DTO 에 그 필드가 아예 없다. 여기서 채우려 들면 "기본값도 저장된다"는 오해가 코드에 남는다.
            // 🔴 둘 다 NOT NULL(기본값 없음)이라 UPSERT 마다 채운다 — 빠뜨리면 INSERT 가 터지고,
            //    매핑만 하고 비우면 merge 가 기존 3행을 NULL 로 덮어 500 이 난다.
            entity.setMdfrId(SYSTEM_USER_ID);
            entity.setMdfcnDt(now);
            entities.add(entity);
        }

        // 부분 저장 금지 (D7) — 단계가 하나라도 빠지면 400
        if (!submitted.containsAll(required)) {
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(submitted);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "기준은 " + required.size() + "단계 전부를 보내야 합니다. 누락: " + String.join(", ", missing));
        }

        // 3건 일괄 UPSERT — thrsh_id 가 할당형 PK 라 save() 는 merge 로 간다(있으면 UPDATE, 없으면 INSERT).
        riskCrtrRepository.saveAll(entities);
        // 🔴 merge 는 flush 하지 않는다 — 여기서 밀지 않으면 아래 재조회가 **갱신 전 3건**을 돌려준다
        riskCrtrRepository.flush();

        // 정렬(sort_seq)·형태를 GET 과 동일하게 맞추려고 재조회한다
        return riskNotiMapper.selectCriteria();
    }

    // --- 내부 헬퍼 ------------------------------------------------------------------------

    /**
     * 🔴 <b>단건 응답 3경로(등록 POST · 수정 PUT · 활성화 PATCH)가 모두 이 헬퍼를 탄다</b> (TSK-4250 Step 5).
     * {@code selectReceiverById} + 지역 부착 + 합친 {@code regionNm} 조립을 한 곳에 묶는다 — 한 경로만
     * {@code selectReceiverById} 를 직접 부르면 그 응답만 지역이 빈다.
     *
     * @return 없거나 삭제됐으면 null ({@code selectReceiverById} 와 같다)
     */
    private NotiReceiverVO findReceiverView(Long rcvrId) {
        NotiReceiverVO receiver = riskNotiMapper.selectReceiverById(rcvrId);
        if (receiver != null) {
            attachRegions(List.of(receiver));
        }
        return receiver;
    }

    /**
     * 수신자들에 지역 배열과 합친 {@code regionNm} 을 붙인다 — 목록·단건 공통 (TSK-4250 Step 5 · R6).
     *
     * <p>{@code selectReceiverRegions} 를 <b>한 번</b>만 부르고 {@code rcvr_id} 로 나눈다(N+1 금지).
     * 🔴 {@code rcvrIds} 가 비면 부르지 않는다 — {@code IN )} 문법 오류 500.
     *
     * <p>합친 {@code regionNm}: 원소 {@code regionNm}(매퍼 CASE 가 만든 3분기)을 매퍼 정렬 순서대로 {@code ", "} 로 잇는다.
     * 전체 지역은 원소 1개({@code 전체 지역})라 그대로 그 값이 된다. ⚠️ 지역 0행은 {@code 지역 없음} —
     * {@code 전체 지역} 으로 보이면 fail-closed 상태(아무 지역도 받지 않음)를 가린다(R3).
     */
    private void attachRegions(List<NotiReceiverVO> receivers) {
        if (receivers == null || receivers.isEmpty()) {
            return;
        }
        List<Long> rcvrIds = new ArrayList<>(receivers.size());
        for (NotiReceiverVO receiver : receivers) {
            rcvrIds.add(receiver.getRcvrId());
        }
        Map<Long, List<NotiReceiverRegionVO>> byRcvrId = new HashMap<>();
        for (NotiReceiverRegionVO region : riskNotiMapper.selectReceiverRegions(rcvrIds)) {
            byRcvrId.computeIfAbsent(region.getRcvrId(), k -> new ArrayList<>()).add(region);
        }
        for (NotiReceiverVO receiver : receivers) {
            List<NotiReceiverRegionVO> regions =
                    byRcvrId.getOrDefault(receiver.getRcvrId(), new ArrayList<>());
            receiver.setRegions(regions);
            receiver.setRegionNm(joinRegionNm(regions));
        }
    }

    /** 원소 표시명을 {@code ", "} 로 잇는다. 0행이면 {@code 지역 없음} (R3 fail-closed 를 가리지 않는다) */
    private static String joinRegionNm(List<NotiReceiverRegionVO> regions) {
        if (regions.isEmpty()) {
            return REGION_NM_NONE;
        }
        List<String> names = new ArrayList<>(regions.size());
        for (NotiReceiverRegionVO region : regions) {
            names.add(region.getRegionNm());
        }
        return String.join(REGION_NM_SEPARATOR, names);
    }

    /**
     * 정규화된 단계·지역 매핑을 INSERT 하고 flush 한다 — 등록·수정이 같이 쓴다.
     *
     * <p>호출 시점에 그 수신자의 매핑은 비어 있어야 한다(등록은 신규, 수정은 벌크 삭제 직후).
     * 전체 지역은 {@code (NULL, NULL)} 행 1개다(R3).
     *
     * <p>★ 끝의 {@code flush()} 는 재조회 전 DB 반영이다 — {@link NotiRcvrLvl} 은 할당형 복합 PK 라
     * {@code save()}=merge 로 INSERT 가 flush 까지 미뤄지고, MyBatis SELECT 는 flush 를 일으키지 않는다
     * (빠뜨리면 응답 {@code riskLvlCds} 가 {@code []}).
     */
    private void insertMappings(Long rcvrId, Set<String> riskLvlCds, Set<RegionKey> regions) {
        List<NotiRcvrLvl> levels = new ArrayList<>();
        for (String riskLvlCd : riskLvlCds) {
            levels.add(new NotiRcvrLvl(rcvrId, riskLvlCd));
        }
        notiRcvrLvlRepository.saveAll(levels);

        List<NotiRcvrRgn> regionRows = new ArrayList<>();
        for (RegionKey region : regions) {
            regionRows.add(new NotiRcvrRgn(rcvrId, region.sggNm(), region.emdCd()));
        }
        notiRcvrRgnRepository.saveAll(regionRows);

        notiRcvrLvlRepository.flush();
    }

    /**
     * 활성화·수정·삭제 대상 수신자를 영속 상태로 잡아온다. 없거나 <b>이미 삭제됐으면</b> 404.
     *
     * <p>이미 삭제된 행을 다시 건드리는 요청은 "없는 행"과 같이 취급한다 (D5) —
     * 목록 조회 기준으로 존재하지 않는 행이고, 되살리는 경로를 만들지 않는다.
     */
    private NotiRcvr loadAlive(Long rcvrId) {
        NotiRcvr entity = notiRcvrRepository.findById(rcvrId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "수신자를 찾을 수 없습니다: " + rcvrId));
        if (YES.equals(entity.getDelYn())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "수신자를 찾을 수 없습니다: " + rcvrId);
        }
        return entity;
    }

    /**
     * 위험단계 목록 정규화 — 공백 제거 → 중복 제거 → 코드 대조.
     *
     * <p>중복 제거({@link LinkedHashSet})는 <b>복합 PK 위반 방지</b>다 —
     * {@code ["CRIT","WARN","CRIT"]} 를 그대로 INSERT 하면 {@code pk_tb_noti_rcvr_lvl} 에 걸려 500 이다.
     * 빈 배열은 400 이며(최소 1단계), 요청 DTO 의 {@code @NotEmpty} 가 못 잡는 <b>공백 원소만 있는</b>
     * 경우도 여기서 걸린다.
     */
    private Set<String> normalizeRiskLvlCds(List<String> riskLvlCds) {
        Set<String> normalized = new LinkedHashSet<>();
        if (riskLvlCds != null) {
            for (String riskLvlCd : riskLvlCds) {
                String value = trimToNull(riskLvlCd);
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "riskLvlCds 는 최소 1개 이상이어야 합니다");
        }

        List<String> validCds = riskNotiMapper.selectCodeValues(GROUP_RISK_LVL_CD);
        List<String> invalid = new ArrayList<>();
        for (String riskLvlCd : normalized) {
            if (!validCds.contains(riskLvlCd)) {
                invalid.add(riskLvlCd);
            }
        }
        if (!invalid.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "사용할 수 없는 코드값입니다: riskLvlCds=" + String.join(", ", invalid)
                            + " (" + String.join(" | ", validCds) + ")");
        }
        return normalized;
    }

    /**
     * 수신 지역 목록 정규화 (TSK-4250 Step 3 — R3·R11).
     *
     * <p>원소마다 {@link #normalizeRegion} 을 적용한 뒤 {@link LinkedHashSet} 으로 같은 지역을 제거하고
     * (단계 목록과 같은 처리 — {@code uq_tb_noti_rcvr_rgn} 위반 방지), 겹침을 400 으로 끊는다:
     * <ul>
     *   <li>전체 지역 원소 + 다른 원소 공존 — 전체가 이미 모두를 덮는다</li>
     *   <li>시군구 전체 + 그 시군구의 읍면동 공존 — 시군구 전체가 이미 그 읍면동을 덮는다</li>
     * </ul>
     * 🔴 화면도 같은 규칙으로 막지만 API 직접 호출은 화면을 거치지 않는다(R11).
     * 빈 목록은 400 이다 — DTO 의 {@code @NotEmpty} 가 먼저 잡지만 서비스 단독 호출에도 fail-closed(R3).
     */
    private Set<RegionKey> normalizeRegions(List<NotiReceiverRegionRequest> regions) {
        if (regions == null || regions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "regions 는 최소 1개 이상이어야 합니다 (전체 지역은 [{}])");
        }
        Set<RegionKey> normalized = new LinkedHashSet<>();
        for (NotiReceiverRegionRequest region : regions) {
            if (region == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "regions 에 null 원소가 있습니다 (전체 지역은 {})");
            }
            normalized.add(normalizeRegion(region));
        }

        // 겹침 ① 전체 지역 + 다른 지역
        if (normalized.contains(RegionKey.ALL) && normalized.size() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "전체 지역은 다른 지역과 함께 지정할 수 없습니다");
        }
        // 겹침 ② 시군구 전체 + 그 시군구의 읍면동
        Set<String> wholeSggNms = new LinkedHashSet<>();
        for (RegionKey region : normalized) {
            if (region.sggNm() != null && region.emdCd() == null) {
                wholeSggNms.add(region.sggNm());
            }
        }
        for (RegionKey region : normalized) {
            if (region.emdCd() != null && wholeSggNms.contains(region.sggNm())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "시군구 전체와 그 시군구의 읍면동을 함께 지정할 수 없습니다: sggNm="
                                + region.sggNm() + ", emdCd=" + region.emdCd());
            }
        }
        return normalized;
    }

    /**
     * 지역 원소 하나의 3형태 정규화 (D13 단건 규칙을 원소 단위로 옮긴 것).
     *
     * <ul>
     *   <li>{@code emdCd} 있음 → {@code sggNm} 은 <b>서버가 TB_ADMDST 에서 다시 채운다.</b>
     *       클라이언트가 보낸 값은 버린다 — '제주시 + 성산읍' 같은 불일치 조합을 원천 차단한다.
     *       없는 {@code emdCd} 는 400 (FK 위반을 500 으로 흘리지 않는다).</li>
     *   <li>{@code sggNm} 만 → <b>존재 확인 후</b> 시군구 전체 ({@code emdCd} 는 null).
     *       🔴 대조가 없으면 오타('제주 시')가 그대로 저장돼 화면 {@code regionNm} 에 노출된다.</li>
     *   <li>둘 다 없음 → 전체 지역 (둘 다 null — R3 의 명시 행)</li>
     * </ul>
     *
     * <p>빈 문자열은 "지정 안 함"으로 본다 — 폼이 비운 select 를 {@code ""} 로 보내는 경우가 있고,
     * 그대로 저장하면 {@code emd_cd=''} 가 FK 위반으로 500 이 된다.
     */
    private RegionKey normalizeRegion(NotiReceiverRegionRequest region) {
        String emdCd = trimToNull(region.getEmdCd());
        String sggNm = trimToNull(region.getSggNm());

        if (emdCd != null) {
            String resolvedSggNm = riskNotiMapper.selectSggNmByEmdCd(emdCd);
            if (resolvedSggNm == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 읍면동코드입니다: " + emdCd);
            }
            return new RegionKey(resolvedSggNm, emdCd);
        }

        if (sggNm != null) {
            if (riskNotiMapper.countSggNm(sggNm) == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "존재하지 않는 시군구명입니다: " + sggNm);
            }
            return new RegionKey(sggNm, null);
        }

        return RegionKey.ALL;
    }

    /**
     * 예외 원인 사슬에 지정한 제약명의 위반이 있는지. Hibernate 가 추출한 제약명을 먼저 보고,
     * 추출하지 못한 경우를 대비해 JDBC 예외 메시지도 본다(PostgreSQL 은 메시지에 제약명을 싣는다).
     */
    private static boolean isConstraintViolation(Throwable e, String constraintName) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && constraintName.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            if (t instanceof java.sql.SQLException sqle
                    && sqle.getMessage() != null && sqle.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    /** 길이 상한에서 자른다 (null 은 null) */
    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    /** 공백만 있는 문자열·빈 문자열을 null 로 접는다 (선택 입력의 "지정 안 함" 판정용) */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 정규화된 지역 한 칸 — {@code (sggNm, emdCd)}. record 의 동등성이 null 을 포함해 비교하므로
     * {@link LinkedHashSet} 중복 제거 키로 쓴다. {@link #ALL} 은 전체 지역(둘 다 null — R3)이다.
     */
    private record RegionKey(String sggNm, String emdCd) {
        static final RegionKey ALL = new RegionKey(null, null);
    }
}
