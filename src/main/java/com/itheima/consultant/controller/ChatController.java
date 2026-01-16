package com.itheima.consultant.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itheima.consultant.langchain4j.aiservice.ConsultantService;

import reactor.core.publisher.Flux;

@RestController
public class ChatController {
//    @Autowired
//    private OpenAiChatModel model;
    @Autowired
    private ConsultantService consultantService;

    @RequestMapping(value = "/chat",produces = "text/html;charset=utf-8")
    public Flux<String> chat(@RequestParam(required = false) String memoryId, @RequestParam String message) {
        
        System.out.println("收到用户消息: " + message);

        // 【新增逻辑判断】
        // 1. 如果消息包含 "研报" 或 "报告"，说明用户想生成格式化的研报
        // 2. 并且调用那个“严格格式”的 agent 方法
        if (message.contains("研报") || message.contains("报告")) {
            System.out.println(">>> 触发指令：正在调用 generateReport 生成研报...");
            
            // 这里调用 Service 里定义的 generateReport
            // 注意：如果你的 Service 接口里 generateReport 需要 memoryId，就改成 generateReport(memoryId, message)
            // 根据之前的上下文，这里假设你只需要传 message (其中包含股票名)
            return consultantService.generateReport(message)
                    .onErrorResume(e -> Flux.just("❌ 生成研报出错：" + e.getMessage()));
        }

        // 【默认逻辑】
        // 如果没有触发关键字，就走普通的聊天模式
        System.out.println(">>> 普通聊天模式");
        return consultantService.chat(memoryId, message);
    }
}