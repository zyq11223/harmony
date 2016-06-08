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
package edu.snu.cay.dolphin.bsp.core.optimizer;

import edu.snu.cay.dolphin.bsp.core.CtrlTaskContextIdFetcher;
import edu.snu.cay.services.em.optimizer.api.DataInfo;
import edu.snu.cay.services.em.optimizer.api.EvaluatorParameters;
import edu.snu.cay.services.em.optimizer.api.Optimizer;
import edu.snu.cay.services.em.optimizer.impl.DataInfoImpl;
import edu.snu.cay.services.em.plan.api.Plan;
import edu.snu.cay.services.em.plan.api.TransferStep;
import edu.snu.cay.services.em.plan.impl.PlanImpl;
import edu.snu.cay.services.em.plan.impl.TransferStepImpl;
import org.apache.reef.util.Optional;
import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

import javax.inject.Inject;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static edu.snu.cay.dolphin.bsp.core.optimizer.OptimizationOrchestrator.NAMESPACE_DOLPHIN_BSP;

/**
 * Optimizer class based on ILP (integer linear programming)
 * using <a href="http://ojalgo.org">ojAlgo</a>'s MIP(Mixed Integer Programming) solver.
 *
 * Assumes that the active evaluators consists of a single controller task and compute tasks
 * and the compute performance for each types of data is equivalent.
 * <p>
 *   Optimization is done by the following.<br>
 *   1. Calculating a communication cost and compute costs for each compute task. <br/>
 *   2. Creating ILP model and find an optimal solution for minimizing cost using ojAlgo's MIP Solver. <br/>
 *   3. Generating transfer steps by greedily moving data
 *      from the compute task having the largest amount of data to send
 *      to the one having the largest amount of data to receive without regarding to types of data.
 *      {@link #generatePlan}
 * </p>
 */
public final class ILPSolverOptimizer implements Optimizer {

  private static final Logger LOG = Logger.getLogger(ILPSolverOptimizer.class.getName());
  private static final String NEW_COMPUTE_TASK_ID_PREFIX = "newComputeTask-";
  private static final String TEMP_COMPUTE_TASK_ID_PREFIX = "tempComputeTask-";
  private final AtomicInteger newComputeTaskSequence = new AtomicInteger(0);
  private final DataUnitsToMoveComparator ascendingComparator;
  private final DataUnitsToMoveComparator descendingComparator;
  private final CtrlTaskContextIdFetcher ctrlTaskContextIdFetcher;

  @Inject
  private ILPSolverOptimizer(final CtrlTaskContextIdFetcher ctrlTaskContextIdFetcher) {
    this.ascendingComparator = new DataUnitsToMoveComparator();
    this.descendingComparator = new DataUnitsToMoveComparator(DataUnitsToMoveComparator.Order.DESCENDING);
    this.ctrlTaskContextIdFetcher = ctrlTaskContextIdFetcher;
  }


