/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.statefun.flink.core.httpfn;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.apache.flink.statefun.flink.core.backpressure.BoundedExponentialBackoff;
import org.apache.flink.statefun.flink.core.metrics.RemoteInvocationMetrics;
import org.apache.flink.statefun.flink.core.reqreply.ToFunctionRequestSummary;
import org.apache.flink.util.function.RunnableWithException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("NullableProblems")
final class RetryingCallback implements Callback {
  private static final Duration INITIAL_BACKOFF_DURATION = Duration.ofMillis(1000);
  private static final double BACKOFF_JITTER = 0.1;
  private static final double BACKOFF_INCREASE_FACTOR = 2;

  private static final Set<Integer> RETRYABLE_HTTP_CODES =
      new HashSet<>(Arrays.asList(400, 408, 409, 420, 429, 499, 500, 503, 504));

  private static final Logger LOG = LoggerFactory.getLogger(RetryingCallback.class);

  private final CompletableFuture<Response> resultFuture;
  private final BoundedExponentialBackoff backoff;
  private final ToFunctionRequestSummary requestSummary;
  private final RemoteInvocationMetrics metrics;
  private final BooleanSupplier isShutdown;
  private final int maxRetries;

  private long requestStarted;

  private int retryAttempts;

  RetryingCallback(
      ToFunctionRequestSummary requestSummary,
      RemoteInvocationMetrics metrics,
      Timeout timeout,
      BooleanSupplier isShutdown,
      int maxRetries) {
    this.resultFuture = new CompletableFuture<>();
    this.backoff =
        new BoundedExponentialBackoff(
            INITIAL_BACKOFF_DURATION, BACKOFF_INCREASE_FACTOR, BACKOFF_JITTER, duration(timeout));
    this.requestSummary = requestSummary;
    this.metrics = metrics;
    this.isShutdown = Objects.requireNonNull(isShutdown);
    this.maxRetries = maxRetries;
    this.retryAttempts = 0;
  }

  CompletableFuture<Response> future() {
    return resultFuture;
  }

  void attachToCall(Call call) {
    this.requestStarted = System.nanoTime();
    call.enqueue(this);
  }

  @Override
  public void onFailure(Call call, IOException cause) {
    tryWithFuture(() -> onFailureUnsafe(call, cause));
  }

  @Override
  public void onResponse(Call call, Response response) {
    tryWithFuture(() -> onResponseUnsafe(call, response));
  }

  private void onFailureUnsafe(Call call, IOException cause) {
    if (isShutdown.getAsBoolean()) {
      throw new IllegalStateException("An exception caught during shutdown.", cause);
    }

    final double callDurationSeconds = timeSinceRequestStartedInNanoseconds() / Math.pow(10, 9);
    LOG.warn(
        "Retriable exception caught after "
            + callDurationSeconds
            + " seconds while trying to deliver a message: "
            + requestSummary,
        cause);
    metrics.remoteInvocationFailures();

    if (!retryAfterApplyingBackoff(call)) {
      throw new IllegalStateException(
          "Maximal request time has elapsed after "
              + callDurationSeconds
              + " seconds. Last cause is attached",
          cause);
    }
  }

  private void onResponseUnsafe(Call call, Response response) {
    if (response.isSuccessful()) {
      resultFuture.complete(response);
      return;
    }

    boolean isRetryable = RETRYABLE_HTTP_CODES.contains(response.code());

    String prefixString =
        isRetryable
            ? "Non-successful, retryable HTTP response code " + response.code() + " received"
            : "Non-successful, non-retryable HTTP response code " + response.code() + " received";

    try {
      ResponseBody body = response.body();
      if (body == null) {
        String errorMessage = prefixString + " and the response body is null.";
        if (isRetryable) {
          LOG.warn(errorMessage);
        } else {
          LOG.error(errorMessage);
        }
      } else {
        String bodyText = body.string();
        String errorMessage = prefixString + " with body \"" + bodyText + "\".";
        if (isRetryable) {
          LOG.warn(errorMessage);
        } else {
          LOG.error(errorMessage);
        }
      }
    } catch (IOException exception) {
      String errorMessage =
          prefixString + " and encountered an IOException while reading the body.";
      if (isRetryable) {
        LOG.warn(errorMessage);
      } else {
        LOG.error(errorMessage);
      }
    }

    response.close();

    if ((maxRetries >= 0) && (response.code() == 500)) {
      if (retryAttempts < maxRetries) {
        LOG.warn("Failed attempt " + retryAttempts + " of " + maxRetries + ". Retrying.");
        retryAttempts++;
      } else {
        LOG.warn("Maximum number of attempts (" + maxRetries + ") exceeded. Dropping message.");
        resultFuture.complete(null);
        return;
      }
    }

    if (!isRetryable && response.code() < 500) {
      throw new IllegalStateException("Non successful HTTP response code " + response.code());
    }

    if (!retryAfterApplyingBackoff(call)) {
      final double callDurationSeconds = timeSinceRequestStartedInNanoseconds() / Math.pow(10, 9);

      throw new IllegalStateException(
          String.format(
              "Maximal request time has elapsed after %f seconds. Last known error is: invalid HTTP response code %d",
              callDurationSeconds, response.code()));
    }
  }

  /**
   * Retires the original call, after applying backoff.
   *
   * @return if the request was retried successfully, false otherwise.
   */
  private boolean retryAfterApplyingBackoff(Call call) {
    if (backoff.applyNow()) {
      Call newCall = call.clone();
      attachToCall(newCall);
      return true;
    }
    return false;
  }

  /**
   * Executes the runnable, and completes {@link #resultFuture} with any exceptions thrown, during
   * its execution.
   */
  private void tryWithFuture(RunnableWithException runnable) {
    try {
      endTimingRequest();
      runnable.run();
    } catch (Throwable t) {
      resultFuture.completeExceptionally(t);
    }
  }

  private static Duration duration(Timeout timeout) {
    return Duration.ofNanos(timeout.timeoutNanos());
  }

  private void endTimingRequest() {
    final long nanosecondsElapsed = this.timeSinceRequestStartedInNanoseconds();
    final long millisecondsElapsed = TimeUnit.NANOSECONDS.toMillis(nanosecondsElapsed);
    metrics.remoteInvocationLatency(millisecondsElapsed);
  }

  private long timeSinceRequestStartedInNanoseconds() {
    return System.nanoTime() - requestStarted;
  }
}
