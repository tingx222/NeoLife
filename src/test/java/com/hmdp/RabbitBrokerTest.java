package com.hmdp;
import com.hmdp.service.*;
import com.hmdp.entity.VoucherOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@EnabledIfSystemProperty(named="runRabbitBroker",matches="true")
class RabbitBrokerTest {
 @Test @SuppressWarnings("unchecked") void realPersistentPublishConfirmAndConsumerAck() throws Exception {
  Properties p=new Properties();try(java.io.Reader reader=java.nio.file.Files.newBufferedReader(java.nio.file.Paths.get("config/application.properties"),java.nio.charset.StandardCharsets.UTF_8)){p.load(reader);}
  CachingConnectionFactory factory=new CachingConnectionFactory(p.getProperty("spring.rabbitmq.host","localhost"),Integer.parseInt(p.getProperty("spring.rabbitmq.port","5672")));
  factory.setUsername(p.getProperty("spring.rabbitmq.username"));factory.setPassword(p.getProperty("spring.rabbitmq.password"));factory.setVirtualHost(p.getProperty("spring.rabbitmq.virtual-host","hmdp"));
  String queue="test.hmdp."+UUID.randomUUID();String dead=queue+".dead";
  StringRedisTemplate redis=mock(StringRedisTemplate.class);when(redis.opsForHash()).thenReturn(mock(HashOperations.class));OrderWriter writer=mock(OrderWriter.class);
  RabbitOrderTransport transport=new RabbitOrderTransport(new RabbitTemplate(factory),new ObjectMapper(),writer,redis);
  ReflectionTestUtils.setField(transport,"queue",queue);ReflectionTestUtils.setField(transport,"dead",dead);
  Channel channel=null;
  try{
   channel=factory.createConnection().createChannel(false);channel.queueDeclare(dead,true,false,false,null);
   VoucherOrder order=new VoucherOrder();order.setId(101L);order.setUserId(202L);order.setVoucherId(303L);transport.publish(order);
   GetResponse received=channel.basicGet(queue,false);assertNotNull(received);assertEquals(2,received.getProps().getDeliveryMode());
   MessageProperties props=new MessageProperties();props.setDeliveryTag(received.getEnvelope().getDeliveryTag());
   transport.receive(new Message(received.getBody(),props),channel);verify(writer).persist(any(VoucherOrder.class));assertEquals(0,channel.queueDeclarePassive(queue).getMessageCount());
  }finally{if(channel!=null && channel.isOpen()){channel.queueDelete(queue);channel.queueDelete(dead);channel.close();}factory.destroy();}
 }
}
