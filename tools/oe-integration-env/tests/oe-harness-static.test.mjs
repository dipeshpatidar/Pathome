/** Nonconnecting safety regression batch. Never launches PostgreSQL, Maven, Spring or Chromium. */
import assert from 'node:assert/strict';
import { spawnSync, execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, mkdtempSync, writeFileSync, rmSync, symlinkSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHmac } from 'node:crypto';
import { test } from 'node:test';
import { SCENARIOS, PHASE2A_ACTORS, PHASE2A_AREAS } from '../phase2a/manifest.mjs';
import { BROWSER_SCENARIOS } from '../phase2a/browser.mjs';
import { assertResponse, offsetWindow, Evidence } from '../phase2a/evidence.mjs';
import { safeOrigin, safePort, verifyJwt, verifyBinding, validateMedia } from '../lib/runtime.mjs';
const tool=resolve(dirname(fileURLToPath(import.meta.url)),'..'), repo=resolve(tool,'../..');
const read=name=>readFileSync(join(tool,name),'utf8');
const q=value=>"'"+value.replaceAll("'","'\\''")+"'";
const env={PATH:'/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin',HOME:process.env.HOME,LC_ALL:'C'};
const bash=source=>spawnSync('/bin/bash',['-c',source],{encoding:'utf8',env,timeout:10000});
const guard='. '+q(join(tool,'lib/db-guard.sh'))+'\nexport PATHOME_OE_TEST_ALLOW_DISPOSABLE=true PATHOME_OE_TEST_DB=pathome_oe_test_integration PATHOME_OE_TEST_DB_HOST=localhost PATHOME_OE_TEST_DB_PORT=5432\noe_guard_resolve\n';
const stub='oe_runtime_exec() { printf "UNEXPECTED_SUBPROCESS\\n"; return 99; }\n';
const scripts=['run.sh','package3.sh','package4.sh','phase2a.sh','fixture-roles.sh',...readdirSync(join(tool,'lib')).filter(f=>f.endsWith('.sh')).map(f=>'lib/'+f)];

