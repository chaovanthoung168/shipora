package com.shipora.pipeline;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class PipelineHttpAdvice {
 @ExceptionHandler(InternalHttp.RemoteFailure.class) public ResponseEntity<ProblemDetail> remote(InternalHttp.RemoteFailure e){
  int status=java.util.Set.of(400,401,403,404,409).contains(e.status)?e.status:503;
  return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),"Pipeline request was denied or a required service is unavailable."));
 }
}
