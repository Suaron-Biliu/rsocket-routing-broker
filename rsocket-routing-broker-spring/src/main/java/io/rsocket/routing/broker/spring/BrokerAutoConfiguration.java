/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.routing.broker.spring;

import io.rsocket.SocketAcceptor;
import io.rsocket.routing.broker.Broker;
import io.rsocket.routing.broker.RSocketIndex;
import io.rsocket.routing.broker.RoutingTable;
import io.rsocket.routing.broker.acceptor.BrokerSocketAcceptor;
import io.rsocket.routing.broker.acceptor.ClusterSocketAcceptor;
import io.rsocket.routing.broker.config.BrokerProperties;
import io.rsocket.routing.broker.config.ClusterBrokerProperties;
import io.rsocket.routing.broker.config.TransportProperties;
import io.rsocket.routing.broker.loadbalance.LoadBalancer;
import io.rsocket.routing.broker.loadbalance.WeightedLoadBalancer;
import io.rsocket.routing.broker.loadbalance.WeightedRSocketFactory;
import io.rsocket.routing.broker.locator.RemoteRSocketLocator;
import io.rsocket.routing.broker.rsocket.RoutingRSocketFactory;
import io.rsocket.routing.broker.spring.cluster.BrokerConnections;
import io.rsocket.routing.broker.spring.cluster.ClusterController;
import io.rsocket.routing.broker.spring.cluster.ClusterJoinListener;
import io.rsocket.routing.broker.spring.cluster.MessageHandlerClusterSocketAcceptor;
import io.rsocket.routing.broker.spring.cluster.ProxyConnections;
import io.rsocket.routing.broker.spring.cluster.RouteJoinListener;
import io.rsocket.routing.frames.RoutingFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.rsocket.RSocketStrategiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.rsocket.context.RSocketServerBootstrap;
import org.springframework.boot.rsocket.netty.NettyRSocketServer;
import org.springframework.boot.rsocket.server.RSocketServerCustomizer;
import org.springframework.boot.rsocket.server.RSocketServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorResourceFactory;
import org.springframework.messaging.rsocket.DefaultMetadataExtractor;
import org.springframework.messaging.rsocket.MetadataExtractor;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.messaging.rsocket.annotation.support.RSocketMessageHandler;

@Configuration
@EnableConfigurationProperties
@ConditionalOnProperty(name = BrokerAutoConfiguration.BROKER_PREFIX + ".enabled", matchIfMissing = true)
@AutoConfigureAfter({RSocketStrategiesAutoConfiguration.class, BrokerRSocketStrategiesAutoConfiguration.class})
public class BrokerAutoConfiguration implements InitializingBean {

	public static final String BROKER_PREFIX = "io.rsocket.routing.broker";

	private final ApplicationContext context;

	public BrokerAutoConfiguration(ApplicationContext context) {
		this.context = context;
	}

	@Override
	public void afterPropertiesSet() {
		RSocketStrategies rSocketStrategies = this.context
				.getBean(RSocketStrategies.class);
		MetadataExtractor metadataExtractor = rSocketStrategies.metadataExtractor();

		if (metadataExtractor instanceof DefaultMetadataExtractor) {
			DefaultMetadataExtractor extractor = (DefaultMetadataExtractor) metadataExtractor;
			// adds all RoutingFrame impls such as RouteJoin, RouteSetup, etc..
			// to the spring encoding/decoding framework.
			extractor
					.metadataToExtract(MimeTypes.ROUTING_FRAME_MIME_TYPE, RoutingFrame.class,
							MimeTypes.ROUTING_FRAME_METADATA_KEY);
		}
	}

	@Bean
	// TODO: Broker class needed?
	public Broker broker() {
		return new Broker();
	}

	@Bean
	@ConfigurationProperties(BROKER_PREFIX)
	public BrokerProperties brokerProperties() {
		return new BrokerProperties();
	}

	@Bean
	public RSocketIndex rSocketIndex(WeightedRSocketFactory factory) {
		return new RSocketIndex(factory);
	}

	@Bean
	public RoutingTable routingTable() {
		return new RoutingTable();
	}

	@Bean
	@ConditionalOnMissingBean
	public ReactorResourceFactory reactorResourceFactory() {
		return new ReactorResourceFactory();
	}

	@Bean
	public WeightedRSocketFactory weightedRSocketFactory() {
		return new WeightedRSocketFactory();
	}

	@Bean
	@ConditionalOnMissingBean
	public LoadBalancer.Factory loadBalancerFactory() {
		// TODO: pick LoadBalancer algorithm via tags
		//return new RoundRobinLoadBalancer.Factory();
		return new WeightedLoadBalancer.Factory();
	}

	@Bean
	public RemoteRSocketLocator remoteRSocketLocator(BrokerProperties properties,
			RoutingTable routingTable, RSocketIndex index,
			LoadBalancer.Factory loadBalancerFactory, ProxyConnections connections) {
		return new RemoteRSocketLocator(properties, routingTable, index,
				loadBalancerFactory, connections::get);
	}

	@Bean
	public AddressTagsExtractor addressTagsExtractor(RSocketStrategies strategies) {
		return new AddressTagsExtractor(strategies.metadataExtractor());
	}

