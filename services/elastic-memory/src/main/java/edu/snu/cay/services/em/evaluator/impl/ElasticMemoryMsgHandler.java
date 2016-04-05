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
package edu.snu.cay.services.em.evaluator.impl;

import edu.snu.cay.services.em.avro.*;
import edu.snu.cay.services.em.evaluator.api.RemoteAccessibleMemoryStore;
import edu.snu.cay.services.em.msg.api.ElasticMemoryMsgSender;
import edu.snu.cay.services.em.serialize.Serializer;
import edu.snu.cay.services.em.utils.AvroUtils;
import edu.snu.cay.utils.LongRangeUtils;
import edu.snu.cay.utils.trace.HTraceUtils;
import edu.snu.cay.utils.SingleMessageExtractor;
import org.apache.commons.lang.math.LongRange;
import org.apache.reef.annotations.audience.Private;
import org.apache.reef.util.Optional;
import org.htrace.Trace;
import org.htrace.TraceInfo;
import org.htrace.TraceScope;
import org.apache.reef.annotations.audience.EvaluatorSide;
import org.apache.reef.io.network.Message;
import org.apache.reef.io.serialization.Codec;
import org.apache.reef.tang.InjectionFuture;
import org.apache.reef.wake.EventHandler;

import javax.inject.Inject;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Evaluator-side message handler.
 * Processes control message from the driver and data message from
 * other evaluators.
 */
@EvaluatorSide
@Private
public final class ElasticMemoryMsgHandler implements EventHandler<Message<AvroElasticMemoryMessage>> {
  private static final Logger LOG = Logger.getLogger(ElasticMemoryMsgHandler.class.getName());

  private static final String ON_DATA_MSG = "onDataMsg";
  private static final String ON_CTRL_MSG = "onCtrlMsg";
  private static final String ON_UPDATE_MSG = "onUpdateMsg";

  private final RemoteAccessibleMemoryStore memoryStore;
  private final OperationResultAggregator resultAggregator;
  private final Serializer serializer;
  private final InjectionFuture<ElasticMemoryMsgSender> sender;

  /**
   * Keeps the information for applying the updates after the data is transferred.
   * This allows the data to be accessible while the migration is going on.
   */
  private final ConcurrentMap<String, Update> pendingUpdates = new ConcurrentHashMap<>();

  /**
   * Keeps the moving ranges, so we don't include the units to the migration if they are already moving.
   */
  private final Set<LongRange> movingRanges = Collections.synchronizedSet(new HashSet<LongRange>());

  @Inject
  private ElasticMemoryMsgHandler(final RemoteAccessibleMemoryStore memoryStore,
                                  final OperationResultAggregator resultAggregator,
                                  final InjectionFuture<ElasticMemoryMsgSender> sender,
                                  final Serializer serializer) {
    this.memoryStore = memoryStore;
    this.resultAggregator = resultAggregator;
    this.serializer = serializer;
    this.sender = sender;
  }

  @Override
  public void onNext(final Message<AvroElasticMemoryMessage> msg) {
    LOG.entering(ElasticMemoryMsgHandler.class.getSimpleName(), "onNext", msg);

    final AvroElasticMemoryMessage innerMsg = SingleMessageExtractor.extract(msg);
    switch (innerMsg.getType()) {
    case RemoteOpMsg:
      onRemoteOpMsg(innerMsg);
      break;

    case RemoteOpResultMsg:
      onRemoteOpResultMsg(innerMsg);
      break;

    case DataMsg:
      onDataMsg(innerMsg);
      break;

    case CtrlMsg:
      onCtrlMsg(innerMsg);
      break;

    case UpdateMsg:
      onUpdateMsg(innerMsg);
      break;

    default:
      throw new RuntimeException("Unexpected message: " + msg);
    }

    LOG.exiting(ElasticMemoryMsgHandler.class.getSimpleName(), "onNext", msg);
  }

