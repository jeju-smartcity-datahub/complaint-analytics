-- 제주 데이터허브 parking · batch 스키마 DDL (2026-11-18, 마이그레이션 118건 적용 기준)
-- 사전조건: PostgreSQL 15 + PostGIS 3.4
CREATE EXTENSION IF NOT EXISTS postgis;

--
-- PostgreSQL database dump
--

-- Dumped from database version 15.8 (Debian 15.8-1.pgdg110+1)
-- Dumped by pg_dump version 15.8 (Debian 15.8-1.pgdg110+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: batch; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA batch;


--
-- Name: parking; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA parking;


--
-- Name: source_row_hash(text[]); Type: FUNCTION; Schema: batch; Owner: -
--

CREATE FUNCTION batch.source_row_hash(VARIADIC parts text[]) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$
  SELECT encode(sha256(convert_to(COALESCE(string_agg(
    CASE WHEN part IS NULL THEN '-1:' ELSE length(part)::text || ':' || part END,
    '' ORDER BY ord), ''), 'UTF8')), 'hex')
  FROM unnest(parts) WITH ORDINALITY AS p(part, ord)
$$;


--
-- Name: fn_jenks_shortage(date, date, text, text, text, text); Type: FUNCTION; Schema: parking; Owner: -
--

CREATE FUNCTION parking.fn_jenks_shortage(p_start_dt date, p_end_dt date, p_sgg_cd text DEFAULT NULL::text, p_emd_cd text DEFAULT NULL::text, p_cvlcpt_type_cd text DEFAULT NULL::text, p_grade_filter text DEFAULT NULL::text) RETURNS TABLE(out_emd_cd character varying, out_emd_nm character varying, out_pklt_sum numeric, out_cvlcpt_sum bigint, out_psdr numeric, out_grade text)
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    v_n        int;
    v_data     numeric[];
    v_cut1     int := 0;
    v_cut2     int := 0;
    v_ssi      numeric[];
    v_ssi2     numeric[];
    v_i        int;
    v_l        int;
    v_m        int;
    v_w        numeric;
    v_best     numeric;
BEGIN
    -- ── (A) Jenks 입력: PSDR 이 NOT NULL 인 zone 들의 psdr 배열 + 카운트
    --      🔴 `ORDER BY psdr, emd_cd` — Jenks 는 정렬된 값 배열을 전제한다.
    --         (D) 의 row_number() 와 **같은 정렬식**이어야 cut index 가 행에 맞는다.
    SELECT
        COALESCE(array_agg(psdr ORDER BY psdr, emd_cd), '{}'::numeric[]),
        COUNT(*)
      INTO v_data, v_n
      FROM (
          SELECT
              a.emd_cd,
              a.emd_nm,
              COALESCE(p.pklt_sum, 0)::numeric AS pklt_sum,
              COALESCE(c.cvlcpt_sum, 0)::bigint AS cvlcpt_sum,
              CASE
                  WHEN COALESCE(c.cvlcpt_sum, 0) = 0 THEN NULL
                  ELSE ROUND((COALESCE(p.pklt_sum, 0)::numeric / c.cvlcpt_sum)::numeric, 4)
              END AS psdr
          FROM parking.tb_admdst a
          LEFT JOIN (
              SELECT emd_cd, SUM(pklt_cpcty)::numeric AS pklt_sum
              FROM parking.tb_pblpklt
              WHERE pklt_type_cd IN ('PBLPKLT', 'ONSTR', 'VACLOT')
                AND emd_cd IS NOT NULL
              GROUP BY emd_cd
          ) p ON p.emd_cd = a.emd_cd
          LEFT JOIN (
              SELECT emd_cd, COUNT(*) AS cvlcpt_sum
              FROM parking.tb_cvlcpt
              WHERE cvlcpt_type_cd IN ('01','02','03','04','05')
                AND emd_cd IS NOT NULL
                AND ocrn_dt::date BETWEEN p_start_dt AND p_end_dt
              GROUP BY emd_cd
          ) c ON c.emd_cd = a.emd_cd
          WHERE a.bndry_geom IS NOT NULL
            AND a.emd_cd IS NOT NULL
            AND (p_sgg_cd IS NULL OR LEFT(a.emd_cd, 5) = p_sgg_cd)
            AND (p_emd_cd IS NULL OR a.emd_cd = p_emd_cd)
      ) src
     WHERE psdr IS NOT NULL;

    -- ── (B) 데이터 부족 (n < 3) — Jenks 부적합 → grade 미배정
    --      p_grade_filter 가 NULL(전체) 면 노출, 아니면 빈 결과
    --      ⚠️ 이 분기는 종전과 동일하다 (등급을 배정하지 않으므로 정렬과 무관).
    IF v_n < 3 THEN
        IF p_grade_filter IS NULL THEN
            RETURN QUERY
                WITH _src AS (
                    SELECT
                        a.emd_cd,
                        a.emd_nm,
                        COALESCE(p.pklt_sum, 0)::numeric AS pklt_sum,
                        COALESCE(c.cvlcpt_sum, 0)::bigint AS cvlcpt_sum,
                        CASE
                            WHEN COALESCE(c.cvlcpt_sum, 0) = 0 THEN NULL
                            ELSE ROUND((COALESCE(p.pklt_sum, 0)::numeric / c.cvlcpt_sum)::numeric, 4)
                        END AS psdr
                    FROM parking.tb_admdst a
                    LEFT JOIN (
                        SELECT emd_cd, SUM(pklt_cpcty)::numeric AS pklt_sum
                        FROM parking.tb_pblpklt
                        WHERE pklt_type_cd IN ('PBLPKLT', 'ONSTR', 'VACLOT')
                          AND emd_cd IS NOT NULL
                        GROUP BY emd_cd
                    ) p ON p.emd_cd = a.emd_cd
                    LEFT JOIN (
                        SELECT emd_cd, COUNT(*) AS cvlcpt_sum
                        FROM parking.tb_cvlcpt
                        WHERE cvlcpt_type_cd IN ('01','02','03','04','05')
                          AND emd_cd IS NOT NULL
                          AND ocrn_dt::date BETWEEN p_start_dt AND p_end_dt
                        GROUP BY emd_cd
                    ) c ON c.emd_cd = a.emd_cd
                    WHERE a.bndry_geom IS NOT NULL
                      AND a.emd_cd IS NOT NULL
                      AND (p_sgg_cd IS NULL OR LEFT(a.emd_cd, 5) = p_sgg_cd)
                      AND (p_emd_cd IS NULL OR a.emd_cd = p_emd_cd)
                )
                SELECT
                    s.emd_cd::varchar     AS out_emd_cd,
                    s.emd_nm::varchar     AS out_emd_nm,
                    s.pklt_sum            AS out_pklt_sum,
                    s.cvlcpt_sum          AS out_cvlcpt_sum,
                    s.psdr                AS out_psdr,
                    NULL::text            AS out_grade
                FROM _src s;
        END IF;
        RETURN;
    END IF;

    -- ── (C) Jenks DP — cut index 직접 탐색 (k=3, n 작음 → O(n²))
    v_ssi  := array_fill(0::numeric, ARRAY[v_n+1]);
    v_ssi2 := array_fill(0::numeric, ARRAY[v_n+1]);
    FOR v_i IN 1..v_n LOOP
        v_ssi[v_i+1]  := v_ssi[v_i]  + v_data[v_i];
        v_ssi2[v_i+1] := v_ssi2[v_i] + v_data[v_i] * v_data[v_i];
    END LOOP;

    v_best := NULL;
    FOR v_l IN 2..(v_n-1) LOOP
        FOR v_m IN (v_l+1)..v_n LOOP
            v_w :=
                  (v_ssi2[v_l-1] - (v_ssi[v_l-1]*v_ssi[v_l-1]) / (v_l-1))
                + (v_ssi2[v_m-1] - v_ssi2[v_l-1]
                   - ((v_ssi[v_m-1]-v_ssi[v_l-1]) * (v_ssi[v_m-1]-v_ssi[v_l-1]))
                     / (v_m - v_l))
                + (v_ssi2[v_n] - v_ssi2[v_m-1]
                   - ((v_ssi[v_n]-v_ssi[v_m-1]) * (v_ssi[v_n]-v_ssi[v_m-1]))
                     / (v_n - v_m + 1));
            IF v_best IS NULL OR v_w < v_best THEN
                v_best := v_w;
                v_cut1 := v_l;
                v_cut2 := v_m;
            END IF;
        END LOOP;
    END LOOP;

    -- ── (D) grade 배정 + p_grade_filter 적용 (CTE 만 사용)
    --      🔴 2단 CTE — `_base` 가 psdr 을 계산하고 `_src` 가 그 값으로 번호를 매긴다.
    --         한 SELECT 안에서 `row_number() OVER (ORDER BY psdr …)` 를 쓰면 출력 별칭을
    --         윈도우 ORDER BY 가 못 봐서 파싱 에러다.
    --      🔴 정렬식은 (A) 와 동일한 `psdr NULLS LAST, emd_cd` 다.
    RETURN QUERY
        WITH _base AS (
            SELECT
                a.emd_cd,
                a.emd_nm,
                COALESCE(p.pklt_sum, 0)::numeric AS pklt_sum,
                COALESCE(c.cvlcpt_sum, 0)::bigint AS cvlcpt_sum,
                CASE
                    WHEN COALESCE(c.cvlcpt_sum, 0) = 0 THEN NULL
                    ELSE ROUND((COALESCE(p.pklt_sum, 0)::numeric / c.cvlcpt_sum)::numeric, 4)
                END AS psdr
            FROM parking.tb_admdst a
            LEFT JOIN (
                SELECT emd_cd, SUM(pklt_cpcty)::numeric AS pklt_sum
                FROM parking.tb_pblpklt
                WHERE pklt_type_cd IN ('PBLPKLT', 'ONSTR', 'VACLOT')
                  AND emd_cd IS NOT NULL
                GROUP BY emd_cd
            ) p ON p.emd_cd = a.emd_cd
            LEFT JOIN (
                SELECT emd_cd, COUNT(*) AS cvlcpt_sum
                FROM parking.tb_cvlcpt
                WHERE cvlcpt_type_cd IN ('01','02','03','04','05')
                  AND emd_cd IS NOT NULL
                  AND ocrn_dt::date BETWEEN p_start_dt AND p_end_dt
                GROUP BY emd_cd
            ) c ON c.emd_cd = a.emd_cd
            WHERE a.bndry_geom IS NOT NULL
              AND a.emd_cd IS NOT NULL
              AND (p_sgg_cd IS NULL OR LEFT(a.emd_cd, 5) = p_sgg_cd)
              AND (p_emd_cd IS NULL OR a.emd_cd = p_emd_cd)
        ),
        _src AS (
            SELECT
                b.emd_cd,
                b.emd_nm,
                b.pklt_sum,
                b.cvlcpt_sum,
                b.psdr,
                row_number() OVER (ORDER BY b.psdr NULLS LAST, b.emd_cd) AS idx
            FROM _base b
        )
        SELECT
            i.emd_cd::varchar     AS out_emd_cd,
            i.emd_nm::varchar     AS out_emd_nm,
            i.pklt_sum            AS out_pklt_sum,
            i.cvlcpt_sum          AS out_cvlcpt_sum,
            i.psdr                AS out_psdr,
            CASE
                WHEN i.psdr IS NULL THEN CAST(NULL AS text)
                WHEN i.idx < v_cut1 THEN '심각'
                WHEN i.idx < v_cut2 THEN '주의'
                ELSE '양호'
            END::text AS out_grade
        FROM _src i
        WHERE
            (p_grade_filter IS NULL)
            OR (p_grade_filter = '심각' AND i.psdr IS NOT NULL AND i.idx < v_cut1)
            OR (p_grade_filter = '주의' AND i.psdr IS NOT NULL AND i.idx >= v_cut1 AND i.idx < v_cut2)
            OR (p_grade_filter = '양호' AND i.psdr IS NOT NULL AND i.idx >= v_cut2);
END;
$$;


--
-- Name: FUNCTION fn_jenks_shortage(p_start_dt date, p_end_dt date, p_sgg_cd text, p_emd_cd text, p_cvlcpt_type_cd text, p_grade_filter text); Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON FUNCTION parking.fn_jenks_shortage(p_start_dt date, p_end_dt date, p_sgg_cd text, p_emd_cd text, p_cvlcpt_type_cd text, p_grade_filter text) IS 'Jenks Natural Breaks k=3 으로 수요부족 지역 등급 분류 (심각/주의/양호). PSDR = tb_pblpklt.pklt_cpcty 합산 / tb_cvlcpt 주차 관련 5종 민원 수. p_grade_filter 로 특정 등급만 반환 가능. TSK-3873 §3 KPI #4 + §4 분석레이어 shortage (2026-09-04). 🔴 TSK-4197 (2026-10-02) 정렬 결함 수정 — Jenks 입력 배열과 등급 판정 인덱스를 모두 `psdr, emd_cd` 순으로 맞췄다. 종전에는 둘 다 `emd_cd` 순이라 등급이 값이 아니라 읍면동 코드 순으로 갈렸다(심각 max 70.0 > 양호 min 2.09).';


--
-- Name: fn_shortage_count(date, date, text, text, text); Type: FUNCTION; Schema: parking; Owner: -
--

CREATE FUNCTION parking.fn_shortage_count(p_start_dt date, p_end_dt date, p_sgg_cd text DEFAULT NULL::text, p_emd_cd text DEFAULT NULL::text, p_cvlcpt_type_cd text DEFAULT NULL::text) RETURNS bigint
    LANGUAGE sql STABLE
    AS $$
    SELECT COUNT(*)::bigint
    FROM parking.fn_jenks_shortage(p_start_dt, p_end_dt, p_sgg_cd, p_emd_cd, p_cvlcpt_type_cd, '심각');
$$;


--
-- Name: FUNCTION fn_shortage_count(p_start_dt date, p_end_dt date, p_sgg_cd text, p_emd_cd text, p_cvlcpt_type_cd text); Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON FUNCTION parking.fn_shortage_count(p_start_dt date, p_end_dt date, p_sgg_cd text, p_emd_cd text, p_cvlcpt_type_cd text) IS '§3 KPI #4 shortageCount — Jenks "심각" 등급 zone 수. TSK-3873 (2026-09-04).';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: batch_job_execution; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.batch_job_execution (
    job_execution_id bigint NOT NULL,
    version bigint,
    job_instance_id bigint NOT NULL,
    create_time timestamp without time zone NOT NULL,
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    status character varying(10),
    exit_code character varying(2500),
    exit_message character varying(2500),
    last_updated timestamp without time zone
);


--
-- Name: batch_job_execution_context; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.batch_job_execution_context (
    job_execution_id bigint NOT NULL,
    short_context character varying(2500) NOT NULL,
    serialized_context text
);


--
-- Name: batch_job_execution_params; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.batch_job_execution_params (
    job_execution_id bigint NOT NULL,
    parameter_name character varying(100) NOT NULL,
    parameter_type character varying(100) NOT NULL,
    parameter_value character varying(2500),
    identifying character(1) NOT NULL
);


--
-- Name: batch_job_execution_seq; Type: SEQUENCE; Schema: batch; Owner: -
--

CREATE SEQUENCE batch.batch_job_execution_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: batch_job_instance; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.batch_job_instance (
    job_instance_id bigint NOT NULL,
    version bigint,
    job_name character varying(100) NOT NULL,
    job_key character varying(32) NOT NULL
);


--
-- Name: batch_job_seq; Type: SEQUENCE; Schema: batch; Owner: -
--

CREATE SEQUENCE batch.batch_job_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: batch_step_execution; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.batch_step_execution (
    step_execution_id bigint NOT NULL,
    version bigint NOT NULL,
    step_name character varying(100) NOT NULL,
    job_execution_id bigint NOT NULL,
    create_time timestamp without time zone NOT NULL,
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    status character varying(10),
    commit_count bigint,
    read_count bigint,
    filter_count bigint,
    write_count bigint,
    read_skip_count bigint,
    write_skip_count bigint,
    process_skip_count bigint,
    rollback_count bigint,
    exit_code character varying(2500),
    exit_message character varying(2500),
    last_updated timestamp without time zone
);


--
-- Name: batch_step_execution_context; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.batch_step_execution_context (
    step_execution_id bigint NOT NULL,
    short_context character varying(2500) NOT NULL,
    serialized_context text
);


--
-- Name: batch_step_execution_seq; Type: SEQUENCE; Schema: batch; Owner: -
--

CREATE SEQUENCE batch.batch_step_execution_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tb_btch_excn_hstry; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.tb_btch_excn_hstry (
    job_execution_id bigint NOT NULL,
    job_nm character varying(100) NOT NULL,
    status character varying(20) NOT NULL,
    run_mode character varying(20),
    target_range character varying(50),
    started_dt timestamp without time zone NOT NULL,
    ended_dt timestamp without time zone,
    duration_ms bigint,
    read_cnt bigint,
    write_cnt bigint,
    skip_geocode_failed bigint,
    skip_emd_unresolved bigint,
    filtered_transient bigint,
    geocode_api_lookups bigint,
    geocode_cache_hits bigint,
    geocode_place_recoveries bigint,
    geocode_stripped bigint,
    emd_contains bigint,
    emd_buffer bigint,
    unclsf_cnt bigint,
    unknown_types jsonb,
    failure_message text,
    created_dt timestamp without time zone DEFAULT now() NOT NULL,
    emd_name_fallback bigint,
    excn_se_cd character varying(10),
    excutr_id character varying(20),
    up_excn_id bigint
);


--
-- Name: TABLE tb_btch_excn_hstry; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON TABLE batch.tb_btch_excn_hstry IS '배치실행이력 - Job 실행 1회 = 1행. 종료 요약 로그의 지표를 영구 보존해 실행 간 추이를 SQL 로 비교한다';


--
-- Name: COLUMN tb_btch_excn_hstry.job_execution_id; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.job_execution_id IS 'Spring Batch JobExecution ID - batch.batch_job_execution 과 조인';


--
-- Name: COLUMN tb_btch_excn_hstry.job_nm; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.job_nm IS '작업명 - Spring Batch Job 이름';


--
-- Name: COLUMN tb_btch_excn_hstry.status; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.status IS 'Job 종료 상태 - COMPLETED | FAILED | STOPPED 등(BatchStatus)';


--
-- Name: COLUMN tb_btch_excn_hstry.run_mode; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.run_mode IS '실행 모드 - Job 파라미터 mode, 없으면 from/to→backfill · targetDate→daily · limit→manual 로 추론. 파라미터가 없으면 NULL(프로퍼티 기본값을 따른 실행)';


--
-- Name: COLUMN tb_btch_excn_hstry.target_range; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.target_range IS '조회 구간 - backfill: from~to, daily: 기준일, 표본 실행: limit=N';


--
-- Name: COLUMN tb_btch_excn_hstry.started_dt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.started_dt IS '시작일시 - Job 시작 시각';


--
-- Name: COLUMN tb_btch_excn_hstry.ended_dt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.ended_dt IS '종료일시 - Job 종료 시각';


--
-- Name: COLUMN tb_btch_excn_hstry.duration_ms; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.duration_ms IS '실행 소요(ms) - ended_at - started_at';


--
-- Name: COLUMN tb_btch_excn_hstry.read_cnt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.read_cnt IS '읽기건수 - Reader 가 흘려보낸 행 수';


--
-- Name: COLUMN tb_btch_excn_hstry.write_cnt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.write_cnt IS '쓰기건수 - Writer 가 적재한 행 수';


--
-- Name: COLUMN tb_btch_excn_hstry.filtered_transient; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.filtered_transient IS '지오코딩 일시장애로 이번 실행만 제외된 건 - 스킵 테이블에 남지 않아(D8) 이 컬럼이 유일한 흔적이다';


--
-- Name: COLUMN tb_btch_excn_hstry.geocode_cache_hits; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.geocode_cache_hits IS '캐시 히트 - api_lookups 와 함께 보면 캐시가 실제로 듣고 있는지 알 수 있다';


--
-- Name: COLUMN tb_btch_excn_hstry.geocode_place_recoveries; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.geocode_place_recoveries IS '장소(POI) 검색(D7 3단)으로 복구된 건 - 주소DB(/req/address)에 없어 /req/search 로 살린 주소. geocode_stripped 와 합치지 않는다(이쪽은 정밀도 손실이 없다)';


--
-- Name: COLUMN tb_btch_excn_hstry.geocode_stripped; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.geocode_stripped IS '부번제거(D7 4단)로 복구된 건 - 본번 대표좌표라 번지 단위 정밀도가 아니다';


--
-- Name: COLUMN tb_btch_excn_hstry.unclsf_cnt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.unclsf_cnt IS '미분류건수 - 민원유형 99 로 적재된 행 수';


--
-- Name: COLUMN tb_btch_excn_hstry.unknown_types; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.unknown_types IS '미지 유형 값별 누계 JSON - 어떤 값이 99 를 밀어올렸는지가 원천 표기 변경 탐지의 핵심이다';


--
-- Name: COLUMN tb_btch_excn_hstry.failure_message; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.failure_message IS 'FAILED 원인 요약 - 예외 클래스명 + 메시지 앞부분(자격증명 유출 방지를 위해 길이 제한·마스킹)';


--
-- Name: COLUMN tb_btch_excn_hstry.created_dt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.created_dt IS '생성일시 - 이력 행이 기록된 시각';


--
-- Name: COLUMN tb_btch_excn_hstry.emd_name_fallback; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.emd_name_fallback IS '읍면동이름폴백건수 - 좌표 공간매칭 실패 후 emd_nm 이름 조회로 emd_cd 를 채운 행 수(NULL = 해당 잡이 하지 않음)';


--
-- Name: COLUMN tb_btch_excn_hstry.excn_se_cd; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.excn_se_cd IS '실행구분코드 - SCHDL(스케줄) | MANUAL(운영 화면) | CHAIN(연쇄) | API(HTTP 트리거). NULL = 구분 도입 전 실행';


--
-- Name: COLUMN tb_btch_excn_hstry.excutr_id; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.excutr_id IS '실행자ID - 수동(MANUAL) 실행한 사용자 ID(TB_USER.user_id, FK 미설정). 그 밖은 NULL';


--
-- Name: COLUMN tb_btch_excn_hstry.up_excn_id; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_excn_hstry.up_excn_id IS '상위실행아이디 - 연쇄(CHAIN) 실행일 때 앞 잡의 job_execution_id. 그 밖은 NULL';


--
-- Name: tb_btch_schdl; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.tb_btch_schdl (
    job_nm character varying(100) NOT NULL,
    cron_expr character varying(100) NOT NULL,
    use_yn character(1) NOT NULL,
    mdfr_id character varying(20),
    mdfcn_dt timestamp without time zone,
    CONSTRAINT ck_tb_btch_schdl_use_yn CHECK ((use_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar])))
);


