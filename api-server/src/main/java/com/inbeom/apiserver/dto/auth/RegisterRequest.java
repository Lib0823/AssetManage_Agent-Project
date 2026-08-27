package com.inbeom.apiserver.dto.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 4, max = 20, message = "Username must be between 4 and 20 characters")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank(message = "Password confirmation is required")
    private String passwordConfirm;

    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Phone is required")
    private String phone;

    @NotNull(message = "Birth date is required")
    private LocalDate birthDate;

    @Valid
    private KisAccountInfo kisAccount;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KisAccountInfo {
        @NotBlank(message = "Account number is required")
        private String accountNumber;

        @NotBlank(message = "APP Key is required")
        private String appKey;

        @NotBlank(message = "APP Secret is required")
        private String appSecret;

    }
}
