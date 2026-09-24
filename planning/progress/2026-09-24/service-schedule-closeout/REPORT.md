# 服务发布收尾与排期草案审阅

日期：2026-09-24。基线：develop `0c2d7ae`（PR77/78/79已合入）；工作分支：`codex/service-schedule-closeout-20260924`。
实现提交：`ee1cd379b3150553d6522ae3823c3929be3d6540`。上述自动检查对应此提交的代码。

本分支从 SCH-002/SCH-004 两笔准备文档提交继续，原准备工作区与主目录未改动。唯一 Writer：根 Work（本次用户授权的跨前后端收尾与台账同步）。未合并 PR、未发布生产。

## 结论

- 排期业务决定已记录于 SSOT §29、PRD29 和[联合回执](../../../ccr/CCR-W2-API-001/schedule-review-decisions.md)。两份草案更新至 v0.2，历史v0.1存档。
- SCH-002方向已批，后续先同步07/27/11再实现；SCH-004仍存在人员/指派并发保护、能力集合版本、双方向占用匹配三项技术契约缺口，登记BLOCKED。不是重复请求已批业务决定。
- 服务前端接入已批SERVICE_COVER上传管线，复用原文件/UUID恢复，按用户、商家、门店、编辑目标隔离日志；READY后仍需保存绑定，成功后再清理本地副本。证照上传语义保持。
- 消费者服务详情现有头图位置绑定真实cover字段，过期/加载错误有重读入口；不伪造可访问URL。此为VIS-004数据绑定差异，未重做全量视觉/真机验收。
- PR79空草稿模拟器复验发现F4：POST已成功，但详情/列表解码拒绝null名称，前端误报失败。本分支把可编辑草稿的null名称映射为空表单值，列表显示“未命名草稿”，ACTIVE/REVIEWING仍拒绝空名。
- 用户批准新增独立ServiceCoverSigningApi；已实现thirdparty自有素材检查、精确versionId签名及boot适配。无新增外部签名路由、Schema/Event变化，07/31契约同步。
- **新上传封面的真实签名/图片读取验证已通过**：用户随后在OSS控制台开启mtxoss2版本控制；新合成图片经真实OSS/ClamAV上传READY，获得VERSION_ID，精确版本签名GET返回200且标准化内容摘要一致。19项兼容性复验通过（含原私有材料真实HTTP上传/原请求重放）。不修改代码即可解除新对象的版本条件阻塞；旧ETag对象未迁移，完整UI/真机链仍未验收。

## 本地验证（执行记录）

| 范围 | 结果与边界 |
|---|---|
| 小程序 typecheck、Node测试 | PASS，178/178；含原证照上传回归、封面purpose、隔离/旧响应/原请求恢复、终态拒绝重选、签名URL过期以及空草稿解码。 |
| 真实模式Taro构建、包检查 | PASS；静态包体预算通过；不等于微信上传或真机验收。 |
| ServiceWriteHttpTest | PASS，2/2；真实MySQL/Redis，含PR79最小草稿→补齐→审核链；素材/微信事实依该既有测试缝隙。 |
| PrivateAssetHttpTest | PASS，9/9；HTTP契约层，非真实OSS证明。 |
| 新签名专项 | PASS，ServiceCoverSigningMySqlTest 2、S3ServiceCoverObjectSignerTest 2、ServiceCoverSigningConfigurationTest 2；验证证照/nonREADY拒绝、精确版本、默认关/双开关、失效与无泄密错误。 |
| 架构Python工具 | PASS，13/13；依赖及显示状态源检查通过。 |
| e2e离线契约套件 | PASS，115/115；90操作冒烟；不是在线全业务E2E。 |
| 全量Maven clean verify | PASS，429 tests，0 failures / 0 errors，0 skipped；含全量架构检查；汇总见 validation-summary.json。 |
| 真实OSS/ClamAV专项 | 历史首次因ETAG_ONLY而FAIL/ENV_BLOCKED；用户开启版本控制后的新上传复验PASS：VERSION_ID、签名GET 200、摘要一致。19项兼容性测试全部通过，无skip；旧失败保存在validation-summary.json的liveCoverHistory，不改写历史。 |
| 新排期业务测试 | NOT_EXECUTED，本轮只审阅草案，没有排期业务实现。 |

