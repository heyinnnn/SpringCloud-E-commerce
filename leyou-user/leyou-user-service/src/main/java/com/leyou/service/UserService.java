package com.leyou.service;

import com.leyou.common.pojo.utils.NumberUtils;
import com.leyou.mapper.UserMapper;
import com.leyou.user.pojo.User;
import com.leyou.utils.CodecUtils;
import com.leyou.utils.MailUtil;
import com.sun.org.apache.xpath.internal.operations.Bool;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import tk.mybatis.mapper.entity.Example;

import javax.servlet.http.HttpSession;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    public Boolean checkData(String data, Integer type) {
        User record = new User();
        switch (type) {
            case 1:
                record.setUsername(data);
                break;
            case 2:
                record.setPhone(data);
                break;
            default:
                return null;
        }
        return this.userMapper.selectCount(record) == 0;
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AmqpTemplate amqpTemplate;

    @Autowired
    ThreadPoolExecutor taskExecutor;

    static final String KEY_PREFIX = "user:code:phone:";

    static final Logger logger = LoggerFactory.getLogger(UserService.class);

    public Boolean sendVerifyCode(String phone) {
        // 生成验证码
        String code = NumberUtils.generateCode(6);
        try {
            // 发送短信
            Map<String, String> msg = new HashMap<>();
            msg.put("phone", phone);
            msg.put("code", code);
            this.amqpTemplate.convertAndSend("leyou.sms.exchange", "sms.verify.code", msg);
            // 将code存入redis
            this.redisTemplate.opsForValue().set(KEY_PREFIX + phone, code, 5, TimeUnit.MINUTES);
            return true;
        } catch (Exception e) {
            logger.error("发送短信失败。phone：{}， code：{}", phone, code);
            return false;
        }
    }

    public Boolean register(User user, String code) {
        // 校验短信验证码
        String cacheCode = this.redisTemplate.opsForValue().get(KEY_PREFIX + user.getPhone());
        if (!StringUtils.equals(code, cacheCode)) {
            return false;
        }

        // 生成盐
        String salt = CodecUtils.generateSalt();
        user.setSalt(salt);

        // 对密码加密
        user.setPassword(CodecUtils.md5Hex(user.getPassword(), salt));

        // 强制设置不能指定的参数为null
        user.setId(null);
        user.setCreated(new Date());
        // 添加到数据库
        boolean b = this.userMapper.insertSelective(user) == 1;

        if(b){
            // 注册成功，删除redis中的记录
            this.redisTemplate.delete(KEY_PREFIX + user.getPhone());
        }
        return b;
    }


    public Boolean register(User user, HttpSession session) {
        // 生成盐
        String salt = CodecUtils.generateSalt();
        user.setSalt(salt);

        // 对密码加密
        user.setPassword(CodecUtils.md5Hex(user.getPassword(), salt));

        // 强制设置不能指定的参数为null
        user.setId(null);
        user.setCreated(new Date());
        // 添加到数据库
        boolean insertSuccess = this.userMapper.insertSelective(user) == 1;

        if (insertSuccess){
            final String sessionid = DigestUtils.md5DigestAsHex(session.getId().getBytes());
            redisTemplate.opsForValue().set(sessionid, user.getId().toString(), 60 * 60, TimeUnit.SECONDS);
            try {
                taskExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            MailUtil.sendTo("<h1>点击<a href='http://localhost:7070/api/user/activate?code="+sessionid+"'>此处</a>激活账户<h1>", user.getEmail());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
            logger.info("用户创建成功！请到邮箱中激活");
            return true;
        }
        else{
            logger.info("用户已存在， {}", user);
            return false;
        }
    }

    public User queryUser(String username, String password) {
        // 查询
        User record = new User();
        record.setUsername(username);
        User user = this.userMapper.selectOne(record);
        // 校验用户名
        if (user == null) {
            return null;
        }
        // 校验密码
        if (!user.getPassword().equals(password)) {
            return null;
        }
        // 用户名密码都正确
        return user;
    }

    public Boolean activate(String code) {
        String id = redisTemplate.opsForValue().get(code);
        if (id == null){
            logger.info("redis中不存在");
            return false;
        }
        User user = new User();
        user.setIsactivate(true);

        Example example = new Example(User.class);
        Example.Criteria criteria = example.createCriteria().andEqualTo("id", Long.parseLong(id));
        int result = userMapper.updateByExampleSelective(user, example);
        if (result == 1){
            logger.info("激活成功 ，请登录");
            redisTemplate.delete(code);
            return true;
        }
        else{
            logger.info("数据库操作失败");
            return false;
        }
    }
}