package com.restaurant.redismodule.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

@Configuration
@EnableConfigurationProperties(RedisProperties.class)
@ComponentScan(basePackages = "com.restaurant.redismodule")
public class RedisConfig {
    
    private final RedisProperties redisProperties;
    
    public RedisConfig(RedisProperties redisProperties) {
        this.redisProperties = redisProperties;
    }
    
    /**
     * Create Redis connection factory based on the configured mode
     */
    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public LettuceConnectionFactory redisConnectionFactory() {
        LettuceClientConfiguration clientConfig = getLettuceClientConfiguration();
        
        LettuceConnectionFactory factory = switch (redisProperties.getMode()) {
            case SENTINEL -> new LettuceConnectionFactory(redisSentinelConfiguration(), clientConfig);
            case CLUSTER -> new LettuceConnectionFactory(redisClusterConfiguration(), clientConfig);
            default -> new LettuceConnectionFactory(redisStandaloneConfiguration(), clientConfig);
        };

        factory.setDatabase(redisProperties.getDatabase());
        return factory;
    }
    
    /**
     * Standalone configuration
     */
    private RedisStandaloneConfiguration redisStandaloneConfiguration() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisProperties.getStandalone().getHost());
        config.setPort(redisProperties.getStandalone().getPort());
        config.setDatabase(redisProperties.getDatabase());
        
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
            config.setPassword(RedisPassword.of(redisProperties.getPassword()));
        }
        
        return config;
    }
    
    /**
     * Sentinel configuration
     */
    private RedisSentinelConfiguration redisSentinelConfiguration() {
        RedisSentinelConfiguration config = new RedisSentinelConfiguration();
        config.setMaster(redisProperties.getSentinel().getMaster());
        config.setDatabase(redisProperties.getDatabase());
        
        if (redisProperties.getSentinel().getNodes() != null && !redisProperties.getSentinel().getNodes().isEmpty()) {
            Set<RedisNode> sentinelNodes = new HashSet<>();
            for (String node : redisProperties.getSentinel().getNodes()) {
                String[] parts = node.split(":");
                String host = parts[0];
                int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 26379;
                sentinelNodes.add(new RedisNode(host, port));
            }
            config.setSentinels(sentinelNodes);
        }
        
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
            config.setPassword(RedisPassword.of(redisProperties.getPassword()));
        }
        
        if (redisProperties.getSentinel().getPassword() != null && !redisProperties.getSentinel().getPassword().isEmpty()) {
            config.setSentinelPassword(RedisPassword.of(redisProperties.getSentinel().getPassword()));
        }
        
        return config;
    }
    
    /**
     * Cluster configuration
     */
    private RedisClusterConfiguration redisClusterConfiguration() {
        RedisClusterConfiguration config = new RedisClusterConfiguration(redisProperties.getCluster().getNodes());
        config.setMaxRedirects(redisProperties.getCluster().getMaxRedirects());
        
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
            config.setPassword(RedisPassword.of(redisProperties.getPassword()));
        }
        
        return config;
    }
    
    /**
     * Configure Lettuce client with connection pooling
     */
    private LettuceClientConfiguration getLettuceClientConfiguration() {
        // Configure socket options
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(redisProperties.getTimeout())
                .build();
        
        // Configure client options based on mode
        ClientOptions.Builder clientOptionsBuilder = ClientOptions.builder()
                .socketOptions(socketOptions)
                .timeoutOptions(TimeoutOptions.enabled(redisProperties.getTimeout()));
        
        // For cluster mode, add cluster-specific options
        if (redisProperties.getMode() == RedisProperties.RedisMode.CLUSTER) {
            ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
                    .enablePeriodicRefresh(Duration.ofMinutes(10))
                    .enableAllAdaptiveRefreshTriggers()
                    .build();
            
            clientOptionsBuilder = ClusterClientOptions.builder()
                    .socketOptions(socketOptions)
                    .timeoutOptions(TimeoutOptions.enabled(redisProperties.getTimeout()))
                    .topologyRefreshOptions(topologyRefreshOptions);
        }
        
        // Configure connection pool
        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(redisProperties.getLettuce().getMaxActive());
        poolConfig.setMaxIdle(redisProperties.getLettuce().getMaxIdle());
        poolConfig.setMinIdle(redisProperties.getLettuce().getMinIdle());
        poolConfig.setMaxWait(redisProperties.getLettuce().getMaxWait());
        
        if (redisProperties.getLettuce().getTimeBetweenEvictionRuns() != null) {
            poolConfig.setTimeBetweenEvictionRuns(redisProperties.getLettuce().getTimeBetweenEvictionRuns());
        }
        
        // Build Lettuce configuration with pooling
        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder = 
                LettucePoolingClientConfiguration.builder()
                        .poolConfig(poolConfig)
                        .clientOptions(clientOptionsBuilder.build())
                        .commandTimeout(redisProperties.getTimeout());
        
        // For sentinel and cluster, configure read-from strategy
        if (redisProperties.getMode() == RedisProperties.RedisMode.SENTINEL || 
            redisProperties.getMode() == RedisProperties.RedisMode.CLUSTER) {
            builder.readFrom(ReadFrom.REPLICA_PREFERRED); // Read from replicas when possible
        }
        
        return builder.build();
    }
    
    /**
     * Configure RedisTemplate with proper serializers
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        
        // Use String serializer for keys
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        
        // Use JSON serializer for values
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        template.afterPropertiesSet();
        return template;
    }
}

