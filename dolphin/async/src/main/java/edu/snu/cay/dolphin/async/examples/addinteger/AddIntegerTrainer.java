/*
 * Copyright (C) 2016 Seoul National University
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
package edu.snu.cay.dolphin.async.examples.addinteger;

import edu.snu.cay.common.metric.*;
import edu.snu.cay.common.param.Parameters;
import edu.snu.cay.dolphin.async.Trainer;
import edu.snu.cay.dolphin.async.metric.Tracer;
import edu.snu.cay.dolphin.async.metric.avro.WorkerMetrics;
import edu.snu.cay.services.em.evaluator.api.MemoryStore;
import edu.snu.cay.services.ps.worker.api.ParameterWorker;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link Trainer} class for the AddIntegerREEF application.
 * Pushes a value to the server and checks the current value at the server via pull, once per iteration.
 * It sleeps {@link #computeTime} for each iteration to simulate computation, preventing the saturation of NCS of PS.
 */
final class AddIntegerTrainer implements Trainer {
  private static final Logger LOG = Logger.getLogger(AddIntegerTrainer.class.getName());

  /**
   * Sleep to wait validation check is possible.
   */
  private static final long VALIDATE_SLEEP_MS = 100;

  /**
   * Retry validation check maximum 20 times to wait server processing.
   */
  private static final int NUM_VALIDATE_RETRIES = 20;

  private final ParameterWorker<Integer, Integer, Integer> parameterWorker;

  /**
   * The integer to be added to each key in an update.
   */
  private final int delta;

  /**
   * The number of keys.
   */
  private final int numberOfKeys;

  /**
   * The number of updates for each key in an iteration.
   */
  private final int numberOfUpdates;

  /**
   * Sleep time to simulate computation.
   */
  private final long computeTime;

  /**
   * The expected total sum of each key.
   */
  private final int expectedResult;

  private final MemoryStore<Long> memoryStore;

  // TODO #487: Metric collecting should be done by the system, not manually by the user code.
  private final MetricsMsgSender<WorkerMetrics> metricsMsgSender;
  private final Tracer computeTracer;

  @Inject
  private AddIntegerTrainer(final ParameterWorker<Integer, Integer, Integer> parameterWorker,
                            @Parameter(AddIntegerREEF.DeltaValue.class) final int delta,
                            @Parameter(AddIntegerREEF.NumKeys.class) final int numberOfKeys,
                            @Parameter(AddIntegerREEF.NumUpdatesPerItr.class) final int numberOfUpdates,
                            @Parameter(AddIntegerREEF.NumWorkers.class) final int numberOfWorkers,
                            @Parameter(AddIntegerREEF.ComputeTimeMs.class) final long computeTime,
                            @Parameter(Parameters.Iterations.class) final int numIterations,
                            final MemoryStore<Long> memoryStore,
                            final MetricsMsgSender<WorkerMetrics> metricsMsgSender) {
    this.parameterWorker = parameterWorker;
    this.delta = delta;
    this.numberOfKeys = numberOfKeys;
    this.numberOfUpdates = numberOfUpdates;
    this.computeTime = computeTime;

    // TODO #681: Need to consider numWorkerThreads after multi-thread worker is enabled
    this.expectedResult = delta * numberOfWorkers * numIterations * numberOfUpdates;
    LOG.log(Level.INFO, "delta:{0}, numWorkers:{1}, numIterations:{2}, numberOfUpdates:{3}",
        new Object[]{delta, numberOfWorkers, numIterations, numberOfUpdates});

    this.memoryStore = memoryStore;
    this.metricsMsgSender = metricsMsgSender;

    this.computeTracer = new Tracer();
  }

  @Override
  public void initialize() {
  }

  @Override
  public void run() {
    // sleep to simulate computation
    computeTracer.startTimer();
    try {
      Thread.sleep(computeTime);
    } catch (final InterruptedException e) {
      LOG.log(Level.WARNING, "Interrupted while sleeping to simulate computation", e);
    } finally {
      computeTracer.recordTime(1);
    }

    for (int i = 0; i < numberOfUpdates; i++) {
      for (int key = 0; key < numberOfKeys; key++) {
        parameterWorker.push(key, delta);
        final Integer value = parameterWorker.pull(key);
        LOG.log(Level.INFO, "Current value associated with key {0} is {1}", new Object[]{key, value});
      }
    }

    // send empty metrics to trigger optimization
    final WorkerMetrics workerMetrics =
        buildMetricsMsg(memoryStore.getNumBlocks());

    sendMetrics(workerMetrics);
  }

  private void sendMetrics(final WorkerMetrics workerMetrics) {
    LOG.log(Level.FINE, "Sending WorkerMetrics {0}", workerMetrics);

    metricsMsgSender.send(workerMetrics);
  }

  private WorkerMetrics buildMetricsMsg(final int numDataBlocks) {
    return WorkerMetrics.newBuilder()
        .setNumDataBlocks(numDataBlocks)
        .setTotalCompTime(computeTracer.totalElapsedTime())
        .build();
  }

  @Override
  public void cleanup() {
    int numRemainingRetries = NUM_VALIDATE_RETRIES;

    while (numRemainingRetries-- > 0) {
      if (validate()) {
        LOG.log(Level.INFO, "Validation success");
        return;
      }

      try {
        Thread.sleep(VALIDATE_SLEEP_MS);
      } catch (final InterruptedException e) {
        LOG.log(Level.WARNING, "Interrupted while sleeping to compare the result with expected value", e);
      }
    }

    LOG.log(Level.WARNING, "Validation failed");
  }

  /**
   * Checks the result(total sum) of each key is same with expected result.
   *
   * @return true if all of the values of keys are matched with expected result, otherwise false.
   */
  private boolean validate() {
    LOG.log(Level.INFO, "Start validation");
    boolean isSuccess = true;
    for (int key = 0; key < numberOfKeys; key++) {
      final int result = parameterWorker.pull(key);

      if (expectedResult != result) {
        LOG.log(Level.WARNING, "For key {0}, expected value {1} but received {2}",
            new Object[]{key, expectedResult, result});
        isSuccess = false;
      }
    }
    return isSuccess;
  }
}