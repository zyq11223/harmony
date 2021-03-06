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
package edu.snu.cay.dolphin.core.client;

import edu.snu.cay.dolphin.core.worker.Trainer;
import edu.snu.cay.services.et.evaluator.api.DataParser;
import edu.snu.cay.services.et.evaluator.api.UpdateFunction;
import edu.snu.cay.services.et.evaluator.impl.VoidUpdateFunction;
import edu.snu.cay.utils.StreamingSerializableCodec;
import org.apache.reef.annotations.audience.ClientSide;
import org.apache.reef.io.network.impl.StreamingCodec;
import org.apache.reef.io.serialization.Codec;
import org.apache.reef.io.serialization.SerializableCodec;
import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.Tang;
import org.apache.reef.tang.annotations.Name;
import org.apache.reef.util.BuilderUtils;

import java.util.LinkedList;
import java.util.List;

/**
 * Job configuration of a Dolphin on ET application.
 *
 * Call {@code newBuilder} and supply classes for {@link Trainer}, {@link UpdateFunction}, {@link DataParser}, codecs,
 * parameters, configuration for workers, and configuration for servers.
 * {@link SerializableCodec}s are used in case codec classes are not given. Parameter classes are also optional.
 * Use with {@link ETDolphinLauncher#launch(String, String[], ETDolphinConfiguration)} to launch application.
 */
@ClientSide
public final class ETDolphinConfiguration {
  private final Class<? extends Trainer> trainerClass;
  private final boolean hasInputDataKey;
  private final Class<? extends DataParser> inputParserClass;
  private final Class<? extends StreamingCodec> inputKeyCodecClass;
  private final Class<? extends StreamingCodec> inputValueCodecClass;
  private final Class<? extends UpdateFunction> modelUpdateFunctionClass;
  private final Class<? extends StreamingCodec> modelKeyCodecClass;
  private final Class<? extends StreamingCodec> modelValueCodecClass;
  private final Class<? extends Codec> modelUpdateValueCodecClass;

  // local model table is optional
  private final boolean hasLocalModelTable;
  private final Class<? extends UpdateFunction> localModelUpdateFunctionClass;
  private final Class<? extends StreamingCodec> localModelKeyCodecClass;
  private final Class<? extends StreamingCodec> localModelValueCodecClass;
  private final Class<? extends Codec> localModelUpdateValueCodecClass;

  private final List<Class<? extends Name<?>>> parameterClassList;
  private final Configuration workerConfiguration;
  private final Configuration serverConfiguration;

  private ETDolphinConfiguration(final Class<? extends Trainer> trainerClass,
                                 final boolean hasInputDataKey,
                                 final Class<? extends DataParser> inputParserClass,
                                 final Class<? extends StreamingCodec> inputKeyCodecClass,
                                 final Class<? extends StreamingCodec> inputValueCodecClass,
                                 final Class<? extends UpdateFunction> modelUpdateFunctionClass,
                                 final Class<? extends StreamingCodec> modelKeyCodecClass,
                                 final Class<? extends StreamingCodec> modelValueCodecClass,
                                 final Class<? extends Codec> modelUpdateValueCodecClass,
                                 final boolean hasLocalModelTable,
                                 final Class<? extends UpdateFunction> localModelUpdateFunctionClass,
                                 final Class<? extends StreamingCodec> localModelKeyCodecClass,
                                 final Class<? extends StreamingCodec> localModelValueCodecClass,
                                 final Class<? extends Codec> localModelUpdateValueCodecClass,
                                 final List<Class<? extends Name<?>>> parameterClassList,
                                 final Configuration workerConfiguration,
                                 final Configuration serverConfiguration) {
    this.trainerClass = trainerClass;
    this.hasInputDataKey = hasInputDataKey;
    this.inputParserClass = inputParserClass;
    this.inputKeyCodecClass = inputKeyCodecClass;
    this.inputValueCodecClass = inputValueCodecClass;
    this.modelUpdateFunctionClass = modelUpdateFunctionClass;
    this.modelKeyCodecClass = modelKeyCodecClass;
    this.modelValueCodecClass = modelValueCodecClass;
    this.modelUpdateValueCodecClass = modelUpdateValueCodecClass;
    this.hasLocalModelTable = hasLocalModelTable;
    this.localModelUpdateFunctionClass = localModelUpdateFunctionClass;
    this.localModelKeyCodecClass = localModelKeyCodecClass;
    this.localModelValueCodecClass = localModelValueCodecClass;
    this.localModelUpdateValueCodecClass = localModelUpdateValueCodecClass;
    this.parameterClassList = parameterClassList;
    this.workerConfiguration = workerConfiguration;
    this.serverConfiguration = serverConfiguration;
  }

