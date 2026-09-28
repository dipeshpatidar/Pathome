import React from 'react';
import { Check } from 'lucide-react';

export type OnboardingStepKey = 'type' | 'basics' | 'pricing' | 'location' | 'media' | 'details' | 'preview';

interface StepMeta {
  key: OnboardingStepKey;
  stepNumber: number;
  shortLabel: string;
  mobileHeading: string;
}

const STEPS: StepMeta[] = [
  { key: 'type', stepNumber: 1, shortLabel: 'Property', mobileHeading: 'What kind of home is it?' },
  { key: 'basics', stepNumber: 2, shortLabel: 'Configuration', mobileHeading: 'Tell us about the home' },
  { key: 'pricing', stepNumber: 3, shortLabel: 'Pricing', mobileHeading: 'Set your rent' },
  { key: 'location', stepNumber: 4, shortLabel: 'Location', mobileHeading: 'Where is the home?' },
  { key: 'media', stepNumber: 5, shortLabel: 'Photos', mobileHeading: 'Show the home' },
  { key: 'details', stepNumber: 6, shortLabel: 'Availability', mobileHeading: 'Availability & features' },
  { key: 'preview', stepNumber: 7, shortLabel: 'Review', mobileHeading: 'Review your property' }
];

export interface LessorProgressBarProps {
  currentStep: OnboardingStepKey;
}

export const LessorProgressBar: React.FC<LessorProgressBarProps> = ({ currentStep }) => {
  const currentIndex = STEPS.findIndex(s => s.key === currentStep);
  const activeMeta = STEPS[currentIndex] || STEPS[0];
  const progressPercent = Math.min(100, Math.round(((currentIndex + 1) / STEPS.length) * 100));

  return (
    <div className="w-full">
      {/* DESKTOP GUIDED PROGRESS (>= 768px) */}
      <div className="hidden md:block">
        <div className="relative flex items-center justify-between">
          {/* Subtle Background Connector Line */}
          <div className="absolute left-0 top-1/2 -translate-y-1/2 h-0.5 w-full bg-slate-200" />
          {/* Active Progress Connector Fill */}
          <div
            className="absolute left-0 top-1/2 -translate-y-1/2 h-0.5 bg-emerald-600 transition-[width] duration-300 motion-reduce:transition-none"
            style={{ width: `${(currentIndex / (STEPS.length - 1)) * 100}%` }}
          />

          {STEPS.map((step, idx) => {
            const isCompleted = idx < currentIndex;
            const isCurrent = idx === currentIndex;
            return (
              <div key={step.key} className="relative z-10 flex flex-col items-center">
                <div
                  className={`flex h-6 w-6 items-center justify-center rounded-full text-[10px] font-bold transition-all duration-200 ${
                    isCompleted
                      ? 'bg-emerald-600 text-white shadow-xs'
                      : isCurrent
                      ? 'border-2 border-emerald-600 bg-white text-emerald-800 ring-4 ring-emerald-100 shadow-xs'
                      : 'border border-slate-300 bg-white text-slate-400'
                  }`}
                >
                  {isCompleted ? <Check className="h-3 w-3 stroke-[3]" /> : step.stepNumber}
                </div>
                <span
                  className={`mt-1.5 whitespace-nowrap text-[11px] font-semibold tracking-tight transition-colors ${
                    isCurrent
                      ? 'text-emerald-900 font-bold'
                      : isCompleted
                      ? 'text-slate-700'
                      : 'text-slate-400'
                  }`}
                >
                  {step.shortLabel}
                </span>
              </div>
            );
          })}
        </div>
      </div>

      {/* MOBILE COMPACT PROGRESS (< 768px) */}
      <div className="md:hidden">
        <div className="flex items-center justify-between text-xs">
          <span className="font-bold uppercase tracking-wider text-emerald-800">
            {activeMeta.stepNumber <= 6 ? `Step ${activeMeta.stepNumber} of 6` : 'Review & Submit'}
          </span>
          <span className="font-medium text-slate-500">{activeMeta.shortLabel}</span>
        </div>
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-slate-200">
          <div
            className="h-full bg-emerald-600 transition-[width] duration-300 motion-reduce:transition-none"
            style={{ width: `${progressPercent}%` }}
          />
        </div>
      </div>
    </div>
  );
};
