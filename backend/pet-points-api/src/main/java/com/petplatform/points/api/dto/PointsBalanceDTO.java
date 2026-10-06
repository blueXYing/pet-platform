package com.petplatform.points.api.dto;

/** CCR-C006 P1: balance is a non-negative integer string; no account row means "0". */
public record PointsBalanceDTO(String balance) {}
