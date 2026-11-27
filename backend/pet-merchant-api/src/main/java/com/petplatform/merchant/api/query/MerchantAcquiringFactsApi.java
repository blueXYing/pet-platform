package com.petplatform.merchant.api.query;

import com.petplatform.merchant.api.dto.MerchantAcquiringFactDTO;

/**
 * Internal merchant facts a payment provider needs before onboarding a merchant for acquiring
 * (CCR-PAY-ONBOARD-001, contract 81 §8). Read-only, system-internal, no HTTP face of its own.
 */
public interface MerchantAcquiringFactsApi {

  /** The merchant's onboarding facts, or {@code null} when no merchant has that id. */
  MerchantAcquiringFactDTO find(String merchantId);
}
