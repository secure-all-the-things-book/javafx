package com.example.bootiful_javafx;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

@ImportHttpServices(MessageClient.class)
@Configuration
class MessageClientConfiguration {
}
