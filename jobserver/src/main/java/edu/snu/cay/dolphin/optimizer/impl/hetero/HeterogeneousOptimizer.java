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
package edu.snu.cay.dolphin.optimizer.impl.hetero;

import edu.snu.cay.common.param.Parameters;
import edu.snu.cay.dolphin.optimizer.api.EvaluatorParameters;
import edu.snu.cay.dolphin.optimizer.api.Optimizer;
import edu.snu.cay.dolphin.optimizer.impl.*;
import edu.snu.cay.dolphin.optimizer.parameters.Constants;
import edu.snu.cay.dolphin.plan.api.Plan;
import edu.snu.cay.dolphin.plan.api.TransferStep;
import edu.snu.cay.dolphin.plan.impl.EmptyPlan;
import edu.snu.cay.dolphin.plan.impl.ILPPlanDescriptor;
import edu.snu.cay.dolphin.plan.impl.PlanImpl;
import gurobi.GRBException;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;
import java.util.*;

/**
 * This optimizer minimizes epoch(mini-batch) time,
 * by allocating proper role (e.g., server/worker) and amount of workload (number of blocks) to resources.
 * Different from {@link HomogeneousOptimizer}, this optimizer considers heterogeneity of resources.
 *
 * It solves Integer Linear Programming problem to find the optimal configuration (See {@link ILPSolver}).
 *
 * This optimizer
 * 1) does not support change of total amount of resources.
 * 2) generate a plan, being aware of switch operation.
 */
public final class HeterogeneousOptimizer implements Optimizer {
  /**
   * To indicate that the evaluator's cWProc is unknown because the evaluator's role was a server, not a worker.
   */
  private static final double UNKNOWN_CWPROC = -1.0;
  private static final int NUM_EMPTY_BLOCK = 0;
  private static final double EMA_ALPHA = 0.9;
  
  private final double defNetworkBandwidth;
  private final int defCoreNum;
  private final Map<String, Double> hostToBandwidth;
  private final Map<String, Integer> hostToCoreNum;
  private final ILPSolver ilpSolver;
  private final Map<String, Double> hostToCWProc;

  @Inject
  private HeterogeneousOptimizer(@Parameter(Parameters.DefaultNetworkBandwidth.class) final double defNetworkBandwidth,
                                 @Parameter(Parameters.DefaultCoreNum.class) final int defCoreNum,
                                 final ILPSolver ilpSolver,
                                 final BandwidthInfoParser bandwitdthInfoParser,
                                 final CoreInfoParser coreInfoParser) {
    this.defNetworkBandwidth = defNetworkBandwidth;
    this.defCoreNum = defCoreNum;
    this.hostToBandwidth = bandwitdthInfoParser.parseBandwidthInfo();
    this.hostToCoreNum = coreInfoParser.parseCoreInfo();
    this.ilpSolver = ilpSolver;
    this.hostToCWProc = new HashMap<>();
  }

