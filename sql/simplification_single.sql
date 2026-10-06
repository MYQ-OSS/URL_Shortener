-- 可选、非破坏性单库升级：补齐主表列表索引，可重复执行。
-- 先 USE 实际业务库；本脚本不迁移分片、不删除旧表、旧字段或旧消息。
SET @sl_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 't_link' AND index_name = 'idx_gid_list'
);
SET @sl_index_sql = IF(@sl_index_exists = 0,
  'ALTER TABLE t_link ADD INDEX idx_gid_list (gid, del_flag, id)',
  'SELECT ''idx_gid_list already exists'' AS message');
PREPARE sl_index_statement FROM @sl_index_sql;
EXECUTE sl_index_statement;
DEALLOCATE PREPARE sl_index_statement;
