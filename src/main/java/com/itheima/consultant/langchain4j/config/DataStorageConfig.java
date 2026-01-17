package com.itheima.consultant.langchain4j.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataStorageConfig {
    
    @Value("${financial.data.storage.path:financial_data_storage}")
    private String storagePath;
    
    public String getStoragePath() {
        return storagePath;
    }
    
    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }
}