  public Class<? extends Trainer> getTrainerClass() {
    return trainerClass;
  }

  public boolean hasInputDataKey() {
    return hasInputDataKey;
  }

  public Class<? extends DataParser> getInputParserClass() {
    return inputParserClass;
  }

  public Class<? extends StreamingCodec> getInputKeyCodecClass() {
    return inputKeyCodecClass;
  }

  public Class<? extends StreamingCodec> getInputValueCodecClass() {
    return inputValueCodecClass;
  }

  public Class<? extends UpdateFunction> getModelUpdateFunctionClass() {
    return modelUpdateFunctionClass;
  }

  public Class<? extends StreamingCodec> getModelKeyCodecClass() {
    return modelKeyCodecClass;
  }

  public Class<? extends StreamingCodec> getModelValueCodecClass() {
    return modelValueCodecClass;
  }

  public Class<? extends Codec> getModelUpdateValueCodecClass() {
    return modelUpdateValueCodecClass;
  }

  public boolean hasLocalModelTable() {
    return hasLocalModelTable;
  }

  public Class<? extends UpdateFunction> getLocalModelUpdateFunctionClass() {
    return localModelUpdateFunctionClass;
  }

  public Class<? extends StreamingCodec> getLocalModelKeyCodecClass() {
    return localModelKeyCodecClass;
  }

  public Class<? extends StreamingCodec> getLocalModelValueCodecClass() {
    return localModelValueCodecClass;
  }

  public Class<? extends Codec> getLocalModelUpdateValueCodecClass() {
    return localModelUpdateValueCodecClass;
  }

  public List<Class<? extends Name<?>>> getParameterClassList() {
    return parameterClassList;
  }

  public Configuration getWorkerConfiguration() {
    return workerConfiguration;
  }

