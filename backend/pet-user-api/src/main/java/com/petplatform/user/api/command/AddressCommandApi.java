package com.petplatform.user.api.command;

import com.petplatform.user.api.dto.AddressView;

/**
 * Address book commands per contract 59: first-success receipts are bound to the requestId
 * (supplement 23 five-tuple, scope USER_SELF); replay returns the original receipt.
 */
public interface AddressCommandApi {

    AddressView createAddress(AddressCommands.CreateAddress command);

    AddressView updateAddress(AddressCommands.UpdateAddress command);

    AddressCommands.AddressReceipt deleteAddress(AddressCommands.DeleteAddress command);

    AddressView setDefaultAddress(AddressCommands.SetDefaultAddress command);
}
