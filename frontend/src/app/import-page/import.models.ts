import { Job } from '../applications-board/application.models';

export type ImportInputType = 'URL' | 'TEXT';

export type ImportStatus =
  'PENDING' | 'PROCESSING' | 'READY_FOR_REVIEW' | 'NEEDS_INPUT' | 'FAILED' | 'COMPLETED';

export type ImportInput =
  | { readonly inputType: 'URL'; readonly url: string }
  | { readonly inputType: 'TEXT'; readonly text: string };

export interface ImportEvidence {
  readonly source: 'TEXT' | 'STRUCTURED_DATA';
  readonly excerpt?: string;
}

export type ImportProposal = Partial<Omit<Job, 'id'>> & {
  readonly evidence?: Readonly<Record<string, ImportEvidence>>;
};

export interface ImportFailure {
  readonly code: string;
  readonly message: string;
}

export interface ImportTask {
  readonly id: number;
  readonly inputType: ImportInputType;
  readonly status: ImportStatus;
  readonly expiresAt: string;
  readonly failure?: ImportFailure;
  readonly result?: ImportProposal;
  readonly jobId?: number;
}

export const ACTIVE_STATUSES: ReadonlySet<ImportStatus> = new Set(['PENDING', 'PROCESSING']);
