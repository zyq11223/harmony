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
package edu.snu.cay.dolphin.core.master;

import edu.snu.cay.dolphin.MiniBatchControlMsg;
import edu.snu.cay.dolphin.core.worker.WorkerTasklet;
import edu.snu.cay.jobserver.JobLogger;
import edu.snu.cay.dolphin.DolphinMsg;
import edu.snu.cay.dolphin.ModelEvalAnsMsg;
import edu.snu.cay.dolphin.dolphinMsgType;
import edu.snu.cay.jobserver.Parameters;
import edu.snu.cay.services.et.driver.api.ETMaster;
import edu.snu.cay.services.et.exceptions.ExecutorNotExistException;
import edu.snu.cay.utils.AvroUtils;
import org.apache.reef.exception.evaluator.NetworkException;
import org.apache.reef.tang.InjectionFuture;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;

import java.util.logging.Level;

/**
 * Master-side message sender.
 */
final class MasterSideMsgSender {
  private final JobLogger jobLogger;
  private final String jobId;

  private final InjectionFuture<ETMaster> etMasterFuture;
  private final InjectionFuture<ETTaskRunner> taskRunnerFuture;
  private final byte[] serializedReleaseMsg;

  @Inject
  private MasterSideMsgSender(final JobLogger jobLogger,
                              @Parameter(Parameters.JobId.class) final String jobId,
                              final InjectionFuture<ETMaster> etMasterFuture,
                              final InjectionFuture<ETTaskRunner> taskRunnerFuture) {
    this.jobLogger = jobLogger;
    this.jobId = jobId;
    this.etMasterFuture = etMasterFuture;
    this.taskRunnerFuture = taskRunnerFuture;

    this.serializedReleaseMsg = AvroUtils.toBytes(DolphinMsg.newBuilder()
        .setType(dolphinMsgType.ReleaseMsg)
        .build(), DolphinMsg.class);
  }

  /**
   * Send a release msg to {@code workerId}.
   * @param workerId an identifier of worker
   */
  void sendReleaseMsg(final String workerId) {
    try {
      taskRunnerFuture.get().getWorkerTasklet(workerId).send(serializedReleaseMsg);
    } catch (NetworkException e) {
      jobLogger.log(Level.INFO, String.format("Fail to send release msg to worker %s.", workerId), e);
    }
  }

  /**
   * Send a mini-batch control msg to {@code workerId}.
   * @param workerId an identifier of worker
   */
  void sendMiniBatchControlMsg(final String workerId, final boolean stop) {
    final DolphinMsg msg = DolphinMsg.newBuilder()
        .setType(dolphinMsgType.MiniBatchControlMsg)
        .setMiniBatchControlMsg(MiniBatchControlMsg.newBuilder()
            .setStop(stop).build())
        .build();

    try {
      taskRunnerFuture.get().getWorkerTasklet(workerId).send(AvroUtils.toBytes(msg, DolphinMsg.class));
    } catch (NetworkException e) {
      jobLogger.log(Level.INFO, String.format("Fail to send mini-batch control msg to worker %s.", workerId), e);
    }
  }

  /**
   * Send a response msg for model evaluation request from a worker.
   * @param workerId a worker id
   * @param doNext a boolean indicating whether a worker do one more evaluation or not
   */
  void sendModelEvalAnsMsg(final String workerId, final boolean doNext) {
    final DolphinMsg msg = DolphinMsg.newBuilder()
        .setType(dolphinMsgType.ModelEvalAnsMsg)
        .setModelEvalAnsMsg(ModelEvalAnsMsg.newBuilder()
            .setDoNext(doNext).build())
        .build();

    try {
      etMasterFuture.get().getExecutor(workerId).getRunningTasklet(jobId + "-" + WorkerTasklet.TASKLET_ID)
          .send(AvroUtils.toBytes(msg, DolphinMsg.class));
    } catch (NetworkException | ExecutorNotExistException e) {
      jobLogger.log(Level.INFO, String.format("Fail to send ModelEvalAns msg to worker %s.", workerId), e);
    }
  }
}
