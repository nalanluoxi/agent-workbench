package com.agentdesk.workbench.auth;

import com.agentdesk.workbench.core.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/email-codes")
    public Map<String,Object> sendCode(@Valid @RequestBody EmailRequest request) {
        auth.sendCode(request.email());
        return Map.of("message", "验证码已提交发送，请检查邮箱");
    }

    @PostMapping("/register")
    public Map<String,Object> register(@Valid @RequestBody RegisterRequest request) {
        AuthService.RegistrationResult result = auth.register(request.email(), request.code(), request.password());
        if (!result.success()) throw ApiException.badRequest(result.message());
        return Map.of("message", result.message());
    }

    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest request) { return auth.login(request.email(), request.password()); }

    @PostMapping("/logout")
    public Map<String,Object> logout(HttpServletRequest request) { auth.logout(AuthFilter.require(request)); return Map.of("message", "已退出登录"); }

    @GetMapping("/me")
    public Map<String,Object> me(HttpServletRequest request) {
        Principal principal = AuthFilter.require(request);
        return auth.profile(principal);
    }

    public record EmailRequest(@NotBlank String email) {}
    public record RegisterRequest(@NotBlank String email, @NotBlank String code, @NotBlank String password) {}
    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
}
