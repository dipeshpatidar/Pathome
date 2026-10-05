'use client'

import { Check, CircleAlert, LoaderCircle, ShieldCheck } from 'lucide-react'
import type { ReactNode } from 'react'
import styles from './pathome-submission-progress.module.css'

export type SubmissionProgressState = 'preparing' | 'processing-media' | 'saving-property' | 'finalising' | 'success' | 'error'

export type SubmissionProgressProps = {
  open: boolean
  state: SubmissionProgressState
  percent: number
  step: string
  detail?: string
  errorDetail?: string
  onViewListing?: () => void
  onGoToListings?: () => void
  onRetry?: () => void
  children?: ReactNode
}

const progressStates = new Set<SubmissionProgressState>(['preparing', 'processing-media', 'saving-property', 'finalising'])

function clampPercent(value: number) {
  if (!Number.isFinite(value)) return 0
  return Math.min(100, Math.max(0, Math.round(value)))
}

export function PathomeSubmissionProgress({
  open,
  state,
  percent,
  step,
  detail,
  errorDetail,
  onViewListing,
  onGoToListings,
  onRetry,
  children,
}: SubmissionProgressProps) {
  if (!open) return null

  const isProgress = progressStates.has(state)
  const isSuccess = state === 'success'
  const isError = state === 'error'
  const value = clampPercent(percent)
  const title = isSuccess ? 'Property submitted' : isError ? "We couldn't complete the submission" : 'Submitting your property'
  const description = isSuccess
    ? 'Your property has been submitted successfully.'
    : isError
      ? errorDetail ?? 'Your property details are still safe. You can try the submission again when you are ready.'
      : 'Your details are safe. Please keep this tab open while Pathome completes your submission.'

  return (
    <div className={styles.backdrop} role="presentation">
      <section
        className={styles.card}
        role={isProgress ? 'dialog' : 'dialog'}
        aria-modal="true"
        aria-labelledby="pathome-submission-title"
        aria-describedby="pathome-submission-description"
      >
        <div className={`${styles.icon} ${isSuccess ? styles.iconSuccess : isError ? styles.iconError : ''}`} aria-hidden="true">
          {isSuccess ? <Check size={24} strokeWidth={2.25} /> : isError ? <CircleAlert size={23} /> : <ShieldCheck size={23} />}
        </div>

        <div className={styles.eyebrow}>{isSuccess ? 'Submission complete' : isError ? 'Submission paused' : 'Pathome is working'}</div>
        <h2 id="pathome-submission-title" className={styles.title}>{title}</h2>
        <p id="pathome-submission-description" className={styles.description}>{description}</p>

        {isProgress && (
          <div className={styles.progressArea} aria-live="polite">
            <div className={styles.progressHeader}>
              <div>
                <p className={styles.step}>{step}</p>
                {detail && <p className={styles.detail}>{detail}</p>}
              </div>
              <div className={styles.percentage} aria-label={`${value}% complete`}>
                <strong>{value}%</strong>
                <span>complete</span>
              </div>
            </div>
            <div
              className={styles.progressTrack}
              role="progressbar"
              aria-label="Property submission progress"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={value}
            >
              <span className={styles.progressValue} style={{ width: `${value}%` }} />
            </div>
          </div>
        )}

        {isProgress && (
          <div className={styles.keepOpen}>
            <LoaderCircle size={15} aria-hidden="true" />
            <span>Please don&apos;t submit again while this is in progress.</span>
          </div>
        )}

        {isSuccess && (
          <div className={styles.actions}>
            {onViewListing && <button type="button" className={styles.primaryButton} onClick={onViewListing}>View your listing</button>}
            {onGoToListings && <button type="button" className={styles.secondaryButton} onClick={onGoToListings}>Go to your listings</button>}
          </div>
        )}

        {isError && onRetry && (
          <div className={styles.actions}>
            <button type="button" className={styles.primaryButton} onClick={onRetry}>Retry submission</button>
          </div>
        )}

        {children}
      </section>
    </div>
  )
}

export default PathomeSubmissionProgress
