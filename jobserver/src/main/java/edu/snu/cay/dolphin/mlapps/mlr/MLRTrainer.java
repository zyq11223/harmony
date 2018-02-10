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
package edu.snu.cay.dolphin.mlapps.mlr;

import edu.snu.cay.common.math.linalg.Vector;
import edu.snu.cay.common.math.linalg.VectorFactory;
import edu.snu.cay.common.param.Parameters;
import edu.snu.cay.dolphin.DolphinParameters;
import edu.snu.cay.dolphin.core.worker.ModelAccessor;
import edu.snu.cay.dolphin.core.worker.ModelHolder;
import edu.snu.cay.dolphin.core.worker.Trainer;
import edu.snu.cay.services.et.evaluator.api.Table;
import edu.snu.cay.utils.CatchableExecutors;
import edu.snu.cay.utils.MemoryUtils;
import edu.snu.cay.utils.ThreadUtils;
import edu.snu.cay.utils.Tuple3;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.reef.tang.annotations.Parameter;
import edu.snu.cay.dolphin.DolphinParameters.*;
import org.hyperic.sigar.Cpu;
import org.hyperic.sigar.CpuInfo;
import org.hyperic.sigar.Sigar;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static edu.snu.cay.dolphin.mlapps.mlr.MLRParameters.*;

/**
 * {@link Trainer} class for the MLRREEF application.
 * Uses {@code numClasses} model vectors to determine which class each data instance belongs to.
 * The model vector that outputs the highest dot product value is declared as that data instance's prediction.
 */
final class MLRTrainer implements Trainer<Long, MLRData> {
  private static final Logger LOG = Logger.getLogger(MLRTrainer.class.getName());
  private static final float ZERO_THRESHOLD = 1e-7f;

  private final ModelAccessor<Integer, Vector, Vector> modelAccessor;

  /**
   * Number of possible classes for a data instance.
   */
  private final int numClasses;

  /**
   * Number of features for a data instance.
   */
  private final int numFeatures;

  /**
   * Number of features of each model partition.
   */
  private final int numFeaturesPerPartition;

  /**
   * Number of model partitions for each class.
   */
  private final int numPartitionsPerClass;

  /**
   * Size of each step taken during gradient descent.
   */
  private float stepSize;

  /**
   * L2 regularization constant.
   */
  private final float lambda;

  /**
   * Object for creating {@link Vector} instances.
   */
  private final VectorFactory vectorFactory;

  /**
   * Preserves the model parameters that are pulled from server, in order to compute gradients.
   */
  private final MLRModel model;

  /**
   * A list from 0 to {@code numClasses * numPartitionsPerClass} that will be used during {@link #pullModels()}.
   */
  private List<Integer> classPartitionIndices;

  /**
   * The step size drops by this rate.
   */
  private final float decayRate;

  /**
   * The step size drops after every {@code decayPeriod} epochs pass.
   */
  private final int decayPeriod;

  /**
   * Executes the Trainer threads.
   */
  private final ExecutorService executor;

  /**
   * Number of Trainer threads that train concurrently.
   */
  private final int numTrainerThreads;

