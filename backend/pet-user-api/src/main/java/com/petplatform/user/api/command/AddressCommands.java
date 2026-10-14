package com.petplatform.user.api.command;

import com.petplatform.common.CommandContext;

/** Address write commands per contract 59 (ruling-32 second batch, HTTP10 §3.2.2). */
public final class AddressCommands {
    private AddressCommands() {}

    public record CreateAddress(
            CommandContext context,
            String contactName, String contactPhone, String region, String detail,
            boolean defaultAddress
    ) {}

    /** isDefault null keeps the current flag (contract 59: PUT is a full field replace, flag optional). */
    public record UpdateAddress(
            CommandContext context,
            String addressId,
            String contactName, String contactPhone, String region, String detail,
            Boolean defaultAddress
    ) {}

    public record DeleteAddress(CommandContext context, String addressId) {}

    public record SetDefaultAddress(CommandContext context, String addressId) {}

    public record AddressReceipt(String addressId, String status) {}
}
