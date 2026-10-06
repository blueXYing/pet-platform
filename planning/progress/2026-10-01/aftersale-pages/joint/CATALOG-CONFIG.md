# 正式售后目录资源与装配记录

日期：2026-10-01。状态：RESOURCE_DOCUMENTED / 新装配验收结果另记。资源由根协调编辑，本代理仅新建本记录，无其他文件修改、commit 或 push。

## 来源与历史判断纠正

根已写入生产代码资源 [aftersale-catalog.yml](../../../../../backend/pet-boot/src/main/resources/aftersale-catalog.yml)，完整包含最终 PRD 规定的五类问题与五类诉求。来源及逐段定位见 [目录与视觉审查](CATALOG-VIS-HANDOFF.md) §1：

- C 最终 PRD §5.1.28，`word/document.xml` P2160 / P2162 及字段表 P2319 / P2331 明确十项中文名称。
- M 最终 PRD §5.10，P1257 / P1263 同样列举；运营 PRD §6.7 对齐 C/M 规则，退款/部分补偿类诉求的最终结论归运营。

P 为 XML `.//w:p` 一基段落顺序，包含表格段落，不是 Word 印刷页码。本轮读取最终 Word 原文后确认，正式中文目录内容已经存在；旧报告曾将目录内容判断为待裁决，该内容阻断现已消除。旧报告和批准前记录保留原样，以本记录和本轮来源核对纠正当前判断，不回写历史为当时已知。

目录未从旧 Figma「仅退款/退货退款」、旧申请原因、`QA_*` 或前端测试 fixture 推导。中文名称属于既有批准产品内容；英文键是根在本轮授权配置中采用的稳定工程映射，不增加新产品类别。

## 完整 code 与 label

资源按 ASCII code 升序登记，各组 code 集合与 label key 集合完全一致。`OTHER` 分别位于 type / demand 两个命名空间。

| 组 | code | label |
|---|---|---|
| type | FEE_DISPUTE | 费用争议 |
| type | NON_PERFORMANCE | 未履约 |
| type | OTHER | 其他 |
| type | PET_SAFETY | 宠物安全 |
| type | SERVICE_QUALITY | 质量问题 |
| demand | APOLOGY | 道歉 |
| demand | OTHER | 其他 |
| demand | PARTIAL_COMPENSATION | 部分补偿 |
| demand | REFUND | 退款 |
| demand | RESERVICE | 重新服务 |

资源使用 `pet.aftersale.type-codes` / `demand-codes`，名称使用 `type-labels` / `demand-labels` 的带括号 YAML map key，例如 `"[SERVICE_QUALITY]": "质量问题"`，以保留绑定后的大写字母与下划线。HTTP 目录读取及新创建继续使用同一个完整不可变 ReasonPolicy 快照，不在前端复制正式字典。

## 显式装配

该资源通过 Spring 配置导入显式装配：

```properties
spring.config.import=classpath:aftersale-catalog.yml
```

也可在受控启动参数中指定等价的 `--spring.config.import=classpath:aftersale-catalog.yml`。导入使用资源准确路径；文件已位于 `pet-boot` 的 `src/main/resources`。本文件记录装配方式，不代表已在生产环境导入或发布。

`aftersale-catalog.yml` 仅包含十项目录 code / label，没有 workflow、HTTP、worker、refund 或任何生产 Provider 的启用开关。导入该文件只提供目录配置，原默认关闭与真实依赖校验保持；不因配置完整自动启用售后环境、调度、公开资金或生产渠道。

## 用户诉求与资金能力

完整保留「退款」「部分补偿」两项诉求，不为非出款验收删减已批准的用户选择。它们表达申请人的期望，不能授权退款、退款单或渠道出款；`requestedAmount` 也不是实付金额、可退金额或执行指令。

当前运营前端仍仅提交 REJECT / RESERVICE / OTHER 三种非退款终局。真实资金权威依赖、身份、审核、证据存储等未满足时继续失败关闭。目录不改变七天、单活动工单、P4 人工核对、核销/退款互斥、终局归属或 type→demand 组合规则；不新增自动诊断或退款直通。

## 已批准合同的核对点

以下是 [Contract51](../../../../../docs/04-api/51-AfterSale-Http-Contract-v0.1.md) / 已批准目录 CCR 的既有要求，供本轮实际装配与页面联调核验，不是本记录声称已通过的新测试：

1. 真实本人认证的 `GET /api/v1/c/aftersale-options` 返回完整5+5、正式名称与 ASCII code 升序，bodyless / 无 query；新创建提交所选 code，由同源目录校验。
2. 任一 label 缺失、额外或非法时，不返回空成功或半份目录，不使用测试/硬编码回退。已开启且目录适配不完整时，目录读取及首次创建503 `COMMON_DEPENDENCY_UNAVAILABLE`；原启动校验遇缺失/非法 code 配置仍阻止启用。
3. 新创建的未知/非法 typeCode 或 demandCode 被拒绝，不产生售后业务迁移、证据批次或资金动作；不根据客户端名称或原稿文案猜测代码。
4. 已成功创建的同 UUID / 原 payload 重放按现有授权及幂等合同返回原回执，优先于后来可变目录配置；不得因为目录不可用换 UUID 重建申请。
5. 生产默认关闭、缺真实 Provider 失败关闭，与隔离测试替身的适用边界分别记录。静态资源核对、旧后端测试或 Figma 来源审查均不替代本轮真实装配/页面联合闭环测试。

本轮新装配和三端页面测试是否通过，由根与联调执行者在对应结果报告中提供真实证据。本记录不提前更新测试状态、完整 Issue DoD、VIS、真机、生产启用或 PR 合并结论。
