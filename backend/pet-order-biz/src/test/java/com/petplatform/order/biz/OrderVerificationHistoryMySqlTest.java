package com.petplatform.order.biz;

import static org.junit.jupiter.api.Assertions.*;
import com.petplatform.common.ApiException;
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

/** Historical proof read tests. Rows are explicit fixture seeds, not claims of real verification E2E. */
class OrderVerificationHistoryMySqlTest {
    private static final OffsetDateTime VERIFIED=OffsetDateTime.parse("2026-09-01T00:00:00.000Z");

    @Test void laterRefundProjectionVersionsKeepTheOriginalVerificationProof() throws Exception {
        try(Database d=new Database()){
            d.require();
            d.sql.update("UPDATE pet_order SET version=14 WHERE id=1");
            d.require();
            assertEquals(9L,d.sql.queryForObject("SELECT order_version FROM order_verification_commit WHERE order_id=1",Long.class));
            assertEquals("COMPLETED",d.sql.queryForObject("SELECT order_stage FROM pet_order WHERE id=1",String.class));
        }
    }

    @Test void laterVersionDoesNotMaskWrongIdentityTimeStateOrAftersaleProof() throws Exception {
        try(Database d=new Database()){
            for(String mutation:List.of("version=8","version=14,store_id=8","version=14,verified_at='2026-09-02 00:00:00'",
                    "version=14,completed_at='2026-09-02 00:00:00'","version=14,verification_status='UNVERIFIED'",
                    "version=14,order_stage='PENDING_SERVICE'","version=14,current_aftersale_id=99",
                    "version=14,aftersale_status='PROCESSING'")){
                d.reset();d.sql.update("UPDATE pet_order SET "+mutation+" WHERE id=1");
                assertThrows(ApiException.class,d::require,mutation);
            }
            d.reset();d.sql.update("UPDATE order_verification_commit SET verification_id=71 WHERE order_id=1");
            assertThrows(ApiException.class,d::require);
        }
    }

    @Test void proofCannotBeReadOutsideTheRequiredWritableTransaction() throws Exception {
        try(Database d=new Database()){
            assertThrows(ApiException.class,()->d.api.requireCommitted("1","7","70",VERIFIED,d.source));
            d.tx.setReadOnly(true);assertThrows(ApiException.class,d::require);
        }
    }

    private static final class Database implements AutoCloseable {
        final String name="order_history_"+UUID.randomUUID().toString().replace("-","");
        final JdbcTemplate admin,sql;final DataSource source;final TransactionTemplate tx;final OrderVerificationCommitApiImpl api;
        Database(){
            String base=System.getenv().getOrDefault("BOOKING_MYSQL_URL","jdbc:mysql://127.0.0.1:33459/");
            if(!base.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"))throw new IllegalArgumentException("Isolated local MySQL server root required");
            String user=System.getenv().getOrDefault("BOOKING_MYSQL_USER","root"),password=System.getenv().getOrDefault("BOOKING_MYSQL_PASSWORD","");
            admin=new JdbcTemplate(new DriverManagerDataSource(base,user,password));admin.execute("CREATE DATABASE "+name);
            source=new DriverManagerDataSource(base+name+"?serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",user,password);sql=new JdbcTemplate(source);
            sql.execute("CREATE TABLE pet_order(id BIGINT PRIMARY KEY,store_id BIGINT,merchant_id BIGINT,current_aftersale_id BIGINT,version BIGINT,order_stage VARCHAR(32),verification_status VARCHAR(32),aftersale_status VARCHAR(32),verified_at DATETIME(3),completed_at DATETIME(3))");
            sql.execute("CREATE TABLE order_verification_commit(order_id BIGINT PRIMARY KEY,store_id BIGINT,verification_id BIGINT,credential_id BIGINT,attempt_id BIGINT,command_id BIGINT,operator_id BIGINT,request_id VARCHAR(64),order_version BIGINT,event_id BIGINT,verified_at DATETIME(3),aftersale_id BIGINT,aftersale_status VARCHAR(32))");
            sql.update("INSERT INTO order_verification_commit VALUES(1,7,70,71,72,73,74,'fixture',9,75,'2026-09-01 00:00:00',NULL,'NONE')");reset();
            tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            ScheduleCapacityGuardApi guard=new ScheduleCapacityGuardApi(){
                public void acquire(List<String> stores,com.petplatform.common.QueryContext q){}
                public void requireHeld(String store,DataSource same){if(!"7".equals(store)||source!=same||!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException();}
            };
            api=new OrderVerificationCommitApiImpl(source,guard,null,()->100L,event->{},()->null,()->null);
        }
        void reset(){sql.update("DELETE FROM pet_order");sql.update("INSERT INTO pet_order VALUES(1,7,8,NULL,9,'COMPLETED','VERIFIED','NONE','2026-09-01 00:00:00','2026-09-01 00:00:00')");}
        void require(){tx.executeWithoutResult(s->api.requireCommitted("1","7","70",VERIFIED,source));}
        public void close(){admin.execute("DROP DATABASE "+name);}
    }
}
