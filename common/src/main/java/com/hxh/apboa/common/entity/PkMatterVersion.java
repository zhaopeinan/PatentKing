package com.hxh.apboa.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxh.apboa.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(TableConst.PK_MATTER_VERSION)
public class PkMatterVersion extends BaseTenantEntity {

    private Long matterId;

    private Integer versionNo;

    private String label;

    private String pathRel;

    private String note;
}
