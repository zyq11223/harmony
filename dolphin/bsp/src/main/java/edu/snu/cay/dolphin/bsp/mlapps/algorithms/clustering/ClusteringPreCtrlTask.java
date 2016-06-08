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
package edu.snu.cay.dolphin.bsp.mlapps.algorithms.clustering;

import edu.snu.cay.dolphin.bsp.core.KeyValueStore;
import edu.snu.cay.dolphin.bsp.core.UserControllerTask;
import edu.snu.cay.dolphin.bsp.mlapps.key.Centroids;
import edu.snu.cay.dolphin.bsp.mlapps.parameters.NumberOfClusters;
import edu.snu.cay.dolphin.bsp.groupcomm.interfaces.DataGatherReceiver;
import org.apache.mahout.math.Vector;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

public final class ClusteringPreCtrlTask extends UserControllerTask
    implements DataGatherReceiver<List<Vector>> {

  private static final Logger LOG = Logger.getLogger(ClusteringPreCtrlTask.class.getName());

  /**
   * Number of clusters.
   */
  private final int numberOfClusters;

  /**
   * Initial centroids passed from Compute Tasks.
   */
  private List<Vector> initialCentroids = null;

  /**
   * Memory storage to put/get the data.
   */
  private final KeyValueStore keyValueStore;

  @Inject
  public ClusteringPreCtrlTask(
      final KeyValueStore keyValueStore,
      @Parameter(NumberOfClusters.class) final int numberOfClusters) {
    this.keyValueStore = keyValueStore;
    this.numberOfClusters = numberOfClusters;
  }

  @Override
  public void run(final int iteration) {
    //do nothing
  }

  @Override
  public void cleanup() {
    /*
     * Pass the initial centroids to the main process.
     * Since CtrlTask is the only one to own the data, put data in LocalStore.
     */
    keyValueStore.put(Centroids.class, initialCentroids);
  }

  @Override
  public boolean isTerminated(final int iteration) {
    return iteration > 0;
  }

  @Override
  public void receiveGatherData(final int iteration, final List<List<Vector>> initialCentroidsData) {
    final List<Vector> points = new LinkedList<>();

    // Flatten the given list of lists
    for (final List<Vector> list : initialCentroidsData) {
      for (final Vector vector : list) {
        points.add(vector);
      }
    }

    //sample initial centroids
    this.initialCentroids = ClusteringPreCmpTask.sample(points, numberOfClusters);
  }
}