  /**
   * Handles the data operation sent from the remote memory store.
   */
  private <T> void onRemoteOpMsg(final AvroElasticMemoryMessage msg) {
    LOG.log(Level.INFO, "onRemoteOpMsg: {0}", msg);

    final RemoteOpMsg remoteOpMsg = msg.getRemoteOpMsg();
    final String origEvalId = remoteOpMsg.getOrigEvalId().toString();
    final DataOpType operationType = remoteOpMsg.getOpType();
    final String dataType = remoteOpMsg.getDataType().toString();
    final List<AvroLongRange> avroLongRangeList = remoteOpMsg.getDataKeyRanges();
    final List<UnitIdPair> dataKVPairList = remoteOpMsg.getDataKVPairList();
    final String operationId = msg.getOperationId().toString();

    final List<LongRange> dataKeyRanges = new ArrayList<>(avroLongRangeList.size());
    for (final AvroLongRange avroRange : avroLongRangeList) {
      dataKeyRanges.add(AvroUtils.fromAvroLongRange(avroRange));
    }

    final Optional<SortedMap<Long, T>> dataKeyValueMap;
    if (operationType.equals(DataOpType.PUT)) {
      final SortedMap<Long, T> dataMap = new TreeMap<>();
      dataKeyValueMap = Optional.of(dataMap);

      // decode data values
      final Codec codec = serializer.getCodec(dataType);
      for (final UnitIdPair dataKVPair : dataKVPairList) {
        final T dataValue = (T) codec.decode(dataKVPair.getUnit().array());
        dataMap.put(dataKVPair.getId(), dataValue);
      }
    } else {
      dataKeyValueMap = Optional.empty();
    }

    final DataOperation<T> operation = new DataOperation<>(Optional.of(origEvalId),
        operationId, operationType, dataType, dataKeyRanges, dataKeyValueMap);

    // enqueue operation requested from remote store into memory store
    memoryStore.onNext(operation);
  }

  /**
   * Handles the result of data operation sent from the remote memory store.
   */
  private void onRemoteOpResultMsg(final AvroElasticMemoryMessage msg) {
    LOG.log(Level.INFO, "onRemoteOpResultMsg: {0}", msg);

    final RemoteOpResultMsg remoteOpResultMsg = msg.getRemoteOpResultMsg();
    final String operationId = msg.getOperationId().toString();
    final List<UnitIdPair> dataKVPairList = remoteOpResultMsg.getDataKVPairList();
    final List<AvroLongRange> failedRanges = remoteOpResultMsg.getFailedKeyRanges();

    resultAggregator.submitRemoteResult(operationId, dataKVPairList, failedRanges);
  }

  /**
   * Puts the data message contents into own memory store.
   */
  private void onDataMsg(final AvroElasticMemoryMessage msg) {
    try (final TraceScope onDataMsgScope = Trace.startSpan(ON_DATA_MSG, HTraceUtils.fromAvro(msg.getTraceInfo()))) {

      final DataMsg dataMsg = msg.getDataMsg();
      final String dataType = dataMsg.getDataType().toString();
      final Codec codec = serializer.getCodec(dataType);
      final String operationId = msg.getOperationId().toString();

      // Compress the ranges so the number of ranges minimizes.
      final SortedSet<Long> newIds = new TreeSet<>();
      for (final UnitIdPair unitIdPair : dataMsg.getUnits()) {
        final long id = unitIdPair.getId();
        newIds.add(id);
      }
      final Set<LongRange> longRangeSet = LongRangeUtils.generateDenseLongRanges(newIds);

      // store the items in the pendingUpdates, so the items will be added later.
      pendingUpdates.put(operationId, new Add(dataType, codec, dataMsg.getUnits(), longRangeSet));
      sender.get().sendDataAckMsg(longRangeSet, operationId, TraceInfo.fromSpan(onDataMsgScope.getSpan()));
    }
  }

