package com.buildflow.site.domain.profit.repository;

import com.buildflow.site.domain.profit.entity.SiteProfit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SiteProfitRepository extends JpaRepository<SiteProfit, Long> {

    Optional<SiteProfit> findBySiteId(Long siteId);

    List<SiteProfit> findBySiteIdIn(Collection<Long> siteIds);
}
