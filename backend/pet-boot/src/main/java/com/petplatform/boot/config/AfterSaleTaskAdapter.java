package com.petplatform.boot.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.petplatform.aftersale.api.command.AfterSaleSupplementTimeoutApi;
import com.petplatform.aftersale.biz.application.AfterSalePorts;
import com.petplatform.common.*;
import com.petplatform.task.core.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Durable task ownership and real worker-dispatch provenance; a caller-created SYSTEM DTO is insufficient. */
public final class AfterSaleTaskAdapter implements AfterSalePorts.Tasks {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DataSource source;
    private final JdbcAsyncTaskSubmitter submitter;
    private final JdbcAsyncTaskRecoverer recoverer;
    private final TaskSubmissionInspector inspector;
    public AfterSaleTaskAdapter(DataSource source,SnowflakeIdGenerator ids){
        this.source=Objects.requireNonNull(source);submitter=new JdbcAsyncTaskSubmitter(source,ids);
        recoverer=new JdbcAsyncTaskRecoverer(source,ids);inspector=new TaskSubmissionInspector(source);
    }
    @Override public void enqueue(AfterSalePorts.TaskSpec t){submitter.enqueueAt(t.taskKey(),"AFTERSALE",t.taskType(),t.bizType(),t.bizId(),
            t.expectedVersion(),t.payloadJson(),t.maxRetryCount(),t.retryPolicy(),t.availableAt());}
    @Override public void recover(AfterSalePorts.TaskSpec t){recoverer.recover(t.taskKey(),"AFTERSALE",t.taskType(),t.bizType(),t.bizId(),
            t.expectedVersion(),t.payloadJson(),t.maxRetryCount(),t.retryPolicy(),t.availableAt());}
    @Override public void requireTrusted(CommandContext context,AfterSalePorts.TaskSpec t){
        try {
            var lease=TaskInvocation.requireCurrent();var row=inspector.find(t.taskKey());
            if(context==null||context.operatorType()!=OperatorType.SYSTEM||context.operatorId()!=null
                    ||!"ASYNC_TASK".equals(context.source())||!("TASK:"+t.taskKey()).equals(context.requestId())
                    ||!("TASK:"+lease.taskId()+":"+lease.attemptNo()).equals(context.traceId())
                    ||row==null||!"RUNNING".equals(row.status())||!row.taskId().equals(Long.toString(lease.taskId()))
                    ||!row.taskKey().equals(t.taskKey())||!lease.taskKey().equals(t.taskKey())||!"AFTERSALE".equals(row.ownerModule())
                    ||!row.taskType().equals(t.taskType())||!lease.taskType().equals(t.taskType())||!row.bizType().equals(t.bizType())
                    ||!row.bizId().equals(Long.toString(t.bizId()))||lease.bizId()!=t.bizId()
                    ||!Objects.equals(row.expectedVersion(),t.expectedVersion())||!Objects.equals(lease.expectedVersion(),t.expectedVersion())
                    ||row.maxRetryCount()!=t.maxRetryCount()||lease.maxRetryCount()!=t.maxRetryCount()
                    ||!row.retryPolicy().equals(t.retryPolicy())||!lease.retryPolicy().equals(t.retryPolicy())
                    ||!Objects.equals(row.submittedExecuteAt(),t.availableAt())
                    ||!JSON.readTree(t.payloadJson()).equals(JSON.readTree(row.payloadJson()))
                    ||!JSON.readTree(t.payloadJson()).equals(JSON.readTree(lease.payloadJson())))throw new IllegalArgumentException();
        } catch(Exception failure){throw new ApiException(CommonApiCodes.FORBIDDEN,"Trusted aftersale task delivery required");}
    }
    public record Payload(String afterSaleId,String supplementRequestId,String storeId,String expectedDeadline) {}
    public TaskRegistration<Payload> registration(AfterSaleSupplementTimeoutApi api){
        var clock=new TaskDatabaseClock(source);
        return new TaskRegistration<>(new TaskHandler<>() {
            public String taskType(){return "AFTERSALE_SUPPLEMENT_TIMEOUT";}
            public TaskExecutionResult execute(TaskExecutionContext delivery,Payload p){
                var c=new CommandContext(delivery.requestId(),delivery.traceId(),OperatorType.SYSTEM,null,"ASYNC_TASK");
                var result=api.handle(new AfterSaleSupplementTimeoutApi.Timeout(c,p.afterSaleId(),p.supplementRequestId(),p.storeId(),p.expectedDeadline()));
                if(result.completed())return new TaskExecutionResult.Success("AFTERSALE_SUPPLEMENT_FINISHED");
                var remaining=Duration.between(clock.now().atOffset(ZoneOffset.UTC),OffsetDateTime.parse(result.retryAt()));
                return new TaskExecutionResult.Retry("AFTERSALE_SUPPLEMENT_NOT_DUE",remaining.isNegative()?Duration.ofMillis(250):remaining.plusMillis(250));
            }
        },lease->{
            try {
                var n=JSON.readTree(lease.payloadJson());if(n==null||!n.isObject())throw new IllegalArgumentException();
                var fields=new HashSet<String>();n.fieldNames().forEachRemaining(fields::add);
                if(!fields.equals(Set.of("afterSaleId","supplementRequestId","storeId","expectedDeadline")))throw new IllegalArgumentException();
                for(String f:fields)if(!n.path(f).isTextual())throw new IllegalArgumentException();
                var codec=new DecimalPublicIdCodec();var p=new Payload(n.path("afterSaleId").textValue(),n.path("supplementRequestId").textValue(),n.path("storeId").textValue(),n.path("expectedDeadline").textValue());
                codec.fromApi(p.afterSaleId());codec.fromApi(p.storeId());long round=codec.fromApi(p.supplementRequestId());
                var deadline=PublicContractChecks.requireMillisecondPrecision(OffsetDateTime.parse(p.expectedDeadline()));
                if(!deadline.getOffset().equals(ZoneOffset.UTC)||lease.bizId()!=round||!Objects.equals(lease.expectedVersion(),0L)
                        ||!lease.taskKey().equals("AFTERSALE_SUPPLEMENT_TIMEOUT:"+round+":0")||!lease.taskType().equals("AFTERSALE_SUPPLEMENT_TIMEOUT")
                        ||lease.maxRetryCount()!=8||!"AFTERSALE_SUPPLEMENT".equals(lease.retryPolicy()))throw new IllegalArgumentException();
                return p;
            }catch(Exception e){throw new IllegalArgumentException("Invalid aftersale supplement task");}
        },lease->"TASK:"+lease.taskKey());
    }
}
