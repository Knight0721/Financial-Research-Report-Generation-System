package com.itheima.consultant.langchain4j.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.Tool;

@Component("financialTools")
public class FinancialTools {

    @Value("${financial.alpha-vantage.api-key}")
    private String alphaApiKey;
    @Value("${financial.alpha-vantage.base-url}")
    private String alphaBaseUrl;
    
    @Value("${financial.tushare.api-key:invalid-token}")
    private String tushareToken;
    @Value("${financial.tushare.base-url:http://api.tushare.pro}")
    private String tushareUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 假设行业平均 PE
    private static final double INDUSTRY_PE = 20.0;
    // 默认兜底汇率 (防止API超频)
    private static final double DEFAULT_USD_CNY_RATE = 7.25;

    @Tool("获取指定股票在特定报告期(reportDate)的财务数据。")
    public String getFinancialForecast(String stockCode, String reportDate) {
        String targetDate = (reportDate == null || reportDate.isEmpty() || reportDate.equals("null")) ? "20230930" : reportDate;
        System.out.println("🤖 [FinancialTools] AI 请求查询：" + stockCode + "，日期：" + targetDate);

        if (isAShare(stockCode)) {
            return getTushareHybridStrategy(stockCode, targetDate);
        } else {
            // 美股逻辑入口
            System.out.println("👉 识别为美股，调用 Alpha Vantage 并进行汇率换算...");
            return getAlphaVantageData(stockCode);
        }
    }

    // ==================== Tushare (保持之前的双保险逻辑) ====================
    private String getTushareHybridStrategy(String stockCode, String date) {
        // 1. 尝试高精度 income
        try {
            String requestBody = String.format("""
                {"api_name": "income", "token": "%s", "params": {"ts_code": "%s", "period": "%s", "fields": "ts_code,end_date,total_revenue,n_income"}}
                """, tushareToken, stockCode, date);
            
            JsonNode root = callApi(requestBody);
            if (root.has("code") && root.get("code").asInt() == 0 && !root.path("data").path("items").isEmpty()) {
                JsonNode data = root.path("data").path("items").get(0);
                double revenue = parseDoubleSafe(data.get(2).asText(), 0.0) / 100000000.0;
                double profit = parseDoubleSafe(data.get(3).asText(), 0.0) / 100000000.0;
                double eps2025 = (profit / 29.11 * 4) * 1.2; 
                double target = calculateTargetPrice(eps2025, INDUSTRY_PE);
                return buildJson(stockCode, date, target, eps2025, INDUSTRY_PE, revenue, profit, "基于Tushare财报真实数据");
            }
        } catch (Exception e) { /* 忽略错误，走降级 */ }

        // 2. 降级 daily_basic
        return getTushareDailyBasicWithFallback(stockCode, date);
    }

    private String getTushareDailyBasicWithFallback(String stockCode, String startDate) {
        int maxRetries = 5;
        String currentDate = startDate;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        for (int i = 0; i < maxRetries; i++) {
            try {
                String requestBody = String.format("""
                    {"api_name": "daily_basic", "token": "%s", "params": {"ts_code": "%s", "trade_date": "%s", "fields": "ts_code,trade_date,close,pe,total_mv"}}
                    """, tushareToken, stockCode, currentDate);
                
                JsonNode root = callApi(requestBody);
                if (root.has("code") && root.get("code").asInt() != 0) {
                    return String.format("{\"error\": \"API报错\", \"season_desc\": \"API获取失败(Mock触发)\"}");
                }

                JsonNode items = root.path("data").path("items");
                if (!items.isEmpty()) {
                    JsonNode data = items.get(0);
                    double realPrice = parseDoubleSafe(data.get(2).asText(), 0.0);
                    double realPe = parseDoubleSafe(data.get(3).asText(), 0.0);
                    double totalMv = parseDoubleSafe(data.get(4).asText(), 0.0) / 10000.0; 

                    double calculatedEps = (realPe > 0) ? (realPrice / realPe) : 0.0;
                    calculatedEps = BigDecimal.valueOf(calculatedEps).setScale(2, RoundingMode.HALF_UP).doubleValue();
                    double targetPrice = calculateTargetPrice(calculatedEps * 1.15, realPe);

                    return buildJson(stockCode, startDate, targetPrice, calculatedEps * 1.15, realPe, 0.0, totalMv / realPe, 
                        String.format("基于Tushare行情(股价%.2f, PE%.2f)反推", realPrice, realPe));
                }
                currentDate = LocalDate.parse(currentDate, formatter).minusDays(1).format(formatter);
            } catch (Exception e) { return getMockData(stockCode, startDate); }
        }
        return getMockData(stockCode, startDate);
    }

