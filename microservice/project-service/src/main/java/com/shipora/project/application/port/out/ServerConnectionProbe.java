package com.shipora.project.application.port.out;

import com.shipora.project.domain.model.ConnectionCheckCode;
import com.shipora.project.domain.model.Server;

public interface ServerConnectionProbe {
    ConnectionCheckCode check(Server server);
}
