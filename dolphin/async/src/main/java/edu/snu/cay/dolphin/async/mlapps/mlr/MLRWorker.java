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
package edu.snu.cay.dolphin.async.mlapps.mlr;

import edu.snu.cay.dolphin.async.mlapps.mlr.MLRREEF.*;
import edu.snu.cay.dolphin.async.Worker;
import edu.snu.cay.dolphin.async.WorkerSynchronizer;
import edu.snu.cay.dolphin.async.mlapps.nmf.Tracer;
import edu.snu.cay.common.math.linalg.Vector;
import edu.snu.cay.common.math.linalg.VectorFactory;
import edu.snu.cay.services.ps.worker.api.ParameterWorker;
import edu.snu.cay.utils.Tuple3;
import org.apache.reef.io.network.util.Pair;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link Worker} class for the MLRREEF application.
 * Uses {@code numClasses} model vectors to determine which class each data instance belongs to.
 * The model vector that outputs the highest dot product value is declared as that data instance's prediction.
 */
final class MLRWorker implements Worker {
  private static final Logger LOG = Logger.getLogger(MLRWorker.class.getName());

  /**
   * Parser object for fetching and parsing the input dataset.
   */
  private final MLRParser mlrParser;

  /**
   * Synchronization component for setting a global barrier across workers.
   */
  private final WorkerSynchronizer synchronizer;

  /**
   * Worker object used to interact with the parameter server.
   */
  private final ParameterWorker<Integer, Vector, Vector> worker;

  /**
   * Number of possible classes for a data instance.
   */
  private final int numClasses;

  /**
   * Number of features of each model partition.
   */
  private final int numFeaturesPerPartition;

  /**
   * Number of model partitions for each class.
   */
  private final int numPartitionsPerClass;

  /**
   * Number of batches per iteration.
   */
  private final int numBatchPerIter;

  /**
   * Size of each step taken during gradient descent.
   */
  private double stepSize;

  /**
   * L2 regularization constant.
   */
  private final double lambda;

  /**
   * Number of iterations to wait until logging the loss value.
   */
  private final int statusLogPeriod;

  /**
   * Object for creating {@link Vector} instances.
   */
  private final VectorFactory vectorFactory;

  /**
   * The input dataset, given as a list of pairs which are in the form, (input vector, label).
   */
  private List<Pair<Vector, Integer>> data;

  /**
   * Model vectors that represent each class.
   */
  private final Vector[] oldModels;
  private final Vector[] newModels;

  /**
   * A list from 0 to {@code numClasses * numPartitionsPerClass} that will be used during {@code worker.pull()}.
   */
  private List<Integer> classPartitionIndices;

  /**
   * Number of times {@code run()} has been called.
   */
  private int iteration;

  /**
   * The step size drops by this rate.
   */
  private final double decayRate;

  /**
   * The step size drops after {@code decayPeriod} iterations pass.
   */
  private final int decayPeriod;

  /**
   * Number of instances to compute training loss with.
   */
  private final int trainErrorDatasetSize;

  /**
   * Write loss info to log after {@code numBatchPerLossLog} batches.
   */
  private final int numBatchPerLossLog;

  private final Tracer pushTracer;
  private final Tracer pullTracer;
  private final Tracer computeTracer;

  @Inject
  private MLRWorker(final MLRParser mlrParser,
                    final WorkerSynchronizer synchronizer,
                    final ParameterWorker<Integer, Vector, Vector> worker,
                    @Parameter(NumClasses.class) final int numClasses,
                    @Parameter(NumFeatures.class) final int numFeatures,
                    @Parameter(NumFeaturesPerPartition.class) final int numFeaturesPerPartition,
                    @Parameter(InitialStepSize.class) final double initStepSize,
                    @Parameter(Lambda.class) final double lambda,
                    @Parameter(StatusLogPeriod.class) final int statusLogPeriod,
                    @Parameter(DecayRate.class) final double decayRate,
                    @Parameter(DecayPeriod.class) final int decayPeriod,
                    @Parameter(TrainErrorDatasetSize.class) final int trainErrorDatasetSize,
                    @Parameter(NumBatchPerLossLog.class) final int numBatchPerLossLog,
                    @Parameter(NumBatchPerIter.class) final int numBatchPerIter,
                    final VectorFactory vectorFactory) {
    this.mlrParser = mlrParser;
    this.synchronizer = synchronizer;
    this.worker = worker;
    this.numClasses = numClasses;
    this.numFeaturesPerPartition = numFeaturesPerPartition;
    if (numFeatures % numFeaturesPerPartition != 0) {
      throw new RuntimeException("Uneven model partitions");
    }
    this.numPartitionsPerClass = numFeatures / numFeaturesPerPartition;
    this.numBatchPerIter = numBatchPerIter;
    this.stepSize = initStepSize;
    this.lambda = lambda;
    this.statusLogPeriod = statusLogPeriod;
    this.vectorFactory = vectorFactory;
    this.oldModels = new Vector[numClasses];
    this.newModels = new Vector[numClasses];
    this.iteration = 0;
    this.decayRate = decayRate;
    this.decayPeriod = decayPeriod;
    this.trainErrorDatasetSize = trainErrorDatasetSize;
    this.numBatchPerLossLog = numBatchPerLossLog;

    this.pushTracer = new Tracer();
    this.pullTracer = new Tracer();
    this.computeTracer = new Tracer();
  }

