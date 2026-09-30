package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.*;
import com.petplatform.aftersale.api.command.AfterSaleVerificationApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.order.biz.apiimpl.OrderVerificationCommitApiImpl;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seeded ORDER component tests with a bound public AFS history port, not real verification/AFS E2E. */
class OrderVerificationHistoryMySqlTest {
    private static final OffsetDateTime VERIFIED=OffsetDateTime.parse("2026-09-01T00:00:00.000Z");

    @Test void laterRefundProjectionVersionsKeepTheOriginalVerificationProof() throws Exception {
        try(Database d=new Database()){
            for(var original:List.of(new AfterSaleVerificationApi.Evidence(null,"NONE",false),
                    new AfterSaleVerificationApi.Evidence("90","INVALIDATED",true))){
                d.reset();
                d.history=HistoryProof.original(original);
                d.sql.update("UPDATE order_verification_commit SET aftersale_id=?,aftersale_status=? WHERE order_id=1",original.aftersaleId(),original.status());
                d.sql.update("UPDATE pet_order SET current_aftersale_id=?,aftersale_status=? WHERE id=1",original.aftersaleId(),original.status());
                d.require();
                d.sql.update("UPDATE pet_order SET version=14 WHERE id=1");
                d.require();
                // A later case's current projection is separate from the original immutable AFS history.
                // This seed models that migration only; real case creation belongs to the boot acceptance tests.
                d.sql.update("UPDATE pet_order SET version=15,current_aftersale_id=99,aftersale_status='PROCESSING' WHERE id=1");
                d.require();
                assertEquals(3,d.historyReads);
                assertEquals(original,d.history.evidence());
                assertEquals(9L,d.sql.queryForObject("SELECT order_version FROM order_verification_commit WHERE order_id=1",Long.class));
                assertEquals(original.aftersaleId(),d.sql.queryForObject("SELECT aftersale_id FROM order_verification_commit WHERE order_id=1",String.class));
                assertEquals(original.status(),d.sql.queryForObject("SELECT aftersale_status FROM order_verification_commit WHERE order_id=1",String.class));
                assertEquals(99L,d.sql.queryForObject("SELECT current_aftersale_id FROM pet_order WHERE id=1",Long.class));
                assertEquals("COMPLETED",d.sql.queryForObject("SELECT order_stage FROM pet_order WHERE id=1",String.class));
            }
        }
    }

    @Test void laterVersionDoesNotMaskWrongIdentityTimeStateOrAftersaleProof() throws Exception {
        try(Database d=new Database()){
            for(String mutation:List.of("version=8","version=14,store_id=8","version=14,verified_at='2026-09-02 00:00:00'",
                    "version=14,completed_at='2026-09-02 00:00:00'","version=14,verification_status='UNVERIFIED'",
                    "version=14,order_stage='PENDING_SERVICE'")){
                d.reset();d.require();d.sql.update("UPDATE pet_order SET "+mutation+" WHERE id=1");
                assertThrows(ApiException.class,d::require,mutation);
            }
            for(String mutation:List.of("verification_id=71","store_id=8","aftersale_id=90","aftersale_status='INVALIDATED'")){
                d.reset();d.require();d.sql.update("UPDATE order_verification_commit SET "+mutation+" WHERE order_id=1");
                assertThrows(ApiException.class,d::require,mutation);
            }
        }
    }

    @Test void missingOrMisboundAftersaleHistoryCannotAuthorizeTheOrderSnapshot() throws Exception {
        try(Database d=new Database()){
            var none=new AfterSaleVerificationApi.Evidence(null,"NONE",false);
            for(var damaged:List.of(new HistoryProof("2","7","70",VERIFIED,none),
                    new HistoryProof("1","8","70",VERIFIED,none),
                    new HistoryProof("1","7","71",VERIFIED,none),
                    new HistoryProof("1","7","70",VERIFIED.plusNanos(1_000_000),none),
                    HistoryProof.original(new AfterSaleVerificationApi.Evidence("90","INVALIDATED",true)),
                    HistoryProof.original(null))){
                d.reset();d.require();d.history=damaged;
                assertThrows(ApiException.class,d::require,damaged.toString());
                assertEquals(2,d.historyReads);
            }
            d.reset();d.require();d.history=null;
            assertThrows(ApiException.class,d::require,"missing AFS history");
            assertEquals(2,d.historyReads);
        }
    }

