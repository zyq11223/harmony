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
package edu.snu.cay.dolphin.jobserver;

import edu.snu.cay.dolphin.DolphinMsg;
import edu.snu.cay.dolphin.DolphinParameters;
import edu.snu.cay.dolphin.core.master.DolphinMaster;
import edu.snu.cay.dolphin.core.master.MasterSideMsgHandler;
import edu.snu.cay.jobserver.Parameters;
import edu.snu.cay.jobserver.driver.JobMaster;
import edu.snu.cay.jobserver.driver.JobServerDriver;
import edu.snu.cay.services.et.driver.api.AllocatedExecutor;
import edu.snu.cay.services.et.driver.api.AllocatedTable;
import edu.snu.cay.utils.AvroUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.reef.tang.InjectionFuture;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;
import java.util.List;

/**
 * JobMaster implementation for Dolphin.
 */
public final class DolphinJobMaster implements JobMaster {

  private final String jobId;

  private final boolean offlineModelEval;
  private final InjectionFuture<JobServerDriver> jobServerDriverFuture;
  private final DolphinMaster dolphinMaster;
  private final MasterSideMsgHandler msgHandler;

  @Inject
  private DolphinJobMaster(@Parameter(DolphinParameters.OfflineModelEvaluation.class) final boolean offlineModelEval,
                           @Parameter(Parameters.JobId.class) final String jobId,
                           final InjectionFuture<JobServerDriver> jobServerDriverFuture,
                           final DolphinMaster dolphinMaster,
                           final MasterSideMsgHandler msgHandler) {
    this.offlineModelEval = offlineModelEval;
    this.jobId = jobId;
    this.jobServerDriverFuture = jobServerDriverFuture;
    this.dolphinMaster = dolphinMaster;
    this.msgHandler = msgHandler;
  }

  @Override
  public void onMsg(final String srcId, final byte[] bytes) {
    final DolphinMsg dolphinMsg = AvroUtils.fromBytes(bytes, DolphinMsg.class);
    msgHandler.onDolphinMsg(dolphinMsg);
  }

  @Override
  public void start(final List<List<AllocatedExecutor>> executorGroups, final List<AllocatedTable> tables) {
    final List<AllocatedExecutor> servers = executorGroups.get(0);
    final List<AllocatedExecutor> workers = executorGroups.get(1);

    final AllocatedTable modelTable = tables.get(0);
    final AllocatedTable inputTable = tables.get(1);
    dolphinMaster.start(servers, workers, modelTable, inputTable);

    if (offlineModelEval) {
      jobServerDriverFuture.get().registerDolphinMasterToEvaluateModel(jobId, Pair.of(this, dolphinMaster));
    }
  }
}
