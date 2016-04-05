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
package edu.snu.cay.services.em.driver.impl;

import edu.snu.cay.utils.LongRangeUtils;
import net.jcip.annotations.ThreadSafe;
import org.apache.commons.lang.math.LongRange;
import org.apache.reef.annotations.audience.DriverSide;
import org.apache.reef.annotations.audience.Private;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manager class for keeping track of partitions registered by evaluators.
 * It handles a global view of the partitions of all Evaluators, so we can guarantee that all partitions are unique.
 * This class is thread-safe, so many users can request to register/remove/move partitions at the same time.
 */
@ThreadSafe
@DriverSide
@Private
public final class PartitionManager {
  private static final Logger LOG = Logger.getLogger(PartitionManager.class.getName());

  private final AtomicInteger numEvalCounter = new AtomicInteger(0);

  /**
   * This map holds the partitions of all evaluators by using data type as a key.
   * We can easily check the uniqueness of a partition when we register it.
   * Note that we should update the both maps when adding or removing a partition.
   */
  private final Map<String, NavigableSet<LongRange>> globalPartitions;

  /**
   * This map consists of the sub-map which holds the partitions of each evaluator.
   * When asked to remove/move partitions, we choose the partitions based on this information.
   * For example, let's assume that Evaluator1 has [1, 4] and Evaluator2 has [5, 8].
   * When {@code remove[3, 6]} is requested to Evaluator1, then only [5, 6] will be removed because
   * Evaluator2 does not have rest of the partitions.
   * Note that we should update the both maps when adding or removing a partition.
   */
  private final Map<String, Map<String, NavigableSet<LongRange>>> evalPartitions;

  /**
   * A mapping that maintains each evaluator have taken which partitions.
   * A key of map is an evaluator id and and a value of map is a partition id.
   * TODO #346: Clean up data structures managing data id partitions
   */
  private final Map<String, Integer> evalPartitionMap;

  @Inject
  private PartitionManager() {
    this.evalPartitionMap = new HashMap<>();
    this.evalPartitions = new HashMap<>();
    this.globalPartitions = new HashMap<>();
  }

  /**
   * Register an evaluator and allocate a partition to the evaluator.
   * @param contextId an id of context
   * @return a id of allocated partition
   */
  public synchronized int registerEvaluator(final String contextId) {
    if (evalPartitionMap.containsKey(contextId)) {
      throw new RuntimeException("This evaluator is already registered. Its context Id is " + contextId);
    }
    final int partitionId = numEvalCounter.getAndIncrement();
    LOG.log(Level.INFO, "contextId: {0}, partitionId: {1}", new Object[]{contextId, partitionId});
    evalPartitionMap.put(contextId, Integer.valueOf(partitionId));
    return partitionId;
  }

  /**
   * Register a partition to an evaluator.
   * @param evalId Identifier of the Evaluator.
   * @param dataType Type of the data.
   * @param unitStartId Smallest unit id in the partition.
   * @param unitEndId Largest unit id in the partition.
   * @return {@code true} if the partition was registered successfully, {@code false} there was an
   * overlapping range.
   */
  public boolean register(final String evalId, final String dataType,
                          final long unitStartId, final long unitEndId) {
    return register(evalId, dataType, new LongRange(unitStartId, unitEndId));
  }

