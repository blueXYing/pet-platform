package com.petplatform.user.api.query;

/** Owner is mandatory: snapshots are always resolved against a verified owner (CCR-W2-API-001 user domain). */
public record PetSnapshotQuery(String petId, String ownerUserId) {}