  /**
   * Parse the input dataset, initialize a few data structures, and wait for other workers.
   */
  @Override
  public void initialize() {
    data = mlrParser.parse();

    classPartitionIndices = new ArrayList<>(numClasses * numPartitionsPerClass);
    for (int classIndex = 0; classIndex < numClasses; ++classIndex) {
      for (int partitionIndex = 0; partitionIndex < numPartitionsPerClass; ++partitionIndex) {
        // 0 ~ (numPartitionsPerClass - 1) is for class 0
        // numPartitionsPerClass ~ (2 * numPartitionsPerClass - 1) is for class 1
        // and so on
        classPartitionIndices.add(classIndex * numPartitionsPerClass + partitionIndex);
      }
    }

    LOG.log(Level.INFO, "Step size = {0}", stepSize);
    LOG.log(Level.INFO, "Number of batches per iteration = {0}", numBatchPerIter);
    LOG.log(Level.INFO, "Total number of samples = {0}", data.size());
    if (data.size() < trainErrorDatasetSize) {
      LOG.log(Level.WARNING, "Number of samples is less than trainErrorDatasetSize = {0}", trainErrorDatasetSize);
    }

    // all workers should start at the same time
    synchronizer.globalBarrier();
  }

  private void resetTracers() {
    pushTracer.reset();
    pullTracer.reset();
    computeTracer.reset();
  }

  @Override
  public void run() {
    resetTracers();
    final long iterationBegin = System.currentTimeMillis();
    pullModels();

    final Tuple3<Double, Double, Float> tuple3 = computeLoss(trainErrorDatasetSize);
    LOG.log(Level.INFO, "Iteration For Loss: {0}, NumInstances: {1}, Sample Loss Avg: {2}, Reg Loss Avg: {3}, " +
        "Accuracy: {4}",
        new Object[]{iteration, trainErrorDatasetSize, tuple3.getFirst(), tuple3.getSecond(), tuple3.getThird()});


    int numInstances = 0;
    int numBatch = 0;
    int batchSize = data.size() / numBatchPerIter;
    batchSize += data.size() % numBatchPerIter == 0 ? 0 : 1;
    computeTracer.start();
    for (final Pair<Vector, Integer> entry : data) {
      if (numInstances >= batchSize) {
        computeTracer.end(numInstances);

        // push gradients and pull fresh models
        refreshModel();
        computeTracer.start();

        if (++numBatch % numBatchPerLossLog == 0) {
          final Tuple3<Double, Double, Float> pair = computeLoss(trainErrorDatasetSize);
          LOG.log(Level.INFO, "Iteration For Loss: {0}, NumInstances: {1}, Sample Loss Avg: {2}, Reg Loss Avg: {3}, " +
              "Accuracy: {4}",
              new Object[]{iteration, trainErrorDatasetSize, pair.getFirst(), pair.getSecond(), pair.getThird()});
        }

        numInstances = 0;
        batchSize = data.size() / numBatchPerIter;
        batchSize += data.size() % numBatchPerIter <= numBatch ? 0 : 1;
      }

      final Vector features = entry.getFirst();
      final int label = entry.getSecond();

      // compute h(x, w) = softmax(x dot w)
      final Vector predictions = predict(features);

      // error = h(x, w) - y, where y_j = 1 (if positive for class j) or 0 (otherwise)
      // instead of allocating a new vector for the error,
      // we use the same object for convenience
      predictions.set(label, predictions.get(label) - 1);

      // gradient_j = -stepSize * error_j * x
      if (lambda != 0) {
        for (int j = 0; j < numClasses; ++j) {
          newModels[j].axpy(-predictions.get(j) * stepSize, features);
          newModels[j].axpy(-stepSize * lambda, newModels[j]);
        }
      } else {
        for (int j = 0; j < numClasses; ++j) {
          newModels[j].axpy(-predictions.get(j) * stepSize, features);
        }
      }

      ++numInstances;
    }

    computeTracer.end(numInstances);
    if (numInstances > 0) {
      // flush gradients for remaining instances to server
      pushAndResetGradients();
    }

    ++iteration;
    if (statusLogPeriod > 0 && iteration % statusLogPeriod == 0) {
      final double elapsedTime = (System.currentTimeMillis() - iterationBegin) / 1000.0D;
      LOG.log(Level.INFO, "Iteration: {0}, Sample Count: {1}, " +
              "Avg Comp Per Row: {2}, Sum Comp: {3}, Avg Pull: {4}, Sum Pull: {5}, Avg Push: {6}, " +
              "Sum Push: {7}, DvT: {8}, Elapsed Time: {9}",
          new Object[]{iteration, data.size(),
              computeTracer.avgElement(), computeTracer.sum(), pullTracer.avgElement(), pullTracer.sum(),
              pushTracer.avgElement(), pushTracer.sum(), data.size() / elapsedTime, elapsedTime});
    }

    if (iteration % decayPeriod == 0) {
      final double prevStepSize = stepSize;
      stepSize *= decayRate;
      LOG.log(Level.INFO, "{0} iterations have passed. Step size decays from {1} to {2}",
          new Object[]{decayPeriod, prevStepSize, stepSize});
    }
  }

