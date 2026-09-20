# CCR-MER-MAP-001 — 成都商家申请后端地图核验

状态：用户于 2026-09-20 明确要求继续补齐真实后端地图核验；适配器及8项定向测试通过，真实服务验收待配置。该授权确认 Provider 方向和成都范围，**没有批准具体距离阈值**。此授权不扩大开放城市；腾讯位置服务密钥不得写入仓库或普通日志。

## 1. 范围

- 仅服务既有 `cityCode=chengdu / 成都` 商家申请提交。
- 输入坐标沿用微信地图提供的 GCJ-02，经纬度在 Java 输入中仍为 longitude/latitude；调用腾讯位置服务时按其协议发送 `纬度,经度`。
- 不新增城市，不把坐标数值范围、成都 bounding box 或客户端选点结果当作地图核验。
- 不增加地址纠正、POI 搜索、门店展示或路线能力。

## 2. 权威事实与判定

后端使用腾讯位置服务 WebService `GET /ws/geocoder/v1/` 做两次独立读取：

1. 正向地理编码：`address + region=成都`，取得 GCJ-02 坐标、地址部件、行政代码、reliability、level、deviation。
2. 逆向地理编码：用户提交的 `latitude,longitude`，取得地址部件、adcode 与 city_code。

通过必须同时满足：

- 正向明确返回四川省/成都市，逆向明确返回中国/四川省/成都市；adcode 属于 `5101xx`，逆向 city_code 为 `510100`；
- 正向 `reliability >= 7`、`level >= 9`。这是腾讯地址服务说明中“较为准确”和“门址/POI 精度”的技术质量线；
- 腾讯返回的正向 `deviation` 不超过批准的距离策略；
- 正向坐标与用户提交坐标的 Haversine 球面距离不超过同一距离策略。

腾讯业务无结果、城市不符、精度不足或距离不符返回 `false`，MER 按既有规则拒绝提交。HTTP 非 200、鉴权/配额/服务错误、超时、重定向、响应过大、JSON/字段损坏均作为 `COMMON_DEPENDENCY_UNAVAILABLE` 失败关闭。

## 3. 距离策略待定

代码不提供产品默认值，启用时必须显式设置 `pet.merchant.map.max-distance-meters`。工程评估候选值为 **1000 米**：腾讯正向响应本身以米提供 deviation，1 公里可以覆盖门址解析落到道路/建筑入口与微信选点的常见差异，同时不会退化为成都城区范围判断。该候选值尚未获得用户批准，不是产品规则，不能据此启用生产。

## 4. 配置与秘密

- `pet.merchant.map.enabled=false`：默认关闭。
- `TMAP_WEBSERVICE_KEY`：腾讯位置服务 WebService Key；只检测是否配置，不写入仓库或普通日志。
- `pet.merchant.map.endpoint=https://apis.map.qq.com`：生产固定官方 HTTPS；仅协议测试允许 loopback HTTP。
- `pet.merchant.map.timeout-millis=3000`：单次请求连接/整体超时，允许范围 100～10000 毫秒。
- `pet.merchant.map.max-distance-meters`：必填批准距离，代码无默认通过值。

启用但缺少 Key、距离策略或配置非法时启动失败。运行时 Key、完整请求 URI、原始腾讯错误消息和用户地址不进入应用日志。

## 5. 依据与验收边界

- 腾讯位置服务地址服务：<https://lbs.qq.com/service/webService/webServiceGuide/webServiceGcoder>
- 腾讯位置服务 WebService 概览：<https://lbs.qq.com/service/webService/webServiceGuide/webServiceOverview>
- 外部技能：`tencentmap-webservice-skill` 1.0.4（腾讯位置服务团队发布；仅用于协议指导）。

受控 loopback 协议测试验证参数顺序、行政事实、质量/距离、业务错误、HTTP 错误、畸形 JSON 与超时。没有正式 Key 时不声称腾讯生产链路已验收；上线前仍须验证正式 Key 权限、服务器 IP 白名单、额度/QPS 与成都真实样本。
