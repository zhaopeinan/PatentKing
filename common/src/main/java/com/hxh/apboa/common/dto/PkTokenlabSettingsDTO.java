package com.hxh.apboa.common.dto;

import lombok.Data;

/**
 * Tokenlab 生图配置（租户级，用于 Word 图示 auto 模式）。
 */
@Data
public class PkTokenlabSettingsDTO {

    /** API Key；留空表示不修改已有密钥 */
    private String apiKey;
    /** 主 Base URL，默认 https://api.tokenlab.cc.cd/v1 */
    private String baseUrl;
    /** 备用 Base URL，默认 https://hk.tokenlab.ccwu.cc/v1 */
    private String fallbackBaseUrl;
    /** 模型，默认 gpt-image-2 */
    private String model;
    /** 尺寸，默认 1024x1024 */
    private String size;
    /** 质量：auto | low | medium | high */
    private String quality;
    /** 网络：direct | env | http-proxy | socks5-proxy */
    private String network;
    /** 代理地址（http-proxy / socks5-proxy 时使用） */
    private String proxy;
}