  /**
   * Creates a data message using the control message contents, and then
   * sends the data message to the correct evaluator.
   */
  private void onCtrlMsg(final AvroElasticMemoryMessage msg) {
    try (final TraceScope onCtrlMsgScope = Trace.startSpan(ON_CTRL_MSG, HTraceUtils.fromAvro(msg.getTraceInfo()))) {
      final CtrlMsgType ctrlMsgType = msg.getCtrlMsg().getCtrlMsgType();
      switch (ctrlMsgType) {
      case IdRange:
        onCtrlMsgIdRange(msg, onCtrlMsgScope);
        break;

      case NumUnits:
        onCtrlMsgNumUnits(msg, onCtrlMsgScope);
        break;

      default:
        throw new RuntimeException("Unexpected control message type: " + ctrlMsgType);
      }
    }
  }

  /**
   * Creates a data message using the id ranges specified in the given control message and send it.
   */
  private void onCtrlMsgIdRange(final AvroElasticMemoryMessage msg,
                                final TraceScope parentTraceInfo) {
    final String operationId = msg.getOperationId().toString();
    final CtrlMsg ctrlMsg = msg.getCtrlMsg();
    final String dataType = ctrlMsg.getDataType().toString();
    final Codec codec = serializer.getCodec(dataType);

    // pack the extracted items into a single list for message transmission
    // the identifiers for each item are included with the item itself as an UnitIdPair
    final List<UnitIdPair> unitIdPairList = new ArrayList<>();

    // keep the ids of the items to be deleted later
    final SortedSet<Long> ids = new TreeSet<>();

    // extract all data items from my memory store that correspond to
    // the control message's id specification
    // TODO #15: this loop may be creating a gigantic message, and may cause memory problems
    for (final AvroLongRange avroLongRange : ctrlMsg.getIdRange()) {
      final Map<Long, Object> idObjectMap =
          memoryStore.getRange(dataType, avroLongRange.getMin(), avroLongRange.getMax());
      for (final Map.Entry<Long, Object> idObject : idObjectMap.entrySet()) {
        final long id = idObject.getKey();
        if (!isMoving(id)) {
          // Include the units only if they are not moving already.
          final UnitIdPair unitIdPair = UnitIdPair.newBuilder()
              .setUnit(ByteBuffer.wrap(codec.encode(idObject.getValue())))
              .setId(id)
              .build();
          unitIdPairList.add(unitIdPair);
          ids.add(id);
        }
      }
    }

    // If there is no data to move, send failure message instead of sending an empty DataMsg.
    if (ids.size() == 0) {
      final String myId = msg.getDestId().toString();
      final String reason = new StringBuilder()
          .append("No data is movable in ").append(myId)
          .append(" of type ").append(dataType)
          .append(". Requested ranges: ").append(Arrays.toString(ctrlMsg.getIdRange().toArray()))
          .toString();
      sender.get().sendFailureMsg(operationId, reason, TraceInfo.fromSpan(parentTraceInfo.getSpan()));
      return;
    }

    final Set<LongRange> ranges = LongRangeUtils.generateDenseLongRanges(ids);

    // The items of the ids will be removed after the migration succeeds.
    pendingUpdates.put(operationId, new Remove(dataType, ranges));

    // Keep the moving ranges to avoid duplicate request, before the data is removed from the MemoryStore.
    movingRanges.addAll(ranges);

    sender.get().sendDataMsg(msg.getDestId().toString(), ctrlMsg.getDataType().toString(), unitIdPairList,
        operationId, TraceInfo.fromSpan(parentTraceInfo.getSpan()));
  }

