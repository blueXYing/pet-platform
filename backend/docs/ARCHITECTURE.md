# Backend architecture baseline

## Mandatory dependency rule

```text
xxx-biz -> yyy-api       allowed
xxx-biz -> yyy-biz       forbidden
xxx-biz -> yyy Repository/Mapper/Entity forbidden
pet-boot -> all biz      allowed for assembly only
```

## Package shape

```text
com.petplatform.<domain>.api
  command/
  query/
  dto/
  enums/
  error/

com.petplatform.<domain>.biz
  application/
  domain/model/
  domain/service/
  infrastructure/persistence/mapper/
  infrastructure/persistence/entity/
  infrastructure/provider/
  apiimpl/
  event/
  task/
```

## Cross-module communication

Synchronous:
- required reads / validation
- commands requiring immediate result
- schedule hold
- order operation guard
- payment/refund provider submission

Asynchronous:
- payment succeeded
- order paid/confirmed/verified
- refund created/succeeded
- aftersale facts
- merchant disabled
- notifications, coupon, points, review projections

Durability:
- Transactional Outbox
- consumer idempotency
- durable async_task
- reconciliation_issue

## Late payment rule

If the order has already been closed due to PAYMENT_TIMEOUT and the provider later confirms payment success:
- do not restore the order
- do not restore appointment/coupon
- do not emit normal OrderPaidEvent
- create a FULL refund using the actual channel paid amount
- refund_source = LATE_PAYMENT_TIMEOUT
