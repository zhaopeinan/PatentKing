-- db_init.sql 已含 alias；增量环境可能尚未添加。幂等，避免 Duplicate column。
SET @pk_alias_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'skill_package'
      AND column_name = 'alias'
);
SET @pk_alias_sql := IF(
    @pk_alias_exists = 0,
    'ALTER TABLE `skill_package` ADD COLUMN `alias` VARCHAR(500) DEFAULT NULL COMMENT ''技能别名'' AFTER `name`',
    'SELECT 1'
);
PREPARE pk_alias_stmt FROM @pk_alias_sql;
EXECUTE pk_alias_stmt;
DEALLOCATE PREPARE pk_alias_stmt;
