import React, { useState } from 'react';
import { CalendarDays, MapPin, ShieldAlert } from 'lucide-react';
import { UserProfile } from '../types';
import { GroundVisitOperationsPanel } from './GroundVisitOperationsPanel';
import { OperationsVisitRepairPanel } from './OperationsVisitRepairPanel';
import { OperationsVisitOutcomePanel } from './OperationsVisitOutcomePanel';

interface EmployeeCrmDashboardProps {
  user: UserProfile | null;
}

type EmployeeWorkspaceTab = 'visits' | 'visit-repairs' | 'visit-outcomes';

export const EmployeeCrmDashboard: React.FC<EmployeeCrmDashboardProps> = ({ user }) => {
  const canReviewVisitRepairs = user?.employeeRoleType?.toUpperCase() === 'WFH_ADMIN';
  const canReviewVisitOutcomes = user?.role === 'ADMIN' || canReviewVisitRepairs;
  const [activeTab, setActiveTab] = useState<EmployeeWorkspaceTab>('visits');

  return (
    <main className="min-h-screen min-w-0 bg-slate-950 px-4 pb-20 pt-24 text-slate-100 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-7xl space-y-6">
        <header className="flex flex-col gap-4 rounded-3xl border border-slate-800 bg-slate-900/90 p-6 shadow-2xl sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 items-center gap-4">
            <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl border border-indigo-500/30 bg-indigo-600/20 text-lg font-bold text-indigo-300" aria-hidden="true">
              {user?.fullName?.charAt(0) || 'P'}
            </div>
            <div className="min-w-0">
              <h1 className="truncate text-xl font-bold tracking-tight text-white sm:text-2xl">
                {user?.fullName ? `Welcome, ${user.fullName}` : 'Operations workspace'}
              </h1>
              <p className="mt-1 text-sm text-slate-400">Visit operations available to your account</p>
            </div>
          </div>
          <div className="flex items-start gap-3 rounded-2xl border border-amber-500/20 bg-amber-500/10 p-4 text-sm text-amber-100 sm:max-w-md">
            <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0 text-amber-400" aria-hidden="true" />
            <p>Attendance, leave, and performance tools are unavailable because this workspace has no authoritative data for them.</p>
          </div>
        </header>

        <nav aria-label="Operations workspace" className="flex flex-wrap gap-2 border-b border-slate-800 pb-3">
          <button
            type="button"
            onClick={() => setActiveTab('visits')}
            aria-current={activeTab === 'visits' ? 'page' : undefined}
            className={`flex min-h-11 items-center gap-2 rounded-xl px-4 py-2 text-xs font-semibold transition-colors ${activeTab === 'visits' ? 'bg-indigo-600 text-white' : 'text-slate-300 hover:bg-slate-900 hover:text-white'}`}
          >
            <MapPin className="h-4 w-4" aria-hidden="true" /> My escort visits
          </button>
          {canReviewVisitRepairs && (
            <button
              type="button"
              onClick={() => setActiveTab('visit-repairs')}
              aria-current={activeTab === 'visit-repairs' ? 'page' : undefined}
              className={`min-h-11 rounded-xl px-4 py-2 text-xs font-semibold transition-colors ${activeTab === 'visit-repairs' ? 'bg-amber-600 text-white' : 'text-slate-300 hover:bg-slate-900 hover:text-white'}`}
            >
              Visit repairs
            </button>
          )}
          {canReviewVisitOutcomes && (
            <button
              type="button"
              onClick={() => setActiveTab('visit-outcomes')}
              aria-current={activeTab === 'visit-outcomes' ? 'page' : undefined}
              className={`flex min-h-11 items-center gap-2 rounded-xl px-4 py-2 text-xs font-semibold transition-colors ${activeTab === 'visit-outcomes' ? 'bg-amber-600 text-white' : 'text-slate-300 hover:bg-slate-900 hover:text-white'}`}
            >
              <CalendarDays className="h-4 w-4" aria-hidden="true" /> Visit outcome exceptions
            </button>
          )}
        </nav>

        {activeTab === 'visits' && <GroundVisitOperationsPanel user={user} />}
        {activeTab === 'visit-repairs' && canReviewVisitRepairs && <OperationsVisitRepairPanel />}
        {activeTab === 'visit-outcomes' && canReviewVisitOutcomes && <OperationsVisitOutcomePanel />}
      </div>
    </main>
  );
};
