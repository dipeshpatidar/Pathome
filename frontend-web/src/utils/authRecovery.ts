export const DUPLICATE_EMAIL_CODE = 'EMAIL_ALREADY_REGISTERED';
export const DUPLICATE_EMAIL_MESSAGE = 'An account with this email already exists.';

export interface DuplicateEmailRecoveryState {
  authMode: 'LOGIN';
  email: string;
  password: string;
  fullName: string;
  errorMessage: string;
  duplicateEmailError: false;
}

export const isDuplicateEmailResponse = (
  errorData: { error?: string; message?: string } | null,
  responseText: string
): boolean =>
  errorData?.error === DUPLICATE_EMAIL_CODE ||
  errorData?.message === DUPLICATE_EMAIL_MESSAGE ||
  responseText.trim() === DUPLICATE_EMAIL_MESSAGE;

export const getDuplicateEmailRecoveryState = (email: string): DuplicateEmailRecoveryState => ({
  authMode: 'LOGIN',
  email,
  password: '',
  fullName: '',
  errorMessage: '',
  duplicateEmailError: false
});
