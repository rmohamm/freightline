# Freightline

A small shipment tracking and freight rating service, built with Java 21 and Spring Boot 3.5.

Freightline lets you create shipments with a carrier and service level, record status events as a shipment moves (picked up, in transit, delivered), estimate delivery dates, and quote freight rates.

## Geek Agent demo

This repository is the demo for [Geek Agent](https://github.com/rmohamm/geek-agent), an unattended coding agent that fixes a GitHub issue and opens a pull request for human review. Its issues are seeded bugs and feature requests; the agent's pull requests show what it does with them.

- **Fix an issue:** Actions → **Geek Agent** → Run workflow, or add the `agent-ready` label to an issue.
- **Triage issues:** Actions → **Geek Agent triage** → Run workflow.
- **Comparison runs** use the `eval/baseline` branch, which holds the original code with every seeded bug. Their pull requests target `eval/baseline` and are never merged.

## Run it

```bash
mvn spring-boot:run
```

The API starts on http://localhost:8080 with an in-memory H2 database and three carriers.

To load 500 sample shipments for demos:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

The H2 console is at http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:freightline`, user `sa`, no password).

## Test it

```bash
mvn test
```

## API

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/shipments` | Create a shipment |
| `GET` | `/api/shipments` | List shipments |
| `GET` | `/api/shipments/{id}` | Get one shipment |
| `POST` | `/api/shipments/{id}/events` | Record a status event |
| `GET` | `/api/shipments/{id}/history` | Status history for a shipment |
| `GET` | `/api/shipments/summary` | Tracking summary for all shipments |
| `POST` | `/api/rates/quote` | Quote a freight rate |

### Examples

Create a shipment:

```bash
curl -s -X POST localhost:8080/api/shipments -H 'Content-Type: application/json' -d '{
  "origin": "Dallas, TX",
  "destination": "Atlanta, GA",
  "originTimeZone": "America/Chicago",
  "weightKg": 12.5,
  "carrierCode": "RDLN",
  "serviceLevel": "STANDARD"
}'
```

Record a status event:

```bash
curl -s -X POST localhost:8080/api/shipments/1/events -H 'Content-Type: application/json' -d '{
  "status": "PICKED_UP",
  "location": "Dallas, TX"
}'
```

Quote a rate:

```bash
curl -s -X POST localhost:8080/api/rates/quote -H 'Content-Type: application/json' -d '{
  "carrierCode": "PRCL",
  "serviceLevel": "EXPRESS",
  "weightKg": 4
}'
```

### Carriers

| Code | Name | Rate per kg (USD) |
|---|---|---|
| `FSHP` | FastShip Freight | 2.10 |
| `RDLN` | Redline Logistics | 1.75 |
| `PRCL` | Parcel Prime | 2.50 |

### Service levels

| Level | Transit days | Rate multiplier |
|---|---|---|
| `STANDARD` | 5 | 1.0 |
| `EXPRESS` | 2 | 1.5 |
| `OVERNIGHT` | 1 | 2.25 |

A fuel surcharge of 8% is added to every quote.

### Shipment statuses

`CREATED` → `PICKED_UP` → `IN_TRANSIT` → `OUT_FOR_DELIVERY` → `DELIVERED`, with `EXCEPTION` for problems such as damage or a missed delivery.
