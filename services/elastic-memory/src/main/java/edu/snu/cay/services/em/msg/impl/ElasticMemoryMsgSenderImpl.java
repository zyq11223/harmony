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
package edu.snu.cay.services.em.msg.impl;

import edu.snu.cay.services.em.avro.*;
import edu.snu.cay.services.em.msg.api.ElasticMemoryMsgSender;
import edu.snu.cay.services.em.ns.EMNetworkSetup;
import edu.snu.cay.services.em.trace.HTraceUtils;
import edu.snu.cay.services.em.utils.AvroUtils;
import org.apache.commons.lang.math.LongRange;
import org.htrace.Trace;
import org.htrace.TraceInfo;
import org.htrace.TraceScope;
import org.apache.reef.driver.parameters.DriverIdentifier;
import org.apache.reef.exception.evaluator.NetworkException;
import org.apache.reef.io.network.Connection;
import org.apache.reef.tang.annotations.Parameter;
import org.apache.reef.wake.IdentifierFactory;

import javax.inject.Inject;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;


/**
 * Sender class that uses NetworkConnectionService to
 * send AvroElasticMemoryMessages to the driver and evaluators.
 */
public final class ElasticMemoryMsgSenderImpl implements ElasticMemoryMsgSender {
  private static final Logger LOG = Logger.getLogger(ElasticMemoryMsgSenderImpl.class.getName());

  private static final String SEND_CTRL_MSG = "sendCtrlMsg";
  private static final String SEND_DATA_MSG = "sendDataMsg";
  private static final String SEND_RESULT_MSG = "sendResultMsg";
  private static final String SEND_REGIS_MSG = "sendRegisMsg";

  private final EMNetworkSetup emNetworkSetup;
  private final IdentifierFactory identifierFactory;

  private final String driverId;

  @Inject
  private ElasticMemoryMsgSenderImpl(final EMNetworkSetup emNetworkSetup,
                                     final IdentifierFactory identifierFactory,
                                     @Parameter(DriverIdentifier.class) final String driverId) throws NetworkException {
    this.emNetworkSetup = emNetworkSetup;
    this.identifierFactory = identifierFactory;

    this.driverId = driverId;
  }

  private void send(final String destId, final AvroElasticMemoryMessage msg) {
    LOG.entering(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "send", new Object[]{destId, msg});

    final Connection<AvroElasticMemoryMessage> conn = emNetworkSetup.getConnectionFactory()
        .newConnection(identifierFactory.getNewInstance(destId));
    try {
      conn.open();
      conn.write(msg);
    } catch (final NetworkException ex) {
      throw new RuntimeException("NetworkException", ex);
    }

    LOG.exiting(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "send", new Object[]{destId, msg});
  }


  @Override
  public void sendCtrlMsg(final String destId, final String dataType, final String targetEvalId,
                          final Set<LongRange> idRangeSet, final String operationId, final TraceInfo parentTraceInfo) {
    try (final TraceScope sendCtrlMsgScope = Trace.startSpan(SEND_CTRL_MSG, parentTraceInfo)) {

      LOG.entering(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "sendCtrlMsg",
          new Object[]{destId, dataType, targetEvalId, idRangeSet});

      final List<AvroLongRange> avroLongRangeList = new LinkedList<>();
      for (final LongRange idRange : idRangeSet) {
        avroLongRangeList.add(AvroUtils.convertLongRange(idRange));
      }

      final CtrlMsg ctrlMsg = CtrlMsg.newBuilder()
          .setDataType(dataType)
          .setCtrlMsgType(CtrlMsgType.IdRange)
          .setIdRange(avroLongRangeList)
          .build();

      send(destId,
          AvroElasticMemoryMessage.newBuilder()
              .setType(Type.CtrlMsg)
              .setSrcId(destId)
              .setDestId(targetEvalId)
              .setOperationId(operationId)
              .setTraceInfo(HTraceUtils.toAvro(parentTraceInfo))
              .setCtrlMsg(ctrlMsg)
              .build());

      LOG.exiting(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "sendCtrlMsg",
          new Object[]{destId, dataType, targetEvalId});
    }
  }