  @Override
  public Plan optimize(final Map<String, List<EvaluatorParameters>> evalParamsMap, final int availableEvaluators) {
    final Optional<String> ctrlTaskContextId = ctrlTaskContextIdFetcher.getCtrlTaskContextId();
    if (!ctrlTaskContextId.isPresent()) {
      LOG.log(Level.WARNING, "Controller task is unidentifiable at the moment. Returning empty plan.");
      return PlanImpl.newBuilder().build();
    }

    final List<EvaluatorParameters> activeEvaluators = evalParamsMap.get(NAMESPACE_DOLPHIN_BSP);

    final Optional<Cost> cost = CostCalculator.calculate(activeEvaluators, ctrlTaskContextId.get());
    if (!cost.isPresent()) {
      LOG.log(Level.WARNING, "No controller task present at the moment. Returning empty plan.");
      return PlanImpl.newBuilder().build();
    }

    final List<OptimizedComputeTask> optimizedComputeTasks =
        initOptimizedComputeTasks(cost.get(), availableEvaluators - activeEvaluators.size());

    // create ILP model for optimization
    // C_cmp: expected compute cost
    // C_comm': communication cost
    // C_comm: expected communication cost
    // #_active' : the number of active compute tasks
    // #_active : the expected number of active compute tasks
    // p(i): indicator for the participation of i-th compute task.

    // objective function = C_cmp + C_comm
    // s.t. C_comm = (C_comm' / #_active') * #_active = (C_comm' / #_active') * sum(p(i))
    final ExpressionsBasedModel model = new ExpressionsBasedModel();
    final Variable cmpCostVar = Variable.make("computeCost");
    final double commCostWeight = cost.get().getCommunicationCost() / cost.get().getComputeTaskCosts().size();

    model.addVariable(cmpCostVar.weight(1.0));

    for (final OptimizedComputeTask cmpTask : optimizedComputeTasks) {
      model.addVariable(cmpTask.getParticipateVariable().weight(commCostWeight));
      model.addVariable(cmpTask.getRequestedDataVariable()); // without weight for being excluded in objective function.
    }

    addExpressions(model, cmpCostVar, optimizedComputeTasks, availableEvaluators - 1); // -1 for excluding the ctrl task
    final int totalDataUnits = getSumDataUnits(optimizedComputeTasks);

    final Optimisation.Result result = model.minimise();
    LOG.log(Level.FINEST, "ILPSolverOptimizer Optimization Result: {0}", result);

    makeDataUnitsInteger(optimizedComputeTasks, totalDataUnits);

    final Plan plan = generatePlan(optimizedComputeTasks);
    LOG.log(Level.FINE, "ILPSolverOptimizer Plan: {0}", plan);
    return plan;
  }

  /**
   * Make data units for each compute tasks that participating the execution integer
   * while preserving total number of data units.
   * @param optimizedComputeTasks a list of optimized compute tasks
   * @param totalDataUnits a total number of data units
   */
  private static void makeDataUnitsInteger(final List<OptimizedComputeTask> optimizedComputeTasks,
                                           final int totalDataUnits) {
    int sum = 0;
    for (final OptimizedComputeTask cmpTask : optimizedComputeTasks) {
      if (!cmpTask.getParticipateValue()) {
        continue;
      }
      cmpTask.getRequestedDataVariable().setValue(
          BigDecimal.valueOf(Math.floor(cmpTask.getRequestedDataVariable().getValue().doubleValue())));
      sum += cmpTask.getRequestedDataValue();
    }
    for (final OptimizedComputeTask cmpTask : optimizedComputeTasks) {
      if (cmpTask.getParticipateValue()) {
        // (totalDataUnits - sum) must be >= 0 due to the above floor operation.
        cmpTask.getRequestedDataVariable().setValue(
            BigDecimal.valueOf(cmpTask.getRequestedDataValue() + (totalDataUnits - sum)));
        return;
      }
    }
  }

  /**
   * Initializes a {@link OptimizedComputeTask} per active compute task from the calculated cost
   * and adds {@link OptimizedComputeTask}s for available but unused evaluators.
   * An {@link OptimizedComputeTask} keeps track of data at a compute task while optimizing and scheduling transfers.
   * @param cost the calculated cost for Dolphin.
   * @param unusedEvaluators the number of unused evaluators.
   * @return a list of {@link OptimizedComputeTask}s
   */
  private static List<OptimizedComputeTask> initOptimizedComputeTasks(final Cost cost,
                                                                      final int unusedEvaluators) {
    final List<OptimizedComputeTask> ret = new ArrayList<>(cost.getComputeTaskCosts().size() + unusedEvaluators);

    // create variables for active compute tasks
    for (final Cost.ComputeTaskCost cmpTaskCost : cost.getComputeTaskCosts()) {
      // add variables for data
      final Variable dataVar = getNewDataVariable(cmpTaskCost.getId(), false);

      // create variable for the compute task's participation to the model
      final Variable participateVar = getNewParticipateVariable(cmpTaskCost.getId(), false);

      ret.add(new OptimizedComputeTask(
          cmpTaskCost.getId(), participateVar, dataVar, cmpTaskCost.getDataInfos(), cmpTaskCost.getComputeCost()));
    }

    // create variables for unused evaluators
    for (int i = 0; i < unusedEvaluators; ++i) {
      final String id = TEMP_COMPUTE_TASK_ID_PREFIX + i;
      final Variable participateVar = getNewParticipateVariable(id, true);
      final Variable dataVar = getNewDataVariable(id, true);
      ret.add(new OptimizedComputeTask(id, participateVar, dataVar));
    }

    return ret;
  }

