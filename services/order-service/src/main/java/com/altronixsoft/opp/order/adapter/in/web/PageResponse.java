package com.altronixsoft.opp.order.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One page of results")
public record PageResponse<T>(
        List<T> content,

        @Schema(description = "Zero-based page number", example = "0")
        int page,

        @Schema(description = "Requested page size", example = "20")
        int size,

        @Schema(description = "Number of elements over all pages", example = "1")
        long totalElements,

        @Schema(description = "Number of pages", example = "1")
        int totalPages) {}
