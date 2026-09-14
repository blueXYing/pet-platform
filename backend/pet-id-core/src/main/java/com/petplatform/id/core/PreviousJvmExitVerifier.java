package com.petplatform.id.core;

import java.util.UUID;

/**
 * Trusted host integration, NOT a client-provided boolean, lease-expiry check or cancellation flag.
 * Verify termination of exactly this prior incarnation (host/process identity including start time),
 * or the audited virgin initialization. Missing production integration fails closed.
 */
@FunctionalInterface
public interface PreviousJvmExitVerifier {
    void verify(PreviousJvm previous);

    record PreviousJvm(int nodeId, UUID incarnation, long fence,
                       String initializationRef, long reservedThrough) {}

    static PreviousJvmExitVerifier rejecting() {
        return previous -> { throw new IllegalStateException("Trusted previous JVM exit verification is not installed"); };
    }
}
