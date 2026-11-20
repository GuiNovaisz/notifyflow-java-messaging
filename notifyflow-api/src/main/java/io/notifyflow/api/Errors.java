package io.notifyflow.api;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> error(Exception error) {
    int status = 500;
    String detail = "Request could not be completed";
    if (error instanceof ResponseStatusException e) {
      status = e.getStatusCode().value();
      detail = e.getReason();
    } else if (error instanceof org.springframework.web.client.RestClientException) {
      status = 503;
      detail = "Delivery service temporarily unavailable";
    } else if (error instanceof org.springframework.web.bind.MethodArgumentNotValidException
        || error instanceof jakarta.validation.ConstraintViolationException
        || error instanceof org.springframework.web.bind.MissingRequestHeaderException
        || error instanceof org.springframework.http.converter.HttpMessageNotReadableException
        || error
            instanceof
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
      status = 400;
      detail = "Invalid request";
    }
    return ResponseEntity.status(status)
        .body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail));
  }
}
