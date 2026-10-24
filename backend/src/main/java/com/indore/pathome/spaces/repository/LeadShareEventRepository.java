package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.LeadShareEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LeadShareEventRepository extends JpaRepository<LeadShareEvent, UUID> {
    List<LeadShareEvent> findAllByShare_IdInOrderByOccurredAtAscIdAsc(Collection<UUID> shareIds);
}
