import React, { useCallback, useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { AlertTriangle, CheckCircle2, RefreshCw, ShieldCheck, Sparkles, XCircle } from 'lucide-react';
import { propertyService } from '../services/propertyService';
import { getErrorDetails, getErrorMessage } from '../services/apiError';
import { useNotification } from '../context/NotificationContext';

interface LearningField {
  fieldName: string;
  predictedValue?: string;
  reviewedValue: string;
  corrected: boolean;
  valueValid: boolean;
  trainingEligible: boolean;
  exclusionReason?: string;
}

interface LearningExample {
  id: string;
  propertyIndex: number;
  inputSource: string;
  rawPrompt: string;
  eligibleLabelCount: number;
  fields: LearningField[];
}

interface LearningStats {
  quarantined: number;
  pendingCuration: number;
  approvedTraining: number;
  approvedValidation: number;
  approvedHoldout: number;
  rejected: number;
}

const displayFieldName = (name: string): string => {
  const labels: Record<string, string> = {
    bhk: 'Layout', type: 'Property type', city: 'City', sector: 'Locality', colony: 'Society or colony',
    rentAmount: 'Monthly rent', brokerageVal: 'Brokerage', brokerageDays: 'Brokerage terms',
    areaSqFt: 'Area', depositVal: 'Security deposit', bathrooms: 'Bathrooms',
    furnishingStatus: 'Furnishing', possessionDate: 'Available from', state: 'State',
    pincode: 'Postal code', landmark: 'Landmark', vastuFacing: 'Facing', ownerName: 'Owner name',
    ownerPhone: 'Owner phone', status: 'Listing status', address: 'Address', amenities: 'Amenities'
  };
  return labels[name] || name;
};

export const ParserLearningReviewPanel: React.FC = () => {
  const { notifySuccess, showErrorDialog } = useNotification();
  const [stats, setStats] = useState<LearningStats | null>(null);
  const [examples, setExamples] = useState<LearningExample[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);

  const loadReviewQueue = useCallback(async () => {
    setIsLoading(true);
    try {
      const [summary, pending] = await Promise.all([
        propertyService.getParserLearningStats(),
        propertyService.getPendingParserLearningExamples()
      ]);
      setStats(summary);
      setExamples(Array.isArray(pending.content) ? pending.content : []);
    } catch (error) {
      showErrorDialog({
        title: 'Unable to load learning review',
        message: getErrorMessage(error, 'Please try again.'),
        details: getErrorDetails(error)
      });
    } finally {
      setIsLoading(false);
    }
  }, [showErrorDialog]);

  useEffect(() => {
    void loadReviewQueue();
  }, [loadReviewQueue]);

  const decide = async (example: LearningExample, approve: boolean) => {
    setBusyId(example.id);
    try {
      if (approve) {
        await propertyService.approveParserLearningExample(example.id);
        notifySuccess(
          'Example approved',
          'Only source-supported fields were added to the private learning dataset.',
          'The live parser has not changed.',
          'SYSTEM'
        );
      } else {
        await propertyService.rejectParserLearningExample(
          example.id,
          'Excluded by an administrator during learning review'
        );
        notifySuccess(
          'Example excluded',
          'This example will not influence future extraction.',
          'The published property is unchanged.',
          'SYSTEM'
        );
      }
      await loadReviewQueue();
    } catch (error) {
      showErrorDialog({
        title: approve ? 'Unable to approve this example' : 'Unable to exclude this example',
        message: getErrorMessage(error, 'Please review the example and try again.'),
        details: getErrorDetails(error)
      });
    } finally {
      setBusyId(null);
    }
  };

  const approvedCount = stats
    ? stats.approvedTraining + stats.approvedValidation + stats.approvedHoldout
    : 0;

  return (
    <motion.section
      initial={{ opacity: 0, y: 18 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: 'spring', stiffness: 470, damping: 24 }}
      className="space-y-5"
    >
      <div className="rounded-3xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <div className="mb-2 inline-flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-[11px] font-bold text-emerald-700">
              <ShieldCheck className="h-3.5 w-3.5" /> Private learning controls
            </div>
            <h2 className="text-xl font-black text-slate-900">Review extraction examples</h2>
            <p className="mt-1 max-w-2xl text-sm text-slate-500">
              Confirm which published examples are safe to teach the private extractor. Publishing alone never trains it.
            </p>
          </div>
          <motion.button
            whileHover={{ scale: 1.03 }}
            whileTap={{ scale: 0.97 }}
            onClick={() => void loadReviewQueue()}
            disabled={isLoading}
            className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-xs font-bold text-slate-700 shadow-sm hover:border-emerald-300 hover:text-emerald-700 disabled:opacity-60 sm:w-auto"
          >
            <RefreshCw className={`h-4 w-4 ${isLoading ? 'animate-spin' : ''}`} /> Refresh review queue
          </motion.button>
        </div>

        <div className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[
            ['Awaiting review', stats?.pendingCuration ?? 0, 'text-amber-700 bg-amber-50 border-amber-200'],
            ['Approved examples', approvedCount, 'text-emerald-700 bg-emerald-50 border-emerald-200'],
            ['Protected test examples', stats?.approvedHoldout ?? 0, 'text-indigo-700 bg-indigo-50 border-indigo-200'],
            ['Excluded examples', stats?.rejected ?? 0, 'text-slate-700 bg-slate-50 border-slate-200']
          ].map(([label, value, colors]) => (
            <div key={String(label)} className={`rounded-2xl border p-3 sm:p-4 ${colors}`}>
              <div className="text-2xl font-black">{value}</div>
              <div className="mt-1 text-[11px] font-bold">{label}</div>
            </div>
          ))}
        </div>
      </div>

      {isLoading ? (
        <div className="rounded-3xl border border-slate-200 bg-white p-6 text-center text-sm text-slate-500 sm:p-10">
          Loading examples awaiting review…
        </div>
      ) : examples.length === 0 ? (
        <div className="rounded-3xl border border-emerald-200 bg-emerald-50 p-6 text-center sm:p-10">
          <CheckCircle2 className="mx-auto h-8 w-8 text-emerald-600" />
          <h3 className="mt-3 font-black text-emerald-900">Learning review is up to date</h3>
          <p className="mt-1 text-sm text-emerald-700">New examples appear here only after a property is reviewed and published.</p>
        </div>
      ) : (
        <div className="space-y-4">
          {examples.map((example) => (
            <motion.article key={example.id} layout className="overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-sm">
              <div className="flex flex-col gap-3 border-b border-slate-100 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="rounded-lg bg-slate-900 px-2 py-1 text-[10px] font-black text-white">
                      Property {example.propertyIndex}
                    </span>
                    <span className="text-[11px] font-bold text-slate-500">
                      {example.inputSource === 'DICTATED'
                        ? 'Dictated details'
                        : example.inputSource === 'MIXED'
                          ? 'Typed and dictated details'
                          : 'Typed details'}
                    </span>
                  </div>
                  <p className="mt-2 whitespace-pre-wrap break-words text-sm leading-relaxed text-slate-700">{example.rawPrompt}</p>
                </div>
                <div className="shrink-0 rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-center">
                  <div className="text-lg font-black text-emerald-700">{example.eligibleLabelCount}</div>
                  <div className="text-[10px] font-bold text-emerald-700">safe fields</div>
                </div>
              </div>

              <div className="grid gap-2 p-5 sm:grid-cols-2 lg:grid-cols-3">
                {example.fields.map((field) => {
                  const state = field.trainingEligible
                    ? { icon: CheckCircle2, colors: 'border-emerald-200 bg-emerald-50 text-emerald-800', note: 'Supported by source text' }
                    : !field.valueValid
                      ? { icon: XCircle, colors: 'border-rose-200 bg-rose-50 text-rose-800', note: field.exclusionReason || 'Invalid value' }
                      : { icon: AlertTriangle, colors: 'border-amber-200 bg-amber-50 text-amber-800', note: field.exclusionReason || 'Audit only' };
                  const Icon = state.icon;
                  return (
                    <div key={field.fieldName} className={`rounded-2xl border p-3 ${state.colors}`}>
                      <div className="flex items-start justify-between gap-2">
                        <span className="text-[10px] font-black uppercase tracking-wide">{displayFieldName(field.fieldName)}</span>
                        <Icon className="h-4 w-4 shrink-0" />
                      </div>
                      <div className="mt-1 break-words text-sm font-bold">{field.reviewedValue}</div>
                      {field.corrected && field.predictedValue && (
                        <div className="mt-1 text-[10px] opacity-75">Previously read as {field.predictedValue}</div>
                      )}
                      <div className="mt-2 text-[10px] font-semibold opacity-80">{state.note}</div>
                    </div>
                  );
                })}
              </div>

              <div className="flex flex-col gap-3 border-t border-slate-100 bg-slate-50 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex items-center gap-2 text-xs text-slate-500">
                  <Sparkles className="h-4 w-4 text-indigo-500" /> Personal or unsupported fields are automatically excluded.
                </div>
                <div className="flex w-full flex-col gap-2 min-[480px]:w-auto min-[480px]:flex-row">
                  <button
                    onClick={() => void decide(example, false)}
                    disabled={busyId === example.id}
                    className="min-h-11 w-full rounded-xl border border-slate-300 bg-white px-4 py-2 text-xs font-bold text-slate-700 hover:border-rose-300 hover:text-rose-700 disabled:opacity-50 min-[480px]:w-auto"
                  >
                    Exclude example
                  </button>
                  <motion.button
                    whileHover={{ scale: 1.03 }}
                    whileTap={{ scale: 0.97 }}
                    onClick={() => void decide(example, true)}
                    disabled={busyId === example.id || example.eligibleLabelCount < 1}
                    className="min-h-11 w-full rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 px-4 py-2 text-xs font-black text-white shadow-md shadow-emerald-600/20 disabled:cursor-not-allowed disabled:opacity-50 min-[480px]:w-auto"
                  >
                    Approve safe fields for learning
                  </motion.button>
                </div>
              </div>
            </motion.article>
          ))}
        </div>
      )}
    </motion.section>
  );
};
