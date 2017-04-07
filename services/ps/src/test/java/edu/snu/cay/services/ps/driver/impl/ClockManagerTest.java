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
package edu.snu.cay.services.ps.driver.impl;

import edu.snu.cay.common.centcomm.avro.CentCommMsg;
import edu.snu.cay.common.centcomm.master.MasterSideCentCommMsgSender;
import edu.snu.cay.common.centcomm.slave.SlaveSideCentCommMsgSender;
import edu.snu.cay.services.ps.avro.AvroClockMsg;
import edu.snu.cay.services.ps.avro.ClockMsgType;
import edu.snu.cay.services.ps.avro.RequestInitClockMsg;
import edu.snu.cay.services.ps.avro.TickMsg;
import edu.snu.cay.services.ps.ns.ClockMsgCodec;
import edu.snu.cay.services.ps.worker.parameters.StalenessBound;
import org.apache.reef.exception.evaluator.NetworkException;
import org.apache.reef.io.network.util.StringIdentifierFactory;
import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.Injector;
import org.apache.reef.tang.Tang;
import org.apache.reef.tang.exceptions.InjectionException;
import org.apache.reef.wake.IdentifierFactory;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link ClockManager}.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({SlaveSideCentCommMsgSender.class, MasterSideCentCommMsgSender.class})
public final class ClockManagerTest {
  private static final int STALENESS_BOUND = 4;
  private static final int NUM_WORKERS = 10;

  private SlaveSideCentCommMsgSender mockSlaveSideCentCommMsgSender;
  private MasterSideCentCommMsgSender mockMasterSideCentCommMsgSender;
  private ClockManager clockManager;
  private ClockManager.MessageHandler clockMessageHandler;
  private ClockMsgCodec codec;

  @Before
  public void setup() throws InjectionException {
    final Configuration conf = Tang.Factory.getTang().newConfigurationBuilder()
        .bindNamedParameter(StalenessBound.class, Integer.toString(STALENESS_BOUND))
        .bindImplementation(IdentifierFactory.class, StringIdentifierFactory.class)
        .build();
    final Injector injector = Tang.Factory.getTang().newInjector(conf);
    mockSlaveSideCentCommMsgSender = mock(SlaveSideCentCommMsgSender.class);
    injector.bindVolatileInstance(SlaveSideCentCommMsgSender.class, mockSlaveSideCentCommMsgSender);
    mockMasterSideCentCommMsgSender = mock(MasterSideCentCommMsgSender.class);
    injector.bindVolatileInstance(MasterSideCentCommMsgSender.class, mockMasterSideCentCommMsgSender);

    this.clockManager = injector.getInstance(ClockManager.class);
    this.clockMessageHandler = injector.getInstance(ClockManager.MessageHandler.class);
    this.codec = injector.getInstance(ClockMsgCodec.class);

    doAnswer(invocation -> {
      // the first parameter of SlaveSideCentCommMsgSender::send() is classClientName but workerId is used instead
      // because mockSlaveSideCentCommMsgSender couldn't send its source id(no network connection).
      final String workerId = invocation.getArgumentAt(0, String.class);
      final byte[] data = invocation.getArgumentAt(1, byte[].class);
      final CentCommMsg centCommMsg = getTestCentCommMsg(workerId, data);

      clockMessageHandler.onNext(centCommMsg);
      return null;
    }).when(mockSlaveSideCentCommMsgSender).send(anyString(), anyObject());
  }

  /**
   * Tests whether ClockManager initializes the clock of workers not added by EM with current global minimum clock.
   * Tests whether ClockManager initializes the clock of workers added by EM with global minimum clock + (staleness /2).
   */
  @Test
  public void testInitializingWorkers() throws InjectionException, NetworkException {
    final int initialGlobalMinimumClock = clockManager.getGlobalMinimumClock();
    final int expectedClockOfWorkersAddedByEM = clockManager.getGlobalMinimumClock() + (STALENESS_BOUND / 2);
    final int numEarlyWorkers = NUM_WORKERS / 2;

    // initialize the clock of workers not added by EM
    for (int workerIdx = 0; workerIdx < numEarlyWorkers; workerIdx++) {
      final String workerId = Integer.toString(workerIdx);
      clockManager.onWorkerAdded(false, workerId);

      final AvroClockMsg avroClockMsg =
          AvroClockMsg.newBuilder()
              .setType(ClockMsgType.RequestInitClockMsg)
              .setRequestInitClockMsg(RequestInitClockMsg.newBuilder().build())
              .build();
      final byte[] data = codec.encode(avroClockMsg);
      mockSlaveSideCentCommMsgSender.send(workerId, data);

      // new clock of worker which is not added by EM equals to globalMinimumClock;
      assertEquals(clockManager.getGlobalMinimumClock(), clockManager.getClockOf(workerId).intValue());
    }

    // new clock of worker not added by EM do not change global minimum clock
    assertEquals(initialGlobalMinimumClock, clockManager.getGlobalMinimumClock());

    // initialize the clock of workers added by EM
    for (int workerIdx = numEarlyWorkers; workerIdx < NUM_WORKERS; workerIdx++) {
      final String workerId = Integer.toString(workerIdx);
      clockManager.onWorkerAdded(true, workerId);

      final AvroClockMsg avroClockMsg =
          AvroClockMsg.newBuilder()
              .setType(ClockMsgType.RequestInitClockMsg)
              .setRequestInitClockMsg(RequestInitClockMsg.newBuilder().build())
              .build();
      final byte[] data = codec.encode(avroClockMsg);
      mockSlaveSideCentCommMsgSender.send(workerId, data);

      // new clock of worker which is added by EM is globalMinimumClock + staleness / 2 ;
      assertEquals(expectedClockOfWorkersAddedByEM, clockManager.getClockOf(workerId).intValue());
    }

    // new workers added by EM do not change global minimum clock
    assertEquals(initialGlobalMinimumClock, clockManager.getGlobalMinimumClock());
  }

