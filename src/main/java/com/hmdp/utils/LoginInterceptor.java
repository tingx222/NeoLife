package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;


//拦截器未生效，需要配置拦截器mvcconfig
public class LoginInterceptor implements HandlerInterceptor {

    //不能用注释进行注入，因为这个类的对象是由我们自己创建的（mvcconfig使用了这个对象），而不是spring创建的
    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            //不存在，拦截，返回401状态码
            response.setStatus(401);
            return false;
        }

        String key = RedisConstants.LOGIN_USER_KEY + token;
        //基于token获取redis中的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(key);

        //2.判断用户是否存在
        if(userMap.isEmpty()){
            //3.没有，拦截,返回401状态
            response.setStatus(401);
            return false;
        }

        //将查询到的Hash数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //4.有，保存在threadlocal中
        UserHolder.saveUser(userDTO);

        //5.刷新token有效期
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
       //移除threadlocal中的用户
        UserHolder.removeUser();
        /**
         * 解决session过期问题
         * 因为ThreadLocal底层是ThreadLocalMap，当期线程Threadlocal作为key(弱引用)，
         * user作为value(强引用)然后
         *
         * 移除用户是因为：因为ThreadLocal对应的是一个线程的数据，
         * 每次http请求，tomcat都会创建一个新的线程，也就是说，当前的ThreadLocal只在当前的线程中有用
         *
         * threadlocal 实质是存到线程的map中了，值是弱引用，用完就回收了，
         * 但是值是强引用。没有办法处理，就导致在内存中一直存着，不易销毁
         */
    }
}
