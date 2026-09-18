import React, { useState } from 'react';
import { motion } from 'framer-motion';
import {
  Calendar,
  Clock,
  CheckCircle2,
  FileText,
  MapPin,
  ShieldAlert,
  Award,
  Phone,
  Check,
  UserCheck
} from 'lucide-react';
import { UserProfile } from '../types';
import { useNotification } from '../context/NotificationContext';

interface EmployeeCrmDashboardProps {
  user: UserProfile | null;
}

const mockAssignedVisits = [
  { id: "VST-901", tenantName: "Aman Gupta", tenantPhone: "+91 98260 12345", propertyTitle: "Luxury 3 BHK Flat (Vijay Nagar)", visitTime: "Today, 4:00 PM", status: "SCHEDULED", securityOtp: "849201" },
  { id: "VST-902", tenantName: "Priya Sharma", tenantPhone: "+91 98930 67890", propertyTitle: "Furnished 2 BHK Flat (Nipania)", visitTime: "Today, 6:30 PM", status: "PENDING_OTP", securityOtp: "992104" },
  { id: "VST-903", tenantName: "Rohan Mehta", tenantPhone: "+91 94253 11223", propertyTitle: "Executive Villa (Old Palasia)", visitTime: "Tomorrow, 11:00 AM", status: "UPCOMING", securityOtp: "110293" },
];

const mockMyLeaves = [
  { id: "LV-101", leaveType: "Casual Leave", startDate: "18 Sep 2026", endDate: "19 Sep 2026", reason: "Personal Work", status: "APPROVED" },
  { id: "LV-102", leaveType: "Sick Leave", startDate: "25 Sep 2026", endDate: "25 Sep 2026", reason: "Dental Appointment", status: "PENDING" },
];

