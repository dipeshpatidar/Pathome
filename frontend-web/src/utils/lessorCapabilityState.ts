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

export class LessorCapabilityTracker {
  private generation = 0;
  private readonly publish: (hasLessorProfile: boolean) => void;

  constructor(publish: (hasLessorProfile: boolean) => void) {
    this.publish = publish;
  }

  clear(): void {
    this.generation++;
    this.publish(false);
  }

  cancel(): void {
    this.generation++;
  }

  async refresh(
    identity: LessorSessionIdentity,
    load: () => Promise<{ userId: number; hasLessorProfile: boolean }>
  ): Promise<void> {
    const generation = ++this.generation;
    this.publish(false);
    try {
      const capability = await load();
      if (generation === this.generation &&
          readLessorSessionIdentity()?.key === identity.key &&
          capability.userId === identity.userId) {
        this.publish(capability.hasLessorProfile === true);
      }
    } catch {
      // The state was cleared before fetching; failure must not restore cached capability.
    }
  }
}