  @Inject
  private MLRTrainer(final ModelAccessor<Integer, Vector, Vector> modelAccessor,
                     @Parameter(NumClasses.class) final int numClasses,
                     @Parameter(NumFeatures.class) final int numFeatures,
                     @Parameter(NumFeaturesPerPartition.class) final int numFeaturesPerPartition,
                     @Parameter(InitialStepSize.class) final float initStepSize,
                     @Parameter(Lambda.class) final float lambda,
                     @Parameter(DecayRate.class) final float decayRate,
                     @Parameter(DecayPeriod.class) final int decayPeriod,
                     @Parameter(NumTrainerThreads.class) final int numTrainerThreads,
                     @Parameter(Parameters.HyperThreadEnabled.class) final boolean hyperThreadEnabled,
                     @Parameter(DolphinParameters.NumTotalMiniBatches.class) final int numTotalMiniBatches,
                     final VectorFactory vectorFactory) {
    this.modelAccessor = modelAccessor;
    this.numClasses = numClasses;
    this.numFeatures = numFeatures;
    this.numFeaturesPerPartition = numFeaturesPerPartition;
    if (numFeatures % numFeaturesPerPartition != 0) {
      throw new RuntimeException("Uneven model partitions");
    }
    this.numPartitionsPerClass = numFeatures / numFeaturesPerPartition;
    this.stepSize = initStepSize;
    this.lambda = lambda;
    this.vectorFactory = vectorFactory;
    this.model = new MLRModel(new Vector[numClasses]);

    this.decayRate = decayRate;
    if (decayRate <= 0.0 || decayRate > 1.0) {
      throw new IllegalArgumentException("decay_rate must be larger than 0 and less than or equal to 1");
    }
    this.decayPeriod = decayPeriod;
    if (decayPeriod <= 0) {
      throw new IllegalArgumentException("decay_period must be a positive value");
    }

    // Use the half of the processors if hyper-thread is on, since using virtual cores do not help for float-point ops.
    this.numTrainerThreads = numTrainerThreads == Integer.parseInt(DolphinParameters.NumTrainerThreads.UNSET_VALUE) ?
        Runtime.getRuntime().availableProcessors() / (hyperThreadEnabled ? 2 : 1) :
        numTrainerThreads;
    this.executor = CatchableExecutors.newFixedThreadPool(this.numTrainerThreads);

    this.classPartitionIndices = new ArrayList<>(numClasses * numPartitionsPerClass);
    for (int classIndex = 0; classIndex < numClasses; ++classIndex) {
      for (int partitionIndex = 0; partitionIndex < numPartitionsPerClass; ++partitionIndex) {
        // 0 ~ (numPartitionsPerClass - 1) is for class 0
        // numPartitionsPerClass ~ (2 * numPartitionsPerClass - 1) is for class 1
        // and so on
        classPartitionIndices.add(classIndex * numPartitionsPerClass + partitionIndex);
      }
    }

    this.keyToGradientMap = new HashMap<>(numClasses * numPartitionsPerClass);

    LOG.log(Level.INFO, "Number of Trainer threads = {0}", this.numTrainerThreads);
    LOG.log(Level.INFO, "Step size = {0}", stepSize);
    LOG.log(Level.INFO, "Number of total mini-batches in an epoch = {0}", numTotalMiniBatches);
    LOG.log(Level.INFO, "Total number of keys = {0}", classPartitionIndices.size());
  }

  @Override
  public void initGlobalSettings() {
  }

  private volatile Collection<Map.Entry<Long, MLRData>> miniBatchTrainingData;

  @Override
  public void setMiniBatchData(final Collection<Map.Entry<Long, MLRData>> newMiniBatchTrainingData) {
    this.miniBatchTrainingData = newMiniBatchTrainingData;
  }

  @Override
  public void pullModel() {
    this.partitions = pullModels();
  }

