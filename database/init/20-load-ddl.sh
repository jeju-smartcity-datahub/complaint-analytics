#!/bin/bash
# postgres 이미지 최초 초기화(빈 데이터 볼륨) 때 한 번 실행된다.
# load-data.sql 의 \copy 가 'data/...' 상대경로라 ddl 디렉터리로 이동한 뒤 실행한다.
set -euo pipefail

cd /ddl
echo "[init] jeju-datahub-schema.sql 적용"
psql -v ON_ERROR_STOP=1 -q --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f jeju-datahub-schema.sql
echo "[init] load-data.sql 적재 (기준데이터)"
psql -v ON_ERROR_STOP=1 -q --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f load-data.sql
echo "[init] 완료"
