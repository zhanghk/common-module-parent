package cn.com.flaginfo.redis.mq;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * @author: LiuMeng
 * @date: 2019/8/22
 * TODO:
 */
public class RedisQueueAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IRedisQueueConsumerFailedMessage.class)
    public IRedisQueueConsumerFailedMessage redisQueueConsumerFailedMessage() {
        return new DefaultRedisQueueConsumerFailedMessage();
    }

    @Bean
    @ConditionalOnMissingBean(IRedisQueueConsumerCache.class)
    public IRedisQueueConsumerCache redisQueueConsumerCache(IRedisQueueMessageRetry redisQueueMessageRetry) {
        return new DefaultRedisQueueConsumerCache(redisQueueMessageRetry);
    }

    @Bean
    @ConditionalOnMissingBean(IRedisQueueMessageRetry.class)
    public IRedisQueueMessageRetry redisQueueMessageRetry() {
        return new DefaultRedisQueueMessageRetry();
    }

    @Bean
    public RedisQueueListenerBeanPostProcessor redisQueueListenerBeanPostProcessor(
            IRedisQueueConsumerFailedMessage redisQueueConsumerFailedMessage,
            IRedisQueueConsumerCache redisQueueConsumerCache,
            IRedisQueueMessageRetry redisQueueMessageRetry) {
        return new RedisQueueListenerBeanPostProcessor(redisQueueConsumerCache,
                redisQueueConsumerFailedMessage,
                redisQueueMessageRetry);
    }

    @Bean
    public RedisQueueTemplate redisQueueTemplate(){
        return new RedisQueueTemplate();
    }

}
