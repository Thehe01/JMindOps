package com.kama.jmindops.security;

public record AuthenticatedUser(String id, String username, String role) {
}
