package dev.oms.infra;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrors {
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        kafka,
                        (record, failure) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var h = new DefaultErrorHandler(recoverer, new FixedBackOff(1000, 3));
        h.addNotRetryableExceptions(IllegalArgumentException.class);
        return h;
    }
}
