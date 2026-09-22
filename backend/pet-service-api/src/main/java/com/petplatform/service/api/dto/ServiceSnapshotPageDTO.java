package com.petplatform.service.api.dto;

import java.util.List;

/** C catalog page of visible store services, created_at DESC then id DESC. */
public record ServiceSnapshotPageDTO(
        List<ServiceSnapshotDTO> items, int page, int pageSize, long total) {}
