/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.vizit.vpoc.exercise;

import io.vizit.vpoc.common.datatypes.TaxiFare;
import io.vizit.vpoc.common.datatypes.TaxiRide;
import io.vizit.vpoc.common.sources.TaxiFareSource;
import io.vizit.vpoc.common.sources.TaxiRideSource;
import io.vizit.vpoc.common.utils.ExerciseBase;
import io.vizit.vpoc.common.utils.MissingSolutionException;
import org.apache.flink.api.common.state.StateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.RichCoFlatMapFunction;
import org.apache.flink.util.Collector;

/**
 * The "Stateful Enrichment" exercise of the Flink training in the docs.
 * https://github.com/apache/flink-training/tree/master/rides-and-fares
 *
 * <p>The goal for this exercise is to enrich TaxiRides with fare information.
 *
 * <p>Parameters:
 * -rides path-to-input-file
 * -fares path-to-input-file
 */
public class RidesAndFaresExercise extends ExerciseBase {

	/**
	 * Main method.
	 *
	 * <p>Parameters:
	 * -rides path-to-input-file
	 * -fares path-to-input-file
	 *
	 * @throws Exception which occurs during job execution.
	 */
	public static void main(String[] args) throws Exception {

		ParameterTool params = ParameterTool.fromArgs(args);
		final String ridesFile = params.get("rides", PATH_TO_RIDE_DATA);
		final String faresFile = params.get("fares", PATH_TO_FARE_DATA);

		final int delay = 60;					// at most 60 seconds of delay
		final int servingSpeedFactor = 1800; 	// 30 minutes worth of events are served every second

		// set up streaming execution environment
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setParallelism(ExerciseBase.parallelism);

		DataStream<TaxiRide> rides = env
				.addSource(rideSourceOrTest(new TaxiRideSource(ridesFile, delay, servingSpeedFactor)))
				.filter((TaxiRide ride) -> ride.isStart)
				.keyBy((TaxiRide ride) -> ride.rideId);

		DataStream<TaxiFare> fares = env
				.addSource(fareSourceOrTest(new TaxiFareSource(faresFile, delay, servingSpeedFactor)))
				.keyBy((TaxiFare fare) -> fare.rideId);

		DataStream<Tuple2<TaxiRide, TaxiFare>> enrichedRides = rides
				.connect(fares)
				.flatMap(new EnrichmentFunction());

		printOrTest(enrichedRides);

		env.execute("Join Rides with Fares (java RichCoFlatMap)");
	}

	public static class EnrichmentFunction extends RichCoFlatMapFunction<TaxiRide, TaxiFare, Tuple2<TaxiRide, TaxiFare>> {
        ValueState<TaxiRide> rideState;
        ValueState<TaxiFare> fareState;

		@Override
		public void open(Configuration config) throws Exception {
//			throw new MissingSolutionException();
            // 初始化状态
            rideState = getRuntimeContext().getState(new ValueStateDescriptor<TaxiRide>("rideState", TaxiRide.class));
            fareState = getRuntimeContext().getState(new ValueStateDescriptor<TaxiFare>("fareState", TaxiFare.class));
		}

        @Override
        public void flatMap1(TaxiRide ride, Collector<Tuple2<TaxiRide, TaxiFare>> out) throws Exception {
		    // 来一个ride，去匹配fair
            if (fareState.value() != null) {
                out.collect(new Tuple2<>(ride, fareState.value()));
                fareState.clear();
                return;
            }
            rideState.update(ride);
        }

        @Override
        public void flatMap2(TaxiFare fare, Collector<Tuple2<TaxiRide, TaxiFare>> out) throws Exception {
            if (rideState.value() != null) {
                out.collect(new Tuple2<>(rideState.value(), fare));
                rideState.clear();
                return;
            }
            fareState.update(fare);
        }
	}
}