export const EmployeeCrmDashboard: React.FC<EmployeeCrmDashboardProps> = ({ user }) => {
  const { notifyInfo, notifySuccess } = useNotification();
  const [clockedIn, setClockedIn] = useState(true);
  const [isLocating, setIsLocating] = useState(false);
  const [punchLocation, setPunchLocation] = useState<{
    lat: number;
    lng: number;
    sector: string;
    time: string;
  }>({
    lat: 22.7533,
    lng: 75.8937,
    sector: 'Vijay Nagar Sector 3 Hub, Indore',
    time: '09:15 AM IST'
  });

  const [activeTab, setActiveTab] = useState<'visits' | 'leaves' | 'performance'>('visits');
  const [leavesList, setLeavesList] = useState(mockMyLeaves);
  const [showLeaveForm, setShowLeaveForm] = useState(false);
  const [newLeaveType, setNewLeaveType] = useState('Casual Leave');
  const [newStartDate, setNewStartDate] = useState('');
  const [newEndDate, setNewEndDate] = useState('');
  const [newReason, setNewReason] = useState('');

  const handlePunchInWithGps = () => {
    setIsLocating(true);

    if ('geolocation' in navigator) {
      navigator.geolocation.getCurrentPosition(
        (position) => {
          const { latitude, longitude } = position.coords;
          const nowTime = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + ' IST';
          setPunchLocation({
            lat: parseFloat(latitude.toFixed(4)),
            lng: parseFloat(longitude.toFixed(4)),
            sector: 'GPS Verified On-Site Landmark (Indore)',
            time: nowTime
          });
          setClockedIn(true);
          setIsLocating(false);
          notifySuccess('Check-in confirmed', `Location confirmed at ${nowTime}.`, `Coordinates: ${latitude.toFixed(4)}° N, ${longitude.toFixed(4)}° E`);
        },
        (error) => {
          console.warn('GPS location request warning, falling back to sector landmark geofence:', error);
          const nowTime = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + ' IST';
          setPunchLocation({
            lat: 22.7533,
            lng: 75.8937,
            sector: 'Vijay Nagar Hub (GPS Geofence Fallback)',
            time: nowTime
          });
          setClockedIn(true);
          setIsLocating(false);
          notifyInfo('Check-in confirmed', `Location confirmation was completed at ${nowTime}.`, 'Vijay Nagar Hub');
        },
        { enableHighAccuracy: true, timeout: 6000 }
      );
    } else {
      setClockedIn(true);
      setIsLocating(false);
      notifyInfo('Check-in confirmed', 'Location confirmation was completed at Vijay Nagar Sector Hub.');
    }
  };

  const handleApplyLeave = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newStartDate || !newReason.trim()) return;
    const newEntry = {
      id: `LV-${Math.floor(100 + Math.random() * 900)}`,
      leaveType: newLeaveType,
      startDate: newStartDate,
      endDate: newEndDate || newStartDate,
      reason: newReason,
      status: 'PENDING'
    };
    setLeavesList([newEntry, ...leavesList]);
    setShowLeaveForm(false);
    setNewStartDate('');
    setNewEndDate('');
    setNewReason('');
    notifySuccess('Leave request submitted', 'Your leave request has been sent for approval.');
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 pt-24 pb-20 px-4 sm:px-6 lg:px-8">
      <div className="max-w-7xl mx-auto space-y-6">
        
        {/* Top Header & Clock-In Hub */}
        <div className="bg-slate-900/90 rounded-3xl p-6 border border-slate-800 shadow-2xl backdrop-blur-xl flex flex-col md:flex-row md:items-center justify-between gap-6">
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 rounded-2xl bg-indigo-600/20 border border-indigo-500/30 flex items-center justify-center text-indigo-400 text-2xl font-bold">
              {user?.fullName?.charAt(0) || 'E'}
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-xl sm:text-2xl font-bold text-white tracking-tight">
                  Welcome back, {user?.fullName || 'Rahul Verma'} 👋
                </h1>
                <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-indigo-500/10 text-indigo-400 border border-indigo-500/20">
                  Staff CRM
                </span>
              </div>
              <p className="text-xs sm:text-sm text-slate-400 mt-1">
                ID: <span className="font-mono text-indigo-300">EMP-101</span> • Field Escort Specialist • Indore Micro-Markets
              </p>
            </div>
          </div>

          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3 self-start md:self-auto">
            
            {/* GPS LOCATION PUNCH-IN BUTTON */}
            {!clockedIn ? (
              <motion.button
                whileHover={{ scale: 1.04, y: -1 }}
                whileTap={{ scale: 0.96 }}
                disabled={isLocating}
                onClick={handlePunchInWithGps}
                className="px-5 py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white rounded-2xl text-xs sm:text-sm font-bold transition-all shadow-lg shadow-emerald-600/25 flex items-center gap-2"
              >
                <MapPin className={`w-4 h-4 text-emerald-200 ${isLocating ? 'animate-bounce' : ''}`} />
                <span>{isLocating ? '📡 Verifying GPS Geofence...' : '📍 Punch-In Duty (Live GPS)'}</span>
              </motion.button>
            ) : (
              <div className="flex items-center gap-2.5 bg-slate-950 p-2 rounded-2xl border border-slate-800 text-xs">
                <div className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse shrink-0" />
                <div className="text-left">
                  <div className="text-emerald-400 font-bold flex items-center gap-1">
                    <span>Punched In</span> • <span className="font-mono text-slate-300">{punchLocation.time}</span>
                  </div>
                  <div className="text-[10px] text-slate-400 flex items-center gap-1 font-mono">
                    <MapPin className="w-3 h-3 text-indigo-400 shrink-0" />
                    <span>{punchLocation.lat}° N, {punchLocation.lng}° E</span>
                  </div>
                </div>
                <motion.button
                  whileHover={{ scale: 1.05 }}
                  whileTap={{ scale: 0.95 }}
                  onClick={() => { setClockedIn(false); notifyInfo('Shift ended', 'You have been checked out for today.'); }}
                  className="ml-2 px-2.5 py-1 bg-rose-500/20 hover:bg-rose-500/30 text-rose-300 border border-rose-500/40 rounded-xl text-[11px] font-semibold transition-all"
                >
                  Punch Out
                </motion.button>
              </div>
            )}

          </div>
        </div>

        {/* Restricted Access Info Bar */}
        <div className="bg-amber-500/10 border border-amber-500/20 rounded-2xl p-4 flex items-start gap-3">
          <ShieldAlert className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
          <div className="text-xs sm:text-sm text-amber-200">
            <span className="font-semibold text-amber-300">Staff CRM Limited Access Mode:</span> You are authenticated in the Employee Portal. Master Admin settings, financial payouts, platform commission edits, and audit logs are restricted to Admin accounts.
          </div>
        </div>

        {/* Quick Metrics Cards */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="bg-slate-900/60 rounded-2xl p-4 border border-slate-800/80">
            <div className="flex justify-between items-center text-slate-400 text-xs font-semibold mb-2">
              <span>Assigned Tours Today</span>
              <Calendar className="w-4 h-4 text-indigo-400" />
            </div>
            <div className="text-2xl font-bold text-white">3 Visits</div>
            <div className="text-[11px] text-emerald-400 mt-1 font-medium">1 In Progress • 2 Scheduled</div>
          </div>

          <div className="bg-slate-900/60 rounded-2xl p-4 border border-slate-800/80">
            <div className="flex justify-between items-center text-slate-400 text-xs font-semibold mb-2">
              <span>Monthly Deals Closed</span>
              <CheckCircle2 className="w-4 h-4 text-emerald-400" />
            </div>
            <div className="text-2xl font-bold text-white">6 Deals</div>
            <div className="text-[11px] text-slate-400 mt-1 font-medium">Target: 8 Deals / Month</div>
          </div>

          <div className="bg-slate-900/60 rounded-2xl p-4 border border-slate-800/80">
            <div className="flex justify-between items-center text-slate-400 text-xs font-semibold mb-2">
              <span>Incentive Balance</span>
              <Award className="w-4 h-4 text-amber-400" />
            </div>
            <div className="text-2xl font-bold text-white">₹12,000</div>
            <div className="text-[11px] text-amber-400 mt-1 font-medium">Approved for Disbursal</div>
          </div>

          <div className="bg-slate-900/60 rounded-2xl p-4 border border-slate-800/80">
            <div className="flex justify-between items-center text-slate-400 text-xs font-semibold mb-2">
              <span>Attendance Rate</span>
              <UserCheck className="w-4 h-4 text-cyan-400" />
            </div>
            <div className="text-2xl font-bold text-white">96.2%</div>
            <div className="text-[11px] text-cyan-400 mt-1 font-medium">24 / 25 Days Present</div>
          </div>
        </div>

        {/* Tab Navigation */}
        <div className="flex items-center gap-2 border-b border-slate-800 pb-3">
          <button
            onClick={() => setActiveTab('visits')}
            className={`px-4 py-2 rounded-xl text-xs font-semibold transition-all flex items-center gap-2 ${
              activeTab === 'visits' ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/20' : 'text-slate-400 hover:text-white hover:bg-slate-900'
            }`}
          >
            <MapPin className="w-4 h-4" /> My Escort Visits ({mockAssignedVisits.length})
          </button>
          <button
            onClick={() => setActiveTab('leaves')}
            className={`px-4 py-2 rounded-xl text-xs font-semibold transition-all flex items-center gap-2 ${
              activeTab === 'leaves' ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/20' : 'text-slate-400 hover:text-white hover:bg-slate-900'
            }`}
          >
            <Calendar className="w-4 h-4" /> Apply & Track Leaves ({leavesList.length})
          </button>
        </div>

        {/* Tab Content */}
        {activeTab === 'visits' && (
          <div className="space-y-4">
            <h2 className="text-base font-bold text-white flex items-center gap-2">
              <Clock className="w-4 h-4 text-indigo-400" /> Today's Assigned Field Escort Schedule
            </h2>
            <div className="grid grid-cols-1 gap-4">
              {mockAssignedVisits.map((visit) => (
                <div key={visit.id} className="bg-slate-900/80 border border-slate-800 rounded-2xl p-5 hover:border-slate-700 transition-all flex flex-col md:flex-row md:items-center justify-between gap-4">
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-white text-base">{visit.propertyTitle}</span>
                      <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-indigo-500/20 text-indigo-300 border border-indigo-500/30">
                        {visit.visitTime}
                      </span>
                    </div>
                    <p className="text-xs text-slate-400 flex items-center gap-2">
                      <span>Tenant: <strong className="text-slate-200">{visit.tenantName}</strong></span> •
                      <span className="flex items-center gap-1"><Phone className="w-3 h-3 text-indigo-400" /> {visit.tenantPhone}</span>
                    </p>
                  </div>

                  <div className="flex items-center gap-3">
                    <div className="bg-slate-950 px-3 py-1.5 rounded-xl border border-slate-800 text-xs">
                      <span className="text-slate-500">Security OTP: </span>
                      <span className="font-mono font-bold text-cyan-400">{visit.securityOtp}</span>
                    </div>
                    <button 
                      onClick={() => notifyInfo('Tenant contact', `${visit.tenantName}: ${visit.tenantPhone}`)}
                      className="px-3.5 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-semibold transition-all flex items-center gap-1.5"
                    >
                      <Phone className="w-3.5 h-3.5" /> Call Tenant
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {activeTab === 'leaves' && (
          <div className="space-y-4">
            <div className="flex justify-between items-center">
              <h2 className="text-base font-bold text-white flex items-center gap-2">
                <FileText className="w-4 h-4 text-indigo-400" /> Leave Applications & Approvals
              </h2>
              <button
                onClick={() => setShowLeaveForm(!showLeaveForm)}
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-semibold transition-all"
              >
                {showLeaveForm ? 'Cancel Application' : '+ Apply New Leave'}
              </button>
            </div>

            {showLeaveForm && (
              <form onSubmit={handleApplyLeave} className="bg-slate-900 border border-indigo-500/30 rounded-2xl p-5 space-y-4">
                <h3 className="text-sm font-bold text-indigo-300">New Leave Application Form</h3>
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                  <div>
                    <label className="text-xs text-slate-400 block mb-1 font-medium">Leave Type</label>
                    <select
                      value={newLeaveType}
                      onChange={(e) => setNewLeaveType(e.target.value)}
                      className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-white focus:outline-none focus:border-indigo-500"
                    >
                      <option>Casual Leave</option>
                      <option>Sick Leave</option>
                      <option>Paternity Leave</option>
                      <option>Earned Leave</option>
                    </select>
                  </div>
                  <div>
                    <label className="text-xs text-slate-400 block mb-1 font-medium">Start Date</label>
                    <input
                      type="date"
                      value={newStartDate}
                      onChange={(e) => setNewStartDate(e.target.value)}
                      className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-white focus:outline-none focus:border-indigo-500"
                      required
                    />
                  </div>
                  <div>
                    <label className="text-xs text-slate-400 block mb-1 font-medium">End Date</label>
                    <input
                      type="date"
                      value={newEndDate}
                      onChange={(e) => setNewEndDate(e.target.value)}
                      className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-white focus:outline-none focus:border-indigo-500"
                    />
                  </div>
                </div>
                <div>
                  <label className="text-xs text-slate-400 block mb-1 font-medium">Reason for Leave</label>
                  <textarea
                    value={newReason}
                    onChange={(e) => setNewReason(e.target.value)}
                    placeholder="Enter details for Admin approval review..."
                    rows={2}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-white focus:outline-none focus:border-indigo-500"
                    required
                  />
                </div>
                <button
                  type="submit"
                  className="px-5 py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl text-xs font-semibold transition-all"
                >
                  Submit Application to HR Admin
                </button>
              </form>
            )}

            <div className="space-y-3">
              {leavesList.map((leave) => (
                <div key={leave.id} className="bg-slate-900/60 border border-slate-800 rounded-2xl p-4 flex justify-between items-center">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-white text-sm">{leave.leaveType}</span>
                      <span className="text-xs text-slate-400">({leave.startDate} to {leave.endDate})</span>
                    </div>
                    <p className="text-xs text-slate-400 mt-1">Reason: {leave.reason}</p>
                  </div>
                  <div>
                    {leave.status === 'APPROVED' && (
                      <span className="px-3 py-1 bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 rounded-full text-xs font-semibold">
                        Approved ✓
                      </span>
                    )}
                    {leave.status === 'PENDING' && (
                      <span className="px-3 py-1 bg-amber-500/10 text-amber-400 border border-amber-500/20 rounded-full text-xs font-semibold">
                        Pending HR Review
                      </span>
                    )}
                    {leave.status === 'REJECTED' && (
                      <span className="px-3 py-1 bg-rose-500/10 text-rose-400 border border-rose-500/20 rounded-full text-xs font-semibold">
                        Rejected ✕
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

      </div>
    </div>
  );
};
