package com.petplatform.order.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Derived customer read surface for the merchant store (contract 69, PRD 5.12; 2026-10-19 CCR
 * expansion): every customer is a user_id grouping of the store's own pet_order rows — zero
 * new fact sources, zero cross-store rows. The nickname/masked-phone projection comes from the
 * user domain's existing masked basic read; the pet projection is the store's own
 * order_pet_snapshot reduced to name + type/breed level (no health fields, no export/marketing
 * surface). The OWNER merchant coordinate is re-proven per call inside the store guard
 * transaction by the merchant authority port, mirroring the §4.1 list. The 2026-10-19
 * expansion adds the order-no-suffix search (hits only when one of THIS store's orders has a
 * matching decimal tail — no credential enters the path) and the single merchant-internal note
 * carried on the detail (write face: {@link MerchantCustomerNoteCommandApi}).
 */
public interface MerchantCustomerQueryApi {

    /** One page of the store's derived customers, totalSpend DESC then userId ASC. */
    PageResult<CustomerRow> listCustomers(CustomerListQuery query);

    /** One derived customer of this store; a user with no store order is the same NOT_FOUND. */
    CustomerDetail getCustomer(CustomerGetQuery query);

    /** Masked wire row: phoneMasked matches 1[0-9]{2}****[0-9]{4}; money is a 2-dp string. */
    record CustomerRow(String userId, String nickname, String phoneMasked, long orderCount,
            BigDecimal totalSpend, BigDecimal avgTicket, OffsetDateTime lastVisitAt, PetView topPet) {}

    /** Name + breed-level pet projection only (privacy ruling: no sex/age/weight/vaccine/notes). */
    record PetView(String petName, String petType, String breedName) {}

    /** The store's single internal note for one derived customer; null until the first save. */
    record NoteView(String content, String version, OffsetDateTime updatedAt) {}

    /** Derived detail: summary + deduped pet list + the store's internal note (may be null). */
    record CustomerDetail(CustomerRow customer, List<PetView> pets, NoteView note) {}

    /**
     * nickname is an optional contains filter (1..64 code points); orderNoSuffix is the optional
     * order-no tail filter (2..10 digits, hits only this store's orders). The two filters are
     * mutually exclusive; page 1.., pageSize 1..100.
     */
    record CustomerListQuery(String merchantId, String storeId, String nickname, String orderNoSuffix,
            int page, int pageSize, QueryContext context) {}

    /** userId is the canonical decimal string of the customer; coordinates are the store's. */
    record CustomerGetQuery(String merchantId, String storeId, String userId, QueryContext context) {}
}
