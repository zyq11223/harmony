#!/bin/sh
# Copyright (C) 2017 Seoul National University
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# EXAMPLE USAGE
# ./submit_mlr.sh -input file://$(pwd)/sample_mlr -test_data_path file://$(pwd)/sample_mlr_test -max_num_epochs 20 -num_mini_batches 10 -num_worker_blocks 10 -init_step_size 0.1 -classes 10 -features 784 -features_per_partition 392 -model_gaussian 0.001 -lambda 0.005 -decay_period 5 -decay_rate 0.9 -num_trainer_threads 1 -server_metric_flush_period_ms 1000

SELF_JAR=`echo ../target/jobserver-*-shaded.jar`

LOGGING_CONFIG='-Djava.util.logging.config.class=edu.snu.cay.utils.LoggingConfig'

CLASSPATH=$YARN_HOME/share/hadoop/common/*:$YARN_HOME/share/hadoop/common/lib/*:$YARN_HOME/share/hadoop/yarn/*:$YARN_HOME/share/hadoop/hdfs/*:$YARN_HOME/share/hadoop/mapreduce/lib/*:$YARN_HOME/share/hadoop/mapreduce/*

YARN_CONF_DIR=$YARN_HOME/etc/hadoop

ALG=edu.snu.cay.dolphin.mlapps.mlr.MLRJob

CMD="java -cp $YARN_CONF_DIR:$SELF_JAR:$CLASSPATH $LOGGING_CONFIG $ALG $*"
echo $CMD
$CMD
