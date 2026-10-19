package com.petplatform.order.api.query;

import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Derived customer read surface for the merchant store (contract 69, PRD 5.12 minimal ruling
 * 2026-10-19): every customer is a user_id grouping of the store's own pet_order rows — zero
 * new fact sources, zero cross-store rows. The nickname/masked-phone projection comes from the
 * user domain's existing masked basic read; the pet projection is the store's own
 * order_pet_snapshot reduced to name + type/breed level (no health fields, read-only, no
 * export/marketing surface). The OWNER merchant coordinate is re-proven per call inside the
 * store guard transaction by the merchant authority port, mirroring the §4.1 list.
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

    /** Derived detail: the summary plus the store's deduped pet list for this customer. */
    record CustomerDetail(CustomerRow customer, List<PetView> pets) {}

    /** nickname is an optional contains filter (1..64 code points); page 1.., pageSize 1..100. */
    record CustomerListQuery(String merchantId, String storeId, String nickname,
            int page, int pageSize, QueryContext context) {}

    /** userId is the canonical decimal string of the customer; coordinates are the store's. */
    record CustomerGetQuery(String merchantId, String storeId, String userId, QueryContext context) {}
}
