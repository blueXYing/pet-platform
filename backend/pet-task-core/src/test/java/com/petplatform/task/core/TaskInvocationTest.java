package com.petplatform.task.core;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class TaskInvocationTest {
    @Test void onlyPreparedDeliveryBindsTheLeaseAndAlwaysClearsIt() {
        var lease=new TaskLease(1,"task:1","TEST",2,0L,"{}","worker",3,4,1,0,8,"TEST");
        var registration=new TaskRegistration<>(new TaskHandler<String>() {
            public String taskType(){return "TEST";}
            public TaskExecutionResult execute(TaskExecutionContext context,String value){
                assertSame(lease,TaskInvocation.requireCurrent());
                assertEquals("TASK:1:1",context.traceId());
                throw new IllegalArgumentException("test failure");
            }
        },delivery->"decoded",delivery->"TASK:task:1");
        assertThrows(IllegalStateException.class,TaskInvocation::requireCurrent);
        var prepared=registration.prepare(lease,Clock.systemUTC());
        assertThrows(IllegalStateException.class,TaskInvocation::requireCurrent);
        assertThrows(IllegalArgumentException.class,prepared::get);
        assertThrows(IllegalStateException.class,TaskInvocation::requireCurrent);
        assertThrows(IllegalArgumentException.class,prepared::get);
        assertThrows(IllegalStateException.class,TaskInvocation::requireCurrent);
    }
}
