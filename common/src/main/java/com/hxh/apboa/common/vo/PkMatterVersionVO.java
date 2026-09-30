package com.hxh.apboa.common.vo;

import com.hxh.apboa.common.config.SerializableEnable;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PkMatterVersionVO implements SerializableEnable {
    private Long id;
    private Long matterId;
    private Integer versionNo;
    private String label;
    private String pathRel;
    private String note;
    private LocalDateTime createdAt;
}
