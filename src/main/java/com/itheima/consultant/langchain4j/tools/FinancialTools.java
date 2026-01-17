package com.itheima.consultant.langchain4j.tools;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itheima.consultant.langchain4j.config.DataStorageConfig;

import dev.langchain4j.agent.tool.Tool;

@Component("financialTools")
public class FinancialTools {

    @Value("${financial.alpha-vantage.api-key}")
    private String alphaApiKey;

    @Value("${financial.alpha-vantage.base-url}")
    private String alphaBaseUrl;

    @Value("${financial.tushare.api-key}")
    private String tushareToken;

    @Value("${financial.tushare.base-url:https://api.tushare.pro}")
    private String tushareUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DataStorageConfig dataStorageConfig;

    private static final double INDUSTRY_PE = 20.0;
    private static final double DEFAULT_USD_CNY_RATE = 7.25;
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 1000;

    // RestTemplate 初始化（直连，无代理）
    public FinancialTools() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Tool("获取指定股票(stockCode)在特定报告期(reportDate, 格式yyyyMMdd)的财务预测和估值数据。")
    public String getFinancialForecast(String stockCode, String reportDate) {
        String targetDate = (reportDate == null || reportDate.isEmpty() || "null".equals(reportDate))
                ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                : reportDate;

        System.out.println("🤖 [FinancialTools] AI 请求查询：" + stockCode + "，日期：" + targetDate);

        if (isAShare(stockCode)) {
            System.out.println("👉 识别为A股，调用Tushare接口...");
            return getAShareData(stockCode, targetDate);
        } else {
            System.out.println("👉 识别为美股，调用Alpha Vantage接口...");
            return getUSStockData(stockCode);
        }
    }

    // ==================== A股数据处理 ====================
    private String getAShareData(String stockCode, String reportDate) {
        try {
            // 调用income接口
            String incomeBody = String.format(
                    "{\"api_name\": \"income\", \"token\": \"%s\", \"params\": {\"ts_code\": \"%s\", \"period\": \"%s\", \"fields\": \"total_revenue,n_income,total_mv,end_date\"}}",
                    tushareToken, stockCode, reportDate);

            JsonNode incomeRoot = callTushareApiWithRetry(incomeBody);

            if (isEmptyData(incomeRoot)) {
                return buildErrorJson("数据缺失", "未查询到 " + stockCode + " 在 " + reportDate + " 的财务数据");
            }

            JsonNode incomeData = incomeRoot.path("data").path("items").get(0);
            double revenue = parseDoubleSafe(incomeData.get(0).asText(), 0.0);
            double netIncome = parseDoubleSafe(incomeData.get(1).asText(), 0.0);
            double totalMv = parseDoubleSafe(incomeData.get(2).asText(), 0.0);
            String actualDate = incomeData.get(3).asText();

            System.out.println("✅ 成功获取财报数据，实际报告期: " + actualDate);

            double epsCurrent = 0.0;
            double peCalculated = 0.0;
            double currentPrice = 0.0;
            double totalShare = 0.0;

            // 获取最新股价
            String dailyBody = String.format(
                    "{\"api_name\": \"daily\", \"token\": \"%s\", \"params\": {\"ts_code\": \"%s\", \"start_date\": \"%s\", \"end_date\": \"%s\", \"fields\": \"ts_code,trade_date,close\"}}",
                    tushareToken, stockCode, calculateStartDate(reportDate, 30), reportDate);

            JsonNode dailyRoot = callTushareApiWithRetry(dailyBody);

            if (!isEmptyData(dailyRoot)) {
                JsonNode dailyData = dailyRoot.path("data").path("items").get(0);
                currentPrice = parseDoubleSafe(dailyData.get(2).asText(), 0.0);

                if (currentPrice > 0) {
                    totalShare = totalMv / currentPrice;
                    epsCurrent = netIncome / totalShare;
                    if (netIncome > 0) {
                        peCalculated = totalMv / netIncome;
                    }
                }
            }

            // 返回JSON
            return generateAnalysisResult(stockCode, reportDate, currentPrice, epsCurrent, peCalculated,
                    revenue / 100000000.0, netIncome / 100000000.0, "Tushare季度财报");

        } catch (Exception e) {
            e.printStackTrace();
            return buildErrorJson("API异常", "Tushare调用失败: " + e.getMessage());
        }
    }

