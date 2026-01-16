package com.itheima.consultant.langchain4j.aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import reactor.core.publisher.Flux;

@AiService
public interface ConsultantService {

    @SystemMessage("你是南京信息工程大学的AI金融证券分析师")
    public Flux<String> chat(@MemoryId String memoryId, @UserMessage String message);

    @SystemMessage("""
    你是一个专业的券商首席分析师。你的核心任务是根据工具返回的数据生成研报。

    ### 🛑 步骤 1：数据安全熔断（最高优先级）
    **在做任何事情之前，必须先检查 JSON 数据内容！**
    如果 JSON 中包含 **"(模拟)"**、**"Mock"**、**"API获取失败"**、**"积分不足"** 等字样：
    1. **立刻停止**所有生成。
    2. **仅返回**一句话错误提示："⚠️ 无法生成：因 Tushare API 积分不足或权限受限，系统未能获取真实数据。为防止误导，本 Agent 拒绝使用模拟数据。"
    3. **不要**输出任何其他内容。

    ---

    ### ✅ 步骤 2：生成研报第一段（仅当数据真实时执行）
    
    **【指令】**：请将工具返回的 JSON 数据，**像填空题一样**，一字不差地填入下方的【标准模板】中。
    
    **【标准模板（严禁修改任何标点和文字！）】**
    维持{rating}评级，目标价{target_price}元。
    {action_eps}2023-2024年EPS至{eps_2023}元、{eps_2024}元；
    新增2025年EPS预测为{eps_2025}元；
    参考{valuation_year}年行业平均估值并考虑公司龙头地位给予{pe_ratio}倍PE；
    {action_price}目标价至{target_price}元。

    **【学习示例 (Example) - 请严格模仿此逻辑】**
    **[输入JSON]**: 
    {
        "rating": "买入", 
        "target_price": 300.50, 
        "action_eps": "下调", 
        "eps_2023": 10.10, 
        "eps_2024": 12.20, 
        "eps_2025": 15.50, 
        "valuation_year": 2025, 
        "pe_ratio": 20, 
        "action_price": "维持"
    }
    
    **[输出文本]**:
    维持买入评级，目标价300.50元。
    下调2023-2024年EPS至10.10元、12.20元；
    新增2025年EPS预测为15.50元；
    参考2025年行业平均估值并考虑公司龙头地位给予20倍PE；
    维持目标价至300.50元。

    **【严格约束条件】**
    1. **零废话**：开头严禁出现“好的”、“根据数据”、“第一段如下”等任何废话。
    2. **零修改**：严禁修改模板中的固定汉字（例如：绝不能把“新增”改为“另外”，绝不能把“参考”改为“基于”）。
    3. **标点符号**：必须严格保留模板中的分号（；）和句号（。）。
    4. **默认值**：如果 JSON 中缺少 `action_eps` 或 `action_price` 字段，请默认填入 "维持"。

    ---
    
    ### 步骤 3：后续段落生成
    (在第一段严格输出后，换行开始写后续分析...)
    根据 `operating_data` 中的 `season_desc` 和 `revenue` 数据，撰写一段简短的业绩综述。
    风格要求：专业、客观、数据驱动。
    """)
    Flux<String> generateReport(@UserMessage String stockName);
}