    // ==================== Alpha Vantage (含汇率转换) ====================
    private String getAlphaVantageData(String stockCode) {
        try {
            // 1. 获取汇率 (API 或 兜底)
            double exchangeRate = getUsdToCnyRate();
            System.out.println("💱 当前应用汇率 (USD->CNY): " + exchangeRate);

            // 2. 获取美股基本面 (OVERVIEW)
            String url = String.format("%s?function=OVERVIEW&symbol=%s&apikey=%s", alphaBaseUrl, stockCode, alphaApiKey);
            String jsonResponse = restTemplate.getForObject(url, String.class);
            JsonNode rootNode = objectMapper.readTree(jsonResponse);

            if (rootNode.isEmpty() || !rootNode.has("Symbol")) {
                return getMockData(stockCode, "最新");
            }
            
            // 3. 提取美元数据
            double currentPE = parseDoubleSafe(rootNode.path("PERatio").asText(), 20.0); // PE 不受汇率影响
            double currentEpsUsd = parseDoubleSafe(rootNode.path("EPS").asText(), 1.0);
            double revenueUsd = parseDoubleSafe(rootNode.path("RevenueTTM").asText(), 0.0); // 原始单位是"美元"

            // 4. 【核心步骤】转换为人民币
            double currentEpsCny = currentEpsUsd * exchangeRate;
            double revenueCnyBillion = (revenueUsd * exchangeRate) / 1000000000.0; // 转为"亿元人民币"

            // 5. 预测与定价 (基于人民币 EPS)
            double eps2025Cny = currentEpsCny * 1.15; // 假设 15% 增长
            double targetPriceCny = calculateTargetPrice(eps2025Cny, currentPE);
            
            // 6. 格式化输出 (Action 字段补全)
            return String.format("""
                {
                    "stock_code": "%s", "report_date": "最新",
                    "rating": "买入", 
                    "target_price": %.2f,
                    "action_eps": "上调", "action_price": "上调",
                    "eps_2023": %.2f, "eps_2024": %.2f, "eps_2025": %.2f,
                    "valuation_year": 2025, "pe_ratio": %.2f,
                    "operating_data": { 
                        "revenue": "%.2f亿元(人民币)", 
                        "net_profit": "暂无数据",
                        "season_desc": "基于AlphaVantage实时数据(汇率%.2f)换算" 
                    }
                }
                """, stockCode, targetPriceCny, 
                     currentEpsCny, currentEpsCny*1.1, eps2025Cny, 
                     currentPE, revenueCnyBillion, exchangeRate);

        } catch (Exception e) {
            e.printStackTrace();
            return getMockData(stockCode, "最新");
        }
    }

    // 辅助：获取实时汇率
    private double getUsdToCnyRate() {
        try {
            // 调用 Alpha Vantage 汇率接口
            String url = String.format("%s?function=CURRENCY_EXCHANGE_RATE&from_currency=USD&to_currency=CNY&apikey=%s", alphaBaseUrl, alphaApiKey);
            String res = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(res);
            JsonNode rateNode = root.path("Realtime Currency Exchange Rate").path("5. Exchange Rate");
            
            if (!rateNode.isMissingNode()) {
                double rate = Double.parseDouble(rateNode.asText());
                if (rate > 0) return rate; // 成功获取
            }
        } catch (Exception e) {
            System.out.println("⚠️ 汇率API调用失败，使用默认汇率: " + DEFAULT_USD_CNY_RATE);
        }
        return DEFAULT_USD_CNY_RATE; // 失败兜底
    }

    // ==================== 通用辅助方法 ====================
    private String buildJson(String code, String date, double target, double eps25, double pe, double rev, double prof, String desc) {
        return String.format("""
            {
                "stock_code": "%s", "report_date": "%s", "rating": "买入",
                "target_price": %.2f, "action_eps": "维持", "action_price": "上调",
                "eps_2023": %.2f, "eps_2024": %.2f, "eps_2025": %.2f,
                "valuation_year": 2025, "pe_ratio": %.0f,
                "operating_data": { "revenue": "%.2f亿元", "net_profit": "%.2f亿元", "season_desc": "%s" }
            }
            """, code, date, target, eps25/1.2/1.15, eps25/1.2, eps25, pe, rev, prof, desc);
    }

    private JsonNode callApi(String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return objectMapper.readTree(restTemplate.postForObject(tushareUrl, entity, String.class));
    }

    private String getMockData(String stockCode, String date) {
        return String.format("""
            {
                "rating": "买入(模拟)", "target_price": 300.00,
                "action_eps": "维持", "action_price": "维持",
                "eps_2023": 10.0, "eps_2024": 12.0, "eps_2025": 15.0,
                "valuation_year": 2025, "pe_ratio": 20,
                "operating_data": { "season_desc": "API获取失败(Mock触发)" }
            }
            """);
    }

    private double calculateTargetPrice(double eps, double pe) {
        return BigDecimal.valueOf(eps).multiply(BigDecimal.valueOf(pe)).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
    private boolean isAShare(String code) { return code != null && Character.isDigit(code.charAt(0)); }
    private double parseDoubleSafe(String value, double def) { try { return Double.parseDouble(value); } catch(Exception e) { return def; } }
}