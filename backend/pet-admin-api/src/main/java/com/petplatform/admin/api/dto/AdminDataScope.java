package com.petplatform.admin.api.dto;

import java.util.List;
import java.util.Objects;

/** Lexical validation does not attest that a city or merchant exists. */
public record AdminDataScope(String mode, List<String> cityCodes, List<String> merchantIds) {
  public AdminDataScope {
    Objects.requireNonNull(mode);
    cityCodes = List.copyOf(cityCodes);
    merchantIds = List.copyOf(merchantIds);
    if (cityCodes.stream().anyMatch(s -> s == null || s.isBlank())
        || cityCodes.stream().distinct().count() != cityCodes.size()
        || merchantIds.stream().distinct().count() != merchantIds.size())
      throw new IllegalArgumentException("Invalid scope");
    for (String id : merchantIds) {
      try {
        if (!id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) < 1)
          throw new IllegalArgumentException("Invalid scope");
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid scope");
      }
    }
    switch (mode) {
      case "ALL", "NONE" -> {
        if (!cityCodes.isEmpty() || !merchantIds.isEmpty())
          throw new IllegalArgumentException("Invalid scope");
      }
      case "CITY" -> {
        if (cityCodes.isEmpty() || cityCodes.size() > 100 || !merchantIds.isEmpty())
          throw new IllegalArgumentException("Invalid scope");
      }
      case "MERCHANT" -> {
        if (merchantIds.isEmpty() || merchantIds.size() > 1000 || !cityCodes.isEmpty())
          throw new IllegalArgumentException("Invalid scope");
      }
      default -> throw new IllegalArgumentException("Invalid scope");
    }
    cityCodes = cityCodes.stream().sorted().toList();
    merchantIds =
        merchantIds.stream()
            .sorted((a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)))
            .toList();
  }
}
