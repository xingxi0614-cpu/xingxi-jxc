#!/usr/bin/env python3
"""Healthy regression assertions against the local disposable E2E environment only.
Starts real HTTP requests behind held MySQL locks to reproduce the race deterministically.
"""
from pathlib import Path
import subprocess,json,urllib.request,concurrent.futures,time
import os
repo=Path(__file__).resolve().parent.parent
project=os.environ.get('E2E_PROJECT','oss-e2e')
assert project=='oss-e2e' or project.startswith('oss-e2e-')
container=project+'-mysql'
meta=json.loads(subprocess.check_output(['docker','inspect',container]))[0]
assert meta['Config']['Labels']['com.docker.compose.project']==project
port=int(os.environ.get('E2E_BACK_PORT','18081'))
assert port not in (80,443,8080)

password=next(x.split(': ',1)[1].strip() for x in (repo/'docker-compose.test.yml').read_text().splitlines() if 'MYSQL_ROOT_PASSWORD:' in x)
cmd=['docker','exec','-i','-e','MYSQL_PWD='+password,container,'mysql','--default-character-set=utf8mb4','-uroot','-N','--unbuffered','scm']
def sql(s):return subprocess.run(cmd,input=s,text=True,capture_output=True,check=True).stdout.strip()
def request(path,data=None,token=None,method='POST',ua='audit-local'):
 headers={'Content-Type':'application/json','User-Agent':ua}
 if token:headers['Authorization']=token
 req=urllib.request.Request(f'http://127.0.0.1:{port}/api'+path,data=None if data is None else json.dumps(data).encode(),headers=headers,method=method)
 with urllib.request.urlopen(req,timeout=30) as r:return json.load(r)
