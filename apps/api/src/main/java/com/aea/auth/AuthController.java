package com.aea.auth;

import com.aea.platform.ApiException;
import com.aea.platform.Principal;
import com.aea.platform.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        var tokens = auth.login(request.email().trim(), request.password());
        return TokenResponse.of(tokens);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.of(auth.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        Principal principal = TenantContext.principal()
                .orElseThrow(ApiException::forbidden);
        auth.logout(principal.userId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            // Upper bound matters: BCrypt cost 12 on a megabyte of input is a
            // cheap way to exhaust the request threads.
            @NotBlank @Size(min = 1, max = 200) String password) {}

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    public record TokenResponse(String accessToken, String refreshToken,
                                String tokenType, long expiresIn) {
        static TokenResponse of(AuthService.Tokens t) {
            return new TokenResponse(t.accessToken(), t.refreshToken(),
                    "Bearer", t.expiresInSeconds());
        }
    }
}
