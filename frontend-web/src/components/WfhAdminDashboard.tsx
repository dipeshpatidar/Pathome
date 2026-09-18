import React, { useState } from 'react';
import {
  motion,
  AnimatePresence
} from 'framer-motion';
import {
  MessageSquare,
  CheckCircle2,
  Send,
  Search,
  UserCheck
} from 'lucide-react';
import { useNotification } from '../context/NotificationContext';

interface WfhAdminDashboardProps {
  activeTab: string;
}

const mockWhatsAppPosts = [
  { id: 1, sender: "Broker Ramesh Vijay Nagar", text: "3BHK fully furnished flat available in Vijay Nagar Scheme 54. Rent 24k. Deposit 48k. Owner +91 98260 11223.", sector: "Vijay Nagar", rent: 24000, status: "UNMAPPED" },
  { id: 2, sender: "Indore Rentals Group #4", text: "2BHK house near Bhawarkua main square. Students/Family welcome. Rent 16000. Owner +91 94250 88776.", sector: "Bhawarkua", rent: 16000, status: "MAPPED" },
];

const mockBrokers = [
  { id: 1, name: "Indore Prime Realty", contact: "+91 98930 12345", sector: "Vijay Nagar", activeListings: 14, verified: true },
  { id: 2, name: "Bhawarkua Student Housing", contact: "+91 98270 54321", sector: "Bhawarkua", activeListings: 9, verified: true },
  { id: 3, name: "Malwa Real Estate", contact: "+91 94253 99887", sector: "Nipania", activeListings: 5, verified: false },
];

const mockUnmappedLeads = [
  { id: "LEAD-901", name: "Ankit Sharma", phone: "+91 98765 00112", adSource: "Meta Lead Ad (Vijay Nagar 3BHK)", targetSector: "Vijay Nagar", assignedGroundBoy: null },
  { id: "LEAD-902", name: "Priya Patel", phone: "+91 98260 33445", adSource: "Meta Lead Ad (Bhawarkua House)", targetSector: "Bhawarkua", assignedGroundBoy: "Rahul Verma (On-Site)" },
];

const containerVariants = {
  hidden: { opacity: 0, y: 16 },
  visible: { opacity: 1, y: 0, transition: { staggerChildren: 0.08, duration: 0.4, ease: [0.16, 1, 0.3, 1] as const } }
};

const cardVariants = {
  hidden: { opacity: 0, y: 16, scale: 0.98 },
  visible: { opacity: 1, y: 0, scale: 1, transition: { duration: 0.35, ease: [0.16, 1, 0.3, 1] as const } }
};

