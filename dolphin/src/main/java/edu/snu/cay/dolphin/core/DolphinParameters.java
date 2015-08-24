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
package edu.snu.cay.dolphin.core;

import edu.snu.cay.dolphin.parameters.*;
import edu.snu.cay.dolphin.scheduling.LocalSchedulabilityAnalyzer;
import edu.snu.cay.dolphin.scheduling.SchedulabilityAnalyzer;
import edu.snu.cay.dolphin.scheduling.YarnSchedulabilityAnalyzer;
import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.Configurations;
import org.apache.reef.tang.Tang;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;

public final class DolphinParameters {
  private final String identifier;
  private final UserJobInfo userJobInfo;
  private final UserParameters userParameters;
  private final int desiredSplits;
  private final int evalSize;
  private final String inputDir;
  private final String outputDir;
  private final boolean onLocal;
  private final int localRuntimeMaxNumEvaluators;
  private final int timeout;

  @Inject
  private DolphinParameters(@Parameter(JobIdentifier.class) final String identifier,
                            final UserJobInfo userJobInfo,
                            final UserParameters userParameters,
                            @Parameter(DesiredSplits.class) final int desiredSplits,
                            @Parameter(EvaluatorSize.class) final int evalSize,
                            @Parameter(InputDir.class) final String inputDir,
                            @Parameter(OutputDir.class) final String outputDir,
                            @Parameter(OnLocal.class) final boolean onLocal,
                            @Parameter(LocalRuntimeMaxNumEvaluators.class) final int localRuntimeMaxNumEvaluators,
                            @Parameter(Timeout.class) final int timeout) {
    this.identifier = identifier;
    this.userJobInfo = userJobInfo;
    this.userParameters = userParameters;
    this.desiredSplits = desiredSplits;
    this.evalSize = evalSize;
    this.inputDir = inputDir;
    this.outputDir = outputDir;
    this.onLocal = onLocal;
    this.localRuntimeMaxNumEvaluators = localRuntimeMaxNumEvaluators;
    this.timeout = timeout;
  }

  /**
   * Return a configuration for the driver.
   * @return
   */
  public Configuration getDriverConf() {
    final Configuration driverConf = Tang.Factory.getTang().newConfigurationBuilder()
        .bindNamedParameter(DesiredSplits.class, String.valueOf(desiredSplits))
        .bindNamedParameter(OnLocal.class, String.valueOf(onLocal))
        .bindImplementation(UserJobInfo.class, userJobInfo.getClass())
        .bindImplementation(UserParameters.class, userParameters.getClass())
        .build();
    final Configuration schedulingConf = onLocal ? getLocalSchedulingConf() : getYarnSchedulingConf();
    return Configurations.merge(userParameters.getDriverConf(), driverConf, schedulingConf);
  }

  private Configuration getLocalSchedulingConf() {
    return Tang.Factory.getTang().newConfigurationBuilder()
        .bindImplementation(SchedulabilityAnalyzer.class, LocalSchedulabilityAnalyzer.class)
        .bindNamedParameter(LocalRuntimeMaxNumEvaluators.class, String.valueOf(localRuntimeMaxNumEvaluators))
        .build();
  }

  private Configuration getYarnSchedulingConf() {
    return Tang.Factory.getTang().newConfigurationBuilder()
        .bindImplementation(SchedulabilityAnalyzer.class, YarnSchedulabilityAnalyzer.class)
        .build();
  }

  public String getIdentifier() {
    return identifier;
  }

  public int getDesiredSplits() {
    return desiredSplits;
  }

  public int getEvalSize() {
    return evalSize;
  }

  public String getInputDir() {
    return inputDir;
  }

  public String getOutputDir() {
    return outputDir;
  }

  public boolean getOnLocal() {
    return onLocal;
  }

  public int getLocalRuntimeMaxNumEvaluators() {
    return localRuntimeMaxNumEvaluators;
  }

  public int getTimeout() {
    return timeout;
  }
}
