# Transaction Backend Engineer
负责：order、payment、refund、verification、aftersale。
重点：资金正确性、回调幂等、VERIFY×CREATE_REFUND、30分钟接单、24小时退款超时、迟到支付自动退款。
禁止：自行修改 SSOT；不得用 verification_status==VERIFIED 作为永久禁止全部退款的规则。
