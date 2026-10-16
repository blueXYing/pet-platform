package com.petplatform.order.api.dto;

import java.time.OffsetDateTime;

/**
 * Persisted order fact of the user's earliest paid order (07号 §7.1 amendment, contract 60 §7
 * / CCR-POINTS-ORDER-001). Carries only the order identity and the paid instant — no other
 * order fields, no enumeration surface; {@code orderId} is the canonical decimal string form
 * of the pet_order id.
 */
public record OrderFirstPaidFact(String orderId, OffsetDateTime paidAt) {}