--
-- Name: TABLE tb_btch_schdl; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON TABLE batch.tb_btch_schdl IS '배치일정 - 잡별 스케줄(cron·사용여부)의 정본. 연쇄의 머리 잡에만 둔다(연쇄 잡은 env 플래그). 화면(M0604)에서 바꾼다';


--
-- Name: COLUMN tb_btch_schdl.job_nm; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_schdl.job_nm IS '작업명 - Spring Batch Job 이름';


--
-- Name: COLUMN tb_btch_schdl.cron_expr; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_schdl.cron_expr IS '크론식 - Spring 6필드 cron(초 분 시 일 월 요일, Asia/Seoul). 표준단어 미등재 임시명';


--
-- Name: COLUMN tb_btch_schdl.use_yn; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_schdl.use_yn IS '사용여부 - N = 스케줄 중지(수동 실행은 가능)';


--
-- Name: COLUMN tb_btch_schdl.mdfr_id; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_schdl.mdfr_id IS '수정자ID - 마지막으로 바꾼 사용자 ID(NULL = 초기값 그대로)';


--
-- Name: COLUMN tb_btch_schdl.mdfcn_dt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_btch_schdl.mdfcn_dt IS '수정일시 - 마지막 변경 일시';


--
-- Name: tb_geocode_cache; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.tb_geocode_cache (
    addr character varying(200) NOT NULL,
    lat numeric(12,10),
    lot numeric(13,10),
    found boolean NOT NULL,
    fail_rsn character varying(20),
    updated_dt timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE tb_geocode_cache; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON TABLE batch.tb_geocode_cache IS '주소 지오코딩 결과 캐시 - 실행 간 재사용으로 외부 API 호출 절감(실측 고유주소 15.5%)';


--
-- Name: COLUMN tb_geocode_cache.fail_rsn; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_geocode_cache.fail_rsn IS '실패사유 - NULL(성공) | NOT_FOUND(영구, 캐시 히트로 재조회 생략) | TRANSIENT(일시 장애, 히트로 치지 않고 재조회)';


--
-- Name: COLUMN tb_geocode_cache.updated_dt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_geocode_cache.updated_dt IS '수정일시 - 캐시 행이 마지막으로 갱신된 시각';


--
-- Name: tb_geocode_skip; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.tb_geocode_skip (
    src_se character varying(20) NOT NULL,
    trgt_id character varying(64) NOT NULL,
    fail_rsn character varying(30) NOT NULL,
    note text,
    trgt_addr character varying(200),
    checked_dt timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE tb_geocode_skip; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON TABLE batch.tb_geocode_skip IS '지오코딩 실패 확정 - 민원·공영주차장 공통. Reader 가 NOT EXISTS 로 재시도 대상에서 제외';


--
-- Name: COLUMN tb_geocode_skip.src_se; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_geocode_skip.src_se IS '수집출처구분 - 어느 파이프라인에서 실패했는지(GEOCODE_SRC_SE). CVLCPT=민원 | PBLPKLT=공영주차장';


--
-- Name: COLUMN tb_geocode_skip.trgt_id; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_geocode_skip.trgt_id IS '대상아이디 - 민원·공영주차장 원천아이디 또는 단속 주소 SHA-256(64자리)';


--
-- Name: COLUMN tb_geocode_skip.fail_rsn; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_geocode_skip.fail_rsn IS '실패사유 - GEOCODE_FAILED(지오코딩 4단 전부 실패) | EMD_UNRESOLVED(행정동 공간매칭 실패)';


--
-- Name: COLUMN tb_geocode_skip.note; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_geocode_skip.note IS '스킵 단계 내역 - GEOCODE_FAILED: parcel/road/place/stripped 각 단계 판정, EMD_UNRESOLVED: 좌표 + 최근접 행정동/거리';


--
-- Name: COLUMN tb_geocode_skip.trgt_addr; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_geocode_skip.trgt_addr IS '대상주소 - 지오코딩 입력으로 쓴 주소 원문. 실패 원인 진단의 1차 단서';


--
-- Name: COLUMN tb_geocode_skip.checked_dt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_geocode_skip.checked_dt IS '확인일시 - 실패로 확정한 시각';


--
-- Name: tb_job_skip; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.tb_job_skip (
    job_se character varying(20) NOT NULL,
    trgt_id character varying(40) NOT NULL,
    trgt_de date NOT NULL,
    fail_rsn character varying(30) NOT NULL,
    note text,
    checked_dt timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE tb_job_skip; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON TABLE batch.tb_job_skip IS '잡스킵 - 배치 처리 중 저장 불가로 건너뛴 대상. 잡 공통(job_se 로 구분) (TSK-4069 D9)';


--
-- Name: COLUMN tb_job_skip.job_se; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_job_skip.job_se IS '잡구분 - 어느 잡의 스킵인지(JOB_SE). RISKNOTI=위험단계 알림 판정';


--
-- Name: COLUMN tb_job_skip.trgt_id; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_job_skip.trgt_id IS '대상아이디 - 건너뛴 대상의 키. RISKNOTI=읍면동코드(emd_cd)';


--
-- Name: COLUMN tb_job_skip.trgt_de; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_job_skip.trgt_de IS '대상일자 - 건너뛴 대상일. RISKNOTI=판정일. 같은 대상이 다른 날 다시 스킵될 수 있어 PK 에 포함한다';


--
-- Name: COLUMN tb_job_skip.fail_rsn; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_job_skip.fail_rsn IS '실패사유 - 예) RATE_OVERFLOW (증가율이 NUMERIC(7,2) 상한 초과)';


--
-- Name: COLUMN tb_job_skip.note; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_job_skip.note IS '비고 - 당일/전일 건수와 계산된 증가율 등 진단 정보';


--
-- Name: COLUMN tb_job_skip.checked_dt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_job_skip.checked_dt IS '확인일시 - 스킵으로 확정한 시각';


--
-- Name: tb_pblpklt_addr_anomaly; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.tb_pblpklt_addr_anomaly (
    id bigint NOT NULL,
    pklt_id character varying(20) NOT NULL,
    orgnl_addr text NOT NULL,
    anomaly_type character varying(30) NOT NULL,
    detected_dt timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE tb_pblpklt_addr_anomaly; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON TABLE batch.tb_pblpklt_addr_anomaly IS '공영주차장 주소 특이 패턴 관측 — 수집 단계, 값 변형 없음';


--
-- Name: COLUMN tb_pblpklt_addr_anomaly.pklt_id; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_pblpklt_addr_anomaly.pklt_id IS '원본 PK - parking.tb_pblpklt.origin_pk 와 같은 값(원천이 준 키). 🔴 parking.tb_pblpklt.pklt_id 가 아니다 — 그쪽은 DB 채번 일련번호다';


--
-- Name: COLUMN tb_pblpklt_addr_anomaly.orgnl_addr; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_pblpklt_addr_anomaly.orgnl_addr IS '원본주소 - 판정 입력 문자열(원본 그대로, 변환 없음)';


--
-- Name: COLUMN tb_pblpklt_addr_anomaly.anomaly_type; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_pblpklt_addr_anomaly.anomaly_type IS 'DIRECTION_SUFFIX | NON_ADDRESS_NAME | ETC';


--
-- Name: COLUMN tb_pblpklt_addr_anomaly.detected_dt; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON COLUMN batch.tb_pblpklt_addr_anomaly.detected_dt IS '탐지일시 - 특이 패턴으로 판정한 시각';


--
-- Name: tb_pblpklt_addr_anomaly_id_seq; Type: SEQUENCE; Schema: batch; Owner: -
--

CREATE SEQUENCE batch.tb_pblpklt_addr_anomaly_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tb_pblpklt_addr_anomaly_id_seq; Type: SEQUENCE OWNED BY; Schema: batch; Owner: -
--

ALTER SEQUENCE batch.tb_pblpklt_addr_anomaly_id_seq OWNED BY batch.tb_pblpklt_addr_anomaly.id;


--
-- Name: tb_pklt_utztn_mtch; Type: TABLE; Schema: batch; Owner: -
--

CREATE TABLE batch.tb_pklt_utztn_mtch (
    src_pklt_nm character varying(300) NOT NULL,
    src_pklt_addr character varying(200) NOT NULL,
    norm_addr character varying(200),
    norm_nm character varying(300),
    pklt_id bigint,
    mtch_stp character varying(10),
    cand_cnt integer,
    ambg_yn character(1),
    cpcty_dfrn_yn character(1),
    fail_rsn character varying(30),
    last_excn_dt timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE tb_pklt_utztn_mtch; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON TABLE batch.tb_pklt_utztn_mtch IS '주차장이용패턴 매칭 감사 - 원천(이름·주소) → 우리 pklt_id 해석 결과를 주차장 단위로 남긴다. 원천에 주차장 키가 없어 검산 수단이 없는 것을 대신한다';


--
-- Name: v_btch_excn_hstry; Type: VIEW; Schema: batch; Owner: -
--

CREATE VIEW batch.v_btch_excn_hstry AS
 SELECT tb_btch_excn_hstry.job_execution_id,
    tb_btch_excn_hstry.job_nm,
    tb_btch_excn_hstry.status,
    tb_btch_excn_hstry.run_mode,
    tb_btch_excn_hstry.target_range,
    tb_btch_excn_hstry.started_dt,
    (tb_btch_excn_hstry.duration_ms / 1000) AS duration_sec,
    tb_btch_excn_hstry.read_cnt,
    tb_btch_excn_hstry.write_cnt,
    (tb_btch_excn_hstry.skip_geocode_failed + tb_btch_excn_hstry.skip_emd_unresolved) AS skip_cnt,
    tb_btch_excn_hstry.filtered_transient,
    tb_btch_excn_hstry.geocode_api_lookups,
    tb_btch_excn_hstry.geocode_cache_hits,
    tb_btch_excn_hstry.unclsf_cnt,
    tb_btch_excn_hstry.emd_name_fallback
   FROM batch.tb_btch_excn_hstry
  ORDER BY tb_btch_excn_hstry.started_dt DESC;


--
-- Name: VIEW v_btch_excn_hstry; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON VIEW batch.v_btch_excn_hstry IS '배치실행이력 요약 - 최근 실행부터. 지표 추이(캐시 효율·미분류 급등·일시장애·행정동 이름 폴백)를 한 화면에서 본다';


--
-- Name: v_geocode_skip_diagnosis; Type: VIEW; Schema: batch; Owner: -
--

CREATE VIEW batch.v_geocode_skip_diagnosis AS
 SELECT tb_geocode_skip.src_se,
    tb_geocode_skip.fail_rsn,
    count(*) AS skip_cnt,
    min(tb_geocode_skip.checked_dt) AS first_checked_dt,
    max(tb_geocode_skip.checked_dt) AS last_checked_dt
   FROM batch.tb_geocode_skip
  GROUP BY tb_geocode_skip.src_se, tb_geocode_skip.fail_rsn
  ORDER BY tb_geocode_skip.src_se, tb_geocode_skip.fail_rsn;


--
-- Name: VIEW v_geocode_skip_diagnosis; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON VIEW batch.v_geocode_skip_diagnosis IS '지오코딩 스킵 요약 - 출처×사유별 건수와 기간. 상세는 batch.tb_geocode_skip 을 직접 조회';


--
-- Name: tb_admdst; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_admdst (
    seq bigint NOT NULL,
    emd_cd character varying(8) NOT NULL,
    ctpv_nm character varying(40) NOT NULL,
    sgg_nm character varying(40) NOT NULL,
    emd_nm character varying(40) NOT NULL,
    ctr_lat numeric(12,10),
    ctr_lot numeric(13,10),
    bndry_geom public.geometry(Geometry,4326)
);


--
-- Name: TABLE tb_admdst; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_admdst IS '행정구역 - 민원·단속·분석의 공간 기준이 되는 제주 읍·면·동(8자리 코드) 마스터';


--
-- Name: COLUMN tb_admdst.seq; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_admdst.seq IS '일련번호 - 행 식별용 내부 시퀀스 일련번호(SEQ_ADMIN_DISTRICT). 업무 키는 읍면동코드';


--
-- Name: COLUMN tb_admdst.emd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_admdst.emd_cd IS '읍면동코드 - 행정동을 식별하는 8자리 표준 행정동 코드';


--
-- Name: COLUMN tb_admdst.ctpv_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_admdst.ctpv_nm IS '시도명 - 행정구역이 속한 시·도명 (예: 제주특별자치도)';


--
-- Name: COLUMN tb_admdst.sgg_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_admdst.sgg_nm IS '시군구명 - 행정구역이 속한 시·군·구명 (예: 제주시/서귀포시)';


--
-- Name: COLUMN tb_admdst.emd_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_admdst.emd_nm IS '읍면동명 - 행정동의 읍·면·동 명칭';


--
-- Name: COLUMN tb_admdst.ctr_lat; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_admdst.ctr_lat IS '중심위도 - 행정동 경계의 중심점 위도 좌표';


--
-- Name: COLUMN tb_admdst.ctr_lot; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_admdst.ctr_lot IS '중심경도 - 행정동 경계의 중심점 경도 좌표';


--
-- Name: COLUMN tb_admdst.bndry_geom; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_admdst.bndry_geom IS '경계도형 - 행정동 경계를 나타내는 공간 도형(geometry) 데이터';


--
-- Name: tb_ai_conv_msg; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_ai_conv_msg (
    msg_id character varying(20) NOT NULL,
    sesn_id character varying(20) NOT NULL,
    spk_role_cd character varying(30) NOT NULL,
    msg_mtxt text NOT NULL,
    crt_dt timestamp without time zone NOT NULL
);


--
-- Name: TABLE tb_ai_conv_msg; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_ai_conv_msg IS 'AI대화메시지 - 세션 내 사용자/AI 메시지';


--
-- Name: COLUMN tb_ai_conv_msg.msg_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_conv_msg.msg_id IS '메시지아이디 - AI 대화 메시지를 식별하는 고유 ID(PK)';


--
-- Name: COLUMN tb_ai_conv_msg.sesn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_conv_msg.sesn_id IS '세션아이디 - 메시지가 속한 대화 세션 ID(AI대화세션 TB_AI_CONV_SESN 참조)';


--
-- Name: COLUMN tb_ai_conv_msg.spk_role_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_conv_msg.spk_role_cd IS '발화자코드 - 발화자를 구분하는 코드(MSG_ROLE)';


--
-- Name: COLUMN tb_ai_conv_msg.msg_mtxt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_conv_msg.msg_mtxt IS '메시지본문 - 메시지 본문 내용';


--
-- Name: COLUMN tb_ai_conv_msg.crt_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_conv_msg.crt_dt IS '생성일시 - 메시지 생성 일시';


--
-- Name: tb_ai_conv_sesn; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_ai_conv_sesn (
    sesn_id character varying(20) NOT NULL,
    user_id character varying(20) NOT NULL,
    conv_ttl character varying(200),
    bgng_dt timestamp without time zone NOT NULL
);


--
-- Name: TABLE tb_ai_conv_sesn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_ai_conv_sesn IS 'AI대화세션 - sLLM+RAG 기반 대화형 행정 지원 세션';


--
-- Name: COLUMN tb_ai_conv_sesn.sesn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_conv_sesn.sesn_id IS '세션아이디 - AI 대화 세션을 식별하는 고유 ID(PK)';


--
-- Name: COLUMN tb_ai_conv_sesn.user_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_conv_sesn.user_id IS '사용자ID - 대화 세션 사용자 ID(외부 회원 시스템 참조, FK 미설정)';


--
-- Name: COLUMN tb_ai_conv_sesn.conv_ttl; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_conv_sesn.conv_ttl IS '대화제목 - 대화 제목(최근 대화 목록 표시)';


--
-- Name: COLUMN tb_ai_conv_sesn.bgng_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_conv_sesn.bgng_dt IS '시작일시 - 대화 세션 시작 일시';


--
-- Name: tb_ai_dgstfn_srvy; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_ai_dgstfn_srvy (
    srvy_sn bigint NOT NULL,
    user_id character varying(20) NOT NULL,
    sesn_id character varying(512),
    dgstfn_scr numeric(7,0) NOT NULL,
    opnn_cn character varying(4000),
    reg_dt timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_tb_ai_dgstfn_srvy_scr CHECK (((dgstfn_scr >= (1)::numeric) AND (dgstfn_scr <= (10)::numeric)))
);


--
-- Name: TABLE tb_ai_dgstfn_srvy; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_ai_dgstfn_srvy IS 'AI만족도설문 - AI 어시스턴트 만족도 응답(1행 = 1회 제출, TSK-4369). 대화 원문은 저장하지 않는다';


--
-- Name: COLUMN tb_ai_dgstfn_srvy.srvy_sn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_dgstfn_srvy.srvy_sn IS '설문일련번호 - 단어 조합 설문 SRVY + 일련번호 SN (DB 채번)';


--
-- Name: COLUMN tb_ai_dgstfn_srvy.user_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_dgstfn_srvy.user_id IS '사용자ID - 응답한 로그인 사용자(TB_USER.user_id). 표준용어 USER_ID 의 도메인은 일련번호N19 이나 TB_USER.user_id 가 VARCHAR(20) 문자열이라 그 타입을 따른다(103 과 같은 예외). FK 미설정';


--
-- Name: COLUMN tb_ai_dgstfn_srvy.sesn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_dgstfn_srvy.sesn_id IS '세션아이디 - 응답 당시 업스트림 Agent 대화 세션 ID(참고용, 새 대화 화면이면 NULL). 도메인 예외: 업스트림 ID 가 문자열이라 VARCHAR(512)(103 TB_AI_RPTP_CN.sesn_id 와 같다)';


--
-- Name: COLUMN tb_ai_dgstfn_srvy.dgstfn_scr; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_dgstfn_srvy.dgstfn_scr IS '만족도점수 - 1(매우 불만족)~10(매우 만족) 정수. 표준용어 만족도점수 DGSTFN_SCR(수N7)';


--
-- Name: COLUMN tb_ai_dgstfn_srvy.opnn_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_dgstfn_srvy.opnn_cn IS '의견내용 - 선택 입력 자유 의견(빈 값은 NULL, 서버 상한 1000자). 표준용어 의견내용 OPNN_CN(내용V4000)';


--
-- Name: COLUMN tb_ai_dgstfn_srvy.reg_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_dgstfn_srvy.reg_dt IS '등록일시 - 설문 제출 일시';


--
-- Name: tb_ai_dgstfn_srvy_srvy_sn_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_ai_dgstfn_srvy ALTER COLUMN srvy_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_ai_dgstfn_srvy_srvy_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_ai_rptp_cn; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_ai_rptp_cn (
    rptp_id bigint NOT NULL,
    user_id character varying(20) NOT NULL,
    sesn_id character varying(512) NOT NULL,
    msg_id character varying(64) NOT NULL,
    rptp_seq numeric(10,0) NOT NULL,
    rptp_cn text NOT NULL,
    reg_dt timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE tb_ai_rptp_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_ai_rptp_cn IS 'AI보고서내용 - AI 어시스턴트 답변에서 사용자가 등록한 HTML 보고서 본문(TB_RPTP 와 1:1, TSK-4293 D7). 대화 원문이 아니라 등록한 보고서 1건만 저장한다. 멱등 키 (user_id, sesn_id, msg_id, rptp_seq) UNIQUE(D12)';


--
-- Name: COLUMN tb_ai_rptp_cn.rptp_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_rptp_cn.rptp_id IS '보고서ID - 보고서 TB_RPTP 참조(PK 겸 FK, 삭제 시 CASCADE). 표준용어 보고서ID RPTP_ID(일련번호N19) — 부모와 같은 BIGINT';


--
-- Name: COLUMN tb_ai_rptp_cn.user_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_rptp_cn.user_id IS '사용자ID - 보고서를 등록한 로그인 사용자(TB_USER.user_id). 멱등 키 구성(D12 — 공유 세션 current 대비). 표준용어 사용자ID USER_ID 의 도메인은 일련번호N19 이나 TB_USER.user_id 가 VARCHAR(20) 문자열이라 그 타입을 따른다. FK 미설정 — 사용자 비활성·삭제 정책이 없다(TB_NOTI_RCVR.user_id 선례)';


--
-- Name: COLUMN tb_ai_rptp_cn.sesn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_rptp_cn.sesn_id IS '세션아이디 - 보고서가 나온 업스트림 Agent 대화 세션 ID(portal-{UUID} 43자 · 기본 세션 current). 도메인 예외: 표준용어 세션아이디 SESN_ID 는 일련번호N19(숫자)이나 업스트림 ID 는 문자열이고, 기존 VARCHAR(20)(TB_AI_CONV_SESN 등)으로는 portal-{UUID} 가 잘린다. 길이 512 는 백엔드 AgentApiClient.SESSION_ID_MAX_LEN 과 같다';


--
-- Name: COLUMN tb_ai_rptp_cn.msg_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_rptp_cn.msg_id IS '메시지아이디 - 보고서가 들어 있던 어시스턴트 메시지의 업스트림 ID(전송 응답 id = 상세 completion_id, chatcmpl- + 12 hex 실측 21자, TSK-4293 D11). 도메인 예외: 표준용어 메시지아이디 MSG_ID 는 일련번호N19(숫자)이나 업스트림 ID 가 문자열이라 VARCHAR(64)(여유 포함)';


--
-- Name: COLUMN tb_ai_rptp_cn.rptp_seq; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_rptp_cn.rptp_seq IS '보고서순서 - 한 메시지 안에서 보고서 영역의 순번(rptIdx, 0부터). 단어 조합 보고서 RPTP + 순서 SEQ. 도메인 순서N10(NUMERIC(10)) — 101-DB-STANDARD-COLUMN-TYPES 가 sort_seq 를 NUMERIC(10) 으로 맞춘 관례를 따른다';


--
-- Name: COLUMN tb_ai_rptp_cn.rptp_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_rptp_cn.rptp_cn IS '보고서내용 - 새니타이즈를 거친 보고서 HTML 본문(영역 1건 상한 1MB, TSK-4293 D9). 도메인 예외: 표준용어 보고서내용 RPTP_CN 은 내용V4000 이나 1MB 상한을 담을 수 없어 도메인 내용TX(TEXT)를 쓴다';


--
-- Name: COLUMN tb_ai_rptp_cn.reg_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_ai_rptp_cn.reg_dt IS '등록일시 - 등록 일시';


--
-- Name: tb_anls_excn; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_anls_excn (
    excn_id bigint NOT NULL,
    anls_type_cd character varying(30) NOT NULL,
    excutr_id character varying(20) NOT NULL,
    emd_cd character varying(8),
    anls_bgng_ymd character(8),
    anls_end_ymd character(8),
    anls_param_json jsonb,
    excn_stts_cd character varying(30) NOT NULL,
    excn_dt timestamp without time zone NOT NULL,
    sgg_nm character varying(40)
);


--
-- Name: TABLE tb_anls_excn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_anls_excn IS '실행이력 - 보고서를 낳는 실행 이벤트 공통 관리(분석·시뮬레이션 5종 + 민원현황 + 대시보드). TSK-4026 D7 로 범위 확장';


--
-- Name: COLUMN tb_anls_excn.excn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_excn.excn_id IS '실행아이디 - 실행을 식별하는 고유 번호(PK, BIGINT 자동 증가. TSK-4026 D6)';


--
-- Name: COLUMN tb_anls_excn.anls_type_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_excn.anls_type_cd IS '실행유형코드 - 실행 유형을 구분하는 코드(ANLS_TYPE_CD, 7종)';


--
-- Name: COLUMN tb_anls_excn.excutr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_excn.excutr_id IS '실행자ID - 분석을 실행한 사용자 ID(외부 회원 시스템 참조, FK 미설정)';


--
-- Name: COLUMN tb_anls_excn.emd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_excn.emd_cd IS '읍면동코드 - 분석 대상 행정동 코드(행정구역 TB_ADMDST 참조)';


--
-- Name: COLUMN tb_anls_excn.anls_bgng_ymd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_excn.anls_bgng_ymd IS '분석시작일자 - 분석 대상 기간의 시작일';


--
-- Name: COLUMN tb_anls_excn.anls_end_ymd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_excn.anls_end_ymd IS '분석종료일자 - 분석 대상 기간의 종료일';


--
-- Name: COLUMN tb_anls_excn.anls_param_json; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_excn.anls_param_json IS '분석설정값 - 격자크기·임계값·가중치 등 분석 설정값(JSON)';


--
-- Name: COLUMN tb_anls_excn.excn_stts_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_excn.excn_stts_cd IS '실행상태코드 - 분석 실행 상태 코드 (예: 대기/실행중/완료/실패)';


--
-- Name: COLUMN tb_anls_excn.excn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_excn.excn_dt IS '실행일시 - 분석이 실행된 일시';


--
-- Name: COLUMN tb_anls_excn.sgg_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_excn.sgg_nm IS '시군구명 - 실행 대상 지역. emd_cd 가 NULL 이면 시군구 전체, 둘 다 NULL 이면 전체 지역(TSK-4026 D15)';


--
-- Name: tb_anls_excn_excn_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_anls_excn ALTER COLUMN excn_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_anls_excn_excn_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_anls_vrbl_hstry; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_anls_vrbl_hstry (
    chg_hstry_sn bigint NOT NULL,
    anls_type_cd character varying(30) NOT NULL,
    chg_artcl_nm character varying(100) NOT NULL,
    chg_cn character varying(1000) NOT NULL,
    chg_rsn character varying(300),
    bfr_vrbl_json jsonb NOT NULL,
    aftr_vrbl_json jsonb NOT NULL,
    chnrg_id character varying(20) NOT NULL,
    chnrg_nm character varying(100) NOT NULL,
    chg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE tb_anls_vrbl_hstry; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_anls_vrbl_hstry IS '분석변수변경이력 - TB_ANLS_VRBL_STNG 의 변경 이력. 저장 1회 = 1행';


--
-- Name: COLUMN tb_anls_vrbl_hstry.chg_hstry_sn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_hstry.chg_hstry_sn IS '변경이력일련번호 - PK(DB identity 가 매긴다)';


--
-- Name: COLUMN tb_anls_vrbl_hstry.anls_type_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_hstry.anls_type_cd IS '분석유형코드 - 변경 대상 분석유형(ANLS_TYPE_CD)';


--
-- Name: COLUMN tb_anls_vrbl_hstry.chg_artcl_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_hstry.chg_artcl_nm IS '변경항목명 - 화면 표시용 항목 이름(기록 시점의 분석유형명)';


--
-- Name: COLUMN tb_anls_vrbl_hstry.chg_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_hstry.chg_cn IS '변경내용 - 화면 표시용 문장. 기록 시점에 조립해 굳힌다(예: 민원빈도 60 → 55, 단속건수 40 → 45)';


--
-- Name: COLUMN tb_anls_vrbl_hstry.chg_rsn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_hstry.chg_rsn IS '변경사유 - 사용자가 입력한 사유(선택)';


--
-- Name: COLUMN tb_anls_vrbl_hstry.bfr_vrbl_json; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_hstry.bfr_vrbl_json IS '이전변수JSON - 변경 전 스냅샷 [{vrblCd, vrblNm, stngVl}, ...]. 이 마이그레이션 이전 행은 옛 키({idctCd, idctNm, wgvlRt})다 - 본문은 변환하지 않는다(D6)';


--
-- Name: COLUMN tb_anls_vrbl_hstry.aftr_vrbl_json; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_hstry.aftr_vrbl_json IS '이후변수JSON - 변경 후 스냅샷 (되돌리기·감사용). 옛 키 혼재는 bfr_vrbl_json 과 같다';


--
-- Name: COLUMN tb_anls_vrbl_hstry.chnrg_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_hstry.chnrg_id IS '변경자아이디 - 로그인 도입 전 고정값 system';


--
-- Name: COLUMN tb_anls_vrbl_hstry.chnrg_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_hstry.chnrg_nm IS '변경자명 - 화면 표시용. 로그인 도입 전 고정값 시스템';


--
-- Name: COLUMN tb_anls_vrbl_hstry.chg_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_hstry.chg_dt IS '변경일시 - 저장이 커밋된 시각';


--
-- Name: tb_anls_idct_wgvl_hstry_chg_hstry_sn_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_anls_vrbl_hstry ALTER COLUMN chg_hstry_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_anls_idct_wgvl_hstry_chg_hstry_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_anls_rslt; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_anls_rslt (
    rslt_id bigint NOT NULL,
    excn_id bigint NOT NULL,
    rslt_json jsonb NOT NULL
);


--
-- Name: TABLE tb_anls_rslt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_anls_rslt IS '분석결과 - 실행 1건의 결과 전체를 JSONB 한 행으로 담는다 (정책시뮬레이션 제외)';


--
-- Name: COLUMN tb_anls_rslt.rslt_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_rslt.rslt_id IS '결과아이디 - 자동증가';


--
-- Name: COLUMN tb_anls_rslt.excn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_rslt.excn_id IS '실행아이디 - TB_ANLS_EXCN 참조. 실행 1건당 결과 1행(UNIQUE)';


--
-- Name: COLUMN tb_anls_rslt.rslt_json; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_rslt.rslt_json IS '결과제이슨 - 분석 종류별 결과 전체. 종류는 TB_ANLS_EXCN.anls_type_cd 가 정한다';


--
-- Name: tb_anls_rslt_rslt_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_anls_rslt ALTER COLUMN rslt_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_anls_rslt_rslt_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_anls_vrbl_stng; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_anls_vrbl_stng (
    anls_type_cd character varying(30) NOT NULL,
    vrbl_cd character varying(30) NOT NULL,
    vrbl_nm character varying(100) NOT NULL,
    vrbl_expln character varying(300),
    stng_vl numeric(12,4) NOT NULL,
    bsc_stng_vl numeric(12,4) NOT NULL,
    sort_seq numeric(10,0) NOT NULL,
    use_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    mdfr_id character varying(20),
    mdfcn_dt timestamp without time zone,
    vrbl_se_cd character varying(10) NOT NULL,
    min_vl numeric(12,4) NOT NULL,
    max_vl numeric(12,4) NOT NULL,
    unit_nm character varying(20),
    anls_trgt_cd character varying(30),
    expsr_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    CONSTRAINT ck_tb_anls_vrbl_stng_bsc CHECK (((bsc_stng_vl >= min_vl) AND (bsc_stng_vl <= max_vl))),
    CONSTRAINT ck_tb_anls_vrbl_stng_expsr_yn CHECK ((expsr_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT ck_tb_anls_vrbl_stng_rng CHECK ((min_vl <= max_vl)),
    CONSTRAINT ck_tb_anls_vrbl_stng_se CHECK (((vrbl_se_cd)::text = ANY ((ARRAY['WGVL'::character varying, 'CFC'::character varying])::text[]))),
    CONSTRAINT ck_tb_anls_vrbl_stng_use_yn CHECK ((use_yn = ANY (ARRAY['Y'::bpchar, 'N'::bpchar]))),
    CONSTRAINT ck_tb_anls_vrbl_stng_vl CHECK (((stng_vl >= min_vl) AND (stng_vl <= max_vl)))
);


--
-- Name: TABLE tb_anls_vrbl_stng; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_anls_vrbl_stng IS '분석변수설정 - 분석유형별 가중치·계수. 산식이 읽는 값의 단일 출처';


--
-- Name: COLUMN tb_anls_vrbl_stng.anls_type_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.anls_type_cd IS '분석유형코드 - ANLS_TYPE_CD 공통코드(HOTSPOT/RECOMMEND/INVEST/LIFEZONE)';


--
-- Name: COLUMN tb_anls_vrbl_stng.vrbl_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.vrbl_cd IS '변수코드 - 분석유형 안에서 유일';


--
-- Name: COLUMN tb_anls_vrbl_stng.vrbl_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.vrbl_nm IS '변수명 - 화면에 표시하는 변수 이름';


--
-- Name: COLUMN tb_anls_vrbl_stng.vrbl_expln; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.vrbl_expln IS '변수설명 - 화면 보조설명(원천·산식 안내)';


--
-- Name: COLUMN tb_anls_vrbl_stng.stng_vl; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.stng_vl IS '설정값 - 가중치는 %, 계수는 자체 단위';


--
-- Name: COLUMN tb_anls_vrbl_stng.bsc_stng_vl; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.bsc_stng_vl IS '기본설정값 - 기본값으로 초기화의 원천';


--
-- Name: COLUMN tb_anls_vrbl_stng.sort_seq; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.sort_seq IS '정렬순서 - 화면 표시 순서';


--
-- Name: COLUMN tb_anls_vrbl_stng.use_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.use_yn IS '사용여부 - N 이면 점수 산출에서 제외하고 나머지 지표로 재정규화한다(원천 데이터 미도입 지표)';


--
-- Name: COLUMN tb_anls_vrbl_stng.mdfr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.mdfr_id IS '수정자아이디 - 가중치를 마지막으로 저장한 주체(로그인 도입 전 고정값 system)';


--
-- Name: COLUMN tb_anls_vrbl_stng.mdfcn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.mdfcn_dt IS '수정일시 - 가중치 최종 저장 일시';


--
-- Name: COLUMN tb_anls_vrbl_stng.vrbl_se_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.vrbl_se_cd IS '변수구분코드 - WGVL(가중치) | CFC(계수). 🔴 합계 100 검증은 WGVL 에만 적용';


--
-- Name: COLUMN tb_anls_vrbl_stng.min_vl; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.min_vl IS '최소값 - 허용 하한(CHECK 로 강제)';


--
-- Name: COLUMN tb_anls_vrbl_stng.max_vl; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.max_vl IS '최대값 - 허용 상한(CHECK 로 강제)';


--
-- Name: COLUMN tb_anls_vrbl_stng.unit_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.unit_nm IS '단위명 - 화면 표시용(% · 면/건)';


--
-- Name: COLUMN tb_anls_vrbl_stng.anls_trgt_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.anls_trgt_cd IS '분석내용코드 - 대메뉴(anls_type_cd) 안의 하위 유형. NULL 이면 대메뉴 공통. 가중치(WGVL) 합계 100 은 (anls_type_cd, anls_trgt_cd) 묶음마다 본다';


--
-- Name: COLUMN tb_anls_vrbl_stng.expsr_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_anls_vrbl_stng.expsr_yn IS '노출여부 - N 이면 설정·분석 계수 조정·보고서 화면에 보이지 않는다. 🔴 계산 스위치가 아니다(그건 use_yn) — 계산은 stng_vl 을 그대로 쓴다';


--
-- Name: tb_authrt; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_authrt (
    authrt_cd character varying(30) NOT NULL,
    authrt_nm character varying(100) NOT NULL,
    authrt_expln character varying(4000),
    use_yn character(1) NOT NULL,
    rgtr_id character varying(20) NOT NULL,
    reg_dt timestamp without time zone NOT NULL,
    mdfr_id character varying(20),
    mdfcn_dt timestamp without time zone,
    authrt_type_cd character varying(30)
);


--
-- Name: TABLE tb_authrt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_authrt IS '권한 - 포털 접근 권한(롤) 정의. 시스템관리자·데이터관리자·기관·일반·게스트 등 롤 마스터.';


--
-- Name: COLUMN tb_authrt.authrt_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt.authrt_cd IS '권한코드 - 권한(롤)을 식별하는 코드. 예: ROLE_ADMIN';


--
-- Name: COLUMN tb_authrt.authrt_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt.authrt_nm IS '권한명 - 화면에 표시되는 권한 명칭(유일값)';


--
-- Name: COLUMN tb_authrt.authrt_expln; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt.authrt_expln IS '권한설명 - 권한에 대한 설명';


--
-- Name: COLUMN tb_authrt.use_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt.use_yn IS '사용여부 - 사용 여부(Y/N)';


--
-- Name: COLUMN tb_authrt.rgtr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt.rgtr_id IS '등록자ID - 등록자 회원 아이디';


--
-- Name: COLUMN tb_authrt.reg_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt.reg_dt IS '등록일시 - 등록 일시';


--
-- Name: COLUMN tb_authrt.mdfr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt.mdfr_id IS '수정자ID - 수정자 회원 아이디';


--
-- Name: COLUMN tb_authrt.mdfcn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt.mdfcn_dt IS '수정일시 - 수정 일시';


--
-- Name: COLUMN tb_authrt.authrt_type_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt.authrt_type_cd IS '권한유형코드 - 권한 유형(SUPER: 전 메뉴 접근이 보장되며 화면에서 회수할 수 없다). NULL 이면 일반';


--
-- Name: tb_authrt_menu; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_authrt_menu (
    authrt_cd character varying(30) NOT NULL,
    menu_id character varying(20) NOT NULL,
    rgtr_id character varying(20) NOT NULL,
    reg_dt timestamp without time zone NOT NULL,
    mdfr_id character varying(20),
    mdfcn_dt timestamp without time zone
);


--
-- Name: TABLE tb_authrt_menu; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_authrt_menu IS '권한메뉴매핑 - 권한×메뉴 접근 매핑. 행이 존재하면 해당 권한이 그 메뉴에 접근 허용(복합키)';


--
-- Name: COLUMN tb_authrt_menu.authrt_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt_menu.authrt_cd IS '권한코드 - 권한 코드(권한 TB_AUTHRT 참조)';


--
-- Name: COLUMN tb_authrt_menu.menu_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt_menu.menu_id IS '메뉴아이디 - 메뉴 아이디(메뉴 TB_MENU 참조). 행 존재=접근 허용';


--
-- Name: COLUMN tb_authrt_menu.rgtr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt_menu.rgtr_id IS '등록자ID - 등록자 회원 아이디';


--
-- Name: COLUMN tb_authrt_menu.reg_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt_menu.reg_dt IS '등록일시 - 등록 일시';


--
-- Name: COLUMN tb_authrt_menu.mdfr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt_menu.mdfr_id IS '수정자ID - 수정자 회원 아이디';


--
-- Name: COLUMN tb_authrt_menu.mdfcn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_authrt_menu.mdfcn_dt IS '수정일시 - 수정 일시';


--
-- Name: tb_cd_group; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_cd_group (
    group_cd character varying(30) NOT NULL,
    group_nm character varying(100) NOT NULL,
    expln character varying(300),
    use_yn character(1) NOT NULL
);


--
-- Name: TABLE tb_cd_group; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_cd_group IS '코드그룹 - 시스템 전반 분류 코드 그룹';


--
-- Name: COLUMN tb_cd_group.group_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cd_group.group_cd IS '그룹코드 - 코드 그룹을 식별하는 그룹 코드(PK)';


--
-- Name: COLUMN tb_cd_group.group_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cd_group.group_nm IS '그룹명 - 코드 그룹 명칭';


--
-- Name: COLUMN tb_cd_group.expln; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cd_group.expln IS '설명 - 코드 그룹에 대한 설명';


--
-- Name: COLUMN tb_cd_group.use_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cd_group.use_yn IS '사용여부 - 코드 그룹 사용 여부';


--
-- Name: tb_com_cd; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_com_cd (
    group_cd character varying(30) NOT NULL,
    cd_vl character varying(30) NOT NULL,
    cd_nm character varying(100) NOT NULL,
    sort_seq numeric(10,0),
    use_yn character(1) NOT NULL,
    addl_atrb1 character varying(100)
);


--
-- Name: TABLE tb_com_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_com_cd IS '공통코드 - 코드그룹별 코드 값';


--
-- Name: COLUMN tb_com_cd.group_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_com_cd.group_cd IS '그룹코드 - 공통코드가 속한 그룹 코드(코드그룹 TB_CD_GROUP 참조)';


--
-- Name: COLUMN tb_com_cd.cd_vl; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_com_cd.cd_vl IS '코드값 - 공통코드 값';


--
-- Name: COLUMN tb_com_cd.cd_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_com_cd.cd_nm IS '코드명 - 공통코드 명칭';


--
-- Name: COLUMN tb_com_cd.sort_seq; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_com_cd.sort_seq IS '정렬순서 - 코드 정렬 순서';


--
-- Name: COLUMN tb_com_cd.use_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_com_cd.use_yn IS '사용여부 - 공통코드 사용 여부';


--
-- Name: COLUMN tb_com_cd.addl_atrb1; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_com_cd.addl_atrb1 IS '부가속성1 - 그룹마다 의미가 다르다. ANLS_TYPE_CD 에서는 용도 구분(ANLS=분석 화면 전용 / RPTP=보고서 전용 / BOTH=양쪽)';


--
-- Name: tb_crdn_dsctn; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_crdn_dsctn (
    crdn_id bigint NOT NULL,
    crdn_dt timestamp without time zone,
    ffnlg character varying,
    crdn_mthd character varying,
    carmdl character varying,
    orgnl_cn character varying,
    crdn_plc character varying,
    crdn_addr character varying,
    src_hash character varying(64),
    load_dt timestamp without time zone,
    emd_cd character varying(8),
    cvlcpt_id character varying(50),
    lat numeric(12,10),
    lot numeric(13,10),
    crdn_type_cd character varying(30),
    geom public.geometry(Point,4326) GENERATED ALWAYS AS (public.st_setsrid(public.st_makepoint((lot)::double precision, (lat)::double precision), 4326)) STORED,
    origin_pk text,
    orgnl_load_dt timestamp without time zone
);


--
-- Name: TABLE tb_crdn_dsctn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_crdn_dsctn IS '단속내역 - 불법주차 단속 실적. 집중구역·단속집중지역 산정 입력값. DHubManager 수집(crackdownFetchJob)';


--
-- Name: COLUMN tb_crdn_dsctn.crdn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.crdn_id IS '단속아이디 - 단속 건별 고유 식별자(PK, BIGINT 자동 증가). 원천에 고유키가 없어 대리키를 쓴다';


--
-- Name: COLUMN tb_crdn_dsctn.crdn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.crdn_dt IS '단속일시 - 단속이 이루어진 일시';


--
-- Name: COLUMN tb_crdn_dsctn.ffnlg; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.ffnlg IS '과태료 - 부과된 과태료(원본 문자열 보존). 0 인 행이 있다 — 부과 없음과 미기재가 구분되지 않는다';


--
-- Name: COLUMN tb_crdn_dsctn.crdn_mthd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.crdn_mthd IS '단속방법 - 단속을 수행한 방법(고정형CCTV / 주행형CCTV / PDA / 민원(공익제보)). 원천 원문이며 코드가 아니다. ⚠️ 조치 유형(crdn_type_cd)과 다른 개념이다';


--
-- Name: COLUMN tb_crdn_dsctn.carmdl; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.carmdl IS '차종 - 단속 대상 차량의 종류(승용 / 화물(4톤이하) 등)';


--
-- Name: COLUMN tb_crdn_dsctn.orgnl_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.orgnl_cn IS '원본내용 - 원천 응답 원문. 파싱이 어긋났을 때 되짚는 근거';


--
-- Name: COLUMN tb_crdn_dsctn.crdn_plc; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.crdn_plc IS '단속장소 - 위반 지점까지 포함한 장소 표기(예: 외도일동 751-5부근 횡단보도). crdn_addr 를 포함하는 비율이 83%라 주소로 대체되지 않는다';


--
-- Name: COLUMN tb_crdn_dsctn.crdn_addr; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.crdn_addr IS '단속주소 - 단속 지점의 주소(예: 외도일동 751-5). 지오코딩 입력으로 쓴 값';


--
-- Name: COLUMN tb_crdn_dsctn.src_hash; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.src_hash IS '원천해시 - 정규화한 원천 7필드의 SHA-256. 기존 중복 사본만 NULL';


--
-- Name: COLUMN tb_crdn_dsctn.load_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.load_dt IS '적재일시 - 원천 load_dt. MAX 값 이상부터 월별 증분 수집. 결측은 NULL 보존';


--
-- Name: COLUMN tb_crdn_dsctn.emd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.emd_cd IS '읍면동코드 - 단속 발생 행정동(행정구역 TB_ADMDST 참조). 원천이 주지 않아 좌표 공간매칭(ST_Contains)으로 채운다. 경계 밖 좌표는 NULL';


--
-- Name: COLUMN tb_crdn_dsctn.cvlcpt_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.cvlcpt_id IS '민원아이디 - 단속과 연계된 민원(민원 TB_CVLCPT 참조). ⚠️ 연계 로직이 아직 없어 현재 전 행 NULL 이다';


--
-- Name: COLUMN tb_crdn_dsctn.lat; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.lat IS '위도 - 단속 위치의 위도 좌표. geom 의 원본';


--
-- Name: COLUMN tb_crdn_dsctn.lot; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.lot IS '경도 - 단속 위치의 경도 좌표. geom 의 원본';


--
-- Name: COLUMN tb_crdn_dsctn.crdn_type_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.crdn_type_cd IS '단속유형코드 - 조치 유형을 구분하는 코드(CRDN_TYPE_CD: STICKER 스티커발부 / TOW 견인조치 / FINE 과태료부과 / WARN 경고계도). ⚠️ 원천이 조치 유형을 주지 않아 현재 전 행 NULL 이다 — 유형별 집계 시 NULL 을 제외할 것';


--
-- Name: COLUMN tb_crdn_dsctn.geom; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.geom IS '위치지오메트리 - lat/lot 으로부터 자동 생성되는 공간 컬럼(EPSG:4326, GiST). 직접 쓰지 않는다 — 좌표를 고치면 따라온다';


--
-- Name: COLUMN tb_crdn_dsctn.origin_pk; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.origin_pk IS '원천아이디 - 원천 crdn_id. NULL/중복 허용, 내부 PK와 무관';


--
-- Name: COLUMN tb_crdn_dsctn.orgnl_load_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_crdn_dsctn.orgnl_load_dt IS '원본적재일시 - 전환 전 로컬 적재시각, 워터마크에 사용하지 않음';


--
-- Name: tb_crdn_dsctn_crdn_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

CREATE SEQUENCE parking.tb_crdn_dsctn_crdn_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tb_crdn_dsctn_crdn_id_seq; Type: SEQUENCE OWNED BY; Schema: parking; Owner: -
--

ALTER SEQUENCE parking.tb_crdn_dsctn_crdn_id_seq OWNED BY parking.tb_crdn_dsctn.crdn_id;


--
-- Name: tb_cvlcpt; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_cvlcpt (
    cvlcpt_id character varying(50) NOT NULL,
    cvlcpt_type_cd character varying(30) NOT NULL,
    emd_cd character varying(8),
    lat numeric(12,10),
    lot numeric(13,10),
    ocrn_dt timestamp without time zone NOT NULL,
    risk_lvl_cd character varying(30),
    src_cd character varying(30),
    cvlcpt_cn text,
    load_dt timestamp without time zone,
    geom public.geometry(Point,4326) GENERATED ALWAYS AS (public.st_setsrid(public.st_makepoint((lot)::double precision, (lat)::double precision), 4326)) STORED,
    aprs_cd character varying(30),
    dclr_ymd character(10),
    ttl character varying(256),
    dclr_ocrn_rgn character varying(100),
    prgrs_stts character varying(50),
    ans_prcs_stts character varying(10),
    ans_ymd character(10),
    aplcnt_se character varying(10),
    dclr_fld character varying(10),
    rwrd_yn character varying(1),
    prcs_inst_cnt integer,
    prcs_inst_nm character varying(200),
    prcs_inst_pic_nm character varying(20),
    dclr_grp_nm character varying(50),
    mbr_grp_nm character varying(50),
    dclr_type character varying(30),
    prk_cvlcpt_type character varying(20),
    src_path character varying(10),
    origin_pk text,
    src_hash character varying(64),
    orgnl_load_dt timestamp without time zone
);


--
-- Name: TABLE tb_cvlcpt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_cvlcpt IS '민원 - 주차·안전 관련 민원 단위 데이터(시각화·분석 원천)';


--
-- Name: COLUMN tb_cvlcpt.cvlcpt_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.cvlcpt_id IS '민원아이디 - 내부 시퀀스 PK. 전환 전 기존 PK/FK 값은 보존';


--
-- Name: COLUMN tb_cvlcpt.cvlcpt_type_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.cvlcpt_type_cd IS '민원유형코드 - 민원 유형을 구분하는 공통코드(CVLCPT_TYPE_CD)';


--
-- Name: COLUMN tb_cvlcpt.emd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.emd_cd IS '읍면동코드 - 민원 발생 행정동 코드(행정구역 TB_ADMDST 참조). 주차민원으로 분류되지 않은 행(cvlcpt_type_cd=99)은 지오코딩을 생략하므로 NULL 이다 - 읍면동 단위 집계 시 NULL 행을 제외할 것';


--
-- Name: COLUMN tb_cvlcpt.lat; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.lat IS '위도 - 민원 발생 위치의 위도 좌표(히트맵 표시용)';


--
-- Name: COLUMN tb_cvlcpt.lot; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.lot IS '경도 - 민원 발생 위치의 경도 좌표(히트맵 표시용)';


--
-- Name: COLUMN tb_cvlcpt.ocrn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.ocrn_dt IS '발생일시 - 민원이 발생한 일시(기간 필터 기준)';


--
-- Name: COLUMN tb_cvlcpt.risk_lvl_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.risk_lvl_cd IS '위험단계코드 - 민원 위험 단계를 구분하는 코드(RISK_LVL_CD)';


--
-- Name: COLUMN tb_cvlcpt.src_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.src_cd IS '수집출처코드 - 민원 수집 경로를 구분하는 코드 (예: 모바일앱/포털/API/콜센터/기타)';


--
-- Name: COLUMN tb_cvlcpt.cvlcpt_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.cvlcpt_cn IS '민원내용 - 민원 상세 내용';


--
-- Name: COLUMN tb_cvlcpt.load_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.load_dt IS '적재일시 - 원천 load_dt. MAX 값 이상부터 월별 증분 수집. 결측은 NULL 보존';


--
-- Name: COLUMN tb_cvlcpt.geom; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.geom IS '위치지오메트리 - lat/lot 로부터 자동 생성되는 공간 컬럼(EPSG:4326, 클러스터링·공간질의용). lat/lot 이 NULL 인 행(cvlcpt_type_cd=99)은 geom 도 NULL 이다';


--
-- Name: COLUMN tb_cvlcpt.aprs_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.aprs_cd IS '감정구분코드 - 민원 내용(cvlcpt_cn)의 감정 분석 결과 코드(APRS_CD). 원천이 이미 분석한 값을 받는다. 분석 전 행은 NULL - 감정별 집계 시 NULL 을 제외할 것';


--
-- Name: COLUMN tb_cvlcpt.dclr_ymd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.dclr_ymd IS '신고일자 - 민원이 접수된 날짜. 발생일시(ocrn_dt)와 다르다 — 신고는 발생 이후에 이뤄진다';


--
-- Name: COLUMN tb_cvlcpt.ttl; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.ttl IS '제목 - 신고 제목(원천 원문)';


--
-- Name: COLUMN tb_cvlcpt.dclr_ocrn_rgn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.dclr_ocrn_rgn IS '신고발생지역 - 지오코딩 입력으로 쓴 주소 원문. 좌표가 틀렸을 때 원인을 되짚는 유일한 단서';


--
-- Name: COLUMN tb_cvlcpt.prgrs_stts; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.prgrs_stts IS '진행상황 - 원천의 신고 진행 상태 원문';


--
-- Name: COLUMN tb_cvlcpt.ans_prcs_stts; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.ans_prcs_stts IS '답변처리상황 - 원천의 답변 처리 상태 원문';


--
-- Name: COLUMN tb_cvlcpt.ans_ymd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.ans_ymd IS '답변일자 - 원천이 답변한 날짜(ISO 정규화). 적재일시(load_dt)의 산출 근거';


--
-- Name: COLUMN tb_cvlcpt.aplcnt_se; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.aplcnt_se IS '신청자구분 - 원천의 신청인 구분 원문';


--
-- Name: COLUMN tb_cvlcpt.dclr_fld; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.dclr_fld IS '신고분야 - 원천의 신고 분야 원문';


--
-- Name: COLUMN tb_cvlcpt.rwrd_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.rwrd_yn IS '포상여부 - 원천의 포상 대상 여부(Y/N)';


--
-- Name: COLUMN tb_cvlcpt.prcs_inst_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.prcs_inst_cnt IS '처리기관수 - 해당 신고를 처리하는 기관의 수';


--
-- Name: COLUMN tb_cvlcpt.prcs_inst_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.prcs_inst_nm IS '처리기관명 - 해당 신고를 처리하는 기관의 이름';


--
-- Name: COLUMN tb_cvlcpt.prcs_inst_pic_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.prcs_inst_pic_nm IS '처리기관담당자명 - 처리기관 담당자의 이름';


--
-- Name: COLUMN tb_cvlcpt.dclr_grp_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.dclr_grp_nm IS '신고단체명 - 신고를 접수한 단체의 이름';


--
-- Name: COLUMN tb_cvlcpt.mbr_grp_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.mbr_grp_nm IS '회원단체명 - 신고자가 소속된 회원 단체의 이름';


--
-- Name: COLUMN tb_cvlcpt.dclr_type; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.dclr_type IS '신고유형 - 원천의 유형 원문. prk_cvlcpt_type 이 비었을 때 민원유형코드(cvlcpt_type_cd) CASE 분류의 입력이다 — 재분류의 근거라 반드시 보존한다';


--
-- Name: COLUMN tb_cvlcpt.prk_cvlcpt_type; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.prk_cvlcpt_type IS '주차민원유형 - 원천이 직접 준 주차민원 유형. 값이 있으면 CASE 분류보다 우선한다';


--
-- Name: COLUMN tb_cvlcpt.src_path; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.src_path IS '유입경로 - 원천의 수집 경로 원문. 수집출처코드(src_cd)로 변환하기 전의 값';


--
-- Name: COLUMN tb_cvlcpt.origin_pk; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.origin_pk IS '원천아이디 - 원천 cvlcpt_id. NULL/중복 허용, 내부 PK와 무관';


--
-- Name: COLUMN tb_cvlcpt.src_hash; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.src_hash IS '원천해시 - 원천 ID와 load_dt를 제외한 내용의 SHA-256. 기존 중복 사본만 NULL';


--
-- Name: COLUMN tb_cvlcpt.orgnl_load_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_cvlcpt.orgnl_load_dt IS '원본적재일시 - 전환 전 load_dt(답변일 등), 워터마크에 사용하지 않음';


--
-- Name: tb_cvlcpt_cvlcpt_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

CREATE SEQUENCE parking.tb_cvlcpt_cvlcpt_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tb_cvlcpt_cvlcpt_id_seq; Type: SEQUENCE OWNED BY; Schema: parking; Owner: -
--

ALTER SEQUENCE parking.tb_cvlcpt_cvlcpt_id_seq OWNED BY parking.tb_cvlcpt.cvlcpt_id;


--
-- Name: tb_dept; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_dept (
    dept_cd character varying(30) NOT NULL,
    dept_nm character varying(100) NOT NULL,
    sort_seq numeric(10,0),
    use_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    rgtr_id character varying(20) NOT NULL,
    reg_dt timestamp without time zone NOT NULL,
    mdfr_id character varying(20),
    mdfcn_dt timestamp without time zone
);


--
-- Name: TABLE tb_dept; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_dept IS '부서 - IdP groups claim 의 투영(TSK-4183 D5). 마스터가 아니다';


--
-- Name: COLUMN tb_dept.dept_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_dept.dept_cd IS '부서코드 - IdP 그룹 슬러그(최대 30자)';


--
-- Name: COLUMN tb_dept.dept_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_dept.dept_nm IS '부서명 - 초기값은 슬러그와 같다. 표시명은 사후 UPDATE 로 보정';


--
-- Name: COLUMN tb_dept.sort_seq; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_dept.sort_seq IS '정렬순서 - 화면 select 표시 순서. 자동 등재분은 NULL(목록이 NULLS LAST)';


--
-- Name: COLUMN tb_dept.use_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_dept.use_yn IS '사용여부 - N 이면 /api/depts 목록에서 제외';


--
-- Name: COLUMN tb_dept.rgtr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_dept.rgtr_id IS '등록자ID - 등록자 회원 아이디';


--
-- Name: COLUMN tb_dept.reg_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_dept.reg_dt IS '등록일시 - 등록 일시';


--
-- Name: COLUMN tb_dept.mdfr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_dept.mdfr_id IS '수정자ID - 수정자 회원 아이디';


--
-- Name: COLUMN tb_dept.mdfcn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_dept.mdfcn_dt IS '수정일시 - 수정 일시';


--
-- Name: tb_faq; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_faq (
    faq_id bigint NOT NULL,
    qstn character varying(300) NOT NULL,
    ans text,
    expsr_seq integer,
    expsr_yn boolean NOT NULL
);


--
-- Name: TABLE tb_faq; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_faq IS '자주하는질문 - AI 어시스턴트 추천 질의';


--
-- Name: COLUMN tb_faq.faq_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_faq.faq_id IS 'FAQ ID - FAQ 를 식별하는 고유 번호(PK, BIGINT 자동 증가. TSK-4026 D6)';


--
-- Name: COLUMN tb_faq.qstn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_faq.qstn IS '질문 - 자주 하는 질문 내용';


--
-- Name: COLUMN tb_faq.ans; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_faq.ans IS '답변 - 질문에 대한 답변 내용';


--
-- Name: COLUMN tb_faq.expsr_seq; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_faq.expsr_seq IS '노출순서 - FAQ 화면 노출 순서';


--
-- Name: COLUMN tb_faq.expsr_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_faq.expsr_yn IS '노출여부 - FAQ 노출 여부';


--
-- Name: tb_faq_faq_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_faq ALTER COLUMN faq_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_faq_faq_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_grid; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_grid (
    grid_sz integer NOT NULL,
    grid_cd character varying(20) NOT NULL,
    sgg_cd character varying(5) NOT NULL,
    ctr_lat numeric(12,10) NOT NULL,
    ctr_lot numeric(13,10) NOT NULL,
    bndry_geom public.geometry(MultiPolygon,4326) NOT NULL,
    CONSTRAINT ck_tb_grid_grid_sz CHECK ((grid_sz = ANY (ARRAY[1000, 500, 250, 100])))
);


--
-- Name: TABLE tb_grid; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_grid IS '격자 - 생활권·핫스팟 분석의 공간 집계 단위가 되는 제주 격자 마스터(1km/500m/250m/100m)';


--
-- Name: COLUMN tb_grid.grid_sz; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_grid.grid_sz IS '격자크기 - 격자 한 변의 길이(m). 1000/500/250/100';


--
-- Name: COLUMN tb_grid.grid_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_grid.grid_cd IS '격자코드 - 격자를 식별하는 국가지점번호(예: 나나7578). 격자크기별로 체계가 다르다';


--
-- Name: COLUMN tb_grid.sgg_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_grid.sgg_cd IS '시군구코드 - 격자가 속한 시군구 5자리 코드(50110 제주시 / 50130 서귀포시)';


--
-- Name: COLUMN tb_grid.ctr_lat; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_grid.ctr_lat IS '중심위도 - 격자 중심점 위도 좌표(적재 시 ST_Centroid 로 산출)';


--
-- Name: COLUMN tb_grid.ctr_lot; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_grid.ctr_lot IS '중심경도 - 격자 중심점 경도 좌표(적재 시 ST_Centroid 로 산출)';


--
-- Name: COLUMN tb_grid.bndry_geom; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_grid.bndry_geom IS '경계도형 - 격자 경계를 나타내는 공간 도형(geometry) 데이터';


--
-- Name: tb_lgn_hstry; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_lgn_hstry (
    lgn_sn bigint NOT NULL,
    user_id character varying(20),
    lgn_dt timestamp without time zone NOT NULL,
    lgn_yn character(1) NOT NULL,
    lgn_fail_rsn character varying(200),
    lgn_ip_addr character varying(45),
    sesn_id character varying(20),
    cntn_end_dt timestamp without time zone,
    CONSTRAINT ck_tb_lgn_hstry_sesn_id CHECK (((sesn_id IS NULL) OR (length((sesn_id)::text) = 16)))
);


--
-- Name: TABLE tb_lgn_hstry; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_lgn_hstry IS '로그인이력 - 로그인 시도 1회 = 1행. 성공·실패를 모두 남기며 접속 종료 시각을 함께 관리한다';


--
-- Name: COLUMN tb_lgn_hstry.lgn_sn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_lgn_hstry.lgn_sn IS '로그인일련번호 - 로그인 이력을 식별하는 일련번호(PK)';


--
-- Name: COLUMN tb_lgn_hstry.user_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_lgn_hstry.user_id IS '사용자ID - 로그인 사용자 아이디. 신원 확인 전 실패는 NULL (TB_USER 참조, FK 미설정)';


--
-- Name: COLUMN tb_lgn_hstry.lgn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_lgn_hstry.lgn_dt IS '로그인일시 - 로그인 시도 일시';


--
-- Name: COLUMN tb_lgn_hstry.lgn_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_lgn_hstry.lgn_yn IS '로그인여부 - 로그인 성공 여부(Y/N)';


--
-- Name: COLUMN tb_lgn_hstry.lgn_fail_rsn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_lgn_hstry.lgn_fail_rsn IS '로그인실패사유 - 실패 시 사유. 원문이 아니라 마스킹된 요약을 남긴다';


--
-- Name: COLUMN tb_lgn_hstry.lgn_ip_addr; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_lgn_hstry.lgn_ip_addr IS '로그인IP주소 - 클라이언트 IP. 프록시 뒤에서는 X-Forwarded-For 로 복원한 값이라 참고 정보다';


--
-- Name: COLUMN tb_lgn_hstry.sesn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_lgn_hstry.sesn_id IS '세션아이디 - 세션 식별자의 SHA-256 앞 16자. 원문을 저장하지 않는다(탈취 방지)';


--
-- Name: COLUMN tb_lgn_hstry.cntn_end_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_lgn_hstry.cntn_end_dt IS '접속종료일시 - 로그아웃·세션만료 시각. 브라우저 닫기·서버 재시작은 채워지지 않는다(정상)';


--
-- Name: tb_lgn_hstry_lgn_sn_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_lgn_hstry ALTER COLUMN lgn_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_lgn_hstry_lgn_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_menu; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_menu (
    menu_id character varying(20) NOT NULL,
    up_menu_id character varying(20),
    menu_nm character varying(100) NOT NULL,
    menu_path_nm character varying(300),
    menu_lvl numeric(10,0) NOT NULL,
    sort_seq numeric(10,0) NOT NULL,
    use_yn character(1) NOT NULL,
    rgtr_id character varying(20) NOT NULL,
    reg_dt timestamp without time zone NOT NULL,
    mdfr_id character varying(20),
    mdfcn_dt timestamp without time zone
);


--
-- Name: TABLE tb_menu; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_menu IS '메뉴 - 포털 메뉴 트리 정의(대/중/소 메뉴, 폴더·페이지·버튼). 자기참조 계층 구조.';


--
-- Name: COLUMN tb_menu.menu_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.menu_id IS '메뉴아이디 - 메뉴를 식별하는 아이디. 예: M0101';


--
-- Name: COLUMN tb_menu.up_menu_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.up_menu_id IS '상위메뉴아이디 - 상위 메뉴 아이디(자기참조). 최상위는 NULL';


--
-- Name: COLUMN tb_menu.menu_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.menu_nm IS '메뉴명 - 메뉴 명칭';


--
-- Name: COLUMN tb_menu.menu_path_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.menu_path_nm IS '메뉴경로명 - 메뉴 화면 경로. 1단계(폴더형) 메뉴는 NULL. 예: /dataset/search';


--
-- Name: COLUMN tb_menu.menu_lvl; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.menu_lvl IS '메뉴단계 - 메뉴 트리 단계(1=대메뉴, 2=중메뉴, 3=소메뉴). 메뉴 유형 구분 기준 — 1단계는 폴더(하위 메뉴 묶음), 2단계 이하는 실제 페이지';


--
-- Name: COLUMN tb_menu.sort_seq; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.sort_seq IS '정렬순서 - 동일 상위 메뉴 내 정렬 순서';


--
-- Name: COLUMN tb_menu.use_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.use_yn IS '사용여부 - 사용 여부(Y/N)';


--
-- Name: COLUMN tb_menu.rgtr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.rgtr_id IS '등록자ID - 등록자 회원 아이디';


--
-- Name: COLUMN tb_menu.reg_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.reg_dt IS '등록일시 - 등록 일시';


--
-- Name: COLUMN tb_menu.mdfr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.mdfr_id IS '수정자ID - 수정자 회원 아이디';


--
-- Name: COLUMN tb_menu.mdfcn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_menu.mdfcn_dt IS '수정일시 - 수정 일시';


--
-- Name: tb_noti_hstry_rcvr; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_noti_hstry_rcvr (
    noti_id bigint NOT NULL,
    rcvr_id bigint NOT NULL,
    prsl_yn character(1) DEFAULT 'N'::bpchar NOT NULL,
    prsl_dt timestamp without time zone
);


--
-- Name: TABLE tb_noti_hstry_rcvr; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_noti_hstry_rcvr IS '알림수신대상 - 위험단계 알림 1건의 수신 대상 수신자와 그 사람의 열람 상태. TSK-4202 부터 조회 화면이 이 표로 대상을 거른다(TSK-4028 D13 의 "조회는 이 표를 쓰지 않는다" 는 폐기). 판정 배치가 적재한다';


--
-- Name: COLUMN tb_noti_hstry_rcvr.noti_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_hstry_rcvr.noti_id IS '알림아이디 - 위험단계알림이력 TB_RISK_STP_NOTI_HSTRY 참조';


--
-- Name: COLUMN tb_noti_hstry_rcvr.rcvr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_hstry_rcvr.rcvr_id IS '수신자ID - 메일을 받은 수신자 TB_NOTI_RCVR 참조';


--
-- Name: COLUMN tb_noti_hstry_rcvr.prsl_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_hstry_rcvr.prsl_yn IS '열람여부 - 이 수신자가 이 알림을 열람했는지(Y/N). 알림 1건당 하나였던 TB_RISK_STP_NOTI_HSTRY.prsl_yn 을 수신자별로 분리한 것(TSK-4202 D9)';


--
-- Name: COLUMN tb_noti_hstry_rcvr.prsl_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_hstry_rcvr.prsl_dt IS '열람일시 - prsl_yn 이 Y 로 바뀐 시각';


--
-- Name: tb_noti_rcvr; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_noti_rcvr (
    rcvr_id bigint NOT NULL,
    risk_lvl_cd character varying(30),
    user_id character varying(20) NOT NULL,
    rcvr_nm character varying(100),
    cntct_no character varying(11),
    ntfctn_ch_cd character varying(30),
    dept_cd character varying(30),
    eml character varying(100),
    sgg_nm character varying(40),
    emd_cd character varying(8),
    use_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    del_yn character(1) DEFAULT 'N'::bpchar NOT NULL
);


--
-- Name: TABLE tb_noti_rcvr; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_noti_rcvr IS '알림수신자 - 위험 단계별 알림 수신 대상(내부 사용자 또는 외부 연락처)';


--
-- Name: COLUMN tb_noti_rcvr.rcvr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.rcvr_id IS '수신자ID - 알림 수신자를 식별하는 고유 번호(PK, BIGINT 자동 증가. TSK-4028 D11)';


--
-- Name: COLUMN tb_noti_rcvr.risk_lvl_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.risk_lvl_cd IS '위험단계코드 - [DEPRECATED] TB_NOTI_RCVR_LVL 로 이관(TSK-4028 D5). 신규 코드는 참조하지 않는다';


--
-- Name: COLUMN tb_noti_rcvr.user_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.user_id IS '사용자ID - 수신자로 지정된 사용자(TB_USER.user_id). 벨·알림 조회의 로그인 사용자 매칭 키(TSK-4250 Q3 — 종전 이메일 매칭 대체). 활성 행(del_yn=N) 기준 사용자당 1행(R9). FK 미설정 — 사용자 비활성·삭제 정책이 없어 걸면 사용자 정리가 막힌다';


--
-- Name: COLUMN tb_noti_rcvr.rcvr_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.rcvr_nm IS '수신자명 - 외부 수신자 명칭';


--
-- Name: COLUMN tb_noti_rcvr.cntct_no; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.cntct_no IS '연락처 - 수신자 연락처';


--
-- Name: COLUMN tb_noti_rcvr.ntfctn_ch_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.ntfctn_ch_cd IS '통보채널코드 - 알림 통보 채널 코드 (예: SMS/메일)';


--
-- Name: COLUMN tb_noti_rcvr.dept_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.dept_cd IS '소속부서코드 - 수신자 소속(TB_DEPT)';


--
-- Name: COLUMN tb_noti_rcvr.eml; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.eml IS '이메일 - 알림 수신 이메일 주소';


--
-- Name: COLUMN tb_noti_rcvr.sgg_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.sgg_nm IS '시군구명 - [DEPRECATED] TB_NOTI_RCVR_RGN 로 이관(TSK-4250 R4). 신규 코드는 참조하지 않는다';


--
-- Name: COLUMN tb_noti_rcvr.emd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.emd_cd IS '읍면동코드 - [DEPRECATED] TB_NOTI_RCVR_RGN 로 이관(TSK-4250 R4). 신규 코드는 참조하지 않는다';


--
-- Name: COLUMN tb_noti_rcvr.use_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.use_yn IS '사용여부 - 알림 활성화 여부(Y/N). 화면의 토글 스위치';


--
-- Name: COLUMN tb_noti_rcvr.del_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr.del_yn IS '삭제여부 - 소프트 삭제 플래그(Y/N). 발송기록(TB_NOTI_HSTRY_RCVR)이 rcvr_id 로 참조하므로 행을 지우지 않는다(TSK-4028 D5)';


--
-- Name: tb_noti_rcvr_lvl; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_noti_rcvr_lvl (
    rcvr_id bigint NOT NULL,
    risk_lvl_cd character varying(30) NOT NULL
);


--
-- Name: TABLE tb_noti_rcvr_lvl; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_noti_rcvr_lvl IS '알림수신자위험단계 - 수신자가 지정한 위험 단계(다중). ⚠️ 알림 생성 대상을 거르지 않는다(TSK-4028 D14) — 담당 지역의 알림은 단계와 무관하게 전부 조회된다. 발송 방식을 가르는 용도(TSK-4028 D5·D14)';


--
-- Name: COLUMN tb_noti_rcvr_lvl.rcvr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr_lvl.rcvr_id IS '수신자ID - 알림수신자 TB_NOTI_RCVR 참조';


--
-- Name: COLUMN tb_noti_rcvr_lvl.risk_lvl_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr_lvl.risk_lvl_cd IS '위험단계코드 - 수신자가 지정한 위험 단계(RISK_LVL_CD). 조회 대상 필터가 아니다';


--
-- Name: tb_noti_rcvr_rcvr_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_noti_rcvr ALTER COLUMN rcvr_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_noti_rcvr_rcvr_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_noti_rcvr_rgn; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_noti_rcvr_rgn (
    rgn_sn bigint NOT NULL,
    rcvr_id bigint NOT NULL,
    sgg_nm character varying(40),
    emd_cd character varying(8),
    CONSTRAINT ck_noti_rcvr_rgn_sgg CHECK (((emd_cd IS NULL) OR (sgg_nm IS NOT NULL)))
);


--
-- Name: TABLE tb_noti_rcvr_rgn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_noti_rcvr_rgn IS '알림수신자지역 - 수신자가 지정한 수신 대상 지역(다중, TSK-4250 R1). 🔴 sgg_nm·emd_cd 둘 다 NULL 인 행 = 전체 지역이고, 행이 0개면 수신 지역 없음(아무 알림도 받지 않음)이다(R3 — fail-closed). NULL 을 "값 없음" 으로 읽고 걷어내지 말 것';


--
-- Name: COLUMN tb_noti_rcvr_rgn.rgn_sn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr_rgn.rgn_sn IS '지역일련번호 - 수신자 지역 행을 식별하는 일련번호(PK, BIGINT 자동 증가)';


--
-- Name: COLUMN tb_noti_rcvr_rgn.rcvr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr_rgn.rcvr_id IS '수신자ID - 알림수신자 TB_NOTI_RCVR 참조(삭제 시 CASCADE)';


--
-- Name: COLUMN tb_noti_rcvr_rgn.sgg_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr_rgn.sgg_nm IS '시군구명 - 수신 대상 시군구. 🔴 emd_cd 와 함께 NULL 이면 전체 지역(R3). 행 0개 = 수신 지역 없음(fail-closed)';


--
-- Name: COLUMN tb_noti_rcvr_rgn.emd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_noti_rcvr_rgn.emd_cd IS '읍면동코드 - 수신 대상 행정동(행정구역 TB_ADMDST 참조). NULL 이면 sgg_nm 시군구 전체, sgg_nm 도 NULL 이면 전체 지역(R3). 값이 있으면 sgg_nm 필수(CHECK)';


--
-- Name: tb_noti_rcvr_rgn_rgn_sn_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_noti_rcvr_rgn ALTER COLUMN rgn_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_noti_rcvr_rgn_rgn_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_pblpklt; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_pblpklt (
    pklt_nm character varying(300) NOT NULL,
    emd_cd character varying(8) NOT NULL,
    lat numeric(12,10) NOT NULL,
    lot numeric(13,10) NOT NULL,
    pklt_cpcty integer,
    pklt_type_cd character varying(30),
    extsh_dt timestamp without time zone,
    origin_pk character varying(40),
    pklt_addr character varying(200),
    orgnl_id character varying(40),
    orgnl_sn integer,
    emd_nm character varying(40),
    pklt_type_nm character varying(100),
    pkltar numeric(19,9),
    prk_crg_yn character(1),
    prk_crg_cn character varying(4000),
    prk_crg_swtc_ym character(6),
    instl_ym character(6),
    instl_ymd character(8),
    rttrt numeric(5,2),
    day_avg_vhcl_cnt integer,
    incm_amt numeric(15,0),
    expnd_amt numeric(15,0),
    mng_nope integer,
    mng_dept_nm character varying(200),
    pklt_mthd_nm character varying(100),
    ev_chrgr_cnt integer,
    ownshp_type_nm character varying(100),
    prk_slot_grd character varying(30),
    obstcl_cn character varying(4000),
    obstcl_slot_cnt integer,
    trfc_sfty_sign_instl_yn character(1),
    stpr_instl_yn character(1),
    rmrk_cn character varying(4000),
    pklt_id bigint NOT NULL
);


--
-- Name: TABLE tb_pblpklt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_pblpklt IS '공영주차장 - 제주 전역 공영주차장 마스터. 투자우선순위·사고·이용패턴 기준';


--
-- Name: COLUMN tb_pblpklt.pklt_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.pklt_nm IS '주차장명 - 공영주차장의 명칭';


--
-- Name: COLUMN tb_pblpklt.emd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.emd_cd IS '읍면동코드 - 주차장이 위치한 행정동 코드(행정구역 TB_ADMDST 참조)';


--
-- Name: COLUMN tb_pblpklt.lat; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.lat IS '위도 - 주차장 위치의 위도 좌표(지도 표시용)';


--
-- Name: COLUMN tb_pblpklt.lot; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.lot IS '경도 - 주차장 위치의 경도 좌표(지도 표시용)';


--
-- Name: COLUMN tb_pblpklt.pklt_cpcty; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.pklt_cpcty IS '주차면수 - 주차장의 총 주차 가능 면수';


--
-- Name: COLUMN tb_pblpklt.pklt_type_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.pklt_type_cd IS '주차장구분코드 - 주차장 유형을 구분하는 공통코드';


--
-- Name: COLUMN tb_pblpklt.extsh_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.extsh_dt IS '소멸일시 - NULL=존재, 값 있음=소멸. 수집 잡이 이번 실행 집합에 없는 행만 마킹한다';


--
-- Name: COLUMN tb_pblpklt.origin_pk; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.origin_pk IS '원본키 - 마지막으로 관측한 원천 pklt_id. 비유일이며 주차장 식별에 사용하지 않음';


--
-- Name: COLUMN tb_pblpklt.pklt_addr; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.pklt_addr IS '주차장주소 - 원천 주소 원문. 비유일. 수집 시 같은 주소의 기존 행 전체를 갱신';


--
-- Name: COLUMN tb_pblpklt.orgnl_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.orgnl_id IS '원본아이디 - 원천이 부여한 아이디 원문';


--
-- Name: COLUMN tb_pblpklt.orgnl_sn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.orgnl_sn IS '원본일련번호 - 원천이 부여한 일련번호 원문';


--
-- Name: COLUMN tb_pblpklt.emd_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.emd_nm IS '읍면동명 - 원천이 준 행정동 명칭 원문. emd_cd 공간매칭 실패 시 폴백 입력';


--
-- Name: COLUMN tb_pblpklt.pklt_type_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.pklt_type_nm IS '주차장구분명 - 원천 유형 원문(예: 공한지). pklt_type_cd 분류의 입력';


--
-- Name: COLUMN tb_pblpklt.pkltar; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.pkltar IS '주차장면적 - 주차장의 면적(㎡)';


--
-- Name: COLUMN tb_pblpklt.prk_crg_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.prk_crg_yn IS '주차요금여부 - 유료 주차장인지(Y/N)';


--
-- Name: COLUMN tb_pblpklt.prk_crg_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.prk_crg_cn IS '주차요금내용 - 요금 체계 설명 원문';


--
-- Name: COLUMN tb_pblpklt.prk_crg_swtc_ym; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.prk_crg_swtc_ym IS '주차요금전환연월 - 무료↔유료 전환 시점';


--
-- Name: COLUMN tb_pblpklt.instl_ym; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.instl_ym IS '설치연월 - 주차장 설치 연월';


--
-- Name: COLUMN tb_pblpklt.instl_ymd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.instl_ymd IS '설치일자 - 주차장 설치 일자';


--
-- Name: COLUMN tb_pblpklt.rttrt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.rttrt IS '회전율 - 주차면 1면당 일 회전 횟수';


--
-- Name: COLUMN tb_pblpklt.day_avg_vhcl_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.day_avg_vhcl_cnt IS '일평균차량수 - 일 평균 주차 차량 수';


--
-- Name: COLUMN tb_pblpklt.incm_amt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.incm_amt IS '수입금액 - 주차 요금 수입';


--
-- Name: COLUMN tb_pblpklt.expnd_amt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.expnd_amt IS '지출금액 - 운영 지출';


--
-- Name: COLUMN tb_pblpklt.mng_nope; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.mng_nope IS '관리인원수 - 배치된 관리 인원 수';


--
-- Name: COLUMN tb_pblpklt.mng_dept_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.mng_dept_nm IS '관리부서명 - 주차장을 관리하는 부서';


--
-- Name: COLUMN tb_pblpklt.pklt_mthd_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.pklt_mthd_nm IS '주차장방법명 - 원문. 두 의미가 섞여 있다: 공한지·공영 계열은 포장재(블록·아스콘 등), 노상 계열은 주차 배치(평행·직각 등). 원본 필드 pavement_type_nm';


--
-- Name: COLUMN tb_pblpklt.ev_chrgr_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.ev_chrgr_cnt IS '전기차충전기수 - 설치된 전기차 충전기 수. 🔴 EV·CHRGR 는 사전 등재 요청 중(TSK-4142)';


--
-- Name: COLUMN tb_pblpklt.ownshp_type_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.ownshp_type_nm IS '소유권유형명 - 소유 형태 원문(예: 공영)';


--
-- Name: COLUMN tb_pblpklt.prk_slot_grd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.prk_slot_grd IS '주차슬롯등급 - 급지 구분 원문';


--
-- Name: COLUMN tb_pblpklt.obstcl_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.obstcl_cn IS '장애물내용 - 주차면을 가로막는 장애물 원문(예: 전신주·쓰레기장·주차금지봉). 원본 필드 obstacle_cn. 🔴 OBSTCL 은 사전 등재 요청 중(TSK-4142)';


--
-- Name: COLUMN tb_pblpklt.obstcl_slot_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.obstcl_slot_cnt IS '장애물구획수 - 장애물로 쓸 수 없는 주차면 수(pklt_cpcty 이하). 원본 필드 obstacle_space_cnt. 🔴 OBSTCL 은 사전 등재 요청 중(TSK-4142)';


--
-- Name: COLUMN tb_pblpklt.trfc_sfty_sign_instl_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.trfc_sfty_sign_instl_yn IS '교통안전표지설치여부 - 🔴 TRFC 는 사전 등재 요청 중(TSK-4142)';


--
-- Name: COLUMN tb_pblpklt.stpr_instl_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.stpr_instl_yn IS '차량멈춤턱설치여부 - 🔴 STPR 는 사전 등재 요청 중(TSK-4142)';


--
-- Name: COLUMN tb_pblpklt.rmrk_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.rmrk_cn IS '비고내용 - 원천 비고 원문';


--
-- Name: COLUMN tb_pblpklt.pklt_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pblpklt.pklt_id IS '주차장아이디 - 공영주차장을 식별하는 고유 ID(PK). DB 가 매기는 일련번호(BIGINT IDENTITY)이며 업무적 의미가 없다. 원천 식별자는 origin_pk 가 진다';


--
-- Name: tb_pblpklt_pklt_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_pblpklt ALTER COLUMN pklt_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_pblpklt_pklt_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_pklt_acdnt_hstry; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_pklt_acdnt_hstry (
    acdnt_id character varying(20) NOT NULL,
    pklt_id bigint NOT NULL,
    acdnt_dt timestamp without time zone NOT NULL,
    svrt_cd character varying(30),
    acdnt_type character varying(100),
    origin_pk text,
    pklt_nm character varying(300),
    pklt_addr character varying(200),
    prcs_cn character varying(4000),
    prcs_stts_nm character varying(300),
    src_json jsonb,
    src_hash character varying(64),
    reg_dt timestamp without time zone,
    btch_excn_id bigint
);


--
-- Name: TABLE tb_pklt_acdnt_hstry; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_pklt_acdnt_hstry IS '주차장사고이력 - 주차장 화재·안전 사고 이력. 투자우선순위 사고이력 지표';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.acdnt_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.acdnt_id IS '사고아이디 - 내부 시퀀스 PK. 원천 순번과 무관. 기존 ID 보존';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.pklt_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.pklt_id IS '주차장아이디 - 사고가 발생한 주차장 ID(공영주차장 TB_PBLPKLT 참조). DB 채번 일련번호(BIGINT)';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.acdnt_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.acdnt_dt IS '사고일시 - 사고 발생 일시(최근 3개월 집계 기준)';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.svrt_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.svrt_cd IS '심각도코드 - 사고 심각도를 구분하는 코드';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.acdnt_type; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.acdnt_type IS '사고유형 - 사고 유형 (예: 화재·안전 등)';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.origin_pk; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.origin_pk IS '원천아이디 - 원천 seq_no 원문. 중복/NULL 허용, 내부 PK로 사용하지 않음';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.pklt_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.pklt_nm IS '주차장명 - 사고 원천의 주차장명 원문';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.pklt_addr; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.pklt_addr IS '주차장주소 - 사고 원천의 주소 원문';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.prcs_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.prcs_cn IS '처리내용 - 사고 처리내용 원문';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.prcs_stts_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.prcs_stts_nm IS '처리상태명 - 사고 처리상태 원문, 심각도와 구분';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.src_json; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.src_json IS '원천JSON - 원천 7컬럼, 사고일 복원 근거 보존';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.src_hash; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.src_hash IS '원천해시 - 내부 주차장ID/사고일시/사고유형의 SHA-256. 동일 사고 재수집 병합';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.reg_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.reg_dt IS '등록일시 - 내부 DB 최초 적재시각(KST). 사고일시와 구분';


--
-- Name: COLUMN tb_pklt_acdnt_hstry.btch_excn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_acdnt_hstry.btch_excn_id IS '배치실행아이디 - 마지막으로 수집한 Spring Batch 실행 ID';


--
-- Name: tb_pklt_acdnt_hstry_acdnt_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

CREATE SEQUENCE parking.tb_pklt_acdnt_hstry_acdnt_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tb_pklt_acdnt_hstry_acdnt_id_seq; Type: SEQUENCE OWNED BY; Schema: parking; Owner: -
--

ALTER SEQUENCE parking.tb_pklt_acdnt_hstry_acdnt_id_seq OWNED BY parking.tb_pklt_acdnt_hstry.acdnt_id;


--
-- Name: tb_pklt_sfty_chck; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_pklt_sfty_chck (
    sfty_chck_id bigint NOT NULL,
    pklt_id bigint NOT NULL,
    chck_ymd character(8),
    chck_prd_cd character varying(30),
    sfty_chck_type_cd character varying(30),
    sfty_chck_rslt_cd character varying(30) NOT NULL,
    rmrk_cn character varying(4000),
    rgtr_id character varying(20) NOT NULL,
    reg_dt timestamp without time zone NOT NULL,
    load_dt timestamp without time zone DEFAULT now(),
    btch_excn_id bigint,
    src_hash character(64),
    src_json jsonb
);


--
-- Name: TABLE tb_pklt_sfty_chck; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_pklt_sfty_chck IS '주차장안전점검 - 공영주차장별 안전점검 실시 현황';


--
-- Name: COLUMN tb_pklt_sfty_chck.sfty_chck_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.sfty_chck_id IS '안전점검아이디 - 안전점검 1건을 식별하는 고유 ID(PK). DB 채번이며 업무적 의미 없음';


--
-- Name: COLUMN tb_pklt_sfty_chck.pklt_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.pklt_id IS '주차장아이디 - 점검 대상 주차장 ID(공영주차장 TB_PBLPKLT 참조)';


--
-- Name: COLUMN tb_pklt_sfty_chck.chck_ymd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.chck_ymd IS '점검일자 - 안전점검을 실시한 날짜(YYYYMMDD)';


--
-- Name: COLUMN tb_pklt_sfty_chck.chck_prd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.chck_prd_cd IS '점검기간코드 - 점검 시기. 상반기/하반기(CHCK_PRD_CD)';


--
-- Name: COLUMN tb_pklt_sfty_chck.sfty_chck_type_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.sfty_chck_type_cd IS '안전점검유형코드 - 점검 유형(SFTY_CHCK_TYPE_CD)';


--
-- Name: COLUMN tb_pklt_sfty_chck.sfty_chck_rslt_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.sfty_chck_rslt_cd IS '안전점검결과코드 - 점검 종합 판정(SFTY_CHCK_RSLT_CD)';


--
-- Name: COLUMN tb_pklt_sfty_chck.rmrk_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.rmrk_cn IS '비고내용 - 참고 사항';


--
-- Name: COLUMN tb_pklt_sfty_chck.rgtr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.rgtr_id IS '등록자ID - 등록자 회원 아이디';


--
-- Name: COLUMN tb_pklt_sfty_chck.reg_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.reg_dt IS '등록일시 - 등록 일시';


--
-- Name: COLUMN tb_pklt_sfty_chck.load_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.load_dt IS '수집일시 - 원천 점검일과 구분. 기존 행의 수집일시는 미상';


--
-- Name: COLUMN tb_pklt_sfty_chck.src_json; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_sfty_chck.src_json IS '원천JSON - 날짜/유형 결측 및 원천 D등급을 임의 보정 없이 보존';


--
-- Name: tb_pklt_sfty_chck_sfty_chck_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_pklt_sfty_chck ALTER COLUMN sfty_chck_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_pklt_sfty_chck_sfty_chck_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_pklt_utztn_pttn; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_pklt_utztn_pttn (
    pklt_id bigint NOT NULL,
    tot_ymd date,
    pcshr numeric(5,2),
    trnvr_cnt integer,
    prk_cntom integer,
    tot_ym character(6),
    day_avg_trnvr_rt numeric(12,6),
    utztn_stts_id bigint NOT NULL,
    load_dt timestamp without time zone DEFAULT now(),
    btch_excn_id bigint,
    src_hash character(64),
    src_json jsonb
);


--
-- Name: TABLE tb_pklt_utztn_pttn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_pklt_utztn_pttn IS '주차장이용패턴 - 주차장 점유율·회전 등 이용 패턴 지표';


--
-- Name: COLUMN tb_pklt_utztn_pttn.pklt_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_utztn_pttn.pklt_id IS '주차장아이디 - 집계 대상 주차장 ID(공영주차장 TB_PBLPKLT 참조). DB 채번 일련번호(BIGINT)';


--
-- Name: COLUMN tb_pklt_utztn_pttn.tot_ymd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_utztn_pttn.tot_ymd IS '집계일자 - 이용 패턴 집계 기준 일자';


--
-- Name: COLUMN tb_pklt_utztn_pttn.pcshr; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_utztn_pttn.pcshr IS '점유율 - 해당 일자의 주차장 점유율';


--
-- Name: COLUMN tb_pklt_utztn_pttn.trnvr_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_utztn_pttn.trnvr_cnt IS '회전수 - 해당 일자의 주차 회전 수';


--
-- Name: COLUMN tb_pklt_utztn_pttn.prk_cntom; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_utztn_pttn.prk_cntom IS '주차대수 - 집계 기간에 해당 주차장에 주차한 차량 대수(주차 가능 면수 pklt_cpcty 와 다름)';


--
-- Name: COLUMN tb_pklt_utztn_pttn.tot_ym; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_utztn_pttn.tot_ym IS '집계연월 - 이용 패턴 집계 기준 연월(YYYYMM). 월별 집계가 정본이다';


--
-- Name: COLUMN tb_pklt_utztn_pttn.day_avg_trnvr_rt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_utztn_pttn.day_avg_trnvr_rt IS '일평균회전율 - 집계 기간의 하루 평균 주차 회전 수(원천 use_rate = 주차대수 ÷ 주차면수 ÷ 일수). 점유율 pcshr 과 다르다';


--
-- Name: COLUMN tb_pklt_utztn_pttn.utztn_stts_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_utztn_pttn.utztn_stts_id IS '이용집계아이디 - 주차장 이용 집계를 식별하는 고유 ID(PK). DB 가 매기는 일련번호(BIGINT IDENTITY)이며 업무적 의미가 없다';


--
-- Name: COLUMN tb_pklt_utztn_pttn.load_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_utztn_pttn.load_dt IS '수집일시 - 원천 측정월과 구분. 기존 행의 수집일시는 미상';


--
-- Name: COLUMN tb_pklt_utztn_pttn.src_json; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_pklt_utztn_pttn.src_json IS '원천JSON - 이용률 원문과 집계기간 미상인 관측값도 원문 그대로 보존';


--
-- Name: tb_pklt_utztn_pttn_utztn_stts_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_pklt_utztn_pttn ALTER COLUMN utztn_stts_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_pklt_utztn_pttn_utztn_stts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_plcy_smlt_rslt; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_plcy_smlt_rslt (
    rslt_id bigint NOT NULL,
    excn_id bigint NOT NULL,
    plcy_type_cd character varying(30) NOT NULL,
    predc_mm integer NOT NULL,
    add_pklt_slot integer,
    road_mtnc_type character varying(40),
    fee_incrs_rt numeric(5,2),
    cvlcpt_rdct_rt numeric(7,2),
    cngst_impv_rt numeric(5,2),
    cst_lvl_cd character varying(30) NOT NULL,
    rcmdtn_yn boolean NOT NULL,
    anls_rng_cn character varying(100),
    crtr_cvlcpt_cnt numeric(10,2),
    crtr_ilgl_cnt numeric(10,2),
    crtr_cngst_idx numeric(6,3),
    aftr_cngst_idx numeric(6,3),
    exs_pklt_cpcty integer,
    vld_add_pklt_slot numeric(8,2),
    aplcn_param_json jsonb,
    aftr_cvlcpt_cnt numeric(10,2),
    calc_stts_cd character varying(30),
    excs_yn boolean DEFAULT false NOT NULL,
    road_mtnc_len integer,
    adjc_cvlcpt_cnt numeric(10,2),
    fee_aplcn_mthd character varying(30),
    fee_aplcn_zone character varying(30),
    aplcn_trgt_slot integer,
    lvng_vhcl_cnt numeric(10,2),
    absrp_cnt numeric(10,2),
    trnsf_cnt numeric(10,2)
);


--
-- Name: TABLE tb_plcy_smlt_rslt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_plcy_smlt_rslt IS '정책시뮬레이션결과 - 주차장확충/이면도로정비/요금제적용 정책 효과 예측·시나리오 비교';


--
-- Name: COLUMN tb_plcy_smlt_rslt.rslt_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.rslt_id IS '결과아이디 - 정책시뮬레이션 결과를 식별하는 고유 번호(PK, BIGINT 자동 증가. TSK-4078)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.excn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.excn_id IS '실행아이디 - 결과가 생성된 분석 실행 ID(분석실행 TB_ANLS_EXCN 참조)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.plcy_type_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.plcy_type_cd IS '정책유형코드 - 정책 유형을 구분하는 코드(POLICY_TYPE)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.predc_mm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.predc_mm IS '예측기간 - 예측 대상 기간(개월, 예: 3/6/12/24)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.add_pklt_slot; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.add_pklt_slot IS '주차면증설수 - 주차장 확충 시뮬레이션의 주차면 증설 수';


--
-- Name: COLUMN tb_plcy_smlt_rslt.road_mtnc_type; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.road_mtnc_type IS '정비유형 - 이면도로 정비 유형';


--
-- Name: COLUMN tb_plcy_smlt_rslt.fee_incrs_rt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.fee_incrs_rt IS '요금인상율 - 요금제 적용 시 요금 인상율';


--
-- Name: COLUMN tb_plcy_smlt_rslt.cvlcpt_rdct_rt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.cvlcpt_rdct_rt IS '민원감소예측율 - 정책 적용 시 예측되는 민원 감소율';


--
-- Name: COLUMN tb_plcy_smlt_rslt.cngst_impv_rt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.cngst_impv_rt IS '혼잡도개선율 - 정책 적용 시 예측되는 혼잡도 개선율';


--
-- Name: COLUMN tb_plcy_smlt_rslt.cst_lvl_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.cst_lvl_cd IS '비용수준코드 - 정책 비용 수준을 구분하는 코드(COST_LEVEL)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.rcmdtn_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.rcmdtn_yn IS '추천여부 - 추천 정책 여부(추천 배지 표시)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.anls_rng_cn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.anls_rng_cn IS '분석범위내용 - 조회 범위 라벨(예: 제주시 전체 · 연동)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.crtr_cvlcpt_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.crtr_cvlcpt_cnt IS '기준민원수 - 기준선 월평균 민원 C0 합계(건/월). R 의 분모와 같은 합(NO_ENF 제외), 범위 전체가 NO_ENF 면 범위의 실제 C0 합';


--
-- Name: COLUMN tb_plcy_smlt_rslt.crtr_ilgl_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.crtr_ilgl_cnt IS '기준불법주차수 - 기준선 불법주차 추정치 E0 합계(대)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.crtr_cngst_idx; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.crtr_cngst_idx IS '기준혼잡지수 - 확충 전 혼잡 지수 H0 = (U0 + E0) ÷ S';


--
-- Name: COLUMN tb_plcy_smlt_rslt.aftr_cngst_idx; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.aftr_cngst_idx IS '이후혼잡지수 - 확충 후 혼잡 지수 H1. NO_ENF·NO_SPLY 면 NULL';


--
-- Name: COLUMN tb_plcy_smlt_rslt.exs_pklt_cpcty; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.exs_pklt_cpcty IS '기존주차면수 - 확충 전 공영주차장 주차면수 S';


--
-- Name: COLUMN tb_plcy_smlt_rslt.vld_add_pklt_slot; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.vld_add_pklt_slot IS '유효주차면증설수 - 유효 증설 면수 ΔS_eff(공한지는 반환분 차감 후, 소수 둘째 자리까지)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.aplcn_param_json; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.aplcn_param_json IS '적용매개변수JSON - 산출 시점 POLICY_SIM 계수(정책별 필수 계수 전부 · g_sgg 두 시 값 모두) + 민원·단속 기준 구간. 요청 조건은 TB_ANLS_EXCN.anls_param_json';


--
-- Name: COLUMN tb_plcy_smlt_rslt.aftr_cvlcpt_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.aftr_cvlcpt_cnt IS '이후민원수 - 확충 후 월평균 민원 C1 합계(건/월). NO_ENF 면 NULL';


--
-- Name: COLUMN tb_plcy_smlt_rslt.calc_stts_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.calc_stts_cd IS '산출상태코드 - 조회 범위 단위 산출 상태(CALC_STTS_CD). NULL=정상 · NO_ENF(범위 ΣE0=0) · NO_SPLY(범위 ΣS=0), NO_ENF 우선';


--
-- Name: COLUMN tb_plcy_smlt_rslt.excs_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.excs_yn IS '초과여부 - 흡수 가능량(α×ΔS — 요금제는 α×V)이 불법주차 추정치(E0) 이상이라 전량 흡수로 잘렸다(SATURATED, DEC-025)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.road_mtnc_len; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.road_mtnc_len IS '정비길이 - 이면도로 정비 구간 연장(m). 확보 면수 ΔS = 길이 ÷ 100 × n_100 (ΔS 는 vld_add_pklt_slot 에 저장)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.adjc_cvlcpt_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.adjc_cvlcpt_cnt IS '인접지역민원증가수 - 풍선효과·요금 회피 전이로 인접 지역에서 늘어나는 월 민원. 순효과 R 에 이미 반영돼 있으며 내역 확인용. 인접이 없으면 0(증가분은 대상에 가산)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.fee_aplcn_mthd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.fee_aplcn_mthd IS '요금적용방법 - 요금제 적용 방식 코드(RAISE 요금 인상 / CONVERT 무료→유료 전환)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.fee_aplcn_zone; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.fee_aplcn_zone IS '요금적용구역 - 적용 급지(ALL 전체 / Z1 1급지 동 / Z2 2급지 읍·면). 읍면동을 지정하면 그 읍면동의 급지';


--
-- Name: COLUMN tb_plcy_smlt_rslt.aplcn_trgt_slot; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.aplcn_trgt_slot IS '적용대상면수 - 대상 지역 ∩ 급지 ∩ 유무료 조건의 공영주차장 주차면 합(RAISE 유료 / CONVERT 무료, 민영 제외)';


--
-- Name: COLUMN tb_plcy_smlt_rslt.lvng_vhcl_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.lvng_vhcl_cnt IS '이탈차량수 - 요금 변경으로 주차장을 떠난 차량 수(V). 흡수·전이의 분모라 함께 저장한다';


--
-- Name: COLUMN tb_plcy_smlt_rslt.absrp_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.absrp_cnt IS '흡수불법주차수 - 빈자리로 들어온 불법주차 대수. occ < occ_th 이면 0';


--
-- Name: COLUMN tb_plcy_smlt_rslt.trnsf_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_plcy_smlt_rslt.trnsf_cnt IS '전이불법주차수 - 요금 회피로 새로 생긴 불법주차 대수';


--
-- Name: tb_plcy_smlt_rslt_rslt_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_plcy_smlt_rslt ALTER COLUMN rslt_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_plcy_smlt_rslt_rslt_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_risk_stp_noti_crtr; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_risk_stp_noti_crtr (
    thrsh_id character varying(20) NOT NULL,
    risk_lvl_cd character varying(30) NOT NULL,
    thrsh_cnt integer,
    mdfr_id character varying(20) NOT NULL,
    mdfcn_dt timestamp without time zone NOT NULL,
    incrs_rt numeric(7,2),
    min_cvlcpt_cnt integer DEFAULT 0 NOT NULL,
    bsc_min_cvlcpt_cnt integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_risk_crtr_bsc_min_cvlcpt_cnt CHECK ((bsc_min_cvlcpt_cnt >= 0)),
    CONSTRAINT ck_risk_crtr_min_cvlcpt_cnt CHECK ((min_cvlcpt_cnt >= 0))
);


--
-- Name: TABLE tb_risk_stp_noti_crtr; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_risk_stp_noti_crtr IS '위험단계알림기준 - 심각/경고/주의 단계별 알림 발생 기준 건수';


--
-- Name: COLUMN tb_risk_stp_noti_crtr.thrsh_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_crtr.thrsh_id IS '기준아이디 - 위험단계 알림 기준을 식별하는 고유 ID(PK)';


--
-- Name: COLUMN tb_risk_stp_noti_crtr.risk_lvl_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_crtr.risk_lvl_cd IS '위험단계코드 - 위험 단계를 구분하는 코드(RISK_LEVEL)';


--
-- Name: COLUMN tb_risk_stp_noti_crtr.thrsh_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_crtr.thrsh_cnt IS '기준건수 - [보류] 화면 기준이 증가율(%)로 확정돼 사용하지 않는다';


--
-- Name: COLUMN tb_risk_stp_noti_crtr.mdfr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_crtr.mdfr_id IS '수정자ID - 기준을 수정한 사용자 ID(외부 회원 시스템 참조, FK 미설정)';


--
-- Name: COLUMN tb_risk_stp_noti_crtr.mdfcn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_crtr.mdfcn_dt IS '수정일시 - 기준 수정 일시';


--
-- Name: COLUMN tb_risk_stp_noti_crtr.incrs_rt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_crtr.incrs_rt IS '증가율기준 - 전일 대비 민원 증가율(%) 기준. 이 값 이상이면 해당 단계 알림 발생. 3행(단계별 1행)이 전 지역·전 수신자에 공통 적용되는 유일한 설정이다(TSK-4028 D7·D18)';


--
-- Name: COLUMN tb_risk_stp_noti_crtr.min_cvlcpt_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_crtr.min_cvlcpt_cnt IS '최소민원수 - 판정일 당일 민원이 이 값 미만인 읍면동은 해당 단계 알림에서 제외한다. 0 이면 하한 없음. 증가율(incrs_rt)의 대안이 아니라 전제 조건이다 — 증가율 임계를 넘어도 이 건수에 못 미치면 알림이 나지 않는다(TSK-4092 D3·D4). ⚠️ 3행 값이 서로 다르면 단계마다 하한이 달라진다 - 예) CRIT 10·CAUT 5 이면 8건 읍면동은 심각은 탈락하고 주의만 발생한다. 화면은 값 1개만 받아 3행에 같은 값을 쓰므로(TSK-4092 D3-1) 정상 경로에서는 갈리지 않는다 - 갈린 상태는 SQL 로 직접 고친 경우뿐이다';


--
-- Name: COLUMN tb_risk_stp_noti_crtr.bsc_min_cvlcpt_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_crtr.bsc_min_cvlcpt_cnt IS '기본최소민원수 - 최초 등재값(불변). 화면 `최소 건수 기본값으로` 의 원천이며 저장 API 가 갱신하지 않는다(JPA 매핑이 updatable=false. TSK-4092 D11). ⚠️ 판정에는 쓰지 않는다 - 배치는 min_cvlcpt_cnt 만 읽는다';


--
-- Name: tb_risk_stp_noti_hstry; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_risk_stp_noti_hstry (
    noti_id bigint NOT NULL,
    risk_lvl_cd character varying(30) NOT NULL,
    emd_cd character varying(8) NOT NULL,
    cvlcpt_cnt integer,
    ocrn_dt timestamp without time zone NOT NULL,
    prcs_stts_cd character varying(30) NOT NULL,
    incrs_rt numeric(7,2),
    noti_ctt character varying(200),
    incrs_rt_thrsh numeric(7,2),
    prsl_yn character(1) DEFAULT 'N'::bpchar NOT NULL,
    prsl_dt timestamp without time zone,
    idnty_yn character(1) DEFAULT 'N'::bpchar NOT NULL,
    idnty_dt timestamp without time zone,
    CONSTRAINT ck_risk_hstry_read_cnfm CHECK (((idnty_yn = 'N'::bpchar) OR (prsl_yn = 'Y'::bpchar)))
);


--
-- Name: TABLE tb_risk_stp_noti_hstry; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_risk_stp_noti_hstry IS '위험단계알림이력 - 기준 초과 시 발생한 위험 알림 이력 및 처리상태';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.noti_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.noti_id IS '알림아이디 - 알림 이력을 식별하는 고유 번호(PK, BIGINT 자동 증가. TSK-4028 D11)';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.risk_lvl_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.risk_lvl_cd IS '위험단계코드 - 발령된 위험 단계 코드(RISK_LEVEL)';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.emd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.emd_cd IS '읍면동코드 - 판정이 난 행정동(필수). 판정은 제주도 전체 74개 읍면동을 대상으로 한다(TSK-4028 D12·D13)';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.cvlcpt_cnt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.cvlcpt_cnt IS '민원건수 - 판정 시점의 민원 건수';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.ocrn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.ocrn_dt IS '발생일시 - 알림 발생 일시';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.prcs_stts_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.prcs_stts_cd IS '처리상태코드 - 기존 컬럼. TSK-4028 범위에서는 OPEN 고정이며 실질 상태는 read_yn/cnfm_yn 이 담당한다(D17). 전환은 범위 밖 #4';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.incrs_rt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.incrs_rt IS '증가율 - 판정 시점의 전일 대비 민원 증가율(%). 해당 읍면동 기준으로 (당일건수 - GREATEST(전일건수,1)) / GREATEST(전일건수,1) * 100. 전일 0건은 1건으로 보정한다(TSK-4028 D18)';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.noti_ctt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.noti_ctt IS '알림내용 - 판정 시점에 만든 알림 문구. 예) 연동 민원 45% 증가 (TSK-4028 D6 — 조회 시 재조립하지 않는다)';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.incrs_rt_thrsh; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.incrs_rt_thrsh IS '증가율임계값 - 알림 발생 판정에 쓴 기준 증가율(%)';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.prsl_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.prsl_yn IS '열람여부 - 시스템 사용자가 이 알림을 열어 보았는지(Y/N)';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.prsl_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.prsl_dt IS '열람일시 - prsl_yn 이 Y 로 바뀐 시각';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.idnty_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.idnty_yn IS '확인여부 - 조치 완료를 명시적으로 표시했는지(Y/N). 열람(prsl_yn)보다 상위 상태';


--
-- Name: COLUMN tb_risk_stp_noti_hstry.idnty_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_risk_stp_noti_hstry.idnty_dt IS '확인일시 - idnty_yn 이 Y 로 바뀐 시각';


--
-- Name: tb_risk_stp_noti_hstry_noti_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_risk_stp_noti_hstry ALTER COLUMN noti_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_risk_stp_noti_hstry_noti_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_rptp; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_rptp (
    rptp_id bigint NOT NULL,
    crt_type character varying(20) NOT NULL,
    stng_id bigint,
    excn_id bigint,
    rptp_nm character varying(256) NOT NULL,
    anls_prd_expln character varying(100),
    stts_cd character varying(30) NOT NULL,
    file_fmt character varying(20),
    file_path character varying(300),
    creatr_id character varying(20),
    crt_ymd timestamp without time zone,
    creatr_nm character varying(50),
    trgt_prd_cd character varying(20),
    crltn_dept_cd character varying(30),
    crltn_cycl_cd character varying(30)
);


--
-- Name: TABLE tb_rptp; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_rptp IS '보고서 - 자동/직접 생성 보고서. 상태·생성자·파일 관리';


--
-- Name: COLUMN tb_rptp.rptp_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.rptp_id IS '보고서ID - 보고서를 식별하는 고유 번호(PK, BIGINT 자동 증가. TSK-4026 D6)';


--
-- Name: COLUMN tb_rptp.crt_type; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.crt_type IS '생성구분 - 보고서 생성 방식을 구분하는 코드(REPORT_GEN_TYPE)';


--
-- Name: COLUMN tb_rptp.stng_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.stng_id IS '설정아이디 - 자동생성 시 참조하는 설정 ID(보고서자동생성설정 TB_RPTP_AUTO_CRT_STNG 참조)';


--
-- Name: COLUMN tb_rptp.excn_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.excn_id IS '실행아이디 - 직접생성 시 참조하는 분석 실행 ID(분석실행 TB_ANLS_EXCN 참조)';


--
-- Name: COLUMN tb_rptp.rptp_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.rptp_nm IS '보고서명 - 보고서 명칭';


--
-- Name: COLUMN tb_rptp.anls_prd_expln; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.anls_prd_expln IS '분석기간 - 보고서 조회 조건이 되는 분석 기간';


--
-- Name: COLUMN tb_rptp.stts_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.stts_cd IS '상태코드 - 보고서 처리 상태 코드(REPORT_STATUS)';


--
-- Name: COLUMN tb_rptp.file_fmt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.file_fmt IS '파일형식 - 내보낼 때 고른 형식(FILE_FMT_CD: PDF|DOCX|XLSX). 직접 생성은 요청값을 담고, 자동 생성은 NULL 이다 — 설정에 형식 칸이 없어 고를 창구가 없다(TSK-4027 D14). 읽는 곳이 없는 기록용 칸이다(목록 화면에 표시되지 않고 재다운로드는 그때 고른다). XLSX 는 집중구역 보고서만 받는다(TSK-4338).';


--
-- Name: COLUMN tb_rptp.file_path; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.file_path IS '파일경로 - 생성된 보고서 파일 저장 경로';


--
-- Name: COLUMN tb_rptp.creatr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.creatr_id IS '생성자ID - 보고서를 생성한 사용자 ID(외부 회원 시스템 참조, FK 미설정)';


--
-- Name: COLUMN tb_rptp.crt_ymd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.crt_ymd IS '생성일자 - 보고서 생성 일자';


--
-- Name: COLUMN tb_rptp.creatr_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.creatr_nm IS '생성자명 - 화면 표시용 이름(사용자 마스터 도입 전 임시)';


--
-- Name: COLUMN tb_rptp.trgt_prd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.trgt_prd_cd IS '대상기간 - 자동 생성 보고서가 다루는 기간 식별자(2026-07 / 2026-Q2 / 2026-H1 / 2026). 직접 생성은 NULL(TSK-4026 D14)';


--
-- Name: COLUMN tb_rptp.crltn_dept_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.crltn_dept_cd IS '배포부서코드 - 생성 시점 스냅샷(TB_DEPT). 설정이 바뀌어도 과거 보고서 표기는 고정된다';


--
-- Name: COLUMN tb_rptp.crltn_cycl_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp.crltn_cycl_cd IS '배포주기코드 - 생성 시점 스냅샷(CRLTN_CYCL_CD). 위와 같은 이유';


--
-- Name: tb_rptp_auto_crt_stng; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_rptp_auto_crt_stng (
    stng_id bigint NOT NULL,
    crltn_cycl_cd character varying(30) NOT NULL,
    actvtn_yn boolean NOT NULL,
    creatr_id character varying(20) NOT NULL,
    crt_dt timestamp without time zone NOT NULL,
    rptp_type_cd character varying(30),
    crltn_dept_cd character varying(30),
    crltn_mm smallint,
    crltn_dd smallint,
    creatr_nm character varying(50),
    last_crt_dt timestamp without time zone,
    sgg_nm character varying(40),
    emd_cd character varying(8),
    del_dt timestamp without time zone,
    CONSTRAINT ck_rptp_stng_crltn_dd CHECK (((crltn_dd IS NULL) OR ((crltn_dd >= 1) AND (crltn_dd <= 28)))),
    CONSTRAINT ck_rptp_stng_crltn_mm CHECK (((crltn_mm IS NULL) OR ((crltn_mm >= 1) AND (crltn_mm <= 12))))
);


--
-- Name: TABLE tb_rptp_auto_crt_stng; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_rptp_auto_crt_stng IS '보고서자동생성설정 - 배포 주기·일자 기반 보고서 자동 생성 설정';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.stng_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.stng_id IS '설정아이디 - 자동생성 설정을 식별하는 고유 번호(PK, BIGINT 자동 증가. TSK-4026 D6)';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.crltn_cycl_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.crltn_cycl_cd IS '배포주기코드 - 보고서 배포 주기를 구분하는 코드(DIST_CYCLE)';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.actvtn_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.actvtn_yn IS '활성여부 - 자동생성 설정 활성화 여부';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.creatr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.creatr_id IS '생성자ID - 설정을 생성한 사용자 ID(외부 회원 시스템 참조, FK 미설정)';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.crt_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.crt_dt IS '생성일시 - 설정 생성 일시';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.rptp_type_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.rptp_type_cd IS '보고서종류코드 - 자동 생성할 보고서 종류. ANLS_TYPE_CD 중 etc1 IN (RPTP,BOTH) 인 값만 허용(TSK-4026 D13)';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.crltn_dept_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.crltn_dept_cd IS '배포부서코드 - 보고서를 배포할 부서(TB_DEPT)';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.crltn_mm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.crltn_mm IS '배포기준월 - 주기가 분기/반기/연간일 때의 기준 월(1~12). 월간이면 NULL';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.crltn_dd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.crltn_dd IS '배포기준일 - 배포 기준 일(1~28). 말일 편차를 피해 28 로 제한';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.creatr_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.creatr_nm IS '생성자명 - 화면 표시용 이름(사용자 마스터 도입 전 임시, TSK-4026 D5)';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.last_crt_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.last_crt_dt IS '최근생성일시 - 이 설정으로 보고서를 마지막으로 생성한 일시';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.sgg_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.sgg_nm IS '시군구명 - 보고서 대상 지역. NULL 이면 전체 지역(TSK-4026 D12)';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.emd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.emd_cd IS '읍면동코드 - 보고서 대상 행정동. NULL 이면 시군구 전체(행정구역 TB_ADMDST 참조)';


--
-- Name: COLUMN tb_rptp_auto_crt_stng.del_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_rptp_auto_crt_stng.del_dt IS '삭제일시 - NULL=존재, 값 있음=삭제됨(소프트 삭제). tb_pblpklt 의 extsh_dt 와 같은 규약';


--
-- Name: tb_rptp_auto_crt_stng_stng_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_rptp_auto_crt_stng ALTER COLUMN stng_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_rptp_auto_crt_stng_stng_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_rptp_rptp_id_seq; Type: SEQUENCE; Schema: parking; Owner: -
--

ALTER TABLE parking.tb_rptp ALTER COLUMN rptp_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME parking.tb_rptp_rptp_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tb_stdg_dong_mpng; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_stdg_dong_mpng (
    emd_cd character varying(8) NOT NULL,
    dong_nm character varying(40) NOT NULL,
    stdg_incl_rng character varying(10) NOT NULL,
    CONSTRAINT ck_tb_legdst_admdst_coverage CHECK (((stdg_incl_rng)::text = ANY ((ARRAY['FULL'::character varying, 'PARTIAL'::character varying])::text[])))
);


--
-- Name: TABLE tb_stdg_dong_mpng; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_stdg_dong_mpng IS '법정동행정동매핑 - 법정동(TB_ADMDST)을 행정동명으로 잇는 매핑. 동 지역만 대상(읍·면 제외)';


--
-- Name: COLUMN tb_stdg_dong_mpng.emd_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_stdg_dong_mpng.emd_cd IS '읍면동코드 - 법정동을 식별하는 8자리 코드(행정구역 TB_ADMDST 참조)';


--
-- Name: COLUMN tb_stdg_dong_mpng.dong_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_stdg_dong_mpng.dong_nm IS '행정동명 - 해당 법정동이 속하는 행정동 명칭(예: 일도1동). 원본에 코드가 없어 이름으로 식별한다';


--
-- Name: COLUMN tb_stdg_dong_mpng.stdg_incl_rng; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_stdg_dong_mpng.stdg_incl_rng IS '법정동포함범위 - FULL: 법정동 전체가 이 행정동에 속함 / PARTIAL: 여러 행정동에 나뉘어 속함';


--
-- Name: tb_user; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_user (
    user_id character varying(20) NOT NULL,
    cert_id character varying(100) NOT NULL,
    user_nm character varying(100) NOT NULL,
    user_eml_addr character varying(200),
    user_authrt_cd character varying(30) NOT NULL,
    last_lgn_dt timestamp without time zone,
    use_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    rgtr_id character varying(20) NOT NULL,
    reg_dt timestamp without time zone NOT NULL,
    mdfr_id character varying(20),
    mdfcn_dt timestamp without time zone,
    dept_cd character varying(30)
);


--
-- Name: TABLE tb_user; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_user IS '사용자 - OIDC 로 인증된 포털 사용자. 신원은 IdP(DHub2 Manager)가, 롤은 이 표가 소유한다.';


--
-- Name: COLUMN tb_user.user_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.user_id IS '사용자ID - 포털 내부 사용자 아이디(preferred_username 기반, 20자)';


--
-- Name: COLUMN tb_user.cert_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.cert_id IS '인증ID - IdP 가 발급한 불변 식별자(sub). 로그인 매칭의 정본';


--
-- Name: COLUMN tb_user.user_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.user_nm IS '사용자명 - 화면에 표시되는 이름';


--
-- Name: COLUMN tb_user.user_eml_addr; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.user_eml_addr IS '사용자이메일주소 - 알림 수신 등에 쓰는 이메일';


--
-- Name: COLUMN tb_user.user_authrt_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.user_authrt_cd IS '사용자권한코드 - TB_AUTHRT 참조. 메뉴 접근 권한 판정 기준';


--
-- Name: COLUMN tb_user.last_lgn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.last_lgn_dt IS '최종로그인일시 - 마지막 로그인 성공 일시';


--
-- Name: COLUMN tb_user.use_yn; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.use_yn IS '사용여부 - N 이면 로그인 차단(퇴직·정지)';


--
-- Name: COLUMN tb_user.rgtr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.rgtr_id IS '등록자ID - 등록자 회원 아이디';


--
-- Name: COLUMN tb_user.reg_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.reg_dt IS '등록일시 - 등록 일시';


--
-- Name: COLUMN tb_user.mdfr_id; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.mdfr_id IS '수정자ID - 수정자 회원 아이디';


--
-- Name: COLUMN tb_user.mdfcn_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.mdfcn_dt IS '수정일시 - 수정 일시';


--
-- Name: COLUMN tb_user.dept_cd; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_user.dept_cd IS '부서코드 - 소속 부서(TB_DEPT). IdP groups claim 이 정본';


--
-- Name: tb_zone_nm_cache; Type: TABLE; Schema: parking; Owner: -
--

CREATE TABLE parking.tb_zone_nm_cache (
    lat_key numeric(9,5) NOT NULL,
    lot_key numeric(10,5) NOT NULL,
    zone_nm character varying(100),
    zone_nm_src character varying(20) NOT NULL,
    poi_dist_m integer,
    inq_dt timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE tb_zone_nm_cache; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON TABLE parking.tb_zone_nm_cache IS '구역명 조회 캐시 - 군집 중심좌표 → VWorld POI/역지오코딩 결과. 같은 조회조건 반복 시 외부 호출을 없앤다 (TSK-4032 D17)';


--
-- Name: COLUMN tb_zone_nm_cache.lat_key; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_zone_nm_cache.lat_key IS '위도키 - 군집 중심 위도를 소수 5자리(약 1m)로 반올림한 값. PK 앞자리';


--
-- Name: COLUMN tb_zone_nm_cache.lot_key; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_zone_nm_cache.lot_key IS '경도키 - 군집 중심 경도를 소수 5자리(약 1m)로 반올림한 값. PK 뒷자리';


--
-- Name: COLUMN tb_zone_nm_cache.zone_nm; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_zone_nm_cache.zone_nm IS '구역명 - 화면에 그대로 쓰는 최종 이름(읍면동명 접두까지 붙인 100자 이내). 확정 실패(NOT_FOUND)면 NULL';


--
-- Name: COLUMN tb_zone_nm_cache.zone_nm_src; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_zone_nm_cache.zone_nm_src IS '구역명출처 - POI(VWorld 장소 최근접) / REVERSE_GEOCODE(VWorld 역지오코딩) / NOT_FOUND(둘 다 결과 없음, 재호출 차단용)';


--
-- Name: COLUMN tb_zone_nm_cache.poi_dist_m; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_zone_nm_cache.poi_dist_m IS '장소거리 - POI 채택 시 군집 중심점까지의 거리(m). 채택 상한 150m 를 넘는 후보는 저장되지 않는다. POI 외 출처는 NULL';


--
-- Name: COLUMN tb_zone_nm_cache.inq_dt; Type: COMMENT; Schema: parking; Owner: -
--

COMMENT ON COLUMN parking.tb_zone_nm_cache.inq_dt IS '조회일시 - 외부 API 로 이름을 확정한 시각. 재조회 시 갱신된다';


--
-- Name: v_pklt_sfty_chck_latest; Type: VIEW; Schema: parking; Owner: -
--

CREATE VIEW parking.v_pklt_sfty_chck_latest AS
 SELECT DISTINCT ON (tb_pklt_sfty_chck.pklt_id, tb_pklt_sfty_chck.chck_ymd, tb_pklt_sfty_chck.sfty_chck_type_cd) tb_pklt_sfty_chck.sfty_chck_id,
    tb_pklt_sfty_chck.pklt_id,
    tb_pklt_sfty_chck.chck_ymd,
    tb_pklt_sfty_chck.chck_prd_cd,
    tb_pklt_sfty_chck.sfty_chck_type_cd,
    tb_pklt_sfty_chck.sfty_chck_rslt_cd,
    tb_pklt_sfty_chck.rmrk_cn,
    tb_pklt_sfty_chck.rgtr_id,
    tb_pklt_sfty_chck.reg_dt,
    tb_pklt_sfty_chck.load_dt,
    tb_pklt_sfty_chck.btch_excn_id,
    tb_pklt_sfty_chck.src_hash,
    tb_pklt_sfty_chck.src_json
   FROM parking.tb_pklt_sfty_chck
  WHERE ((tb_pklt_sfty_chck.chck_ymd IS NOT NULL) AND (tb_pklt_sfty_chck.sfty_chck_type_cd IS NOT NULL) AND ((tb_pklt_sfty_chck.sfty_chck_rslt_cd)::text = ANY ((ARRAY['A'::character varying, 'B'::character varying, 'C'::character varying])::text[])))
  ORDER BY tb_pklt_sfty_chck.pklt_id, tb_pklt_sfty_chck.chck_ymd, tb_pklt_sfty_chck.sfty_chck_type_cd, tb_pklt_sfty_chck.load_dt DESC NULLS LAST, tb_pklt_sfty_chck.sfty_chck_id DESC;


--
-- Name: v_pklt_utztn_pttn_latest; Type: VIEW; Schema: parking; Owner: -
--

CREATE VIEW parking.v_pklt_utztn_pttn_latest AS
 SELECT DISTINCT ON (tb_pklt_utztn_pttn.pklt_id, tb_pklt_utztn_pttn.tot_ym) tb_pklt_utztn_pttn.pklt_id,
    tb_pklt_utztn_pttn.tot_ymd,
    tb_pklt_utztn_pttn.pcshr,
    tb_pklt_utztn_pttn.trnvr_cnt,
    tb_pklt_utztn_pttn.prk_cntom,
    tb_pklt_utztn_pttn.tot_ym,
    tb_pklt_utztn_pttn.day_avg_trnvr_rt,
    tb_pklt_utztn_pttn.utztn_stts_id,
    tb_pklt_utztn_pttn.load_dt,
    tb_pklt_utztn_pttn.btch_excn_id,
    tb_pklt_utztn_pttn.src_hash,
    tb_pklt_utztn_pttn.src_json
   FROM parking.tb_pklt_utztn_pttn
  WHERE (tb_pklt_utztn_pttn.tot_ym IS NOT NULL)
  ORDER BY tb_pklt_utztn_pttn.pklt_id, tb_pklt_utztn_pttn.tot_ym, tb_pklt_utztn_pttn.load_dt DESC NULLS LAST, tb_pklt_utztn_pttn.utztn_stts_id DESC;


--
-- Name: tb_pblpklt_addr_anomaly id; Type: DEFAULT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.tb_pblpklt_addr_anomaly ALTER COLUMN id SET DEFAULT nextval('batch.tb_pblpklt_addr_anomaly_id_seq'::regclass);


--
-- Name: tb_crdn_dsctn crdn_id; Type: DEFAULT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_crdn_dsctn ALTER COLUMN crdn_id SET DEFAULT nextval('parking.tb_crdn_dsctn_crdn_id_seq'::regclass);


--
-- Name: tb_cvlcpt cvlcpt_id; Type: DEFAULT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_cvlcpt ALTER COLUMN cvlcpt_id SET DEFAULT (nextval('parking.tb_cvlcpt_cvlcpt_id_seq'::regclass))::text;


--
-- Name: tb_pklt_acdnt_hstry acdnt_id; Type: DEFAULT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_pklt_acdnt_hstry ALTER COLUMN acdnt_id SET DEFAULT (nextval('parking.tb_pklt_acdnt_hstry_acdnt_id_seq'::regclass))::text;


--
-- Name: batch_job_execution_context batch_job_execution_context_pkey; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_job_execution_context
    ADD CONSTRAINT batch_job_execution_context_pkey PRIMARY KEY (job_execution_id);


--
-- Name: batch_job_execution batch_job_execution_pkey; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_job_execution
    ADD CONSTRAINT batch_job_execution_pkey PRIMARY KEY (job_execution_id);


--
-- Name: batch_job_instance batch_job_instance_pkey; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_job_instance
    ADD CONSTRAINT batch_job_instance_pkey PRIMARY KEY (job_instance_id);


--
-- Name: batch_step_execution_context batch_step_execution_context_pkey; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_step_execution_context
    ADD CONSTRAINT batch_step_execution_context_pkey PRIMARY KEY (step_execution_id);


--
-- Name: batch_step_execution batch_step_execution_pkey; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_step_execution
    ADD CONSTRAINT batch_step_execution_pkey PRIMARY KEY (step_execution_id);


--
-- Name: batch_job_instance job_inst_un; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_job_instance
    ADD CONSTRAINT job_inst_un UNIQUE (job_name, job_key);


--
-- Name: tb_btch_schdl pk_tb_btch_schdl; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.tb_btch_schdl
    ADD CONSTRAINT pk_tb_btch_schdl PRIMARY KEY (job_nm);


--
-- Name: tb_geocode_skip pk_tb_geocode_skip; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.tb_geocode_skip
    ADD CONSTRAINT pk_tb_geocode_skip PRIMARY KEY (src_se, trgt_id);


--
-- Name: tb_job_skip pk_tb_job_skip; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.tb_job_skip
    ADD CONSTRAINT pk_tb_job_skip PRIMARY KEY (job_se, trgt_id, trgt_de);


--
-- Name: tb_pklt_utztn_mtch pk_tb_pklt_utztn_mtch; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.tb_pklt_utztn_mtch
    ADD CONSTRAINT pk_tb_pklt_utztn_mtch PRIMARY KEY (src_pklt_nm, src_pklt_addr);


--
-- Name: tb_btch_excn_hstry tb_batch_run_pkey; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.tb_btch_excn_hstry
    ADD CONSTRAINT tb_batch_run_pkey PRIMARY KEY (job_execution_id);


--
-- Name: tb_geocode_cache tb_geocode_cache_pkey; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.tb_geocode_cache
    ADD CONSTRAINT tb_geocode_cache_pkey PRIMARY KEY (addr);


--
-- Name: tb_pblpklt_addr_anomaly tb_pblpklt_addr_anomaly_pkey; Type: CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.tb_pblpklt_addr_anomaly
    ADD CONSTRAINT tb_pblpklt_addr_anomaly_pkey PRIMARY KEY (id);


--
-- Name: tb_admdst pk_tb_admdst; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_admdst
    ADD CONSTRAINT pk_tb_admdst PRIMARY KEY (emd_cd);


--
-- Name: tb_ai_conv_msg pk_tb_ai_conv_msg; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_ai_conv_msg
    ADD CONSTRAINT pk_tb_ai_conv_msg PRIMARY KEY (msg_id);


--
-- Name: tb_ai_conv_sesn pk_tb_ai_conv_sesn; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_ai_conv_sesn
    ADD CONSTRAINT pk_tb_ai_conv_sesn PRIMARY KEY (sesn_id);


--
-- Name: tb_ai_dgstfn_srvy pk_tb_ai_dgstfn_srvy; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_ai_dgstfn_srvy
    ADD CONSTRAINT pk_tb_ai_dgstfn_srvy PRIMARY KEY (srvy_sn);


--
-- Name: tb_ai_rptp_cn pk_tb_ai_rptp_cn; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_ai_rptp_cn
    ADD CONSTRAINT pk_tb_ai_rptp_cn PRIMARY KEY (rptp_id);


--
-- Name: tb_anls_excn pk_tb_anls_excn; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_anls_excn
    ADD CONSTRAINT pk_tb_anls_excn PRIMARY KEY (excn_id);


--
-- Name: tb_anls_rslt pk_tb_anls_rslt; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_anls_rslt
    ADD CONSTRAINT pk_tb_anls_rslt PRIMARY KEY (rslt_id);


--
-- Name: tb_anls_vrbl_hstry pk_tb_anls_vrbl_hstry; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_anls_vrbl_hstry
    ADD CONSTRAINT pk_tb_anls_vrbl_hstry PRIMARY KEY (chg_hstry_sn);


--
-- Name: tb_anls_vrbl_stng pk_tb_anls_vrbl_stng; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_anls_vrbl_stng
    ADD CONSTRAINT pk_tb_anls_vrbl_stng PRIMARY KEY (anls_type_cd, vrbl_cd);


--
-- Name: tb_authrt pk_tb_authrt; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_authrt
    ADD CONSTRAINT pk_tb_authrt PRIMARY KEY (authrt_cd);


--
-- Name: tb_authrt_menu pk_tb_authrt_menu; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_authrt_menu
    ADD CONSTRAINT pk_tb_authrt_menu PRIMARY KEY (authrt_cd, menu_id);


--
-- Name: tb_cd_group pk_tb_cd_group; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_cd_group
    ADD CONSTRAINT pk_tb_cd_group PRIMARY KEY (group_cd);


--
-- Name: tb_com_cd pk_tb_com_cd; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_com_cd
    ADD CONSTRAINT pk_tb_com_cd PRIMARY KEY (group_cd, cd_vl);


--
-- Name: tb_crdn_dsctn pk_tb_crdn_dsctn; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_crdn_dsctn
    ADD CONSTRAINT pk_tb_crdn_dsctn PRIMARY KEY (crdn_id);


--
-- Name: tb_cvlcpt pk_tb_cvlcpt; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_cvlcpt
    ADD CONSTRAINT pk_tb_cvlcpt PRIMARY KEY (cvlcpt_id);


--
-- Name: tb_dept pk_tb_dept; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_dept
    ADD CONSTRAINT pk_tb_dept PRIMARY KEY (dept_cd);


--
-- Name: tb_faq pk_tb_faq; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_faq
    ADD CONSTRAINT pk_tb_faq PRIMARY KEY (faq_id);


--
-- Name: tb_grid pk_tb_grid; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_grid
    ADD CONSTRAINT pk_tb_grid PRIMARY KEY (grid_sz, grid_cd, sgg_cd);


--
-- Name: tb_stdg_dong_mpng pk_tb_legdst_admdst; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_stdg_dong_mpng
    ADD CONSTRAINT pk_tb_legdst_admdst PRIMARY KEY (emd_cd, dong_nm);


--
-- Name: tb_lgn_hstry pk_tb_lgn_hstry; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_lgn_hstry
    ADD CONSTRAINT pk_tb_lgn_hstry PRIMARY KEY (lgn_sn);


--
-- Name: tb_menu pk_tb_menu; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_menu
    ADD CONSTRAINT pk_tb_menu PRIMARY KEY (menu_id);


--
-- Name: tb_noti_hstry_rcvr pk_tb_noti_hstry_rcvr; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_hstry_rcvr
    ADD CONSTRAINT pk_tb_noti_hstry_rcvr PRIMARY KEY (noti_id, rcvr_id);


--
-- Name: tb_noti_rcvr pk_tb_noti_rcvr; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_rcvr
    ADD CONSTRAINT pk_tb_noti_rcvr PRIMARY KEY (rcvr_id);


--
-- Name: tb_noti_rcvr_lvl pk_tb_noti_rcvr_lvl; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_rcvr_lvl
    ADD CONSTRAINT pk_tb_noti_rcvr_lvl PRIMARY KEY (rcvr_id, risk_lvl_cd);


--
-- Name: tb_noti_rcvr_rgn pk_tb_noti_rcvr_rgn; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_rcvr_rgn
    ADD CONSTRAINT pk_tb_noti_rcvr_rgn PRIMARY KEY (rgn_sn);


--
-- Name: tb_pblpklt pk_tb_pblpklt; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_pblpklt
    ADD CONSTRAINT pk_tb_pblpklt PRIMARY KEY (pklt_id);


--
-- Name: tb_pklt_acdnt_hstry pk_tb_pklt_acdnt_hstry; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_pklt_acdnt_hstry
    ADD CONSTRAINT pk_tb_pklt_acdnt_hstry PRIMARY KEY (acdnt_id);


--
-- Name: tb_pklt_sfty_chck pk_tb_pklt_sfty_chck; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_pklt_sfty_chck
    ADD CONSTRAINT pk_tb_pklt_sfty_chck PRIMARY KEY (sfty_chck_id);


--
-- Name: tb_pklt_utztn_pttn pk_tb_pklt_utztn_pttn; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_pklt_utztn_pttn
    ADD CONSTRAINT pk_tb_pklt_utztn_pttn PRIMARY KEY (utztn_stts_id);


--
-- Name: tb_plcy_smlt_rslt pk_tb_plcy_smlt_rslt; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_plcy_smlt_rslt
    ADD CONSTRAINT pk_tb_plcy_smlt_rslt PRIMARY KEY (rslt_id);


--
-- Name: tb_risk_stp_noti_crtr pk_tb_risk_stp_noti_crtr; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_risk_stp_noti_crtr
    ADD CONSTRAINT pk_tb_risk_stp_noti_crtr PRIMARY KEY (thrsh_id);


--
-- Name: tb_risk_stp_noti_hstry pk_tb_risk_stp_noti_hstry; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_risk_stp_noti_hstry
    ADD CONSTRAINT pk_tb_risk_stp_noti_hstry PRIMARY KEY (noti_id);


--
-- Name: tb_rptp pk_tb_rptp; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_rptp
    ADD CONSTRAINT pk_tb_rptp PRIMARY KEY (rptp_id);


--
-- Name: tb_rptp_auto_crt_stng pk_tb_rptp_auto_crt_stng; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_rptp_auto_crt_stng
    ADD CONSTRAINT pk_tb_rptp_auto_crt_stng PRIMARY KEY (stng_id);


--
-- Name: tb_user pk_tb_user; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_user
    ADD CONSTRAINT pk_tb_user PRIMARY KEY (user_id);


--
-- Name: tb_zone_nm_cache pk_tb_zone_nm_cache; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_zone_nm_cache
    ADD CONSTRAINT pk_tb_zone_nm_cache PRIMARY KEY (lat_key, lot_key);


--
-- Name: tb_crdn_dsctn uk_tb_crdn_dsctn_src_hash; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_crdn_dsctn
    ADD CONSTRAINT uk_tb_crdn_dsctn_src_hash UNIQUE (src_hash);


--
-- Name: tb_cvlcpt uk_tb_cvlcpt_src_hash; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_cvlcpt
    ADD CONSTRAINT uk_tb_cvlcpt_src_hash UNIQUE (src_hash);


--
-- Name: tb_pklt_acdnt_hstry uk_tb_pklt_acdnt_hstry_src_hash; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_pklt_acdnt_hstry
    ADD CONSTRAINT uk_tb_pklt_acdnt_hstry_src_hash UNIQUE (src_hash);


--
-- Name: tb_risk_stp_noti_crtr uq_risk_crtr_lvl; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_risk_stp_noti_crtr
    ADD CONSTRAINT uq_risk_crtr_lvl UNIQUE (risk_lvl_cd);


--
-- Name: tb_risk_stp_noti_hstry uq_risk_hstry_emd_ocrn; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_risk_stp_noti_hstry
    ADD CONSTRAINT uq_risk_hstry_emd_ocrn UNIQUE (emd_cd, ocrn_dt);


--
-- Name: tb_rptp uq_rptp_stng_prd; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_rptp
    ADD CONSTRAINT uq_rptp_stng_prd UNIQUE (stng_id, trgt_prd_cd);


--
-- Name: tb_ai_rptp_cn uq_tb_ai_rptp_cn_src; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_ai_rptp_cn
    ADD CONSTRAINT uq_tb_ai_rptp_cn_src UNIQUE (user_id, sesn_id, msg_id, rptp_seq);


--
-- Name: tb_anls_rslt uq_tb_anls_rslt_excn; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_anls_rslt
    ADD CONSTRAINT uq_tb_anls_rslt_excn UNIQUE (excn_id);


--
-- Name: tb_noti_rcvr_rgn uq_tb_noti_rcvr_rgn; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_rcvr_rgn
    ADD CONSTRAINT uq_tb_noti_rcvr_rgn UNIQUE NULLS NOT DISTINCT (rcvr_id, sgg_nm, emd_cd);


--
-- Name: tb_user uq_tb_user_cert_id; Type: CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_user
    ADD CONSTRAINT uq_tb_user_cert_id UNIQUE (cert_id);


--
-- Name: ix_tb_geocode_skip_rsn; Type: INDEX; Schema: batch; Owner: -
--

CREATE INDEX ix_tb_geocode_skip_rsn ON batch.tb_geocode_skip USING btree (src_se, fail_rsn);


--
-- Name: ix_tb_job_skip_rsn; Type: INDEX; Schema: batch; Owner: -
--

CREATE INDEX ix_tb_job_skip_rsn ON batch.tb_job_skip USING btree (job_se, fail_rsn);


--
-- Name: ix_tb_pblpklt_addr_anomaly_pklt_id; Type: INDEX; Schema: batch; Owner: -
--

CREATE INDEX ix_tb_pblpklt_addr_anomaly_pklt_id ON batch.tb_pblpklt_addr_anomaly USING btree (pklt_id);


--
-- Name: ux_pblpklt_addr_anomaly_uk; Type: INDEX; Schema: batch; Owner: -
--

CREATE UNIQUE INDEX ux_pblpklt_addr_anomaly_uk ON batch.tb_pblpklt_addr_anomaly USING btree (pklt_id, orgnl_addr, anomaly_type);


--
-- Name: INDEX ux_pblpklt_addr_anomaly_uk; Type: COMMENT; Schema: batch; Owner: -
--

COMMENT ON INDEX batch.ux_pblpklt_addr_anomaly_uk IS 'anomaly 멱등성 - 동일 (pklt_id, orgnl_addr, anomaly_type) 반복 INSERT 차단. anomaly_type 이 다르면 여러 건 허용';


--
-- Name: idx_tb_admdst_bndry_geom; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX idx_tb_admdst_bndry_geom ON parking.tb_admdst USING gist (bndry_geom);


--
-- Name: idx_tb_grid_bndry_geom; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX idx_tb_grid_bndry_geom ON parking.tb_grid USING gist (bndry_geom);


--
-- Name: idx_tb_legdst_admdst_hjd_nm; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX idx_tb_legdst_admdst_hjd_nm ON parking.tb_stdg_dong_mpng USING btree (dong_nm);


--
-- Name: ix_noti_hstry_rcvr_unread; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_noti_hstry_rcvr_unread ON parking.tb_noti_hstry_rcvr USING btree (rcvr_id, prsl_yn);


--
-- Name: ix_pklt_utztn_latest; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_pklt_utztn_latest ON parking.tb_pklt_utztn_pttn USING btree (pklt_id, tot_ym, load_dt DESC, utztn_stts_id DESC);


--
-- Name: ix_plcy_rslt_excn; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_plcy_rslt_excn ON parking.tb_plcy_smlt_rslt USING btree (excn_id);


--
-- Name: ix_risk_hstry_emd_cd; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_risk_hstry_emd_cd ON parking.tb_risk_stp_noti_hstry USING btree (emd_cd);


--
-- Name: ix_risk_hstry_ocrn_dt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_risk_hstry_ocrn_dt ON parking.tb_risk_stp_noti_hstry USING btree (ocrn_dt DESC);


--
-- Name: ix_rptp_crt_type; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_rptp_crt_type ON parking.tb_rptp USING btree (crt_type, crt_ymd DESC);


--
-- Name: ix_rptp_stng_alive; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_rptp_stng_alive ON parking.tb_rptp_auto_crt_stng USING btree (crt_dt DESC) WHERE (del_dt IS NULL);


--
-- Name: ix_tb_ai_dgstfn_srvy_user_reg; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_ai_dgstfn_srvy_user_reg ON parking.tb_ai_dgstfn_srvy USING btree (user_id, reg_dt DESC);


--
-- Name: ix_tb_anls_vrbl_hstry_chg_dt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_anls_vrbl_hstry_chg_dt ON parking.tb_anls_vrbl_hstry USING btree (chg_dt DESC);


--
-- Name: ix_tb_crdn_dsctn_addr_nocoord; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_crdn_dsctn_addr_nocoord ON parking.tb_crdn_dsctn USING btree (crdn_addr) WHERE (lat IS NULL);


--
-- Name: ix_tb_crdn_dsctn_cvlcpt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_crdn_dsctn_cvlcpt ON parking.tb_crdn_dsctn USING btree (cvlcpt_id) WHERE (cvlcpt_id IS NOT NULL);


--
-- Name: ix_tb_crdn_dsctn_dt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_crdn_dsctn_dt ON parking.tb_crdn_dsctn USING btree (crdn_dt);


--
-- Name: ix_tb_crdn_dsctn_emd_dt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_crdn_dsctn_emd_dt ON parking.tb_crdn_dsctn USING btree (emd_cd, crdn_dt);


--
-- Name: ix_tb_crdn_dsctn_geom; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_crdn_dsctn_geom ON parking.tb_crdn_dsctn USING gist (geom);


--
-- Name: ix_tb_crdn_dsctn_load_dt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_crdn_dsctn_load_dt ON parking.tb_crdn_dsctn USING btree (load_dt);


--
-- Name: ix_tb_crdn_dsctn_origin_pk; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_crdn_dsctn_origin_pk ON parking.tb_crdn_dsctn USING btree (origin_pk);


--
-- Name: ix_tb_cvlcpt_dclr_ymd; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_cvlcpt_dclr_ymd ON parking.tb_cvlcpt USING btree (dclr_ymd);


--
-- Name: ix_tb_cvlcpt_emd_ocrn; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_cvlcpt_emd_ocrn ON parking.tb_cvlcpt USING btree (emd_cd, ocrn_dt);


--
-- Name: ix_tb_cvlcpt_geom; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_cvlcpt_geom ON parking.tb_cvlcpt USING gist (geom);


--
-- Name: ix_tb_cvlcpt_load_dt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_cvlcpt_load_dt ON parking.tb_cvlcpt USING btree (load_dt);


--
-- Name: ix_tb_cvlcpt_ocrn_dt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_cvlcpt_ocrn_dt ON parking.tb_cvlcpt USING btree (ocrn_dt);


--
-- Name: ix_tb_cvlcpt_origin_pk; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_cvlcpt_origin_pk ON parking.tb_cvlcpt USING btree (origin_pk);


--
-- Name: ix_tb_cvlcpt_type_ocrn; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_cvlcpt_type_ocrn ON parking.tb_cvlcpt USING btree (cvlcpt_type_cd, ocrn_dt);


--
-- Name: ix_tb_lgn_hstry_dt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_lgn_hstry_dt ON parking.tb_lgn_hstry USING btree (lgn_dt DESC);


--
-- Name: ix_tb_lgn_hstry_user_dt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_lgn_hstry_user_dt ON parking.tb_lgn_hstry USING btree (user_id, lgn_dt DESC);


--
-- Name: ix_tb_pblpklt_addr; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_pblpklt_addr ON parking.tb_pblpklt USING btree (pklt_addr);


--
-- Name: ix_tb_pblpklt_alive; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_pblpklt_alive ON parking.tb_pblpklt USING btree (emd_cd) WHERE (extsh_dt IS NULL);


--
-- Name: ix_tb_pblpklt_origin_pk; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_pblpklt_origin_pk ON parking.tb_pblpklt USING btree (origin_pk);


--
-- Name: ix_tb_pklt_acdnt_hstry_acdnt_dt; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_pklt_acdnt_hstry_acdnt_dt ON parking.tb_pklt_acdnt_hstry USING btree (acdnt_dt);


--
-- Name: ix_tb_pklt_sfty_chck_pklt_ymd; Type: INDEX; Schema: parking; Owner: -
--

CREATE INDEX ix_tb_pklt_sfty_chck_pklt_ymd ON parking.tb_pklt_sfty_chck USING btree (pklt_id, chck_ymd DESC);


--
-- Name: uq_pklt_sfty_observation; Type: INDEX; Schema: parking; Owner: -
--

CREATE UNIQUE INDEX uq_pklt_sfty_observation ON parking.tb_pklt_sfty_chck USING btree (btch_excn_id, src_hash);


--
-- Name: uq_pklt_utztn_observation; Type: INDEX; Schema: parking; Owner: -
--

CREATE UNIQUE INDEX uq_pklt_utztn_observation ON parking.tb_pklt_utztn_pttn USING btree (btch_excn_id, src_hash);


--
-- Name: uq_tb_noti_rcvr_user; Type: INDEX; Schema: parking; Owner: -
--

CREATE UNIQUE INDEX uq_tb_noti_rcvr_user ON parking.tb_noti_rcvr USING btree (user_id) WHERE (del_yn = 'N'::bpchar);


--
-- Name: batch_job_execution_context job_exec_ctx_fk; Type: FK CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_job_execution_context
    ADD CONSTRAINT job_exec_ctx_fk FOREIGN KEY (job_execution_id) REFERENCES batch.batch_job_execution(job_execution_id);


--
-- Name: batch_job_execution_params job_exec_params_fk; Type: FK CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_job_execution_params
    ADD CONSTRAINT job_exec_params_fk FOREIGN KEY (job_execution_id) REFERENCES batch.batch_job_execution(job_execution_id);


--
-- Name: batch_step_execution job_exec_step_fk; Type: FK CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_step_execution
    ADD CONSTRAINT job_exec_step_fk FOREIGN KEY (job_execution_id) REFERENCES batch.batch_job_execution(job_execution_id);


--
-- Name: batch_job_execution job_inst_exec_fk; Type: FK CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_job_execution
    ADD CONSTRAINT job_inst_exec_fk FOREIGN KEY (job_instance_id) REFERENCES batch.batch_job_instance(job_instance_id);


--
-- Name: batch_step_execution_context step_exec_ctx_fk; Type: FK CONSTRAINT; Schema: batch; Owner: -
--

ALTER TABLE ONLY batch.batch_step_execution_context
    ADD CONSTRAINT step_exec_ctx_fk FOREIGN KEY (step_execution_id) REFERENCES batch.batch_step_execution(step_execution_id);


--
-- Name: tb_ai_conv_msg fk_tb_ai_conv_msg_sesn_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_ai_conv_msg
    ADD CONSTRAINT fk_tb_ai_conv_msg_sesn_id FOREIGN KEY (sesn_id) REFERENCES parking.tb_ai_conv_sesn(sesn_id);


--
-- Name: tb_ai_rptp_cn fk_tb_ai_rptp_cn_rptp; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_ai_rptp_cn
    ADD CONSTRAINT fk_tb_ai_rptp_cn_rptp FOREIGN KEY (rptp_id) REFERENCES parking.tb_rptp(rptp_id) ON DELETE CASCADE;


--
-- Name: tb_anls_excn fk_tb_anls_excn_emd_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_anls_excn
    ADD CONSTRAINT fk_tb_anls_excn_emd_cd FOREIGN KEY (emd_cd) REFERENCES parking.tb_admdst(emd_cd);


--
-- Name: tb_anls_rslt fk_tb_anls_rslt_excn_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_anls_rslt
    ADD CONSTRAINT fk_tb_anls_rslt_excn_id FOREIGN KEY (excn_id) REFERENCES parking.tb_anls_excn(excn_id);


--
-- Name: tb_authrt_menu fk_tb_authrt_menu_authrt_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_authrt_menu
    ADD CONSTRAINT fk_tb_authrt_menu_authrt_cd FOREIGN KEY (authrt_cd) REFERENCES parking.tb_authrt(authrt_cd);


--
-- Name: tb_authrt_menu fk_tb_authrt_menu_menu_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_authrt_menu
    ADD CONSTRAINT fk_tb_authrt_menu_menu_id FOREIGN KEY (menu_id) REFERENCES parking.tb_menu(menu_id);


--
-- Name: tb_com_cd fk_tb_com_cd_group_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_com_cd
    ADD CONSTRAINT fk_tb_com_cd_group_cd FOREIGN KEY (group_cd) REFERENCES parking.tb_cd_group(group_cd);


--
-- Name: tb_crdn_dsctn fk_tb_crdn_dsctn_cvlcpt_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_crdn_dsctn
    ADD CONSTRAINT fk_tb_crdn_dsctn_cvlcpt_id FOREIGN KEY (cvlcpt_id) REFERENCES parking.tb_cvlcpt(cvlcpt_id);


--
-- Name: tb_crdn_dsctn fk_tb_crdn_dsctn_emd_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_crdn_dsctn
    ADD CONSTRAINT fk_tb_crdn_dsctn_emd_cd FOREIGN KEY (emd_cd) REFERENCES parking.tb_admdst(emd_cd);


--
-- Name: tb_cvlcpt fk_tb_cvlcpt_emd_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_cvlcpt
    ADD CONSTRAINT fk_tb_cvlcpt_emd_cd FOREIGN KEY (emd_cd) REFERENCES parking.tb_admdst(emd_cd);


--
-- Name: tb_stdg_dong_mpng fk_tb_legdst_admdst_emd_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_stdg_dong_mpng
    ADD CONSTRAINT fk_tb_legdst_admdst_emd_cd FOREIGN KEY (emd_cd) REFERENCES parking.tb_admdst(emd_cd);


--
-- Name: tb_menu fk_tb_menu_up_menu_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_menu
    ADD CONSTRAINT fk_tb_menu_up_menu_id FOREIGN KEY (up_menu_id) REFERENCES parking.tb_menu(menu_id);


--
-- Name: tb_noti_hstry_rcvr fk_tb_noti_hstry_rcvr_noti; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_hstry_rcvr
    ADD CONSTRAINT fk_tb_noti_hstry_rcvr_noti FOREIGN KEY (noti_id) REFERENCES parking.tb_risk_stp_noti_hstry(noti_id) ON DELETE CASCADE;


--
-- Name: tb_noti_hstry_rcvr fk_tb_noti_hstry_rcvr_rcvr; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_hstry_rcvr
    ADD CONSTRAINT fk_tb_noti_hstry_rcvr_rcvr FOREIGN KEY (rcvr_id) REFERENCES parking.tb_noti_rcvr(rcvr_id);


--
-- Name: tb_noti_rcvr fk_tb_noti_rcvr_emd_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_rcvr
    ADD CONSTRAINT fk_tb_noti_rcvr_emd_cd FOREIGN KEY (emd_cd) REFERENCES parking.tb_admdst(emd_cd);


--
-- Name: tb_noti_rcvr_lvl fk_tb_noti_rcvr_lvl_rcvr; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_rcvr_lvl
    ADD CONSTRAINT fk_tb_noti_rcvr_lvl_rcvr FOREIGN KEY (rcvr_id) REFERENCES parking.tb_noti_rcvr(rcvr_id) ON DELETE CASCADE;


--
-- Name: tb_noti_rcvr_rgn fk_tb_noti_rcvr_rgn_emd_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_rcvr_rgn
    ADD CONSTRAINT fk_tb_noti_rcvr_rgn_emd_cd FOREIGN KEY (emd_cd) REFERENCES parking.tb_admdst(emd_cd);


--
-- Name: tb_noti_rcvr_rgn fk_tb_noti_rcvr_rgn_rcvr; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_noti_rcvr_rgn
    ADD CONSTRAINT fk_tb_noti_rcvr_rgn_rcvr FOREIGN KEY (rcvr_id) REFERENCES parking.tb_noti_rcvr(rcvr_id) ON DELETE CASCADE;


--
-- Name: tb_pblpklt fk_tb_pblpklt_emd_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_pblpklt
    ADD CONSTRAINT fk_tb_pblpklt_emd_cd FOREIGN KEY (emd_cd) REFERENCES parking.tb_admdst(emd_cd);


--
-- Name: tb_pklt_acdnt_hstry fk_tb_pklt_acdnt_hstry_pklt_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_pklt_acdnt_hstry
    ADD CONSTRAINT fk_tb_pklt_acdnt_hstry_pklt_id FOREIGN KEY (pklt_id) REFERENCES parking.tb_pblpklt(pklt_id);


--
-- Name: tb_pklt_sfty_chck fk_tb_pklt_sfty_chck_pklt_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_pklt_sfty_chck
    ADD CONSTRAINT fk_tb_pklt_sfty_chck_pklt_id FOREIGN KEY (pklt_id) REFERENCES parking.tb_pblpklt(pklt_id);


--
-- Name: tb_pklt_utztn_pttn fk_tb_pklt_utztn_pttn_pklt_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_pklt_utztn_pttn
    ADD CONSTRAINT fk_tb_pklt_utztn_pttn_pklt_id FOREIGN KEY (pklt_id) REFERENCES parking.tb_pblpklt(pklt_id);


--
-- Name: tb_plcy_smlt_rslt fk_tb_plcy_smlt_rslt_excn_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_plcy_smlt_rslt
    ADD CONSTRAINT fk_tb_plcy_smlt_rslt_excn_id FOREIGN KEY (excn_id) REFERENCES parking.tb_anls_excn(excn_id);


--
-- Name: tb_risk_stp_noti_hstry fk_tb_risk_stp_noti_hstry_emd_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_risk_stp_noti_hstry
    ADD CONSTRAINT fk_tb_risk_stp_noti_hstry_emd_cd FOREIGN KEY (emd_cd) REFERENCES parking.tb_admdst(emd_cd);


--
-- Name: tb_rptp fk_tb_rptp_excn_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_rptp
    ADD CONSTRAINT fk_tb_rptp_excn_id FOREIGN KEY (excn_id) REFERENCES parking.tb_anls_excn(excn_id);


--
-- Name: tb_rptp_auto_crt_stng fk_tb_rptp_stng_emd_cd; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_rptp_auto_crt_stng
    ADD CONSTRAINT fk_tb_rptp_stng_emd_cd FOREIGN KEY (emd_cd) REFERENCES parking.tb_admdst(emd_cd);


--
-- Name: tb_rptp fk_tb_rptp_stng_id; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_rptp
    ADD CONSTRAINT fk_tb_rptp_stng_id FOREIGN KEY (stng_id) REFERENCES parking.tb_rptp_auto_crt_stng(stng_id);


--
-- Name: tb_user fk_tb_user_authrt; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_user
    ADD CONSTRAINT fk_tb_user_authrt FOREIGN KEY (user_authrt_cd) REFERENCES parking.tb_authrt(authrt_cd);


--
-- Name: tb_user fk_tb_user_dept; Type: FK CONSTRAINT; Schema: parking; Owner: -
--

ALTER TABLE ONLY parking.tb_user
    ADD CONSTRAINT fk_tb_user_dept FOREIGN KEY (dept_cd) REFERENCES parking.tb_dept(dept_cd);


--
-- PostgreSQL database dump complete
--

