package com.billy65536.chunkscanner.security.server_optin;

import com.billy65536.chunkscanner.security.SecurityPolicyViolationException;

public class ServerAuthorizationRequiredException extends SecurityPolicyViolationException {
    public ServerAuthorizationRequiredException(String message) {
        super(message, "server_authorization");
    }
}