  /**
   * Register a partition to an evaluator.
   * @param evalId Identifier of the Evaluator.
   * @param dataType Type of the data.
   * @param idRange Range of id
   * @return {@code true} if the partition was registered successfully, {@code false} there was an
   * overlapping range.
   */
  public synchronized boolean register(final String evalId, final String dataType, final LongRange idRange) {
    // Check the acceptability of a new partition into global ranges where the data type is a key
    // Early failure in this step guarantees that all ranges are unique across evaluators.
    final NavigableSet<LongRange> globalRanges;

    if (globalPartitions.containsKey(dataType)) {
      globalRanges = globalPartitions.get(dataType);
      final LongRange ceilingRange = globalRanges.ceiling(idRange);
      if (ceilingRange != null && ceilingRange.overlapsRange(idRange)) {
        return false; // upside overlaps
      }
      final LongRange floorRange = globalRanges.floor(idRange);
      if (floorRange != null && floorRange.overlapsRange(idRange)) {
        return false; // downside overlaps
      }
    } else {
      // Successfully registered in the global partitions.
      globalRanges = LongRangeUtils.createLongRangeSet();
      globalPartitions.put(dataType, globalRanges);
    }

    // Check the acceptability of a new partition into evalPartitions
    final Map<String, NavigableSet<LongRange>> evalDataTypeRanges;

    if (this.evalPartitions.containsKey(evalId)) {
      evalDataTypeRanges = this.evalPartitions.get(evalId);
    } else {
      evalDataTypeRanges = new HashMap<>();
      this.evalPartitions.put(evalId, evalDataTypeRanges);
    }

    final NavigableSet<LongRange> evalRanges;

    if (evalDataTypeRanges.containsKey(dataType)) {
      evalRanges = evalDataTypeRanges.get(dataType);
    } else {
      evalRanges = LongRangeUtils.createLongRangeSet();
      evalDataTypeRanges.put(dataType, evalRanges);
    }

    // Check the registering partition's possibility to be merged to adjacent partitions within the evaluator
    // and then merge contiguous partitions into a big partition.
    final LongRange higherRange = evalRanges.higher(idRange);
    final long endId;

    if (higherRange != null && higherRange.getMinimumLong() == idRange.getMaximumLong() + 1) {
      globalRanges.remove(higherRange);
      evalRanges.remove(higherRange);
      endId = higherRange.getMaximumLong();
    } else {
      endId = idRange.getMaximumLong();
    }

    final LongRange lowerRange = evalRanges.lower(idRange);
    final long startId;

    if (lowerRange != null && lowerRange.getMaximumLong() + 1 == idRange.getMinimumLong()) {
      globalRanges.remove(lowerRange);
      evalRanges.remove(lowerRange);
      startId = lowerRange.getMinimumLong();
    } else {
      startId = idRange.getMinimumLong();
    }

    final LongRange mergedRange = new LongRange(startId, endId);

    globalRanges.add(mergedRange);
    evalRanges.add(mergedRange);

    return true;
  }

  /**
   * Get the set of existing data types in a evaluator.
   * @return Set of data types. An empty set is returned if there is no data types in the evaluator.
   */
  public synchronized Set<String> getDataTypes(final String evalId) {
    if (!evalPartitions.containsKey(evalId)) {
      LOG.log(Level.WARNING, "The evaluator {0} does not exist.", evalId);
      return new HashSet<>();
    }
    final Set<String> dataTypeSet = new HashSet<>(evalPartitions.get(evalId).size());
    for (final Map.Entry<String, NavigableSet<LongRange>> entry : evalPartitions.get(evalId).entrySet()) {
      if (!entry.getValue().isEmpty()) {
        dataTypeSet.add(entry.getKey());
      }
    }
    return dataTypeSet;
  }

  /**
   * Get the existing range set in a evaluator of a type.
   * @return Sorted set of ranges. An empty set is returned if there is no matched range.
   */
  public synchronized Set<LongRange> getRangeSet(final String evalId, final String dataType) {
    if (!evalPartitions.containsKey(evalId)) {
      LOG.log(Level.WARNING, "The evaluator {0} does not exist.", evalId);
      return new TreeSet<>();
    }

    final Map<String, NavigableSet<LongRange>> evalDataTypeRanges = evalPartitions.get(evalId);
    if (!evalDataTypeRanges.containsKey(dataType)) {
      LOG.log(Level.WARNING, "The evaluator {0} does not contain any data whose type is {1}.",
          new Object[]{evalId, dataType});
      return new TreeSet<>();
    }

    return new TreeSet<>(evalDataTypeRanges.get(dataType));
  }

