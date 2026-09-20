# MER-001 S7 HTTP integration

Status: implemented behind a default-off gate; bounded adapter tests pass. Real provider deployment remains blocked until the required private-asset, city, map, protected-value, credential and event-publisher beans are configured.

## Surface

The Boot composition exposes the ten approved Contract 30 application routes when `pet.merchant.application.enabled=true`:

- applicant: current, create draft, replace draft, submit;
- operator: list, detail, claim, release, manual verification, decision.

It also exposes the two previously approved agreement routes under the same gate. The gate does not install fallback providers. Enabling it without every authoritative provider fails startup.

The authenticated `GET /api/v1/c/merchant-application-cities` route reads only the trusted
startup-bound `pet.merchant.application.open-cities` catalog. The first approved deployment
configures `{code: chengdu, name: 成都}`. Empty/missing configuration returns 503 and submission
validation rejects every city code not present in the same catalog; neither path derives a city
from coordinates or request data.

MINIAPP identity is taken only from the resolved `MiniSessionView`; ADMIN_WEB identity, session generation and current action set are taken only from the resolved `AdminSessionView`. Client bodies cannot provide actor, owner, operator, scope or authorization facts. Operator collection/resource scope is still rechecked by the merchant application service through the Boot `AdminAuthorizationQueryApi` bridge.

## Wire rules

- IDs, versions, revision numbers and coordinates are JSON strings.
- Request bodies are parsed by an endpoint-local strict mapper: duplicate/unknown fields, scalar coercion and numeric IDs or versions are rejected.
- `X-Request-Id` is a terminal UUID on every write.
- timestamps render in UTC with exactly three fractional digits.
- output projections explicitly select OAS fields. Internal claim timestamps, task status from summaries, internal notes, protected values and raw credentials are not serialized.
- internal subject states map only to `PENDING` or `VERIFIED`; an unknown internal state fails closed with 503.
- create and agreement-consent use durable domain outcomes for first-write 201 versus replay 200.
- all success and error responses set `Cache-Control: no-store` and use the Contract 30 `success/code/message/data/traceId` envelope.

## Verification

`MerchantApplicationHttpTest` covers resolved-session identity mapping, String wire versions, durable 201/200 distinction, missing-session rejection, and unknown/numeric/duplicate/trailing JSON rejection. It uses mock merchant APIs and therefore proves the HTTP adapter only. `MerchantApplicationLifecycleHttpTest` additionally passed actual TCP + C/Admin sessions + MySQL/Redis + real AES/credential normalization/Chengdu configuration + Outbox/inbox, with the frontend repositories decoding actual authenticated responses. External WeChat identity exchange/private material facts/map validation are test adapters; this is not production or real document-upload acceptance.

No test or adapter claims a real private-asset/OCR provider. Manual evidence references are checked by the domain against the locked submitted material set, while private material ingestion and grants remain an external provider contract.