  /**
   * Tests whether global minimum clock is updated when the minimum clock worker is deleted.
   */
  @Test
  public void testDeletionOfMinimumWorkers() {
    final int initialGlobalMinimumClock = clockManager.getGlobalMinimumClock();

    // add workers(not added by EM)
    for (int workerIdx = 0; workerIdx < NUM_WORKERS; workerIdx++) {
      final String workerId = Integer.toString(workerIdx);
      clockManager.onWorkerAdded(false, workerId);
    }

    for (int workerIdx = 0; workerIdx < NUM_WORKERS; workerIdx++) {
      final String workerId = Integer.toString(workerIdx);
      // tick worker id times
      for (int i = 0; i < workerIdx; i++) {
        final AvroClockMsg avroClockMsg =
            AvroClockMsg.newBuilder()
                .setType(ClockMsgType.TickMsg)
                .setTickMsg(TickMsg.newBuilder().build())
                .build();
        final byte[] data = codec.encode(avroClockMsg);
        mockSlaveSideCentCommMsgSender.send(workerId, data);
      }
      assertEquals(initialGlobalMinimumClock + workerIdx, clockManager.getClockOf(workerId).intValue());
    }

    // delete minimum clock worker
    // in this test, minimum clock worker has minimum worker id
    for (int workerIdx = 0; workerIdx < NUM_WORKERS; workerIdx++) {
      assertEquals(initialGlobalMinimumClock + workerIdx, clockManager.getGlobalMinimumClock());

      final String workerId = Integer.toString(workerIdx);
      clockManager.onWorkerDeleted(workerId);
    }

    // if there is no worker, minimum clock is same as initial global minimum clock
    assertEquals(initialGlobalMinimumClock, clockManager.getGlobalMinimumClock());
  }

  /**
   * Tests whether clock manager broadcasts when minimum global clock is updated.
   */
  @Test
  public void testBroadcasting() throws NetworkException {
    final int initialGlobalMinimumClock = clockManager.getGlobalMinimumClock();
    final Map<String, Integer> workerClockMap = new HashMap<>();
    // check whether the number of global minimum updates is same with
    // the number of broadcast messages that are sent from ClockManager.
    // each broadcast message is sent to all workers,
    // so the total message count is numberOfMinClockUpdates(=NUM_WORKERS) * NUM_WORKERS.
    final int expectedNumberOfBroadcastMessages = NUM_WORKERS * NUM_WORKERS;
    final AtomicInteger numberOfBroadcastMessages = new AtomicInteger(0);

    doAnswer(invocation -> {
      final byte[] data = invocation.getArgumentAt(2, byte[].class);
      final AvroClockMsg sendMsg = codec.decode(data);

      if (sendMsg.getType() == ClockMsgType.BroadcastMinClockMsg) {
        // check broadcast count is same as number of minimum clock updates
        numberOfBroadcastMessages.incrementAndGet();
      }
      return null;
    }).when(mockMasterSideCentCommMsgSender).send(anyString(), anyString(), anyObject());

    // add workers first to set same initial clock to all workers
    for (int workerIdx = 0; workerIdx < NUM_WORKERS; workerIdx++) {
      final String workerId = Integer.toString(workerIdx);
      clockManager.onWorkerAdded(false, workerId);
    }

    for (int workerIdx = 0; workerIdx < NUM_WORKERS; workerIdx++) {
      final String workerId = Integer.toString(workerIdx);
      // tick clock its worker id times
      for (int i = 0; i < workerIdx; i++) {
        final AvroClockMsg avroClockMsg =
            AvroClockMsg.newBuilder()
                .setType(ClockMsgType.TickMsg)
                .setTickMsg(TickMsg.newBuilder().build())
                .build();
        final byte[] data = codec.encode(avroClockMsg);
        mockSlaveSideCentCommMsgSender.send(workerId, data);
      }
      workerClockMap.put(workerId, initialGlobalMinimumClock + workerIdx);
    }

    for (int workerIdx = 0; workerIdx < NUM_WORKERS; workerIdx++) {

      // tick workers with minimum clock
      for (int i = 0; i <= workerIdx; i++) {
        final String workerId = Integer.toString(i);
        final int currentClock = workerClockMap.get(workerId);

        final AvroClockMsg avroClockMsg =
            AvroClockMsg.newBuilder()
                .setType(ClockMsgType.TickMsg)
                .setTickMsg(TickMsg.newBuilder().build())
                .build();
        final byte[] data = codec.encode(avroClockMsg);
        mockSlaveSideCentCommMsgSender.send(workerId, data);
        workerClockMap.put(workerId, currentClock + 1);
      }

      // minimum clock is changed here because all the minimum clocks are ticked
    }

    assertEquals(expectedNumberOfBroadcastMessages, numberOfBroadcastMessages.intValue());

    final int expectedMinimumClock = Collections.min(workerClockMap.values());
    assertEquals(expectedMinimumClock, clockManager.getGlobalMinimumClock());
  }

  private CentCommMsg getTestCentCommMsg(final String workerId, final byte[] data) {
    return CentCommMsg.newBuilder()
        .setSourceId(workerId)
        .setClientClassName(ClockManager.CENT_COMM_CLIENT_NAME)
        .setData(ByteBuffer.wrap(data))
        .build();
  }
}
