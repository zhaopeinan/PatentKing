package com.hxh.apboa.common.vo;

import com.hxh.apboa.common.config.SerializableEnable;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PkArtifactVO implements SerializableEnable {
    private Long id;
    private Long matterId;
    private Long versionId;
    private String artifactType;
    private String name;
    private String storageUri;
    private String mime;
    private String metaJson;
    private LocalDateTime createdAt;
}
