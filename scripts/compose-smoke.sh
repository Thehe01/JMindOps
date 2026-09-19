#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-jmindops_smoke_$$}"
UI_PORT="${UI_PORT:-33000}"
WAIT_SECONDS="${WAIT_SECONDS:-300}"
KEEP_STACK="${KEEP_STACK:-0}"
DOCKER_BIN="${DOCKER_BIN:-docker}"

if [[ ! "$PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]+$ ]]; then
  printf 'Invalid COMPOSE_PROJECT_NAME: %s\n' "$PROJECT_NAME" >&2
  exit 2
fi
if [[ ! "$UI_PORT" =~ ^[0-9]+$ ]] || ((UI_PORT < 1024 || UI_PORT > 65535)); then
  printf 'UI_PORT must be an integer between 1024 and 65535.\n' >&2
  exit 2
fi

if ! command -v "$DOCKER_BIN" >/dev/null 2>&1; then
  if [[ -x /snap/bin/docker ]]; then
    DOCKER_BIN=/snap/bin/docker
  else
    printf 'Docker CLI was not found.\n' >&2
    exit 1
  fi
fi

# A short-lived WSL invocation can start before the snap daemon has created its
# socket. Start it when available and wait instead of treating that race as a
# project failure.
if ! "$DOCKER_BIN" info >/dev/null 2>&1; then
  if command -v systemctl >/dev/null 2>&1 \
      && systemctl list-unit-files snap.docker.dockerd.service >/dev/null 2>&1; then
    systemctl start snap.docker.dockerd.service
  fi
  docker_ready=0
  for _ in $(seq 1 30); do
    if "$DOCKER_BIN" info >/dev/null 2>&1; then
      docker_ready=1
      break
    fi
    sleep 1
  done
  if ((docker_ready != 1)); then
    printf 'Docker daemon did not become ready within 30 seconds.\n' >&2
    exit 1
  fi
fi

if [[ -n "$("$DOCKER_BIN" ps -aq --filter "label=com.docker.compose.project=$PROJECT_NAME")" ]]; then
  printf 'Compose project %s already exists; refusing to modify it.\n' "$PROJECT_NAME" >&2
  exit 2
fi
if command -v ss >/dev/null 2>&1 \
    && ss -H -ltn "sport = :$UI_PORT" 2>/dev/null | grep -q .; then
  printf 'Host port %s is already in use. Set UI_PORT to a free port.\n' "$UI_PORT" >&2
  exit 2
fi

random_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$1"
  else
    python3 -c "import secrets; print(secrets.token_hex($1))"
  fi
}

export POSTGRES_USER="${POSTGRES_USER:-jmindops_owner}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-$(random_hex 24)}"
export POSTGRES_DB="${POSTGRES_DB:-jmindops_smoke}"
export APP_DB_PASSWORD="${APP_DB_PASSWORD:-$(random_hex 24)}"
export APP_JWT_SECRET="${APP_JWT_SECRET:-$(random_hex 32)}"
export UI_PORT

# Never consume a developer's provider credentials or call external models in
# this infrastructure smoke test.
export DEEPSEEK_API_KEY=""
export ZHIPUAI_API_KEY=""
export GOOGLE_GENAI_API_KEY=""
export MAIL_USERNAME=""
export MAIL_PASSWORD=""
export EMAIL_TOOL_ENABLED="false"
export DATABASE_TOOL_ENABLED="false"
export MCP_CLIENT_ENABLED="false"
export APP_BOOTSTRAP_ADMIN_USERNAME=""
export APP_BOOTSTRAP_ADMIN_PASSWORD=""

compose=("$DOCKER_BIN" compose --project-directory "$REPO_ROOT" -p "$PROJECT_NAME")

cleanup() {
  status=$?
  trap - EXIT INT TERM
  if ((status != 0)); then
    printf '\nSmoke test failed; container status and recent logs follow.\n' >&2
    "${compose[@]}" ps >&2 || true
    "${compose[@]}" logs --no-color --tail=200 >&2 || true
  fi
  if [[ "$KEEP_STACK" != "1" ]]; then
    "${compose[@]}" down --volumes --remove-orphans --rmi local || true
  else
    printf 'KEEP_STACK=1: retained Compose project %s for inspection.\n' "$PROJECT_NAME"
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

cd "$REPO_ROOT"
printf 'Validating Compose project %s on UI port %s...\n' "$PROJECT_NAME" "$UI_PORT"
"${compose[@]}" config --quiet

printf 'Building application images...\n'
"${compose[@]}" build

printf 'Starting a fresh database and application stack...\n'
"${compose[@]}" up -d --wait --wait-timeout "$WAIT_SECONDS"
"${compose[@]}" ps

base_url="http://127.0.0.1:$UI_PORT"
health_body="$(curl --fail --silent --show-error "$base_url/health")"
printf 'Health endpoint: %s\n' "$health_body"

migration_count="$("${compose[@]}" exec -T db \
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --tuples-only --no-align \
  --command "SELECT count(*) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7') AND success")"
if [[ "$migration_count" != "7" ]]; then
  printf 'Expected successful Flyway migrations V1-V7; got %s.\n' "$migration_count" >&2
  exit 1
fi

bm25_schema_status="$("${compose[@]}" exec -T db \
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --tuples-only --no-align \
  --command "SELECT (SELECT count(*) FROM pg_extension WHERE extname='pg_search')::text || '|' || (SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname='idx_chunk_bm25' AND indexdef LIKE '%USING paradedb%' AND indexdef LIKE '%pdb.jieba%')::text")"
if [[ "$bm25_schema_status" != "1|1" ]]; then
  printf 'Expected pg_search and the Jieba BM25 index; got %s.\n' "$bm25_schema_status" >&2
  exit 1
fi

"${compose[@]}" exec -T db \
  psql --quiet --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --command "INSERT INTO knowledge_base (id, name) VALUES ('10000000-0000-0000-0000-000000000001', 'BM25 smoke');
    INSERT INTO document (id, kb_id, filename, source_key, index_version, index_status, chunk_count)
    VALUES
      ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'vector.txt', 'vector.txt', 1, 'READY', 1),
      ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'redis.txt', 'redis.txt', 1, 'READY', 1);
    INSERT INTO chunk_bge_m3 (id, kb_id, doc_id, content, chunk_hash, chunk_index, embedding)
    VALUES
      ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'PostgreSQL 使用 pgvector 执行向量检索，支持近邻搜索。', repeat('1', 64), 0, array_fill(0::real, ARRAY[1024])::vector),
      ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'Redis 提供缓存、会话和分布式锁。', repeat('2', 64), 0, array_fill(0::real, ARRAY[1024])::vector);" \
  >/dev/null

