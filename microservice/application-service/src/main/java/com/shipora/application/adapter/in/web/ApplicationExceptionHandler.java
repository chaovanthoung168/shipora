package com.shipora.application.adapter.in.web;

import com.shipora.application.domain.ApplicationException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes={ApplicationController.class,KubernetesTargetController.class,PipelineController.class})
public class ApplicationExceptionHandler {
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ProblemDetail> application(ApplicationException e){
        HttpStatus status=switch(e.getKind()){
            case INVALID->HttpStatus.BAD_REQUEST;
            case NOT_FOUND->HttpStatus.NOT_FOUND;
            case FORBIDDEN->HttpStatus.FORBIDDEN;
            case CONFLICT->HttpStatus.CONFLICT;
            case DEPENDENCY,UNAVAILABLE->HttpStatus.SERVICE_UNAVAILABLE;
        };
        return problem(status,e.getMessage());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,ServletRequestBindingException.class,
            HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception e){return problem(HttpStatus.BAD_REQUEST,"Invalid request body or parameters.");}
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> duplicate(DuplicateKeyException e){return problem(HttpStatus.CONFLICT,"An application slug or Kubernetes target name is already in use.");}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> storage(DataAccessException e){return problem(HttpStatus.SERVICE_UNAVAILABLE,"Application storage is unavailable.");}
    private ResponseEntity<ProblemDetail> problem(HttpStatus status,String detail){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatusAndDetail(status,detail));
    }
}
