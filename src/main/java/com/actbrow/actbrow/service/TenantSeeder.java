package com.actbrow.actbrow.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class TenantSeeder implements ApplicationRunner {

	private final TenantService tenantService;

	public TenantSeeder(TenantService tenantService) {
		this.tenantService = tenantService;
	}

	@Override
	public void run(ApplicationArguments args) {
		// Tenant creation is now handled automatically on user login
		// This seeder is no longer needed
	}
}
