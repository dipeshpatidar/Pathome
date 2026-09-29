export interface LessorSessionIdentity {
  key: string;
  userId: number;
}

export function readLessorSessionIdentity(): LessorSessionIdentity | null {
  try {
    const token = localStorage.getItem('pathome_auth_token');
    const storedUser = localStorage.getItem('pathome_user');
    if (!token || !storedUser) return null;
    const userId = JSON.parse(storedUser)?.id;
    if (!Number.isSafeInteger(userId) || userId <= 0) return null;
    return { key: `${userId}:${token}`, userId };
  } catch {
    return null;
  }
}

export function isCurrentLessorSession(identity: LessorSessionIdentity): boolean {
  return readLessorSessionIdentity()?.key === identity.key;
}

export class LessorCapabilityTracker {
  private generation = 0;
  private readonly publish: (enabled: boolean | null | 'error') => void;

  constructor(publish: (enabled: boolean | null | 'error') => void) {
    this.publish = publish;
  }

  clear(): void {
    this.generation++;
    this.publish(null);
  }

  cancel(): void {
    this.generation++;
  }

  async refresh(
    identity: LessorSessionIdentity,
    load: () => Promise<{ userId: number; enabled: boolean }>
  ): Promise<void> {
    if (!isCurrentLessorSession(identity)) return;
    const generation = ++this.generation;
    this.publish(null);
    try {
      const capability = await load();
      if (generation === this.generation &&
          isCurrentLessorSession(identity)) {
        if (capability.userId === identity.userId) {
          this.publish(capability.enabled === true);
        } else {
          // A response for another account cannot establish this user's capability.
          this.publish('error');
        }
      }
    } catch {
      if (generation === this.generation && isCurrentLessorSession(identity)) {
        this.publish('error');
      }
    }
  }
}