## 模拟器复验

平台：微信开发者工具skill 0.3.9，登录有效；独立worktree小程序，真实接口模式，后端M002IntegrationServer 18082、独立MySQL 33453、Redis 16383。

身份缝隙：固定微信Provider经真实HTTP签发测试会话，再注入模拟器本地存储；服务端查询会话后进入工作台。入驻与批准/签署经真实HTTP种子链，入驻材料为该既有fixture事实替身。未声称真实微信授权或真机。

1. 工作台ALLOWED→服务管理→新建，完全不填写业务字段点击保存。首次发现F4；修复后显示“草稿已保存。”（[截图](empty-draft-saved.jpg)）。
2. 同一空草稿点击提交审核，被页面必填检查拦截（[截图](empty-draft-submit-blocked.jpg)），不伪造审核通过。
3. 返回服务管理列表可读取，名称显示“未命名草稿”（[截图](unnamed-draft-list.jpg)）。
4. 网络/数据库结果摘要见 `empty-draft-readback.json`；原始网络可能含会话信息，仅留本地runtime目录，不提交。

封面上传的页面逻辑由单测/构建覆盖，真实provider上传由单独live验证覆盖；本轮**没有**把二者组合声称为“选图→真实上传→审核→消费者图片展示”完整UI闭环。2026-09-24后续复验解除新上传对象的版本化环境阻塞，但没有重新执行完整UI链，不能冒认页面/真机验收。

## 变更面与遗留

- 代码：frontend-miniapp共享上传组件/商家服务页/消费者封面；thirdparty-api/biz签名；boot装配及测试。无biz→biz与跨域Mapper。
- 契约：独立内部封面签名接口（已批）；HTTP字段/路径不变，Schema/Event无变更。排期v0.2仍是分层审阅产物，未把候选写入契约伪装已冻结。
- 配置：`pet.private-assets.enabled`与`pet.service.cover-signing.enabled`同时true方可装配；显式`pet.service.cover-signing.window-seconds`，无生产默认值。未开启生产开关。
- 非版本化/历史ETag对象仍不能签名，本轮没有迁移或覆盖旧素材。真实新版本对象的正向GET已通过，见 versioned-oss-recheck.json；不得据此开启生产应用开关。
- 权威旧文档中的已交付通知/窗口走查等过时描述由根WORK_STATE v8.2及队列页首覆盖；旧状态已存档，不覆盖历史测试结论。
- 人员保护G1、集合版本G2、双方向匹配G3需后续CCR冻结；员工HTTP与页面按原模块推进。200项服务能力上限没有被批准，未写进产品规则。

## 回滚

本轮代码可随PR整体revert；签名开关默认关闭，关闭该开关保留原503行为。无数据库迁移；bucket版本控制由用户随后在控制台开启，不能通过revert代码撤销该云端设置。用户主目录原三个改动保持。排期人工裁决是独立业务事实，回滚代码不撤销裁决，文档回滚需明确说明。


## 版本控制开启后的追加复验

用户在本任务中明确反馈“已经开启成功了”。同一代码提交重新执行真实OSS/ClamAV签名测试及私有上传兼容性，共19项通过。证据：[versioned-oss-recheck.json](versioned-oss-recheck.json)。

测试仅创建/精确删除本轮合成对象，不修改bucket配置、ACL或历史图片。新上传获得非null版本ID，证明配置已对本次上传生效。原x-oss-forbid-overwrite请求头不能再被视为版本化环境的原子防覆盖保证；本次验证覆盖了原请求幂等重放、已有内容核验、精确版本读取和正向签名下载，未声称穷尽所有并发或外部写入场景。应用不借本次验证开启生产签名装配。
