-- 单库升级：暂停创建/统计消费与落库后执行；可重复运行，不删除业务数据。
CREATE TABLE IF NOT EXISTS t_link_dedup (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS t_stats_flush_batch (
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (batch_id)
) ENGINE=InnoDB;
-- 历史同一 URL 存在多个短码时选择一个作为规范短码；已有预约不覆盖。
INSERT IGNORE INTO t_link_dedup (dedup_key, short_uri)
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256), MIN(short_uri)
FROM t_link WHERE del_flag = 0
GROUP BY SHA2(CONCAT(gid, CHAR(0), origin_url), 256);
