package com.actbrow.actbrow.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.actbrow.actbrow.api.dto.TenantRequest;

@Component
public class TenantSeeder implements ApplicationRunner {

	private final TenantService tenantService;

	public TenantSeeder(TenantService tenantService) {
		this.tenantService = tenantService;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			tenantService.create(new TenantRequest("default", "Default Tenant", "ak_default_tenant_key", true));
		}
		catch (IllegalArgumentException e) {
			// Tenant already exists, ignore
		}
	}
}
