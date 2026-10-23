package com.hmdp;

import com.hmdp.dto.*;
import com.hmdp.entity.*;
import com.hmdp.service.*;
import com.hmdp.utils.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: real configured MySQL and Redis. DB fixtures roll back; Redis fixture keys are removed. */
@SpringBootTest(properties={"hmdp.order-worker-enabled=false","hmdp.order-prefix=test:course:","logging.level.com.hmdp=info"})
@EnabledIfSystemProperty(named="runLocalIntegration",matches="true")
@Transactional
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class CourseIntegrationTest {
    @Autowired IUserService users; @Autowired IBlogService blogs; @Autowired IShopService shops;
    @Autowired ISeckillVoucherService stocks; @Autowired IVoucherOrderService orders;
    @Autowired SocialService social; @Autowired DiscoveryService discovery; @Autowired OrderWriter writer;
    @Autowired StringRedisTemplate redis;
    @Autowired AsyncSeckillService async;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    private User author,reader,target;private final List<String> keys=new ArrayList<>();
    private void as(User user){UserDTO dto=new UserDTO();dto.setId(user.getId());UserHolder.saveUser(dto);}
    private User user(){User u=new User();u.setPhone("19"+String.format("%09d",Math.floorMod(System.nanoTime(),1000000000L)));u.setNickName("course-integration");users.save(u);return u;}
    @BeforeEach void fixtures(){author=user();reader=user();target=user();
        for(User u:Arrays.asList(author,reader,target)){
            keys.add("follows:"+u.getId());keys.add("feed:"+u.getId());keys.add("sign:"+u.getId()+":"+LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMM")));
        }
    }
    @AfterEach void cleanup(){UserHolder.removeUser();if(!keys.isEmpty())redis.delete(keys);}
    private Shop shop(){Shop s=new Shop();s.setName("course-integration");s.setTypeId(987654321L);s.setImages("/test.png");s.setAddress("test");s.setX(120.15);s.setY(30.33);s.setSold(0);s.setComments(0);s.setScore(40);shops.save(s);keys.add("shop:geo:"+s.getTypeId());keys.add("cache:shop:"+s.getId());return s;}

    @Test void followCommonPublishFeedAndToggleLikes(){
        as(reader);assertTrue(social.follow(author.getId(),true).getSuccess());social.follow(target.getId(),true);
        as(author);social.follow(target.getId(),true);
        as(reader);List<?> common=(List<?>)social.common(author.getId()).getData();assertEquals(1,common.size());
        assertEquals(target.getId(),((UserDTO)common.get(0)).getId());
        as(author);Blog b=new Blog();b.setShopId(1L);b.setTitle("integration");b.setContent("test");b.setImages("/test.png");
        assertTrue(social.publish(b).getSuccess());keys.add("blog:liked:"+b.getId());
        as(reader);ScrollResult feed=(ScrollResult)social.feed(System.currentTimeMillis()+1000,0).getData();assertEquals(1,feed.getList().size());
        ScrollResult next=(ScrollResult)social.feed(feed.getMinTime(),feed.getOffset()).getData();assertTrue(next.getList().isEmpty());
        social.like(b.getId());assertEquals(1,blogs.getById(b.getId()).getLiked());
        assertTrue(((Blog)social.detail(b.getId()).getData()).getIsLike());assertEquals(1,((List<?>)social.likers(b.getId()).getData()).size());
        social.like(b.getId());assertEquals(0,blogs.getById(b.getId()).getLiked());
        social.follow(author.getId(),false);assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember("follows:"+reader.getId(),author.getId().toString())));
    }
    @Test void bitmapSignIsIdempotentAndMissingDaysBreakStreak(){
        as(reader);assertEquals(0,discovery.signCount().getData());discovery.sign();discovery.sign();assertEquals(1,discovery.signCount().getData());
        assertEquals(3,DiscoveryService.trailingOnes(0b10111));assertEquals(0,DiscoveryService.trailingOnes(0b1110));
    }
    @Test void httpAuthenticationAndPublicEndpoints() throws Exception {
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/shop-type/list"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/user/sign"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        String token=UUID.randomUUID().toString();String key="login:token:"+token;keys.add(key);
        redis.opsForHash().put(key,"id",reader.getId().toString());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/follow/"+author.getId()+"/true").header("authorization",token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/voucher-order/of/me").header("authorization",token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/user/logout").header("authorization",token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true));
        assertFalse(redis.hasKey(key));
    }
    @Test void geoBuildsFromDbAndColdCacheLoadsRealShop(){
        Shop s=shop();List<?> nearby=(List<?>)discovery.nearby(987654321,1,120.15,30.33).getData();assertEquals(1,nearby.size());
        assertTrue(((Shop)nearby.get(0)).getDistance()<100);
        assertTrue(((List<?>)discovery.nearby(987654321,2,120.15,30.33).getData()).isEmpty());
        assertTrue(shops.queryById(s.getId()).getSuccess());assertTrue(redis.hasKey("cache:shop:"+s.getId()));
        assertFalse(discovery.nearby(1,1,200.0,0.0).getSuccess());
    }
    @Test void orderWriterIsIdempotentAndNeverMakesStockNegative(){
        long voucher=800000000L+author.getId();SeckillVoucher v=new SeckillVoucher();v.setVoucherId(voucher);v.setStock(1);v.setBeginTime(LocalDateTime.now());v.setEndTime(LocalDateTime.now().plusDays(1));stocks.save(v);
        VoucherOrder o=new VoucherOrder();o.setId(800000000L+reader.getId());o.setVoucherId(voucher);o.setUserId(reader.getId());writer.persist(o);writer.persist(o);
        assertEquals(0,stocks.getById(voucher).getStock());assertNotNull(orders.getById(o.getId()));
        VoucherOrder other=new VoucherOrder();other.setId(o.getId()+10000000);other.setVoucherId(voucher);other.setUserId(target.getId());
        assertThrows(IllegalStateException.class,()->writer.persist(other));assertNull(orders.getById(other.getId()));
    }
    @Test void streamReservationConsumptionAndOwnedStatus(){
        long voucher=900000000L+author.getId();SeckillVoucher v=new SeckillVoucher();v.setVoucherId(voucher);v.setStock(1);
        v.setBeginTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1));v.setEndTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(1));stocks.save(v);
        keys.addAll(Arrays.asList("test:course:stream.orders","test:course:seckill:pending","test:course:seckill:activity:"+voucher,"test:course:seckill:purchased:"+voucher));
        as(reader);Result submitted=async.submit(voucher);assertTrue(submitted.getSuccess());Long id=Long.valueOf(submitted.getData().toString());
        assertEquals("PROCESSING",((Map<?,?>)async.status(id).getData()).get("state"));
        assertFalse(async.submit(voucher).getSuccess());
        // Simulate an inventory inconsistency: failed DB transaction must leave the message pending.
        v.setStock(0);stocks.updateById(v);
        async.processBatch();
        assertEquals("PROCESSING",((Map<?,?>)async.status(id).getData()).get("state"));
        assertNull(orders.getById(id));
        v.setStock(1);stocks.updateById(v);
        async.processBatch();
        assertEquals("SUCCESS",((Map<?,?>)async.status(id).getData()).get("state"));assertEquals(0,stocks.getById(voucher).getStock());
        as(target);assertFalse(async.status(id).getSuccess());assertFalse(async.submit(voucher).getSuccess());
        assertEquals(0,redis.opsForStream().size("test:course:stream.orders"));
    }
    @Test void luaConcurrentReservationsPreventOversellAndDuplicateUsers() throws Exception {
        String prefix="test:course:"+UUID.randomUUID();List<String> isolated=Arrays.asList(prefix+":activity",prefix+":users",prefix+":stream",prefix+":pending");keys.addAll(isolated);
        Map<String,String> activity=new HashMap<>();activity.put("stock","3");activity.put("begin","0");activity.put("end","9999999999999");redis.opsForHash().putAll(isolated.get(0),activity);
        DefaultRedisScript<Long> script=new DefaultRedisScript<>();script.setLocation(new ClassPathResource("seckill.lua"));script.setResultType(Long.class);
        ExecutorService pool=Executors.newFixedThreadPool(8);try{
            List<Future<Long>> tasks=new ArrayList<>();for(int i=0;i<20;i++){final int n=i;tasks.add(pool.submit(()->redis.execute(script,isolated,"user"+n,"1",Integer.toString(n))));}
            int accepted=0;for(Future<Long> task:tasks)if(task.get(10,TimeUnit.SECONDS)==0)accepted++;
            assertEquals(3,accepted);assertEquals("0",redis.opsForHash().get(isolated.get(0),"stock"));assertEquals(3,redis.opsForStream().size(isolated.get(2)));
            String purchased=redis.opsForSet().members(isolated.get(1)).iterator().next();assertEquals(5L,redis.execute(script,isolated,purchased,"1","999"));
        }finally{pool.shutdownNow();}
    }
}
