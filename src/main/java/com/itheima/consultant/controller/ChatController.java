package com.itheima.consultant.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
    public Flux<String> chat(String memoryId,String message){
        Flux<String> result = consultantService.chat(memoryId,message);
        return result;
    }

    @GetMapping(value = "/generateReport", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> generateReport(@RequestParam String stockName) { // 3. 修正拼写 RequestParam
        return consultantService.generateReport(stockName)
            .onErrorResume(e -> Flux.just("❌ 生成研报出错：" + e.getMessage()));
    }
//    @RequestMapping("/chat")
//    public String chat(String message){
//        String result = model.chat(message);
//        return result;
//    }

}