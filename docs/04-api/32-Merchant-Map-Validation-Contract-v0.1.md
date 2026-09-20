# 商家申请位置输入检查契约 v0.1

批准来源：CCR-MER-MAP-001。用户已明确裁决“不对位置进行限制”。本文描述位置输入格式检查，不称其为地图核验。

## 输入规则

`MapValidationPort.isReasonable(cityCode,address,longitude,latitude)` 当前兼容接口由 `LocationInputValidationProvider` 实现，只返回以下条件的合取结果：

- address 非 null、非空白、Unicode code point 数不超过 255；
- longitude 非 null，且用 BigDecimal 精确比较位于闭区间 `[-180,180]`；
- latitude 非 null，且用 BigDecimal 精确比较位于闭区间 `[-90,90]`。

validator 不读取 cityCode 的地理含义，不根据地址推断城市，不比较地址与坐标，不限制坐标落点，不调用外部服务。成都申请携带任何地球合法坐标均可通过本检查。

## 城市开放边界

调用顺序仍由 MER 保持：先用独立 `OpenCityReader` 检查 cityCode，再检查位置输入。首期开通目录仍只有 `chengdu / 成都`；其他 cityCode 会被开放城市守卫拒绝，与坐标位置无关。

## 装配

当 `pet.merchant.application.enabled=true` 时，boot 默认提供 `LocationInputValidationProvider`，并允许测试或未来明确裁决后的实现通过 `@ConditionalOnMissingBean(MapValidationPort.class)` 替换。不需要地图 Key、endpoint、timeout 或距离配置。

原腾讯位置服务、正反地理编码、行政区匹配和最大偏差方案已由最新用户裁决取消。地址和 GCJ-02 坐标仍按申请 revision 保存，但服务端不声称这些事实经过地图真实性验证。
