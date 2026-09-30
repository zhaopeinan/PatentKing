package com.hxh.apboa.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hxh.apboa.common.consts.TableConst;
import lombok.Getter;
import lombok.Setter;

/**
 * 成果转化案件
 */
@Getter
@Setter
@TableName(TableConst.PK_MATTER)
public class PkMatter extends BaseTenantEntity {

    /** 案件标题 */
    private String title;

    /**
     * disclosure | paper2patent | radar | oa
     * （历史 read/valuate/dd/match 创建时会归一到 disclosure，场景写入 metaJson.scene）
     */
    private String matterType;

    /**
     * draft | in_progress | awaiting_confirm | delivered | archived
     */
    private String status;

    private Long agentDefinitionId;

    /** 相对租户 workspace 的路径 */
    private String workspaceRelPath;

    /** 发明人 JSON 数组 */
    private String inventorsJson;

    private String metaJson;

    private String remark;
}
