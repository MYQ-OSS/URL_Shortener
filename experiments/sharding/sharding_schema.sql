-- ════════════════════════════════════════════════════════════════════════════
-- 历史分库分表实验，不属于当前部署的初始化脚本。
--
--  拓扑（见 ADR-005 / ADR-017）：
--    shortlink_0 / shortlink_1 两个库，每库 8 张分表，共 16 分片
--    t_link          按 short_uri 分片（跳转主链路，必须单分片命中）
--    t_link_goto     按 gid 分片（异构索引表，管理链路查询维度）
--    t_local_message 按 shard_key(=short_uri) 分片——必须与 t_link 同库，
--                    "落库 + 写消息"才能放进同一个本地事务（本地消息表模式的前提）
--    t_id_segment / t_link_stats_daily 只放 shortlink_0（单表，ShardingSphere
--                    按元数据自动路由；号段表绝不能做广播表——CAS 会写花）
--
--  ★ 两个必须继承的教训：
--    1. t_link_N 用 CREATE TABLE ... LIKE shortlink.t_link 继承结构——
--       包括 short_uri 的 utf8mb4_bin（ADR-021，大小写敏感不能丢）
--    2. 分表后"唯一索引只在单分片内生效"：uk_short_uri 只兜同分片碰撞，
--       跨分片靠随机短码空间 + BF（ADR-007）
-- ════════════════════════════════════════════════════════════════════════════

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS shortlink_0 DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS shortlink_1 DEFAULT CHARACTER SET utf8mb4;

-- ────────────────────────────────────────────────────────────────────────────
-- 1. t_link_0..7 × 2 库（LIKE 继承 utf8mb4_bin 与全部索引）
-- ────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS sp_create_link_tables;
DELIMITER $$
CREATE PROCEDURE sp_create_link_tables()
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE db INT DEFAULT 0;
    WHILE db < 2 DO
        WHILE i < 8 DO
            SET @sql = CONCAT('CREATE TABLE IF NOT EXISTS shortlink_', db,
                              '.t_link_', i, ' LIKE shortlink.t_link');
            PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
            SET i = i + 1;
        END WHILE;
        SET i = 0;
        SET db = db + 1;
    END WHILE;
END$$
DELIMITER ;
CALL sp_create_link_tables();
DROP PROCEDURE IF EXISTS sp_create_link_tables;

-- ────────────────────────────────────────────────────────────────────────────
-- 2. t_link_goto_0..7 × 2 库（gid 维度的异构索引）
-- ────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS sp_create_goto_tables;
DELIMITER $$
CREATE PROCEDURE sp_create_goto_tables()
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE db INT DEFAULT 0;
    WHILE db < 2 DO
        WHILE i < 8 DO
            SET @sql = CONCAT('CREATE TABLE IF NOT EXISTS shortlink_', db, '.t_link_goto_', i, ' (
                id        BIGINT      NOT NULL,
                gid       VARCHAR(32) NOT NULL,
                short_uri CHAR(6)     NOT NULL COLLATE utf8mb4_bin,
                PRIMARY KEY (id),
                UNIQUE KEY uk_gid_uri (gid, short_uri)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = ''gid -> short_uri 异构索引''');
            PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
            SET i = i + 1;
        END WHILE;
        SET i = 0;
        SET db = db + 1;
    END WHILE;
END$$
DELIMITER ;
CALL sp_create_goto_tables();
DROP PROCEDURE IF EXISTS sp_create_goto_tables;

-- ────────────────────────────────────────────────────────────────────────────
-- 3. t_local_message_0..7 × 2 库（本地消息表）
--    shard_key 存 short_uri：与 t_link 用同一个分片表达式 → 同一行链接和它的
--    消息必然落同一个库 → "落库+写消息"可以是同一个本地事务。
--    补偿任务 UPDATE 时 WHERE 带上 shard_key，避免广播。
-- ────────────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS sp_create_msg_tables;
DELIMITER $$
CREATE PROCEDURE sp_create_msg_tables()
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE db INT DEFAULT 0;
    WHILE db < 2 DO
        WHILE i < 8 DO
            SET @sql = CONCAT('CREATE TABLE IF NOT EXISTS shortlink_', db, '.t_local_message_', i, ' (
                id          BIGINT       NOT NULL,
                biz_type    VARCHAR(32)  NOT NULL          COMMENT ''LINK_GOTO_SYNC 等'',
                shard_key   CHAR(6)      NOT NULL COLLATE utf8mb4_bin COMMENT ''分片键，同 t_link.short_uri'',
                payload     VARCHAR(512) NOT NULL,
                status      TINYINT      NOT NULL DEFAULT 0 COMMENT ''0待处理 1成功 2超过重试上限'',
                retry_count TINYINT      NOT NULL DEFAULT 0,
                next_retry  DATETIME     NOT NULL,
                create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_status_retry (status, next_retry)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = ''本地消息表（按 short_uri 分片）''');
            PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
            SET i = i + 1;
        END WHILE;
        SET i = 0;
        SET db = db + 1;
    END WHILE;
END$$
DELIMITER ;
CALL sp_create_msg_tables();
DROP PROCEDURE IF EXISTS sp_create_msg_tables;

-- ────────────────────────────────────────────────────────────────────────────
-- 4. 单表：号段表 + 统计表，放 shortlink_0
--    号段表 max_id 必须 ≥ 旧库已用最大 id（迁移脚本会在灌完数据后校正）
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS shortlink_0.t_id_segment (
  biz_tag     VARCHAR(64) NOT NULL,
  max_id      BIGINT      NOT NULL DEFAULT 0,
  step        INT         NOT NULL DEFAULT 1000,
  version     BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (biz_tag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '号段表（单表，不分片）';

INSERT INTO shortlink_0.t_id_segment (biz_tag, max_id, step)
VALUES ('short_link', 0, 1000)
ON DUPLICATE KEY UPDATE biz_tag = biz_tag;

CREATE TABLE IF NOT EXISTS shortlink_0.t_link_stats_daily (
  id        BIGINT  NOT NULL AUTO_INCREMENT,
  short_uri CHAR(6) NOT NULL COLLATE utf8mb4_bin,
  stat_date DATE    NOT NULL,
  hour      TINYINT NOT NULL DEFAULT -1 COMMENT '-1=全天汇总，0-23=小时维度',
  pv        INT     NOT NULL DEFAULT 0,
  uv        INT     NOT NULL DEFAULT 0,
  uip       INT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_uri_date_hour (short_uri, stat_date, hour)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '短链日粒度统计（Week 4 单表，后续可按 short_uri 分片）';

-- 校验：每个库应各有 8+8+8=24 张分表 + shortlink_0 另有 2 张单表
SELECT 'shortlink_0' AS db, COUNT(*) AS tables
  FROM information_schema.tables WHERE table_schema = 'shortlink_0'
UNION ALL
SELECT 'shortlink_1', COUNT(*)
  FROM information_schema.tables WHERE table_schema = 'shortlink_1';
