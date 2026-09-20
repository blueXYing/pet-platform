# MER-001 S7 HTTP、恢复和可信依赖交接

## 已实现

- 10个申请审核HTTP、2个协议HTTP及1个受MINIAPP会话保护的开放城市目录。严格拒绝未知/重复JSON与错误标量类型，版本/ID为String，时间毫秒UTC，创建首次201/持久重放200。
- 本人可编辑详情仅在当前owner校验后解密联系人字段；审核详情脱敏，幂等回执/事件不含联系人明文。审核记录、材料、版本引用都来自持久化事实，不能由HTTP伪造。
- 人工核验在内外接口都必须提交materialId/hash并匹配本轮材料。LONG_TERM记录操作者对原件的明确声明，不能当作机器验证。跨owner申请在读取材料前就拒绝，避免泄露资源存在性。
- 字段保护采用purpose/version绑定的AES-256-GCM、随机nonce、独立HMAC equality key；证件lookup另用独立固定policy key。大陆15/18位身份证与统一社会信用代码规范化已实现，依照公开国家标准。格式/校验位通过不证明身份真实。
- 服务端城市目录可配置、默认空时503。首批批准 `chengdu / 成都`，未知城市不能提交。前端从目录选城市，Taro.chooseLocation提供GCJ-02地址/坐标，不推导城市或取代后端位置核验。
- 前端恢复跨重启原create/save/submit及协议同意请求，已确认receipt和下一阶段与请求移除同次落盘。未知提交不再另存新revision；切换身份隔离迟到响应。布局延续S6，不新造签约页面。

## 验证边界

真实TCP联合用例经过真实C/运营会话、MySQL、Redis、AES、证件规范化、领域事务、Outbox与站内消息，依次完成申请/补正/重提/领取释放/人工核验/批准/首次签约。微信登录Provider与私有材料/地图事实是测试适配器；城市使用真实服务端配置目录，证件使用虚构校验正确号码。用例不能作为真实OSS证件上传或真实身份核验通过的证明。

本地OSS读取了用户指定被Git忽略的ops/oss.env.local，仅用于只读GET bucket ACL；HTTP200，ACL private，未改变桶权限、未上传或读取证件。主工作区配置和密钥未进入Git/日志。外部授权校验尚未证明RAM仅具有最小权限。

前端已完成默认/显式能力构建和测试；原生地图接口的真机隐私授权/后台开通尚未验收。正式源码没有测试Provider。最终测试计数与CI以本轮验证清单和PR最新head为准。

本地最终复测47项全部通过（密码适配、HTTP、城市、真实TCP联合链路和22项架构）；此前较广的169项运行仅架构命名依赖检查失败，修正为配置层不可变JSON reader及现有HttpServletResponse状态码方式后，47项复测覆盖该失败。前端100项、离线契约100项、架构工具13项通过。完整最新提交CI结果在本轮PR登记，不把局部复测冒充完整clean verify。

复核修正包括：跨owner先拒绝再解析材料、内外核验强制当前materialId/hash、本人可编辑与运营脱敏投影分离、原请求恢复与receipt原子落盘、成都目录缺配置失败关闭、只接受大陆地址前缀及非未来出生日期、恢复测试夹具绑定当前材料版本、测试库DROP丢ACK的自有库幂等清理。没有降低生产守卫或删除反例测试。

## 配置与保留项

`pet.merchant.application.enabled` 默认不启用，装配需要真实Provider。字段保护另需 `pet.merchant.protection.enabled=true` 与三个外部secret配置；证件另需 `pet.merchant.subject.enabled=true` 与固定policy版本/独立lookup key，版本必须匹配数据库policy。代码不生成生产密钥、不执行迁移。城市配置 `pet.merchant.application.open-cities[0].code=chengdu`、`.name=成都`；部署扩城市仍须产品批准。

前端 `PET_MERCHANT_APPLICATION_ENABLED=true` 与非空 `PET_C_API_ORIGIN` 才接实际申请/城市HTTP；材料上传尚不可用。地图原生适配不需在业务页面保存腾讯WebService密钥，但平台接口权限和后端地理校验是独立配置。

**仍未完成整项MER-001**：真实私有材料上传/扫描/归属注册/水印授权读取、服务端地图校验、完整审核与签约页面、通知跳转及真机/完整VIS验收。[私有材料CCR](../../../ccr/CCR-MER-PRIVATE-001.md)列出新增表、端点和时效/水印方案，当前为待审提案，不能把已有OSS桶等同于这些功能已实现。

资料保留期限尚无产品决定，本轮没有擅自增加自动删除或清理策略。用户已确认的成都/大陆身份证范围已同步SSOT §27和PRD26，不重复询问。
