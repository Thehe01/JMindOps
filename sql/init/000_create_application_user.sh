#!/bin/sh
set -eu

: "${APP_DB_PASSWORD:?Set APP_DB_PASSWORD in .env}"
: "${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}"

# The one-shot db-role-init service runs this before the application. Flyway
# subsequently creates tables as the owner and grants DML to this role.
export PGPASSWORD="$POSTGRES_PASSWORD"
if [ -n "${POSTGRES_HOST:-}" ]; then
    set -- --host "$POSTGRES_HOST"
else
    set --
fi

psql "$@" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"     --set=ON_ERROR_STOP=1 --set=app_password="$APP_DB_PASSWORD" <<'EOSQL'
\set QUIET on
SELECT format(
    'CREATE ROLE jmindops_app LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
    :'app_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jmindops_app') \gexec

SELECT format('ALTER ROLE jmindops_app PASSWORD %L', :'app_password') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO jmindops_app', current_database()) \gexec
GRANT USAGE ON SCHEMA public TO jmindops_app;

DO $$
DECLARE
    application_table TEXT;
BEGIN
    FOREACH application_table IN ARRAY ARRAY[
        'agent', 'chat_session', 'chat_message', 'knowledge_base', 'document',
        'chunk_bge_m3', 'app_user', 'tool_approval', 'tool_audit_log', 'generation_task',
        'document_index_task'
    ]
    LOOP
        IF to_regclass('public.' || application_table) IS NOT NULL THEN
            EXECUTE format(
                'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %I TO jmindops_app',
                application_table
            );
        END IF;
    END LOOP;
END
$$;
EOSQL

if [ -n "${DB_TOOL_PASSWORD:-}" ]; then
    psql "$@" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
        --set=ON_ERROR_STOP=1 --set=tool_password="$DB_TOOL_PASSWORD" <<'EOSQL'
\set QUIET on
SELECT format(
    'CREATE ROLE jmindops_tool_reader LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
    :'tool_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jmindops_tool_reader') \gexec

SELECT format('ALTER ROLE jmindops_tool_reader PASSWORD %L', :'tool_password') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO jmindops_tool_reader', current_database()) \gexec
SELECT format('REVOKE TEMPORARY ON DATABASE %I FROM jmindops_tool_reader', current_database()) \gexec
GRANT USAGE ON SCHEMA public TO jmindops_tool_reader;

DO $$
DECLARE
    readable_table TEXT;
BEGIN
    FOREACH readable_table IN ARRAY ARRAY[
        'agent', 'chat_session', 'chat_message', 'knowledge_base', 'document', 'chunk_bge_m3'
    ]
    LOOP
        IF to_regclass('public.' || readable_table) IS NOT NULL THEN
            EXECUTE format('GRANT SELECT ON TABLE %I TO jmindops_tool_reader', readable_table);
        END IF;
    END LOOP;
END
$$;
EOSQL
fi
unset PGPASSWORD