bm25_result="$("${compose[@]}" exec -T --env PGPASSWORD="$APP_DB_PASSWORD" db \
  psql --host 127.0.0.1 --username jmindops_app --dbname "$POSTGRES_DB" \
  --tuples-only --no-align \
  --command "SELECT id FROM chunk_bge_m3
    WHERE kb_id = '10000000-0000-0000-0000-000000000001'
      AND content ||| 'PostgreSQL 如何进行向量检索'
    ORDER BY pdb.score(id) DESC, id ASC
    LIMIT 1")"
if [[ "$bm25_result" != "30000000-0000-0000-0000-000000000001" ]]; then
  printf 'Expected the Chinese BM25 query to retrieve the vector chunk; got %s.\n' "$bm25_result" >&2
  exit 1
fi

incremental_index_column_count="$("${compose[@]}" exec -T db \
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --tuples-only --no-align \
  --command "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND ((table_name='document' AND column_name IN ('source_key','content_hash','index_version','index_status','chunk_count','indexed_at')) OR (table_name='chunk_bge_m3' AND column_name IN ('chunk_hash','chunk_index','document_version')))")"
if [[ "$incremental_index_column_count" != "9" ]]; then
  printf 'Expected 9 incremental-index columns; got %s.\n' "$incremental_index_column_count" >&2
  exit 1
