package com.indore.pathome.spaces.dto;

import java.util.List;

public record TenantVisitExecutionPage(List<VisitExecutionView> sessions, int page, int size, int totalPages) {}