  @Override
  public void localCompute() {
    try {
      System.out.println(System.getProperty("java.library.path"));
      final Sigar sigar = new Sigar();
      System.out.println("-----CPU Info-----");
      System.out.println(sigar.getCpuPerc());
      System.out.println(sigar.getCpu());
      final CpuInfo[] cpuInfoList = sigar.getCpuInfoList();
      final Cpu[] cpuList = sigar.getCpuList();
      for (int c = 0; c < cpuInfoList.length; c++) {
        System.out.println("CPU " + c);
        System.out.println(cpuInfoList[c].toString());
        System.out.println(cpuList[c].toString());
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    final CountDownLatch latch = new CountDownLatch(numTrainerThreads);

    final BlockingQueue<Map.Entry<Long, MLRData>> instances = new ArrayBlockingQueue<>(miniBatchTrainingData.size());
    instances.addAll(miniBatchTrainingData);

    final List<Future<Vector[]>> futures = new ArrayList<>(numTrainerThreads);
    try {
      // Threads drain multiple instances from shared queue, as many as nInstances / nThreads.
      final int drainSize = instances.size() / numTrainerThreads;

      final CountDownLatch modelSetupLatch = new CountDownLatch(numTrainerThreads);
      final int numClassesPerThread = numClasses / numTrainerThreads;

      for (int threadIdx = 0; threadIdx < numTrainerThreads; threadIdx++) {
        final int finalThreadIdx = threadIdx;
        final Future<Vector[]> future = executor.submit(() -> {

          // 1. setup model that is for all threads
          final Vector[] params = model.getParams();

          final int startIdx = numClassesPerThread * finalThreadIdx;
          final int endIdx = finalThreadIdx == numTrainerThreads - 1 ? numClasses : startIdx + numClassesPerThread;
          for (int classIndex = startIdx; classIndex < endIdx; ++classIndex) {
            // 0 ~ (numPartitionsPerClass - 1) is for class 0
            // numPartitionsPerClass ~ (2 * numPartitionsPerClass - 1) is for class 1
            // and so on
            final List<Vector> partialModelsForThisClass =
                partitions.subList(classIndex * numPartitionsPerClass, (classIndex + 1) * numPartitionsPerClass);

            // concat partitions into one long vector
            params[classIndex] = vectorFactory.concatDense(partialModelsForThisClass);
          }

          modelSetupLatch.countDown();

          // 2. setup per-thread data structure for aggregating gradients
          final List<Map.Entry<Long, MLRData>> drainedInstances = new ArrayList<>(drainSize);
          final Vector[] threadGradient = new Vector[numClasses];
          for (int classIdx = 0; classIdx < numClasses; classIdx++) {
            threadGradient[classIdx] = vectorFactory.createDenseZeros(numFeatures);
          }
          LOG.log(Level.INFO, "Gradient vectors are initialized. Used memory: {0} MB", MemoryUtils.getUsedMemoryMB());

          // progress to next step after finishing model setup
          modelSetupLatch.await();

          // 3. start generating gradients from training data
          int count = 0;
          while (true) {
            final int numDrained = instances.drainTo(drainedInstances, drainSize);
            if (numDrained == 0) {
              break;
            }

            drainedInstances.forEach(instance -> updateGradient(instance.getValue(), threadGradient));
            drainedInstances.clear();
            count += numDrained;
          }
          latch.countDown();
          LOG.log(Level.INFO, "{0} has computed {1} instances",
              new Object[] {Thread.currentThread().getName(), count});
          return threadGradient;
        });
        futures.add(future);
      }
      latch.await();
    } catch (final InterruptedException e) {
      LOG.log(Level.SEVERE, "Exception occurred.", e);
      throw new RuntimeException(e);
    }

    // collect the gradients computed by multiple threads
    final List<Vector[]> threadGradients = ThreadUtils.retrieveResults(futures);
    final Vector[] aggregatedMiniBatchGradients = aggregateGradient(threadGradients);

    for (int classIndex = 0; classIndex < numClasses; classIndex++) {
      final Vector gradient = aggregatedMiniBatchGradients[classIndex];

      for (int partitionIndex = 0; partitionIndex < numPartitionsPerClass; ++partitionIndex) {
        final int partitionStart = partitionIndex * numFeaturesPerPartition;
        final int partitionEnd = (partitionIndex + 1) * numFeaturesPerPartition;
        if (keyToGradientMap.put(classIndex * numPartitionsPerClass + partitionIndex,
            gradient.slice(partitionStart, partitionEnd)) != null) {
          throw new RuntimeException();
        }
      }
    }
  }

  @Override
  public void pushUpdate() {
    pushAndResetGradients();
  }

  @Override
  public void onEpochFinished(final int epochIdx) {
    if (decayRate != 1 && (epochIdx + 1) % decayPeriod == 0) {
      final float prevStepSize = stepSize;
      stepSize *= decayRate;
      LOG.log(Level.INFO, "{0} epochs passed. Step size decays from {1} to {2}",
          new Object[]{decayPeriod, prevStepSize, stepSize});
    }
  }

  @Override
  public Map<CharSequence, Double> evaluateModel(final Collection<Map.Entry<Long, MLRData>> inputData,
                                                 final Collection<MLRData> testData,
                                                 final Table modelTable) {
    LOG.log(Level.INFO, "Pull model to compute loss value");
    final MLRModel mlrModel = pullModelsToEvaluate(classPartitionIndices, modelTable);

    LOG.log(Level.INFO, "Start computing loss value");
    final Tuple3<Float, Float, Float> trainingLossRegLossAvgAccuracy = computeLoss(inputData, null, mlrModel);
    final Tuple3<Float, Float, Float> testLossRegLossAvgAccuracy = computeLoss(null, testData, mlrModel);

    final Map<CharSequence, Double> map = new HashMap<>();
    map.put("loss", (double) trainingLossRegLossAvgAccuracy.getFirst());
    map.put("regLoss", (double) trainingLossRegLossAvgAccuracy.getSecond());
    map.put("avgAccuracy", (double) trainingLossRegLossAvgAccuracy.getThird());
    map.put("testLoss", (double) testLossRegLossAvgAccuracy.getFirst());
    map.put("testRegLoss", (double) testLossRegLossAvgAccuracy.getSecond());
    map.put("testAvgAccuracy", (double) testLossRegLossAvgAccuracy.getThird());
    return map;
  }

  /**
   * Pull models one last time and perform validation.
   */
  private MLRModel pullModelsToEvaluate(final List<Integer> keys, final Table<Integer, Vector, Vector> modelTable) {
    partitions = modelAccessor.pull(keys, modelTable);

    final MLRModel mlrModel = new MLRModel(new Vector[numClasses]);
    final Vector[] params = mlrModel.getParams();

    for (int classIndex = 0; classIndex < numClasses; ++classIndex) {
      // 0 ~ (numPartitionsPerClass - 1) is for class 0
      // numPartitionsPerClass ~ (2 * numPartitionsPerClass - 1) is for class 1
      // and so on
      final List<Vector> partialModelsForThisClass =
          partitions.subList(classIndex * numPartitionsPerClass, (classIndex + 1) * numPartitionsPerClass);

      // concat partitions into one long vector
      params[classIndex] = vectorFactory.concatDense(partialModelsForThisClass);
    }

    return mlrModel;
  }

  @Override
  public void cleanup() {
    executor.shutdown();
  }

  /**
   * Pull up-to-date model parameters from server, which become accessible via {@link ModelHolder#getModel()}.
   */
  private List<Vector> pullModels() {
    return modelAccessor.pull(classPartitionIndices);
  }

  private  volatile List<Vector> partitions;

  /**
   * Processes one training data instance and update the intermediate model.
   * @param instance training data instance
   * @param threadGradient update for each instance
   */
  private void updateGradient(final MLRData instance, final Vector[] threadGradient) {
    final Vector feature = instance.getFeature();
    final Vector[] params = model.getParams();
    final int label = instance.getLabel();

    // compute h(x, w) = softmax(x dot w)
    final Vector predictions = predict(feature, params);

    // error = h(x, w) - y, where y_j = 1 (if positive for class j) or 0 (otherwise)
    // instead of allocating a new vector for the error,
    // we use the same object for convenience
    predictions.set(label, predictions.get(label) - 1);

    // gradient_j = -stepSize * error_j * x
    if (lambda != 0) {
      for (int j = 0; j < numClasses; ++j) {
        threadGradient[j].axpy(-predictions.get(j) * stepSize, feature);
        threadGradient[j].axpy(-stepSize * lambda, params[j]);
      }
    } else {
      for (int j = 0; j < numClasses; ++j) {
        threadGradient[j].axpy(-predictions.get(j) * stepSize, feature);
      }
    }
  }

  /**
   * Add all the gradients computed by different threads.
   * @param threadGradients list of gradients computed by trainer threads
   * @return an array of vectors each of which is gradient in a class.
   */
  private Vector[] aggregateGradient(final List<Vector[]> threadGradients) {
    final Vector[] gradients = new Vector[numClasses];

    for (int classIdx = 0; classIdx < numClasses; classIdx++) {
      gradients[classIdx] = vectorFactory.createDenseZeros(numFeatures);
      for (int threadIdx = 0; threadIdx < numTrainerThreads; threadIdx++) {
        gradients[classIdx].addi(threadGradients.get(threadIdx)[classIdx]);
      }
    }
    return gradients;
  }

  private final Map<Integer, Vector> keyToGradientMap;

  /**
   * Push the gradients to parameter server.
   */
  private void pushAndResetGradients() {
    modelAccessor.push(keyToGradientMap);
    keyToGradientMap.clear();
  }

  /**
   * Compute the loss value using the current models and given data instances.
   * May take long, so do not call frequently.
   * Only one type of MLR data arguments is not null.
   */
  private Tuple3<Float, Float, Float> computeLoss(final Collection<Map.Entry<Long, MLRData>> kvData,
                                                  final Collection<MLRData> data,
                                                  final MLRModel mlrModel) {
    final Vector[] params = mlrModel.getParams();
    final int dataSize;

    double loss = 0;
    int correctPredictions = 0;

    if (kvData == null) {
      dataSize = data.size();
      for (final MLRData entry : data) {
        final Vector feature = entry.getFeature();
        final int label = entry.getLabel();
        final Vector predictions = predict(feature, params);
        final int prediction = max(predictions).getLeft();

        if (label == prediction) {
          ++correctPredictions;
        }

        loss += -Math.log(predictions.get(label));
      }
    } else {
      dataSize = kvData.size();
      for (final Map.Entry<Long, MLRData> entry : kvData) {
        final Vector feature = entry.getValue().getFeature();
        final int label = entry.getValue().getLabel();
        final Vector predictions = predict(feature, params);
        final int prediction = max(predictions).getLeft();

        if (label == prediction) {
          ++correctPredictions;
        }

        loss += -Math.log(predictions.get(label));
      }
    }

    double regLoss = 0;
    if (lambda != 0) {
      // skip this part entirely if lambda is zero, to avoid regularization operation overheads
      for (int classIndex = 0; classIndex < numClasses; ++classIndex) {
        final Vector perClassParams = params[classIndex];
        double l2norm = 0;
        for (int vectorIndex = 0; vectorIndex < perClassParams.length(); ++vectorIndex) {
          l2norm += perClassParams.get(vectorIndex) * perClassParams.get(vectorIndex);
        }
        regLoss += l2norm * lambda / 2;
      }
    }
    regLoss /= numClasses;

    return new Tuple3<>((float) loss, (float) regLoss, (float) correctPredictions / dataSize);
  }

  /**
   * Compute the probability vector of the given data instance, represented by {@code features}.
   */
  private Vector predict(final Vector features, final Vector[] params) {
    final float[] predict = new float[numClasses];
    for (int classIndex = 0; classIndex < numClasses; ++classIndex) {
      predict[classIndex] = params[classIndex].dot(features);
    }
    return softmax(vectorFactory.createDense(predict));
  }

  private static Vector softmax(final Vector vector) {
    // prevent overflow during exponential operations
    // https://lingpipe-blog.com/2009/06/25/log-sum-of-exponentials/
    final double logSumExp = logSumExp(vector);
    for (int index = 0; index < vector.length(); ++index) {
      vector.set(index, Math.max(
          Math.min(1 - ZERO_THRESHOLD, (float) Math.exp(vector.get(index) - logSumExp)),
          ZERO_THRESHOLD));
    }
    return vector;
  }

  /**
   * Returns {@code log(sum_i(exp(vector.get(i)))}, while avoiding overflow.
   */
  private static double logSumExp(final Vector vector) {
    final double max = max(vector).getRight();
    double sumExp = 0f;
    for (int index = 0; index < vector.length(); ++index) {
      sumExp += Math.exp(vector.get(index) - max);
    }
    return max + Math.log(sumExp);
  }

  /**
   * Find the largest value in {@code vector} and return its index and the value itself together.
   */
  private static Pair<Integer, Float> max(final Vector vector) {
    float maxValue = vector.get(0);
    int maxIndex = 0;
    for (int index = 1; index < vector.length(); ++index) {
      final float value = vector.get(index);
      if (value > maxValue) {
        maxValue = value;
        maxIndex = index;
      }
    }
    return Pair.of(maxIndex, maxValue);
  }
}