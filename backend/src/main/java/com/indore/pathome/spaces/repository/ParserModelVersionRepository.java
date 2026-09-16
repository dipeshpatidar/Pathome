package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.ParserModelStatus;
import com.indore.pathome.spaces.entity.ParserModelVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ParserModelVersionRepository extends JpaRepository<ParserModelVersion, Long> {

    Optional<ParserModelVersion> findByModelVersion(String modelVersion);

    Page<ParserModelVersion> findByModelStatusOrderByCreatedAtDesc(
            ParserModelStatus modelStatus, Pageable pageable);
}