test('all shell and JS files parse without executing',()=>{
  for(const name of scripts) execFileSync('/bin/bash',['-n',join(tool,name)],{env});
  for(const name of ['journey.mjs',...readdirSync(join(tool,'lib')).filter(f=>f.endsWith('.mjs')).map(f=>'lib/'+f),...readdirSync(join(tool,'phase2a')).filter(f=>f.endsWith('.mjs')).map(f=>'phase2a/'+f)])
    execFileSync(process.execPath,['--check',join(tool,name)],{env});
  for(const name of ['operations-governed-provisioning','operations-governed-intake','listing-publication-journey','operations-workspace-real-backend'])
    execFileSync(process.execPath,['--check',join(repo,'frontend-web/browser-verification',name,'run.mjs')],{env});
});
test('existing hermetic DB / Package 3 / media controls remain green',()=>{
  for(const [file,command,count] of [['lib/db-guard.sh','selftest',13],['lib/db-guard.sh','selftest-package3',10],['lib/env-isolation.sh','selftest',19]]){
    const output=execFileSync('/bin/bash',[join(tool,file),command],{env,encoding:'utf8',timeout:10000});
    assert.doesNotMatch(output,/^FAIL/m); assert.equal(output.split('\n').filter(line=>line.startsWith('PASS')).length,count);
  }
});
test('every protected target is refused at the lowest boundary BEFORE any subprocess',()=>{
  for(const name of ['pathome_db','pathome_db_clone','pathome_autonomous_v2_test','divyavastu_db','hotel_booking','template1']){
    const result=bash(guard+stub+'oe_guard_check_sentinel '+q(name)+' pathome');
    assert.notEqual(result.status,0,name); assert.doesNotMatch(result.stdout,/UNEXPECTED_SUBPROCESS/);
  }
});
test('connection overrides and unrestricted maintenance SQL are refused',()=>{
  for(const command of ['oe_guard_psql "$PATHOME_OE_TEST_DB" pathome -d pathome_db -Atc "select 1"',
    'oe_guard_psql "$PATHOME_OE_TEST_DB" pathome -dpathome_db -Atc "select 1"',
    'oe_guard_psql "$PATHOME_OE_TEST_DB" pathome -hforeign.invalid -Atc "select 1"',
    'oe_guard_psql postgres postgres -c "select 1" -c "select current_user"',
    'oe_guard_psql postgres postgres -f /nonexistent/probe.sql -c "select current_user"',
    'oe_guard_psql postgres postgres -c "DROP DATABASE pathome_db"']){
    const result=bash(guard+stub+command); assert.equal(result.status,19); assert.doesNotMatch(result.stdout,/UNEXPECTED_SUBPROCESS/);
  }
});
test('drop proof failure stops even when caller captures the result',()=>{
  const result=bash(guard+stub+'oe_guard_verify() { return 17; }; oe_guard_drop; exit $?');
  assert.equal(result.status,17); assert.doesNotMatch(result.stdout,/UNEXPECTED_SUBPROCESS/);
  assert.doesNotMatch(read('lib/verify.sh'),/CREATE DATABASE/);
  assert.match(read('lib/verify.sh'),/NOT TESTED  live unmarked-probe/);
  assert.match(read('run.sh'),/oe_guard_verify --destructive\n  oe_guard_require_run/);
});
test('psql disables rc files and uses controlled libpq environment',()=>{
  assert.match(read('lib/db-guard.sh'),/oe_runtime_exec psql psql -X -w/);
  assert.match(read('lib/runtime.sh'),/command env -i/);
  assert.match(read('lib/runtime.sh'),/PGCONNECT_TIMEOUT=5/);
});
test('invalid numeric ports are refused before a connection',()=>{
  for(const port of ['0','65536','5432/other','8080@evil.invalid','-1','05432','']){
    const result=bash(guard+stub+'oe_guard_port '+q(port));
    assert.notEqual(result.status,0,port); assert.doesNotMatch(result.stdout,/UNEXPECTED_SUBPROCESS/);
  }
});
test('strict JDBC parsing rejects fake loopback hosts, protected names, ports and parameters',()=>{
  for(const url of ['jdbc:postgresql://localhost.example.invalid:5432/pathome_oe_test_integration',
    'jdbc:postgresql://localhost:5432/pathome_oe_test_integration?service=x',
    'jdbc:postgresql://localhost:5433/pathome_oe_test_integration','jdbc:postgresql://localhost:5432/pathome_db',
    'jdbc:postgresql://localhost:5432/pathome_oe_test_integration#ignored']){
    const result=bash(guard+stub+'oe_guard_verify_jdbc '+q(url));
    assert.equal(result.status,19,url); assert.doesNotMatch(result.stdout,/UNEXPECTED_SUBPROCESS/);
  }
  assert.equal(bash(guard+'oe_guard_verify_jdbc jdbc:postgresql://localhost:5432/pathome_oe_test_integration').status,0);
});
const injections=['SPRING_APPLICATION_JSON','SPRING_FLYWAY_URL','SPRING_DATASOURCE_HIKARI_JDBC_URL','APP_JWT_SECRET',
  'SPRING_PROFILES_INCLUDE','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS',
  'NODE_OPTIONS','SPRING_MAIL_HOST','SPRING_CONFIG_LOCATION','PSQLRC','PGSERVICE','CURL_HOME','CLOUDINARY_API_SECRET',
  'PATHOME_STAGING_S3_BUCKET','HTTPS_PROXY'];
