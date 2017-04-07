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
package edu.snu.cay.services.ps.metric;

import edu.snu.cay.common.centcomm.slave.SlaveSideCentCommMsgSender;
import edu.snu.cay.common.metric.MetricsHandler;
import edu.snu.cay.common.metric.MetricsMsgSender;
import edu.snu.cay.common.metric.avro.Metrics;
import edu.snu.cay.services.ps.metric.avro.ServerMetrics;
import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import java.util.logging.Logger;

/**
 * A MetricsHandler implementation that sends a ServerMetricsMsg via Cent Comm Service.
 * The metrics are set via MetricsHandler. The other message parts must be
 * set via the setters for each server window.
 * The built MetricsMessage is passed through {@code send()} when sending the network message.
 */
@NotThreadSafe
public final class ServerMetricsMsgSender implements MetricsHandler, MetricsMsgSender<ServerMetrics> {
  private static final Logger LOG = Logger.getLogger(ServerMetricsMsgSender.class.getName());

  private final ServerMetricsMsgCodec metricsMessageCodec;
  private Metrics metrics;
  private final SlaveSideCentCommMsgSender slaveSideCentCommMsgSender;

  @Inject
  private ServerMetricsMsgSender(final ServerMetricsMsgCodec metricsMessageCodec,
                                 final SlaveSideCentCommMsgSender slaveSideCentCommMsgSender) {
    this.metricsMessageCodec = metricsMessageCodec;
    this.slaveSideCentCommMsgSender = slaveSideCentCommMsgSender;
  }

  @Override
  public void onNext(final Metrics metricsParam) {
    this.metrics = metricsParam;
  }

  @Override
  public Metrics getMetrics() {
    return metrics;
  }

  @Override
  public void send(final ServerMetrics message) {
    LOG.entering(ServerMetricsMsgSender.class.getSimpleName(), "send");
    slaveSideCentCommMsgSender.send(ServerConstants.CENT_COMM_CLIENT_NAME, metricsMessageCodec.encode(message));
    LOG.exiting(ServerMetricsMsgSender.class.getSimpleName(), "send");
  }
}
