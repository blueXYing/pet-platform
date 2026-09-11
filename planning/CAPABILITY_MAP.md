# V1.0 三端统一 Capability Map

ACR-001终端映射：C端与商家端是同一Taro React微信小程序的consumer/merchant工作区；运营端是React网页。下表的三端为业务角色视角，不代表三个独立发布应用；能力和产品范围不变。

| Capability | C端 | 商家端 | 运营端 | 后端领域 |
|---|---|---|---|---|
| Identity & Access | 登录、账号设置 | 登录/身份切换 | 后台账号/RBAC | user/admin |
| Pet Profile | 宠物档案 | 客户宠物查看 | 客户宠物档案 | user |
| Merchant & Store | 商家详情、入驻 | 店铺资料 | 入驻审核、商家台账 | merchant/admin |
| Service Catalog | 服务列表/详情 | 服务项目管理 | 服务审核/管理 | service/admin |
| Schedule & Capacity | 预约时间查询 | 排期/预约管理、员工 | 排期监管 | schedule |
| Order | 下单、列表/详情 | 接单/拒单/订单管理 | 订单总览/异常处理 | order |
| Payment | 微信支付 | 订单支付事实展示 | 交易查询 | payment |
| Reschedule | 用户改期 | 承接改期 | 改期监管 | order/schedule |
| Refund | 退款申请/结果 | 同意/拒绝 | 退款单管理/异常兜底 | refund/order |
| Verification | 核销码 | 平台订单核销 | 核销记录 | verification |
| After-sale | 售后/投诉 | 证据/意见 | 工单池/终局裁决 | aftersale/admin |
| Coupon | 我的优惠券 | - | 模板/活动/实例 | coupon/admin |
| Points | 签到/任务/余额流水 | - | 任务/积分管理 | points/admin |
| Review | 评价 | 评价管理/申诉 | 评价治理/申诉处理 | review/admin |
| Notification | 消息中心 | 消息中心 | 消息/公告 | notification |
| Community | 动态/发布/详情/收藏关注 | - | 动态审核/话题 | community/admin |
| Pet Encyclopedia | 百科 | - | 百科/品种库配置 | content/admin |
| Third-party Group Buy | 无平台订单 | 美团/点评核销 | 授权/映射/台账 | thirdparty/admin |
| Customer Service | 人工客服入口 | 相关业务协同 | 人工客服承接 | customer-service TBD |
| Analytics | 个人业务查看 | 经营数据 | 运营看板 | query/reporting |

说明：同一个 Capability 必须跨三端联合分析，不按三份 PRD 分成三套互不相关的业务。
