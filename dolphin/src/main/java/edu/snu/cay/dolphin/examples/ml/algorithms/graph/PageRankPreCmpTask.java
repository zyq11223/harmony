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
package edu.snu.cay.dolphin.examples.ml.algorithms.graph;

import edu.snu.cay.dolphin.core.DataParser;
import edu.snu.cay.dolphin.core.ParseException;
import edu.snu.cay.dolphin.core.UserComputeTask;
import edu.snu.cay.dolphin.examples.ml.data.AdjacencyListDataType;
import edu.snu.cay.services.em.evaluator.api.MemoryStore;
import edu.snu.cay.services.em.evaluator.api.PartitionTracker;
import edu.snu.cay.utils.LongRangeUtils;
import org.apache.commons.lang.math.LongRange;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

public final class PageRankPreCmpTask extends UserComputeTask {

  /**
   * Key used in Elastic Memory to put/get the data.
   */
  private final String dataType;

  /**
   * Adjacency list data parser.
   */
  private final DataParser<Map<Integer, List<Integer>>> dataParser;

  /**
   * Memory storage to put/get the data.
   */
  private final MemoryStore memoryStore;

  /**
   * Partition tracker to register partitions to.
   */
  private final PartitionTracker partitionTracker;

  @Inject
  private PageRankPreCmpTask(@Parameter(AdjacencyListDataType.class) final String dataType,
                             final DataParser<Map<Integer, List<Integer>>> dataParser,
                             final MemoryStore memoryStore,
                             final PartitionTracker partitionTracker) {
    this.dataType = dataType;
    this.dataParser = dataParser;
    this.memoryStore = memoryStore;
    this.partitionTracker = partitionTracker;
  }

  @Override
  public void initialize() throws ParseException {
    final Map<Integer, List<Integer>> subgraphs = dataParser.get();

    // Register a minimal set of LongRange partitions. Sorting can be expensive. If the dataset is known
    // to be sorted, the dataParser should preserve order and the initialization should skip sorting.
    final SortedSet<Long> sortedIds = new TreeSet<>();
    for (final Integer id : subgraphs.keySet()) {
      sortedIds.add(id.longValue());
    }
    for (final LongRange range : LongRangeUtils.generateDenseLongRanges(sortedIds)) {
      partitionTracker.registerPartition(dataType, range.getMinimumLong(), range.getMaximumLong());
    }

    for (final Map.Entry<Integer, List<Integer>> entry : subgraphs.entrySet()) {
      memoryStore.getElasticStore().put(dataType, entry.getKey().longValue(), entry.getValue());
    }
  }

  @Override
  public void run(final int iteration) {

  }
}