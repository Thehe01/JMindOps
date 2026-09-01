package com.kama.jmindops.service;

import com.kama.jmindops.model.request.LoginRequest;
import com.kama.jmindops.model.request.RegisterRequest;
import com.kama.jmindops.model.response.AuthResponse;

public interface AuthService {
    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);
}
