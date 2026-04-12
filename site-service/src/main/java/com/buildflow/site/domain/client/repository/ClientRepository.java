package com.buildflow.site.domain.client.repository;

import com.buildflow.site.domain.client.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, Long> {
}
