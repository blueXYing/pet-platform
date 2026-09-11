package com.petplatform.event.api;

public interface IntegrationEventPublisher {
    void publish(IntegrationEvent<?> event);
}
