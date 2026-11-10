#!/usr/bin/env python3
"""Run the complete DB suite against a dedicated e2e MySQL container only.
Start scripts/e2e-local.sh --keep first; uses a separate, freshly initialized DB.
"""
from pathlib import Path
import os, subprocess, hashlib, json
root=Path(__file__).resolve().parent.parent
project=os.environ.get('E2E_PROJECT','oss-e2e')
if not (project=='oss-e2e' or project.startswith('oss-e2e-')): raise SystemExit('Only oss-e2e containers allowed')
container=project+'-mysql'
meta=json.loads(subprocess.check_output(['docker','inspect',container]))[0]
assert meta['Config']['Labels']['com.docker.compose.project']==project
port=meta['NetworkSettings']['Ports']['3306/tcp'][0]
assert port['HostIp']=='127.0.0.1', 'Test DB must bind loopback only'
password=next(x.split(': ',1)[1].strip() for x in (root/'docker-compose.test.yml').read_text().splitlines() if 'MYSQL_ROOT_PASSWORD:' in x)
db='scm_regression'
cmd=['docker','exec','-i','-e','MYSQL_PWD='+password,container,'mysql','--default-character-set=utf8mb4','-uroot','-N']
def sql(s): return subprocess.run(cmd,input=s,text=True,capture_output=True,check=True).stdout
# Only this fixed test DB in the validated disposable test container.
sql('DROP DATABASE IF EXISTS scm_regression;')
sql(f'CREATE DATABASE {db} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
for name in ['01-schema.sql','02-seed.sql']:
 sql(f'USE {db};\n'+(root/'database'/name).read_text())
applied=set(sql(f'USE {db}; SELECT id FROM sys_schema_migration;').splitlines())
for f in sorted((root/'database/migrations').glob('*.sql')):
 if f.name in applied:continue
 sql(f'USE {db};\n'+f.read_text())
 sql(f"USE {db}; INSERT INTO sys_schema_migration(id,checksum,applied_at) VALUES('{f.name}','{hashlib.sha256(f.read_bytes()).hexdigest()}',NOW());")
env={k:v for k,v in os.environ.items() if not k.startswith(('SPRING_','SCM_','SERVER_','RUN_DB_TESTS'))}
env.update(E2E_PROJECT=project,SCM_DB_TEST_VERIFIED_PORT=port['HostPort'],RUN_DB_TESTS='true',SPRING_PROFILES_ACTIVE='dev',SPRING_DATASOURCE_URL=f"jdbc:mysql://127.0.0.1:{port['HostPort']}/{db}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",SPRING_DATASOURCE_USERNAME='root',SPRING_DATASOURCE_PASSWORD=password,SCM_UPLOAD_PATH=f'/tmp/{project}-regression-upload',SCM_TOOLBOX_PATH=f'/tmp/{project}-regression-toolbox',SCM_ATTACHMENT_DIR=f'/tmp/{project}-regression-att',SCM_SAMPLE_ACCOUNTS_ENABLED='true',SCM_SAMPLE_PASSWORD='123456',SCM_DEMO_PASSWORD='Demo-e2e-2026',SCM_ADMIN_PASSWORD='admin@2026')
raise SystemExit(subprocess.run(['mvn','-o','test','-Dstyle.color=never']+(['-Dtest='+os.environ['DB_TEST_FILTER']] if os.environ.get('DB_TEST_FILTER') else []),cwd=root/'backend',env=env).returncode)