fi

table_count="$("${compose[@]}" exec -T db \
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --tuples-only --no-align \
  --command "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('agent','chat_session','chat_message','knowledge_base','document','chunk_bge_m3','app_user','tool_approval','tool_audit_log','generation_task')")"
if [[ "$table_count" != "10" ]]; then
  printf 'Expected 10 application tables; got %s.\n' "$table_count" >&2
  exit 1
fi

owner_column_count="$("${compose[@]}" exec -T db \
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --tuples-only --no-align \
  --command "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='owner_id' AND table_name IN ('agent','knowledge_base','chat_session')")"
if [[ "$owner_column_count" != "3" ]]; then
  printf 'Expected owner_id on agent, knowledge_base and chat_session; got %s columns.\n' "$owner_column_count" >&2
  exit 1
fi

runtime_acl="$("${compose[@]}" exec -T --env PGPASSWORD="$APP_DB_PASSWORD" db \
  psql --host 127.0.0.1 --username jmindops_app --dbname "$POSTGRES_DB" \
  --tuples-only --no-align --field-separator='|' \
  --command "SELECT current_user, has_schema_privilege(current_user,'public','USAGE'), has_schema_privilege(current_user,'public','CREATE'), has_table_privilege(current_user,'public.agent','SELECT'), has_table_privilege(current_user,'public.agent','INSERT'), has_table_privilege(current_user,'public.agent','UPDATE'), has_table_privilege(current_user,'public.agent','DELETE'), has_table_privilege(current_user,'public.generation_task','SELECT'), has_table_privilege(current_user,'public.generation_task','INSERT'), has_table_privilege(current_user,'public.generation_task','UPDATE')")"
if [[ "$runtime_acl" != "jmindops_app|t|f|t|t|t|t|t|t|t" ]]; then
  printf 'Unexpected runtime database privileges: %s\n' "$runtime_acl" >&2
  exit 1
fi

query_token_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "$base_url/api/knowledge-bases?access_token=invalid-query-token")"
if [[ "$query_token_status" != "401" ]]; then
  printf 'Query-string access_token should be rejected with 401; got %s.\n' "$query_token_status" >&2
  exit 1
fi

smoke_username="smoke_$(date +%s)_$$"
smoke_password="Smoke-$(random_hex 12)"
printf -v register_payload '{"username":"%s","password":"%s"}' \
  "$smoke_username" "$smoke_password"
register_response="$(curl --fail --silent --show-error \
  --header 'Content-Type: application/json' \
  --data "$register_payload" \
  "$base_url/api/auth/register")"
access_token="$(printf '%s' "$register_response" | python3 -c \
  'import json, sys; payload=json.load(sys.stdin); assert payload.get("code")==200; print(payload["data"]["accessToken"])')"
if [[ -z "$access_token" ]]; then
  printf 'Registration succeeded without returning an access token.\n' >&2
  exit 1
fi

me_response="$(curl --fail --silent --show-error \
  --header "Authorization: Bearer $access_token" \
  "$base_url/api/auth/me")"
printf '%s' "$me_response" | python3 -c \
  'import json, sys; payload=json.load(sys.stdin); assert payload.get("code")==200; assert payload["data"]["username"].startswith("smoke_")'

knowledge_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --header "Authorization: Bearer $access_token" \
  "$base_url/api/knowledge-bases")"
if [[ "$knowledge_status" != "200" ]]; then
  printf 'Authenticated knowledge-base request returned %s.\n' "$knowledge_status" >&2
  exit 1
fi

printf 'Flyway V1-V7, Jieba BM25 retrieval, incremental index schema, ownership, runtime ACLs, registration and header authentication: PASS\n'
printf 'Query-string JWT rejection: PASS\n'
printf 'Compose smoke test: PASS\n'
