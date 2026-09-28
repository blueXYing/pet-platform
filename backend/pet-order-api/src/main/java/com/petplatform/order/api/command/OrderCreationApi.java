package com.petplatform.order.api.command;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderCommand;
import com.petplatform.order.api.dto.OrderCreationTypes.CreateOrderResult;
/** Internal atomic creation only; no payment or externally enabled checkout route. */
public interface OrderCreationApi { CreateOrderResult create(CreateOrderCommand command); }
