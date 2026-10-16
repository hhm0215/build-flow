package com.buildflow.notification.infra.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "site-service", contextId = "notificationSiteReferenceClient")
public interface SiteReferenceClient {

    @GetMapping("/api/v1/sites/{id}")
    SiteReferenceResponse findById(@PathVariable("id") Long id);
}
