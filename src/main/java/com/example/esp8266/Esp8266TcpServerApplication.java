package com.example.esp8266;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 入口类：启动内嵌 Tomcat（浏览器界面 + REST/WebSocket），以及给 ESP8266 用的裸 TCP 服务端。
 */
@SpringBootApplication
@EnableScheduling
public class Esp8266TcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(Esp8266TcpServerApplication.class, args);
    }
}