  @Override
  public Plan optimize(final Map<String, List<EvaluatorParameters>> evalParamsMap,
                       final int availableEvaluators,
                       final Map<String, Double> optimizerModelParamsMap) {
    final int numTotalDataBlocks = (int) Math.round(optimizerModelParamsMap.get(Constants.NUM_DATA_BLOCKS));
    final int numTotalModelBlocks = (int) Math.round(optimizerModelParamsMap.get(Constants.NUM_MODEL_BLOCKS));
    final List<EvaluatorParameters> serverParams = evalParamsMap.get(Constants.NAMESPACE_SERVER);
    final List<MachineDescriptor> serverDescriptors = processServerParameters(serverParams);

    final List<EvaluatorParameters> workerParams = evalParamsMap.get(Constants.NAMESPACE_WORKER);
    final List<MachineDescriptor> workerDescriptors = processWorkerParameters(workerParams);

    assert availableEvaluators ==
        evalParamsMap.get(Constants.NAMESPACE_SERVER).size() + evalParamsMap.get(Constants.NAMESPACE_WORKER).size();
    // We don't consider dynamic availability for now.

    final int p = (int) Math.round(optimizerModelParamsMap.get(Constants.AVG_PULL_SIZE_PER_MINI_BATCH));

    final int n = evalParamsMap.get(Constants.NAMESPACE_SERVER).size()
        + evalParamsMap.get(Constants.NAMESPACE_WORKER).size();
    final int[] dOld = new int[n];
    final int[] mOld = new int[n];
    final int[] roleOld = new int[n];
    final double[] cWProc = new double[n];
    final double[] bandwidth = new double[n];
    final String[] evalIds = new String[n];

    /*
     * We predict CWProc(computation time to process one data block with one machine using all the cores in it) value
     * for CWProc-unknown machines for the following steps:
     * 1. Assume all the cores' computation power are the same each other and it takes T times to process one data block
     *    with one core.
     * 2. Assume each machine has n[i] number of cores and measured computation time of each machine is CWProc[i].
     * 3. Then, for each machine,  the following equation holds: T / n[i] = CWProc[i].
     * 4. Add LHS and RHS of the upper equation for all the machines, and compute T. Then, T can be expressed as
     *    the follow: T = Sum(CWProc[i]) / Sum(1 / n[i])
     * 5. With the computed T value, now we can predict CWProc for CWProc-unknown machine with m cores.
     *    CWProc = T / m
     */
    double sumCWProc = 0.0;
    double totalHarmonicCoreSum = 0.0;
    for (final MachineDescriptor workerDescriptor : workerDescriptors) {
      sumCWProc += workerDescriptor.getcWProc();
      totalHarmonicCoreSum += 1.0 / hostToCoreNum.getOrDefault(workerDescriptor.getHostName(), defCoreNum);
    }
    final double cWProcWithOneCore = sumCWProc / totalHarmonicCoreSum;

    int idx = 0;
    for (final MachineDescriptor serverDescriptor : serverDescriptors) {
      roleOld[idx] = EvaluatorRole.SERVER.getValue();
      dOld[idx] = serverDescriptor.getNumTrainingDataBlocks();
      mOld[idx] = serverDescriptor.getNumModelBlocks();
      bandwidth[idx] = serverDescriptor.getBandwidth();
      evalIds[idx] = serverDescriptor.getId();
      cWProc[idx] = hostToCWProc.containsKey(serverDescriptor.getHostName()) ?
          hostToCWProc.get(serverDescriptor.getHostName()) :
          cWProcWithOneCore / (double) hostToCoreNum.getOrDefault(serverDescriptor.getHostName(), defCoreNum);
      idx++;
    }

    for (final MachineDescriptor workerDescriptor : workerDescriptors) {
      roleOld[idx] = EvaluatorRole.WORKER.getValue();
      dOld[idx] = workerDescriptor.getNumTrainingDataBlocks();
      mOld[idx] = workerDescriptor.getNumModelBlocks();
      bandwidth[idx] = workerDescriptor.getBandwidth();
      evalIds[idx] = workerDescriptor.getId();
      cWProc[idx] = workerDescriptor.getcWProc();
      idx++;
    }

    try {
      final ConfDescriptor optConfDescriptor =
          ilpSolver.optimize(n, numTotalDataBlocks, numTotalModelBlocks, p, cWProc, bandwidth);

      if (optConfDescriptor == null) {
        return new EmptyPlan(OptimizerType.HETEROGENEOUS);
      }

      final PlanImpl.Builder planBuilder = PlanImpl.newBuilder();
      
      planBuilder.setOptimizerType(OptimizerType.HETEROGENEOUS);

      // Generate a reconfiguration plan by comparing old configuration and new configuration obtained from ILPSolver.
      final ILPPlanDescriptor planDescriptor = ILPPlanGenerator.generatePlanDescriptor(evalIds, roleOld, dOld, mOld,
          optConfDescriptor.getRole(), optConfDescriptor.getD(), optConfDescriptor.getM());
      final List<String> workerEvalIdxsToAdd = planDescriptor.getEvaluatorsToAdd(Constants.NAMESPACE_WORKER);
      final List<String> serverEvalIdxsToAdd = planDescriptor.getEvaluatorsToAdd(Constants.NAMESPACE_SERVER);
      planBuilder.addEvaluatorsToAdd(Constants.NAMESPACE_WORKER, workerEvalIdxsToAdd);
      planBuilder.addEvaluatorsToAdd(Constants.NAMESPACE_SERVER, serverEvalIdxsToAdd);

      final List<String> workerEvalIdxsToDelete = planDescriptor.getEvaluatorsToDelete(Constants.NAMESPACE_WORKER);
      final List<String> serverEvalIdxsToDelete = planDescriptor.getEvaluatorsToDelete(Constants.NAMESPACE_SERVER);
      planBuilder.addEvaluatorsToDelete(Constants.NAMESPACE_WORKER, workerEvalIdxsToDelete);
      planBuilder.addEvaluatorsToDelete(Constants.NAMESPACE_SERVER, serverEvalIdxsToDelete);

      final List<TransferStep> workerTransferSteps = planDescriptor.getTransferSteps(Constants.NAMESPACE_WORKER);
      final List<TransferStep> serverTransferSteps = planDescriptor.getTransferSteps(Constants.NAMESPACE_SERVER);
      planBuilder.addTransferSteps(Constants.NAMESPACE_WORKER, workerTransferSteps);
      planBuilder.addTransferSteps(Constants.NAMESPACE_SERVER, serverTransferSteps);

      return planBuilder.build();

    } catch (final GRBException e) {
      throw new RuntimeException(e);
    }
  }

