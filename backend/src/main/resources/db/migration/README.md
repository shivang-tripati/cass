# Flyway Migration Conventions

This directory holds the forward-only PostgreSQL migration history for the
OBD platform. The application schema starts from an intentionally empty
baseline; the common API foundation requires no tables.

## Naming

V<version>__<snake_case_description>.sql

Examples (illustrative only — do not create ahead of the code that needs them):

    V1__create_users.sql
    V2__create_tenants.sql

## Rules

- Forward-only. Never edit a migration after it has been applied anywhere.
- One logical change per migration; reviewable and deterministic.
- Use `CREATE TABLE IF NOT EXISTS` sparingly — prefer plain `CREATE TABLE`
  so drift fails loudly.
- Every foreign key gets an explicit supporting index unless justified.
- Timestamps: `TIMESTAMPTZ` mapped to `Instant`.
- Enum-like values stored as `VARCHAR` with application-level enums.
- Primary keys are UUIDs (`gen_random_uuid()`).
- Transactional DDL is supported by PostgreSQL; keep migrations atomic by
  default, but place index builds on large future tables outside hot paths.
- Seed/reference data belongs in idempotent migrations only when strictly
  required; prefer environment provisioning over seed migrations.

## Configuration

Flyway runs from `classpath:db/migration` (profile `dev`).
`spring.flyway.baseline-on-migrate` is disabled: against a dirty database,
startup must fail rather than silently adopt unknown history.
