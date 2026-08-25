# Dev Notes

Scratch notes while building this out. Not meant to be tidy.

---

## YugabyteDB setup headaches

Getting YugabyteDB running locally was painful at first. The default docker image needs `--tserver_flags` to enable the Postgres wire protocol on 5433. The `docker-compose.yml` has the right flags now but took a while to figure out.

Also — the JDBC URL needs `?sslmode=disable` for local dev otherwise Spring keeps throwing SSL handshake errors.

## OTel span parenting

Spent time figuring out why child spans weren't showing up properly in Tempo. The issue was that `Span.makeCurrent()` returns a `Scope` that needs to be closed in a try-with-resources — if you don't, the span context leaks across threads and the waterfall gets scrambled.

The correct pattern is:
```java
try (Scope scope = span.makeCurrent()) {
    // work here
} finally {
    span.end();
}
```

Always end the span in `finally`, not inside the try block, so it gets ended even on exceptions.

## Prometheus metric naming

Micrometer converts camelCase names to snake_case automatically and adds the unit suffix. So `Timer` named `pricing.calculation.duration` becomes `pricing_calculation_duration_seconds_*` in Prometheus. This confused me initially because the name in code doesn't match what you query in PromQL.

## Grafana provisioning

Dashboard JSON files in `grafana/dashboards/` are picked up automatically via the provisioning config. If you edit a dashboard in the UI and want to save it, export as JSON and overwrite the file — otherwise changes are lost on container restart.

## FOIR calculation

The eligibility check uses 50% FOIR × 120 months × 85% safety margin to estimate max eligible loan. This is a rough approximation — real CIBIL-based underwriting is more complex, but it's good enough for the POC.

Formula:
```
maxLoan = (annualIncome / 12) * 0.50 * 120 * 0.85
```

---

*Last updated: adding OTel span notes after debugging Tempo waterfall*
