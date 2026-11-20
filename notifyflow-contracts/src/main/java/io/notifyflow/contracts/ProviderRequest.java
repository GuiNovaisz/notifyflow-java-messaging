package io.notifyflow.contracts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ProviderRequest(
    @NotNull UUID notificationId, @NotNull @Valid NotificationRequest notification) {}
