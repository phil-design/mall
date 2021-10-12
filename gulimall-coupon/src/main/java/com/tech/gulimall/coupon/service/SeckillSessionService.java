package com.tech.gulimall.coupon.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tech.gulimall.common.utils.PageUtils;
import com.tech.gulimall.coupon.entity.SeckillSessionEntity;

import java.util.Map;

/**
 * 秒杀活动场次
 *
 * @author phil
 * @email hudong.tech@gmail.com
 * @date 2021-10-12 20:49:42
 */
public interface SeckillSessionService extends IService<SeckillSessionEntity> {

    PageUtils queryPage(Map<String, Object> params);
}

