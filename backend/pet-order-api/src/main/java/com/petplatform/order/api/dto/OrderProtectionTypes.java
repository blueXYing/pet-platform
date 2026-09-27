package com.petplatform.order.api.dto;
import java.util.List;
public final class OrderProtectionTypes {
    private OrderProtectionTypes() {}
    public record OrderProtectionFact(String orderId, String reservationId, String userId,
            String merchantId, String storeId, String serviceId, String fulfillmentType,
            String currentStaffId, String orderStage, String verificationStatus,
            String orderVersion, String assignmentId, String assignmentVersion, boolean protectRequired) {}
    public record OrderProtectionSnapshot(String storeId, boolean complete, long totalOrders,
            long totalCurrentAssignments, List<OrderProtectionFact> items) {
        public OrderProtectionSnapshot { items=List.copyOf(items); }
    }
}
