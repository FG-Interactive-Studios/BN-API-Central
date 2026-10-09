package com.FGInteractive.BatalhaNaval.auth.dto;


import jakarta.validation.constraints.*;
public record RegisterRequest(
    @NotBlank(message="nickname is required")
    @Size(min=3,max=30,message="nickname must contain between 3 and 30 characters")
    String nickname,
    @NotBlank(message="email is required")
    @Email(message="email must be valid")
    @Size(max=254,message="email must contain at most 254 characters")
    String email,
    @NotBlank(message="password is required")
    @Size(min=8,max=72,message="password must contain between 8 and 72 characters")
    String password
) {}
