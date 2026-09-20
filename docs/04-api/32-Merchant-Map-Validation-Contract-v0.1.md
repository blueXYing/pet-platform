# 商家申请地图核验契约 v0.1

授权与状态：CCR-MER-MAP-001 已授权补齐真实后端地图 Provider，具体距离阈值仍待用户选择。本契约处于 `IMPLEMENTATION_READY_DISTANCE_POLICY_PENDING`，不能据此启用生产。适用 MER-001 商家申请提交；开放城市仍只有 `chengdu / 成都`。

`MapValidationPort.isReasonable(cityCode,address,longitude,latitude)` 是服务端可信边界。生产 adapter 使用腾讯位置服务正向与逆向地理编码，不接受 bounding box、客户端城市文本或经纬度数值范围作为成功替代。

## 输入与腾讯协议

- `cityCode` 只接受 `chengdu`；其他值返回 false 且不调用 Provider。
- address、longitude、latitude 来自已保存的申请 revision；坐标为 GCJ-02。
- 正向：`GET /ws/geocoder/v1/?address=...&region=成都&output=json&key=...`。
- 逆向：`GET /ws/geocoder/v1/?location={latitude},{longitude}&get_poi=0&output=json&key=...`。
- 不跟随重定向；响应最多 256 KiB；单次请求使用覆盖 headers 与完整 body 的整体 deadline，超时主动取消 body subscription。

## 成功规则

正向与逆向响应都必须 `status=0`、字段类型完整。正向必须证明四川省成都市，逆向必须证明中国、四川省、成都市；两者都须提供成都行政代码。正向结果另须 `reliability>=7`、`level>=9`，Provider deviation 和正向坐标到提交坐标的 Haversine 距离都不得超过显式批准距离。

业务无结果、城市/质量/距离不符返回 false；依赖错误抛 `COMMON_DEPENDENCY_UNAVAILABLE`。调用者沿用既有提交语义：false 为位置不合理冲突，依赖异常为 503，不把依赖故障伪装成用户地址错误。

## 启用

默认关闭。启用要求：

- `pet.merchant.map.enabled=true`
- `TMAP_WEBSERVICE_KEY` 非空
- `pet.merchant.map.max-distance-meters` 显式正数
- 可选 `pet.merchant.map.timeout-millis`（默认 3000，允许 100～10000）
- endpoint 默认且生产只允许 `https://apis.map.qq.com`；loopback HTTP 只用于受控协议测试

代码不保存 Key，不记录地址、Key、完整 URI 或腾讯原始错误。工程候选距离 1000 米记录于 CCR，但尚未批准且未被设为产品默认；正式启用前必须由用户明确选择该值或提供替代值。
