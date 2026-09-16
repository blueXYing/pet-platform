# 历史选型记录：Snowflake SDK（2026-09-14）

本文件归档当时的比较，不重新提出选型审批。当前事实：PR13 已接受适配方案，PR16 已合入 Hutool 组件；backend/pet-id-core/pom.xml 已依赖 hutool-core 5.8.47。生产宿主、迁移和启用限制仍见 backend/pet-id-core/HANDOFF.md。下文“尚未写入项目/方案待审”等属于评估时点，不能当作当前实现状态，也不是针对未来版本的新推荐。

# 雪花 ID Java SDK 比较与推荐

状态：SDK_SELECTED。人工已明确采用cn.hutool:hutool-core:5.8.47，选型不再待审。生产适配约定待具体方案，当前未修改pom、应用代码或权威契约。读取日期：2026-09-14。下文比较保留原评估语境，最新选型以本段为准。

## 给人工CTO的结论

**首选 Hutool 的 `cn.hutool:hutool-core:5.8.47` 作为雪花算法库，接入已有 `SnowflakeIdGenerator` 接口。** 优先复用而非重新编写位运算/序号算法；不要引入整个hutool-all。暂不批准PR13的原完整自研方案，先依据本比较修订生产接入方案。

这是“选择算法库”的明确推荐，**不是认定一行Maven依赖已满足所有生产要求**。现有已接受约定还要求节点独占、重启安全、回拨停发和一秒预算。所比较库没有被证明能零适配满足这些全部要求；不能为推销SDK悄悄删掉已批准约束。

## 比较

| 候选 | 已核对的事实 | 对本项目的判断 |
|---|---|---|
| Hutool 5.8.47 | Maven Central正式包；41位毫秒+5位机房+5位worker+12位序号，可设置epoch、回拨容忍和随机序号参数。当前child pom无依赖项；隔离Java21单jar实测可运行 | **首选算法核**：与已接受41/10/12布局最接近，10位节点可拆为5+5，无需引入新服务；仍需生产接入适配 |
| 百度 UidGenerator | 官方pom仍1.0.0-SNAPSHOT，JDK8、Spring4.2.5/MyBatis3.2.3等旧依赖；默认28位秒级时间+22位worker+13位序号，worker可基于数据库分配 | 不作为当前首选：原包依赖和时间粒度与本项目现状差异较大；不能把“有数据库worker分配”视作可无成本直接替换 |
| 美团 Leaf | 提供服务化ID方案，可复用leaf-core；Snowflake模式配置ZooKeeper，默认还提供segment路线 | 暂不选：会引入本项目当前没有的协调/部署成本；segment不是本次已选Snowflake格式，不偷偷改算法 |
| Yitter IdGenerator | Java说明给出Maven包；默认漂移算法，保留序号处理回拨，亦有经典模式；worker唯一性仍由外部保证，自动注册方案另用Redis辅助 | 备选而非首选：默认漂移/回拨语义与已接受“回拨停发、不虚构未来时间”不同；经典模式也仍需具体源码/版本适配验证 |
| Twitter原Snowflake | 算法来源；原仓库已归档，是历史服务实现 | 不作为Java21新工程的直接官方Maven SDK方案 |

除Hutool外，本次为官方文档/源码审阅，未安装或实测其他候选；未把它们判定为“Java21不能运行”。不依据项目知名度或宣传性能认定可靠性。

## Hutool 实测与配置方向

从Maven Central读取metadata并选择当次可见5.8稳定版本5.8.47，下载JAR和sources JAR；仓库SHA-1校验匹配。JAR大小1,534,226字节，SHA-256：

`7cc076ad4ed9846dc129edcd2a5e4e01b61a9d715bf0d3b9a7fa707d4f637266`

在项目外 `D:/Temp/pet-snowflake-sdk-review/` 用Java21.0.11实际运行，未修改项目依赖：

- 同一生成器、8线程合计100,000个ID，全部唯一且为正Long。
- 自定义epoch `2026-01-01T00:00:00Z`和节点字段解码一致；String往返不损失整数。
- 回拨检查以反射注入未来lastTimestamp测试：`timeOffset=0`时拒绝发号；没有修改OS时钟。这不是机器重启/真实回拨灾难测试。
- 相同节点创建两个全新生成器，实测可产生相同ID。因此必须保持实例管理，不能每次请求new一个生成器，也不能多个进程重复使用同一节点。

建议适配配置：每个进程唯一实例；明确分配10位nodeId，映射 `workerId=nodeId & 31`、`datacenterId=nodeId >>> 5`；显式传入既定epoch，关闭随机起始序号，回拨容忍设0。不用IP/MAC取模或默认参数冒充全局唯一。先构造验证配置，再提供既有接口的适配器；不把Hutool类型传入业务api。

仅用于审核的Maven坐标（尚未写入项目）：

```xml
<dependency>
  <groupId>cn.hutool</groupId>
  <artifactId>hutool-core</artifactId>
  <version>5.8.47</version>
</dependency>
```

## 不能省略的差距

1. **跨进程/重启状态：** 该类的sequence/lastTimestamp在内存中，库本身不管理共享worker独占或持久化重启边界；“依赖导入成功”不能解除生产S2门禁。
2. **一秒预算：** 所核对版本的序号耗尽等待没有本项目要求的显式超时预算。不能简单用Future超时就宣称内部调用已停止。保持原约定需验证接入层是否可可靠满足，不能做到则提出具体技术契约调整，等批准后才变更。
3. **PR13时间窗方案：** Hutool的取时和等待方法为私有，不能未经验证就认为可以原样嵌入PR13的预留窗口/fence模型。不要把旧自研协调方案整套照搬，再称为轻量SDK接入。
4. **生产条件：** 节点数量、进程重叠、滚动发布、重启和时钟管理必须落实为可验证约束。单JVM10万次测试不证明集群唯一性、长期运行、时钟灾难恢复或安全审计通过。

## 推荐后的工作

我负责将Hutool接入与剩余生产约束整理为精简CCR，比较必要适配和可以放弃的额外复杂度；你只审具体建议。现有S1接口、String ID、金额/Context和Worker测试可保留。若库适配仍需要大幅自研或放宽既定保证，应在实施前明确指出，不隐藏成本。

本报告不自动批准库替换或原PR13。AUTH规范准备可独立继续。暂缓自研S2实现，先确认SDK选型及生产接入约束，再按审批进入代码。

## 主要来源

- [Hutool官方使用说明](https://doc.hutool.cn/pages/IdUtil/)：单例注意事项。
- [Hutool官方源码](https://raw.githubusercontent.com/dromara/hutool/v5-master/hutool-core/src/main/java/cn/hutool/core/lang/Snowflake.java)：构造参数、回拨、内存状态和等待循环。本次另以5.8.47 sources JAR核对相关实现，未只依赖浮动分支。
- [Maven Central 5.8.47](https://repo.maven.apache.org/maven2/cn/hutool/hutool-core/5.8.47/)：实际制品、源码及校验值。
- [百度官方pom](https://github.com/baidu/uid-generator/blob/master/pom.xml)、[DefaultUidGenerator](https://github.com/baidu/uid-generator/blob/master/src/main/java/com/baidu/fsg/uid/impl/DefaultUidGenerator.java)。
- [Leaf官方说明](https://github.com/Meituan-Dianping/Leaf)。
- [Yitter Java接入说明](https://github.com/yitter/IdGenerator/blob/master/Java/README.md)、[主说明](https://github.com/yitter/IdGenerator)。
- [Twitter原仓库](https://github.com/twitter-archive/snowflake)。
