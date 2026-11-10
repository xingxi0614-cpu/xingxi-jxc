# 星嘻进销存

一套面向中小企业、支持多公司的开源进销存系统。从采购、入库、出库到月末结账，按账套严格隔离，库存成本按移动加权平均核算。浏览器就能用，手机上也有专门的界面。

> ⚠️ 本项目按 [MIT 许可证](LICENSE) 提供，**不附带任何担保**。用于记真账之前，请先在测试环境里完整走一遍你的业务流程，并做好数据备份。

## 功能概览

| 模块 | 内容 |
|---|---|
| 采购 | 采购订单、采购入库(暂估入库 / 发票入库)、采购退货、采购发票、付款登记、录入单价防错提示 |
| 出库 | 出库申请、材料出库、退料、借料、库存转库；提交时即检查可用库存 |
| 库存 | 现存量、出入库流水、盘点、库存上下限预警、期初导入；成本按仓库或按公司移动加权平均 |
| 销售 | 销售订单、销售出库、销售发票、销售退货、收款 |
| 结账 | 月末结账前自动对账检查，结账后锁定当月单据，可反结账 |
| 报表 | 存货明细账、收发存汇总、暂估待冲回、采购/销售执行情况、出库成本按科目汇总等，可导出 Excel |
| 审批 | 按岗位配置审批流程，审批、驳回、撤回、反审(需走撤销审批) |
| 管理 | 多公司账套隔离、岗位与菜单权限、单据编号规则、操作日志、在线用户、数据备份 |
| 其他 | 免登录的领料申请 / 录单链接(扫码填写)、单据附件、单据关联追溯、手机端工作台、常用小工具 |

## 快速开始(Docker)

需要一台装好 [Docker](https://docs.docker.com/get-docker/)(含 Docker Compose)的电脑或服务器，内存建议 2GB 以上。

```bash
git clone <本仓库地址> xingxi-jxc
cd xingxi-jxc
cp .env.example .env        # 然后打开 .env，把 DB_ROOT_PASSWORD 和 DB_PASSWORD 改成你自己的长密码
docker compose up -d --build
```

第一次启动要下载镜像、编译程序，需要几分钟。完成后用浏览器打开 `http://服务器地址:8080`。

### 第一次登录

| 账号 | 用途 | 初始密码 |
|---|---|---|
| `admin` | 系统管理员，能看全部公司 | `.env` 里设了 `ADMIN_PASSWORD` 就用它；没设则首次启动随机生成 |
| `demo` | 演示账号，只能看「演示公司」，可以随便试 | `.env` 里的 `DEMO_PASSWORD`，没设则随机生成 |

随机生成的密码只在首次启动的日志里出现一次，这样查看：

```bash
docker compose logs backend | grep 初始密码
```

登录后请立即修改 admin 密码。示例数据里另有 6 个样例业务账号(采购、仓管、材料会计各两个)，默认是**停用**的，需要时由管理员改密码后启用。

### 开始用自己的数据

示例数据里有一家「示例公司」和一家「演示公司」。正式使用时，用 admin 登录后：

1. 「系统管理 → 组织架构」新建你的公司和部门；
2. 「基础资料」维护物料、仓库、供应商(支持 Excel 导入)；
3. 「系统管理 → 用户管理」建账号、分配岗位；
4. 在「库存 → 现存量」页点「期初导入」录入期初库存，然后就可以开始走单据了。

## ⚠️ 数据备份(一定要看)

**本系统不会自动把数据备份到别的地方。** 硬盘坏了、服务器被删了，没备份的数据就找不回来。请至少每月备份一次，并把备份文件存到另一台电脑或网盘。

- **最简单**：admin 登录后打开「系统管理 → 数据备份」，下载「整库备份(SQL)」。
- **命令行备份数据库**：

  ```bash
  docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump --single-transaction --no-tablespaces -u scm scm' | gzip > backup-$(date +%Y%m%d).sql.gz
  ```

- **单据附件**存在 Docker 数据卷 `app-data` 里，也要一起备份：

  ```bash
  docker compose cp backend:/data/scm/attachments ./attachments-backup
  ```

**恢复数据库**：

```bash
gunzip -c backup-20270101.sql.gz | docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u scm scm'
```

## 升级

```bash
git pull
docker compose up -d --build
```

如果新版本的 `database/migrations/` 目录里有新的 SQL 文件，请**先备份**，再按文件名顺序逐个执行：

```bash
docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u scm scm' < database/migrations/文件名.sql
```

## 用 HTTPS 对外提供服务

`docker compose` 只提供 HTTP。要放到公网上，请在前面加一层 HTTPS(云厂商的负载均衡、Caddy、或另一个配了证书的 Nginx)，然后把 `.env` 里的 `COOKIE_SECURE` 改成 `true`，再执行 `docker compose up -d`。

数据库端口默认不对外开放，请保持这样。

## 本地开发

需要 JDK 17、Node.js 20、MySQL 8.0。

```bash
# 1. 准备数据库(建库 scm,字符集 utf8mb4 / utf8mb4_unicode_ci),依次导入 database/01-schema.sql、02-seed.sql
# 2. 后端(默认连 127.0.0.1:3306 的 scm 库,账号密码用环境变量 SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD)
cd backend && ./mvnw spring-boot:run
# 3. 前端(开发服务器把 /api 转给 http://127.0.0.1:8080)
cd frontend && npm ci && npm run dev
```

### 测试

- 后端单元测试：`cd backend && ./mvnw test`(连真实数据库的那部分默认跳过)
- 全流程测试：`scripts/e2e-local.sh`，会在 Docker 里起一套独立的数据库和后端，跑完整条业务链路的断言，不碰你的正式数据。
- 密钥扫描：`scripts/security-scan.sh`，提交前检查有没有把密钥、私钥写进代码。

## 技术栈

- 后端：Java 17、Spring Boot 3.5、MyBatis-Plus、Sa-Token、Apache POI
- 前端：Vue 3、TypeScript、Vite、Element Plus、Pinia、ECharts
- 数据库：MySQL 8.0

## 参与贡献

欢迎提交问题和改进，请先看 [CONTRIBUTING.md](CONTRIBUTING.md)。发现安全漏洞请按 [SECURITY.md](SECURITY.md) 私下报告，不要直接公开。

## 许可证

[MIT](LICENSE)
