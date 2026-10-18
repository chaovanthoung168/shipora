package com.shipora.git.application.port.in;

import com.shipora.git.application.dto.*;
import java.util.List;
import java.util.UUID;

public interface GitConnectionUseCases {
    DiscoveryConnectionResult start(UUID organizationId);
    ConnectionAuthorizationResult complete(UUID organizationId, String state, String code);
    List<AccountChoice> choices(UUID organizationId, String connectionToken);
    InstallationResult select(UUID organizationId, String connectionToken, UUID choiceId);

    record AccountChoice(UUID id, String accountName) {}
}
