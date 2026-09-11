# GOV-001 后续架构证据只读复核

2026-09-11，Backend Core。状态：**GOV-001仍未完成**。本次仅审阅后续固定提交的代码和已提交日志，没有在GOV-001纯基线运行新门禁，没有合并/cherry-pick其他Issue实现，也没有更改原始失败证据。

## 准确适用范围

- 原始导入：`e8654c30b4dfe2cfc9e3c050110322d2a18bb6a1`。
- GOV-002复核对象：`79180f30c39dcd8f5d2d2a0e4f24746da9c39f1a`，其父为 `09e94530cfe55237136a0db816a1e3f9ad0f5cc8`（采用PLAT-001 `6cfefa8799a6c1bcba8c48a35940215e1a6c4f7d`的修复），再往前为上述导入提交。
- 检查GOV-002工作区相关backend测试/tools/.github与固定提交无差异；核实导入提交是该组合的祖先。未修改GOV-002工作区。
- GOV-001 PR #1原验证对象远端 `5fb3950d3ac214dab2db5cc90deaf35a09ca7967`（本地树相同的 `7bf703fbd173c1e99e99ceafd4e7c6ba062a1aff`）不包含这些实现；其CI失败与历史Maven失败记录仍有效。

## 已核对证据

| 项目 | 只读核对结果 |
|---|---|
| GOV-002全量构建日志 | Java21 clean verify的41项目Reactor全部SUCCESS；22项JUnit，0失败/错误/跳过，完成时间2026-09-11 11:34:37 +08:00 |
| 测试拆分 | 15项Java合成fixture执行 + 7项生产/源码门禁；Python测试13项通过（也由Maven测试入口调用），不能把它们当作未来业务测试 |
| ARCH-002规则 | 所有导入生产类的直接字节码依赖；按com.petplatform.<domain>归属判断跨模块，持久化包名、Repository/Mapper/DO/Entity/DAO后缀及注解识别 |
| ARCH-002负例 | 普通refund/admin业务类引用order持久化类型，涵盖Repository/Mapper/DO/Entity及无后缀持久化包；fixture先编译成功，再断言违反ARCH-002 |
| ARCH-002正例 | 同模块持久化、跨模块API允许，防止一律拒绝造成假通过 |
| 扫描范围 | 39个生产模块，294个含package-info的类，其中50个非package-info类；真实Controller/Domain仍为0，不能宣称业务覆盖 |
| ARCH-001真实Enforcer负例 | 读取PLAT证据提交 `f33d7c241887ae37a84ab6cbe6101df20c2249ee` 的 `backend/evidence/PLAT-001/arch-001-negative.txt`，确认refund-biz失败来自ban-cross-biz-dependencies/BannedDependencies并明确点名order-biz，不是编译错误冒充通过 |

GOV-002证据路径：`backend/tools/evidence/GOV-002/`。复核文件SHA-256：

- maven-verify.txt：`AE3CC9F47F3CDCCC7AB97110A6B4246946D89F8B5059299775C703B44ED1C0EE`
- python-fixtures.txt：`3EB284651123FE98290566C5E3579BB0575831DFB7FE8895084319DCA2951FA1`

证据执行者为GOV-002/PLAT-001，本次GOV-001只读核对，不声称独立重跑。首轮规则错误及修正日志由GOV-002保留，未跳过失败以通过。

## 结论与剩余门禁

后续组合的本地证据已覆盖GOV-001此前指出的构建缺口和ARCH-002规则缺口；原纯基线未获得追溯性绿灯。远端对应PR/CI及PLAT独立复验仍由根Work核验，正式集成、GOV-001状态更新及依赖解锁依既有人工门禁与EX-W1-001处理。本次不标DONE、不改变产品或Contract、不合并main/develop。

边界：字节码直接依赖规则不证明反射、外部SQL或未来业务正确性；声明的静态分析限制继续适用。微信运行时、视觉、交易并发等不属于这次架构证据。
