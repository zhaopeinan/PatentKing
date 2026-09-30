-- 对话传附件依赖恰好一条 valid=1 的存储协议。
-- 本地 Docker 默认使用已挂载的 /app/.apboa（对应 docker/data/.apboa）。

UPDATE `storage_protocol`
SET `valid` = 0
WHERE `valid` = 1
  AND `id` NOT IN (
    SELECT `id` FROM (
      SELECT MIN(`id`) AS `id` FROM `storage_protocol` WHERE `valid` = 1
    ) AS `keep_one`
  );

INSERT INTO `storage_protocol` (
  `id`, `name`, `protocol`, `protocol_config`, `valid`, `tenant_id`, `create_at`, `update_at`, `remark`
)
SELECT
  2090100000000000020,
  '本地存储',
  'LOCAL',
  '{"localDir":"/app/.apboa/storage"}',
  1,
  1,
  NOW(),
  NOW(),
  'PatentKing 默认本地存储'
WHERE NOT EXISTS (
  SELECT 1 FROM `storage_protocol` WHERE `valid` = 1
);
