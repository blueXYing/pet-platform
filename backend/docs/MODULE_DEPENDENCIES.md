# Module dependency matrix

| Module | Allowed internal dependencies |
|---|---|
| pet-user-biz | pet-user-api, pet-common, pet-event-api |
| pet-merchant-biz | pet-merchant-api, pet-common, pet-event-api |
| pet-service-biz | pet-service-api, pet-common, pet-event-api, pet-merchant-api |
| pet-schedule-biz | pet-schedule-api, pet-common, pet-event-api, pet-merchant-api, pet-service-api, pet-task-core |
| pet-order-biz | pet-order-api, pet-common, pet-event-api, pet-user-api, pet-merchant-api, pet-service-api, pet-schedule-api, pet-payment-api, pet-coupon-api, pet-task-core |
| pet-payment-biz | pet-payment-api, pet-common, pet-event-api, pet-task-core |
| pet-refund-biz | pet-refund-api, pet-common, pet-event-api, pet-order-api, pet-payment-api, pet-task-core |
| pet-verification-biz | pet-verification-api, pet-common, pet-event-api, pet-order-api |
| pet-aftersale-biz | pet-aftersale-api, pet-common, pet-event-api, pet-order-api, pet-refund-api |
| pet-coupon-biz | pet-coupon-api, pet-common, pet-event-api |
| pet-points-biz | pet-points-api, pet-common, pet-event-api |
| pet-review-biz | pet-review-api, pet-common, pet-event-api, pet-order-api |
| pet-notification-biz | pet-notification-api, pet-common, pet-event-api, pet-task-core |
| pet-community-biz | pet-community-api, pet-common, pet-event-api, pet-user-api |
| pet-thirdparty-biz | pet-thirdparty-api, pet-common, pet-event-api, pet-merchant-api, pet-task-core |
| pet-customer-service-biz | pet-customer-service-api, pet-common, pet-event-api |
| pet-admin-biz | pet-admin-api, pet-common, pet-event-api, pet-merchant-api, pet-order-api, pet-refund-api, pet-aftersale-api, pet-review-api, pet-coupon-api, pet-points-api, pet-notification-api |

`pet-boot` intentionally assembles every `*-biz` module.

`pet-architecture-test` depends on `pet-boot` only to import the full compiled application graph for ArchUnit.
