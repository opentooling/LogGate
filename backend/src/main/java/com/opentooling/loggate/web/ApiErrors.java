package com.opentooling.loggate.web;

import com.opentooling.loggate.pods.MetricsException;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * How the API answers a request it cannot serve, in one place: a status and a
 * message the page can show as it is.
 *
 * <p>A request the caller has to change is an {@link IllegalArgumentException},
 * whether a controller found the problem or a service did, and its message
 * says what to change. Registered in {@code WebConfig}, like the controllers,
 * and scoped to them.
 */
@RestControllerAdvice(basePackageClasses = ApiErrors.class)
public class ApiErrors {

  /** @param message what went wrong */
  public record ApiError(String message) {}

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ApiError> invalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
  }

  @ExceptionHandler(DateTimeParseException.class)
  ResponseEntity<ApiError> unreadableTime(DateTimeParseException e) {
    return ResponseEntity.badRequest().body(new ApiError("from and to must be ISO-8601 instants"));
  }

  /** The metrics store is the only thing that lists pods, and there is a way round it. */
  @ExceptionHandler(MetricsException.class)
  ResponseEntity<ApiError> metricsUnavailable(MetricsException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ApiError("Pods could not be listed just now. Narrow by pod pattern instead."));
  }
}
