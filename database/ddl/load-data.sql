-- 제주 데이터허브 기준 데이터 적재 (2026-11-18, 마이그레이션 118건 기준)
-- 순서: jeju-datahub-schema.sql 실행 → 이 파일 실행. data/ 폴더가 있는 위치(ddl/)에서 실행한다.
--   psql -h <호스트> -U <계정> -d <DB> -f load-data.sql
\set ON_ERROR_STOP on
BEGIN;
\copy parking.tb_cd_group FROM 'data/01-parking.tb_cd_group.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy parking.tb_com_cd FROM 'data/02-parking.tb_com_cd.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy parking.tb_authrt FROM 'data/03-parking.tb_authrt.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy parking.tb_menu FROM 'data/04-parking.tb_menu.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy parking.tb_authrt_menu FROM 'data/05-parking.tb_authrt_menu.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy parking.tb_anls_vrbl_stng FROM 'data/06-parking.tb_anls_vrbl_stng.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy parking.tb_risk_stp_noti_crtr FROM 'data/07-parking.tb_risk_stp_noti_crtr.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy parking.tb_admdst FROM 'data/08-parking.tb_admdst.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy parking.tb_stdg_dong_mpng FROM 'data/09-parking.tb_stdg_dong_mpng.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy parking.tb_grid FROM 'data/10-parking.tb_grid.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
\copy batch.tb_btch_schdl FROM 'data/11-batch.tb_btch_schdl.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
COMMIT;
