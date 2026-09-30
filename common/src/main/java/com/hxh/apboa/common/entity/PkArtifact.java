package com.hxh.apboa.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxh.apboa.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(TableConst.PK_ARTIFACT)
public class PkArtifact extends BaseTenantEntity {

    private Long matterId;

    private Long versionId;

    /** disclosure_md | docx | report | figure | other */
    private String artifactType;

    private String name;

    private String storageUri;

    private String mime;

    private String checksum;

    private String metaJson;
}
