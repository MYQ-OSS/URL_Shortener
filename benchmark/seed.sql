-- ════════════════════════════════════════════════════════════════════════════
--  压测数据 seeder —— 纯 SQL，不需要任何驱动或依赖
--
--  用法：
--    docker exec -i sl-mysql mysql -uroot -proot123 shortlink < benchmark/seed.sql
--
--  默认生成 100 万条（先跑通流程）。要 1000 万条改下面的 @total_rows。
--  1000 万条在普通笔记本上大约 5-15 分钟，占磁盘约 1.5 GB。
--
--  ⚠️ 三件必读的事
--
--  1. 种子短码用「模乘置换」生成：code = b62((row * 1000000007 + 20260726) mod 62^6)
--     这保证唯一、无需判重、可批量算出，而且 Python 的请求生成器能算出同一批短码。
--     生产短码走 ShortCodeGenerator 的随机方案（模乘置换会被两次 API 调用反解，
--     见 ADR-003）；测试数据不需要抗枚举，用它完全没问题。
--
--  2. 只在独立测试库执行；主版本没有 Bloom 和号段维护前置条件。
--
--  3. 这里的短码生成规则必须与 历史编码实验与 benchmark/seedcode.py 一致。
--     已交叉校验通过（5000 个短码完全一致）。修改 SQL 规则时需同时修改 Python 请求生成规则。
-- ════════════════════════════════════════════════════════════════════════════

SET @total_rows = 1000000;      -- 想要 1000 万就改成 10000000
SET @batch_size = 10000;        -- 每批提交行数

USE shortlink;

-- ────────────────────────────────────────────────────────────────────────────
-- 1. Base62 定长编码函数（与 Java Base62Codec.encodeFixed 等价）
-- ────────────────────────────────────────────────────────────────────────────
DROP FUNCTION IF EXISTS fn_b62;
DELIMITER $$
CREATE FUNCTION fn_b62(n BIGINT UNSIGNED) RETURNS CHAR(6)
    DETERMINISTIC
    NO SQL
BEGIN
    -- 这张表必须与 Java Base62Codec.TABLE 逐字符相同
    DECLARE tbl CHAR(62) DEFAULT 'kb27yYTIZxKSQuVGwcFspJ01DfnLmdltOzCqAgr9oX3aP8W5MU4HhBjvEeNiR6';
    DECLARE res CHAR(6) DEFAULT '';
    DECLARE i TINYINT DEFAULT 0;
    DECLARE v BIGINT UNSIGNED DEFAULT n;
    WHILE i < 6 DO
        -- SUBSTRING 是 1-indexed，所以要 +1。这里最容易出 off-by-one。
        SET res = CONCAT(SUBSTRING(tbl, (v MOD 62) + 1, 1), res);
        SET v = FLOOR(v / 62);
        SET i = i + 1;
    END WHILE;
    RETURN res;
END$$
DELIMITER ;

-- ────────────────────────────────────────────────────────────────────────────
-- 2. 0..9999 辅助序列表（用于批量 INSERT ... SELECT，比逐行 INSERT 快两个数量级）
-- ────────────────────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS t_seq_10k;
CREATE TABLE t_seq_10k (n INT NOT NULL PRIMARY KEY) ENGINE = InnoDB;

-- MySQL 默认 cte_max_recursion_depth = 1000，递归到 9999 会被中止（ERROR 3636）。
-- 不显式调大的话，上面的建表会成功但这里插不进任何行，后续灌数静默失败。
SET SESSION cte_max_recursion_depth = 10001;

INSERT INTO t_seq_10k (n)
WITH RECURSIVE seq AS (SELECT 0 AS n
                       UNION ALL
                       SELECT n + 1 FROM seq WHERE n < 9999)
SELECT n FROM seq;

-- ────────────────────────────────────────────────────────────────────────────
-- 3. 批量灌数据
-- ────────────────────────────────────────────────────────────────────────────
-- seeder 假设独占 t_link：行号从 1 开始，与已有行（如冒烟测试数据）主键冲突。
-- Docker 环境靠 `compose down -v` 重建干净卷；非 Docker 环境（本机 MySQL）
-- 必须先清空。TRUNCATE 保留表结构，只清数据——压测数据本来就可随时重灌。
TRUNCATE TABLE t_link;

DROP PROCEDURE IF EXISTS sp_seed_links;
DELIMITER $$
CREATE PROCEDURE sp_seed_links(IN total BIGINT, IN batch INT)
BEGIN
    DECLARE done BIGINT DEFAULT 0;
    DECLARE t0 DATETIME DEFAULT NOW();

    -- 灌数据期间关掉这三项，能快好几倍。
    -- unique_checks=0 是"跳过唯一性检查"而不是"允许重复"——
    -- 我们的短码由双射函数生成，本来就不会重复，所以关掉是安全的。
    -- ⚠️ 但这只适用于灌测试数据。生产导入前要想清楚数据是否真的无重复。
    SET unique_checks = 0;
    SET foreign_key_checks = 0;
    SET autocommit = 0;

    WHILE done < total DO
        INSERT INTO t_link (id, short_uri, domain, origin_url, url_hash, gid,
                            enable_status, valid_type, click_num, del_flag, create_time)
        SELECT r.row_no,
               fn_b62((r.row_no * 1000000007 + 20260726) MOD 56800235584),
               'localhost:8080',
               CONCAT('https://example.com/seed/', r.row_no,
                      '?utm_source=bench&utm_campaign=', r.row_no MOD 50),
               UNHEX(MD5(CONCAT('https://example.com/seed/', r.row_no))),
               CONCAT('g', LPAD(r.row_no MOD 100, 3, '0')),   -- 散到 100 个分组
               0, 0, 0, 0,
               NOW() - INTERVAL (r.row_no MOD 365) DAY
        FROM (SELECT done + n + 1 AS row_no
              FROM t_seq_10k
              WHERE n < LEAST(batch, total - done)) r;

        SET done = done + LEAST(batch, total - done);
        COMMIT;

        IF done MOD 200000 = 0 THEN
            SELECT CONCAT(done, ' / ', total, '  已用 ',
                          TIMESTAMPDIFF(SECOND, t0, NOW()), ' 秒') AS progress;
        END IF;
    END WHILE;

    SET unique_checks = 1;
    SET foreign_key_checks = 1;
    SET autocommit = 1;

    SELECT CONCAT('完成 ', total, ' 行，共用 ',
                  TIMESTAMPDIFF(SECOND, t0, NOW()), ' 秒') AS result;
END$$
DELIMITER ;

CALL sp_seed_links(@total_rows, @batch_size);

-- ────────────────────────────────────────────────────────────────────────────
-- 4. 自检：确认短码唯一、且与 Python/Java 生成的一致
-- ────────────────────────────────────────────────────────────────────────────
SELECT COUNT(*) AS 总行数, COUNT(DISTINCT short_uri) AS 唯一短码数 FROM t_link;
-- 这两个数必须相等。不相等说明 fn_b62 写错了（大概率是 SUBSTRING 的 off-by-one）。

-- 前 5 行短码应为：bI2Enc  2QP4To  7FDaUb  yDygdD  YdWtx5
-- （已与 Java Base62Codec 和 benchmark/seedcode.py 交叉校验过）
SELECT id, short_uri, gid, origin_url FROM t_link ORDER BY id LIMIT 5;

-- 新业务主键采用内置 ASSIGN_ID，不需要修改历史号段表。

DROP TABLE IF EXISTS t_seq_10k;
DROP PROCEDURE IF EXISTS sp_seed_links;
-- fn_b62 保留，重新灌数据时还要用
