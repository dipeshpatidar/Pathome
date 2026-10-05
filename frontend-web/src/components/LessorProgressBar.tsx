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

  return (
    <div className="lessor-v0-progress w-full" aria-label="Listing progress">
      <span>YOUR LISTING</span>
      <div className="lessor-v0-progress-steps">
        {STEPS.map((step, idx) => {
          const complete = idx < currentIndex;
          const current = idx === currentIndex;
          return <div key={step.key} className={`lessor-v0-progress-step ${complete ? 'is-complete' : current ? 'is-current' : ''}`} aria-current={current ? 'step' : undefined}>
            <span className="lessor-v0-progress-number">{complete ? <Check size={12} aria-hidden="true" /> : `0${step.stepNumber}`}</span>
            <small>{step.shortLabel}</small>
          </div>;
        })}
      </div>
      <p className="lessor-v0-progress-mobile">Step {currentIndex + 1} of {STEPS.length} · {STEPS[currentIndex]?.mobileHeading}</p>
    </div>
  );
};
