# MER-001 S7 接通实施与范围

基线：PR53/54/55已按用户要求合入develop，当前632ced7，合并CI三次均成功。用户明确批准三线实施，并在本轮确认复用本地OSS、采用微信原生选点、首推成都、身份证件仅接受大陆居民身份证（提问包含历史15位）。

独立分支：codex/mer001-integration-20260920。主目录用户project.config.json保持不动。

- s7_http / GPT-5.6 Sol medium：boot HTTP、真实身份/权限接线、精确wire DTO、可配置城市目录、适配器与配置测试。
- s7_dependencies / GPT-5.6 Sol medium：字段加密、大陆证件规范化/HMAC适配及测试；私有上传/读取CCR。
- s7_frontend / GPT-6 Astra medium：持久化意图和原请求恢复、API能力开关、服务端城市目录、微信原生选点、前端测试。
- s7_security_qa / GPT-5.6 Luna xhigh：独立隐私/密码与恢复检查，收紧内部人工核验的材料引用强制校验。
- 根任务：merchant API投影与持久化返回事实、真实TCP/会话/MySQL/Redis/AES/Outbox联合测试、契约一致性、权威裁决、整合与PR。

本轮不授权生产发布或自动合并新PR，不新建第二套业务状态、不放宽材料/权限校验。真实私有资产上传/水印读取与扫描、服务端地图一致性校验不以测试替身或前端地图成功代替。新增未批准私有资产契约按CCR推进，不能直接使用公开素材表存证件。
