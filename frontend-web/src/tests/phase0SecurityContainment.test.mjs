import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const master = read('../components/MasterAdminDashboard.tsx');
const employee = read('../components/EmployeeCrmDashboard.tsx');
const home = read('../components/Home.tsx');
const notifications = read('../context/NotificationContext.tsx');
const notificationPolicy = read('../utils/notificationPolicy.ts');

test('live admin surfaces no longer render demo workforce, analytics, GPS, payout, or demand metrics', () => {
  for (const demoContent of [
    'mockGroundBoys', 'mockLeaseCashbacks', 'mockPlotApprovals', 'mockEmployeeRoster',
    'mockLeaveRequests', 'Meta Ads Leads', '184 Passes', '2 / 3 Staff',
    'Real-Time GPS Radar', 'GPS Telemetry', 'BhkDemandGaugeGrid', 'demandScore', 'avgRent'
  ]) {
    assert.equal(master.includes(demoContent), false, `admin demo content removed: ${demoContent}`);
  }
  for (const demoContent of ['EMP-101', '3 Visits', '6 Deals', '₹12,000', '96.2%', 'mockMyLeaves', 'geolocation']) {
    assert.equal(employee.includes(demoContent), false, `employee demo content removed: ${demoContent}`);
  }
  assert.match(employee, /Attendance, leave, and performance tools are unavailable/);
});

test('authoritative property and visit operations remain mounted', () => {
  for (const feature of [
    'BatchPropertyIngestionStudio', 'DraftManagementBar', 'FailedUploadsPanel',
    'OperationsVisitRepairPanel', 'OperationsVisitOutcomePanel'
  ]) {
    assert.match(master, new RegExp(feature));
  }
  assert.match(employee, /GroundVisitOperationsPanel user=\{user\}/);
});

test('admin navigation falls back to a real workspace and excludes demo tabs', () => {
  const adminTabs = home.match(/const VALID_ADMIN_TABS = new Set\(\[([\s\S]*?)\]\);/)?.[1] ?? '';
  assert.match(adminTabs, /'learning'[\s\S]*?'media'[\s\S]*?'failed-uploads'[\s\S]*?'visit-repairs'[\s\S]*?'visit-outcomes'/);
  assert.match(home, /return 'media';/);
  assert.doesNotMatch(adminTabs, /'overview'|'funnel'|'crm'|'payroll'|'approval'|'config'/);
});

test('notification fetch no longer sends frontend role as authority and retains server action target', () => {
  assert.match(notifications, /fetch\(`\$\{API_ROOT_URL\}\/notifications`, \{ headers \}\)/);
  assert.doesNotMatch(notifications, /notifications\?role=|targetRole: item\.targetRole|eventKey: item\.eventKey/);
  assert.match(notifications, /actionTarget: item\.actionTarget/);
  assert.match(notificationPolicy, /item\.actionTarget === '\/tenant#visit-history'/);
});