export const WfhAdminDashboard: React.FC<WfhAdminDashboardProps> = ({ activeTab }) => {
  const { notifyInfo, notifySuccess } = useNotification();
  const [waText, setWaText] = useState('');
  const [posts, setPosts] = useState(mockWhatsAppPosts);
  const [leads, setLeads] = useState(mockUnmappedLeads);
  const [brokerSearch, setBrokerSearch] = useState('');

  const handleParseWhatsApp = (e: React.FormEvent) => {
    e.preventDefault();
    if (!waText.trim()) return;

    const newPost = {
      id: Date.now(),
      sender: "WFH Manual Paste",
      text: waText,
      sector: waText.toLowerCase().includes('bhawarkua') ? 'Bhawarkua' : 'Vijay Nagar',
      rent: 22000,
      status: "UNMAPPED"
    };

    setPosts([newPost, ...posts]);
    setWaText('');
    notifySuccess('Property message added', 'The property details were added to the review queue.');
  };

  const handleAssignLead = (leadId: string, boyName: string) => {
    setLeads(leads.map(l => l.id === leadId ? { ...l, assignedGroundBoy: boyName } : l));
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -16 }}
      transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
      className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-6"
    >
      
      {/* 1. TOP HEADER & METRICS */}
      <div className="bg-slate-900 text-white rounded-3xl p-6 sm:p-8 border border-slate-800 shadow-xl relative overflow-hidden">
        <div className="absolute top-0 right-0 w-96 h-96 bg-emerald-500/10 blur-3xl rounded-full pointer-events-none"></div>

        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 relative z-10">
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-black text-emerald-400 uppercase tracking-wider bg-emerald-950 px-3 py-1 rounded-full border border-emerald-800">
                WFH Operations Hub
              </span>
              <span className="text-xs text-slate-400 font-mono">Live Sync Active</span>
            </div>
            <h1 className="text-2xl sm:text-3xl font-black font-['Outfit'] mt-2 text-white">
              WhatsApp Data Ingestion & Broker Portal
            </h1>
            <p className="text-xs text-slate-400 mt-1">
              Process unmapped ad leads, parse Indore WhatsApp group pastes, and manage verified broker directory.
            </p>
          </div>

          <div className="flex items-center gap-3">
            <div className="bg-slate-950/80 px-4 py-2 rounded-2xl border border-slate-800 text-right font-mono">
              <span className="text-[10px] text-slate-400 block uppercase">Unmapped Leads</span>
              <span className="text-lg font-bold text-amber-400">12 Pending</span>
            </div>
            <div className="bg-slate-950/80 px-4 py-2 rounded-2xl border border-slate-800 text-right font-mono">
              <span className="text-[10px] text-slate-400 block uppercase">Ground Boys</span>
              <span className="text-lg font-bold text-emerald-400">2 Online</span>
            </div>
          </div>
        </div>
      </div>

      {/* 2. TAB CONTENT SWITCHER */}
      <AnimatePresence mode="wait">
        
        {/* TAB 1: WHATSAPP DROP */}
        {activeTab === 'whatsapp' && (
          <motion.div
            key="tab-whatsapp"
            variants={containerVariants}
            initial="hidden"
            animate="visible"
            exit="hidden"
            className="space-y-6"
          >
            {/* WhatsApp Paste Box */}
            <motion.div variants={cardVariants} className="bg-white rounded-3xl p-6 border border-slate-200 shadow-sm">
              <div className="flex items-center gap-2 mb-4">
                <div className="w-9 h-9 rounded-xl bg-emerald-50 text-emerald-600 flex items-center justify-center font-bold">
                  <MessageSquare className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="text-lg font-bold text-slate-900 font-['Outfit']">WhatsApp Raw Text Auto-Parser</h3>
                  <span className="text-xs text-slate-500">Paste unformatted property posts from WhatsApp broker groups</span>
                </div>
              </div>

              <form onSubmit={handleParseWhatsApp} className="space-y-4">
                <textarea
                  value={waText}
                  onChange={(e) => setWaText(e.target.value)}
                  placeholder="Paste WhatsApp text snippet here (e.g. 'Available 3BHK flat Vijay Nagar Scheme 54, Rent 22000, Deposit 44000, Call owner 98260XXXXX')..."
                  className="w-full bg-slate-50 border border-slate-200 rounded-2xl p-4 text-xs font-medium text-slate-900 focus:outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-600/10 min-h-[100px]"
                />
                <motion.button
                  whileHover={{ scale: 1.02 }}
                  whileTap={{ scale: 0.97 }}
                  type="submit"
                  className="bg-emerald-600 hover:bg-emerald-700 text-white font-extrabold text-xs py-3 px-6 rounded-xl shadow-md shadow-emerald-600/20 flex items-center gap-2 shimmer-glow"
                >
                  <Send className="w-4 h-4 text-white" />
                  <span className="text-white font-extrabold">Extract & Route to Ground Boy Queue</span>
                </motion.button>
              </form>
            </motion.div>

            {/* Parsed WhatsApp Posts List */}
            <motion.div variants={cardVariants} className="bg-white rounded-3xl p-6 border border-slate-200 shadow-sm">
              <h3 className="text-base font-bold text-slate-900 font-['Outfit'] mb-4">Recent Parsed Group Snippets</h3>
              <div className="space-y-3">
                {posts.map((post) => (
                  <motion.div key={post.id} whileHover={{ y: -2 }} className="bg-slate-50 p-4 rounded-2xl border border-slate-200/80 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-bold text-slate-900">{post.sender}</span>
                        <span className="text-[10px] font-bold text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">{post.sector}</span>
                      </div>
                      <p className="text-xs text-slate-600 font-mono">{post.text}</p>
                    </div>
                    <motion.button
                      whileHover={{ scale: 1.05 }}
                      whileTap={{ scale: 0.95 }}
                      onClick={() => notifySuccess('Listing published', `Listing ${post.id} has been verified and published.`)}
                      className="bg-slate-900 hover:bg-slate-800 text-white text-xs font-bold px-3.5 py-2 rounded-xl shrink-0"
                    >
                      Publish Listing
                    </motion.button>
                  </motion.div>
                ))}
              </div>
            </motion.div>
          </motion.div>
        )}

        {/* TAB 2: BROKER DIRECTORY */}
        {activeTab === 'broker' && (
          <motion.div
            key="tab-broker"
            variants={containerVariants}
            initial="hidden"
            animate="visible"
            exit="hidden"
            className="space-y-6"
          >
            <motion.div variants={cardVariants} className="bg-white rounded-3xl p-6 border border-slate-200 shadow-sm">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
                <div>
                  <h3 className="text-lg font-bold text-slate-900 font-['Outfit']">Indore Verified Broker Directory</h3>
                  <span className="text-xs text-slate-500">Track local broker profiles & zero-bypass direct inventory</span>
                </div>
                <div className="relative w-full sm:w-64">
                  <Search className="w-4 h-4 text-slate-400 absolute left-3 top-3" />
                  <input
                    type="text"
                    value={brokerSearch}
                    onChange={(e) => setBrokerSearch(e.target.value)}
                    placeholder="Search broker name..."
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-9 pr-3.5 py-2 text-xs font-semibold focus:outline-none focus:border-emerald-600"
                  />
                </div>
              </div>

              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead>
                    <tr className="border-b border-slate-100 text-slate-400 font-bold uppercase tracking-wider text-[10px]">
                      <th className="pb-3 px-3">Broker Firm</th>
                      <th className="pb-3 px-3">Primary Sector</th>
                      <th className="pb-3 px-3">Contact</th>
                      <th className="pb-3 px-3">Active Listings</th>
                      <th className="pb-3 px-3">Status</th>
                      <th className="pb-3 px-3 text-right">Action</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {mockBrokers.filter(b => b.name.toLowerCase().includes(brokerSearch.toLowerCase())).map((broker) => (
                      <tr key={broker.id} className="hover:bg-slate-50/80 transition-colors">
                        <td className="py-3.5 px-3 font-bold text-slate-900">{broker.name}</td>
                        <td className="py-3.5 px-3">
                          <span className="bg-slate-100 text-slate-700 px-2 py-0.5 rounded text-[11px] font-medium">{broker.sector}</span>
                        </td>
                        <td className="py-3.5 px-3 font-mono font-semibold text-slate-600">{broker.contact}</td>
                        <td className="py-3.5 px-3 font-bold text-emerald-700">{broker.activeListings} Properties</td>
                        <td className="py-3.5 px-3">
                          {broker.verified ? (
                            <span className="text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded text-[10px] font-bold border border-emerald-200 flex items-center gap-1 w-fit">
                              <CheckCircle2 className="w-3 h-3" /> Vetted
                            </span>
                          ) : (
                            <span className="text-amber-800 bg-amber-50 px-2 py-0.5 rounded text-[10px] font-bold border border-amber-200 w-fit block">Pending</span>
                          )}
                        </td>
                        <td className="py-3.5 px-3 text-right">
                          <button onClick={() => notifyInfo('Broker listings', `Showing listings from ${broker.name}.`)} className="text-xs font-bold text-emerald-600 hover:underline">
                            Inspect
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </motion.div>
          </motion.div>
        )}

        {/* TAB 3: UNMAPPED LEADS */}
        {(activeTab === 'leads' || activeTab === 'overview') && (
          <motion.div
            key="tab-leads"
            variants={containerVariants}
            initial="hidden"
            animate="visible"
            exit="hidden"
            className="space-y-6"
          >
            <motion.div variants={cardVariants} className="bg-white rounded-3xl p-6 border border-slate-200 shadow-sm">
              <div className="flex items-center justify-between mb-4 pb-3 border-b border-slate-100">
                <div>
                  <h3 className="text-lg font-bold text-slate-900 font-['Outfit']">Meta Lead Ads Unmapped Routing Queue</h3>
                  <span className="text-xs text-slate-500">Assign incoming Facebook/Instagram leads to Indore Ground Boys</span>
                </div>
                <span className="text-xs font-mono font-bold text-amber-800 bg-amber-50 px-3 py-1 rounded-full border border-amber-200">
                  Auto-Ingestion Webhook Active
                </span>
              </div>

              <div className="space-y-4">
                {leads.map((lead) => (
                  <motion.div key={lead.id} whileHover={{ y: -2 }} className="bg-slate-50 p-5 rounded-2xl border border-slate-200 flex flex-col md:flex-row md:items-center justify-between gap-4">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="font-mono font-bold text-xs text-slate-900">{lead.id}</span>
                        <span className="text-xs font-extrabold text-slate-900">{lead.name}</span>
                        <span className="text-[10px] font-mono text-slate-500">{lead.phone}</span>
                      </div>
                      <p className="text-xs text-slate-500">{lead.adSource} • Target: <span className="font-bold text-slate-800">{lead.targetSector}</span></p>
                    </div>

                    <div className="flex items-center gap-2">
                      {lead.assignedGroundBoy ? (
                        <span className="text-xs font-bold text-emerald-700 bg-emerald-50 px-3 py-1.5 rounded-xl border border-emerald-200 flex items-center gap-1.5">
                          <UserCheck className="w-4 h-4 text-emerald-600" /> {lead.assignedGroundBoy}
                        </span>
                      ) : (
                        <div className="flex items-center gap-2">
                          <motion.button
                            whileHover={{ scale: 1.05 }}
                            whileTap={{ scale: 0.95 }}
                            onClick={() => handleAssignLead(lead.id, "Rahul Verma (Vijay Nagar)")}
                            className="bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-bold px-3 py-1.5 rounded-xl shadow-xs"
                          >
                            Assign Rahul (Vijay Nagar)
                          </motion.button>
                          <motion.button
                            whileHover={{ scale: 1.05 }}
                            whileTap={{ scale: 0.95 }}
                            onClick={() => handleAssignLead(lead.id, "Vikram Singh (Bhawarkua)")}
                            className="bg-slate-900 hover:bg-slate-800 text-white text-xs font-bold px-3 py-1.5 rounded-xl shadow-xs"
                          >
                            Assign Vikram (Bhawarkua)
                          </motion.button>
                        </div>
                      )}
                    </div>
                  </motion.div>
                ))}
              </div>
            </motion.div>
          </motion.div>
        )}

      </AnimatePresence>

    </motion.div>
  );
};
