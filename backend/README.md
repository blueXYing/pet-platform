# Pet Platform Backend

V1.0 backend skeleton for a modular monolith that is deliberately shaped for future microservice extraction.

## Baseline

- Java 21
- Spring Boot 4.1.1
- Maven multi-module
- MyBatis Spring Boot 4.1.0
- MySQL 8.x
- Redis
- Flyway
- ArchUnit
- Testcontainers
- Transactional Outbox
- durable async_task

## Start local infrastructure

```bash
docker compose -f docker-compose.dev.yml up -d
```

## Validate architecture

```bash
python tools/check-module-deps.py
mvn test
```

## Run application

```bash
mvn -pl pet-boot -am spring-boot:run
```

## Core rule

```text
biz -> api  OK
biz -> biz  FORBIDDEN
```

The root Maven Enforcer rule rejects direct `pet-*-biz` dependencies everywhere except assembly/test modules.

## Important

This is a project skeleton, not the full business implementation.

Before feature coding:
1. consolidate approved SQL into Flyway migrations;
2. materialize API Contract DTO/interfaces from the approved contract document;
3. implement Outbox/AsyncTask persistence;
4. write P0 state-machine/concurrency tests first.
