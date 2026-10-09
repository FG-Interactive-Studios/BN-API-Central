package com.FGInteractive.BatalhaNaval.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank(message = "currentPassword is required")
    @Size(max = 256, message = "currentPassword is too long")
    String currentPassword,

    @NotBlank(message = "newPassword is required")
    @Size(min = 8, max = 72, message = "newPassword must contain between 8 and 72 characters")
    String newPassword
) {}