  private List<MachineDescriptor> processWorkerParameters(final List<EvaluatorParameters> evalParamsList) {
    final List<MachineDescriptor> machineDescriptors = new ArrayList<>(evalParamsList.size());
    for (final EvaluatorParameters evalParams : evalParamsList) {
      final WorkerEvaluatorParameters workerEvalParams = (WorkerEvaluatorParameters) evalParams;
      final String id = workerEvalParams.getId();
      final int numDataBlocks = workerEvalParams.getDataInfo().getNumBlocks();
      final String hostname = workerEvalParams.getMetrics().getHostname().toString();
      final double bandwidth = hostToBandwidth.getOrDefault(hostname, defNetworkBandwidth) / 8D;
      // EMA is used to prevent fluctuation of cWProc value.
      final double presentCWProc = workerEvalParams.getMetrics().getTotalCompTime();
      final double cWProc = hostToCWProc.containsKey(hostname) ?
          presentCWProc * EMA_ALPHA + hostToCWProc.get(hostname) * (1.0 - EMA_ALPHA) : presentCWProc;
      hostToCWProc.put(hostname, cWProc);
      machineDescriptors.add(
          new MachineDescriptor(id, bandwidth, numDataBlocks, cWProc, NUM_EMPTY_BLOCK, hostname));
    }
    return machineDescriptors;
  }

  private List<MachineDescriptor> processServerParameters(final List<EvaluatorParameters> evalParamsList) {
    final List<MachineDescriptor> machineDescriptors = new ArrayList<>(evalParamsList.size());
    for (final EvaluatorParameters evalParams : evalParamsList) {
      final ServerEvaluatorParameters serverEvalParams = (ServerEvaluatorParameters) evalParams;
      final String id = serverEvalParams.getId();
      final int numModelBlocks = serverEvalParams.getDataInfo().getNumBlocks();
      final String hostname = serverEvalParams.getMetrics().getHostname().toString();
      final double bandwidth = hostToBandwidth.getOrDefault(hostname, defNetworkBandwidth) / 8D;
      machineDescriptors.add(
          new MachineDescriptor(id, bandwidth, NUM_EMPTY_BLOCK, UNKNOWN_CWPROC, numModelBlocks, hostname));
    }
    return machineDescriptors;
  }

  class MachineDescriptor {
    private String id;
    private double bandwidth;
    private double cWProc;

    private int numTrainingDataBlocks; // d
    private int numModelBlocks; // m
    
    private String hostname;

    MachineDescriptor(final String id,
                      final double bandwidth,
                      final int numTrainingDataBlocks,
                      final double cWProc,
                      final int numModelDataBlocks,
                      final String hostname) {
      this.id = id;
      this.bandwidth = bandwidth;
      this.cWProc = cWProc;
      this.numTrainingDataBlocks = numTrainingDataBlocks;
      this.numModelBlocks = numModelDataBlocks;
      this.hostname = hostname;
    }

    String getId() {
      return id;
    }

    double getBandwidth() {
      return bandwidth;
    }

    double getcWProc() {
      return cWProc;
    }

    int getNumTrainingDataBlocks() {
      return numTrainingDataBlocks;
    }

    int getNumModelBlocks() {
      return numModelBlocks;
    }
    
    String getHostName() {
      return hostname;
    }
  }
}

enum EvaluatorRole {
  WORKER(1), SERVER(0);

  private final int value;

  EvaluatorRole(final int value) {
    this.value = value;
  }

  int getValue() {
    return value;
  }
}
