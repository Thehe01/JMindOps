package com.kama.jmindops.model.request;

import com.kama.jmindops.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度必须在 3 到 32 个字符之间")
    @Pattern(regexp = ValidationPatterns.USERNAME, message = "用户名只能包含字母、数字、下划线或短横线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度必须在 8 到 72 个字符之间")
    private String password;

    public RegisterRequest() {}
    public RegisterRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }
    public String getUsername() { return this.username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return this.password; }
    public void setPassword(String password) { this.password = password; }
    public static RegisterRequestBuilder builder() { return new RegisterRequestBuilder(); }
    public static class RegisterRequestBuilder {
        private String username;
        private String password;
        public RegisterRequestBuilder() {}
        public RegisterRequestBuilder username(String username) { this.username = username; return this; }
        public RegisterRequestBuilder password(String password) { this.password = password; return this; }
        public RegisterRequest build() { return new RegisterRequest(username, password); }
    }
}
