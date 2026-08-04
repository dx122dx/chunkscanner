package com.billy65536.chunkscanner.security;

public class SecurityPolicyViolationException extends Exception {
    private final String violatedPolicy;

    public SecurityPolicyViolationException(String message, String policy) {
        super(message + "\t(policy: " + policy + ")");

        this.violatedPolicy = policy;
    }

    public String getViolatedPolicy() {
        return violatedPolicy;
    }
}