CREATE TABLE IF NOT EXISTS `pk_matter` (
  `id` bigint NOT NULL COMMENT '主键',
  `title` varchar(255) NOT NULL COMMENT '案件标题',
  `matter_type` varchar(32) NOT NULL DEFAULT 'disclosure' COMMENT '案件类型',
  `status` varchar(32) NOT NULL DEFAULT 'draft' COMMENT '状态',
  `agent_definition_id` bigint DEFAULT NULL COMMENT '绑定智能体',
  `workspace_rel_path` varchar(512) DEFAULT NULL COMMENT '工作区相对路径',
  `inventors_json` text COMMENT '发明人 JSON',
  `meta_json` mediumtext COMMENT '扩展元数据',
  `remark` varchar(1000) DEFAULT NULL COMMENT '备注',
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pk_matter_tenant_type` (`tenant_id`, `matter_type`),
  KEY `idx_pk_matter_tenant_status` (`tenant_id`, `status`)
) COMMENT='PatentKing 成果转化案件';

CREATE TABLE IF NOT EXISTS `pk_matter_version` (
  `id` bigint NOT NULL COMMENT '主键',
  `matter_id` bigint NOT NULL COMMENT '案件ID',
  `version_no` int NOT NULL COMMENT '版本号',
  `label` varchar(128) DEFAULT NULL COMMENT '版本说明',
  `path_rel` varchar(512) DEFAULT NULL COMMENT '版本目录相对路径',
  `note` varchar(1000) DEFAULT NULL COMMENT '备注',
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pk_matter_version` (`matter_id`, `version_no`),
  KEY `idx_pk_matter_version_tenant` (`tenant_id`)
) COMMENT='PatentKing 案件版本';

CREATE TABLE IF NOT EXISTS `pk_artifact` (
  `id` bigint NOT NULL COMMENT '主键',
  `matter_id` bigint NOT NULL COMMENT '案件ID',
  `version_id` bigint DEFAULT NULL COMMENT '版本ID',
  `artifact_type` varchar(64) NOT NULL DEFAULT 'other' COMMENT '产物类型',
  `name` varchar(255) NOT NULL COMMENT '文件名',
  `storage_uri` varchar(1024) DEFAULT NULL COMMENT '存储路径',
  `mime` varchar(128) DEFAULT NULL COMMENT 'MIME',
  `checksum` varchar(128) DEFAULT NULL COMMENT '校验和',
  `meta_json` mediumtext COMMENT '扩展元数据',
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `tenant_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pk_artifact_matter` (`matter_id`),
  KEY `idx_pk_artifact_tenant` (`tenant_id`)
) COMMENT='PatentKing 案件产物';
