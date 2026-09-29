package com.petplatform.boot.adapter.web.merchant;
import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;
import com.petplatform.order.api.command.MerchantOrderCommandApi;
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
    public MerchantOrderController(MerchantOrderCommandApi orders){this.orders=orders;}
    @ModelAttribute void cache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
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
    private static int round(JsonNode n){if(!n.path("expectedConfirmRound").isIntegralNumber()||!n.path("expectedConfirmRound").canConvertToInt()||n.path("expectedConfirmRound").intValue()!=0)throw invalid();return 0;}
    private static String text(JsonNode n,String field){if(!n.path(field).isTextual())throw invalid();return n.path(field).asText();}
}
