package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.Random;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.手机号校验
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到session
        session.setAttribute("code", code);

        //5.发送验证码到手机
        //调用第三方平台，跳过
        log.debug("发送短信验证码成功，验证码：{}",code);

        //返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.验证手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合，返回错误信息
            return Result.fail("手机号错误");
        }
        //2.验证验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        //3.不一致，报错
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            return Result.fail("验证码错误");
        }
        //4.一致，根据手机号查询用户 select * from tb_user where phone = ?(mybatisplus--简化单表操作）
        User user = query().eq("phone", phone).one();

        //5.用户不存在，注册用户并保存
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        //6.保存用户到session并返回
        session.setAttribute("user", BeanUtil.copyProperties("user", UserDTO.class));

        //返回
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2.保存用户(mybatisplus）
        save(user);
        return user;
    }
}
