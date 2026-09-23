-- ════════════════════════════════════════════════════════════════════════════
--  短链接系统 DDL
--
--  Week 1-3：单库单表（下面的 t_link / t_link_goto 用不带后缀的版本）
--  Week 4  ：接 ShardingSphere 后改为 t_link_0 ~ t_link_7 × 2 库
--            分表脚本见本文件末尾的"分表建表"部分
-- ════════════════════════════════════════════════════════════════════════════

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

-- ────────────────────────────────────────────────────────────────────────────
-- 1. 短链主表
--
--    四个设计决策，每个都能当面试题：
--
--    (1) 主键为什么用 id（发号器）而不是 short_uri？
--        InnoDB 主键是聚簇索引。short_uri 是随机字符串 -> 随机位置插入
--        -> 页分裂 + 写放大 + 索引碎片。发号器 ID 趋势递增，顺序追加写。
--        >>> 这就是号段发号器在本项目里的真正用途：不是生成短码，是生成主键。
--
--    (2) 为什么不用 AUTO_INCREMENT？
--        分库分表下多个分片各自自增会冲突，必须外部发号。
--
--    (3) 幂等索引为什么建在 url_hash BINARY(16) 而不是 origin_url？
--        VARCHAR(2048) utf8mb4 = 8192 字节，超过 InnoDB 索引键 3072 字节上限，
--        建不了索引。BINARY(16) 定长 16 字节，还比 CHAR(32) 存十六进制省一半。
--
--    (4) `describe` 是 MySQL 保留字，必须加反引号。
--
--    (5) ★ short_uri 必须用 utf8mb4_bin（大小写敏感）。
--        默认 collation utf8mb4_0900_ai_ci 大小写不敏感：唯一索引会把
--        'HNhTRj' 和 'HNHtrJ' 当成同一个键，WHERE short_uri=? 也会跨大小写命中——
--        而 Base62 短码的大小写是有意义的（62 个字符含大小写字母）。
--        实测灌 100 万种子数据时在第 15 万行附近即发生碰撞。
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_link (
  id            BIGINT        NOT NULL                     COMMENT '号段发号器 ID，趋势递增',
  short_uri     CHAR(6)       NOT NULL COLLATE utf8mb4_bin COMMENT '短码，不含域名。定长 6 位，大小写敏感',
  domain        VARCHAR(64)   NOT NULL DEFAULT 'localhost:8080',
  origin_url    VARCHAR(2048) NOT NULL,
  url_hash      BINARY(16)    NOT NULL                     COMMENT 'MD5(origin_url)，幂等判断用',
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
  -- 幂等查询索引（gid + url_hash）。
  -- ⚠️ 这里必须是普通索引而不是唯一索引——实测发现的设计冲突：
  --   forceNew=true（渠道归因：同一落地页生成多个短码分投不同渠道）要求
  --   同一 (gid, url_hash) 允许多行，唯一索引会让 forceNew 必然报 Duplicate entry。
  -- 幂等因此退为"查询优先 + 最佳努力"：极端并发下同一长链可能生成两个短码，
  --   两个短码都能正常跳转，业务上可接受（ADR-007 的解法 3；也与分库分表后
  --   "唯一索引只在单分片内生效、本来就拦不住跨分片重复"的结论自洽）。
  -- uk_short_uri 的唯一性不受影响——那是短码防撞的兜底，绝不能动。
  KEY idx_gid_url_hash (gid, url_hash),
  KEY idx_gid_create (gid, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '短链主表';


-- ────────────────────────────────────────────────────────────────────────────
-- 2. 号段表（单库单表，不参与分片）
--
--    起始值 0，因为短码由随机生成器产生、不依赖 ID 编码，
--    所以这里不需要像"号段直接编码短码"的方案那样从 62^5 起跳。
--    step 由 DbSegmentStore 按消耗速度动态调整（Leaf 思路）。
--
--    ⚠️ 实测结论（见 docs/adr.md ADR-002）：
--    step=1000 在 1000 线程压 100 万 ID 时，同步取号 823 次、丢弃倒序号段 163 个；
--    step=100000 时降到 4 次 / 0 个。step 必须按创建 QPS 来定。
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_id_segment (
  biz_tag     VARCHAR(64) NOT NULL,
  max_id      BIGINT      NOT NULL DEFAULT 0,
  step        INT         NOT NULL DEFAULT 1000,
  version     BIGINT      NOT NULL DEFAULT 0    COMMENT '乐观锁版本号',
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (biz_tag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '号段表';

INSERT INTO t_id_segment (biz_tag, max_id, step)
VALUES ('short_link', 0, 1000)
ON DUPLICATE KEY UPDATE biz_tag = biz_tag;


-- ────────────────────────────────────────────────────────────────────────────
-- 3. gid 维度异构索引表（Week 4）
--
--    为什么需要它：t_link 按 short_uri 分片（跳转是主链路，必须单分片命中），
--    但"查我创建的所有短链"是按 gid 查 —— 会广播查询所有分片再归并排序分页。
--
--    解法对比见 ADR-005：
--      A) 基因法：把 gid 的 hash 嵌进短码，使两者路由结果恒等。
--         缺点：分片数固化在短码里，扩容要重新生成所有历史短码，
--               而短码可能已经印在海报和二维码上了 —— 改不了。
--      B) 异构索引表（本项目采用）：多一套双写和最终一致成本，换来可扩容。
--         这也是电商订单表"买家维度 + 卖家维度双写"的经典做法，通用性更高。
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_link_goto (
  id        BIGINT      NOT NULL AUTO_INCREMENT,
  gid       VARCHAR(32) NOT NULL,
  short_uri CHAR(6)     NOT NULL COLLATE utf8mb4_bin COMMENT '大小写敏感，与 t_link 一致',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gid_uri (gid, short_uri)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'gid -> short_uri 异构索引';


-- ────────────────────────────────────────────────────────────────────────────
-- 4. 本地消息表（保证 t_link -> t_link_goto 双写最终一致，Week 4）
--
--    为什么用本地消息表而不是 Seata：
--    双写的两张表在不同分片，但都在 MySQL 内。用"业务表 + 消息表同库事务" +
--    定时补偿投递，就能拿到最终一致，不需要引入分布式事务框架的复杂度。
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS t_local_message (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  biz_type    VARCHAR(32)  NOT NULL              COMMENT 'LINK_GOTO_SYNC 等',
  shard_key   CHAR(6)      NOT NULL COLLATE utf8mb4_bin COMMENT '分片键 short_uri，与 t_link 同分片表达式',
  payload     VARCHAR(512) NOT NULL,
  status      TINYINT      NOT NULL DEFAULT 0    COMMENT '0待处理 1成功 2超过重试上限',
  retry_count TINYINT      NOT NULL DEFAULT 0,
  next_retry  DATETIME     NOT NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_status_retry (status, next_retry)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表';


-- ────────────────────────────────────────────────────────────────────────────
-- 5. 日粒度访问统计（Week 3）
--
--    落库用 upsert 累加，天然幂等于"多次落库同一批增量"之外的重复：
--      INSERT ... ON DUPLICATE KEY UPDATE pv = pv + VALUES(pv), uv = VALUES(uv)
--    注意 pv 是累加（增量语义），uv 是覆盖（HyperLogLog 给的是全量基数）。
--    这个差别很容易写错，写错的表现是 UV 越统计越大。
-- ────────────────────────────────────────────────────────────────────────────
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


-- ════════════════════════════════════════════════════════════════════════════
--  Week 4 分表（已落地，实际脚本见同目录，不再是下面这段示意伪代码）
--
--  分片规模：t_link 2 库 × 8 表 = 16 分片（shortlink_0 / shortlink_1）
--
--  ★ 实际执行的三个脚本（2026-08-03 已在本机跑通）：
--    1) sharding_schema_gen.sql   建 16×3 张分表 + 单表（号段/统计放 shortlink_0）
--    2) sharding_migrate.sql      fn_jhash 复刻 Java hashCode，100 万存量纯 SQL 迁移
--    3) ../src/main/resources/sharding.yaml  分片规则（INLINE + !SINGLE 单表规则）
--
--  ★ 落地踩坑（详见 ADR-024）：
--    - 单表（t_id_segment/t_link_stats_daily）必须显式 !SINGLE 规则 + 通配注册，
--      否则 TableNotFoundException；只写 - !SINGLE 不带 tables 还不够
--    - 本地消息表分片键必须 = short_uri，才能和 t_link 同库事务
--    - 号段表绝不能配广播表（CAS 会写花两个库）
--
--  验收标准（已达成）：打开 ShardingSphere SQL 日志，跳转和列表查询零广播。
--
-- ⚠️ 分表后 uk_gid_url_hash 只在单个分片内生效！
--    t_link 按 short_uri 分片，而幂等判断按 url_hash —— 同一长链两次创建会拿到
--    不同 short_uri，落到不同分片，唯一索引拦不住。见 ADR-007。
