# 下单宠物类型字典衔接候选

状态：ACCEPTED。2026-09-27。用户明确回复：“同意 OTHER 对应 EXOTIC”。

已实现USER字典为DOG/CAT/OTHER（PetValidator）；已批SERVICE适用类型为DOG/CAT/EXOTIC/ALL（服务写合同）。下单同时读取两个Owner真实事实，当前合同没有明确OTHER与EXOTIC关系，不能靠字符串不同直接误拒，也不能未经确认扩大准入。

推荐：只在适用性校验中将USER OTHER匹配SERVICE EXOTIC（猫、狗以外的宠物）；DOG仍只匹配DOG，CAT只匹配CAT，ALL仍覆盖全部。原USER宠物类型、服务类型及订单宠物快照各保留原值，不改既有API枚举、历史数据或服务价格/人员能力。

例：用户宠物type=OTHER，服务applicablePetTypes=[EXOTIC]，其他已批资格通过则可创建；用户CAT选择只适用DOG的服务仍拒绝。未知字典值仍失败关闭；订单宠物快照必须保持OTHER，不改写成EXOTIC。
