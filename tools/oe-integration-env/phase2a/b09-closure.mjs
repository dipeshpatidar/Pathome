/** Focused B09 live regression: real React, supported APIs and guarded PostgreSQL interruption. */
import { createServer, request as httpRequest } from 'node:http';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile, realpath } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyBinding, dataFile, boundClient, verifyJwt } from '../lib/runtime.mjs';
import { Evidence, successfulEvidence } from './evidence.mjs';
import { bounded, BOUNDS } from '../lib/lifecycle.mjs';
import { safeBrowserDiagnostic } from './browser.mjs';

const recoveryOnly = process.argv.includes('--recovery-only');
const scenarios = ['ROLLBACK_RETRY','LOST_ACK_CANCELLED','CANCELLED_QUEUE_FAILURE','CANCELLED_READ_RECOVERY']
  .filter(id => !recoveryOnly || ['LOST_ACK_CANCELLED','CANCELLED_READ_RECOVERY'].includes(id)).map(id =>
  ({ id, kind: 'browser', actor: 'oe-alpha', method: 'UI', apiPath: null }));
const assert = (value, message) => { if (!value) throw Error(message); };
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function main() {
  assert(process.env.PATHOME_OE_TEST_PHASE2A_LIVE === 'true', 'Explicit live opt-in required');
  const run = verifyBinding(), client = boundClient();
  const prepared = successfulEvidence(run.root, 'B09_FIXTURES', dataFile);
  assert(prepared.runId === run.binding.runId && prepared.counts.PASS === 19 && prepared.counts.FAIL === 0
    && prepared.counts['NOT TESTED'] === 0, 'Current complete fixture preparation required');
  assert(prepared.fixturesFile.startsWith(run.root + '/evidence/attempts/') && !prepared.fixturesFile.includes('..'), 'Invalid fixture path');
  const fixtures = JSON.parse(dataFile(prepared.fixturesFile));
  assert(fixtures.runId === run.binding.runId, 'Foreign fixture run');
  const evidence = new Evidence(scenarios, run.root + '/evidence/b09-closure.json', recoveryOnly ? 'B09_RECOVERY' : 'B09_CLOSURE',
    { runId: run.binding.runId, database: run.binding.database, backend: run.origin, fixturesFile: prepared.fixturesFile });
  evidence.watchInterruptions();
  const shell = (...args) => execFileSync('/bin/bash', [fileURLToPath(new URL('../phase2a.sh', import.meta.url)), ...args],
    { encoding: 'utf8', env: process.env, timeout: 15000, stdio: ['ignore','pipe','pipe'] }).trim();
  const facts = id => JSON.parse(shell('scheduling-snapshot', String(id)));
  const fault = (action,id) => shell('scheduling-fault', action, String(id));
  const api = async (method,path,token,body,expected = 200) => {
    const result = await client.request(method,path,token,body);
    assert(result.status === expected, method + ' ' + path + ': HTTP ' + result.status); return result.json;
  };
  const signIn = async (email,role) => {
    const result = await api('POST','/auth/login',null,{email,password:run.secrets.OE_STAFF_PASSWORD});
    const claims = verifyJwt(result.token, run.secrets.OE_JWT_SECRET, email);
    if (role) assert(claims.role === role,'Unexpected setup actor role'); return result.token;
  };
  let server,browser,page,active = null, armed = null, succeeded = false;
  let loseAcks = false, failQueues = false, queueDrops = 0, failSessionReads = false, sessionDrops = 0;
  const sessionReads = [];
  const wire = [], responses = [];
  const deadline = setTimeout(() => { server?.closeAllConnections(); void browser?.close(); }, BOUNDS.browser);
  try {
    const oe = await signIn(fixtures.oeEmail), admin = await signIn(fixtures.adminEmail,'ROLE_ADMIN');
    const makeSession = async requestId => {
      const initialQueue=await api('GET','/operations/my-work?page=0&size=100',oe);
      let existing;
      for(const root of initialQueue.items.filter(i=>i.targetType==='SESSION')) {
        const candidate=await api('GET','/operations/visit-sessions/'+root.targetId,oe);
        if(candidate.items.some(i=>i.sourceRequestId===requestId)){existing=candidate;break;}
      }
      if(existing) {
        assert(existing.status==='DRAFT','Existing fixture moved past DRAFT');
        if(!existing.items[0].propertyAvailabilityStartAt)await api('PUT','/operations/visit-sessions/'+existing.sessionId+'/items/'+existing.items[0].itemId+'/availability',oe,
          {expectedSessionVersion:existing.version,status:'CONFIRMED',availabilityStartAt:fixtures.availabilityStart,
            availabilityEndAt:fixtures.availabilityEnd,availabilityZoneId:fixtures.availabilityZone,availabilitySource:'PHONE'});
        return existing.sessionId;
      }
      const available = await client.request('GET','/operations/claimable-work/REQUEST/'+requestId,oe);
      if(available.status===200) await api('POST','/operations/visit-requests/'+requestId+'/claim',oe,
        {expectedRequestVersion:available.json.expectedVersion,reasonCode:'CLAIM_WORK'},204);
      else assert(available.status===404,'Unexpected claimable response');
      const queue = await api('GET','/operations/my-work?page=0&size=100',oe);
      const item = queue.items.find(i => i.targetType === 'REQUEST' && i.targetId === requestId);
      assert(item,'Owned request missing');
      let current = await api('POST','/operations/visit-requests/'+requestId+'/coordinate',oe,
        {expectedRequestVersion:item.expectedVersion,sessionId:null,expectedSessionVersion:null});
      current = await api('PUT','/operations/visit-sessions/'+current.sessionId+'/items/'+current.items[0].itemId+'/availability',oe,
        {expectedSessionVersion:current.version,status:'CONFIRMED',availabilityStartAt:fixtures.availabilityStart,
          availabilityEndAt:fixtures.availabilityEnd,availabilityZoneId:fixtures.availabilityZone,availabilitySource:'PHONE'});
      return current.sessionId;
    };
    const firstId = recoveryOnly ? null : await makeSession(fixtures.requestId), secondId = await makeSession(fixtures.secondRequestId);
    const dist = await realpath(fileURLToPath(new URL('../../../frontend-web/dist', import.meta.url)));
    server = createServer(async (req,res) => {
      try {
        const url = new URL(req.url,'http://127.0.0.1');
        if (url.pathname.startsWith('/api/')) {
          verifyBinding();
          const selection = req.method === 'POST' && /\/oe-recommendations\/selection$/.test(url.pathname);
          const currentRead = req.method === 'GET' && url.pathname === '/api/v1/operations/visit-sessions/'+secondId;
          const sessionRead = currentRead ? { sequence: sessionReads.length+1, endpoint:url.pathname } : null;
          if(sessionRead)sessionReads.push(sessionRead);
          let record;
          if (selection) {
            record = { sequence:wire.length+1,endpoint:url.pathname }; wire.push(record);
            const chunks=[]; req.on('data',c=>chunks.push(c)); req.on('end',()=>{
              const raw=Buffer.concat(chunks), payload=JSON.parse(raw.toString());
              Object.assign(record,{commandId:payload.commandId,payloadSha256:createHash('sha256').update(raw).digest('hex')});
            });
          }
          const upstream=httpRequest(run.origin+url.pathname+url.search,{method:req.method,
            headers:{...req.headers,host:new URL(run.origin).host},timeout:15000},incoming=>{
            if(selection) responses.push({sequence:record.sequence,status:incoming.statusCode});
            if(sessionRead)sessionRead.upstreamStatus=incoming.statusCode;
            if(currentRead && failSessionReads) {
              incoming.resume();incoming.on('end',()=>{sessionDrops++;sessionRead.transport='DROPPED';res.destroy();});return;
            }
            if(selection && loseAcks) { incoming.resume(); return; }
            if(failQueues && req.method==='GET' && /^\/api\/v1\/operations\/(my-work|claimable-work)$/.test(url.pathname)) {
              incoming.resume(); incoming.on('end',()=>{queueDrops++;res.destroy();}); return;
            }
            if(!res.destroyed) {res.writeHead(incoming.statusCode,incoming.headers);incoming.pipe(res);} else incoming.destroy();
          });
          upstream.on('timeout',()=>upstream.destroy()); upstream.on('error',()=>res.destroy());
          req.pipe(upstream); return;
        }
        let file; try {file=await realpath(resolve(dist,'.'+decodeURIComponent(url.pathname)));assert(file.startsWith(dist+'/'),'Invalid asset');}
        catch {file=resolve(dist,'index.html');}
        const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.woff2':'font/woff2'};
        res.writeHead(200,{'Content-Type':types[extname(file)]??'application/octet-stream'});res.end(await readFile(file));
      } catch {res.destroy();}
    });
    await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
    const origin='http://127.0.0.1:'+server.address().port;
    const playwright=await import('/opt/homebrew/lib/node_modules/playwright/index.js');
    const chromium=playwright.chromium??playwright.default?.chromium;
    assert(chromium,'Playwright Chromium unavailable');
    browser=await chromium.launch({channel:'chromium',timeout:30000});
    const context=await browser.newContext({viewport:{width:1280,height:900},reducedMotion:'reduce'});
    await context.route('**/*',route=>new URL(route.request().url()).origin===origin?route.continue():route.abort('blockedbyclient'));
    page=await context.newPage();page.setDefaultTimeout(15000);
    const loginUI=async()=>{
      await page.goto(origin);await page.getByRole('button',{name:'Sign in',exact:true}).first().click();
      const dialog=page.getByRole('dialog').first();await dialog.locator('input[type=email]').fill(fixtures.oeEmail);
      await dialog.locator('input[type=password]').fill(run.secrets.OE_STAFF_PASSWORD);
      await dialog.getByRole('button',{name:'Sign In',exact:true}).click();
      await page.getByRole('button',{name:/^Operations$/}).first().click();await page.locator('.oe-subnav').waitFor();
    };
    const open=async id=>{await page.getByRole('button',{name:/My work/i}).first().click();
      await page.locator('[data-row-key="SESSION-'+id+'"]').click();};
    const review=async id=>{
      await open(id);await page.getByRole('button',{name:'Schedule visit',exact:true}).click();
      await page.locator('.oe-ge').filter({hasText:'GE user ID '+fixtures.geUserId}).getByRole('button',{name:/^Select /}).first().click();
      await page.getByRole('button',{name:'Continue',exact:true}).click();
      const reason=page.getByLabel(/Reason for this choice/);if(await reason.count())await reason.fill('Disposable B09 frontend closure');
    };
    const check=async()=>{await page.getByRole('button',{name:'Check the session',exact:true}).click();};
    const layout=async label=>{
      const checks=[];
      for(const width of [320,375,390,414,430,768,1024,1280,1440]) {
        await page.setViewportSize({width,height:900});await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
        const result=await page.locator('.oe-sch').evaluate(dialog=>({scroll:document.documentElement.scrollWidth,width:innerWidth,
          controls:[...dialog.querySelectorAll('button,summary')].map(n=>({r:n.getBoundingClientRect(),name:n.getAttribute('aria-label')||n.textContent.trim()}))
            .filter(x=>x.r.width&&x.r.height).every(x=>x.r.width>=44&&x.r.height>=44&&x.name),
          modal:dialog.getAttribute('aria-modal')==='true'&&Boolean(document.getElementById(dialog.getAttribute('aria-labelledby')))}));
        assert(result.scroll<=width&&result.controls&&result.modal,'Layout/accessibility failed '+width);checks.push({width,height:900,pass:true});
        if([375,1280].includes(width))await page.locator('.oe-sch').screenshot({path:evidence.directory+'/'+label+'-'+width+'.png'});
      }
      await page.setViewportSize({width:1280,height:900});
      for(const key of ['Tab','Shift+Tab']){await page.keyboard.press(key);assert(await page.evaluate(()=>Boolean(document.activeElement.closest('.oe-sch'))),'Focus escaped');}
      return checks;
    };
    await loginUI();
    if(!recoveryOnly){
    active='ROLLBACK_RETRY';
    await bounded(async()=>{
      await review(firstId);const before=facts(firstId),start=wire.length;
      fault('arm',firstId);armed=firstId;
      const failed=page.waitForResponse(r=>r.request().method()==='POST'&&/\/oe-recommendations\/selection$/.test(new URL(r.url()).pathname));
      await page.getByRole('button',{name:/^Confirm and schedule$/}).click();
      let terminated=false;const end=Date.now()+8000;
      while(Date.now()<end){if(fault('terminate',firstId).split('\n').includes('t')){terminated=true;break;}await delay(100);}
      assert(terminated,'Target transaction was not terminated');assert((await failed).status()===500,'Rollback did not return real 500');
      fault('clear',firstId);armed=null;
      await page.getByText('We can’t tell whether this was scheduled',{exact:true}).waitFor();
      const rolledBack=facts(firstId);assert(rolledBack.status==='DRAFT'&&rolledBack.version===before.version
        &&rolledBack.commands===0&&rolledBack.decisions===0&&rolledBack.outbox===before.outbox,'Rollback leaked effects');
      await page.reload();await page.locator('.oe-subnav').waitFor();await open(firstId);
      await page.getByText('We can’t tell whether this was scheduled',{exact:true}).waitFor();
      const read=page.waitForResponse(r=>/\/oe-recommendations\/selection\/[a-f0-9-]+$/.test(new URL(r.url()).pathname));
      await check();const receipt=await (await read).json();assert(receipt.state==='NOT_RECORDED','Expected NOT_RECORDED');
      await page.getByRole('button',{name:'Retry scheduling',exact:true}).waitFor();const viewportChecks=await layout('retry');
      const retried=page.waitForResponse(r=>r.request().method()==='POST'&&/\/oe-recommendations\/selection$/.test(new URL(r.url()).pathname));
      await page.getByRole('button',{name:'Retry scheduling',exact:true}).dblclick({delay:30});
      assert((await retried).status()===200,'UI retry failed');await page.getByText('The visit is scheduled.',{exact:true}).waitFor();
      const after=facts(firstId),posts=wire.slice(start);
      assert(posts.length===2&&posts.every(p=>p.commandId===posts[0].commandId&&p.payloadSha256===posts[0].payloadSha256),'Retry identity/payload or double-click guard failed');
      assert(after.version===before.version+1&&after.commands===1&&after.completedCommands===1&&after.decisions===1
        &&after.outbox===2&&after.notifications<=2,'Retry duplicated side effects');
      evidence.record(active,{result:'PASS',before,rolledBack,after,wire:posts,responses:responses.slice(start),receipt,
        transactionTerminated:true,remounted:true,gesture:'DOUBLE_CLICK_RETRY',viewportChecks});
    },BOUNDS.scenario,'Real UI rollback/retry');
    await page.getByRole('button',{name:'Close',exact:true}).last().click();
    }
    active='LOST_ACK_CANCELLED';
    let cancelled;
    await bounded(async()=>{
      await review(secondId);const before=facts(secondId),start=wire.length;loseAcks=true;
      await page.getByRole('button',{name:/^Confirm and schedule$/}).dblclick({delay:30});
      await page.getByText('We can’t tell whether this was scheduled',{exact:true}).waitFor();loseAcks=false;
      const committed=facts(secondId);assert(committed.status==='SCHEDULED'&&committed.commands===1&&committed.decisions===1,'Lost acknowledgement not committed');
      const current=await api('GET','/operations/visit-sessions/'+secondId,admin);
      const cancellation=await api('POST','/operations/visit-sessions/'+secondId+'/cancel',admin,{expectedSessionVersion:current.version});
      assert(cancellation.status==='CANCELLED','Authorized cancellation failed');cancelled=facts(secondId);
      await check();await page.getByText('Current Session status: CANCELLED.',{exact:true}).waitFor();
      const text=await page.locator('.oe-sch').innerText();assert(text.includes('original booking command was accepted')&&!/The visit is scheduled\.|Scheduled, but/.test(text),'Current cancelled UI is false');
      const after=facts(secondId),posts=wire.slice(start);
      assert(after.version===cancelled.version&&after.decisions===1&&after.commands===1&&after.outbox===cancelled.outbox
        &&posts.length===1,'Receipt reconciliation duplicated command/effects');
      evidence.record(active,{result:'PASS',before,committed,cancelled,after,wire:posts,responses:responses.slice(start),
        transport:'REAL_SELECTION_ACK_WITHHELD_UNTIL_CLIENT_DEADLINE',renderedStatus:'CANCELLED',historicalAcceptance:true});
    },BOUNDS.scenario,'Lost acknowledgement and cancelled reconciliation');
    if(!recoveryOnly){
    active='CANCELLED_QUEUE_FAILURE';
    await bounded(async()=>{
      failQueues=true;await page.getByRole('button',{name:'Refresh session details',exact:true}).last().click();
      await page.getByText('Current Session: CANCELLED. Your work lists couldn’t be refreshed',{exact:true}).waitFor();
      const text=await page.locator('.oe-sch').innerText();assert(!/The visit is scheduled\.|Scheduled, but/.test(text)&&queueDrops>0,'Queue failure falsely scheduled cancellation');
      const after=facts(secondId);assert(after.version===cancelled.version&&after.decisions===1&&after.outbox===cancelled.outbox,'Refresh mutated booking');
      const viewportChecks=await layout('cancelled-queue-failure');
      evidence.record(active,{result:'PASS',before:cancelled,after,queueTransportDrops:queueDrops,renderedStatus:'CANCELLED',viewportChecks});
      failQueues=false;
    },BOUNDS.scenario,'Cancelled queue transport failure');
    }
    active='CANCELLED_READ_RECOVERY';
    await bounded(async()=>{
      const before=facts(secondId),postCount=wire.length,readStart=sessionReads.length;
      for(let attempt=0;attempt<2;attempt++){
        failSessionReads=true;
        await page.getByRole('button',{name:attempt===0?'Refresh session details':'Check the session',exact:true}).last().click();
        await page.getByText('Session refresh is temporarily unavailable',{exact:true}).waitFor();
        // Await unlock as well: the same warning is retained during another explicit check.
        await page.getByRole('button',{name:'Check the session',exact:true}).waitFor();
        await page.waitForFunction(()=>!document.querySelector('.oe-sch-foot button')?.disabled);
        const text=await page.locator('.oe-sch').innerText();
        assert(text.includes('Last verified Session status: CANCELLED.')&&text.includes('original booking command was accepted')
          &&!text.includes('The visit is scheduled.')&&!text.includes('Current Session: CANCELLED.'),'Failed refresh erased or falsified accepted history');
      }
      assert(sessionDrops>=2,'Repeated genuine Session transports were not dropped');
      const staleViewportChecks=await layout('cancelled-last-verified');
      failSessionReads=false;
      const recoveredStart=sessionReads.length;
      await check();await page.getByText('Current Session status: CANCELLED.',{exact:true}).waitFor();
      await page.waitForFunction(()=>!document.querySelector('.oe-sch-foot button')?.disabled);
      await page.getByRole('button',{name:'Refresh session details',exact:true}).last().click();
      await page.waitForFunction(()=>!document.querySelector('.oe-sch-foot button')?.disabled);
      assert(sessionReads.length>=recoveredStart+2&&sessionReads.slice(recoveredStart).every(r=>r.upstreamStatus===200&&!r.transport),'Recovery did not make real successful GETs');
      assert(wire.length===postCount,'Accepted recovery repeated a scheduling POST');
      assert(await page.getByText('Current Session status: CANCELLED.',{exact:true}).isVisible(),'Recovered React status is inaccurate');
      // Completed results are deliberately drawer-local. A full refresh reloads current truth;
      // it must not resurrect unresolved intent or persist completed Session data.
      await page.reload();await page.locator('.oe-subnav').waitFor();await open(secondId);
      await page.locator('.oe-detail-head .oe-badge').filter({hasText:'Cancelled'}).waitFor();
      assert(await page.locator('.oe-sch').count()===0,'Completed command returned as an unresolved intent after reload');
      assert(await page.evaluate(()=>Object.keys(sessionStorage).filter(k=>k.startsWith('pathome_oe_schedule_v1:')).length)===0,'Completed intent persisted');
      const after=facts(secondId);
      assert(JSON.stringify(after)===JSON.stringify(before)&&wire.length===postCount,'Recovery/refresh mutated backend state');
      evidence.record(active,{result:'PASS',before,after,sessionTransportDrops:sessionDrops,
        sessionReads:sessionReads.slice(readStart),recoveryGets:sessionReads.length-recoveredStart,
        additionalSchedulingPosts:wire.length-postCount,lastVerifiedStatus:'CANCELLED',renderedStatus:'CANCELLED',
        historicalAcceptance:true,repeatedFailures:2,staleViewportChecks,browserReload:true,
        completedHistoryPersistence:'DRAWER_MEMORY_ONLY; RELOAD_READS_CURRENT_SESSION; NO_COMPLETED_BROWSER_STORAGE'});
    },BOUNDS.scenario,'Cancelled Session read failure and explicit recovery');
    succeeded=evidence.complete();
  }catch(error){
    const diagnostic=safeBrowserDiagnostic(error,run.secrets);
    console.error('B09 closure failed: '+diagnostic);evidence.provenance.failure=diagnostic;
    if(page)await page.screenshot({path:evidence.directory+'/failure.png',fullPage:true}).catch(()=>{});
    if(active&&!evidence.recorded.has(active))evidence.record(active,{result:'FAIL',reason:safeBrowserDiagnostic(error,run.secrets),wire,responses});
    process.exitCode=1;
  }finally{
    if(armed)try{fault('clear',armed);}catch{console.error('Test trigger cleanup requires guarded recovery');process.exitCode=1;succeeded=false;}
    clearTimeout(deadline);await bounded(()=>browser?.close(),BOUNDS.browserShutdown,'Browser shutdown').catch(()=>{});
    if(server?.listening){server.closeAllConnections();await new Promise(r=>server.close(r));}
    evidence.finish(succeeded?'PASSED':'FAILED');console.log('B09 closure evidence: '+evidence.file);
  }
}
main().catch(()=>{console.error('REFUSED: B09 closure prerequisites unavailable');process.exitCode=90;});