    // ==================== 美股数据处理 ====================
    private String getUSStockData(String stockCode) {
        try {
            double exchangeRate = getUsdToCnyRate();
            System.out.println("💱 当前应用汇率 (USD->CNY): " + exchangeRate);

            // 公司概况
            String overviewUrl = String.format("%s?function=OVERVIEW&symbol=%s&apikey=%s", alphaBaseUrl, stockCode,
                    alphaApiKey);
            JsonNode overview = objectMapper.readTree(restTemplate.getForObject(overviewUrl, String.class));

            if (overview.isEmpty() || !overview.has("Symbol")) {
                return buildErrorJson("数据缺失", "AlphaVantage Overview无数据，请检查美股代码");
            }

            // 实时报价
            String quoteUrl = String.format("%s?function=GLOBAL_QUOTE&symbol=%s&apikey=%s", alphaBaseUrl, stockCode,
                    alphaApiKey);
            JsonNode quoteRoot = objectMapper.readTree(restTemplate.getForObject(quoteUrl, String.class));

            double currentPriceUsd = 0.0;
            if (quoteRoot.has("Global Quote") && quoteRoot.path("Global Quote").has("05. price")) {
                currentPriceUsd = parseDoubleSafe(quoteRoot.path("Global Quote").path("05. price").asText(), 0.0);
            } else {
                currentPriceUsd = parseDoubleSafe(overview.path("50DayMovingAverage").asText(), 0.0);
            }

            double peRatio = parseDoubleSafe(overview.path("PERatio").asText(), 20.0);
            double epsUsd = parseDoubleSafe(overview.path("EPS").asText(), 0.0);
            double revenueUsd = parseDoubleSafe(overview.path("RevenueTTM").asText(), 0.0);
            double profitUsd = parseDoubleSafe(overview.path("GrossProfitTTM").asText(), 0.0);

            double currentPriceCny = currentPriceUsd * exchangeRate;
            double epsCny = epsUsd * exchangeRate;
            double revenueCnyBillion = (revenueUsd * exchangeRate) / 1_000_000_000.0;
            double profitCnyBillion = (profitUsd * exchangeRate) / 1_000_000_000.0;

            return generateAnalysisResult(stockCode, "Latest", currentPriceCny, epsCny, peRatio, revenueCnyBillion,
                    profitCnyBillion, "Alpha Vantage");

        } catch (Exception e) {
            e.printStackTrace();
            return buildErrorJson("API异常", "美股数据获取失败: " + e.getMessage());
        }
    }

    // ==================== 核心计算与 JSON ====================
    private String generateAnalysisResult(String stockCode, String date, double currentPrice, double epsCurrent,
            double currentPe, double revenue, double profit, String source) {

        double eps2023 = epsCurrent * 0.85;
        double eps2024 = epsCurrent;
        double eps2025 = epsCurrent * 1.15;
        double targetPrice = BigDecimal.valueOf(eps2025).multiply(BigDecimal.valueOf(INDUSTRY_PE))
                .setScale(2, RoundingMode.HALF_UP).doubleValue();

        String rating = calculateRating(currentPrice, targetPrice);
        String actionEps = calculateEpsAction(eps2023, eps2024);
        String actionPrice = calculatePriceAction(currentPrice, targetPrice);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("stock_code", stockCode);
        root.put("report_date", date);
        root.put("rating", rating);
        root.put("target_price", round(targetPrice));
        root.put("current_price", round(currentPrice));
        root.put("action_eps", actionEps);
        root.put("action_price", actionPrice);
        root.put("eps_2023", round(eps2023));
        root.put("eps_2024", round(eps2024));
        root.put("eps_2025", round(eps2025));
        root.put("valuation_year", 2025);
        root.put("pe_ratio", round(currentPe));

        ObjectNode opData = root.putObject("operating_data");
        opData.put("revenue", String.format("%.2f亿元", revenue));
        opData.put("net_profit", String.format("%.2f亿元", profit));
        opData.put("data_source", source);
        opData.put("season_desc", "基于" + source + "数据计算");

        String jsonResult = root.toString();
        System.out.println("\n📦 [Tool 生成的 JSON]: \n" + jsonResult + "\n");
        storeDataToFile(stockCode, date, jsonResult);

        return jsonResult;
    }

    private String calculateRating(double currentPrice, double targetPrice) {
        if (currentPrice <= 0)
            return "中性";
        double change = (targetPrice - currentPrice) / currentPrice;
        if (change > 0.20)
            return "买入";
        if (change > 0.10)
            return "增持";
        if (change > -0.10)
            return "中性";
        if (change > -0.20)
            return "减持";
        return "卖出";
    }

