package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.rabbitmq.client.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis Stream remains a durable outbox; broker confirmation precedes removing its record. */
@Component
@ConditionalOnProperty(name="hmdp.rabbit.enabled",havingValue="true")
public class RabbitOrderTransport {
    public static final String QUEUE="hmdp.orders",DEAD="hmdp.orders.dead";
    private final RabbitTemplate rabbit;private final ObjectMapper mapper;private final OrderWriter writer;private final StringRedisTemplate redis;
    @org.springframework.beans.factory.annotation.Value("${hmdp.order-prefix:}") private String prefix="";
    @org.springframework.beans.factory.annotation.Value("${hmdp.rabbit.queue:hmdp.orders}") private String queue=QUEUE;
    @org.springframework.beans.factory.annotation.Value("${hmdp.rabbit.dead-queue:hmdp.orders.dead}") private String dead=DEAD;
    public RabbitOrderTransport(RabbitTemplate rabbit,ObjectMapper mapper,OrderWriter writer,StringRedisTemplate redis){this.rabbit=rabbit;this.mapper=mapper;this.writer=writer;this.redis=redis;}
    @Bean public Queue orderQueue(){return QueueBuilder.durable(queue).withArgument("x-dead-letter-exchange","").withArgument("x-dead-letter-routing-key",dead).build();}
    @Bean public Queue deadQueue(){return QueueBuilder.durable(dead).build();}
    public void publish(VoucherOrder order){
        rabbit.execute(channel->{
            channel.confirmSelect();
            // Declare on this channel: a missing queue must fail before a publish can be confirmed.
            java.util.Map<String,Object> arguments=new java.util.HashMap<>();arguments.put("x-dead-letter-exchange","");arguments.put("x-dead-letter-routing-key",dead);
            channel.queueDeclare(queue,true,false,false,arguments);
            channel.basicPublish("",queue,new AMQP.BasicProperties.Builder().deliveryMode(2).contentType("application/json").messageId(order.getId().toString()).build(),mapper.writeValueAsBytes(order));
            channel.waitForConfirmsOrDie(5000);return null;
        });
    }
    @RabbitListener(queues="${hmdp.rabbit.queue:hmdp.orders}",ackMode="MANUAL",concurrency="2-4")
    public void receive(org.springframework.amqp.core.Message message,Channel channel) throws java.io.IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        try{
            VoucherOrder order=mapper.readValue(message.getBody(),VoucherOrder.class);
            writer.persist(order); // Separate bean returns only after transaction commit.
            redis.opsForHash().delete(prefix+"seckill:pending",order.getId().toString());
            redis.opsForHash().delete(prefix+"seckill:pending:vouchers",order.getId().toString());
            channel.basicAck(tag,false);
        }catch(Exception e){channel.basicNack(tag,false,false);} // Durable DLQ retains failures for reconciliation.
    }
}
