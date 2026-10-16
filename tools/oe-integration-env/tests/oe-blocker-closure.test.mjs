/** Focused independent-review regressions. Only inert children and synthetic temporary data. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, rmSync, chmodSync, existsSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SCENARIOS } from '../phase2a/manifest.mjs';
import { Evidence, assertResponse, snapshot, successfulEvidence } from '../phase2a/evidence.mjs';
import { observeQueue, safeBrowserDiagnostic } from '../phase2a/browser.mjs';
import { recoveryPlan, dataFile, archiveRuntime, verifyBinding, PHASE2A_APPROVED_DB, PHASE2A_APPROVED_RUN_DIR } from '../lib/runtime.mjs';
import { BOUNDS, bounded, runProcess, commandAttempt, privateDirectory, validateSupervision } from '../lib/lifecycle.mjs';
import { validateReadiness, vacantPort, mediaConfiguration, precheck } from '../lib/readiness.mjs';

const tool=resolve(dirname(fileURLToPath(import.meta.url)),'..'), repo=resolve(tool,'../..');
const read=p=>readFileSync(join(tool,p),'utf8');
const quote=s=>"'"+s.replaceAll("'","'\\''")+"'";
const env={PATH:'/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin',HOME:process.env.HOME,LC_ALL:'C'};
const base='. '+quote(join(tool,'lib/db-guard.sh'))+'\nexport PATHOME_OE_TEST_ALLOW_DISPOSABLE=true PATHOME_OE_TEST_DB=pathome_oe_test_integration PATHOME_OE_TEST_DB_HOST=localhost PATHOME_OE_TEST_DB_PORT=5432\noe_guard_resolve\n';
const inert='oe_runtime_exec() { printf "INERT_CHILD"; printf " <%s>" "$@"; printf "\\n"; }; node() { :; };\n';
const bash=s=>spawnSync('/bin/bash',['--noprofile','--norc','-c',s],{env,encoding:'utf8',timeout:5000});
const scenario=id=>SCENARIOS.find(s=>s.id===id);
const response=(status,json,headers={})=>({status,json,text:json===null?'':JSON.stringify(json),headers});

test('original curl bypasses, configuration, extra URLs and transfer options refuse before child',()=>{
  for(const args of ['--url=http://outside.invalid/api/v1/x','--url outside.invalid/api/v1/x',
    'http://outside.invalid/api/v1/x','-K /tmp/inert','--config=/tmp/inert','--next',
    'http://127.0.0.1:8080/api/v1/x http://127.0.0.1:8080/api/v1/y',
    '-L http://127.0.0.1:8080/api/v1/x','http://127.0.0.1:8081/api/v1/x',
    '-H '+quote('Authorization: Bearer synthetic\nInjected: value')+' http://127.0.0.1:8080/api/v1/x']){
    const r=bash(base+inert+'curl '+args);assert.equal(r.status,90,args);assert.doesNotMatch(r.stdout,/INERT_CHILD/);
  }
});
test('legitimate curl requests preserve JSON/header data and the single pinned destination',()=>{
  for(const args of ['-s -o /dev/null -w "%{http_code}" --max-time 3 http://127.0.0.1:8080/api/v1/staff/me/capabilities',
    '-sS -D - -o /dev/null -H "Authorization: Bearer synthetic" http://127.0.0.1:8080/api/v1/operations/my-work',
    '-sS -X POST -H "Content-Type: application/json" -d '+quote('{"note":"http://outside.invalid is inert JSON data"}')+' http://127.0.0.1:8080/api/v1/auth/login']){
    const r=bash(base+inert+'curl '+args);assert.equal(r.status,0,r.stderr);assert.match(r.stdout,/INERT_CHILD <curl> <curl> <--globoff>/);
  }
});
test('psql reconnect, include, shell, variable and alternate-connection forms never spawn',()=>{
  const commands=['\\connect pathome_db','\\c pathome_db','\\i /tmp/inert.sql','\\ir inert.sql','\\! printf inert','select 1;\n\\connect pathome_db'];
  for(const text of commands){
    const r=bash(base+inert+'oe_guard_psql "$PATHOME_OE_TEST_DB" pathome -c '+quote(text));
    assert.equal(r.status,19,text);assert.doesNotMatch(r.stdout,/INERT_CHILD/);
  }
  for(const args of ['-f /tmp/inert.sql','--file=/tmp/inert.sql','-dpathome_db -c "select 1"',
    '--host=outside.invalid -c "select 1"','-v DBNAME=pathome_db -c "select 1"','-c "select 1" -c "select 2"']){
    const r=bash(base+inert+'oe_guard_psql "$PATHOME_OE_TEST_DB" pathome '+args);
    assert.equal(r.status,19,args);assert.doesNotMatch(r.stdout,/INERT_CHILD/);
  }
});
test('reviewed SQL files, maintenance catalog and ordinary SQL remain supported using inert psql',()=>{
  for(const file of [join(repo,'backend/src/test/resources/db/baseline/pathome-v1-legacy-core.sql'),
    join(tool,'sql/00-disposable-marker.sql'),join(tool,'sql/fixture-roles.sql')]){
    const r=bash(base+inert+'oe_guard_psql "$PATHOME_OE_TEST_DB" pathome -q -f '+quote(file));
    assert.equal(r.status,0,r.stderr);assert.match(r.stdout,/<-X> <-w>/);
  }
  for(const call of ['oe_guard_psql postgres postgres -Atc "select current_user"',
    'oe_guard_psql "$PATHOME_OE_TEST_DB" pathome -Atc "select current_database()"']){
    const r=bash(base+inert+call);assert.equal(r.status,0,r.stderr);assert.match(r.stdout,/INERT_CHILD/);
  }
});
test('no-marker destructive refusal remains real guard logic with an inert catalog result',()=>{
  const r=bash(base+'oe_runtime_exec() { printf "0\\n"; }; oe_guard_check_sentinel "$PATHOME_OE_TEST_DB" pathome');
  assert.equal(r.status,17);
  assert.doesNotMatch(read('lib/verify.sh'),/CREATE DATABASE/);
  assert.match(read('run.sh'),/QUARANTINED legacy probe/);
});
test('C12 rejects the reproduced no-op; actual owner/status/version transition passes',()=>{
  const before={targetId:17,expectedVersion:2,status:'RECEIVED',observedOwnerUserId:null};
  const after={...before,expectedVersion:3,observedOwnerUserId:9};
  const ctx={requestId:17,actorUserId:9},ok=response(204,null);
  assert.notEqual(assertResponse(scenario('C12'),ok,ctx,before,before),true);
  assert.equal(assertResponse(scenario('C12'),ok,ctx,before,after),true);
  for(const change of [{observedOwnerUserId:10},{expectedVersion:2},{status:'UNAVAILABLE'},{targetId:18}])
    assert.notEqual(assertResponse(scenario('C12'),ok,ctx,before,{...after,...change}),true);
});
test('claim/release Session and independent Request transitions use the correct roots',()=>{
  for(const [id,session,claim] of [['C16',false,true],['C17',false,false],['C19',true,true],['C20',true,false]]){
    const before={targetId:17,expectedVersion:2,status:session?'DRAFT':'RECEIVED',observedOwnerUserId:claim?null:9};
    const after={...before,expectedVersion:3,observedOwnerUserId:claim?9:null};
    const ctx={secondRequestId:17,sessionId:17,actorUserId:9};
    assert.equal(assertResponse(scenario(id),response(204,null),ctx,before,after),true,id);
    assert.notEqual(assertResponse(scenario(id),response(204,null),ctx,before,before),true,id);
  }
});
test('C41 rejects wrong/no-op availability and accepts exact persisted offsets or legitimate idempotency',()=>{
  const submittedBody={status:'CONFIRMED',availabilityStartAt:'2026-10-17T12:00:00+05:30',availabilityEndAt:'2026-10-17T13:00:00+05:30',availabilityZoneId:'Asia/Kolkata',availabilitySource:'PHONE'};
  const item={itemId:33,confirmationStatus:'CONFIRMED',propertyAvailabilityStartAt:'2026-10-17T06:30:00Z',
    propertyAvailabilityEndAt:'2026-10-17T07:30:00Z',propertyAvailabilityZoneId:'Asia/Kolkata',propertyAvailabilitySource:'PHONE'};
  const current={sessionId:22,version:7,items:[{...item,propertyAvailabilityStartAt:'2000-01-01T00:00:00Z'}]},correct={sessionId:22,version:8,items:[item]};
  const ctx={sessionId:22,itemId:33,submittedBody};
  assert.notEqual(assertResponse(scenario('C41'),response(200,current),ctx,snapshot(current),snapshot(current)),true);
  assert.equal(assertResponse(scenario('C41'),response(200,correct),ctx,snapshot(current),snapshot(correct)),true);
  assert.equal(assertResponse(scenario('C41'),response(200,correct),ctx,snapshot(correct),snapshot(correct)),true);
  for(const key of ['confirmationStatus','propertyAvailabilityEndAt','propertyAvailabilityZoneId','propertyAvailabilitySource']){
    const wrong={...correct,items:[{...item,[key]:'INCORRECT'}]};
    assert.notEqual(assertResponse(scenario('C41'),response(200,wrong),ctx,snapshot(current),snapshot(wrong)),true,key);
  }
});
test('C28 rejects nested contacts including arrays; generic errors remain legitimate',()=>{
  for(const json of [{message:'Not found',details:{email:'synthetic@pathome.test'}},
    {message:'Not found',details:[{nested:{phoneNumber:'synthetic'}}]},
    {message:'Not found',x:{lessorContact:{}}}]){
    assert.notEqual(assertResponse(scenario('C28'),response(404,json),{},null,null),true);
  }
  assert.equal(assertResponse(scenario('C28'),response(404,{message:'Not found',details:{code:'MISSING'}}),{},null,null),true);
});
test('reservation snapshot detects idempotency regressions in duration/end/assignment/zone',()=>{
  const state={sessionId:22,version:8,status:'SCHEDULED',items:[],scheduledAt:'2026-10-17T06:30:00Z',
    reservedEndAt:'2026-10-17T07:00:00Z',durationMinutes:30,zoneId:'Asia/Kolkata',assignedAt:'2026-10-16T00:00:00Z'};
  assert.equal(assertResponse(scenario('C52'),response(200,state),{sessionId:22},snapshot(state),snapshot(state)),true);
  for(const key of ['durationMinutes','reservedEndAt','assignedAt','zoneId'])
    assert.notEqual(assertResponse(scenario('C52'),response(200,state),{sessionId:22},snapshot(state),snapshot({...state,[key]:'changed'})),true,key);
});
test('each evidence retry preserves prior results and publishes only completed success',()=>{
  const root=mkdtempSync('/tmp/pathome-oe-evidence-closure-');mkdirSync(root+'/evidence',{mode:0o700});
  try{
    const scenarios=[{id:'C12',kind:'coverage',actor:'oe-alpha'}],path=root+'/evidence/api.json';
    const first=new Evidence(scenarios,path,'API',{runId:'synthetic-run',database:'pathome_oe_test_integration'});first.log=()=>{};
    first.record('C12',{result:'FAIL'});first.finish();const original=readFileSync(first.file,'utf8');
    const second=new Evidence(scenarios,path,'API',{runId:'synthetic-run',database:'pathome_oe_test_integration'});second.log=()=>{};
    assert.notEqual(first.file,second.file);assert.equal(readFileSync(first.file,'utf8'),original);
    assert.throws(()=>successfulEvidence(root,'API',dataFile));
    second.record('C12',{result:'PASS'});second.finish();
    const completed=successfulEvidence(root,'API',dataFile);assert.equal(completed.status,'PASSED');assert.equal(completed.checkpoint,'C12');assert.ok(completed.startedAt&&completed.endedAt);
    assert.equal(JSON.parse(original).counts.FAIL,1);assert.equal(readFileSync(first.file,'utf8'),original);
    const third=new Evidence(scenarios,path,'API',{runId:'synthetic-run'});third.log=()=>{};third.record('C12',{result:'FAIL'});third.finish();
    assert.throws(()=>successfulEvidence(root,'API',dataFile),/Latest attempt/);
  }finally{rmSync(root,{recursive:true});}
});
test('partial-startup/dead-bound recovery plans prove identity; foreign/reused/orphan processes refuse',()=>{
  const runId='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',database='pathome_oe_test_integration';
  const startup={runId,database,parentPid:123,parentStamp:'owned-stamp'};
  const input={pid:123,runId,database,startup,listener:456};
  const inspect={alive:()=>true,processStamp:()=> 'owned-stamp',processIdentity:()=> 'owned-identity',descendant:()=>true,children:()=>[456]};
  assert.deepEqual(recoveryPlan(input,inspect),[456,123]);
  const binding={runId,database,parentPid:123,parentIdentity:'owned-identity',listenerPid:456,listenerIdentity:'owned-identity'};
  assert.deepEqual(recoveryPlan({...input,binding,listener:null},{...inspect,alive:()=>false}),[]);
  assert.throws(()=>recoveryPlan(input,{...inspect,processStamp:()=> 'reused'}));
  assert.throws(()=>recoveryPlan(input,{...inspect,descendant:()=>false}));
  assert.throws(()=>recoveryPlan(input,{...inspect,alive:()=>false}));
  assert.throws(()=>recoveryPlan({...input,startup:{...startup,runId:'foreign'}},inspect));
  assert.deepEqual(recoveryPlan({...input,binding},{...inspect,alive:()=>false}),[456]);
});
test('create/recreate refuse missing preflight before ANY database boundary',()=>{
  for(const name of ['cmd_create','cmd_recreate']){
    const declaration=read('run.sh').match(new RegExp(name+'\\(\\) \\{[\\s\\S]*?\\n\\}'))[0];
    const stubs='oe_load_designation() { :; }; oe_guard_resolve() { :; }; oe_require_tools() { :; }; oe_ensure_run_dir() { :; }; node() { return 90; }; oe_database_exists() { printf "UNEXPECTED_DATABASE"; }; cmd_teardown() { printf "UNEXPECTED_DATABASE"; };\n';
    const r=bash('set -e\n'+stubs+declaration+'\n'+name);assert.equal(r.status,90,r.stderr);assert.doesNotMatch(r.stdout,/UNEXPECTED_DATABASE/);
  }
  assert.match(read('run.sh'),/test-compile spring-boot:help[\s\S]*oe_runtime_exec build npm[\s\S]*readiness.mjs" record/);
});
test('preflight receipt rejects missing/stale/changed prerequisites; unchanged facts pass',()=>{
  const now=Date.now(),facts={database:'pathome_oe_test_integration',mediaHash:'synthetic-hash',sourceHash:'source-hash'};
  const receipt={...facts,completedAt:new Date(now).toISOString(),offlineMaven:true,frontendBuilt:true,chromium:'/synthetic/chromium',distHash:'build-hash'};
  assert.equal(validateReadiness(receipt,facts,'build-hash',now),receipt);
  for(const change of [{offlineMaven:false},{frontendBuilt:false},{chromium:''},{completedAt:'bad'},
    {completedAt:new Date(now-16*60000).toISOString()},{mediaHash:'changed'},{sourceHash:'changed'}])
    assert.throws(()=>validateReadiness({...receipt,...change},facts,'build-hash',now));
  assert.throws(()=>validateReadiness(receipt,facts,'changed',now));
  assert.doesNotThrow(()=>vacantPort(8080,()=>''));assert.throws(()=>vacantPort(8080,()=> 'foreign-pid'));
});
test('fresh Phase 2A target excludes the quarantined database and old runtime before I/O',()=>{
  assert.equal(PHASE2A_APPROVED_DB,'pathome_oe_test_phase2a_20261015');
  assert.equal(PHASE2A_APPROVED_RUN_DIR,'/private/tmp/pathome-oe-phase2a-20261015');
  const old={PATHOME_OE_TEST_DB:'pathome_oe_test_integration',PATHOME_OE_TEST_DB_HOST:'localhost',
    PATHOME_OE_TEST_DB_PORT:'5432',PATHOME_OE_TEST_RUN_DIR:'/private/tmp/pathome-oe-test'};
  assert.throws(()=>precheck(old),/Unapproved integration coordinates/);
  assert.throws(()=>verifyBinding(old),/Unapproved Phase 2A database/);
  assert.throws(()=>precheck({...old,PATHOME_OE_TEST_DB:PHASE2A_APPROVED_DB}),/Unapproved integration coordinates/);
  assert.match(read('sql/fixture-roles.sql'),/f\.database <> 'pathome_oe_test_phase2a_20261015'/);
});
test('browser failure diagnostic preserves the failure class without storing credentials',()=>{
  const value=safeBrowserDiagnostic(Error('locator.waitFor: secret-example did not appear'),{OE_TEST_CLOUDINARY_API_SECRET:'secret-example'});
  assert.match(value,/locator\.waitFor/);
  assert.doesNotMatch(value,/secret-example/);
});
test('private disposable media literal data refuses unknown/duplicate keys, real cloud and insecure permissions',()=>{
  const root=mkdtempSync('/tmp/pathome-oe-media-closure-'),file=root+'/media.env';
  const valid='PATHOME_OE_TEST_MEDIA_MODE=disposable-account\nPATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD=true\nOE_TEST_CLOUDINARY_CLOUD_NAME=synthetic-closure\nOE_TEST_CLOUDINARY_API_KEY=synthetic\nOE_TEST_CLOUDINARY_API_SECRET=synthetic\n';
  try{
    writeFileSync(file,valid,{mode:0o600});assert.equal(mediaConfiguration(file).OE_TEST_CLOUDINARY_CLOUD_NAME,'synthetic-closure');
    for(const text of [valid+'UNKNOWN=value\n',valid+'OE_TEST_CLOUDINARY_API_KEY=duplicate\n',valid.replace('synthetic-closure','hbgzetjy'),valid.replace('=true','=false')]){
      writeFileSync(file,text);assert.throws(()=>mediaConfiguration(file));
    }
    writeFileSync(file,valid);chmodSync(file,0o644);assert.throws(()=>mediaConfiguration(file));
  }finally{rmSync(root,{recursive:true});}
});
test('queue observer is registered before real triggers and ignores attention-only reads',async()=>{
  const events=[];let predicate;
  const page={waitForResponse:(filter,options)=>{events.push('observer');predicate=filter;assert.equal(options.timeout,15000);return Promise.resolve('real-response');}};
  const observed=observeQueue(page,'my-work');events.push('trigger');assert.equal(await observed,'real-response');
  assert.deepEqual(events,['observer','trigger']);
  const r=(path,method='GET')=>({request:()=>({method:()=>method}),url:()=> 'http://127.0.0.1:1234'+path});
  assert.equal(predicate(r('/api/v1/operations/my-work')),true);
  assert.equal(predicate(r('/api/v1/operations/my-work?attentionOnly=true')),false);
  assert.equal(predicate(r('/api/v1/operations/claimable-work')),false);
  const source=read('phase2a/browser.mjs');
  assert.match(source,/initialMyWork = observeQueue[\s\S]*?name: \^?\/\^Operations\$\//);
  assert.match(source,/const refreshed = observeQueue\(page, 'my-work'\);\n      const result = await waitAction/);
  assert.match(source,/holdNext = true;[\s\S]*?page.on\('request', observeOld\)[\s\S]*?page.reload/);
  assert.match(source,/request === oldRequest/);
  assert.match(source,/page.on\('requestfailed', failedOld\)/);
  assert.doesNotMatch(source,/waitAction\(page, 'GET', \/\\\/operations\\\/my-work\$\//);
});
test('deadline starts before Chromium launch; shutdown/scenario/teardown bounds are explicit',async()=>{
  const source=read('phase2a/browser.mjs');assert.ok(source.indexOf('const overallEnd')<source.indexOf('chromium.launch'));
  assert.match(source,/bounded\(\(\) => chromium.launch/);assert.match(source,/bounded\(operation, Math.min\(BOUNDS.scenario/);
  assert.match(source,/bounded\(\(\) => browser.close\(\), BOUNDS.browserShutdown/);
  assert.ok(BOUNDS.teardown<=60000&&BOUNDS.backendShutdown<=30000&&BOUNDS.command<=540000);
  await assert.rejects(bounded(()=>new Promise(()=>{}),15,'inert timeout'),/deadline exceeded/);
  assert.equal(await bounded(()=>Promise.resolve('legitimate'),100,'control'),'legitimate');
  await assert.rejects(bounded(()=>Promise.resolve(),0,'expired'),/deadline exceeded/);
});
test('process watchdog terminates an inert hanging child and permits a normal inert child',async()=>{
  const normal=await runProcess(process.execPath,['-e','process.exit(0)'],{env,milliseconds:1000});assert.equal(normal.code,0);
  const hung=await runProcess(process.execPath,['-e','setInterval(()=>{},1000)'],{env,milliseconds:40});
  assert.equal(hung.timedOut,true);assert.equal(hung.code,124);
});
test('command attempt records never overwrite and preserve interruption state without secrets',()=>{
  const root=mkdtempSync('/tmp/pathome-oe-command-closure-');
  try{
    privateDirectory(root);const first=commandAttempt(root,'backend','pathome_oe_test_integration'),second=commandAttempt(root,'backend','pathome_oe_test_integration');
    assert.notEqual(first.directory,second.directory);first.record.status='INTERRUPTED';first.record.endedAt=new Date().toISOString();first.save();
    assert.equal(JSON.parse(readFileSync(first.directory+'/command.json')).status,'INTERRUPTED');
    assert.equal(JSON.parse(readFileSync(second.directory+'/command.json')).status,'RUNNING');
    assert.doesNotMatch(readFileSync(first.directory+'/command.json','utf8'),/password|token|secret/i);
  }finally{rmSync(root,{recursive:true});}
});
test('recovery archives preserve original bytes and refuse overwrite of previous recovery evidence',()=>{
  const root=mkdtempSync('/tmp/pathome-oe-archive-closure-');
  try {
    const attempt=commandAttempt(root,'recover','pathome_oe_test_integration');
    writeFileSync(root+'/backend.pid','99999999\n',{mode:0o600});
    archiveRuntime(root,attempt.directory,['backend.pid']);
    assert.equal(readFileSync(attempt.directory+'/backend.pid','utf8'),'99999999\n');assert.equal(existsSync(root+'/backend.pid'),false);
    writeFileSync(root+'/backend.pid','99999998\n',{mode:0o600});
    assert.throws(()=>archiveRuntime(root,attempt.directory,['backend.pid']));
    assert.equal(readFileSync(root+'/backend.pid','utf8'),'99999998\n');
  } finally { rmSync(root,{recursive:true}); }
});
test('inherited supervision flags cannot bypass watchdog verification',()=>{
  const now=Date.now(),record={pid:123,command:'teardown',database:'pathome_oe_test_integration',status:'RUNNING',startedAt:new Date(now).toISOString()};
  assert.doesNotThrow(()=>validateSupervision(record,123,'teardown',record.database,now));
  assert.throws(()=>validateSupervision(record,456,'teardown',record.database,now));
  assert.throws(()=>validateSupervision({...record,status:'PASSED'},123,'teardown',record.database,now));
  assert.throws(()=>validateSupervision(record,123,'backend',record.database,now));
  assert.match(read('lib/runtime.sh'),/verify-supervision "\$\$"/);
});
test('absent database with leftover runtime proof cannot report successful teardown',()=>{
  const root=mkdtempSync('/tmp/pathome-oe-absent-closure-');
  try {
    const declaration=read('run.sh').match(/cmd_teardown\(\) \{[\s\S]*?\n\}/)[0];
    const stubs='oe_load_designation() { :; }; oe_guard_resolve() { :; }; oe_require_tools() { :; }; oe_ensure_run_dir() { :; }; oe_database_exists() { return 1; }; oe_die() { exit 90; }; oe_log() { printf "%s\\n" "$*"; };\n';
    for(const name of ['backend.pid','backend-binding.json','backend-startup.json']) {
      writeFileSync(root+'/'+name,'synthetic-owned-artifact',{mode:0o600});
      const r=bash('export PATHOME_OE_TEST_RUN_DIR='+quote(root)+' PATHOME_OE_TEST_DB=pathome_oe_test_integration\n'+stubs+declaration+'\ncmd_teardown');
      assert.equal(r.status,90,name);assert.equal(readFileSync(root+'/'+name,'utf8'),'synthetic-owned-artifact');rmSync(root+'/'+name);
    }
    const clean=bash('export PATHOME_OE_TEST_RUN_DIR='+quote(root)+' PATHOME_OE_TEST_DB=pathome_oe_test_integration\n'+stubs+declaration+'\ncmd_teardown');
    assert.equal(clean.status,0,clean.stderr);
    assert.equal(clean.stderr,'');assert.match(clean.stdout,/nothing to do/);
  }finally{rmSync(root,{recursive:true});}
});