  /**
   * @param id the evaluator id
   * @return the variable for data that the specified evaluator has.
   */
  private static Variable getNewDataVariable(final String id, final boolean isNew) {
    return Variable.make(String.format("data-%s-%s", isNew ? "new" : "active", id)).lower(0);
  }

  /**
   * @param id the evaluator id
   * @return the variable for the participation of the specified evaluator
   */
  private static Variable getNewParticipateVariable(final String id, final boolean isNew) {
    return Variable.makeBinary(String.format("participate-%s-%s", isNew ? "new" : "active", id));
  }

  /**
   * Adds constraint expressions to the ILP model for optimization.
   * @param model the ILP model that constraints are added to
   * @param computeCostVariable the variable for expected compute cost
   * @param optimizedComputeTasks a list of {@link OptimizedComputeTask}s
   * @param availableComputeTasks the number of available compute tasks
   */
  private static void addExpressions(final ExpressionsBasedModel model,
                                     final Variable computeCostVariable,
                                     final List<OptimizedComputeTask> optimizedComputeTasks,
                                     final int availableComputeTasks) {
    // C_cmp'(i): compute cost for i-th compute task.
    // C_cmp(i): expected compute cost for i-th compute task.
    // d'(i): allocated data units for i-th compute task.
    // d(i): requested data units for i-th compute task.
    // p(i): indicator for the participation of i-th compute task.
    //       if p(i) = 1, i-th compute task participates next iteration.

    final int totalDataUnits = getSumDataUnits(optimizedComputeTasks);
    final double predictedComputePerformance = predictComputePerformance(optimizedComputeTasks);

    // [sum of data units] = sum(d(i))
    final Expression totalDataExpression = model.addExpression("totalDataExpression").level(totalDataUnits);
    // sum(p(i)) <= [# of available evaluators]
    final Expression totalComputeTasksExpression =
        model.addExpression("totalComputeTasksExpression").upper(availableComputeTasks);

    for (final OptimizedComputeTask cmpTask : optimizedComputeTasks) {
      totalDataExpression.setLinearFactor(cmpTask.getRequestedDataVariable(), 1);
      totalComputeTasksExpression.setLinearFactor(cmpTask.getParticipateVariable(), 1);

      // d(i) <= p(i) * [sum of data units]
      final Expression dataExpression =
          model.addExpression("dataExpression-" + cmpTask.getId()).lower(0);
      dataExpression.setLinearFactor(cmpTask.getParticipateVariable(), totalDataUnits);
      dataExpression.setLinearFactor(cmpTask.getRequestedDataVariable(), -1);

      // C_cmp = max(C_cmp(i)) i.e. for all i, C_cmp >= C_cmp(i) = C_cmp'(i) / d'(i) * d(i)
      final Expression computeCostExpression =
          model.addExpression("computeCostExpression-" + cmpTask.getId()).upper(0);
      final int sumDataUnits = getSumDataUnits(cmpTask.getAllocatedDataInfos());
      final double computePerformance =
          (sumDataUnits > 0) ? cmpTask.getComputeCost() / sumDataUnits : predictedComputePerformance;

      computeCostExpression.setLinearFactor(cmpTask.getRequestedDataVariable(), computePerformance);
      computeCostExpression.setLinearFactor(computeCostVariable, -1);
    }
  }

  /**
   * @param optimizedComputeTasks a list of {@link OptimizedComputeTask}s.
   * @return the sum of data units that each compute task has without regard to types of data
   */
  private static int getSumDataUnits(final List<OptimizedComputeTask> optimizedComputeTasks) {
    int sum = 0;
    for (final OptimizedComputeTask cmpTask : optimizedComputeTasks) {
      sum += getSumDataUnits(cmpTask.getAllocatedDataInfos());
    }
    return sum;
  }

  /**
   * @param dataInfos a collection of {@link DataInfo}.
   * @return the sum of data units regardless of types of data
   */
  private static int getSumDataUnits(final Collection<DataInfo> dataInfos) {
    int sum = 0;
    for (final DataInfo dataInfo : dataInfos) {
      sum += dataInfo.getNumUnits();
    }
    return sum;
  }

