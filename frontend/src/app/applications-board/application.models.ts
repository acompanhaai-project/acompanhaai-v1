export const STATUS_LABELS = {
  SAVED: 'Salva',
  APPLIED: 'Candidatura realizada',
  SCREENING: 'Triagem',
  INTERVIEW: 'Entrevista',
  OFFER: 'Proposta',
  HIRED: 'Contratação',
  REJECTED: 'Rejeitada',
  WITHDRAWN: 'Desistência',
} as const;

export type ApplicationStatus = keyof typeof STATUS_LABELS;

export interface Application {
  readonly id: number;
  readonly job: Job;
  readonly status: ApplicationStatus;
  readonly appliedOn: string | null;
  readonly notes: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface Job {
  readonly id: number;
  readonly title: string;
  readonly companyName: string | null;
  readonly sourceUrl: string | null;
  readonly description: string | null;
  readonly location: string | null;
  readonly workMode: 'REMOTE' | 'HYBRID' | 'ONSITE' | null;
  readonly salary: number | null;
  readonly salaryCurrency: 'BRL' | null;
  readonly salaryPeriod: 'HOUR' | 'DAY' | 'WEEK' | 'MONTH' | 'YEAR' | null;
  readonly hardSkills: readonly string[];
  readonly softSkills: readonly string[];
}

export interface ApplicationPage {
  readonly content: readonly Application[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
}

export type ApplicationInput = Pick<Application, 'status' | 'appliedOn' | 'notes'> & {
  readonly job: Omit<Job, 'id'>;
};

export interface ApplicationHistoryEntry {
  readonly id: number;
  readonly fromStatus: ApplicationStatus | null;
  readonly toStatus: ApplicationStatus;
  readonly changedAt: string;
}
