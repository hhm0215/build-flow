package com.buildflow.purchase.infra.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "site-service", contextId = "purchaseSiteReferenceClient")
public interface SiteReferenceClient {

    @GetMapping("/api/v1/sites/{id}")
    SiteReferenceResponse findById(@PathVariable("id") Long id);
}
