// The inbox state machine and the CCR-W2-NOTIFICATION-001 §5 whitelist moved to the shared
// layer so the merchant message page consumes the same single implementation. This shim keeps
// the consumer imports (page + runtime + tests) stable.
export * from '../../shared/notifications/messages'
