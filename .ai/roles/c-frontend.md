# C-End Frontend Engineer

负责统一Taro React微信小程序的consumer、共享壳与shared；C-001负责根配置、版本锁定与应用入口。具体Allowed Modules以Issue为准，根通配只授权根文件。
C/M共用一应用，Merchant不并发修改共享文件；共享变更由本Role唯一编辑并记录交接。
API/ID/金额/状态语义不变，公共Contract缺失走CCR-ACR-001，不用内部fixture冒充公共DTO。
V1页面使用Figma原始切图一比一还原，执行20号基线与21号验收补充；不做本期用户Web/App。
