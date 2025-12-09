package com.itheima.consultant.langchain4j.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.Tool;

@Component("financialTools")
public class FinancialTools {

    // 读取配置文件的 Key 和 URL
    @Value("${financial.alpha-vantage.api-key}")
    private String apiKey;

    @Value("${financial.alpha-vantage.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 工具 1：联网查询实时股价 (修复了之前的变量缺失问题)
     */
    @Tool("查询指定股票代码的实时价格，股票代码需要遵循AlphaVantage格式（如 NVDA, 002594.SH, 000858.SZ）")
    public String getStockPrice(String stockCode) {
        System.out.println("🤖 AI 正在联网查询股票：" + stockCode);

        try {
            // 1. 拼接 URL
            String url = String.format("%s?function=GLOBAL_QUOTE&symbol=%s&apikey=%s",
                    baseUrl, stockCode, apiKey);

            // 2. 发送请求
            String jsonResponse = restTemplate.getForObject(url, String.class);

            // 3. 解析 JSON (你之前缺的就是这几行)
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            JsonNode quoteNode = rootNode.path("Global Quote");

            // 检查是否查到了数据
            if (quoteNode.isMissingNode() || quoteNode.isEmpty()) {
                if (rootNode.has("Information")) {
                    return "接口调用受限：" + rootNode.get("Information").asText();
                }
                return "未查询到股票 " + stockCode + " 的数据。";
            }

            // 4. 提取数据 (定义变量)
            String symbol = quoteNode.path("01. symbol").asText();
            String price = quoteNode.path("05. price").asText();
            String changePercent = quoteNode.path("10. change percent").asText();
            String tradingDay = quoteNode.path("07. latest trading day").asText();

            // 5. 返回结果
            return String.format("股票 %s 在 %s 的最新价格为 %s USD，涨跌幅为 %s。",
                    symbol, tradingDay, price, changePercent);

        } catch (Exception e) {
            e.printStackTrace();
            return "查询出错：" + e.getMessage();
        }
    }

    /**
     * 工具 2：查询研报硬核数据 (修复了之前缺少 return 的问题)
     */
    /**
     * 通用版：针对任何股票，获取基础数据并进行简单的算法预测
     * 注意：因为免费API查不到分析师预测，这里采用“基于当前数据线性外推”的算法来模拟预测
     */
    @Tool("获取生成研报所需的关键财务指标预测数据，包括EPS、PE、评级和目标价")
    public String getFinancialForecast(String stockCode) {
        System.out.println("🤖 AI 正在通用计算研报数据：" + stockCode);

        try {
            // 1. 调用 Alpha Vantage 的 OVERVIEW 接口 (获取基本面数据)
            // 文档: https://www.alphavantage.co/documentation/#company-overview
            String url = String.format("%s?function=OVERVIEW&symbol=%s&apikey=%s",
                    baseUrl, stockCode, apiKey);

            String jsonResponse = restTemplate.getForObject(url, String.class);
            JsonNode rootNode = objectMapper.readTree(jsonResponse);

            // 检查是否查到数据
            if (rootNode.isEmpty() || !rootNode.has("Symbol")) {
                 return "无法获取该股票的基础财务数据，请确认代码是否为美股代码（如 IBM, NVDA）。注：AlphaVantage免费版对A股基本面支持较弱。";
            }

            // 2. 提取真实的基础数据 (如果API返回空，给个默认值防止报错)
            double currentEPS = parseDoubleSafe(rootNode.path("EPS").asText(), 1.0);
            double currentPE = parseDoubleSafe(rootNode.path("PERatio").asText(), 20.0);
            // 获取行业平均PE很难查，这里用个通用估算：如果公司PE高，就假设行业也高
            double industryPE = currentPE * 0.8; 

            // 3. 【核心通用逻辑】进行简单的算法预测 (模拟分析师思维)
            // 假设：未来两年每年增长 15% (通用成长股模型)
            double growthRate = 1.15; 
            
            double eps2023 = currentEPS; // 假设去年就是当前EPS
            double eps2024 = currentEPS * growthRate;
            double eps2025 = eps2024 * growthRate;

            // 目标价逻辑：预测EPS * 当前PE (或者给一点溢价)
            double targetPrice = eps2025 * currentPE;

            // 评级逻辑：如果 PEG < 1 (估值低) 就买入，否则增持
            String rating = (currentPE / (growthRate * 100)) < 1 ? "买入" : "增持";
            String action = targetPrice > (eps2023 * currentPE) ? "上调" : "维持";

            // 4. 组装通用 JSON
            return String.format("""
                   {
                       "stock_code": "%s",
                       "rating": "%s",
                       "target_price": %.2f,
                       "action": "%s",
                       
                       "eps_2023": %.2f,
                       "eps_2024": %.2f,
                       "eps_2025_forecast": %.2f,
                       
                       "valuation_year": 2024,
                       "pe_ratio": %.0f
                   }
                   """, 
                   stockCode, rating, targetPrice, action, 
                   eps2023, eps2024, eps2025, currentPE);

        } catch (Exception e) {
            e.printStackTrace();
            return "通用数据计算失败：" + e.getMessage();
        }
    }

    // 一个辅助小方法，防止字符串转数字报错
    private double parseDoubleSafe(String value, double defaultValue) {
        try {
            if (value == null || value.equals("None") || value.equals("null")) return defaultValue;
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}