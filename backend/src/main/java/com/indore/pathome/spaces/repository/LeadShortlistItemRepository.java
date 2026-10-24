package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadShortlistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeadShortlistItemRepository extends JpaRepository<LeadShortlistItem, Long> {
    List<LeadShortlistItem> findAllByShortlist_IdOrderByPositionAsc(Long shortlistId);
    List<LeadShortlistItem> findAllByShortlist_IdInOrderByShortlist_IdAscPositionAsc(
            List<Long> shortlistIds);
    void deleteAllByShortlist_Id(Long shortlistId);
}
