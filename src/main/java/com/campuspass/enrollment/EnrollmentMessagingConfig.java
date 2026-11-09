package com.campuspass.enrollment;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnrollmentMessagingConfig {
    public static final String EXCHANGE = "campuspass.exchange";
    public static final String DEAD_EXCHANGE = "campuspass.dead.exchange";
    public static final String ENROLL_QUEUE = "campuspass.enrollment";
    public static final String TIMEOUT_QUEUE = "campuspass.timeout";
    public static final String DEAD_QUEUE = "campuspass.dead";

    @Bean public DirectExchange campusPassExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean public DirectExchange campusPassDeadExchange() { return new DirectExchange(DEAD_EXCHANGE, true, false); }
    @Bean public Queue enrollmentQueue() { return QueueBuilder.durable(ENROLL_QUEUE).deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey("dead").build(); }
    @Bean public Queue timeoutQueue() { return QueueBuilder.durable(TIMEOUT_QUEUE).ttl(300_000).deadLetterExchange(EXCHANGE).deadLetterRoutingKey("timeout").build(); }
    @Bean public Queue deadQueue() { return QueueBuilder.durable(DEAD_QUEUE).build(); }
    @Bean public Binding enrollmentBinding() { return BindingBuilder.bind(enrollmentQueue()).to(campusPassExchange()).with("enroll"); }
    @Bean public Binding timeoutBinding() { return BindingBuilder.bind(timeoutQueue()).to(campusPassExchange()).with("delay"); }
    @Bean public Binding timeoutProcessBinding() { return BindingBuilder.bind(enrollmentQueue()).to(campusPassExchange()).with("timeout"); }
    @Bean public Binding deadBinding() { return BindingBuilder.bind(deadQueue()).to(campusPassDeadExchange()).with("dead"); }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory factory) {
        RabbitTemplate template = new RabbitTemplate(factory);
        template.setMessageConverter(new SimpleMessageConverter());
        template.setMandatory(true);
        return template;
    }
}
