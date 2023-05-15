package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
//封装的将Java对象存进redis 的工具类
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //创建重建独立线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //存储数据到redis
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //设置redis逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), time, unit);
    }

    //缓存穿透解决办法(redis存空值)
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //1.从redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(Json)) {
            //3.存在，直接返回
            return JSONUtil.toBean(Json, type);
        }
        //redis命中的是空数据时
        if (Json != null) {
            //返回一个错误信息
            return null;
        }
        //4.redis不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.数据库不存在，返回错误
        if (r == null) {
            //将空值写入redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在，将信息写入redis
        this.set(keyPrefix + id, r, time, unit);
        //7.返回
        return r;
    }

    //缓存击穿解决办法（设置逻辑过期时间）
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFall, Long time, TimeUnit unit) {
        //1.从redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        //2.判断是否存在
        if (StrUtil.isBlank(Json)) {
            //3.不存在，直接返回空
            return null;
        }
        //4.存在，json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //4.1获取逻辑过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回店铺信息
            return r;
        }
        //5.2已经过期，需要缓存重建
        //6.1缓存重建，获取互斥锁
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
        //6.2判断是否获取锁成功
        if (isLock) {
            //成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建重建缓存
                    R r1 = dbFall.apply(id);
                    this.setWithLogicalExpire(keyPrefix + id, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(LOCK_SHOP_KEY + id);
                }
            });
        }
        //7.返回店铺信息
        return r;
    }

    //缓存击穿解决办法（互斥锁）
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFall, Long time, TimeUnit unit) {
        //1.从redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(Json)) {
            //3.存在，直接返回
            return JSONUtil.toBean(Json, type);
        }
        //redis命中的是空数据时
        if (Json != null) {
            //返回一个错误信息
            return null;
        }
        //4.redis不存在,进行缓存重建
        //4.1获取互斥锁
        R r;
        try {
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);

            //4.2判断是否获取成功
            if (!isLock) {
                //4.3休眠并且重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFall, time, unit);
            }
            //4.4成功，根据id去数据库查询
            r = dbFall.apply(id);
            //5.数据库不存在，返回错误
            if (r == null) {
                //将空值写入redis，防止缓存穿透
                this.set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.存在，将信息写入redis
            this.set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(r), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unLock(LOCK_SHOP_KEY + id);
        }
        //8.返回
        return r;
    }

    //获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //删除锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
