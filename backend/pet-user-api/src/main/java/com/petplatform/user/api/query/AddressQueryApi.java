package com.petplatform.user.api.query;

import com.petplatform.user.api.dto.AddressView;
import java.util.List;

/**
 * Owner-scoped reads per contract 59: the session user's ACTIVE addresses only, default first
 * then created_at ASC, id ASC. Not-mine/not-found/deleted is indistinguishable (404 semantics).
 */
public interface AddressQueryApi {

    List<AddressView> listActiveAddresses(String ownerUserId);

    AddressView getAddress(String addressId, String ownerUserId);
}