  /**
   * Pull models one last time and perform validation.
   */
  @Override
  public void cleanup() {
    final float waitStart = System.currentTimeMillis();
    synchronizer.globalBarrier();
    final float cleanupStart = System.currentTimeMillis();
    resetTracers();

    pullModels();
    computeTracer.start();
    int numInstances = 0;
    int correctPredictions = 0;
    double loss = 0;
    for (final Pair<Vector, Integer> entry : data) {
      final Vector features = entry.getFirst();
      final int label = entry.getSecond();
      final Vector predictions = predict(features);
      final int prediction = max(predictions).getFirst();

      ++numInstances;
      if (label == prediction) {
        ++correctPredictions;
      }

      for (int classIndex = 0; classIndex < numClasses; ++classIndex) {
        if (classIndex == label) {
          loss += -Math.log(predictions.get(classIndex));
        } else {
          loss += -Math.log(1 - predictions.get(classIndex));
        }
      }
    }
    loss /= data.size();

    computeTracer.end(numInstances);
    final float cleanupEnd = System.currentTimeMillis();

    LOG.log(Level.INFO, "Number of instances: {0}", numInstances);
    LOG.log(Level.INFO, "Correct predictions on training dataset: {0}", correctPredictions);
    LOG.log(Level.INFO, "Prediction accuracy on training dataset: {0}", (float) correctPredictions / numInstances);
    LOG.log(Level.INFO, "Cleanup Samples: {0}, Avg Comp Per Row: {1}, Sum Comp: {2}, Avg Pull: {3}, Sum Pull: {4}, " +
            "Elapsed Time: {5}, Wait Time: {6}, Sample Loss Avg: {7}",
        new Object[]{numInstances, computeTracer.avg(), computeTracer.sum(), pullTracer.avg(), pullTracer.sum(),
            cleanupEnd - waitStart, cleanupStart - waitStart, loss});
  }

  private void pullModels() {
    pullTracer.start();
    final List<Vector> partitions = worker.pull(classPartitionIndices);
    pullTracer.end(partitions.size());
    computeTracer.start();
    for (int classIndex = 0; classIndex < numClasses; ++classIndex) {
      // 0 ~ (numPartitionsPerClass - 1) is for class 0
      // numPartitionsPerClass ~ (2 * numPartitionsPerClass - 1) is for class 1
      // and so on
      final List<Vector> partialModelsForThisClass =
          partitions.subList(classIndex * numPartitionsPerClass, (classIndex  + 1) * numPartitionsPerClass);

      // concat partitions into one long vector
      oldModels[classIndex] = vectorFactory.concatDense(partialModelsForThisClass);
      newModels[classIndex] = oldModels[classIndex].copy();
    }
    computeTracer.end(0);
  }

