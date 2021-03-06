/*
 * Copyright (C) 2017 Seoul National University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.snu.cay.services.et.evaluator.impl;

import edu.snu.cay.services.et.evaluator.api.DataOpResult;
import edu.snu.cay.services.et.exceptions.DataAccessFailedException;

import javax.annotation.Nullable;
import java.util.concurrent.*;

/**
 * A class representing the result of table data operation.
 * @param <V> a type of data value
 */
class SingleKeyDataOpResult<V> implements DataOpResult<V> {

  /**
   * A latch that will be released when the operation gets result.
   */
  private final CountDownLatch completedLatch;

  /**
   * Result of the operation.
   */
  private volatile boolean isSuccess;
  private volatile V resultData;

  SingleKeyDataOpResult() {
    this.isSuccess = false;
    this.resultData = null;
    this.completedLatch = new CountDownLatch(1);
  }

  SingleKeyDataOpResult(final V resultData, final boolean isSuccess) {
    this.isSuccess = isSuccess;
    this.resultData = resultData;
    this.completedLatch = new CountDownLatch(0);
  }

  @Override
  public void onCompleted(final @Nullable V output, final boolean success) {
    resultData = output;
    isSuccess = success;
    completedLatch.countDown();
  }

  @Override
  public boolean cancel(final boolean mayInterruptIfRunning) {
    return false;
  }

  @Override
  public boolean isCancelled() {
    return false;
  }

  @Override
  public boolean isDone() {
    return completedLatch.getCount() == 0;
  }

  @Override
  public V get() throws InterruptedException, ExecutionException {
    completedLatch.await();
    if (!isSuccess) {
      throw new DataAccessFailedException("Fail to execute table access operation");
    }
    return resultData;
  }

  @Override
  public V get(final long timeout, final TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    if (!completedLatch.await(timeout, unit)) {
      throw new TimeoutException("Timeout while waiting for the completion");
    }

    if (!isSuccess) {
      throw new DataAccessFailedException("Fail to execute table access operation");
    }
    return resultData;
  }
}
