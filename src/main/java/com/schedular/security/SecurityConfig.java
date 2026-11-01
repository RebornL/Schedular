package com.schedular.security;

import com.schedular.storage.ApiKeyRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeyRepository apiKeyRepository) {
        FilterRegistrationBean<ApiKeyFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyFilter(apiKeyRepository));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Integer.MIN_VALUE + 100);
        return registration;
    }
}