  private void refreshModel() {
    pushAndResetGradients();
    pullModels();
  }

  private void pushAndResetGradients() {
    for (int classIndex = 0; classIndex < numClasses; classIndex++) {
      computeTracer.start();
      final Vector gradient = newModels[classIndex].sub(oldModels[classIndex]);
      computeTracer.end(0);

      pushTracer.start();
      for (int partitionIndex = 0; partitionIndex < numPartitionsPerClass; ++partitionIndex) {
        final int partitionStart = partitionIndex * numFeaturesPerPartition;
        final int partitionEnd = (partitionIndex + 1) * numFeaturesPerPartition;
        worker.push(classIndex * numPartitionsPerClass + partitionIndex,
            gradient.slice(partitionStart, partitionEnd));
      }
      pushTracer.end(numPartitionsPerClass);
    }
  }

  /**
   * Compute the loss value using the current models and all data instances.
   * May take long, so do not call frequently.
   */
  private Tuple3<Double, Double, Float> computeLoss(final int datasetSize) {
    double loss = 0;
    int numInstances = 0;
    int correctPredictions = 0;
    for (final Pair<Vector, Integer> entry : data.subList(0, datasetSize)) {
      final Vector features = entry.getFirst();
      final int label = entry.getSecond();
      final Vector predictions = predict(features);
      final int prediction = max(predictions).getFirst();

      if (label == prediction) {
        ++correctPredictions;
      }

      for (int classIndex = 0; classIndex < numClasses; ++classIndex) {
        if (classIndex == label) {
          loss += -Math.log(predictions.get(classIndex));
        } else {
          loss += -Math.log(1 - predictions.get(classIndex));
        }
      }

      ++numInstances;
    }
    loss /= numInstances;

    double regLoss = 0;
    if (lambda != 0) {
      // skip this part entirely if lambda is zero, to avoid regularization operation overheads
      for (int classIndex = 0; classIndex < numClasses; ++classIndex) {
        final Vector model = newModels[classIndex];
        double l2norm = 0;
        for (int vectorIndex = 0; vectorIndex < model.length(); ++vectorIndex) {
          l2norm += model.get(vectorIndex) * model.get(vectorIndex);
        }
        regLoss += l2norm * lambda / 2;
      }
    }
    regLoss /= numClasses;
    return new Tuple3<>(loss, regLoss, (float) correctPredictions / numInstances);
  }

  /**
   * Compute the probability vector of the given data instance, represented by {@code features}.
   */
  private Vector predict(final Vector features) {
    final double[] predict = new double[numClasses];
    for (int classIndex = 0; classIndex < numClasses; ++classIndex) {
      predict[classIndex] = newModels[classIndex].dot(features);
    }
    return softmax(vectorFactory.createDense(predict));
  }

  private static Vector softmax(final Vector vector) {
    // prevent overflow during exponential operations
    // https://lingpipe-blog.com/2009/06/25/log-sum-of-exponentials/
    final double logSumExp = logSumExp(vector);
    for (int index = 0; index < vector.length(); ++index) {
      vector.set(index, Math.max(Math.min(1 - 1e-12, Math.exp(vector.get(index) - logSumExp)), 1e-12));
    }
    return vector;
  }

  /**
   * Returns {@code log(sum_i(exp(vector.get(i)))}, while avoiding overflow.
   */
  private static double logSumExp(final Vector vector) {
    final double max = max(vector).getSecond();
    double sumExp = 0;
    for (int index = 0; index < vector.length(); ++index) {
      sumExp += Math.exp(vector.get(index) - max);
    }
    return max + Math.log(sumExp);
  }

  /**
   * Find the largest value in {@code vector} and return its index and the value itself together.
   */
  private static Pair<Integer, Double> max(final Vector vector) {
    double maxValue = vector.get(0);
    int maxIndex = 0;
    for (int index = 1; index < vector.length(); ++index) {
      final double value = vector.get(index);
      if (value > maxValue) {
        maxValue = value;
        maxIndex = index;
      }
    }
    return new Pair<>(maxIndex, maxValue);
  }
}