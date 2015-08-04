/*
 * Copyright (C) 2015 Seoul National University
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
package edu.snu.cay.services.em.examples.simple;

import edu.snu.cay.services.em.evaluator.api.MemoryStore;
import edu.snu.cay.services.em.evaluator.api.PartitionTracker;
import org.apache.reef.driver.task.TaskConfigurationOptions;
import edu.snu.cay.services.em.examples.simple.parameters.Iterations;
import edu.snu.cay.services.em.examples.simple.parameters.PeriodMillis;
import org.apache.reef.tang.annotations.Parameter;
import org.apache.reef.task.HeartBeatTriggerManager;
import org.apache.reef.task.Task;

import javax.inject.Inject;
import java.util.logging.Logger;

final class SimpleEMTask implements Task {
  private static final Logger LOG = Logger.getLogger(SimpleEMTask.class.getName());
  public static final String KEY = "INTEGER";
  private static final int ID_BASE = 100;

  private final MemoryStore memoryStore;
  private final SimpleEMTaskReady simpleEMTaskReady;
  private final HeartBeatTriggerManager heartBeatTriggerManager;
  private final int iterations;
  private final long periodMillis;

  @Inject
  private SimpleEMTask(
      final MemoryStore memoryStore,
      final SimpleEMTaskReady simpleEMTaskReady,
      final HeartBeatTriggerManager heartBeatTriggerManager,
      final PartitionTracker partitionTracker,
      @Parameter(TaskConfigurationOptions.Identifier.class) final String taskId,
      @Parameter(Iterations.class) final int iterations,
      @Parameter(PeriodMillis.class) final long periodMillis) {
    this.memoryStore = memoryStore;
    this.simpleEMTaskReady = simpleEMTaskReady;
    this.heartBeatTriggerManager = heartBeatTriggerManager;
    this.iterations = iterations;
    this.periodMillis = periodMillis;

    final int myIdBase = Integer.valueOf(taskId.substring(SimpleEMDriver.TASK_ID_PREFIX.length())) * ID_BASE;
    partitionTracker.registerPartition(KEY, myIdBase, myIdBase + ID_BASE - 1);

    for (int index = 0; index < 10; index++) {
      final int item = myIdBase + index * 10;
      this.memoryStore.getElasticStore().put(KEY, (long) item, item);
    }
  }

  public byte[] call(final byte[] memento) throws InterruptedException {
    LOG.info("SimpleEMTask commencing...");

    LOG.info("Before sleep, memory store contains: ");
    LOG.info(memoryStore.getElasticStore().getAll(KEY).toString());
    // Should be either
    // [0, 10, 20, 30, 40, 50, 60, 70, 80, 90]
    // or
    // [100, 110, 120, 130, 140, 150, 160, 170, 180, 190]

    simpleEMTaskReady.setReady(true);
    heartBeatTriggerManager.triggerHeartBeat();

    // Sleep one more period than the Driver, to give the Driver time to finish
    final long sleepMillis = (iterations + 1) * periodMillis;
    LOG.info("Sleep for: " + sleepMillis);
    Thread.sleep(sleepMillis);

    LOG.info("After sleep, memory store contains: ");
    LOG.info(memoryStore.getElasticStore().getAll(KEY).toString());
    // Evaluator that receives on the last iteration should have more than before
    // Evaluator that sends on the last iterations should have less than before

    return null;
  }
}