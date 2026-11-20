package io.notifyflow.tests;

import static org.assertj.core.api.Assertions.*;

import io.notifyflow.worker.RetryPolicy;
import org.junit.jupiter.api.Test;

class PolicyTest {
  @Test
  void exponentialDelays() {
    assertThat(
            new long[] {
              RetryPolicy.delay(1, 5000), RetryPolicy.delay(2, 5000), RetryPolicy.delay(3, 5000)
            })
        .containsExactly(5000, 10000, 20000);
  }

  @Test
  void rejectsUnboundedRetry() {
    assertThatThrownBy(() -> RetryPolicy.delay(4, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidAttempt() {
    assertThatThrownBy(() -> RetryPolicy.delay(0, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void transientStatusClassification() {
    assertThat(RetryPolicy.transientStatus(500)).isTrue();
    assertThat(RetryPolicy.transientStatus(429)).isTrue();
    assertThat(RetryPolicy.transientStatus(408)).isTrue();
  }

  @Test
  void permanentStatusClassification() {
    assertThat(RetryPolicy.transientStatus(400)).isFalse();
    assertThat(RetryPolicy.transientStatus(409)).isFalse();
  }

  @Test
  void boundedAttempts() {
    assertThat(RetryPolicy.MAX_ATTEMPTS).isEqualTo(4);
  }
}