  /**
   * Check whether the type of data exists in the Evaluator.
   * @param srcEvalId Identifier of the evaluator who should send the data.
   * @param dataType Type of the data.
   * @return {@code true} if the Evaluator has data of the given type. {@code false} is returned if the
   * Evaluator does not exist, or the Evaluator does not contain the data of the type {@code dataType}.
   */
  public synchronized boolean checkDataType(final String srcEvalId, final String dataType) {
    if (!evalPartitions.containsKey(srcEvalId) || !evalPartitions.get(srcEvalId).containsKey(dataType)) {
      LOG.log(Level.WARNING, "The evaluator {0} does not contain any data whose type is {1}.",
          new Object[]{srcEvalId, dataType});
      return false;
    }
    return true;
  }

  /**
   * Check whether the ranges exist in the Evaluator. Because the ranges are sparse, we can't guarantee that
   * the actual data exist in the Evaluator even this method returns {@code true}.
   * This method is useful to reject the request early if any unit in the ranges does not exist in the Evaluator.
   * @param srcEvalId Identifier of the evaluator who should send the data.
   * @param dataType Type of the data.
   * @param rangesToMove Set of ranges that are requested to move.
   * @return {@code true} if the Evaluator could have units in the {@code rangesToMove}.
   * {@code false} is returned if it is guaranteed that the Evaluator does not have any unit in the range.
   */
  public synchronized boolean checkRanges(final String srcEvalId, final String dataType,
                                          final Set<LongRange> rangesToMove) {
    if (!checkDataType(srcEvalId, dataType)) {
      return false;
    }

    final NavigableSet<LongRange> evalRanges = evalPartitions.get(srcEvalId).get(dataType);
    for (final LongRange range : rangesToMove) {
      // If any range could exist in the Evaluator, return true immediately.
      if (evalRanges.contains(range)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Remove the range from the partitions. After deleting a range, the partitions could be rearranged.
   * @param evalId Identifier of Evaluator.
   * @param dataType Type of data.
   * @param idRange Range of unit ids to remove.
   * @return Ranges that are removed from the partitions. If the entire range is not matched, part of range
   * is removed and returned. On the other hand, multiple ranges could be deleted if the range contains multiple
   * partitions. An empty set is returned if there is no intersecting range.
   */
  public synchronized Set<LongRange> remove(final String evalId, final String dataType, final LongRange idRange) {
    // Early failure if the evaluator is empty.
    final Map<String, NavigableSet<LongRange>> evalDataTypeRanges = evalPartitions.get(evalId);
    if (evalDataTypeRanges == null) {
      LOG.log(Level.WARNING, "The evaluator {0} does not exist.", evalId);
      return new TreeSet<>();
    }

    // Early failure if the evaluator does not have any data of that type.
    final NavigableSet<LongRange> evalRanges = evalDataTypeRanges.get(dataType);
    if (evalRanges == null) {
      LOG.log(Level.WARNING, "The evaluator {0} does not contain any data whose type is {1}.",
          new Object[]{evalId, dataType});
      return new TreeSet<>();
    }

    final Set<LongRange> globalRanges = globalPartitions.get(dataType);

    // Remove the ranges inside the range to remove.
    final Set<LongRange> removedRanges = LongRangeUtils.createLongRangeSet();
    final Set<LongRange> insideRanges = removeInsideRanges(globalRanges, evalRanges, idRange);
    removedRanges.addAll(insideRanges);

    // Remove overlapping ranges if any. Try ceilingRange first.
    final LongRange ceilingRange = evalRanges.ceiling(idRange);
    if (ceilingRange != null && ceilingRange.overlapsRange(idRange)) {
      final LongRange ceilingRemoved = removeIntersectingRange(globalRanges, evalRanges, ceilingRange, idRange);
      if (ceilingRemoved != null) {
        removedRanges.add(ceilingRemoved);
      }
    }

    // Next try the floorRange. There is no duplicate removal because we already removed ceiling range.
    final LongRange floorRange = evalRanges.floor(idRange);
    if (floorRange != null && floorRange.overlapsRange(idRange)) {
      final LongRange floorRemoved = removeIntersectingRange(globalRanges, evalRanges, floorRange, idRange);
      if (floorRemoved != null) {
        removedRanges.add(floorRemoved);
      }
    }

    return removedRanges;
  }

  /**
   * Move a partition to another evaluator.
   * @param srcId Id of the source.
   * @param destId Id of the destination.
   * @param dataType Type of the data.
   * @param toMove Range of unit ids to move.
   */
  public synchronized void move(final String srcId, final String destId,
                                final String dataType, final LongRange toMove) {
    // 1. remove from the source.
    final Set<LongRange> removedRanges = remove(srcId, dataType, toMove);
    // 2. register the ranges to the destination.
    for (final LongRange toRegister : removedRanges) {
      if (!register(destId, dataType, toRegister)) {
        // Fails if there is an overlapping range in the destination, which PartitionManager should not allow.
        // TODO #90: Failure cases for Move
        final String errorMsg = new StringBuilder()
            .append("Failed while moving the range.")
            .append("srcId: ").append(srcId).append(", destId: ").append(destId)
            .append(", type: ").append(dataType).append(", range: ").append(toMove)
            .append(". The destination seems to have ").append(toRegister).append(", which should not happen")
            .toString();
        throw new RuntimeException(errorMsg);
      }
    }
  }

  /**
   * Remove ranges which target range contains whole range.
   * (e.g., [3, 4] [5, 6] when [1, 10] is requested to remove).
   * @return Ranges that are removed. An empty list is returned if there was no range to remove.
   */
  private Set<LongRange> removeInsideRanges(final Set<LongRange> globalRanges,
                                            final NavigableSet<LongRange> evalRanges,
                                            final LongRange target) {
    final long min = target.getMinimumLong();
    final long max = target.getMaximumLong();

    final NavigableSet<LongRange> insideRanges =
        evalRanges.subSet(new LongRange(min, min), false, new LongRange(max, max), false);

    // Copy the ranges to avoid ConcurrentModificationException and losing references.
    final Set<LongRange> copied = new TreeSet<>(insideRanges);
    evalRanges.removeAll(copied);
    globalRanges.removeAll(copied);
    return copied;
  }

  /**
   * Remove the (sub)range from one range.
   * @param from Original range
   * @param target Target range to remove
   * @return Deleted range. {@code null} if there is no range to delete.
   */
  private LongRange removeIntersectingRange(final Set<LongRange> globalRanges,
                                            final Set<LongRange> evalRanges,
                                            final LongRange from,
                                            final LongRange target) {
    // Remove the range from both global and evaluator's range sets.
    globalRanges.remove(from);
    evalRanges.remove(from);

    if (target.containsRange(from)) {
      // If the target range is larger, the whole range is removed.
      return from;

    } else {
      // If two sections are overlapping, we can divide into three parts: LEFT | CENTER | RIGHT
      // We need to remove CENTER ([centerLeft centerRight]) which is an intersection, and keep
      // LEFT ([leftEnd (centerLeft-1)] and RIGHT ([(centerRight+1) rightEnd]) if they are not empty.
      final long minFrom = from.getMinimumLong();
      final long maxFrom = from.getMaximumLong();
      final long minTarget = target.getMinimumLong();
      final long maxTarget = target.getMaximumLong();

      final long leftEnd = Math.min(minFrom, minTarget);
      final long centerLeft = Math.max(minFrom, minTarget);
      final long centerRight = Math.min(maxFrom, maxTarget);
      final long endRight = Math.max(maxFrom, maxTarget);

      // Keep LEFT if exists
      if (leftEnd < centerLeft) {
        final LongRange left = new LongRange(leftEnd, centerLeft - 1);
        globalRanges.add(left);
        evalRanges.add(left);
      }

      // Keep RIGHT if exists
      if (endRight > centerRight) {
        final LongRange right = new LongRange(centerRight + 1, endRight);
        globalRanges.add(right);
        evalRanges.add(right);
      }

      return target;
    }
  }
}