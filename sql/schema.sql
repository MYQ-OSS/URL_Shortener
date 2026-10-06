-- 单库主版本初始化脚本。仅创建缺失的表，不删除或迁移既有数据。
-- 创建防重与统计批次回执保留；号段表、gid 异构索引不再属于主版本。
-- short_uri 大小写敏感，旧短码不需要重新生成。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS t_stats_flush_batch (
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS t_link_dedup (
  dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  short_uri CHAR(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SHA256(gid+NUL+url) -> canonical short code';

CREATE TABLE IF NOT EXISTS t_link (
  id            BIGINT        NOT NULL                     COMMENT 'MyBatis-Plus ASSIGN_ID',
  short_uri     CHAR(6)       NOT NULL COLLATE utf8mb4_bin COMMENT '短码，不含域名。定长 6 位，大小写敏感',
  domain        VARCHAR(64)   NOT NULL DEFAULT 'localhost:8080',
  origin_url    VARCHAR(2048) NOT NULL,
  url_hash      BINARY(16)    NOT NULL                     COMMENT '历史兼容字段，防重使用 t_link_dedup',
  gid           VARCHAR(32)   NOT NULL                     COMMENT '分组 ID，管理链路查询维度',
  enable_status TINYINT       NOT NULL DEFAULT 0           COMMENT '0启用 1用户禁用 2风控封禁',
  valid_type    TINYINT       NOT NULL DEFAULT 0           COMMENT '0永久 1自定义有效期',
  valid_end     DATETIME      NULL,
  click_num     INT           NOT NULL DEFAULT 0           COMMENT '冗余总点击，统计任务定时回写；跳转链路不更新它',
  favicon       VARCHAR(256)  NULL,
  `describe`    VARCHAR(128)  NULL                         COMMENT 'describe 是保留字，必须反引号',
  create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  del_flag      TINYINT       NOT NULL DEFAULT 0           COMMENT '逻辑删除，保留历史统计数据',
  PRIMARY KEY (id),
  UNIQUE KEY uk_short_uri (short_uri),
  KEY idx_gid_list (gid, del_flag, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '短链主表';

CREATE TABLE IF NOT EXISTS t_local_message (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  biz_type    VARCHAR(32)  NOT NULL              COMMENT 'LINK_CACHE_INVALIDATE',
  shard_key   CHAR(6)      NOT NULL COLLATE utf8mb4_bin COMMENT '短码；保留历史列名以兼容旧表',
  payload     VARCHAR(512) NOT NULL,
  status      TINYINT      NOT NULL DEFAULT 0    COMMENT '0待处理 1成功',
  retry_count TINYINT      NOT NULL DEFAULT 0,
  next_retry  DATETIME     NOT NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_status_retry (status, next_retry)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表';

CREATE TABLE IF NOT EXISTS t_link_stats_daily (
  id        BIGINT  NOT NULL AUTO_INCREMENT,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin COMMENT '大小写敏感，与 t_link 一致',
  stat_date DATE    NOT NULL,
  hour      TINYINT NOT NULL DEFAULT -1  COMMENT '-1=全天汇总，0-23=小时维度',
  pv        INT     NOT NULL DEFAULT 0,
  uv        INT     NOT NULL DEFAULT 0,
  uip       INT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_uri_date_hour (short_uri, stat_date, hour)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '短链日粒度统计';
