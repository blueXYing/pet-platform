# CCR-MER-MAP-001 — 商家申请位置输入裁决

状态：**APPROVED**。2026-09-20 用户对地址与地图选点最大偏差明确裁决：“不对位置进行限制”。该最新裁决覆盖本文件早先讨论的腾讯位置服务、正反地理编码、城市/地址匹配和距离阈值方案。

## 1. 已批准规则

- 商家申请仍必须填写地址、经度和纬度。
- 地址必须非空且不超过 255 个 Unicode code points。
- 经度必须在 `[-180,180]`，纬度必须在 `[-90,90]`，边界可用。
- 不根据地址、坐标或两者偏差限制商家位置；不调用地图 Provider，不做正向/逆向地理编码，不做 bounding box、行政区匹配、距离计算或真实性推断。
- `cityCode` 的开放资格仍由既有 `OpenCityReader` 独立控制。V1 首期开通目录仍只有 `chengdu / 成都`，本裁决不开放其他城市。
- 保存用户通过 `chooseLocation` 取得的地址和 GCJ-02 坐标事实；服务端格式检查不把坐标改写为其他城市，也不声称完成真实地图核验。

## 2. 技术落点

现有 `MapValidationPort` 暂保留以避免扩大 MER API 变更，但默认实现明确命名为 `LocationInputValidationProvider`。它只检查地址必填/长度和地球合法经纬度范围，忽略 cityCode 的地理含义且不发网络请求。

当 `pet.merchant.application.enabled=true` 时，boot 默认装配该本地 validator；不再需要 `pet.merchant.map.*`、`TMAP_WEBSERVICE_KEY` 或腾讯服务可用性。测试必须证明成都申请携带北京、上海等合法坐标仍可通过位置输入检查，同时缺失地址、地址超长和越界经纬度会被拒绝。

## 3. 被取消方案

下列内容未获批准并已移除：腾讯 WebService Key、正向/逆向地理编码、`reliability/level/deviation` 门槛、Haversine 最大偏差、行政代码交叉核验、外部地图失败关闭。此前工程候选 1000 米不是产品规则，不再等待配置或上线验收。

该变更不影响私有材料、认证、运营权限、审核任务领取或其他交易规则。
