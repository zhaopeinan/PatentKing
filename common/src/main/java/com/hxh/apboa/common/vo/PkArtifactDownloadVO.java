package com.hxh.apboa.common.vo;

import com.hxh.apboa.common.config.SerializableEnable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 案件产物下载载荷（服务层返回，Controller 写出文件流）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PkArtifactDownloadVO implements SerializableEnable {
    private String filename;
    private String mime;
    private byte[] bytes;
}
