# ePricing Service

Spring Boot microservice for calculating loan interest rates and EMIs in a retail banking context. Built as a POC to demonstrate end-to-end observability using Prometheus, Grafana, Loki, and OpenTelemetry.

## What it does

Takes a loan request (customer ID, product type, loan amount, credit score, income) and returns a calculated interest rate, EMI, and risk category. Everything is traced, metered, and logged in structured JSON so Grafana dashboards work out of the box.

## Stack

- **Spring Boot 3.x** + **YugabyteDB** (Postgres-compatible)
- **Micrometer** → **Prometheus** → **Grafana** for metrics
- **OpenTelemetry** → **Tempo** for distributed traces
- **Logback JSON** → **Promtail** → **Loki** → **Grafana** for logs
- **Docker Compose** for local stack

## Running locally

```bash
# Start the full observability stack
docker compose up -d

# Run the app (requires DB_USERNAME and DB_PASSWORD env vars)
export DB_USERNAME=admin DB_PASSWORD=password
mvn spring-boot:run
```

Then open:
- API: http://localhost:8080
- Grafana: http://localhost:3000 (admin/admin)
- Prometheus: http://localhost:9090
- Actuator: http://localhost:8081/actuator/health

## API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/pricing` | Submit a pricing request |
| GET | `/pricing?customerId=CUST001` | Get history for a customer |
| GET | `/pricing/{id}` | Get a specific record |
| GET | `/health` | Application health + DB check |

### Sample request

```json
POST /pricing
{
  "customerId": "CUST001234",
  "productType": "HOME_LOAN",
  "loanAmount": 2500000,
  "loanTenureMonths": 240,
  "creditScore": 780,
  "annualIncome": 1200000
}
```

## Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DB_USERNAME` | Yes | Database username |
| `DB_PASSWORD` | Yes | Database password |
| `DB_HOST` | No | Defaults to `localhost` |
| `CORS_ALLOWED_ORIGINS` | No | Defaults to deny-all |
| `RATE_VALID_DAYS` | No | How long quoted rates are valid (default: 7) |

## Running tests

```bash
mvn test
```

17 tests covering calculation logic, service orchestration, and HTTP edge cases (400, 404, 422).

## Notes

- Actuator is locked down to `health`, `info`, `prometheus`, `metrics` only
- Customer IDs are masked in all logs (`CUST****1234`)
- The `/metrics-demo/*` endpoints are disabled in the `prod` profile
