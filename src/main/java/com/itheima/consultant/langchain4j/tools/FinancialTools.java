package com.itheima.consultant.langchain4j.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.Tool;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component("financialTools")
public class FinancialTools {

    // ========== Alpha Vantage 配置 (美股) ==========
    @Value("${financial.alpha-vantage.api-key}")
    private String alphaApiKey;

    @Value("${financial.alpha-vantage.base-url}")
    private String alphaBaseUrl;

    // ========== Tushare 配置 (A股) ==========
    @Value("${financial.tushare.api-key:invalid-token}")
    private String tushareToken;

    @Value("${financial.tushare.base-url:http://api.tushare.pro}")
    private String tushareUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 假设行业平均 PE (用于计算目标价)
    private static final double INDUSTRY_PE = 20.0;

    /**
     * 核心工具：智能研报数据生成器
     */
    @Tool("获取指定股票在特定报告期(reportDate)的财务数据。支持美股和A股。reportDate格式必须为YYYYMMDD(例如20240930代表三季报)。")
    public String getFinancialForecast(String stockCode, String reportDate) {
        // 1. 处理默认日期
        String targetDate = (reportDate == null || reportDate.isEmpty() || reportDate.equals("null")) 
                            ? "20240930" : reportDate;
        
        System.out.println("🤖 [FinancialTools] AI 请求查询股票：" + stockCode + "，报告期：" + targetDate);

        // 2. 智能路由：判断是 A股 还是 美股
        if (isAShare(stockCode)) {
            System.out.println("👉 识别为中国 A 股，尝试调用 Tushare 接口...");
            return getTushareData(stockCode, targetDate);
        } else {
            System.out.println("👉 识别为美股，尝试调用 Alpha Vantage 接口...");
            return getAlphaVantageData(stockCode);
        }
    }

    // ==================== Tushare 逻辑 (A股) ====================
    private String getTushareData(String stockCode, String date) {
        try {
            // Tushare API 请求体
            String requestBody = String.format("""
                {
                    "api_name": "income", 
                    "token": "%s",
                    "params": {
                        "ts_code": "%s",
                        "period": "%s", 
                        "fields": "ts_code,end_date,total_revenue,n_income"
                    }
                }
                """, tushareToken, stockCode, date);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            // 发送请求
            String jsonResponse = restTemplate.postForObject(tushareUrl, entity, String.class);
            System.out.println("🔍 [Tushare Raw Response]: " + jsonResponse);

            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            
            // 检查错误
            if (rootNode.has("code") && rootNode.get("code").asInt() != 0) {
                System.out.println("❌ Tushare 报错：" + rootNode.path("msg").asText());
                return getMockData(stockCode, date); 
            }

            JsonNode items = rootNode.path("data").path("items");

            if (items.isEmpty()) {
                System.out.println("⚠️ Tushare 未查到数据，切换至模拟数据...");
                return getMockData(stockCode, date);
            }

            // 提取真实数据
            JsonNode data = items.get(0);
            double revenue = parseDoubleSafe(data.get(2).asText(), 0.0) / 100000000.0; // 转亿元
            double profit = parseDoubleSafe(data.get(3).asText(), 0.0) / 100000000.0;  // 转亿元

            // 动态计算
            double totalShare = 29.11; 
            double currentEps = profit / totalShare; 
            double eps2025 = (currentEps * 4) * 1.2; 
            double targetPrice = calculateTargetPrice(eps2025, INDUSTRY_PE);

            return String.format("""
                {
                    "stock_code": "%s",
                    "report_date": "%s",
                    "rating": "买入",
                    "target_price": %.2f,
                    "target_logic": "公式计算: EPS2025(%.2f) * PE(%.0f)",
                    "eps_2025": %.2f,
                    "valuation_year": 2025, 
                    "pe_ratio": %.0f,
                    "operating_data": {
                        "revenue": "%.2f亿元",
                        "net_profit": "%.2f亿元",
                        "season_desc": "基于Tushare真实数据生成"
                    }
                }
                """, stockCode, date, targetPrice, eps2025, INDUSTRY_PE, eps2025, INDUSTRY_PE, revenue, profit);

        } catch (Exception e) {
            e.printStackTrace();
            return getMockData(stockCode, date);
        }
    }

    // ==================== Alpha Vantage 逻辑 (美股) ====================
    private String getAlphaVantageData(String stockCode) {
        try {
            String url = String.format("%s?function=OVERVIEW&symbol=%s&apikey=%s", alphaBaseUrl, stockCode, alphaApiKey);
            String jsonResponse = restTemplate.getForObject(url, String.class);
            JsonNode rootNode = objectMapper.readTree(jsonResponse);

            if (rootNode.isEmpty() || !rootNode.has("Symbol")) {
                return getMockData(stockCode, "最新");
            }
            
            double currentPE = parseDoubleSafe(rootNode.path("PERatio").asText(), 20.0);
            double currentEPS = parseDoubleSafe(rootNode.path("EPS").asText(), 1.0);
            double growthRate = 1.15;
            double eps2025 = currentEPS * growthRate * growthRate;
            double targetPrice = calculateTargetPrice(eps2025, currentPE);
            
            return String.format("""
                {
                    "stock_code": "%s", "report_date": "最新",
                    "rating": "买入", "target_price": %.2f,
                    "action_eps": "上调", 
                    "eps_2025": %.2f,
                    "pe_ratio": %.2f,
                    "operating_data": { 
                        "revenue": "美股暂无", 
                        "season_desc": "AlphaVantage实时数据" 
                    }
                }
                """, stockCode, targetPrice, eps2025, currentPE);

        } catch (Exception e) {
            return getMockData(stockCode, "最新");
        }
    }

    // ==================== Mock 兜底数据 ====================
    private String getMockData(String stockCode, String date) {
        double mockEps2025 = 17.21;
        double mockPe = 20.0;
        double mockTarget = calculateTargetPrice(mockEps2025, mockPe);
        
        String seasonName = "最新季度";
        if (date.endsWith("0331")) seasonName = "一季报";
        else if (date.endsWith("0630")) seasonName = "半年报";
        else if (date.endsWith("0930")) seasonName = "三季报";
        else if (date.endsWith("1231")) seasonName = "年报";

        return String.format("""
            {
                "stock_code": "%s",
                "report_date": "%s",
                "rating": "买入(模拟)",
                "target_price": %.2f,
                "eps_2023": 10.20, "eps_2024": 13.82, "eps_2025": %.2f,
                "valuation_year": 2025, "pe_ratio": %.0f, 
                "operating_data": {
                    "revenue": "1949.85亿元(模拟)",
                    "net_profit": "78.23亿元(模拟)",
                    "season_desc": "⚠️API获取失败，这是%s的模拟数据"
                }
            }
            """, stockCode, date, mockTarget, mockEps2025, mockPe, seasonName);
    }

    // ==================== 辅助方法 ====================
    private double calculateTargetPrice(double eps, double pe) {
        return BigDecimal.valueOf(eps)
                .multiply(BigDecimal.valueOf(pe))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private boolean isAShare(String code) {
        return code != null && !code.isEmpty() && Character.isDigit(code.charAt(0));
    }

    private double parseDoubleSafe(String value, double defaultValue) {
        try {
            if (value == null || value.equalsIgnoreCase("None") || value.equalsIgnoreCase("null")) {
                return defaultValue;
            }
            return Double.parseDouble(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}