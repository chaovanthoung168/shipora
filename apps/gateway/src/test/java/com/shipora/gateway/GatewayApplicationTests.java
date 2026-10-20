package com.shipora.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class GatewayApplicationTests {

	@Autowired
	private RouteLocator routeLocator;

	@Test
	void contextLoads() {
	}

	@Test
	void routesAreProperlyConfigured() {
		List<String> routeIds = routeLocator.getRoutes().map(Route::getId).collectList().block();
		
		assertTrue(routeIds != null && !routeIds.isEmpty(), "Route list should not be empty");
		assertTrue(routeIds.contains("deployment-service-releases"));
		assertTrue(routeIds.contains("deployment-service-deployments"));
		assertTrue(routeIds.contains("application-service-applications"));
		assertTrue(routeIds.contains("application-service-kubernetes-targets"));
		assertTrue(routeIds.contains("project-service-projects"));
		assertTrue(routeIds.contains("project-service-servers"));
		assertTrue(routeIds.contains("git-service-git"));
	}
}

