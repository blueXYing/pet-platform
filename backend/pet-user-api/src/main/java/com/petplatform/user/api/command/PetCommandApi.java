package com.petplatform.user.api.command;

import com.petplatform.user.api.dto.PetView;

/** First-success receipts are bound to requestId per supplement 23 §5: replay returns the original receipt. */
public interface PetCommandApi {

    PetView createPet(PetCommands.CreatePet command);

    PetView updatePet(PetCommands.UpdatePet command);

    PetCommands.PetReceipt deletePet(PetCommands.DeletePet command);
}
