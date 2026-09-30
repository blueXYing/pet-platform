# 售后非出款 HTTP 实施记录

日期：2026-09-30。基线：[PR97](https://github.com/blueXYing/pet-platform/pull/97) 合并提交 b15665b6573270e8c9123e667eedc606aac54398，合并后 [CI36688416117](https://github.com/blueXYing/pet-platform/actions/runs/36688416117) 六项成功；132份实际后端XML、935测试、失败/错误/跳过均0。当前批次独立验收，不能沿用该数字声称完成。

用户在下一步建议后回复“那么请你开始”，范围见 [CCR](../../../ccr/CCR-W2-API-001/aftersale-http-proposal.md)、[Contract51](../../../../docs/04-api/51-AfterSale-Http-Contract-v0.1.md)。复用 refund-aftersale 工作树，新分支 codex/aftersale-http-20260930；原桌面目录未提交文件不动。既有 pr96-ci 提醒保持停用。

## 已实现表面

- C 本人资格、创建、分页、卷宗、补证、撤回；M 当前 OWNER 指定门店分页、卷宗、证据、四类意见；OPS 当前会话和 read/handle/decide 权限及即时资源范围下的受理、补证要求、重复问题关闭和 REJECT/RESERVICE/OTHER 三类非退款终局。
- HTTP 路径明确绑定 USER/MERCHANT/OPS，不能以通用参与方身份替代。列表权限谓词在本域 SQL COUNT/LIMIT 前生效，商家/运营必须同时指定 merchantId/storeId，当前城市取 MER 锁内事实；本人及门店权限首尾复验。SQL51只补两项分页索引，不运行生产迁移。
- 实际首次创建返回201，同键成功重放200；依据持久提交结果，不猜测时间或当前状态。请求保留原始UUID，身份不从body取。原始JSON节点与DTO双重检查金额/ID/版本String、唯一键、未知字段和附加文档；金额两位，时间UTC毫秒。
- 私有图片真实上传/扫描/规范化/入卷；证据grant独立绑定当前端别、会话、工单批次、对象hash/version及当前权限。五分钟、一次消费，在对象下载前消费，在下载后再次核对撤权/隔离，响应有水印和禁止缓存头。旧内部短期grant格式不变，新HTTP不能降级到无端别接口。
- 全部新开关默认false；完整真实C/ADMIN会话与工作流依赖缺失则不可启动HTTP。公开 FULL_REFUND/PARTIAL_REFUND 无条件关闭，不随内部资金开关变化，不执行 decide、退款单/执行/出款；三类非退款决定不需要资金Provider。

## 实际发现与修复

1. 生产私有原因加密原来只接受商家材料用途，新增AFS读取用途会失败。仅增加明确的AFS用途白名单，AES-GCM AAD仍按用途隔离，补解密及错误用途失败验证。
2. 真实HTTP DTO测试发现Jackson禁用通用scalar coercion仍会把数字12.50变为String。主入口改为原始JSON类型检查后映射，禁止数字/布尔代替字符串，不修改其他旧接口。
3. 独立QA发现撤回首轮ACTIVE鉴权后、提交前只有读取鉴权，冻结用户仍可能提交。撤回新增提交前ACTIVE及本人归属复验，真实Outbox发布钩子后独立冻结用户验证全部业务迁移回滚。
4. 孤立Unicode surrogate在UTF-8编码时会被替换，与问号产生相同grant参数摘要。HTTP与内部证据入口拒绝孤立代理字符，保留合法emoji；补同key异参、零新增grant/审计证明。
5. 框架在controller选择前遇到不支持的Content-Type原映射500，补安全415和四字段响应；AFS认证失败trace与入口X-Trace-Id保持一致。

6. 架构门禁把Spring `org.springframework.http.ResponseEntity` 按Entity后缀误判持久化类型；仅精确排除该HTTP承载类型，补正向和项目Entity/Repository/Mapper/SQL负向夹具，原边界不放宽。

7. 收尾补测发现 eager multipart 在选择控制器前解析，绕过证据控制器局部异常处理。真实已登录HTTP的10MiB+1文件及缺boundary请求均先复现500/旧五字段，零持久写入；全局advice仅对售后证据上传的精确路径补413/400四字段映射。原私有上传及其他路径行为保持，补负向范围验证；三项完整证据HTTP及架构回归复测通过。

测试准备期修复了隔离Redis前缀格式和将旧AUTH envelope误套新AFS四字段断言的问题。C端重新登录允许旧有效会话继续存在，不擅自改为单会话；撤销验收使用真实logout。这些准备修复不计为已发现业务缺陷。

## 验收证据与限制

本地分组验收通过：按类取最终运行并核对实际XML，19个测试类 / 144项测试，失败、错误、跳过均0。包括真实三端主HTTP4项、私有证据HTTP3项、原售后事务/核销竞争/资金与任务回归、装配及架构门禁；保留先前失败记录，不将重复运行累加。实际清单见 [local-test-summary.json](local-test-summary.json)。契约smoke、127项文档测试、18项架构脚本测试及模块依赖/MyBatis/展示状态源检查通过。

[PR98](https://github.com/blueXYing/pet-platform/pull/98) 已建立。首轮CI36696633916五项成功、后端因旧S1测试直接访问已改为引用的AFS schema而失败；该测试已按Contract51读取真实DTO并复测通过。新head完整CI待核对，本地分组通过不替代全仓CI；最终结果回填PR描述，不自动合并。

测试采用独占本地MySQL 8.4端口3315及每例随机schema、Redis 7.4端口16385及随机命名空间。真实 PetPlatformApplication、C/ADMIN HTTP登录、生产过滤器/装配、AFS/ORDER/REFUND/VERIFY持久服务和事务一起运行。订单资格由真实内部下单/支付/普通拒绝/核销产生，不直接插入正向售后工单、决定或来源证明。

微信、外部内容审核、扫描及OSS为明确测试Provider；实际图片规范化/水印、真实身份与授权、事务和grant持久化均执行。HTTP套件无资金资格Provider，退款channel计数且禁止调用。旧A2退款回归中的可退资金和渠道仍为test-only，不代表真实资金链验收。

未交付：页面/真机、STAFF真实成员绑定与动作授权、运营跨店聚合列表、REF-001、真实资金分账/追回及出款资格、通知实际送达与券/积分/评价消费者、生产开关及迁移。worker已有内部真实恢复验收；本批HTTP套件不声称已部署自动调度。RESERVICE只记录人工处理安排，无第二订单/预约/核销。
