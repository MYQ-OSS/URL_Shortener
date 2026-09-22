-- Java String.hashCode 的 SQL 复刻（仅对 ASCII 键安全：short_uri/gid 均为纯 ASCII）
USE shortlink;
DROP FUNCTION IF EXISTS fn_jhash;
DELIMITER $$
CREATE FUNCTION fn_jhash(s VARCHAR(64)) RETURNS BIGINT
    DETERMINISTIC NO SQL
BEGIN
    DECLARE h BIGINT DEFAULT 0;
    DECLARE i INT DEFAULT 1;
    DECLARE n INT;
    SET n = CHAR_LENGTH(s);
    WHILE i <= n DO
        SET h = (h * 31 + ORD(SUBSTRING(s, i, 1))) % 4294967296;
        SET i = i + 1;
    END WHILE;
    IF h >= 2147483648 THEN SET h = h - 4294967296; END IF;
    RETURN h;
END$$
DELIMITER ;
-- 分片表达式（与 sharding.yaml 的 INLINE 完全一致）: (h % 16 + 16) % 16; ds = s DIV 8; tbl = s % 8
INSERT INTO shortlink_0.t_link_0 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 0;
INSERT INTO shortlink_0.t_link_1 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 1;
INSERT INTO shortlink_0.t_link_2 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 2;
INSERT INTO shortlink_0.t_link_3 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 3;
INSERT INTO shortlink_0.t_link_4 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 4;
INSERT INTO shortlink_0.t_link_5 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 5;
INSERT INTO shortlink_0.t_link_6 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 6;
INSERT INTO shortlink_0.t_link_7 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 7;
INSERT INTO shortlink_1.t_link_0 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 8;
INSERT INTO shortlink_1.t_link_1 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 9;
INSERT INTO shortlink_1.t_link_2 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 10;
INSERT INTO shortlink_1.t_link_3 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 11;
INSERT INTO shortlink_1.t_link_4 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 12;
INSERT INTO shortlink_1.t_link_5 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 13;
INSERT INTO shortlink_1.t_link_6 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 14;
INSERT INTO shortlink_1.t_link_7 SELECT * FROM shortlink.t_link WHERE (MOD(fn_jhash(short_uri),16)+16)%16 = 15;
INSERT INTO shortlink_0.t_link_goto_0 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 0;
INSERT INTO shortlink_0.t_link_goto_1 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 1;
INSERT INTO shortlink_0.t_link_goto_2 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 2;
INSERT INTO shortlink_0.t_link_goto_3 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 3;
INSERT INTO shortlink_0.t_link_goto_4 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 4;
INSERT INTO shortlink_0.t_link_goto_5 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 5;
INSERT INTO shortlink_0.t_link_goto_6 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 6;
INSERT INTO shortlink_0.t_link_goto_7 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 7;
INSERT INTO shortlink_1.t_link_goto_0 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 8;
INSERT INTO shortlink_1.t_link_goto_1 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 9;
INSERT INTO shortlink_1.t_link_goto_2 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 10;
INSERT INTO shortlink_1.t_link_goto_3 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 11;
INSERT INTO shortlink_1.t_link_goto_4 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 12;
INSERT INTO shortlink_1.t_link_goto_5 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 13;
INSERT INTO shortlink_1.t_link_goto_6 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 14;
INSERT INTO shortlink_1.t_link_goto_7 (id, gid, short_uri) SELECT id, gid, short_uri FROM shortlink.t_link WHERE (MOD(fn_jhash(gid),16)+16)%16 = 15;
-- 号段推进到迁移数据之后
UPDATE shortlink_0.t_id_segment SET max_id = (SELECT MAX(id) FROM shortlink.t_link) + 1000 WHERE biz_tag = 'short_link';
-- 校验：源表 = 16 分片之和
SELECT (SELECT COUNT(*) FROM shortlink.t_link) AS src, (SELECT SUM(c) FROM (SELECT COUNT(*) c FROM shortlink_0.t_link_0 UNION ALL SELECT COUNT(*) FROM shortlink_0.t_link_1 UNION ALL SELECT COUNT(*) FROM shortlink_0.t_link_2 UNION ALL SELECT COUNT(*) FROM shortlink_0.t_link_3 UNION ALL SELECT COUNT(*) FROM shortlink_0.t_link_4 UNION ALL SELECT COUNT(*) FROM shortlink_0.t_link_5 UNION ALL SELECT COUNT(*) FROM shortlink_0.t_link_6 UNION ALL SELECT COUNT(*) FROM shortlink_0.t_link_7 UNION ALL SELECT COUNT(*) FROM shortlink_1.t_link_0 UNION ALL SELECT COUNT(*) FROM shortlink_1.t_link_1 UNION ALL SELECT COUNT(*) FROM shortlink_1.t_link_2 UNION ALL SELECT COUNT(*) FROM shortlink_1.t_link_3 UNION ALL SELECT COUNT(*) FROM shortlink_1.t_link_4 UNION ALL SELECT COUNT(*) FROM shortlink_1.t_link_5 UNION ALL SELECT COUNT(*) FROM shortlink_1.t_link_6 UNION ALL SELECT COUNT(*) FROM shortlink_1.t_link_7) t) AS dst;