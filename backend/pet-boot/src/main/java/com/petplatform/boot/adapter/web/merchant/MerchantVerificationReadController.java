package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.query.MerchantOrderQueryApi;
import com.petplatform.verification.api.query.MerchantVerificationReadApi;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * CCR-M-VERIFY-RECORD-001 (approved 2026-11-12) merchant verification-record list (Figma
 * 12:7426): GET /api/v1/merchant/verifications, OWNER-only read over the store's own
 * verification_record rows. The kernel re-proves the schedule store guard + OWNER coordinate
 * (anti-enumeration 403); this adapter assembles the page's order-card facts (CCR-M-ORDER-CARD
 * 001 caliber: nickname/avatar, two-decimal amount, no phone) with the order domain's batch
 * read at the same coordinates. operatorKind/operatorName follow the CCR §9 ruling — the raw
 * operatorId never crosses this surface. Default off behind
 * pet.verification.merchant-read.http.enabled.
 */
@RestController
@RequestMapping(value="/api/v1/merchant/verifications",produces=MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name="pet.verification.merchant-read.http.enabled",havingValue="true")
public final class MerchantVerificationReadController {
    private final MerchantVerificationReadApi verifications;
    private final MerchantOrderQueryApi orders;
    public MerchantVerificationReadController(MerchantVerificationReadApi verifications,MerchantOrderQueryApi orders){
        this.verifications=verifications;this.orders=orders;}
    @ModelAttribute void cache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    /** 核销记录列表（CCR-M-VERIFY-RECORD-001 §3）：只读、OWNER 重验、固定排序、PageResult 信封、订单卡沿 CCR-M-ORDER-CARD-001 口径。 */
    @GetMapping Map<String,Object> list(@RequestParam(required=false) String merchantId,
        @RequestParam(required=false) String storeId,@RequestParam(required=false) String orderId,
        @RequestParam(required=false) String verifiedFrom,@RequestParam(required=false) String verifiedTo,
        @RequestParam(required=false) String page,@RequestParam(required=false) String pageSize,
        HttpServletRequest request){
        onlyParameters(request,"merchantId","storeId","orderId","verifiedFrom","verifiedTo","page","pageSize");
        var session=mini(request);
        var value=verifications.listStoreVerifications(new MerchantVerificationReadApi.StoreVerificationListQuery(
            id(merchantId),id(storeId),optionalId(orderId),
            verifiedFrom==null?null:timestamp(verifiedFrom),verifiedTo==null?null:timestamp(verifiedTo),
            number(page),size(pageSize),
            new QueryContext(trace(request),OperatorType.USER,session.userId())));
        // Same-coordinate second assembly: the card facts come from the order domain's own
        // batch read (double-key scoped), so a missing summary degrades to null, never fabricated.
        Set<String> pageOrderIds=new LinkedHashSet<>();
        for(var row:value.items())pageOrderIds.add(row.orderId());
        Map<String,MerchantOrderQueryApi.Summary> cards=pageOrderIds.isEmpty()?Map.of()
            :orders.storeOrderSummariesByIds(id(merchantId),id(storeId),pageOrderIds,
                new QueryContext(trace(request),OperatorType.USER,session.userId()));
        List<Map<String,Object>> items=new ArrayList<>(value.items().size());
        for(var row:value.items()){
            var card=cards.get(row.orderId());
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("verificationId",row.verificationId());item.put("orderId",row.orderId());
            item.put("serviceName",card==null?null:card.serviceName());
            item.put("payAmount",card==null?null:amount(card.payAmount()));
            item.put("customerNickname",card==null?null:card.customerNickname());
            item.put("customerAvatarUrl",card==null?null:card.customerAvatarUrl());
            item.put("verifiedAt",time(row.verifiedAt()));
            item.put("operatorKind",row.membershipKind());item.put("operatorName",row.operatorName());
            items.add(item);
        }
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("items",items);data.put("page",value.page());data.put("pageSize",value.pageSize());data.put("total",value.total());
        return envelope(data,request);
    }
    /** Optional exact orderId filter: canonical decimal string or absent. */
    private static String optionalId(String value){return value==null||value.isEmpty()?null:id(value);}
    /** 11号 DecimalAmountOutput：恒两位小数（与 MerchantOrderController::amount 同渲染）。 */
    private static String amount(java.math.BigDecimal value){
        return value==null?null:value.setScale(2,java.math.RoundingMode.UNNECESSARY).toPlainString();
    }
    /** Page guard: digit-only, >=1, family bound 1..10000 (CCR §3 同家族分页)。 */
    private static int number(String raw){
        if(raw==null||raw.isEmpty())return 1;
        if(!raw.matches("[0-9]{1,10}"))throw invalid();
        long value=Long.parseLong(raw);
        if(value<1||value>10000)throw invalid();
        return (int)value;
    }
    /** PageSize follows the §3.7 precedent: 1..100, default 20. */
    private static int size(String raw){
        if(raw==null||raw.isEmpty())return 20;
        if(!raw.matches("[0-9]{1,3}"))throw invalid();
        int value=Integer.parseInt(raw);
        if(value<1||value>100)throw invalid();
        return value;
    }
}
