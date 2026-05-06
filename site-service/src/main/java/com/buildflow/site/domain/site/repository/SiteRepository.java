package com.buildflow.site.domain.site.repository;

import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.entity.SiteStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Long> {

    @EntityGraph(attributePaths = "client")
    @Query("SELECT s FROM Site s WHERE s.status = :status ORDER BY s.createdAt DESC")
    List<Site> findByStatusOrderByCreatedAtDesc(@Param("status") SiteStatus status);

    @EntityGraph(attributePaths = "client")
    @Query("SELECT s FROM Site s ORDER BY s.createdAt DESC")
    List<Site> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = "client")
    @Query("SELECT s FROM Site s WHERE s.id = :id")
    Optional<Site> findByIdWithClient(@Param("id") Long id);

    boolean existsByClientId(Long clientId);
}
