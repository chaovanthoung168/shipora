package com.shipora.deployment.adapter.in.web;
import com.shipora.deployment.adapter.in.security.CurrentOrganization;
import com.shipora.deployment.application.service.AutomaticPushService;
import com.shipora.pipeline.Protocol;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequiredArgsConstructor
public class PushController {
 private final AutomaticPushService service;private final CurrentOrganization organizations;
 @PostMapping("/internal/git-pushes") public ResponseEntity<Map<String,Object>> accept(Authentication auth,@RequestBody Protocol.Push push){
  service.accept(organizations.require(auth),push);return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("eventId",push.eventId(),"status","ACCEPTED"));
 }
}