  public Configuration getServerConfiguration() {
    return serverConfiguration;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder implements org.apache.reef.util.Builder<ETDolphinConfiguration> {
    private Class<? extends Trainer> trainerClass;
    private boolean hasInputDataKey = false;
    private Class<? extends DataParser> inputParserClass;
    private Class<? extends StreamingCodec> inputKeyCodecClass = StreamingSerializableCodec.class;
    private Class<? extends StreamingCodec> inputValueCodecClass = StreamingSerializableCodec.class;

    private Class<? extends UpdateFunction> modelUpdateFunctionClass;
    private Class<? extends StreamingCodec> modelKeyCodecClass = StreamingSerializableCodec.class;
    private Class<? extends StreamingCodec> modelValueCodecClass = StreamingSerializableCodec.class;
    private Class<? extends Codec> modelUpdateValueCodecClass = SerializableCodec.class;

    // local model table is optional
    private boolean hasLocalModelTable = false;
    private Class<? extends UpdateFunction> localModelUpdateFunctionClass = VoidUpdateFunction.class;
    private Class<? extends StreamingCodec> localModelKeyCodecClass = StreamingSerializableCodec.class;
    private Class<? extends StreamingCodec> localModelValueCodecClass = StreamingSerializableCodec.class;
    private Class<? extends Codec> localModelUpdateValueCodecClass = SerializableCodec.class;

    private List<Class<? extends Name<?>>> parameterClassList = new LinkedList<>();
    private Configuration workerConfiguration = Tang.Factory.getTang().newConfigurationBuilder().build();
    private Configuration serverConfiguration = Tang.Factory.getTang().newConfigurationBuilder().build();

    public Builder setTrainerClass(final Class<? extends Trainer> trainerClass) {
      this.trainerClass = trainerClass;
      return this;
    }

    public Builder setHasInputDataKey() {
      this.hasInputDataKey = true;
      return this;
    }

    public Builder setInputParserClass(final Class<? extends DataParser> inputParserClass) {
      this.inputParserClass = inputParserClass;
      return this;
    }

    public Builder setInputKeyCodecClass(final Class<? extends StreamingCodec> inputKeyCodecClass) {
      this.inputKeyCodecClass = inputKeyCodecClass;
      return this;
    }

    public Builder setInputValueCodecClass(final Class<? extends StreamingCodec> inputValueCodecClass) {
      this.inputValueCodecClass = inputValueCodecClass;
      return this;
    }

    public Builder setModelUpdateFunctionClass(final Class<? extends UpdateFunction> modelUpdateFunctionClass) {
      this.modelUpdateFunctionClass = modelUpdateFunctionClass;
      return this;
    }

    public Builder setModelKeyCodecClass(final Class<? extends StreamingCodec> modelKeyCodecClass) {
      this.modelKeyCodecClass = modelKeyCodecClass;
      return this;
    }

    public Builder setModelValueCodecClass(final Class<? extends StreamingCodec> modelValueCodecClass) {
      this.modelValueCodecClass = modelValueCodecClass;
      return this;
    }

    public Builder setModelUpdateValueCodecClass(final Class<? extends Codec> modelUpdateValueCodecClass) {
      this.modelUpdateValueCodecClass = modelUpdateValueCodecClass;
      return this;
    }

    public Builder setHasLocalModelTable() {
      this.hasLocalModelTable = true;
      return this;
    }

    public Builder setLocalModelUpdateFunctionClass(
        final Class<? extends UpdateFunction> localModelUpdateFunctionClass) {
      this.localModelUpdateFunctionClass = localModelUpdateFunctionClass;
      return this;
    }

    public Builder setLocalModelKeyCodecClass(final Class<? extends StreamingCodec> localModelKeyCodecClass) {
      this.localModelKeyCodecClass = localModelKeyCodecClass;
      return this;
    }

    public Builder setLocalModelValueCodecClass(final Class<? extends StreamingCodec> localModelValueCodecClass) {
      this.localModelValueCodecClass = localModelValueCodecClass;
      return this;
    }

    public Builder setLocalModelUpdateValueCodecClass(final Class<? extends Codec> localModelUpdateValueCodecClass) {
      this.localModelUpdateValueCodecClass = localModelUpdateValueCodecClass;
      return this;
    }

    public Builder addParameterClass(final Class<? extends Name<?>> parameterClass) {
      this.parameterClassList.add(parameterClass);
      return this;
    }

    public Builder setWorkerConfiguration(final Configuration workerConfiguration) {
      this.workerConfiguration = workerConfiguration;
      return this;
    }

    public Builder setServerConfiguration(final Configuration serverConfiguration) {
      this.serverConfiguration = serverConfiguration;
      return this;
    }

    @Override
    public ETDolphinConfiguration build() {
      BuilderUtils.notNull(trainerClass);
      BuilderUtils.notNull(inputParserClass);
      BuilderUtils.notNull(modelUpdateFunctionClass);

      return new ETDolphinConfiguration(trainerClass,
          hasInputDataKey, inputParserClass, inputKeyCodecClass, inputValueCodecClass,
          modelUpdateFunctionClass, modelKeyCodecClass, modelValueCodecClass, modelUpdateValueCodecClass,
          hasLocalModelTable, localModelUpdateFunctionClass, localModelKeyCodecClass, localModelValueCodecClass,
          localModelUpdateValueCodecClass, parameterClassList, workerConfiguration, serverConfiguration);
    }
  }
}