    @Test void proofCannotBeReadOutsideTheRequiredWritableTransaction() throws Exception {
        try(Database d=new Database()){
            d.require();
            assertThrows(ApiException.class,()->d.api.requireCommitted("1","7","70",VERIFIED,d.source));
            d.tx.setReadOnly(true);assertThrows(ApiException.class,d::require);
        }
    }

    private record HistoryProof(String order,String store,String verification,OffsetDateTime at,
            AfterSaleVerificationApi.Evidence evidence) {
        static HistoryProof original(AfterSaleVerificationApi.Evidence evidence){return new HistoryProof("1","7","70",VERIFIED,evidence);}
    }

    private static final class Database implements AutoCloseable {
        final String name="order_history_"+UUID.randomUUID().toString().replace("-","");
        final JdbcTemplate admin,sql;final DataSource source;final TransactionTemplate tx;final OrderVerificationCommitApiImpl api;
        HistoryProof history;int historyReads;
        Database(){
            var environment=System.getenv();
            String prefix=environment.containsKey("BOOKING_MYSQL_URL")?"BOOKING":"AUTH";
            String base=environment.get(prefix+"_MYSQL_URL"),user=environment.get(prefix+"_MYSQL_USER"),password=environment.get(prefix+"_MYSQL_PASSWORD");
            if(base==null||user==null||password==null)throw new IllegalStateException("Set BOOKING_MYSQL_URL/USER/PASSWORD or AUTH_MYSQL_URL/USER/PASSWORD for the isolated MySQL test");
            if(!base.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"))throw new IllegalArgumentException("Isolated local MySQL server root required");
            admin=new JdbcTemplate(new DriverManagerDataSource(base,user,password));admin.execute("CREATE DATABASE "+name);
            source=new DriverManagerDataSource(base+name+"?serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",user,password);sql=new JdbcTemplate(source);
            sql.execute("CREATE TABLE pet_order(id BIGINT PRIMARY KEY,store_id BIGINT,merchant_id BIGINT,current_aftersale_id BIGINT,version BIGINT,order_stage VARCHAR(32),verification_status VARCHAR(32),aftersale_status VARCHAR(32),verified_at DATETIME(3),completed_at DATETIME(3))");
            sql.execute("CREATE TABLE order_verification_commit(order_id BIGINT PRIMARY KEY,store_id BIGINT,verification_id BIGINT,credential_id BIGINT,attempt_id BIGINT,command_id BIGINT,operator_id BIGINT,request_id VARCHAR(64),order_version BIGINT,event_id BIGINT,verified_at DATETIME(3),aftersale_id BIGINT,aftersale_status VARCHAR(32))");
            reset();
            tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            ScheduleCapacityGuardApi guard=new ScheduleCapacityGuardApi(){
                public void acquire(List<String> stores,com.petplatform.common.QueryContext q){}
                public void requireHeld(String store,DataSource same){if(!"7".equals(store)||source!=same||!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException();}
            };
            AfterSaleVerificationApi historyPort=new AfterSaleVerificationApi(){
                public Evidence invalidateCurrent(String token,String order,String store,String verification,OffsetDateTime at,DataSource same){
                    throw new AssertionError("The read-only component fixture cannot create an AFS proof");
                }
                public Evidence requireCommitted(String order,String store,String verification,OffsetDateTime at,DataSource same){
                    historyReads++;
                    if(history==null||!history.order().equals(order)||!history.store().equals(store)
                            ||!history.verification().equals(verification)||!history.at().isEqual(at)||source!=same
                            ||!TransactionSynchronizationManager.isActualTransactionActive()
                            ||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                            ||!TransactionSynchronizationManager.hasResource(source)
                            ||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
                        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,"Seeded AFS historical proof unavailable");
                    return history.evidence();
                }
            };
            api=new OrderVerificationCommitApiImpl(source,guard,null,()->100L,event->{},()->null,()->historyPort);
        }
        void reset(){
            sql.update("DELETE FROM pet_order");sql.update("INSERT INTO pet_order VALUES(1,7,8,NULL,9,'COMPLETED','VERIFIED','NONE','2026-09-01 00:00:00','2026-09-01 00:00:00')");
            sql.update("DELETE FROM order_verification_commit");sql.update("INSERT INTO order_verification_commit VALUES(1,7,70,71,72,73,74,'fixture',9,75,'2026-09-01 00:00:00',NULL,'NONE')");
            history=HistoryProof.original(new AfterSaleVerificationApi.Evidence(null,"NONE",false));historyReads=0;
        }
        void require(){tx.executeWithoutResult(s->api.requireCommitted("1","7","70",VERIFIED,source));}
        public void close(){admin.execute("DROP DATABASE "+name);}
    }
}
