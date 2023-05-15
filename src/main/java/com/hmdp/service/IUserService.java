package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IUserService extends IService<User> {
    /**
     * Service包下的发送手机验证码接口
     *
     * @param phone   验证码
     * @param session 将结果保存到session
     * @return
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * Service包下的登录接口
     * @param loginForm 登陆参数
     * @param session 保存结果
     * @return
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();

}