	@Bean
	public RoutingRSocketFactory routingRSocketFactory(RemoteRSocketLocator locator,
			AddressTagsExtractor tagsExtractor, WeightedRSocketFactory factory) {
		return new RoutingRSocketFactory(locator, tagsExtractor, factory);
	}

	@Bean
	public RouteJoinListener routeJoinListener(BrokerProperties properties,
			RoutingTable routingTable, BrokerConnections brokerConnections) {
		return new RouteJoinListener(properties, routingTable, brokerConnections);
	}

	@Bean
	public MetadataExtractorBrokerSocketAcceptor metadataExtractorBrokerSocketAcceptor(
			RSocketStrategies rSocketStrategies, RoutingTable routingTable,
			RSocketIndex rSocketIndex, RoutingRSocketFactory routingRSocketFactory,
			BrokerProperties properties, ProxyConnections proxyConnections) {
		return new MetadataExtractorBrokerSocketAcceptor(properties, routingTable, rSocketIndex,
				routingRSocketFactory, rSocketStrategies
				.metadataExtractor(), proxyConnections);
	}

	private static String findTransportName(TransportProperties properties) {
		if (properties.hasCustomTransport()) {
			return properties.getCustom().getType();
		}
		else if (properties.getWebsocket() != null) {
			return "websocket";
		}
		else if (properties.getTcp() != null) {
			return "tcp";
		}
		throw new IllegalStateException("Unknown Transport " + properties);
	}

	@Configuration
	@ConditionalOnProperty(name = ClusterConfiguration.PREFIX + ".enabled", matchIfMissing = true)
	protected static class ClusterConfiguration {

		public static final String PREFIX = BROKER_PREFIX + ".cluster";

		@Bean
		public BrokerConnections brokerConnections() {
			return new BrokerConnections();
		}

		@Bean
		public ProxyConnections proxyConnections(WeightedRSocketFactory factory) {
			return new ProxyConnections(factory);
		}

		@Bean
		public ClusterController clusterController(BrokerProperties properties,
				BrokerConnections brokerConnections, RoutingTable routingTable) {
			return new ClusterController(properties, brokerConnections, routingTable);
		}

		@Bean
		public ClusterJoinListener clusterJoinListener(BrokerProperties properties,
				BrokerConnections brokerConnections, ProxyConnections proxyConnections,
				RSocketMessageHandler messageHandler, RSocketStrategies strategies,
				RoutingRSocketFactory routingRSocketFactory) {
			return new ClusterJoinListener(properties, brokerConnections, proxyConnections,
					messageHandler, strategies, routingRSocketFactory);
		}

		@Bean
		public ClusterSocketAcceptor clusterSocketAcceptor(RSocketMessageHandler messageHandler) {
			return new MessageHandlerClusterSocketAcceptor(messageHandler);
		}

		@Bean
		@ConfigurationProperties(PREFIX)
		public ClusterBrokerProperties clusterBrokerProperties() {
			return new ClusterBrokerProperties();
		}

		@Bean
		public BrokerRSocketServerBootstrap clusterRSocketServerBootstrap(
				ClusterBrokerProperties properties,
				ObjectProvider<ServerTransportFactory> transportFactories,
				ClusterSocketAcceptor clusterSocketAcceptor) {
			RSocketServerFactory serverFactory = findRSocketServerFactory(properties, transportFactories);
			return new BrokerRSocketServerBootstrap("cluster", findTransportName(properties), serverFactory, clusterSocketAcceptor);
		}
	}

	@Bean
	public DefaultServerTransportFactory defaultServerTransportFactory(ReactorResourceFactory resourceFactory,
			ObjectProvider<RSocketServerCustomizer> processors) {
		return new DefaultServerTransportFactory(resourceFactory, processors);
	}

	@Bean
	public BrokerRSocketServerBootstrap proxyRSocketServerBootstrap(
			BrokerProperties properties,
			ObjectProvider<ServerTransportFactory> transportFactories,
			BrokerSocketAcceptor brokerSocketAcceptor) {
		RSocketServerFactory serverFactory = findRSocketServerFactory(properties, transportFactories);
		return new BrokerRSocketServerBootstrap("broker", findTransportName(properties), serverFactory, brokerSocketAcceptor);
	}

	private static RSocketServerFactory findRSocketServerFactory(TransportProperties properties, ObjectProvider<ServerTransportFactory> factories) {
		return factories.orderedStream().filter(factory -> factory.supports(properties)).findFirst()
				.map(factory -> factory.create(properties))
				.orElseThrow(() -> new IllegalStateException("Unknown transport " + properties));
	}

	/* for testing */ static class BrokerRSocketServerBootstrap extends RSocketServerBootstrap {

		// purposefully using NettyRSocketServer
		private static final Logger logger = LoggerFactory
				.getLogger(NettyRSocketServer.class);
		private final String type;
		private final String transport;
		private final RSocketServerFactory serverFactory;

		public BrokerRSocketServerBootstrap(String type, String transport,
				RSocketServerFactory serverFactory, SocketAcceptor socketAcceptor) {
			super(serverFactory, socketAcceptor);
			this.type = type;
			this.transport = transport;
			this.serverFactory = serverFactory;
		}

		@Override
		public void start() {
			logger.info("Netty RSocket starting {} with {}", type, transport);
			super.start();
		}

		/* for testing */ RSocketServerFactory getServerFactory() {
			return this.serverFactory;
		}
	}

}
