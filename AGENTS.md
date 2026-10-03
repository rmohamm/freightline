# Agent instructions — Freightline

Freightline is a Spring Boot service for shipment tracking and freight rating.
Read this file before changing anything.

## Build and test

- Java 21, Maven. Run the full test suite with:
  ```
  mvn -q test
  ```
  (If the Maven wrapper is present, `./mvnw -q test` works the same way.)
- The suite runs in well under a minute against an in-memory H2 database. It needs no network services.
- A change is not done until `mvn -q test` passes.

## How to fix an issue

1. Reproduce it first: write a test that fails for the reason described in the issue.
2. Make the smallest change that fixes the root cause. Don't refactor unrelated code.
3. Run the full suite. Every test must pass, including the new one.
4. If you change an API response shape or status code, update the affected existing tests and say so in the PR description.
5. If the issue is too vague to reproduce, don't guess. Stop and explain what information is missing.

## Layout

```
src/main/java/com/freightline/
  carrier/    Carrier entity and repository (seeded from data.sql)
  shipment/   Shipment + ShipmentEvent entities, service, controller, DTOs, DeliveryEstimator
  rating/     RateCalculator and the rate quote API
  config/     Clock bean, demo data seeder (profile "demo")
  web/        GlobalExceptionHandler (errors as RFC 7807 ProblemDetail)
src/test/java/com/freightline/   Unit tests and MockMvc API tests
```

## Conventions

- Constructor injection only; no field injection.
- DTOs are Java records (see `ShipmentDtos`); entities never leave the service layer.
- Use the injected `Clock` for the current time, never `Instant.now()` directly, so time can be fixed in tests.
- Money is currency in USD. Validation uses Jakarta Bean Validation annotations on request records.
- Errors are returned as `ProblemDetail` from `GlobalExceptionHandler`.
- API tests use `@SpringBootTest` + `@AutoConfigureMockMvc`; pure logic gets plain JUnit 5 unit tests.

## Don't

- Don't add dependencies unless the fix genuinely needs one; explain why in the PR if you do.
- Don't change the seeded carriers in `data.sql` or the CI workflow.
- Don't change the Java or Spring Boot version unless the issue asks for it.
