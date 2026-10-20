package com.shipora.application.adapter.in.web;
import com.shipora.application.adapter.in.security.CurrentOrganization;
import com.shipora.application.application.dto.ApplicationResult;
import com.shipora.application.application.port.out.ApplicationStore;
import com.shipora.application.application.service.WorkerContextService;
import com.shipora.application.domain.ApplicationRules;
import com.shipora.pipeline.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequiredArgsConstructor
public class PipelineController {
 private final LeaseAuthorizer leases;private final CurrentOrganization organizations;private final WorkerContextService contexts;private final ApplicationStore applications;
 @PostMapping("/internal/worker/jobs/{job}/application-context")
 public ResponseEntity<Protocol.ApplicationContext> context(Authentication auth,@RequestHeader("Authorization") String authorization,@PathVariable("job") UUID job,@RequestBody Protocol.LeaseRequest in){
  var grant=leases.authorize(organizations.require(auth),job,in.leaseToken(),authorization);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(contexts.context(grant));
 }
 @GetMapping("/internal/automation/applications")
 public ResponseEntity<List<ApplicationResult>> eligible(Authentication auth,@RequestParam("repositoryId") UUID repository,@RequestParam("branch") String branch,
    @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="100") int size){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(applications.autoDeployApplications(organizations.require(auth),repository,ApplicationRules.branch(branch),ApplicationRules.offset(page,size),size).stream().map(ApplicationResult::from).toList());
 }
}
