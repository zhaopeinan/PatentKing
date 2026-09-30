package com.hxh.apboa.common.vo;

import com.hxh.apboa.common.config.SerializableEnable;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PkMatterVO implements SerializableEnable {
    private Long id;
    private String title;
    private String matterType;
    private String status;
    private Long agentDefinitionId;
    private String workspaceRelPath;
    private String inventorsJson;
    private String metaJson;
    private String remark;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
