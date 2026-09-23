SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS shortlink_0 DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS shortlink_1 DEFAULT CHARACTER SET utf8mb4;

CREATE TABLE IF NOT EXISTS shortlink_0.t_link_dedup_0 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_dedup_1 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_dedup_2 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_dedup_3 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_dedup_4 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_dedup_5 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_dedup_6 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_dedup_7 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_dedup_0 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_dedup_1 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_dedup_2 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_dedup_3 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_dedup_4 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_dedup_5 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_dedup_6 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_dedup_7 (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS shortlink_0.t_stats_flush_batch (
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS shortlink_0.t_link_0 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_1 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_2 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_3 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_4 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_5 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_6 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_7 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_0 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_1 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_2 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_3 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_4 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_5 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_6 LIKE shortlink.t_link;
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_7 LIKE shortlink.t_link;

CREATE TABLE IF NOT EXISTS shortlink_0.t_link_goto_0 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_goto_1 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_goto_2 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_goto_3 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_goto_4 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_goto_5 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_goto_6 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_goto_7 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_goto_0 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_goto_1 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_goto_2 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_goto_3 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_goto_4 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_goto_5 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_goto_6 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';
CREATE TABLE IF NOT EXISTS shortlink_1.t_link_goto_7 (
  id BIGINT NOT NULL, gid VARCHAR(32) NOT NULL,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  PRIMARY KEY (id), UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='gid->short_uri 异构索引';

CREATE TABLE IF NOT EXISTS shortlink_0.t_local_message_0 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_0.t_local_message_1 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_0.t_local_message_2 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_0.t_local_message_3 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_0.t_local_message_4 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_0.t_local_message_5 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_0.t_local_message_6 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_0.t_local_message_7 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_1.t_local_message_0 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_1.t_local_message_1 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_1.t_local_message_2 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_1.t_local_message_3 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_1.t_local_message_4 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_1.t_local_message_5 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_1.t_local_message_6 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
CREATE TABLE IF NOT EXISTS shortlink_1.t_local_message_7 (
  id BIGINT NOT NULL, biz_type VARCHAR(32) NOT NULL,
  shard_key CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  payload VARCHAR(512) NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, retry_count TINYINT NOT NULL DEFAULT 0,
  next_retry DATETIME NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_status_retry (status, next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';

CREATE TABLE IF NOT EXISTS shortlink_0.t_id_segment (
  biz_tag VARCHAR(64) NOT NULL, max_id BIGINT NOT NULL DEFAULT 0,
  step INT NOT NULL DEFAULT 1000, version BIGINT NOT NULL DEFAULT 0,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (biz_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO shortlink_0.t_id_segment (biz_tag,max_id,step) VALUES ('short_link',0,1000)
  ON DUPLICATE KEY UPDATE biz_tag=biz_tag;
CREATE TABLE IF NOT EXISTS shortlink_0.t_link_stats_daily (
  id BIGINT NOT NULL AUTO_INCREMENT, short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  stat_date DATE NOT NULL, hour TINYINT NOT NULL DEFAULT -1,
  pv INT NOT NULL DEFAULT 0, uv INT NOT NULL DEFAULT 0, uip INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_uri_date_hour (short_uri,stat_date,hour)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
