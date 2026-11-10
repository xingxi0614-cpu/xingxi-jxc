package com.group.scm.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("星嘻进销存 API")
                .version("v1.0.0")
                .description("覆盖采购、入库、出库、退料、库存流水和库存期间锁定的轻量库存接口")
                .contact(new Contact().name("星嘻进销存")));
    }
}