  /**
   * Generates an optimizer plan with a list of optimized compute tasks.
   * The generated plan transfers data from the compute task with the largest amount of data to send
   * to the compute task with the largest amount of data to receive without regard to types of data.
   * @param optimizedComputeTasks a list of optimized compute tasks
   * @return the generated plan
   */
  private Plan generatePlan(final List<OptimizedComputeTask> optimizedComputeTasks) {
    final PlanImpl.Builder builder = PlanImpl.newBuilder();
    final PriorityQueue<OptimizedComputeTask> senderPriorityQueue =
        new PriorityQueue<>(optimizedComputeTasks.size(), ascendingComparator);
    final PriorityQueue<OptimizedComputeTask> receiverPriorityQueue =
        new PriorityQueue<>(optimizedComputeTasks.size(), descendingComparator);

    for (final OptimizedComputeTask cmpTask : optimizedComputeTasks) {
      final int dataUnitToMove = getDataUnitsToMove(cmpTask);
      if (dataUnitToMove < 0) {
        senderPriorityQueue.add(cmpTask);
      } else if (dataUnitToMove > 0) {
        receiverPriorityQueue.add(cmpTask);
      } else {
        continue;
      }

      if (cmpTask.getParticipateValue() && cmpTask.isNewComputeTask()) {
        cmpTask.setId(getNewComputeTaskId()); // replace temporary id with new permanent one.
        builder.addEvaluatorToAdd(NAMESPACE_DOLPHIN_BSP, cmpTask.getId());
      } else if (!cmpTask.getParticipateValue() && !cmpTask.isNewComputeTask()) {
        builder.addEvaluatorToDelete(NAMESPACE_DOLPHIN_BSP, cmpTask.getId());
      }
    }

    while (senderPriorityQueue.size() > 0) {
      // pick sender as a compute task that has the biggest amount of data units to send
      final OptimizedComputeTask sender = senderPriorityQueue.poll();
      while (getDataUnitsToMove(sender) < 0) {
        // pick receiver as a compute task that has the biggest amount of data unit to receive.
        final OptimizedComputeTask receiver = receiverPriorityQueue.poll();
        builder.addTransferSteps(NAMESPACE_DOLPHIN_BSP, generateTransferStep(sender, receiver));
        if (getDataUnitsToMove(receiver) > 0) {
          receiverPriorityQueue.add(receiver);
          break;
        }
      }
    }
    return builder.build();
  }

  /**
   * Returns the number of data units to move.
   * If a return value is positive, the specified compute task should receive data from others.
   * If a return value is negative, the compute task should send data to others.
   * @param computeTask
   * @return the number of data units to move
   */
  private static int getDataUnitsToMove(final OptimizedComputeTask computeTask) {
    return computeTask.getRequestedDataValue() - getSumDataUnits(computeTask.getAllocatedDataInfos());
  }

  /**
   * Generates {@link TransferStep} from one compute task to another compute task.
   * @param sender the compute task that sends data.
   * @param receiver the compute task that receives data.
   * @return the generated {@link TransferStep}.
   */
  private List<TransferStep> generateTransferStep(final OptimizedComputeTask sender,
                                                  final OptimizedComputeTask receiver) {
    final List<TransferStep> ret = new ArrayList<>();
    int numToSend = getDataUnitsToMove(sender);
    if (numToSend >= 0) {
      throw new IllegalArgumentException("The number of data units to send must be < 0");
    }
    int numToReceive = getDataUnitsToMove(receiver);

    final List<DataInfo> dataInfosToRemove = new ArrayList<>();
    final List<DataInfo> dataInfosToAdd = new ArrayList<>(1);
    final Iterator<DataInfo> dataInfoIterator = sender.getAllocatedDataInfos().iterator();

    while (numToSend < 0 && numToReceive > 0 && dataInfoIterator.hasNext()) {
      final DataInfo dataInfo = dataInfoIterator.next();
      final int numToMove = Math.min(-numToSend, numToReceive);
      final DataInfo dataInfoToMove;

      if (numToMove < dataInfo.getNumUnits()) {
        dataInfoToMove = new DataInfoImpl(dataInfo.getDataType(), numToMove);
        dataInfosToAdd.add(new DataInfoImpl(dataInfo.getDataType(), dataInfo.getNumUnits() - numToMove));
      } else {
        dataInfoToMove = dataInfo;
      }

      numToSend += dataInfoToMove.getNumUnits();
      numToReceive -= dataInfoToMove.getNumUnits();

      dataInfosToRemove.add(dataInfo);
      receiver.getAllocatedDataInfos().add(dataInfoToMove);
      ret.add(new TransferStepImpl(sender.getId(), receiver.getId(), dataInfoToMove));
    }

    sender.getAllocatedDataInfos().removeAll(dataInfosToRemove);
    sender.getAllocatedDataInfos().addAll(dataInfosToAdd);

    return ret;
  }

