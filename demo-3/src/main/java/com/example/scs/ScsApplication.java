package com.example.scs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.demo.Movie;
import io.confluent.demo.Rating;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

@EnableBinding(Bindings.class)
@SpringBootApplication
public class ScsApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScsApplication.class, args);
	}
}

@Log4j2
@Configuration
class HttpConfiguration {
/*
	@Bean
	RouterFunction<ServerResponse> routes(InteractiveQueryService iqs) {
		return route()
			.GET("/report", request -> ok().syncBody(this.reportFor(iqs)))
			.build();
	}

	Map<Long, RatedMovie> reportFor(InteractiveQueryService iqs) {
		Map<Long, RatedMovie> counts = new HashMap<>();
		ReadOnlyKeyValueStore<Long, RatedMovie> queryableStoreType =
			iqs.getQueryableStore(Bindings.RATED_MOVIES_STORE, QueryableStoreTypes.keyValueStore());
		KeyValueIterator<Long, RatedMovie> all = queryableStoreType.all();
		while (all.hasNext()) {
			KeyValue<Long, RatedMovie> value = all.next();
			counts.put(value.key, value.value);
		}
		return counts;
	}*/


	private final ObjectMapper objectMapper;
	private final BlockingQueue<RatedMovie> ratings = new LinkedBlockingQueue<>();
	private final Flux<RatedMovie> publisher = Flux.create(sink -> {
		while (true) {
			try {
				sink.next(ratings.take());
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	});

	HttpConfiguration(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Bean
	RouterFunction<ServerResponse> routes() {
		return route()
			.GET("/sse", request -> ok().contentType(TEXT_EVENT_STREAM).body(publisher.share(), RatedMovie.class))
			.build();
	}

	@KafkaListener(topics = Bindings.RATED_MOVIES)
	public void listenForNewMessages(String rating) throws Exception {
		RatedMovie ratedMovie = objectMapper.readValue(rating, RatedMovie.class);
		this.ratings.offer(ratedMovie);
	}
}

interface Bindings {
	String RATINGS = "ratings";
	String AVG_RATINGS = "avg-ratings";
	String MOVIES = "movies";
	String AVG_TABLE = "avg-table";
	String RATED_MOVIES = "rated-movies";

	//
	// this is the for the HTTP endpoint
	String RATED_MOVIES_STORE = "rated-movies-store";

	@Input(RATINGS)
	KStream<Long, Rating> ratingsIn();

	@Output(AVG_RATINGS)
	KStream<Long, Double> averageRatingsOut();

	@Input(MOVIES)
	KTable<Long, Movie> moviesIn();

	@Input(AVG_TABLE)
	KTable<Long, Double> averageRatingsIn();

	@Output(RATED_MOVIES)
	KStream<Long, RatedMovie> ratedMoviesOut();
}

@Component
class RatingsAverager {

	@SendTo(Bindings.AVG_RATINGS)
	@StreamListener
	KStream<Long, Double> averageRatingsFor(@Input(Bindings.RATINGS) KStream<Long, Rating> ratings) {
		KGroupedStream<Long, Double> ratingsGrouped =
			ratings
				.mapValues(Rating::getRating)
				.groupByKey();
		KTable<Long, Long> count = ratingsGrouped.count();
		KTable<Long, Double> reduce = ratingsGrouped.reduce(Double::sum, Materialized.with(Serdes.Long(), Serdes.Double()));
		KTable<Long, Double> join = reduce.join(count, (sum, count1) -> sum / count1, Materialized.with(Serdes.Long(), Serdes.Double()));
		return join.toStream();
	}
}

@Component
class MovieProcessor {

	@StreamListener
	@SendTo(Bindings.RATED_MOVIES)
	KStream<Long, RatedMovie> rateMoviesFor(@Input(Bindings.AVG_TABLE) KTable<Long, Double> ratings,
																																									@Input(Bindings.MOVIES) KTable<Long, Movie> movies) {

		ValueJoiner<Movie, Double, RatedMovie> joiner = (movie, rating) ->
			new RatedMovie(movie.getMovieId(), movie.getReleaseYear(), movie.getTitle(), rating);

		movies
			.join(ratings, joiner, Materialized
				.<Long, RatedMovie, KeyValueStore<Bytes, byte[]>>as(Bindings.RATED_MOVIES_STORE)
				.withKeySerde(Serdes.Long())
				.withValueSerde(new JsonSerde<>(RatedMovie.class)));

		return movies.join(ratings, joiner).toStream();
	}
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class RatedMovie {
	private long id;
	private int releaseYear;
	private String title;
	private double rating;
}