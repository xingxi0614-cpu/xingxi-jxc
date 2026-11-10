#!/usr/bin/env python3
"""Plan quantities do not reserve stock; posting still requires sufficient stock.
Run only inside the dedicated environment created by e2e-local.sh.
SQL creates isolated fixtures; all document actions use the real HTTP API.
"""
import http.cookiejar
import json
import os
import subprocess
import urllib.request
import uuid
from datetime import date
from decimal import Decimal

project = os.environ['E2E_PROJECT']
assert project.startswith('oss-e2e')
container = project + '-mysql'
meta = json.loads(subprocess.check_output(['docker', 'inspect', container]))[0]
assert meta['Config']['Labels']['com.docker.compose.project'] == project
assert meta['NetworkSettings']['Ports']['3306/tcp'][0]['HostIp'] == '127.0.0.1'
base = 'http://127.0.0.1:' + os.environ.get('E2E_BACK_PORT', '18081') + '/api'
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
checks = 0


def sql(query):
    return subprocess.check_output(
        ['docker', 'exec', '-i', '-e', 'MYSQL_PWD=root@2026', container,
         'mysql', '--default-character-set=utf8mb4', '-uroot', '-N', 'scm', '-e', query],
        text=True).strip()


def sync_cost_pool(mat):
    """测试捷径直接改了 inv_balance(模拟到货)时,同步公司成本账,保持"成本账 = 各仓之和"。
    按公司核算后引擎会校验这个不变式,对不上就拒绝过账(fail fast);按仓核算时成本账不参与计算,同步无害。"""
    sql(f"""INSERT INTO inv_cost_pool(org_id,material_id,qty,amount,avg_price,version)
            SELECT w.org_id,b.material_id,SUM(b.qty),SUM(b.amount),
                   CASE WHEN SUM(b.qty)>0 THEN ROUND(SUM(b.amount)/SUM(b.qty),2) ELSE 0 END,0
            FROM inv_balance b JOIN md_warehouse w ON w.id=b.warehouse_id
            WHERE b.material_id={mat} GROUP BY w.org_id,b.material_id
            ON DUPLICATE KEY UPDATE qty=VALUES(qty),amount=VALUES(amount),avg_price=VALUES(avg_price)""")


def api(method, path, data=None, success=True):
    body = None if data is None else json.dumps(data).encode()
    request = urllib.request.Request(base + path, body, {'Content-Type': 'application/json'}, method=method)
    with opener.open(request, timeout=30) as response:
        result = json.load(response)
    if success:
        check(result['code'] == 0, method + ' ' + path + ': ' + str(result.get('msg', '')))
    return result


def check(condition, message):
    global checks
    assert condition, message
    checks += 1
    print('  PASS:', message, flush=True)


def approve(path, table, doc_id):
    for _ in range(8):
        result = api('POST', f'{path}/{doc_id}/approve', {'comment': 'E2E plan'}, success=False)
        if result['code'] != 0 or sql(f'SELECT status FROM {table} WHERE id={doc_id}') == 'APPROVED':
            return result
    raise AssertionError('Approval did not terminate')


api('POST', '/auth/login', {'username': 'demo', 'password': 'Demo-e2e-2026', 'force': True})
org = int(sql("SELECT param_value FROM sys_param WHERE param_key='demo.org.id'"))
wh = int(sql("SELECT id FROM md_warehouse WHERE code='DEMO-WH' AND deleted=0 LIMIT 1"))
prj = int(sql(f'SELECT id FROM md_project WHERE org_id={org} AND deleted=0 LIMIT 1'))
code = 'E2E-PLAN-' + uuid.uuid4().hex[:12]
mat = int(sql(f"INSERT INTO md_material(code,name,org_id,is_shared) VALUES('{code}','E2E计划边界测试',{org},0); SELECT LAST_INSERT_ID();"))
payload = dict(warehouseId=wh, projectId=prj, businessCategory='运营类',
               materialApplicant='E2E', applyDate=date.today().isoformat(), purpose=code,
               lines=[dict(materialId=mat, qty=10)])
req = api('POST', '/outbound/oreq', payload)['data']['id']
check(sql(f'SELECT status FROM biz_out_req WHERE id={req}') == 'DRAFT', '无库存记录可保存草稿')
payload['lines'][0]['qty'] = 12
api('PUT', f'/outbound/oreq/{req}', payload)
check(Decimal(sql(f'SELECT qty FROM biz_out_req_line WHERE out_req_id={req}')) == 12, '无库存可修改计划数量')
api('POST', f'/outbound/oreq/{req}/submit', {})
check(approve('/outbound/oreq', 'biz_out_req', req)['code'] == 0, '无库存申请可审批通过')
check(sql(f'SELECT status FROM biz_out_req WHERE id={req}') == 'APPROVED', '计划审批通过')
check(sql(f'SELECT COUNT(*) FROM inv_balance WHERE material_id={mat}') == '0', '申请不创建或预占库存')
check(sql(f'SELECT COUNT(*) FROM inv_transaction WHERE material_id={mat}') == '0', '申请不产生库存流水')
# Exercise an explicit zero balance, independently of a missing balance row.
sql(f'INSERT INTO inv_balance(material_id,warehouse_id,qty,amount,avg_price) VALUES({mat},{wh},0,0,0)')
sync_cost_pool(mat)
zero_req = api('POST', '/outbound/oreq', payload)['data']['id']
api('PUT', f'/outbound/oreq/{zero_req}', payload)
check(Decimal(sql(f'SELECT qty FROM inv_balance WHERE material_id={mat}')) == 0, '显式零库存可新增和修改计划')
for qty in [0, -1]:
    invalid = {**payload, 'lines': [dict(materialId=mat, qty=qty)]}
    check(api('POST', '/outbound/oreq', invalid, success=False)['code'] != 0, '非法数量仍拒绝: ' + str(qty))
    check(api('PUT', f'/outbound/oreq/{zero_req}', invalid, success=False)['code'] != 0, '修改为非法数量仍拒绝: ' + str(qty))
