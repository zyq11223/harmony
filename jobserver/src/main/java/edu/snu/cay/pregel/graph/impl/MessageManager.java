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
package edu.snu.cay.pregel.graph.impl;

import edu.snu.cay.pregel.PregelParameters;
import edu.snu.cay.pregel.combiner.MessageCombiner;
import edu.snu.cay.services.et.evaluator.api.Table;
import edu.snu.cay.services.et.evaluator.api.TableAccessor;
import edu.snu.cay.services.et.exceptions.TableNotExistException;
import org.apache.reef.tang.annotations.Parameter;

import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

/**
 * Manage message stores that contain messages from the previous superstep.
 * Determine the incoming message store depending on the state of a worker.
 * It also accumulates messages generated from the current superstep, which is for next superstep.
 *
 * @param <Long> identifier of the vertex
 * @param <M> message type of the vertex
 */
@NotThreadSafe
public final class MessageManager<Long, M> {
  private static final Logger LOG = Logger.getLogger(MessageManager.class.getName());

  private Table<Long, List<M>, M> messageTable1;

  private Table<Long, List<M>, M> messageTable2;

  private boolean tableFlag;

  private final MessageCombiner<Long, M> messageCombiner;

  private Map<Long, M> vertexIdToAccumulatedMsgs = new ConcurrentHashMap<>();

  /**
   * All table commands are added the list for sync the non-blocking methods.
   * At the finish of a single superstep, worker task calls {@link #flushAllMessages()} and gets all futures in it.
   * Then clear it.
   */
  @Inject
  private MessageManager(final TableAccessor tableAccessor,
                         @Parameter(PregelParameters.MessageTableId.class) final String messageTableId,
                         final MessageCombiner<Long, M> messageCombiner) throws TableNotExistException {
    messageTable1 = tableAccessor.getTable(messageTableId + PregelParameters.MSG_TABLE_1_ID_POSTFIX);
    messageTable2 = tableAccessor.getTable(messageTableId + PregelParameters.MSG_TABLE_2_ID_POSTFIX);
    tableFlag = true;
    this.messageCombiner = messageCombiner;
  }

  /**
   * It switches current message table and next message table.
   */
  public void prepareForNextSuperstep() {
    tableFlag = !tableFlag;
  }

  public Table<Long, List<M>, M> getCurrentMessageTable() {
    return tableFlag ? messageTable1 : messageTable2;
  }

  public Table<Long, List<M>, M> getNextMessageTable() {
    return tableFlag ? messageTable2 : messageTable1;
  }

  /**
   * Add a message towards a vertex.
   * All the messages are flushed by {@link #flushAllMessages()} altogether.
   * This method can be used by multi-threads.
   *
   * @param vertexId a vertex id
   * @param message message
   */
  public void addMessage(final Long vertexId, final M message) {
    vertexIdToAccumulatedMsgs.compute(vertexId,
        (id, existingMsg) -> existingMsg == null ? message : messageCombiner.combine(id, existingMsg, message));
  }

  /**
   * Flushes out all messages added by {@link #addMessage}.
   * It returns after receiving ack messages.
   *
   * @return true if there exist ongoing messages
   */
  public boolean flushAllMessages() throws ExecutionException, InterruptedException {
    final boolean messageExist = !vertexIdToAccumulatedMsgs.isEmpty();
    getNextMessageTable().multiUpdate(vertexIdToAccumulatedMsgs).get();
    vertexIdToAccumulatedMsgs.clear();

    return messageExist;
  }
}
