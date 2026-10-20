package com.shipora.deployment.application.port.out;
import com.shipora.deployment.domain.ApplicationSnapshot;
import java.util.*;
public interface AutomaticCatalog {
 List<UUID> matching(UUID org,UUID repository,String branch);
 ApplicationSnapshot configuration(UUID org,UUID applicationId);
}
