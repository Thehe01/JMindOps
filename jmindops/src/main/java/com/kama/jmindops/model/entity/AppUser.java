package com.kama.jmindops.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AppUser {
    private String id;
    private String username;
    private String passwordHash;
    private String role;
    private LocalDateTime createdAt;

    public AppUser() {}
    public AppUser(String id, String username, String passwordHash, String role, LocalDateTime createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = createdAt;
    }
    public String getId() { return this.id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return this.username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return this.passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return this.role; }
    public void setRole(String role) { this.role = role; }
    public LocalDateTime getCreatedAt() { return this.createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public static AppUserBuilder builder() { return new AppUserBuilder(); }
    public static class AppUserBuilder {
        private String id;
        private String username;
        private String passwordHash;
        private String role;
        private LocalDateTime createdAt;
        public AppUserBuilder() {}
        public AppUserBuilder id(String id) { this.id = id; return this; }
        public AppUserBuilder username(String username) { this.username = username; return this; }
        public AppUserBuilder passwordHash(String passwordHash) { this.passwordHash = passwordHash; return this; }
        public AppUserBuilder role(String role) { this.role = role; return this; }
        public AppUserBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public AppUser build() { return new AppUser(id, username, passwordHash, role, createdAt); }
    }
}
