package com.shipora.git.application.port.out;

import com.shipora.git.application.dto.GitHubAppCredentials;
import java.net.URI;

public interface GitHubRegistration {
    GitHubAppCredentials convert(String code, URI callbackUrl);
}
