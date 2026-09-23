-- 分片升级：暂停创建/统计消费与落库后执行。只建新表、回填索引，不修改已有短码。
USE shortlink_0;
CREATE TABLE IF NOT EXISTS t_stats_flush_batch (
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (batch_id)
) ENGINE=InnoDB;
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
-- Java hashCode 的 mod 16 可逐字符计算，无需处理 32 位溢出。
DROP FUNCTION IF EXISTS shortlink_dedup_slot;
DELIMITER //
CREATE FUNCTION shortlink_dedup_slot(dedup_value CHAR(64)) RETURNS INT DETERMINISTIC NO SQL
BEGIN
  DECLARE result INT DEFAULT 0;
  DECLARE char_pos INT DEFAULT 1;
  WHILE char_pos <= 64 DO
    SET result = MOD(result * 31 + ASCII(SUBSTRING(dedup_value, char_pos, 1)), 16);
    SET char_pos = char_pos + 1;
  END WHILE;
  RETURN result;
END//
DELIMITER ;
CREATE TEMPORARY TABLE dedup_candidates AS
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_0.t_link_0 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_0.t_link_1 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_0.t_link_2 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_0.t_link_3 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_0.t_link_4 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_0.t_link_5 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_0.t_link_6 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_0.t_link_7 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_1.t_link_0 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_1.t_link_1 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_1.t_link_2 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_1.t_link_3 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_1.t_link_4 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_1.t_link_5 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_1.t_link_6 WHERE del_flag = 0
UNION ALL
SELECT SHA2(CONCAT(gid, CHAR(0), origin_url), 256) AS dedup_key, short_uri FROM shortlink_1.t_link_7 WHERE del_flag = 0;
INSERT IGNORE INTO shortlink_0.t_link_dedup_0 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 0 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_0.t_link_dedup_1 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 1 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_0.t_link_dedup_2 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 2 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_0.t_link_dedup_3 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 3 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_0.t_link_dedup_4 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 4 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_0.t_link_dedup_5 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 5 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_0.t_link_dedup_6 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 6 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_0.t_link_dedup_7 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 7 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_1.t_link_dedup_0 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 8 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_1.t_link_dedup_1 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 9 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_1.t_link_dedup_2 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 10 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_1.t_link_dedup_3 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 11 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_1.t_link_dedup_4 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 12 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_1.t_link_dedup_5 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 13 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_1.t_link_dedup_6 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 14 GROUP BY dedup_key;
INSERT IGNORE INTO shortlink_1.t_link_dedup_7 (dedup_key, short_uri)
SELECT dedup_key, MIN(short_uri) FROM dedup_candidates
WHERE shortlink_dedup_slot(dedup_key) = 15 GROUP BY dedup_key;
DROP TEMPORARY TABLE dedup_candidates;
DROP FUNCTION shortlink_dedup_slot;