check(Decimal(sql(f'SELECT qty FROM biz_out_req_line WHERE out_req_id={zero_req}')) == 12, '非法修改完整回滚')
line = int(sql(f'SELECT id FROM biz_out_req_line WHERE out_req_id={req}'))
out = api('POST', '/outbound/out/from-oreq', dict(oreqId=req, outType='生活用料', lines=[dict(reqLineId=line, qty=5)]))['data']['id']
# 2026-10-20 起提交审批时就查库存(SubmitStockGuard):库存 0 时提交被拦,单据仍是草稿
blocked = api('POST', f'/outbound/out/{out}/submit', {}, success=False)
check(blocked['code'] != 0 and '库存不足，不能提交审批' in blocked.get('msg', ''), '库存0时提交被拦(提交时就查库存)')
check(sql(f'SELECT status FROM biz_out WHERE id={out}') == 'DRAFT', '提交被拦后仍是草稿')
# 库存够时提交进审批;下面再模拟"提交后库存被领走",验证审批过账时的库存硬闸仍在(双保险)
sql(f'UPDATE inv_balance SET qty=5,amount=50,avg_price=10 WHERE material_id={mat}')
sync_cost_pool(mat)
check(api('POST', f'/outbound/out/{out}/submit', {}, success=False)['code'] == 0, '库存够时提交成功')
check(sql(f'SELECT status FROM biz_out WHERE id={out}') == 'PENDING', '提交后待审批')
for stock in [0, 3]:
    # Simulate stock arriving; SQL is fixture setup in this disposable database only.
    sql(f'UPDATE inv_balance SET qty={stock},amount={stock * 10},avg_price=10 WHERE material_id={mat}')
    sync_cost_pool(mat)
    result = approve('/outbound/out', 'biz_out', out)
    check(result['code'] != 0 and '库存不足' in json.dumps(result, ensure_ascii=False), f'实际出库5件在库存{stock}时被拒绝')
    # 提示必须写物料编码+名称，不能只甩数据库内部 id（实际最高频的报错，仓管看不懂）
    check(code in result.get('msg', '') and 'E2E计划边界测试' in result.get('msg', ''), '库存不足提示带物料编码和名称')
    check(sql(f'SELECT status FROM biz_out WHERE id={out}') == 'PENDING', '过账失败保持待审批')
    check(Decimal(sql(f'SELECT qty FROM inv_balance WHERE material_id={mat}')) == stock, '过账失败库存数量不变')
    check(Decimal(sql(f'SELECT amount FROM inv_balance WHERE material_id={mat}')) == stock * 10, '过账失败库存金额不变')
    check(Decimal(sql(f'SELECT out_qty FROM biz_out_req WHERE id={req}')) == 0, '过账失败申请已出数量不变')
    check(sql(f'SELECT COUNT(*) FROM inv_transaction WHERE material_id={mat}') == '0', '过账失败不产生流水')
# Existing positive stock is also allowed to be less than the planned quantity.
positive_req = api('POST', '/outbound/oreq', payload)['data']['id']
check(Decimal(sql(f'SELECT total_qty FROM biz_out_req WHERE id={positive_req}')) == 12, '库存3件仍可计划12件')
sql(f'UPDATE inv_balance SET qty=5,amount=50,avg_price=10 WHERE material_id={mat}')
sync_cost_pool(mat)
check(approve('/outbound/out', 'biz_out', out)['code'] == 0, '到货足额后同一出库单可重试通过')
check(Decimal(sql(f'SELECT qty FROM inv_balance WHERE material_id={mat}')) == 0, '实际只扣5件')
check(Decimal(sql(f'SELECT amount FROM inv_balance WHERE material_id={mat}')) == 0, '实际扣减对应50元')
check(Decimal(sql(f'SELECT out_qty FROM biz_out_req WHERE id={req}')) == 5, '申请记录实际已出5件')
check(sql(f'SELECT status FROM biz_out_req WHERE id={req}') == 'PARTIAL_OUT', '计划12件支持先发5件')
check(sql(f'SELECT COUNT(*) FROM inv_transaction WHERE material_id={mat}') == '1', '最终仅产生一笔库存流水')
print(f'OUTBOUND-PLAN-OK: {checks} checks', flush=True)
