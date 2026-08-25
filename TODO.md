# Known Issues / TODO

## Open Items

- [ ] Pagination on `/pricing` history doesn't work when `customerId` is blank — currently falls back to last 10 records, should support proper pagination
- [ ] `rateValidUntil` is calculated at response-build time, not stored in DB — if you query by ID later, the timestamp shifts slightly
- [ ] No retry logic on DB save failures — if YugabyteDB leader election is happening, the request fails immediately
- [ ] `MetricsDemoController` generate-load endpoint uses `Math.random()` which isn't seeded — not reproducible for testing
- [ ] Grafana provisioning sometimes loses Loki datasource after `docker compose down && up` — re-run `docker compose restart grafana` as workaround

## Known Limitations (POC scope)

- Rate calculation doesn't account for relationship pricing (existing customers getting lower rates)
- No caching on interest rate calculation — fine for POC but would need Redis in prod
- FOIR check uses a fixed 50% threshold — in reality this varies by bank policy and product
- No async processing — everything is synchronous on the request thread

## Done

- [x] FOIR validation on annual income
- [x] PII masking in logs (customer ID, loan amount ranges)
- [x] Audit trail with REQUIRES_NEW propagation
- [x] 3-tier OTel spans (controller → service → repository)
- [x] Actuator endpoint whitelist (no env/threaddump/heapdump exposure)