  /**
   * Creates a data message using the number of units specified in the given control message and send it.
   * This method simply picks the first n entries that are returned by Map.entrySet().iterator().
   */
  private void onCtrlMsgNumUnits(final AvroElasticMemoryMessage msg,
                                 final TraceScope parentTraceInfo) {
    final String operationId = msg.getOperationId().toString();
    final CtrlMsg ctrlMsg = msg.getCtrlMsg();
    final String dataType = ctrlMsg.getDataType().toString();
    final Codec codec = serializer.getCodec(dataType);
    final int numUnits = ctrlMsg.getNumUnits();

    // fetch all items of the given data type from my memory store
    // TODO #15: this clones the entire map of the given data type, and may cause memory problems
    final Map<Long, Object> dataMap = memoryStore.getAll(dataType);
    final List<UnitIdPair> unitIdPairList = new ArrayList<>(Math.min(numUnits, dataMap.size()));

    // keep the ids of the items to be deleted later
    final SortedSet<Long> ids = new TreeSet<>();

    // TODO #15: this loop may be creating a gigantic message, and may cause memory problems
    for (final Map.Entry<Long, Object> entry : dataMap.entrySet()) {
      if (unitIdPairList.size() >= numUnits) {
        break;
      }

      final Long id = entry.getKey();
      if (!isMoving(id)) {
        // Include the units only if they are not moving already.
        final UnitIdPair unitIdPair = UnitIdPair.newBuilder()
            .setUnit(ByteBuffer.wrap(codec.encode(entry.getValue())))
            .setId(id)
            .build();
        unitIdPairList.add(unitIdPair);
        ids.add(id);
      }
    }

    // If there is no data to move, send failure message instead of sending an empty DataMsg.
    if (ids.size() == 0) {
      final String myId = msg.getDestId().toString();
      final String reason = new StringBuilder()
          .append("No data is movable in ").append(myId)
          .append(" of type ").append(dataType)
          .append(". Requested numUnits: ").append(numUnits)
          .toString();
      sender.get().sendFailureMsg(operationId, reason, TraceInfo.fromSpan(parentTraceInfo.getSpan()));
      return;
    }

    final Set<LongRange> ranges = LongRangeUtils.generateDenseLongRanges(ids);

    // The items of the ids will be removed after the migration succeeds.
    pendingUpdates.put(operationId, new Remove(dataType, ranges));

    // Keep the moving ranges to avoid duplicate request, before the data is removed from the MemoryStore.
    movingRanges.addAll(ranges);

    sender.get().sendDataMsg(msg.getDestId().toString(), ctrlMsg.getDataType().toString(), unitIdPairList,
        operationId, TraceInfo.fromSpan(parentTraceInfo.getSpan()));
  }

  /**
   * Applies the pending updates (add, remove) to the MemoryStore,
   * and sends the ACK message to the Driver.
   */
  private void onUpdateMsg(final AvroElasticMemoryMessage msg) {
    try (final TraceScope onUpdateMsgScope =
             Trace.startSpan(ON_UPDATE_MSG, HTraceUtils.fromAvro(msg.getTraceInfo()))) {
      final String operationId = msg.getOperationId().toString();
      final UpdateResult updateResult;

      final Update update = pendingUpdates.remove(operationId);
      if (update == null) {
        LOG.log(Level.WARNING, "The update with id {0} seems already handled.", operationId);
        return;
      }

      switch (update.getType()) {

      case ADD:
        // The receiver adds the data into its MemoryStore.
        update.apply(memoryStore);
        updateResult = UpdateResult.RECEIVER_UPDATED;
        break;

      case REMOVE:
        // The sender removes the data from its MemoryStore.
        update.apply(memoryStore);
        movingRanges.removeAll(update.getRanges());
        updateResult = UpdateResult.SENDER_UPDATED;
        break;
      default:
        throw new RuntimeException("Undefined Message type of Update: " + update);
      }

      sender.get().sendUpdateAckMsg(operationId, updateResult, TraceInfo.fromSpan(onUpdateMsgScope.getSpan()));
    }
  }

  /**
   * Checks whether the unit is moving in order to avoid the duplicate request for the ranges that are moving already.
   * @param id Identifier of unit to check.
   * @return {@code true} if the {@code id} is inside a range that is moving.
   */
  private boolean isMoving(final long id) {
    // We need to synchronize manually for using SynchronizedSet.iterator().
    synchronized (movingRanges) {
      final Iterator<LongRange> iterator = movingRanges.iterator();
      while (iterator.hasNext()) {
        final LongRange range = iterator.next();
        if (range.containsLong(id)) {
          return true;
        }
      }
    }
    return false;
  }
}