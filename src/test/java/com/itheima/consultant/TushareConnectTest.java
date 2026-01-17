package com.itheima.consultant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tushare 接口连接测试类
 * 专门用于排查 https://jiaoch.site 代理网关的连接问题
 */
public class TushareConnectTest {
    @Test
    public static void main(String args) {
        // 1. 配置参数 (请确保 Token 与您购买的一致)
        String token = "8a38fa38e3966167986608ac01ded95d49ca9d3578f9942c09fc4762a90c";
        String apiUrl = "https://jiaoch.site"; 

        System.out.println("🚀 开始测试 Tushare 接口连接...");
        System.out.println("👉 目标地址: " + apiUrl);

        try {
            // 2. 初始化 RestTemplate
            RestTemplate restTemplate = new RestTemplate();
            ObjectMapper objectMapper = new ObjectMapper();

            // 3. 构建请求头 (关键：必须添加 User-Agent 伪装，否则会被服务器拒绝连接)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

            // 4. 构建请求体 (根据 Tushare 协议)
            // 示例：查询平安银行(000001.SZ)的日线行情
            String jsonBody = String.format(
                "{\"api_name\": \"daily\", \"token\": \"%s\", \"params\": {\"ts_code\": \"000001.SZ\", \"start_date\": \"20240101\", \"end_date\": \"20240110\"}}", 
                token
            );

            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

            // 5. 发送 POST 请求
            System.out.println("⏳ 正在发送请求，等待服务器响应...");
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, entity, String.class);

            // 6. 解析结果
            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("✅ 网络连接成功！状态码: " + response.getStatusCode());
                
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("code") && root.get("code").asInt() == 0) {
                    System.out.println("🎊 数据获取成功！");
                    System.out.println("📦 返回数据概要: " + response.getBody().substring(0, Math.min(200, response.getBody().length())) + "...");
                } else {
                    String errorMsg = root.has("msg")? root.get("msg").asText() : "未知错误";
                    System.err.println("❌ 接口逻辑错误: " + errorMsg);
                }
            } else {
                System.err.println("❌ 服务器响应异常，状态码: " + response.getStatusCode());
            }

        } catch (org.springframework.web.client.ResourceAccessException e) {
            System.err.println("🛑 连接被拒绝！请检查以下几点：");
            System.err.println("1. 确认 application.yml 中的 financial.proxy.enabled 已设为 false。");
            System.err.println("2. 检查 Windows 防火墙是否允许当前 Java 路径访问网络。");
            System.err.println("3. 尝试在浏览器直接打开 https://jiaoch.site 看看是否能访问。");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("💥 发生未预期异常:");
            e.printStackTrace();
        }
    }
}