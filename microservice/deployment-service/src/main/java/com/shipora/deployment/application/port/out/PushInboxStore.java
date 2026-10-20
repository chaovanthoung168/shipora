package com.shipora.deployment.application.port.out;
import com.shipora.pipeline.Protocol.Push;
import java.util.*;
public interface PushInboxStore {
 void accept(UUID org,Push push);
 Optional<Pending> claim(UUID org);
 void targets(Pending pending,List<UUID> targets);
 void advance(Pending pending);
 void postpone(Pending pending);
 record Pending(UUID organizationId,Push push,UUID token,List<UUID> targets,int cursor,int attempts){@Override public String toString(){return "Pending[redacted]";}}
}