# Test admin password from the checked-in fixture, never a production credential.
import re
script=(repo/'scripts/e2e-local.sh').read_text()
pwd=re.search(r'SCM_ADMIN_PASSWORD="([^"]+)"',script)[1]
a=request('/auth/login',{'username':'admin','password':pwd,'force':True});assert a['code']==0
token=a['data']['token']
results=[]
try:
 # Clone an APPROVED synthetic E2E source and its lines; all originals stay unchanged.
 source=sql("SELECT id FROM biz_sale_out WHERE status='APPROVED' AND deleted=0 ORDER BY id LIMIT 1")
 assert source, 'E2E sale fixture missing'
 cols=sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='scm' AND TABLE_NAME='biz_sale_out' AND COLUMN_NAME<>'id' ORDER BY ORDINAL_POSITION").splitlines()
 sel=["CONCAT('AUDIT-CONCUR-',UUID_SHORT())" if c=='doc_no' else '`'+c+'`' for c in cols]
 target=sql('INSERT INTO biz_sale_out ('+','.join('`'+c+'`' for c in cols)+') SELECT '+','.join(sel)+' FROM biz_sale_out WHERE id='+source+'; SELECT LAST_INSERT_ID();')
 cols=sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='scm' AND TABLE_NAME='biz_sale_out_line' AND COLUMN_NAME<>'id' ORDER BY ORDINAL_POSITION").splitlines()
 sql('INSERT INTO biz_sale_out_line ('+','.join('`'+c+'`' for c in cols)+') SELECT '+','.join(target if c=='out_id' else '`'+c+'`' for c in cols)+' FROM biz_sale_out_line WHERE out_id='+source)
 lock=subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
 lock.stdin.write('START TRANSACTION; SELECT id FROM biz_sale_out WHERE id='+target+" FOR UPDATE; SELECT 'LOCKED';\n");lock.stdin.flush()
 while lock.stdout.readline().strip()!='LOCKED':pass
 try:
  with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
   fs=[pool.submit(request,'/sale/invoice/from-out',{'outId':int(target),'invoiceDate':'2099-01-02','remark':'isolated audit concurrency fixture'},token) for _ in range(2)]
   deadline=time.monotonic()+10
   while time.monotonic()<deadline:
    waiting=int(sql("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT' AND trx_query LIKE '%biz_sale_out%'") or '0')
    if waiting>=2:break
    time.sleep(.1)
   lock.stdin.write('COMMIT;\n');lock.stdin.flush();lock.stdin.close();lock.wait(timeout=10)
   responses=[f.result() for f in fs]
  count=int(sql('SELECT COUNT(*) FROM biz_sale_invoice WHERE source_out_id='+target+' AND deleted=0'))
  results.append({'probe':'concurrent_sale_invoice_creation','waiting_before_release':waiting,'isolation':sql('SELECT @@transaction_isolation'),'response_codes':[r['code'] for r in responses],'rejection_messages':[r.get('msg') for r in responses if r['code']!=0],'invoice_ids':[r.get('data',{}).get('id') if isinstance(r.get('data'),dict) else None for r in responses],'source_out_id':int(target),'persisted_invoices':count,'passed':count==1 and sorted(r['code'] for r in responses)==[0,5000] and any('不能重复开票' in r.get('msg','') for r in responses)})
 finally:
  if lock.poll() is None:lock.terminate()
 # Rejected invoices compete with a replacement through the same source lock.
 old_id=int(sql('SELECT id FROM biz_sale_invoice WHERE source_out_id='+target+' AND deleted=0 LIMIT 1'))
 sql(f"UPDATE biz_sale_invoice SET status='REJECTED' WHERE id={old_id}")
 lock=subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
 lock.stdin.write('START TRANSACTION; SELECT id FROM biz_sale_out WHERE id='+target+" FOR UPDATE; SELECT 'LOCKED';\n");lock.stdin.flush()
 while lock.stdout.readline().strip()!='LOCKED':pass
 try:
  with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
   fs=[pool.submit(request,f'/sale/invoice/{old_id}/submit',{},token),pool.submit(request,'/sale/invoice/from-out',{'outId':int(target),'invoiceDate':'2099-01-02'},token)]
   deadline=time.monotonic()+10
   while time.monotonic()<deadline:
    waiting=int(sql("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT' AND trx_query LIKE '%biz_sale_out%'") or '0')
    if waiting>=2:break
    time.sleep(.1)
   lock.stdin.write('COMMIT;\n');lock.stdin.flush();lock.stdin.close();lock.wait(timeout=10)
   responses=[f.result() for f in fs]
  count=int(sql(f"SELECT COUNT(*) FROM biz_sale_invoice WHERE source_out_id={target} AND deleted=0 AND status<>'REJECTED'"))
  results.append({'probe':'rejected_invoice_resubmit_vs_replacement','lock_waits':waiting,'response_codes':[r['code'] for r in responses],'messages':[r.get('msg') for r in responses if r['code']!=0],'effective_invoices':count,'passed':count==1 and sorted(r['code'] for r in responses)==[0,5000] and any('不能重复开票' in r.get('msg','') for r in responses)})
 finally:
  if lock.poll() is None:lock.terminate()
 # A submitted document must not be overwritten by a draft edit already in flight.
 demo_pwd=re.search(r'"username":"demo","password":"([^"\n]+)"',script)[1]
 demo=request('/auth/login',{'username':'demo','password':demo_pwd,'force':True})
 assert demo['code']==0
 dt=demo['data']['token']
 try:
  wh=int(sql("SELECT id FROM md_warehouse WHERE code='DEMO-WH' AND deleted=0 LIMIT 1"))
  sup=int(sql("SELECT id FROM md_supplier WHERE code='DEMO-SUP' AND deleted=0 LIMIT 1"))
  mat=int(sql(f'SELECT material_id FROM inv_balance WHERE warehouse_id={wh} ORDER BY material_id LIMIT 1'))
  body={'supplierId':sup,'warehouseId':wh,'orderDate':'2099-01-03','lines':[{'materialId':mat,'qty':2,'taxRate':0,'amount':20,'priceCheckNote':'E2E测试价'}]}
  created=request('/purchase/po',body,dt);assert created['code']==0,created
  poid=created['data']['id']
  lock=subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
  lock.stdin.write(f"START TRANSACTION; SELECT id FROM biz_po WHERE id={poid} FOR UPDATE; SELECT 'LOCKED';\n");lock.stdin.flush()
  while lock.stdout.readline().strip()!='LOCKED':pass
  def wait_for(n):
   until=time.monotonic()+10
   while time.monotonic()<until:
    waiting=int(sql("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT' AND trx_query LIKE '%biz_po%'") or '0')
    if waiting>=n:return waiting
    time.sleep(.1)
   return waiting
  try:
   with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    submitted=pool.submit(request,f'/purchase/po/{poid}/submit',{},dt)
    wait1=wait_for(1)
    body['lines'][0]['amount']=40
    edited=pool.submit(request,f'/purchase/po/{poid}',body,dt,'PUT')
    wait2=wait_for(2)
    lock.stdin.write('COMMIT;\n');lock.stdin.flush();lock.stdin.close();lock.wait(timeout=10)
    sr,er=submitted.result(),edited.result()
   final=sql(f'SELECT status,total_amt FROM biz_po WHERE id={poid}')
   workflows=sql(f"SELECT status,total_amt FROM wf_instance WHERE biz_type='PO' AND biz_id={poid}")
   results.append({'probe':'po_submit_then_stale_edit','po_id':poid,'queued_lock_waits':[wait1,wait2],'submit_code':sr['code'],'edit_code':er['code'],'edit_message':er.get('msg'),'head_status_amount':final,'workflow_status_amount':workflows,'passed':sr['code']==0 and er['code']==5000 and '只有草稿或已驳回' in er.get('msg','') and final.startswith('PENDING\t20') and workflows.startswith('PROCESSING\t20') and wait2==2})
  finally:
   if lock.poll() is None:lock.terminate()
 finally:request('/auth/logout',{},dt)
 # Delegated account administrators must not grant themselves a global data scope.
 import secrets
 suffix=str(time.time_ns())[-9:]
 role=request('/sys/roles',{'code':'audit_all_'+suffix,'name':'审查全局范围样本','dataScope':'ALL','status':1},token)
 assert role['code']==0,role
 global_role=role['data']['id']
 account_role=int(sql("SELECT id FROM sys_role WHERE code='account_admin' AND deleted=0 LIMIT 1"))
 username='audit_scope_'+suffix
 local_pwd='Audit_'+secrets.token_hex(8)+'7'
 org=int(sql("SELECT id FROM sys_org WHERE code='DEMO' LIMIT 1"))
 user_body={'username':username,'password':local_pwd,'name':'隔离审查账套管理员','orgId':org,'status':1,'roleIds':[account_role]}
 cu=request('/sys/users',user_body,token);assert cu['code']==0,cu
 uid=int(sql("SELECT id FROM sys_user WHERE username='"+username+"'"))
 al=request('/auth/login',{'username':username,'password':local_pwd});assert al['code']==0,al
 at=al['data']['token']
 try:
  def ids(nodes):return [n['id'] for n in nodes]+[x for n in nodes for x in ids(n.get('children',[]))]
  before=request('/sys/orgs/tree',token=at,method='GET')
  user_body.pop('password');user_body['roleIds']=[account_role,global_role]
  changed=request('/sys/users/'+str(uid),user_body,at,method='PUT')
  after=request('/sys/orgs/tree',token=at,method='GET')
  b,a=set(ids(before.get('data',[]))),set(ids(after.get('data',[])))
  results.append({'probe':'account_admin_self_grants_global_scope','precondition':'an enabled non-admin role with ALL data scope exists','self_update_code':changed['code'],'orgs_before':sorted(b),'orgs_after':sorted(a),'passed':changed['code']==4003 and a==b})
 finally:request('/auth/logout',{},at)
 # Two mobile sessions: mini-program has no force argument or 4090 continuation.
 login1=request('/auth/login',{'username':'admin','password':pwd,'force':True},ua='iPhone MicroMessenger')
 login2=request('/auth/login',{'username':'admin','password':pwd},ua='iPhone MicroMessenger')
 results.append({'probe':'mini_mobile_login_conflict','first_code':login1['code'],'second_code':login2['code'],'passed':login1['code']==0 and login2['code']==4090})
 if login1['code']==0:request('/auth/logout',{},login1['data']['token'],ua='iPhone MicroMessenger')
finally:
 request('/auth/logout',{},token)

print(json.dumps(results,ensure_ascii=False,indent=2))

assert all(r['passed'] for r in results), 'Concurrency/permission regression failed'