  /**
   * Predicts computing performance of new compute tasks by averaging computing performances of others.
   * @param optimizedComputeTasks a list of {@link OptimizedComputeTask}s.
   * @return the predicted computing performance.
   */
  private static double predictComputePerformance(final List<OptimizedComputeTask> optimizedComputeTasks) {
    double sum = 0D;
    int count = 0;
    for (final OptimizedComputeTask cmpTask : optimizedComputeTasks) {
      final int sumDataUnits = getSumDataUnits(cmpTask.getAllocatedDataInfos());
      if (sumDataUnits > 0) {
        sum += cmpTask.getComputeCost() / sumDataUnits;
        ++count;
      }
    }
    return sum / count;
  }

  /**
   * @return the id for a new compute task.
   */
  private String getNewComputeTaskId() {
    return NEW_COMPUTE_TASK_ID_PREFIX + newComputeTaskSequence.getAndIncrement();
  }

  private static class OptimizedComputeTask {
    private String id;
    private final Variable participateVariable;
    private final Variable requestedDataVariable;
    private final Collection<DataInfo> allocatedDataInfos;
    private final double computeCost;
    private final boolean newComputeTask;

    OptimizedComputeTask(final String id,
                         final Variable participateVariable,
                         final Variable requestedDataVariable) {
      this.id = id;
      this.participateVariable = participateVariable;
      this.requestedDataVariable = requestedDataVariable;
      this.allocatedDataInfos = new ArrayList<>(0);
      this.computeCost = 0D;
      this.newComputeTask = true;
    }

    OptimizedComputeTask(final String id,
                         final Variable participateVariable,
                         final Variable requestedDataVariable,
                         final Collection<DataInfo> dataInfos,
                         final double computeCost) {
      this.id = id;
      this.participateVariable = participateVariable;
      this.requestedDataVariable = requestedDataVariable;
      this.allocatedDataInfos = new ArrayList<>(dataInfos);
      this.computeCost = computeCost;
      this.newComputeTask = false;
    }

    public boolean isNewComputeTask() {
      return newComputeTask;
    }

    public String getId() {
      return id;
    }

    public void setId(final String newId) {
      id = newId;
    }

    public Variable getParticipateVariable() {
      return participateVariable;
    }

    public boolean getParticipateValue() {
      return participateVariable.getValue().intValueExact() == 1;
    }

    public Variable getRequestedDataVariable() {
      return requestedDataVariable;
    }

    public int getRequestedDataValue() {
      return requestedDataVariable.getValue().intValueExact();
    }

    public Collection<DataInfo> getAllocatedDataInfos() {
      return allocatedDataInfos;
    }

    public double getComputeCost() {
      return computeCost;
    }
  }

  /**
   * Comparator class that compares two {@link OptimizedComputeTask}
   * based on the number of data units for each compute task to move.
   */
  private static class DataUnitsToMoveComparator implements Comparator<OptimizedComputeTask> {

    public enum Order { ASCENDING, DESCENDING }

    private final Order order;

    public DataUnitsToMoveComparator() {
      this.order = Order.ASCENDING;
    }
    public DataUnitsToMoveComparator(final Order order) {
      this.order = order;
    }

    @Override
    public int compare(final OptimizedComputeTask o1, final OptimizedComputeTask o2) {
      final int o1DataDiff = getDataUnitsToMove(o1);
      final int o2DataDiff = getDataUnitsToMove(o2);
      final int ret = Integer.compare(o1DataDiff, o2DataDiff);
      if (order == Order.DESCENDING) {
        return -1 * ret;
      } else {
        return ret;
      }
    }
  }
}