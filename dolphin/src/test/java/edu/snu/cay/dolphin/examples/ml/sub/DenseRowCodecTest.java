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
package edu.snu.cay.dolphin.examples.ml.sub;

import edu.snu.cay.dolphin.examples.ml.data.Row;
import org.apache.mahout.common.RandomUtils;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.Vector;
import org.apache.reef.tang.Tang;
import org.apache.reef.tang.exceptions.InjectionException;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * Test Codec for row with a dense vector.
 */
public final class DenseRowCodecTest {

  private DenseRowCodec denseRowCodec;
  private Random random;

  @Before
  public void setUp() throws InjectionException {
    this.denseRowCodec = Tang.Factory.getTang().newInjector().getInstance(DenseRowCodec.class);
    this.random = RandomUtils.getRandom();
  }

  private Row generateDenseRow(final int featureSize) {
    final double output = random.nextDouble();

    final double[] featureArray = new double[featureSize];
    for (int i = 0; i < featureSize; i++) {
      featureArray[i] = random.nextDouble();
    }
    final Vector feature = new DenseVector(featureArray);

    return new Row(output, feature);
  }

  @Test
  public void testDenseRowCodec() {
    final Row row = generateDenseRow(50);
    assertEquals(row, denseRowCodec.decode(denseRowCodec.encode(row)));
  }
}