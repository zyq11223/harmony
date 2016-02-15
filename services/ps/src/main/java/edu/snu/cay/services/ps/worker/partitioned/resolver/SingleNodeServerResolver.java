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
package edu.snu.cay.services.ps.worker.partitioned.resolver;

import edu.snu.cay.services.ps.driver.impl.ServerId;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;

/**
 * Resolves to the single server node defined by {@link ServerId}.
 */
public final class SingleNodeServerResolver implements ServerResolver {
  /**
   * Network Connection Service identifier of the server.
   */
  private final String serverId;

  @Inject
  private SingleNodeServerResolver(@Parameter(ServerId.class) final String serverId) {
    this.serverId = serverId;
  }

  @Override
  public String resolve(final int hash) {
    return serverId;
  }
}
