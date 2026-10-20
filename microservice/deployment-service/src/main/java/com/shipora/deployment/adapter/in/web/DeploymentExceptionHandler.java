package com.shipora.deployment.adapter.in.web;

import com.shipora.deployment.domain.DeploymentException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;

@RestControllerAdvice(assignableTypes={DeploymentController.class,WorkerController.class,PushController.class})
public class DeploymentExceptionHandler {
    @ExceptionHandler(DeploymentException.class) public ResponseEntity<ProblemDetail> deployment(DeploymentException e){
        return problem(switch(e.getKind()){case INVALID->HttpStatus.BAD_REQUEST;case NOT_FOUND->HttpStatus.NOT_FOUND;case FORBIDDEN->HttpStatus.FORBIDDEN;
            case CONFLICT->HttpStatus.CONFLICT;case UNAVAILABLE->HttpStatus.SERVICE_UNAVAILABLE;},e.getMessage());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,ServletRequestBindingException.class,HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,HandlerMethodValidationException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception e){return problem(HttpStatus.BAD_REQUEST,"Invalid request body or parameters.");}
    @ExceptionHandler(DuplicateKeyException.class) public ResponseEntity<ProblemDetail> duplicate(DuplicateKeyException e){return problem(HttpStatus.CONFLICT,"Deployment request conflicts with an existing record.");}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<ProblemDetail> storage(DataAccessException e){return problem(HttpStatus.SERVICE_UNAVAILABLE,"Deployment storage is unavailable. Retry with the same Idempotency-Key.");}
    private ResponseEntity<ProblemDetail> problem(HttpStatus status,String detail){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatusAndDetail(status,detail));}
}
