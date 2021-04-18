package com.leyou.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @Author: 刁洪亮
 * @Date: Created in 2021/4/18 9:19
 * @Description: TODO
 * @Version: 1.0
 */
@Configuration
public class ThreadPoolConfig {
    @Bean
    ThreadPoolExecutor threadPoolExecutor(){
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue(), new ThreadPoolExecutor.AbortPolicy());
    }

}