test('all independently identified environment injections are neutralised',()=>{
  for(const key of injections){
    const result=bash(guard+'export '+key+'=synthetic-injection; oe_iso_neutralise_environment; test "$'+'{'+key+'+x}" != x');
    assert.equal(result.status,0,key+': '+result.stderr);
  }
});
test('Node allowlist drops unknown messaging keys and injection channels',()=>{
  const result=bash(guard+'export SPRING_APPLICATION_JSON=bad APP_UNREGISTERED_WEBHOOK=bad NODE_OPTIONS=bad; node -e '+
    q('if(process.env.SPRING_APPLICATION_JSON||process.env.APP_UNREGISTERED_WEBHOOK||process.env.NODE_OPTIONS)process.exit(1);console.log(process.env.HOME)'));
  assert.equal(result.status,0,result.stderr); assert.match(result.stdout,/pathome-oe-test\/home/);
});
test('Maven/curl isolation applies to every launcher including Package 4 units',()=>{
  const source=read('lib/runtime.sh');
  assert.match(source,/MAVEN_SKIP_RC=true/); assert.match(source,/-Duser.home=/);
  assert.match(source,/"\$binary" -q/); assert.match(source,/--noproxy '\*' --proxy ''/);
  assert.match(read('package4.sh'),/cmd_verify_unit\(\) \{[\s\S]*?oe_iso_apply_test_env/);
  assert.match(read('lib/env-isolation.sh'),/oe_runtime_load_secrets\n  oe_iso_pin_media_destination\n  export JWT_SECRET="\$OE_JWT_SECRET" APP_JWT_SECRET="\$OE_JWT_SECRET"/);
  for(const name of ['run.sh','package3.sh','package4.sh','phase2a.sh']){
    assert.doesNotMatch(read(name),/^\s*\. "\$(OE_ENV_FILE|secrets)"$/m);
    assert.match(read(name),/oe_runtime_load_designation/);
  }
});
test('backend pinning replaces injections with synthetic disposable values without launching anything',()=>{
  const result=bash(guard+'export PATHOME_OE_TEST_RUN_DIR=/private/tmp/pathome-oe-static-control PATHOME_OE_TEST_OVERRIDES_FILE=/nonexistent/overrides.env; '+
    'export OE_JWT_SECRET=synthetic-run-signing-secret-32-characters PATHOME_OE_TEST_MEDIA_MODE=disposable-account PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD=true; '+
    'export OE_TEST_CLOUDINARY_CLOUD_NAME=synthetic-oe-control OE_TEST_CLOUDINARY_API_KEY=k OE_TEST_CLOUDINARY_API_SECRET=s; '+
    'export SPRING_APPLICATION_JSON=bad SPRING_FLYWAY_URL=bad APP_JWT_SECRET=bad; '+
    'oe_iso_apply_backend_env jdbc:postgresql://localhost:5432/pathome_oe_test_integration /nonexistent/env.local; '+
    'test -z "$SPRING_APPLICATION_JSON" && test "$SPRING_FLYWAY_URL" = jdbc:postgresql://localhost:5432/pathome_oe_test_integration && test "$APP_JWT_SECRET" = "$OE_JWT_SECRET"');
  assert.equal(result.status,0,result.stderr);
});
test('curl refuses external destinations before any subprocess',()=>{
  const result=bash(guard+stub+'curl http://localhost.example.invalid:8080/api/v1/auth/login');
  assert.equal(result.status,90);assert.doesNotMatch(result.stdout,/UNEXPECTED_SUBPROCESS/);
});
test('data parser refuses executable, duplicate, unknown and symlinked inputs',()=>{
  const dir=mkdtempSync(join(tmpdir(),'oe-data-control-'));
  try{
    for(const content of ['PATHOME_OE_TEST_DB=$(touch forbidden)\n','UNKNOWN=value\n',
      'PATHOME_OE_TEST_DB=x\nPATHOME_OE_TEST_DB=y\n','source /tmp/anything\n']){
      const file=join(dir,'designation.env'); writeFileSync(file,content,{mode:0o600});
      assert.equal(bash(guard+'oe_data_read '+q(file)+' "$OE_DATA_DESIGNATION"').status,90);
    }
    const file=join(dir,'literal.env'); writeFileSync(file,'PATHOME_OE_TEST_DB=pathome_oe_test_integration\n');
    assert.equal(bash(guard+'oe_data_read '+q(file)+' "$OE_DATA_DESIGNATION"').status,0);
    assert.equal(bash(guard+'export PATHOME_OE_TEST_DB_HOST=synthetic-inherited; oe_data_read '+q(file)+' "$OE_DATA_DESIGNATION"; test -z "$PATHOME_OE_TEST_DB_HOST"').status,0);
    const link=join(dir,'linked.env'); symlinkSync(file,link);
    assert.equal(bash(guard+'oe_data_read '+q(link)+' "$OE_DATA_DESIGNATION"').status,90);
  }finally{rmSync(dir,{recursive:true});}
});
test('all status entry points reject a protected designation before database access',()=>{
  const dir=mkdtempSync(join(tmpdir(),'oe-status-control-'));
  try{
    const file=join(dir,'designation.env');writeFileSync(file,'PATHOME_OE_TEST_ALLOW_DISPOSABLE=true\nPATHOME_OE_TEST_DB=pathome_db\n');
    for(const script of ['run.sh','package3.sh','package4.sh']){
      const result=spawnSync('/bin/bash',[join(tool,script),'status'],{env:{...env,PATHOME_OE_TEST_ENV_FILE:file},encoding:'utf8',timeout:3000});
      assert.equal(result.status,14,result.stderr);
    }
  }finally{rmSync(dir,{recursive:true});}
});
test('HTTP-origin controls refuse userinfo, fake loopback, protocols, paths, queries and foreign ports',()=>{
  for(const origin of ['http://localhost.example.invalid:8080','http://127.0.0.1:8080@evil.invalid',
    'https://127.0.0.1:8080','http://127.0.0.1:8080/','http://127.0.0.1:8080?x=1','http://127.0.0.1:8081'])
    assert.throws(()=>safeOrigin(origin,8080),origin);
  assert.equal(safeOrigin('http://127.0.0.1:8080',8080),'http://127.0.0.1:8080');
  assert.throws(()=>safePort('8080@elsewhere'));
});
test('401/403 cannot establish backend binding; suite without opt-in exits before any network',()=>{
  assert.throws(()=>verifyBinding({PATHOME_OE_TEST_RUN_DIR:'/private/tmp/pathome-oe-nonexistent-control'}));
  const result=spawnSync(process.execPath,[join(tool,'phase2a/fullstack.mjs')],{env,encoding:'utf8',timeout:3000});
  assert.equal(result.status,90);
  assert.match(read('lib/runtime.mjs'),/listenerPid\(new URL\(origin\).port\)/);
  assert.match(read('lib/runtime.mjs'),/processIdentity\(binding.parentPid\)/);
  assert.match(read('lib/runtime.mjs'),/verifyJwt\(auth.token/);
});
test('JWT proof requires the actual run secret, expected identity and signing algorithm',()=>{
  const secret='synthetic-test-only-key-32-characters', encode=value=>Buffer.from(JSON.stringify(value)).toString('base64url');
  const head=encode({alg:'HS256'}),body=encode({sub:'fixture@pathome.test',user_id:7,exp:Date.now()/1000+60});
  const token=head+'.'+body+'.'+createHmac('sha256',secret).update(head+'.'+body).digest('base64url');
  assert.equal(verifyJwt(token,secret,'fixture@pathome.test').user_id,7);
  assert.throws(()=>verifyJwt(token,secret+'other','fixture@pathome.test'));
  assert.throws(()=>verifyJwt(token,secret,'other@pathome.test'));
  assert.throws(()=>verifyJwt(encode({alg:'none'})+'.'+body+'.',secret,'fixture@pathome.test'));
  for(const [size,alg,digest] of [[48,'HS384','sha384'],[64,'HS512','sha512']]){
    const key='s'.repeat(size),header=encode({alg});
    const signed=header+'.'+body+'.'+createHmac(digest,key).update(header+'.'+body).digest('base64url');
    assert.equal(verifyJwt(signed,key,'fixture@pathome.test').user_id,7);
    assert.throws(()=>verifyJwt(token,key,'fixture@pathome.test'));
  }
});
test('media approval is required again when reading a backend binding',()=>{
  const valid={PATHOME_OE_TEST_MEDIA_MODE:'disposable-account',PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD:'true',
    OE_TEST_CLOUDINARY_CLOUD_NAME:'synthetic-oe-media',OE_TEST_CLOUDINARY_API_KEY:'k',OE_TEST_CLOUDINARY_API_SECRET:'s'};
  assert.equal(validateMedia(valid),'synthetic-oe-media');
  for(const changed of [{PATHOME_OE_TEST_MEDIA_MODE:''},{PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD:'false'},
    {OE_TEST_CLOUDINARY_CLOUD_NAME:'hbgzetjy'},{OE_TEST_CLOUDINARY_CLOUD_NAME:'pathome-prod-media'},
    {OE_TEST_CLOUDINARY_API_SECRET:''}])assert.throws(()=>validateMedia({...valid,...changed}));
});
test('stale PIDs, foreign listeners, broken ancestry and stale attestations fail using inert process probes',()=>{
  const root=mkdtempSync('/tmp/pathome-oe-binding-control-'),now=Date.now(),runId='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  const privateFile=(name,text)=>writeFileSync(join(root,name),text,{mode:0o600});
  const binding={runId,database:'pathome_oe_test_phase2a_20261015',origin:'http://127.0.0.1:8080',mediaCloudName:'synthetic-oe-media',
    parentPid:123,listenerPid:456,parentIdentity:'parent',listenerIdentity:'listener',createdAt:new Date(now).toISOString()};
  const inspect={processIdentity:pid=>pid===123?'parent':'listener',listenerPid:()=>456,descendant:()=>true};
  const environment={PATHOME_OE_TEST_RUN_DIR:root,PATHOME_OE_TEST_DB:binding.database};
  const attestation='live_current_database='+binding.database+'\nrun_id='+runId+'\nbackend_origin='+binding.origin+'\nbackend_listener_pid=456\n';
  try{
    mkdirSync(join(root,'evidence'),{mode:0o700});privateFile('run-id',runId+'\n');
    privateFile('secrets.env','OE_RUN_ID='+runId+'\nPATHOME_OE_TEST_MEDIA_MODE=disposable-account\nPATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD=true\nOE_TEST_CLOUDINARY_CLOUD_NAME=synthetic-oe-media\nOE_TEST_CLOUDINARY_API_KEY=k\nOE_TEST_CLOUDINARY_API_SECRET=s\n');
    privateFile('backend-binding.json',JSON.stringify(binding));privateFile('evidence/target-attestation.txt',attestation);
    assert.equal(verifyBinding(environment,{inspect,now}).binding.runId,runId);
    assert.throws(()=>verifyBinding(environment,{inspect:{...inspect,processIdentity:()=> 'reused-pid'},now}));
    assert.throws(()=>verifyBinding(environment,{inspect:{...inspect,listenerPid:()=>999},now}));
    assert.throws(()=>verifyBinding(environment,{inspect:{...inspect,descendant:()=>false},now}));
    assert.throws(()=>verifyBinding(environment,{inspect,now:now+13*3600000}));
    privateFile('evidence/target-attestation.txt',attestation.replace(runId,'foreign-run'));
    assert.throws(()=>verifyBinding(environment,{inspect,now}));
    privateFile('evidence/target-attestation.txt',attestation+'run_id=duplicate\n');
    assert.throws(()=>verifyBinding(environment,{inspect,now}));
  }finally{rmSync(root,{recursive:true});}
});
test('real migration filenames are numerically ordered V2 through V50',()=>{
  const declaration=read('run.sh').match(/oe_repo_migration_files\(\) \{[\s\S]*?\n\}/)[0];
  const result=bash(guard+'OE_MIGRATION_DIR='+q(join(repo,'backend/src/main/resources/db/migration'))+'\n'+declaration+'\noe_repo_migration_files');
  assert.equal(result.status,0);
  assert.deepEqual(result.stdout.trim().split('\n').map(file=>Number(file.match(/\/V(\d+)__/)[1])),Array.from({length:49},(_,i)=>i+2));
});
test('82 unique required entries: 19 fixtures / 63 coverage; every area and actor is declared',()=>{
  assert.equal(SCENARIOS.length,82);assert.equal(SCENARIOS.filter(s=>s.kind==='fixture').length,19);
  assert.equal(SCENARIOS.filter(s=>s.kind==='coverage').length,63);assert.equal(new Set(SCENARIOS.map(s=>s.id)).size,82);
  for(const s of SCENARIOS)assert.ok(PHASE2A_ACTORS[s.actor],s.id);
  for(const area of PHASE2A_AREAS)assert.ok(SCENARIOS.some(s=>s.area===area.id));
});
test('all corrected routes and HTTP methods match actual controller annotations',()=>{
  const normalize=text=>text.replace(/\{[^}]+\}/g,'{}');
  for(const s of SCENARIOS){
    const source=readFileSync(join(repo,'backend/src/main/java/com/indore/pathome/spaces/controller',s.controller+'.java'),'utf8');
    const base=source.match(/@RequestMapping\("([^"]+)"\)/)[1].replace('/api/v1','');
    assert.equal(normalize(base+(s.mapping??'')),normalize(s.apiPath),s.id);
    if(s.mapping)assert.ok(source.includes('@'+s.method[0]+s.method.slice(1).toLowerCase()+'Mapping("'+s.mapping+'")'),s.id);
  }
});
test('all 19 fixture IDs have executable evidence boundaries and audited disposable role guards',()=>{
  const source=read('phase2a/fullstack.mjs');
  for(const s of SCENARIOS.filter(s=>s.kind==='fixture'))assert.equal((source.match(new RegExp("fixture\\('"+s.id+"'",'g'))??[]).length,1,s.id);
  assert.match(source,/secondTenant = await register\('second-tenant'\)/);assert.match(source,/nearbyListingId !== ctx.listingId/);
  assert.match(read('sql/fixture-roles.sql'),/BEGIN;[\s\S]*current_database\(\)[\s\S]*FOR SHARE[\s\S]*UPDATE users[\s\S]*DISPOSABLE_ACTOR_ROLE_CREATED[\s\S]*COMMIT;/);
  assert.match(read('fixture-roles.sh'),/PATHOME_OE_TEST_FIXTURE_ROLES/);assert.match(read('fixture-roles.sh'),/oe_runtime_require_backend/);
});
test('lifecycle keeps ownership and separates Admin operations / idempotency / missing IDs',()=>{
  const stage=Object.fromEntries(SCENARIOS.map(s=>[s.id,s.stage]));
  assert.ok(stage.C32<stage.C20&&stage.C20<stage.C19&&stage.C19<stage.C36);
  assert.ok(stage.C16<stage.C18&&stage.C18<stage.C17);
  for(const id of ['C44','C45','C48','C52','C53','C54'])assert.equal(SCENARIOS.find(s=>s.id===id).actor,'legacy-admin');
  assert.deepEqual(SCENARIOS.find(s=>s.id==='C52').expect,[200]);
  assert.deepEqual(SCENARIOS.find(s=>s.id==='C33').expect,[404]);
  assert.match(SCENARIOS.find(s=>s.id==='C26').apiPath,/missingRequestId/);
});
test('tenant/property/shift windows carry matching +05:30 offsets and intersect',()=>{
  const w=offsetWindow(Date.UTC(2026,9,15));
  for(const key of ['windowStart','windowEnd','availabilityStart','availabilityEnd'])assert.match(w[key],/\+05:30$/);
  assert.ok(Date.parse(w.windowStart)<Date.parse(w.availabilityStart));assert.ok(Date.parse(w.availabilityEnd)<Date.parse(w.windowEnd));
});
test('every successful coverage response rejects null JSON, including C06 / C08 / C61',()=>{
  for(const s of SCENARIOS.filter(s=>s.kind==='coverage'&&s.expect.includes(200)))
    assert.notEqual(assertResponse(s,{status:200,json:null,text:'null',headers:{}},{},null,null),true,s.id);
});
test('C13 accepts correct post-claim version and rejects stale data',()=>{
  const s=SCENARIOS.find(s=>s.id==='C13'),ctx={requestId:12,claimBaseVersion:3};
  assert.equal(s.assert({json:{items:[{targetId:12,expectedVersion:4}]},ctx}),true);
  assert.notEqual(s.assert({json:{items:[{targetId:12,expectedVersion:3}]},ctx}),true);
});
test('denials reject contact disclosure and mutation; GE discovery rejects empty candidates',()=>{
  const s=SCENARIOS.find(s=>s.id==='C28'),state={version:2},response={status:404,json:{message:'Not found'},text:'Not found',headers:{}};
  assert.equal(assertResponse(s,response,{contactValues:['synthetic private name']},state,state),true);
  assert.notEqual(assertResponse(s,{...response,text:'synthetic private name'},{contactValues:['synthetic private name']},state,state),true);
  assert.notEqual(assertResponse(s,response,{},state,{version:3}),true);
  assert.notEqual(assertResponse(SCENARIOS.find(s=>s.id==='C46'),{status:200,json:{candidates:[]},text:'{}',headers:{}},{geUserId:8},null,null),true);
});
test('failed / missing / duplicate records cannot produce complete acceptance',()=>{
  const dir=mkdtempSync(join(tmpdir(),'oe-evidence-control-'));
  try{
    const report=new Evidence(SCENARIOS,join(dir,'report.json'));assert.equal(report.complete(),false);
    report.log=()=>{};report.record('F01',{result:'FAIL',reason:'synthetic negative control'});assert.equal(report.complete(),false);
    assert.throws(()=>report.record('F01',{result:'PASS'}));assert.throws(()=>report.record('UNKNOWN',{result:'PASS'}));
    report.record('F02',{result:'NOT TESTED',reason:'unsupported negative control'});assert.throws(()=>report.record('F02',{result:'PASS'}));
    assert.deepEqual(JSON.parse(readFileSync(report.file)).counts,{PASS:0,FAIL:1,'NOT TESTED':81});
  }finally{rmSync(dir,{recursive:true});}
});
test('12 browser scenarios use real login / streamed backend responses and no authenticated storage seeding',()=>{
  assert.equal(BROWSER_SCENARIOS.length,12);
  const source=read('phase2a/browser.mjs');
  assert.match(source,/input\[type=password\]/);assert.match(source,/res.writeHead\(incoming.statusCode, incoming.headers\)/);
  assert.doesNotMatch(source,/route\.fulfill|addInitScript|localStorage\.setItem|shellGateBody/);
  assert.match(source,/TenantDashboard control/);assert.match(source,/pending\?\.\(\)/);
  assert.match(source,/ACKNOWLEDGEMENT_DROPPED_AFTER_REAL_BACKEND_RESPONSE/);
  // One JS command may have multiple Chromium wire attempts after socket acknowledgement loss.
  assert.match(source,/browserSelectionRequests === 1/);
  assert.match(source,/selectionPosts === postsBefore/);
  assert.match(source,/payloadSha256 === selectionRequests\[0\].payloadSha256/);
  assert.match(source,/committedBooking.commands === 1/);
  assert.match(source,/after.outbox === committedBooking.outbox/);
  const legacy=readFileSync(join(repo,'frontend-web/browser-verification/operations-workspace-real-backend/run.mjs'),'utf8');
  assert.doesNotMatch(legacy,/route\.fulfill|localStorage\.setItem|shellGateBody/);
});
test('no hardcoded registration credential remains; journey browser loads ephemeral secrets',()=>{
  const source=readFileSync(join(repo,'frontend-web/browser-verification/operations-governed-provisioning/run.mjs'),'utf8');
  assert.match(source,/registerThroughTheForm\(page, email, IDENTITY.oeAlphaPassword/);
  assert.match(read('package4.sh'),/cmd_journey_browser\(\) \{\n  oe_load_designation\n  oe_p4_ensure_secrets/);
});
