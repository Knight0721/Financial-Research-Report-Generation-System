package com.itheima.consultant.langchain4j.aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        //chatMemory="chatMemory"
        chatMemoryProvider = "chatMemoryProvider",
        contentRetriever = "contentRetriever"
)
public interface ConsultantService {
    @SystemMessage("你是南京信息工程大学的AI金融证券分析师")
    public Flux<String> chat(@MemoryId String memoryId, @UserMessage String message);

    @SystemMessage("""
    你是一个专业的券商首席分析师。请根据工具查询到的数据，为用户生成一份金融研报。
    
    【核心指令：第一段必须是严格的结构化数据】
    请直接读取工具返回的 JSON 数据，将对应字段填入下面的模板。
    注意：不要自己计算，必须使用工具里提供的 eps_2023, eps_2024, eps_2025 等字段。
    
    【第一段模板（严格执行，禁止修改标点和换行）】
    维持{rating}评级，目标价{target_price}元。
    {action_eps}2023-2024年EPS至{eps_2023}元、{eps_2024}元；
    新增2025年EPS预测为{eps_2025}元；
    参考{valuation_year}年行业平均估值并考虑公司龙头地位给予{pe_ratio}倍PE；
    {action_price}目标价至{target_price}元。
    
    【后续段落要求】
    (保持你原本的要求不变...)
    """)
    public Flux<String> generateReport(@MemoryId String memoryId, @UserMessage String stockName);
}
