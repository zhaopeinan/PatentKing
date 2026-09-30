package com.hxh.apboa.common.vo;

import com.hxh.apboa.common.config.SerializableEnable;
import lombok.Data;

/**
 * Tokenlab 生图配置（返回给前端，不含完整 API Key）。
 */
@Data
public class PkTokenlabSettingsVO implements SerializableEnable {

    /** 是否已配置 API Key */
    private Boolean apiKeyConfigured;
    private String baseUrl;
    private String fallbackBaseUrl;
    private String model;
    private String size;
    private String quality;
    private String network;
    private String proxy;
}
