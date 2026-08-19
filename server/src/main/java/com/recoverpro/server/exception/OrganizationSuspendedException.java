package com.recoverpro.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class OrganizationSuspendedException extends AuthServiceException {
    public OrganizationSuspendedException(String message) { super(message); }
}
