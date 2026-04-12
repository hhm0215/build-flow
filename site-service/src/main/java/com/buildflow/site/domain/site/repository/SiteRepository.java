package com.buildflow.site.domain.site.repository;

import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.entity.SiteStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteRepository extends JpaRepository<Site, Long> {

    List<Site> findByStatusOrderByCreatedAtDesc(SiteStatus status);

    List<Site> findAllByOrderByCreatedAtDesc();

    boolean existsByClientId(Long clientId);
}
