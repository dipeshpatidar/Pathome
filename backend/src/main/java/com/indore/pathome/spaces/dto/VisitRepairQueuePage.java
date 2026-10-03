package com.indore.pathome.spaces.dto;

import java.util.List;

public record VisitRepairQueuePage(List<VisitRepairQueueItem> items, long totalElements, int page, int size, int totalPages) {}
