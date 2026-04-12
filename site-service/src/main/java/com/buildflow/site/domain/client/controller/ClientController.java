package com.buildflow.site.domain.client.controller;

import com.buildflow.site.domain.client.dto.ClientCreateRequest;
import com.buildflow.site.domain.client.dto.ClientResponse;
import com.buildflow.site.domain.client.dto.ClientUpdateRequest;
import com.buildflow.site.domain.client.service.ClientService;
import com.buildflow.site.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
public class ClientController {

    private final ClientService clientService;

    @PostMapping
    public ResponseEntity<ApiResponse<ClientResponse>> create(
            @Valid @RequestBody ClientCreateRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(clientService.create(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ClientResponse>>> findAll() {
        return ResponseEntity.ok(ApiResponse.success(clientService.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ClientResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(clientService.findById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ClientResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody ClientUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(clientService.update(id, request)));
    }
}
