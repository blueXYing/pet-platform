package com.petplatform.order.biz.application;
/** Admission-only local/read-only check. Missing moderation must not silently approve text. */
@FunctionalInterface
public interface OrderCreationRemarkPolicy { void validate(String remark); }
