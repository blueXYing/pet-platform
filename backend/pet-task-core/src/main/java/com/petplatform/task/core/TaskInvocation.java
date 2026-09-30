package com.petplatform.task.core;

import java.util.function.Supplier;

/** Read-only current worker delivery. Only TASK registration dispatch can establish this scope. */
public final class TaskInvocation {
    private static final ThreadLocal<TaskLease> CURRENT=new ThreadLocal<>();
    private TaskInvocation() {}
    public static TaskLease requireCurrent(){
        var lease=CURRENT.get();if(lease==null)throw new IllegalStateException("A real task worker delivery is required");return lease;
    }
    static <T>T dispatch(TaskLease lease,Supplier<T> work){
        if(CURRENT.get()!=null)throw new IllegalStateException("Nested task dispatch is not supported");
        CURRENT.set(lease);try{return work.get();}finally{CURRENT.remove();}
    }
}
