package io.notifyflow.contracts;

import jakarta.validation.constraints.*;

public record NotificationRequest(
    @NotBlank @Email @Size(max = 254) String recipient,
    @NotBlank @Pattern(regexp = "EMAIL") String channel,
    @NotBlank @Size(max = 200) String subject,
    @NotBlank @Size(max = 10000) String body) {}
