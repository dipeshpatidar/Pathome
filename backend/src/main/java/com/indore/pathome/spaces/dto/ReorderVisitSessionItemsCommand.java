package com.indore.pathome.spaces.dto;

import java.util.List;

public record ReorderVisitSessionItemsCommand(
        Long expectedSessionVersion,
        List<Long> itemIds) {}
