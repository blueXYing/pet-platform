# 宠物平台 V1.0 Maven 多模块工程骨架设计 v0.1

## 1. 最终工程形态

```text
Modular Monolith
Spring Boot 单进程
Maven 多模块
API / Biz 强边界
```

核心编译规则：

```text
xxx-biz -> yyy-api     允许
xxx-biz -> yyy-biz     禁止
跨模块 Repository/Mapper/Entity 访问 禁止
pet-boot -> 所有 biz   仅装配允许
```

## 2. 技术版本

本骨架采用：

```text
Java                    21
Spring Boot             4.1.1
MyBatis Spring Boot     4.1.0
ArchUnit                1.4.2
Testcontainers          2.0.5
MySQL                   8.x
Redis                   7.x
```

没有引入 Spring Cloud / OpenFeign。当前本地实现依赖纯 Java `*-api`；未来拆服务时新增 Feign Adapter。

## 3. 模块

总计 40 个 Maven 子模块。

基础技术：

```text
pet-common
pet-event-api
pet-event-core
pet-task-core
```

业务模块每个领域都采用：

```text
pet-<domain>-api
pet-<domain>-biz
```

领域：

```text
user
merchant
service
schedule
order
payment
refund
verification
aftersale
coupon
points
review
notification
community
thirdparty
customer-service
admin
```

装配/检查：

```text
pet-boot
pet-architecture-test
```

## 4. 为什么增加 event-core

之前只有 `pet-event-api`，只能定义事件契约。

Outbox Dispatcher、本地事件派发、未来 MQ Publisher Adapter 都属于基础设施实现，不应该放进 `pet-boot`，所以独立：

```text
pet-event-api   纯契约
pet-event-core  Outbox/Dispatcher 技术实现
```

业务模块只依赖 `pet-event-api`。

## 5. 为什么保留 task-core

`pet-task-core` 承载：

```text
AsyncTaskWorker
TaskHandler
Lease
RetryPolicy
TaskClock
```

30分钟自动接单、24小时退款超时、支付/退款主动查单等 Handler 放回各自业务模块。

## 6. package 规范

API：

```text
com.petplatform.order.api.command
com.petplatform.order.api.query
com.petplatform.order.api.dto
com.petplatform.order.api.enums
com.petplatform.order.api.error
```

Biz：

```text
com.petplatform.order.biz.application
com.petplatform.order.biz.domain.model
com.petplatform.order.biz.domain.service
com.petplatform.order.biz.infrastructure.persistence.mapper
com.petplatform.order.biz.infrastructure.persistence.entity
com.petplatform.order.biz.infrastructure.provider
com.petplatform.order.biz.apiimpl
com.petplatform.order.biz.event
com.petplatform.order.biz.task
```

## 7. 架构自动门禁

两层保护：

```text
Maven Enforcer
→ 编译依赖层禁止 pet-*-biz

ArchUnit
→ API 不依赖 Spring/MyBatis
→ Domain 不依赖 Infrastructure/Spring/MyBatis
→ Web Controller 不依赖 Mapper/Entity/Repository
```

另外提供：

```bash
python tools/check-module-deps.py
```

用于快速检查 Maven 模块依赖。

## 8. pet-boot 职责

只负责：

```text
Spring Boot 启动
Controller
HTTP DTO
Security Filter
配置装配
Provider callback endpoint
Flyway入口
Actuator
```

不允许把核心订单/退款状态机写进 boot。

## 9. 持久化

本骨架选择 MyBatis，而不是让 JPA Entity 跨模块流动。

每个 `*-biz` 自己拥有：

```text
Mapper
Persistence Entity/DO
Repository实现
```

对外只返回 API DTO。

## 10. 当前没有生成的业务代码

这一步只生成“工程骨架”，没有擅自批量实现：

```text
完整 OrderApi DTO
具体订单状态机
拉卡拉 Provider
完整 Mapper
完整 Flyway SQL
Controller业务逻辑
人工客服承载实现
```

它们应该以已经封板的 Contract / Schema / Test Matrix 为依据，由后续 Codex 阶段逐步生成。

## 11. 下一阶段

工程骨架之后，技术设计阶段只剩最后的“交付包整理”：

```text
当前所有 SSOT
技术基线
Schema
API Contract
OpenAPI
Event Catalog
Scheduler
测试矩阵
工程骨架
```

交给 ChatGPT Work 做统一目录化与一致性检查。

随后：

```text
Work
↓
Codex
↓
先P0测试
↓
实现核心交易代码
```

## ACR-001 同步说明

后端骨架与版本保持不变。统一小程序与运营网页技术见20-前端技术基线-v0.7.md；前端变更不引入新的Node业务后端或改变40个Maven模块。
