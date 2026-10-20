package com.shipora.deployment.application.port.out;

import com.shipora.deployment.domain.ApplicationSnapshot;
import java.util.UUID;
public interface DeploymentCatalog { ApplicationSnapshot requireReadyApplication(UUID organizationId,UUID applicationId); }