  @Override
  public void sendCtrlMsg(final String destId, final String dataType, final String targetEvalId,
                          final int numUnits, final String operationId, final TraceInfo parentTraceInfo) {
    try (final TraceScope sendCtrlMsgScope = Trace.startSpan(SEND_CTRL_MSG, parentTraceInfo)) {

      LOG.entering(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "sendCtrlMsg",
          new Object[]{destId, dataType, targetEvalId, numUnits});

      final CtrlMsg ctrlMsg = CtrlMsg.newBuilder()
          .setDataType(dataType)
          .setCtrlMsgType(CtrlMsgType.NumUnits)
          .setNumUnits(numUnits)
          .build();

      send(destId,
          AvroElasticMemoryMessage.newBuilder()
              .setType(Type.CtrlMsg)
              .setSrcId(destId)
              .setDestId(targetEvalId)
              .setOperationId(operationId)
              .setTraceInfo(HTraceUtils.toAvro(parentTraceInfo))
              .setCtrlMsg(ctrlMsg)
              .build());

      LOG.exiting(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "sendCtrlMsg",
          new Object[]{destId, dataType, targetEvalId});
    }
  }

  @Override
  public void sendDataMsg(final String destId, final String dataType, final List<UnitIdPair> unitIdPairList,
                          final String operationId, final TraceInfo parentTraceInfo) {
    try (final TraceScope sendDataMsgScope = Trace.startSpan(SEND_DATA_MSG, parentTraceInfo)) {

      LOG.entering(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "sendDataMsg",
          new Object[]{destId, dataType});

      final DataMsg dataMsg = DataMsg.newBuilder()
          .setDataType(dataType)
          .setUnits(unitIdPairList)
          .build();

      send(destId,
          AvroElasticMemoryMessage.newBuilder()
              .setType(Type.DataMsg)
              .setSrcId(emNetworkSetup.getMyId().toString())
              .setDestId(destId)
              .setOperationId(operationId)
              .setTraceInfo(HTraceUtils.toAvro(parentTraceInfo))
              .setDataMsg(dataMsg)
              .build());

      LOG.exiting(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "sendDataMsg",
          new Object[]{destId, dataType});
    }
  }

  @Override
  public void sendResultMsg(final boolean success, final String dataType, final Set<LongRange> idRangeSet,
                            final String operationId, final TraceInfo parentTraceInfo) {
    try (final TraceScope sendResultMsgScope = Trace.startSpan(SEND_RESULT_MSG, parentTraceInfo)) {

      LOG.entering(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "sendResultMsg",
          new Object[]{success, idRangeSet, operationId});

      final List<AvroLongRange> avroLongRangeList = new LinkedList<>();
      for (final LongRange idRange : idRangeSet) {
        avroLongRangeList.add(AvroUtils.convertLongRange(idRange));
      }

      final ResultMsg resultMsg = ResultMsg.newBuilder()
          .setResult(success ? Result.SUCCESS : Result.FAILURE)
          .setDataType(dataType)
          .setIdRange(avroLongRangeList)
          .build();

      send(driverId,
          AvroElasticMemoryMessage.newBuilder()
              .setType(Type.ResultMsg)
              .setSrcId(emNetworkSetup.getMyId().toString())
              .setDestId(driverId)
              .setOperationId(operationId)
              .setTraceInfo(HTraceUtils.toAvro(parentTraceInfo))
              .setResultMsg(resultMsg)
              .build());

      LOG.exiting(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "sendResultMsg",
          new Object[]{success, idRangeSet, operationId});

    }
  }

  @Override
  public void sendRegisMsg(final String dataType, final long unitStartId, final long unitEndId,
                           final TraceInfo parentTraceInfo) {
    try (final TraceScope sendRegisMsgScope = Trace.startSpan(SEND_REGIS_MSG, parentTraceInfo)) {

      LOG.entering(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "sendRegisMsg",
          new Object[]{dataType, unitStartId, unitEndId});

      final RegisMsg regisMsg = RegisMsg.newBuilder()
          .setDataType(dataType)
          .setIdRange(AvroLongRange.newBuilder()
              .setMin(unitStartId)
              .setMax(unitEndId)
              .build())
          .build();

      send(driverId,
          AvroElasticMemoryMessage.newBuilder()
              .setType(Type.RegisMsg)
              .setSrcId(emNetworkSetup.getMyId().toString())
              .setDestId(driverId)
              .setRegisMsg(regisMsg)
              .build());

      LOG.exiting(ElasticMemoryMsgSenderImpl.class.getSimpleName(), "sendRegisMsg",
          new Object[]{dataType, unitStartId, unitEndId});
    }
  }
}