    private String calculateEpsAction(double epsPrevious, double epsCurrent) {
        if (epsPrevious <= 0 || epsCurrent <= 0)
            return "维持";
        double change = (epsCurrent - epsPrevious) / epsPrevious;
        if (change > 0.05)
            return "上调";
        if (change < -0.05)
            return "下调";
        return "维持";
    }

    private String calculatePriceAction(double currentPrice, double targetPrice) {
        if (currentPrice <= 0)
            return "维持";
        if (targetPrice > currentPrice * 1.10)
            return "上调";
        if (targetPrice < currentPrice * 0.90)
            return "下调";
        return "维持";
    }

    private String buildErrorJson(String error, String msg) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("error", error);
        node.put("error_message", msg);
        node.put("status", "failed");
        node.put("season_desc", "API获取失败");
        return node.toString();
    }

    private String calculateStartDate(String endDate, int days) {
        try {
            LocalDate end = LocalDate.parse(endDate, DateTimeFormatter.ofPattern("yyyyMMdd"));
            return end.minusDays(days).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        } catch (Exception e) {
            System.err.println("日期解析错误: " + endDate);
            return endDate;
        }
    }

    private JsonNode callTushareApiWithRetry(String body) throws Exception {
        int retryCount = 0;
        Exception lastException = null;
        while (retryCount < MAX_RETRY_COUNT) {
            try {
                return callTushareApi(body);
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                if (retryCount < MAX_RETRY_COUNT) {
                    System.err.println("⚠️ API调用失败，第" + retryCount + "次重试...");
                    Thread.sleep(RETRY_DELAY_MS);
                }
            }
        }
        throw new Exception("API调用失败，已重试" + MAX_RETRY_COUNT + "次", lastException);
    }

    private JsonNode callTushareApi(String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3");
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        System.out.println("👉 正在连接 Tushare 地址: " + tushareUrl);
        try {
            String response = restTemplate.postForObject(tushareUrl, entity, String.class);
            JsonNode root = objectMapper.readTree(response);
            if (root.has("code") && root.get("code").asInt() != 0) {
                String msg = root.has("msg") ? root.get("msg").asText() : "未知错误";
                throw new RuntimeException("API返回错误: " + msg);
            }
            return root;
        } catch (Exception e) {
            throw new RuntimeException("API调用异常: " + e.getMessage(), e);
        }
    }

    private double getUsdToCnyRate() {
        try {
            String url = String.format("%s?function=CURRENCY_EXCHANGE_RATE&from_currency=USD&to_currency=CNY&apikey=%s",
                    alphaBaseUrl, alphaApiKey);
            JsonNode root = objectMapper.readTree(restTemplate.getForObject(url, String.class));
            JsonNode rateNode = root.path("Realtime Currency Exchange Rate").path("5. Exchange Rate");
            if (!rateNode.isMissingNode()) {
                return rateNode.asDouble();
            }
        } catch (Exception e) {
            System.err.println("⚠️ 汇率API调用失败，使用默认汇率: " + DEFAULT_USD_CNY_RATE);
        }
        return DEFAULT_USD_CNY_RATE;
    }

    private boolean isAShare(String code) {
        return code != null && Character.isDigit(code.charAt(0));
    }

    private boolean isEmptyData(JsonNode tushareRoot) {
        return !tushareRoot.has("code") || tushareRoot.get("code").asInt() != 0
                || tushareRoot.path("data").path("items").isEmpty();
    }

    private double parseDoubleSafe(String val, double def) {
        try {
            if (val == null || "null".equals(val) || val.isEmpty())
                return def;
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private double round(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ==================== 数据存储 ====================
    private void ensureStorageDirectoryExists() {
        try {
            File dir = new File(dataStorageConfig.getStoragePath());
            if (!dir.exists()) {
                dir.mkdirs();
                System.out.println("📁 创建数据存储目录: " + dir.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("⚠️ 无法创建数据存储目录: " + e.getMessage());
        }
    }

    private void storeDataToFile(String stockCode, String reportDate, String jsonData) {
        ensureStorageDirectoryExists();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = String.format("%s_%s_%s.json", stockCode, reportDate, timestamp);
        String filePath = dataStorageConfig.getStoragePath() + File.separator + fileName;
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(jsonData);
            System.out.println("💾 数据已存储到文件: " + filePath);
        } catch (IOException e) {
            System.err.println("⚠️ 数据存储失败: " + e.getMessage());
        }
    }
}
