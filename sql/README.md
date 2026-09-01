# Database initialization

Schema changes are managed by Flyway from
`jmindops/src/main/resources/db/migration` and run in version order.

The one-shot `db-role-init` Compose service runs the script in `init` after
PostgreSQL is healthy. It creates or rotates the restricted `jmindops_app`
login before the backend starts, so both empty and existing data volumes use
the same least-privilege runtime account. The directory must not contain schema
migrations.

The former `jmindops.sql`, `002_ai_governance.sql`, and
`003_resource_ownership.sql` scripts were moved into Flyway migrations so a
fresh database can no longer execute governance migrations before the base
tables exist.
