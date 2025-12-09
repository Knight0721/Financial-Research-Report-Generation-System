package com.itheima.consultant.controller;

import com.itheima.consultant.langchain4j.aiservice.ConsultantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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


//    @RequestMapping("/chat")
//    public String chat(String message){
//        String result = model.chat(message);
//        return result;
//    }

}