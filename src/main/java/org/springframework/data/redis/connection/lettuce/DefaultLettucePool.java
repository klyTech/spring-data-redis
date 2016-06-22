/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.connection.lettuce;

import java.util.concurrent.TimeUnit;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.PoolException;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.lambdaworks.redis.RedisAsyncConnection;
import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.resource.ClientResources;

/**
 * Default implementation of {@link LettucePool}.
 * 
 * @author Jennifer Hickey
 * @author Christoph Strobl
 * @author Mark Paluch
 */
public class DefaultLettucePool implements LettucePool, InitializingBean {

	@SuppressWarnings("rawtypes") //
	private GenericObjectPool<RedisAsyncConnection> internalPool;
	private RedisClient client;
	private int dbIndex = 0;
	private GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
	private String hostName = "localhost";
	private int port = 6379;
	private String password;
	private long timeout = TimeUnit.MILLISECONDS.convert(60, TimeUnit.SECONDS);
	private RedisSentinelConfiguration sentinelConfiguration;
	private ClientResources clientResources;

	/**
	 * Constructs a new <code>DefaultLettucePool</code> instance with default settings.
	 */
	public DefaultLettucePool() {}

	/**
	 * Uses the {@link Config} and {@link RedisClient} defaults for configuring the connection pool
	 * 
	 * @param hostName The Redis host
	 * @param port The Redis port
	 */
	public DefaultLettucePool(String hostName, int port) {
		this.hostName = hostName;
		this.port = port;
	}

	/**
	 * Uses the {@link RedisSentinelConfiguration} and {@link RedisClient} defaults for configuring the connection pool
	 * based on sentinels.
	 *
	 * @param sentinelConfiguration The Sentinel configuration
	 * @since 1.6
	 */
	public DefaultLettucePool(RedisSentinelConfiguration sentinelConfiguration) {
		this.sentinelConfiguration = sentinelConfiguration;
	}

	/**
	 * Uses the {@link RedisClient} defaults for configuring the connection pool
	 * 
	 * @param hostName The Redis host
	 * @param port The Redis port
	 * @param poolConfig The pool {@link GenericObjectPoolConfig}
	 */
	public DefaultLettucePool(String hostName, int port, GenericObjectPoolConfig poolConfig) {
		this.hostName = hostName;
		this.port = port;
		this.poolConfig = poolConfig;
	}

	/**
	 * @return true when {@link RedisSentinelConfiguration} is present.
	 * @since 1.6
	 */
	public boolean isRedisSentinelAware() {
		return sentinelConfiguration != null;
	}

	@SuppressWarnings({ "rawtypes" })
	public void afterPropertiesSet() {

		if (clientResources != null) {
			this.client = RedisClient.create(clientResources, getRedisURI());
		} else {
			this.client = RedisClient.create(getRedisURI());
		}

		client.setDefaultTimeout(timeout, TimeUnit.MILLISECONDS);
		this.internalPool = new GenericObjectPool<RedisAsyncConnection>(new LettuceFactory(client, dbIndex), poolConfig);
	}

	/**
	 * @return a RedisURI pointing either to a single Redis host or containing a set of sentinels.
	 */
	private RedisURI getRedisURI() {

		if (isRedisSentinelAware()) {
			RedisURI redisURI = LettuceConverters.sentinelConfigurationToRedisURI(sentinelConfiguration);

			if (StringUtils.hasText(password)) {
				redisURI.setPassword(password);
			}

			return redisURI;
		}

		return createSimpleHostRedisURI();
	}

