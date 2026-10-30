package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadShortlistItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LeadShortlistItemRepository extends JpaRepository<LeadShortlistItem, Long> {
    List<LeadShortlistItem> findAllByShortlist_IdOrderByPositionAsc(Long shortlistId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from LeadShortlistItem item where item.shortlist.id = :shortlistId "
            + "order by item.position asc, item.id asc")
    List<LeadShortlistItem> findAllLockedByShortlist_IdOrderByPositionAsc(
            @Param("shortlistId") Long shortlistId);
    List<LeadShortlistItem> findAllByShortlist_IdInOrderByShortlist_IdAscPositionAsc(
            List<Long> shortlistIds);
    void deleteAllByShortlist_Id(Long shortlistId);
}
