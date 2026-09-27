# OBD Platform Local Infrastructure

## Startup

1. Open a terminal in `infra/`.
2. Run:
   ```bash
   docker compose up -d
   ```

## Verify services

- PostgreSQL: `localhost:5432`
- Redis: `localhost:6379`
- MinIO API: `http://localhost:9000`
- MinIO Console: `http://localhost:9001`

### Health checks

- PostgreSQL:
  ```bash
  docker compose exec obd-postgres pg_isready -U obd_user -d obd
  ```
  Expected output: `localhost:5432 - accepting connections`

- Redis:
  ```bash
  docker compose exec obd-redis redis-cli ping
  ```
  Expected output: `PONG`

- MinIO:
  ```bash
  curl http://localhost:9000/minio/health/live
  ```
  Expected output: `{"status":"ok"}`
  ```

## Data persistence

Named volumes are configured for PostgreSQL, Redis, and MinIO. Containers may be restarted without losing stored data.

## MinIO bucket creation

MinIO buckets are not created automatically by Docker Compose. To create buckets manually:

1. Install the MinIO client (`mc`).
2. Configure the alias:
   ```bash
   mc alias set local http://localhost:9000 minioadmin minioadmin123
   ```
3. Create buckets for the platform:
   ```bash
   mc mb local/obd-audio
   mc mb local/obd-recordings
   mc mb local/obd-imports
   mc mb local/obd-exports
   ```

## Notes

- Use `backend/.env.example` as the base for backend environment variables.
- `application-dev.yml` uses environment variables for database and Redis connectivity.
