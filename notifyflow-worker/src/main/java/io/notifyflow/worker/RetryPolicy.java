package io.notifyflow.worker;

public final class RetryPolicy {
  public static final int MAX_ATTEMPTS = 4;

  private RetryPolicy() {}

  public static boolean transientStatus(int status) {
    return status >= 500 || status == 408 || status == 429;
  }

  public static long delay(int completedAttempt, long base) {
    if (completedAttempt < 1 || completedAttempt >= MAX_ATTEMPTS)
      throw new IllegalArgumentException("No retry for attempt");
    return Math.multiplyExact(base, 1L << (completedAttempt - 1));
  }
}
