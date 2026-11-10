# 数据库迁移目录

全新安装只需要上一级目录的 `01-schema.sql` + `02-seed.sql`,本目录一开始是空的。

已经在用的系统不能靠重建数据库来升级。以后要加字段、索引或表,在本目录新增按日期排序的 SQL 文件,例如:

```text
20270101_001_add_xxx_column.sql
```

## 约定

1. **已经发布的迁移文件不要再改**:写错了就新增一个修正迁移。
2. **迁移必须可重复执行(幂等)**:`ADD COLUMN` / `ADD KEY` 先查 `information_schema` 再加;
   `INSERT` 用 `INSERT IGNORE` 或 `WHERE NOT EXISTS`;不要依赖写死的自增 id。
3. **新建表写全 `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`**。
   MySQL 8 只写 `CHARSET=utf8mb4` 时得到的是 `utf8mb4_0900_ai_ci`,和其它表的文字列一比较就报
   `Illegal mix of collations`。
4. **只做增量变更**:避免 `DROP TABLE`、`TRUNCATE` 等破坏性操作;升级前先备份数据库。
5. 已执行过的迁移记在 `sys_schema_migration` 表(文件名 + sha256),执行工具据此跳过。
