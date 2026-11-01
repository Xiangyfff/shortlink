-- 去重脚本：在已经有重复明细的库上建立唯一索引之前先跑一次。
--
-- 背景：故障注入实验（停 MySQL 30 秒）产生了 626 条重复明细，
-- 原因是批插部分成功 + 整批重投。加了唯一索引 (code, visited_at, visitor) 之后
-- 消费端用 INSERT IGNORE 就幂等了，但**建索引之前必须先把历史重复清掉**，否则 ALTER 会失败。
--
-- 用法：
--   docker exec -i shortlink-mysql mysql -uroot -proot shortlink < scripts/dedupe-access-log.sql
--
-- 注意：这是 O(n^2) 级别的自连接，只适合本项目这个数据量（百万级以内、只留 3 天）。
-- 真实生产环境应该在消费端做幂等，而不是事后清洗。

-- 1) 看一下有多少重复
SELECT COUNT(*) AS dup_groups, COALESCE(SUM(c - 1), 0) AS redundant_rows
FROM (SELECT code, visited_at, visitor, COUNT(*) c
      FROM access_log
      GROUP BY code, visited_at, visitor
      HAVING c > 1) t;

-- 2) 删除重复，每组保留 id 最小的那条
DELETE a1
FROM access_log a1
         JOIN access_log a2
              ON a1.code = a2.code
                  AND a1.visited_at = a2.visited_at
                  AND a1.visitor = a2.visitor
                  AND a1.id > a2.id;

-- 3) 建唯一索引（应用启动时也会尝试建，这里手工建一次更直观）
ALTER TABLE access_log
    ADD UNIQUE KEY uk_code_time_visitor (code, visited_at, visitor);

SELECT CONCAT('dedupe done, rows = ', COUNT(*)) AS msg FROM access_log;
