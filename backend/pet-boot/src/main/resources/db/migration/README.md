# Flyway migration directory

Do not copy historical draft SQL blindly.

Before implementation, consolidate the approved V1.0 schema into ordered Flyway migrations:
- core business schema
- async_task / async_task_attempt / reconciliation_issue
- outbox / consume log
- indexes and constraints

The deprecated `late_payment_recovery` table must NOT be created.
