package com.hmdp;
import com.hmdp.service.*;
import com.hmdp.entity.VoucherOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.mockito.Mockito.*;
class RabbitOrderTransportTest {
 @Test void databaseFailureIsNackedToDeadLetterNotAcknowledged() throws Exception {
  OrderWriter writer=mock(OrderWriter.class);doThrow(new IllegalStateException()).when(writer).persist(any());
  RabbitOrderTransport transport=new RabbitOrderTransport(mock(RabbitTemplate.class),new ObjectMapper(),writer,mock(StringRedisTemplate.class));
  MessageProperties props=new MessageProperties();props.setDeliveryTag(7);Channel channel=mock(Channel.class);
  transport.receive(new Message("{\"id\":1,\"userId\":2,\"voucherId\":3}".getBytes(java.nio.charset.StandardCharsets.UTF_8),props),channel);
  verify(channel).basicNack(7,false,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());
 }
}
