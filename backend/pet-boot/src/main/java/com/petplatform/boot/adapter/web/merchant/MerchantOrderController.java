package com.petplatform.boot.adapter.web.merchant;
import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;
import com.petplatform.order.api.command.MerchantOrderCommandApi;
import com.petplatform.order.api.query.MerchantOrderQueryApi;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
@RestController
@RequestMapping(value="/api/v1/merchant/orders",produces=MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name="pet.order.merchant.http.enabled",havingValue="true")
public final class MerchantOrderController {
    private static final ObjectReader JSON=com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
    private final MerchantOrderCommandApi orders;
    private final MerchantOrderQueryApi queries;
    public MerchantOrderController(MerchantOrderCommandApi orders,MerchantOrderQueryApi queries){
        this.orders=orders;this.queries=queries;}
    @ModelAttribute void cache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    /** 商家订单列表（10号 §4.1 增补）：只读、商家坐标作用域、固定排序、PageResult 信封、防枚举 403。 */
    @GetMapping Map<String,Object> list(@RequestParam(required=false) String merchantId,
        @RequestParam(required=false) String storeId,@RequestParam(required=false) String displayStatus,
        @RequestParam(required=false) String page,@RequestParam(required=false) String pageSize,
        HttpServletRequest request){
        onlyParameters(request,"merchantId","storeId","displayStatus","page","pageSize");
        var session=mini(request);
        var value=queries.listStoreOrders(new MerchantOrderQueryApi.StoreOrderListQuery(id(merchantId),
            id(storeId),displayStatus,number(page,1),size(pageSize),
            new com.petplatform.common.QueryContext(trace(request),com.petplatform.common.OperatorType.USER,session.userId())));
        List<Map<String,Object>> items=value.items().stream().map(MerchantOrderController::summary).toList();
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("items",items);data.put("page",value.page());data.put("pageSize",value.pageSize());data.put("total",value.total());
        return envelope(data,request);
    }
    private static Map<String,Object> summary(MerchantOrderQueryApi.Summary item){
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("orderId",item.orderId());row.put("orderNo",item.orderNo());row.put("displayStatus",item.displayStatus());
        row.put("payAmount",amount(item.payAmount()));row.put("appointmentStart",time(item.appointmentStart()));
        row.put("appointmentEnd",time(item.appointmentEnd()));row.put("paidAt",time(item.paidAt()));
        return row;
    }
    /** 11号 DecimalAmountOutput：恒两位小数（与 C 端 OrderSnapshotWire 同渲染）。 */
    private static String amount(java.math.BigDecimal value){
        return value==null?null:value.setScale(2,java.math.RoundingMode.UNNECESSARY).toPlainString();
    }
    /** Page guard: digit-only, >=1 (contract bounds live in the order module). */
    private static int number(String raw,int fallback){
        if(raw==null||raw.isEmpty())return fallback;
        if(!raw.matches("[0-9]{1,10}"))throw invalid();
        long value=Long.parseLong(raw);
        if(value<1||value>Integer.MAX_VALUE)throw invalid();
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
    @PostMapping(value="/{orderId}/confirm",consumes=MediaType.APPLICATION_JSON_VALUE)
    Map<String,Object> confirm(@PathVariable String orderId,@RequestBody String raw,HttpServletRequest request){
        onlyParameters(request);mini(request);JsonNode n=read(raw,Set.of("expectedConfirmRound","internalNote"));
        return envelope(orders.decide(new MerchantOrderCommandApi.Command(userCommand(request),id(orderId),round(n),"CONFIRM",null,null,
            n.has("internalNote")?text(n,"internalNote"):null)),request);
    }
    @PostMapping(value="/{orderId}/reject",consumes=MediaType.APPLICATION_JSON_VALUE)
    Map<String,Object> reject(@PathVariable String orderId,@RequestBody String raw,HttpServletRequest request){
        onlyParameters(request);mini(request);JsonNode n=read(raw,Set.of("expectedConfirmRound","reasonCode","reasonText"));
        return envelope(orders.decide(new MerchantOrderCommandApi.Command(userCommand(request),id(orderId),round(n),"REJECT",text(n,"reasonCode"),text(n,"reasonText"),null)),request);
    }
    private static JsonNode read(String raw,Set<String> allowed){
        try{JsonNode n=JSON.readTree(raw);if(n==null||!n.isObject())throw invalid();
            for(String name:n.propertyNames())if(!allowed.contains(name))throw invalid();return n;
        }catch(RuntimeException bad){throw invalid();}
    }
    private static int round(JsonNode n){if(!n.path("expectedConfirmRound").isIntegralNumber()||!n.path("expectedConfirmRound").canConvertToInt()||(n.path("expectedConfirmRound").intValue()<0||n.path("expectedConfirmRound").intValue()>1))throw invalid();return n.path("expectedConfirmRound").intValue();}
    private static String text(JsonNode n,String field){if(!n.path(field).isTextual())throw invalid();return n.path(field).asText();}
}
