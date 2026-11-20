package io.notifyflow.worker;

import io.notifyflow.contracts.NotificationEvent;
import org.springframework.stereotype.Component;

/**
 * Replacement seam for deterministic tests. Runtime process injection only in failure-test profile.
 */
@Component
public class CrashHook {
  public void afterProvider(NotificationEvent event) {}
}
