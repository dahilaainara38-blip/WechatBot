package com.demo.WechatBot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class WechatBotApplication {

	public static void main(String[] args) {
		SpringApplication.run(WechatBotApplication.class, args);
	}
}