	private RedisURI createSimpleHostRedisURI() {
		RedisURI.Builder builder = RedisURI.Builder.redis(hostName, port);
		if (password != null) {
			builder.withPassword(password);
		}
		builder.withTimeout(timeout, TimeUnit.MILLISECONDS);
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	public RedisAsyncConnection<byte[], byte[]> getResource() {
		try {
			return internalPool.borrowObject();
		} catch (Exception e) {
			throw new PoolException("Could not get a resource from the pool", e);
		}
	}

	public void returnBrokenResource(final RedisAsyncConnection<byte[], byte[]> resource) {
		try {
			internalPool.invalidateObject(resource);
		} catch (Exception e) {
			throw new PoolException("Could not invalidate the broken resource", e);
		}
	}

	public void returnResource(final RedisAsyncConnection<byte[], byte[]> resource) {
		try {
			internalPool.returnObject(resource);
		} catch (Exception e) {
			throw new PoolException("Could not return the resource to the pool", e);
		}
	}

	public void destroy() {
		try {
			client.shutdown();
			internalPool.close();
		} catch (Exception e) {
			throw new PoolException("Could not destroy the pool", e);
		}
	}

	/**
	 * @return The Redis client
	 */
	public RedisClient getClient() {
		return client;
	}

	/**
	 * @return The pool configuration
	 */
	public GenericObjectPoolConfig getPoolConfig() {
		return poolConfig;
	}

	/**
	 * @param poolConfig The pool configuration to use
	 */
	public void setPoolConfig(GenericObjectPoolConfig poolConfig) {
		this.poolConfig = poolConfig;
	}

	/**
	 * Returns the index of the database.
	 * 
	 * @return Returns the database index
	 */
	public int getDatabase() {
		return dbIndex;
	}

	/**
	 * Sets the index of the database used by this connection pool. Default is 0.
	 * 
	 * @param index database index
	 */
	public void setDatabase(int index) {
		Assert.isTrue(index >= 0, "invalid DB index (a positive index required)");
		this.dbIndex = index;
	}

	/**
	 * Returns the password used for authenticating with the Redis server.
	 * 
	 * @return password for authentication
	 */
	public String getPassword() {
		return password;
	}

	/**
	 * Sets the password used for authenticating with the Redis server.
	 * 
	 * @param password the password to set
	 */
	public void setPassword(String password) {
		this.password = password;
	}

	/**
	 * Returns the current host.
	 * 
	 * @return the host
	 */
	public String getHostName() {
		return hostName;
	}

	/**
	 * Sets the host.
	 * 
	 * @param host the host to set
	 */
	public void setHostName(String host) {
		this.hostName = host;
	}

	/**
	 * Returns the current port.
	 * 
	 * @return the port
	 */
	public int getPort() {
		return port;
	}

	/**
	 * Sets the port.
	 * 
	 * @param port the port to set
	 */
	public void setPort(int port) {
		this.port = port;
	}

	/**
	 * Returns the connection timeout (in milliseconds).
	 * 
	 * @return connection timeout
	 */
	public long getTimeout() {
		return timeout;
	}

	/**
	 * Sets the connection timeout (in milliseconds).
	 * 
	 * @param timeout connection timeout
	 */
	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}

	/**
	 * Get the {@link ClientResources} to reuse infrastructure.
	 * 
	 * @return {@literal null} if not set.
	 * @since 1.7
	 */
	public ClientResources getClientResources() {
		return clientResources;
	}

	/**
	 * Sets the {@link ClientResources} to reuse the client infrastructure. <br />
	 * Set to {@literal null} to not share resources.
	 * 
	 * @param clientResources can be {@literal null}.
	 * @since 1.7
	 */
	public void setClientResources(ClientResources clientResources) {
		this.clientResources = clientResources;
	}

	@SuppressWarnings("rawtypes")
	private static class LettuceFactory extends BasePooledObjectFactory<RedisAsyncConnection> {

		private final RedisClient client;

		private int dbIndex;

		public LettuceFactory(RedisClient client, int dbIndex) {
			super();
			this.client = client;
			this.dbIndex = dbIndex;
		}

		@Override
		public void activateObject(PooledObject<RedisAsyncConnection> pooledObject) throws Exception {
			pooledObject.getObject().select(dbIndex);
		}

		public void destroyObject(final PooledObject<RedisAsyncConnection> obj) throws Exception {
			try {
				obj.getObject().close();
			} catch (Exception e) {
				// Errors may happen if returning a broken resource
			}
		}

		public boolean validateObject(final PooledObject<RedisAsyncConnection> obj) {
			try {
				obj.getObject().ping();
				return true;
			} catch (Exception e) {
				return false;
			}
		}

		@Override
		public RedisAsyncConnection create() throws Exception {
			return client.connectAsync(LettuceConnection.CODEC);
		}

		@Override
		public PooledObject<RedisAsyncConnection> wrap(RedisAsyncConnection obj) {
			return new DefaultPooledObject<RedisAsyncConnection>(obj);
		}

	}
}
