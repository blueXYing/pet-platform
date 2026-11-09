package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;
import static com.petplatform.boot.booking.AfterSaleHttpFixture.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * CCR-M-AFTERSALE-VIEW-001 (approved 2026-11-06) over real loopback HTTP, current C/OWNER/ADMIN
 * sessions and isolated MySQL; no controller/service mocks. The merchant face projects the
 * buyer nickname (user domain basics) and the order-time service/pet snapshot (ORDER port) as
 * five flat nullable keys; the C/admin faces keep their exact pre-CCR key sets; no phone ever
 * appears; non-owner, foreign-store and absent reads keep the same 403/404 anti-enumeration
 * answers with no label; absent facts degrade to explicit nulls; evidence/opinion/version
 * semantics stay unchanged. Expected values are read from the authoritative rows.
 */
class AfterSaleMerchantViewHttpAcceptanceTest {
    private static final List<String> LABEL_KEYS=List.of("customerNickname","serviceName","petName","petType","petBreed");

    @Test void merchantFaceProjectsLabelsWhileConsumerAndAdminFacesStayUnchanged()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            String order=f.verifiedOrder();
            var receipt=f.send("POST",AfterSaleHttpAcceptanceTest.createPath(order),createBody(),f.buyerToken);assertEquals(201,receipt.status());
            String id=receipt.value("afterSaleId");
            var facts=Facts.of(f,order);
            var detail=f.send("GET","/merchant/aftersales/"+id,null,f.ownerToken);assertEquals(200,detail.status());
            facts.assertLabels(detail.data());
            var list=f.send("GET","/merchant/aftersales?merchantId="+MERCHANT+"&storeId="+STORE,null,f.ownerToken);assertEquals(200,list.status());
            var item=onlyItem(list);assertEquals(id,item.get("afterSaleId"));facts.assertLabels(item);
            facts.assertNoPhone(f,detail);facts.assertNoPhone(f,list);
            // 51 minimal disclosure: the C and admin faces keep their exact pre-CCR key sets.
            for(String party:List.of("c","admin")){
                String token=AfterSaleHttpAcceptanceTest.token(f,party);
                var other=f.send("GET","/"+party+"/aftersales/"+id,null,token);assertEquals(200,other.status());
                for(String key:LABEL_KEYS)assertFalse(other.data().containsKey(key),party+" detail "+key);
                String filter=party.equals("c")?"":"?merchantId="+MERCHANT+"&storeId="+STORE;
                var page=f.send("GET","/"+party+"/aftersales"+filter,null,token);assertEquals(200,page.status());
                for(String key:LABEL_KEYS)assertFalse(onlyItem(page).containsKey(key),party+" list "+key);
                facts.assertNoPhone(f,other);facts.assertNoPhone(f,page);
            }
            // The read projection changes no evidence/opinion/version semantics.
            var opinion=Map.of("expectedVersion",detail.value("version"),"opinionCode","PARTLY_AGREE",
                    "explanation","Merchant explains the observed service facts in detail","evidenceAssetIds",List.of());
            var answered=f.send("POST","/merchant/aftersales/"+id+"/opinion",opinion,f.ownerToken);assertEquals(200,answered.status(),answered.toString());
            assertEquals("PENDING",answered.value("status"));assertEquals("1",answered.value("version"));
            var after=f.send("GET","/merchant/aftersales/"+id,null,f.ownerToken);assertEquals(200,after.status());
            facts.assertLabels(after.data());assertEquals("1",after.value("version"));
            assertEquals(2,((List<?>)after.data().get("evidence")).size());
        }
    }

    @Test void nonOwnerForeignStoreAndAbsentReadsKeepTheAntiEnumerationAnswerWithoutAnyLabel()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            String order=f.verifiedOrder();
            var receipt=f.send("POST",AfterSaleHttpAcceptanceTest.createPath(order),createBody(),f.buyerToken);assertEquals(201,receipt.status());
            String id=receipt.value("afterSaleId");
            var facts=Facts.of(f,order);
            // Another user and the case's own buyer are not the store OWNER: the same 403, no label.
            for(String token:List.of(f.otherToken,f.buyerToken)){
                var denied=f.send("GET","/merchant/aftersales/"+id,null,token);assertEquals(403,denied.status());facts.assertNoLabel(f,denied);
                var page=f.send("GET","/merchant/aftersales?merchantId="+MERCHANT+"&storeId="+STORE,null,token);assertEquals(403,page.status());facts.assertNoLabel(f,page);
            }
            // The real OWNER asking for unknown coordinates reads the same 403; an absent case stays 404.
            var foreign=f.send("GET","/merchant/aftersales?merchantId=710399&storeId=710399",null,f.ownerToken);
            assertEquals(403,foreign.status());facts.assertNoLabel(f,foreign);
            var absent=f.send("GET","/merchant/aftersales/"+(Long.parseLong(id)+987_654_321L),null,f.ownerToken);
            assertEquals(404,absent.status());assertEquals("AFTERSALE_NOT_FOUND",absent.code());facts.assertNoLabel(f,absent);
        }
    }

    @Test void blankNicknameAndMissingSnapshotsDegradeToExplicitNullsNeverPlaceholders()throws Exception{
        try(var f=new AfterSaleHttpFixture()){
            String order=f.verifiedOrder();
            var receipt=f.send("POST",AfterSaleHttpAcceptanceTest.createPath(order),createBody(),f.buyerToken);assertEquals(201,receipt.status());
            String id=receipt.value("afterSaleId");
            String service=f.text("SELECT service_name FROM order_service_snapshot WHERE order_id="+order);assertNotNull(service);
            // A blank nickname normalizes to null; an order without its pet snapshot reads null pet fields.
            f.sql("UPDATE user_account SET nickname='   ' WHERE id="+BUYER);
            f.sql("DELETE FROM order_pet_snapshot WHERE order_id="+order);
            var detail=f.send("GET","/merchant/aftersales/"+id,null,f.ownerToken);assertEquals(200,detail.status());
            assertTrue(detail.data().containsKey("customerNickname"));assertNull(detail.data().get("customerNickname"));
            assertEquals(service,detail.data().get("serviceName"));
            for(String key:List.of("petName","petType","petBreed")){assertTrue(detail.data().containsKey(key),key);assertNull(detail.data().get(key),key);}
            // No nickname and no snapshot at all: every label key is present and null on the list.
            f.sql("UPDATE user_account SET nickname=NULL WHERE id="+BUYER);
            f.sql("DELETE FROM order_service_snapshot WHERE order_id="+order);
            var list=f.send("GET","/merchant/aftersales?merchantId="+MERCHANT+"&storeId="+STORE,null,f.ownerToken);assertEquals(200,list.status());
            var item=onlyItem(list);
            for(String key:LABEL_KEYS){assertTrue(item.containsKey(key),key);assertNull(item.get(key),key);}
            assertEquals(id,item.get("afterSaleId"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> onlyItem(AfterSaleHttpFixture.Reply page){
        var items=(List<Map<String,Object>>)page.data().get("items");assertEquals(1,items.size());return items.get(0);
    }

    /** Authoritative row facts for one order: buyer nickname/phone and the creation snapshots. */
    private record Facts(String nickname,String phone,String service,String petName,String petType,String petBreed) {
        static Facts of(AfterSaleHttpFixture f,String order){
            var facts=new Facts(f.text("SELECT nickname FROM user_account WHERE id="+BUYER),f.text("SELECT phone FROM user_account WHERE id="+BUYER),
                    f.text("SELECT service_name FROM order_service_snapshot WHERE order_id="+order),f.text("SELECT pet_name FROM order_pet_snapshot WHERE order_id="+order),
                    f.text("SELECT pet_type FROM order_pet_snapshot WHERE order_id="+order),f.text("SELECT breed_name FROM order_pet_snapshot WHERE order_id="+order));
            for(String value:Arrays.asList(facts.nickname,facts.phone,facts.service,facts.petName,facts.petType,facts.petBreed))
                assertNotNull(value,"the real booking fixture must seed every projected fact");
            return facts;
        }
        void assertLabels(Map<String,Object> data){
            assertEquals(nickname,data.get("customerNickname"));assertEquals(service,data.get("serviceName"));
            assertEquals(petName,data.get("petName"));assertEquals(petType,data.get("petType"));assertEquals(petBreed,data.get("petBreed"));
            assertFalse(data.containsKey("labels"));
            for(String key:data.keySet()){String k=key.toLowerCase(Locale.ROOT);assertFalse(k.contains("phone")||k.contains("mobile")||k.contains("avatar")||k.equals("userid"),key);}
        }
        void assertNoPhone(AfterSaleHttpFixture f,AfterSaleHttpFixture.Reply reply)throws Exception{
            String wire=f.json.writeValueAsString(reply.envelope());
            assertFalse(wire.contains(phone),"raw phone leaked");
            assertFalse(wire.contains(phone.substring(0,3)+"****"+phone.substring(phone.length()-4)),"masked phone leaked");
            assertFalse(wire.toLowerCase(Locale.ROOT).contains("phone"),wire);
        }
        void assertNoLabel(AfterSaleHttpFixture f,AfterSaleHttpFixture.Reply reply)throws Exception{
            assertNull(reply.envelope().get("data"));String wire=f.json.writeValueAsString(reply.envelope());
            for(String key:LABEL_KEYS)assertFalse(wire.contains(key),key);
            for(String fact:List.of(nickname,service,petName,petBreed))assertFalse(wire.contains(fact),fact);
        }
    }
}
